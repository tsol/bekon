package com.wlya.core

import com.wlya.core.adapters.Registry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Core tunnel: ingest/send lifecycle, adapter management, crypto, handlers.
 *
 * Each runtime adapter has its own recv loop. Incoming TransportMessages go
 * through one ingest Channel so decrypt/seq/multipart stay single-threaded.
 */
class Tunnel(
    private val store: Store,
    var handlers: TunnelHandlers,
    config: TunnelConfig,
) {
    private data class InboundBatch(
        val adapterName: String,
        val messages: List<TransportMessage>,
    )

    private var _config: TunnelConfig = config
    val config: TunnelConfig get() = _config

    private val adapters = mutableListOf<BaseAdapter>()
    private var tunnelSeq = 0
    private val seenIds = ConcurrentHashMap.newKeySet<String>()
    private val seenIdOrder = ConcurrentLinkedDeque<String>()
    private val lastTransportSeqs = ConcurrentHashMap<String, Int>()
    private val writeSeqs = ConcurrentHashMap<String, Int>()
    private val multipart = MultipartSequencer()

    private var inbound = Channel<InboundBatch>(Channel.UNLIMITED)
    private var ingestJob: Job? = null
    private var adapterScope: CoroutineScope? = null
    private val recvJobs = ConcurrentHashMap<String, Job>()
    private val duty = AdapterDutyCoordinator()

    val running: Boolean get() = ingestJob?.isActive == true

    val adapterNames: List<String> get() = adapters.map { it.name }

    val adapterLogs: Map<String, List<String>>
        get() = adapters.associate { it.name to it.log.toList() }

    fun logSnapshot(limit: Int = 80): Map<String, List<String>> {
        val n = limit.coerceIn(1, LOG_KEEP_LAST)
        return adapters.associate { it.name to it.log.takeLast(n) }
    }

    val adapterList: List<BaseAdapter> get() = adapters.toList()

    fun getAdapters(): List<AdapterInstanceConfig> = _config.adapters.toList()

    fun isAdapterRuntime(adapterId: String): Boolean = runtimeIndex(adapterId) >= 0

    fun adapterListItems(): List<AdapterListItem> {
        syncDuty()
        return getAdapters().map { ac ->
        val running = isAdapterRuntime(ac.id)
        val snap = duty.snapshot(ac.id, running)
        AdapterListItem(
            type = ac.type,
            id = ac.id,
            label = ac.label,
            config = ac.config,
            enabled = ac.enabled,
            running = running,
            role = snap.role,
            effectiveRole = snap.effectiveRole,
            duty = snap.duty,
            nextPollAtMs = snap.nextPollAtMs,
            lastInboundAtMs = snap.lastInboundAtMs,
            idleUntilMs = snap.idleUntilMs,
            lastPollAtMs = snap.lastPollAtMs,
            lastPollError = snap.lastPollError,
            lastPollErrorAtMs = snap.lastPollErrorAtMs,
        )
        }
    }

    suspend fun clearAdapter(adapterId: String) {
        val adapter = adapters.find { it.name.endsWith(":$adapterId") || it.name == adapterId }
            ?: throw IllegalArgumentException("Adapter $adapterId not found")
        adapter.clearHistory()
    }

    fun resetAdapterSeq(adapterName: String) {
        lastTransportSeqs.remove(adapterName)
        writeSeqs.remove(adapterName)
    }

    suspend fun updateConfig(patch: Map<String, String>) {
        val channel = patch["channel"] ?: patch["seed"]
        channel?.let { _config = _config.copy(channel = it) }
        patch["secret"]?.let { _config = _config.copy(secret = it) }
        patch["label"]?.let { _config = _config.copy(label = it) }
        patch["autostart"]?.let { raw ->
            _config = _config.copy(autostart = raw.equals("true", ignoreCase = true) || raw == "1")
        }
        saveConfig()
    }

    suspend fun start(): String {
        if (ingestJob?.isActive == true) {
            if (adapters.isEmpty()) {
                for (ac in _config.adapters) {
                    if (!ac.enabled) continue
                    attachRuntime(ac)
                }
            }
            return _config.clientId
        }

        stopRecvAll()
        adapters.clear()
        duty.clear()
        lastTransportSeqs.clear()
        writeSeqs.clear()
        restoreSeen(_config.seenIds)

        if (_config.clientId.isEmpty()) {
            _config = _config.copy(clientId = UUID.randomUUID().toString().take(16))
            saveConfig()
        }

        tunnelSeq = _config.tunnelSeq
        for ((k, v) in _config.lastTransportSeqs) lastTransportSeqs[k] = v
        for ((k, v) in _config.writeSeqs) writeSeqs[k] = v

        inbound = Channel(Channel.UNLIMITED)
        val scope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
                println("[tunnel ${_config.id}] uncaught ${e.javaClass.simpleName}: ${e.message}")
            },
        )
        adapterScope = scope
        ingestJob = scope.launch {
            for (batch in inbound) ingestBatch(batch)
        }

        for (ac in _config.adapters) {
            if (!ac.enabled) continue
            attachRuntime(ac)
        }

        if (adapters.isEmpty() && _config.adapters.isEmpty()) {
            val defaultMock = Registry.createAdapter("mock", mapOf("id" to "default"))
            defaultMock.init(_config.channel)
            adapters.add(defaultMock)
            _config = _config.copy(
                adapters = _config.adapters + AdapterInstanceConfig("mock", "default", "Mock", emptyMap())
            )
            saveConfig()
            startRecv(defaultMock)
        }

        val validNames = (
            adapters.map { it.name } +
                _config.adapters.map { "${it.type}:${it.id}" }
            ).toSet()
        lastTransportSeqs.keys.retainAll { it in validNames }
        writeSeqs.keys.retainAll { it in validNames }

        persistCursors(running = true)
        saveConfig()

        return _config.clientId
    }

    suspend fun stop() {
        withContext(NonCancellable) {
            try {
                stopRecvAll()
                inbound.close()
                ingestJob?.cancelAndJoin()
            } finally {
                ingestJob = null
                adapterScope?.cancel()
                adapterScope = null
                persistCursors(running = false)
                try {
                    saveConfig()
                } catch (_: Throwable) {
                }
                adapters.clear()
                duty.clear()
            }
        }
    }

    suspend fun send(
        plaintext: String,
        attachments: List<Map<String, String>>? = null,
    ): Int {
        val tunnelMsg = TunnelMessage(
            id = UUID.randomUUID().toString(),
            seq = ++tunnelSeq,
            from = _config.clientId,
            text = plaintext,
            timestamp = System.currentTimeMillis(),
            attachments = if (attachments.isNullOrEmpty()) null else attachments.map {
                Attachment(
                    id = UUID.randomUUID().toString(),
                    name = it["name"] ?: "",
                    mimeType = it["mimeType"] ?: "",
                    size = it["data"]?.let { d -> (d.length * 3 + 3) / 4 } ?: 0,
                    data = it["data"] ?: "",
                )
            }
        )

        val json = Json.encodeToString(tunnelMsg)
        val cryptoResult = Crypto.encrypt(_config.cryptoSecret(), json)
        val checksum = Crc32.crc32(cryptoResult.cipher)

        rememberSeen(tunnelMsg.id)
        handlers.onMessage(tunnelMsg, "out")
        persistCursors(running = true)
        saveConfig()

        supervisorScope {
            val runtime = adapters.toList().filter { duty.isSendActive(adapterIdOf(it)) }
            val blocking = runtime.filter { !it.serialIo }
            val background = runtime.filter { it.serialIo }
            val waitOn = if (blocking.isNotEmpty()) blocking else background
            val fire = if (blocking.isNotEmpty()) background else emptyList()
            fire.forEach { adapter ->
                launch { deliverToAdapter(adapter, tunnelMsg.id, cryptoResult, checksum, json) }
            }
            waitOn.map { adapter ->
                async { deliverToAdapter(adapter, tunnelMsg.id, cryptoResult, checksum, json) }
            }.awaitAll()
        }

        persistCursors(running = true)
        saveConfig()
        return tunnelMsg.seq
    }

    private suspend fun deliverToAdapter(
        adapter: BaseAdapter,
        tunnelMsgId: String,
        cryptoResult: CryptoResult,
        checksum: String,
        json: String,
    ) {
        val lastRead = lastTransportSeqs[adapter.name] ?: 0
        var writeSeq = writeSeqs[adapter.name] ?: 0
        if (writeSeq < lastRead) {
            writeSeq = lastRead
            writeSeqs[adapter.name] = writeSeq
        }

        val parts = multipart.splitParts(cryptoResult.cipher, adapter.windowSize)
        val totalParts = if (parts.isEmpty()) 1 else parts.size
        val baseSeq = writeSeq + 1
        writeSeqs[adapter.name] = baseSeq + totalParts - 1
        adapter.logEvent(
            "send tunnelSeq=$tunnelSeq parts=$totalParts cipher=${cryptoResult.cipher.length} window=${adapter.windowSize}",
        )

        for (pi in 0 until totalParts) {
            val partContent = if (totalParts > 1) parts[pi].partData else cryptoResult.cipher
            val transportSeq = baseSeq + pi

            val tMsg = TransportMessage(
                id = UUID.randomUUID().toString(),
                from = _config.clientId,
                content = partContent,
                iv = cryptoResult.iv,
                crc = if (totalParts > 1) "" else checksum,
                timestamp = System.currentTimeMillis(),
                transportSeq = transportSeq,
                partOf = if (totalParts > 1) tunnelMsgId else null,
                partIndex = if (totalParts > 1) pi else null,
                totalParts = if (totalParts > 1) totalParts else null,
            )

            var sent = false
            var lastErr = ""
            for (attempt in 1..SEND_RETRY_MAX) {
                try {
                    adapter.lockedSend(tMsg)
                    sent = true
                    handlers.onDebug(
                        adapter.name, tMsg,
                        if (totalParts > 1) "[part ${pi + 1}/$totalParts ok ${partContent.length}b]" else json,
                    )
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastErr = "${e.javaClass.simpleName}: ${e.message}"
                    adapter.logEvent(
                        "ERROR send part ${pi + 1}/$totalParts seq=$transportSeq ${partContent.length}b " +
                            "attempt=$attempt/$SEND_RETRY_MAX $lastErr",
                    )
                    println("[tunnel ${_config.id}] ${adapter.name} send error: $lastErr")
                    handlers.onDebug(
                        adapter.name, tMsg,
                        "[error] part ${pi + 1}/$totalParts seq=$transportSeq ${partContent.length}b $lastErr",
                    )
                    if (attempt < SEND_RETRY_MAX) {
                        val wait = sendRetryDelayMs(attempt, e)
                        adapter.logEvent(
                            "retry send part ${pi + 1}/$totalParts seq=$transportSeq in ${wait}ms",
                        )
                        delay(wait)
                    }
                }
            }
            if (!sent) {
                val leftover = totalParts - pi
                adapter.logEvent(
                    "ERROR send gave up part ${pi + 1}/$totalParts seq=$transportSeq leftover=$leftover " +
                        "tunnelSeq=$tunnelSeq $lastErr",
                )
                return
            }
        }
    }

    companion object {
        const val SEND_RETRY_MAX = 24
        const val SEEN_IDS_CAP = 2000

        internal fun sendRetryDelayMs(failedAttempt: Int, err: Throwable): Long {
            val msg = err.message.orEmpty()
            val rateLimited = msg.contains("rate limit", ignoreCase = true) || msg.contains("429")
            val base = if (rateLimited) 500L else 250L
            val shift = (failedAttempt - 1).coerceIn(0, 4)
            return (base shl shift).coerceAtMost(8_000L)
        }
    }

    suspend fun advertiseAdapters(adapterIds: List<String>): Int {
        val wanted = adapterIds.toSet()
        val selected = _config.adapters.filter { it.id in wanted && it.type != "mock" }
        if (selected.isEmpty()) {
            throw IllegalArgumentException("No adapters selected")
        }
        return send(AdvertiseAdapters.encode(AdvertiseAdaptersPayload(adapters = selected)))
    }

    suspend fun upsertAdapter(ac: AdapterInstanceConfig) {
        if (ac.type == "mock" || ac.id.isBlank()) return
        if (Registry.get(ac.type) == null) return
        val idx = _config.adapters.indexOfFirst { it.id == ac.id }
        if (idx == -1) {
            addAdapter(ac)
            return
        }
        val old = _config.adapters[idx]
        replaceConfig(idx, ac)
        if (!ac.enabled) {
            dropRuntime(ac.id)
            return
        }
        if (!running) return
        val live = runtimeIndex(ac.id) >= 0
        val sameTransport = old.type == ac.type && old.enabled && ac.enabled
        if (live && sameTransport) return
        replaceOrAttachRuntime(ac)
    }

    suspend fun addAdapter(ac: AdapterInstanceConfig) {
        if (_config.adapters.any { it.id == ac.id }) {
            throw IllegalArgumentException("Adapter ${ac.id} already exists")
        }
        _config = _config.copy(adapters = _config.adapters + ac)
        saveConfig()
        if (running && ac.enabled) attachRuntime(ac)
    }

    suspend fun startAdapter(adapterId: String) {
        val idx = _config.adapters.indexOfFirst { it.id == adapterId }
        if (idx == -1) throw IllegalArgumentException("Adapter $adapterId not found")
        val ac = _config.adapters[idx].copy(enabled = true)
        replaceConfig(idx, ac)
        if (running) attachRuntime(ac)
    }

    suspend fun stopAdapter(adapterId: String) {
        val idx = _config.adapters.indexOfFirst { it.id == adapterId }
        if (idx == -1) throw IllegalArgumentException("Adapter $adapterId not found")
        replaceConfig(idx, _config.adapters[idx].copy(enabled = false))
        dropRuntime(adapterId)
    }

    private fun runtimeIndex(adapterId: String): Int =
        adapters.indexOfFirst { it.name.endsWith(":$adapterId") || it.name == adapterId }

    private suspend fun replaceConfig(idx: Int, ac: AdapterInstanceConfig) {
        _config = _config.copy(
            adapters = _config.adapters.mapIndexed { i, c -> if (i == idx) ac else c }
        )
        saveConfig()
    }

    private suspend fun attachRuntime(ac: AdapterInstanceConfig) {
        if (runtimeIndex(ac.id) >= 0) return
        val adapter = tryCreate(ac) ?: return
        adapters.add(adapter)
        syncDuty()
        startRecv(adapter)
    }

    private suspend fun replaceOrAttachRuntime(ac: AdapterInstanceConfig) {
        val adapter = tryCreate(ac) ?: return
        val runtimeIdx = runtimeIndex(ac.id)
        val old = _config.adapters.find { it.id == ac.id }
        val oldName = if (runtimeIdx >= 0) adapters[runtimeIdx].name else "${old?.type ?: ac.type}:${ac.id}"
        stopRecv(oldName)
        if (runtimeIdx >= 0) {
            adapters[runtimeIdx] = adapter
        } else {
            adapters.add(adapter)
        }
        syncDuty()
        startRecv(adapter)
    }

    private suspend fun tryCreate(ac: AdapterInstanceConfig): BaseAdapter? {
        return try {
            val adapter = Registry.createAdapter(
                ac.type,
                ac.config + mapOf(
                    "id" to ac.id,
                    "clientId" to ac.config["clientId"].orEmpty().ifBlank { _config.clientId },
                ),
            )
            adapter.init(_config.channel)
            adapter
        } catch (e: Throwable) {
            println("[tunnel ${_config.id}] adapter ${ac.id} ERROR init failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun dropRuntime(adapterId: String) {
        val runtimeIdx = runtimeIndex(adapterId)
        if (runtimeIdx >= 0) {
            stopRecv(adapters[runtimeIdx].name)
            adapters.removeAt(runtimeIdx)
        }
        duty.drop(adapterId)
        syncDuty()
        wakeActiveAdapters()
    }

    suspend fun removeAdapter(adapterId: String) {
        val idx = _config.adapters.indexOfFirst { it.id == adapterId }
        if (idx == -1) throw IllegalArgumentException("Adapter $adapterId not found")
        val runtimeIdx = runtimeIndex(adapterId)
        if (runtimeIdx >= 0) {
            val name = adapters[runtimeIdx].name
            stopRecv(name)
            lastTransportSeqs.remove(name)
            writeSeqs.remove(name)
            adapters.removeAt(runtimeIdx)
        } else {
            val ac = _config.adapters[idx]
            lastTransportSeqs.remove("${ac.type}:$adapterId")
            writeSeqs.remove("${ac.type}:$adapterId")
        }
        duty.drop(adapterId)
        _config = _config.copy(adapters = _config.adapters.filterIndexed { i, _ -> i != idx })
        saveConfig()
        syncDuty()
        wakeActiveAdapters()
    }

    suspend fun updateAdapter(adapterId: String, newConfig: Map<String, String>) {
        val idx = _config.adapters.indexOfFirst { it.id == adapterId }
        if (idx == -1) throw IllegalArgumentException("Adapter $adapterId not found")
        val ac = _config.adapters[idx].copy(config = newConfig)
        replaceConfig(idx, ac)
        if (!ac.enabled) {
            dropRuntime(adapterId)
            return
        }
        if (running) replaceOrAttachRuntime(ac)
    }

    private suspend fun startRecv(adapter: BaseAdapter) {
        val scope = adapterScope ?: return
        syncDuty()
        recvJobs.remove(adapter.name)?.cancelAndJoin()
        val parent = scope.coroutineContext[Job]
        recvJobs[adapter.name] = scope.launch(SupervisorJob(parent)) {
            val self = adapter.name
            while (isActive) {
                try {
                    val last = lastTransportSeqs[self] ?: 0
                    val msgs = adapter.lockedPoll(last)
                    duty.onPollOk(adapterIdOf(adapter))
                    if (msgs.isNotEmpty()) {
                        val sent = inbound.trySend(InboundBatch(self, msgs))
                        if (sent.isClosed) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    println("${adapter.name} poll error: ${e.message}")
                    adapter.logEvent("ERROR poll FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    val wakeIds = duty.onPollFailed(
                        adapterIdOf(adapter),
                        e.message ?: e.javaClass.simpleName,
                    )
                    for (id in wakeIds) {
                        val other = adapters.find { adapterIdOf(it) == id } ?: continue
                        if (other.name == self) continue
                        wakeRecv(other)
                    }
                }
                if (!isActive) break
                try {
                    duty.pollDelayMs(adapterIdOf(adapter))
                    delayUntilNextPoll(adapter)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    delay(adapter.pollIntervalMs.toLong().coerceAtLeast(250L))
                }
            }
        }
    }

    /**
     * Backup adapters sleep with a 1h delay. If they become effective-primary
     * (wlyaserver stopped), [delay] would otherwise keep them dark for the rest
     * of that hour. Re-check every second and poll immediately once active.
     */
    private suspend fun delayUntilNextPoll(adapter: BaseAdapter) {
        val id = adapterIdOf(adapter)
        while (currentCoroutineContext().isActive) {
            val snap = duty.snapshot(id, true)
            val now = System.currentTimeMillis()
            val wait = (snap.nextPollAtMs ?: now) - now
            if (wait <= 0) return
            // Active adapters (primary, or backups covering a 502) must keep
            // polling. Never sit on a leftover 1h backup sleep after a blip.
            if (duty.isActive(id) && wait > 5_000) return
            delay(wait.coerceAtMost(1_000L).coerceAtLeast(1L))
        }
    }

    private fun wakeRecv(adapter: BaseAdapter) {
        adapterScope?.launch { startRecv(adapter) }
    }

    private fun wakeActiveAdapters() {
        for (adapter in adapters.toList()) {
            if (duty.isActive(adapterIdOf(adapter))) wakeRecv(adapter)
        }
    }

    private fun syncDuty() {
        duty.sync(_config.adapters, adapters.map { adapterIdOf(it) }.toSet())
    }

    private fun adapterIdOf(adapter: BaseAdapter): String = adapterIdFromName(adapter.name)

    private fun adapterIdFromName(name: String): String {
        val i = name.indexOf(':')
        return if (i < 0) name else name.substring(i + 1)
    }

    private suspend fun stopRecv(adapterName: String) {
        recvJobs.remove(adapterName)?.cancelAndJoin()
    }

    private suspend fun stopRecvAll() {
        val jobs = recvJobs.values.toList()
        recvJobs.clear()
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }

    private suspend fun ingestBatch(batch: InboundBatch) {
        var highWater = lastTransportSeqs[batch.adapterName] ?: 0
        for (tMsg in batch.messages) {
            var content = tMsg.content

            if (tMsg.totalParts != null && tMsg.totalParts > 1 && tMsg.partOf != null) {
                val assembled = multipart.addPart(
                    tMsg.partOf, tMsg.partIndex ?: 0, tMsg.totalParts, content,
                )
                if (assembled == null) {
                    handlers.onDebug(batch.adapterName, tMsg, "[part ${(tMsg.partIndex ?: 0) + 1}/${tMsg.totalParts}]")
                    if (tMsg.transportSeq > highWater) {
                        highWater = tMsg.transportSeq
                        lastTransportSeqs[batch.adapterName] = highWater
                    }
                    continue
                }
                content = assembled
            }

            if ((tMsg.totalParts ?: 1) <= 1 && Crc32.crc32(content) != tMsg.crc) {
                handlers.onMessage(
                    TunnelMessage(
                        id = "", seq = 0, from = "",
                        text = "⚠️ corrupt transport (seq=${tMsg.transportSeq})",
                        timestamp = System.currentTimeMillis(),
                    ), "in"
                )
                continue
            }

            val decrypted: String
            try {
                decrypted = Crypto.decrypt(_config.cryptoSecret(), content, tMsg.iv)
            } catch (e: Throwable) {
                adapters.find { it.name == batch.adapterName }
                    ?.logEvent("decrypt FAILED ts=${tMsg.transportSeq} ${e.javaClass.simpleName}")
                if (tMsg.transportSeq > highWater) {
                    highWater = tMsg.transportSeq
                    lastTransportSeqs[batch.adapterName] = highWater
                }
                continue
            }

            val tunnelMsg: TunnelMessage
            try {
                tunnelMsg = Json.decodeFromString(decrypted)
            } catch (e: Throwable) {
                adapters.find { it.name == batch.adapterName }
                    ?.logEvent("decode FAILED ts=${tMsg.transportSeq} ${e.javaClass.simpleName}")
                continue
            }

            val duplicate = !rememberSeen(tunnelMsg.id)
            if (tMsg.transportSeq > highWater) {
                highWater = tMsg.transportSeq
                lastTransportSeqs[batch.adapterName] = highWater
            }
            if (duplicate) continue

            val becameActive = duty.onForeignInbound(adapterIdFromName(batch.adapterName))
            if (becameActive) {
                adapters.find { it.name == batch.adapterName }?.let { wakeRecv(it) }
            }

            try {
                handlers.onMessage(tunnelMsg, "in")
                handlers.onDebug(batch.adapterName, tMsg, decrypted)
            } catch (e: Throwable) {
                println("${batch.adapterName} handler error: ${e.message}")
                adapters.find { it.name == batch.adapterName }
                    ?.logEvent("ERROR handler ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        persistCursors(running = true)
        saveConfig()
    }

    private fun persistCursors(running: Boolean) {
        _config = _config.copy(
            running = running,
            tunnelSeq = tunnelSeq,
            lastTransportSeqs = lastTransportSeqs.toMap(),
            writeSeqs = writeSeqs.toMap(),
            seenIds = seenIdOrder.toList(),
        )
    }

    private fun restoreSeen(ids: List<String>) {
        seenIds.clear()
        seenIdOrder.clear()
        for (id in ids) rememberSeen(id)
    }

    /** @return true if [id] is newly recorded. */
    private fun rememberSeen(id: String): Boolean {
        if (id.isBlank() || !seenIds.add(id)) return false
        seenIdOrder.addLast(id)
        while (seenIdOrder.size > SEEN_IDS_CAP) {
            val old = seenIdOrder.pollFirst() ?: break
            seenIds.remove(old)
        }
        return true
    }

    private suspend fun saveConfig() {
        store.setObject("tunnel:${_config.id}", _config)
    }
}
