package com.wlya.core

import com.wlya.core.adapters.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class TunnelTest {

    @BeforeEach
    fun setup() {
        registerAllAdapters()
    }

    @Test
    fun `MockAdapter roundtrip`() = runBlocking {
        val storePath = File.createTempFile("wlya-local-test", ".json").apply { deleteOnExit() }.absolutePath
        val channel = "test-seed-42"

        val msgs1 = mutableListOf<TunnelMessage>()
        val tunnel1 = Tunnel(
            store = MemoryStore(),
            handlers = object : TunnelHandlers {
                override fun onMessage(msg: TunnelMessage, direction: String) {
                    if (msg.text.startsWith("⚠️")) return
                    msgs1.add(msg)
                }
                override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
            },
            config = TunnelConfig(
                id = "t1",
                label = "Test 1",
                channel = channel,
                clientId = "client-1",
                adapters = listOf(AdapterInstanceConfig("mock", "m1", "Mock", mapOf("storePath" to storePath))),
            ),
        )

        val msgs2 = mutableListOf<TunnelMessage>()
        val tunnel2 = Tunnel(
            store = MemoryStore(),
            handlers = object : TunnelHandlers {
                override fun onMessage(msg: TunnelMessage, direction: String) {
                    if (msg.text.startsWith("⚠️")) return
                    msgs2.add(msg)
                }
                override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
            },
            config = TunnelConfig(
                id = "t2",
                label = "Test 2",
                channel = channel,
                clientId = "client-2",
                adapters = listOf(AdapterInstanceConfig("mock", "m2", "Mock", mapOf("storePath" to storePath))),
            ),
        )

        tunnel1.start()
        tunnel2.start()
        delay(2500)

        tunnel1.send("hello from client-1")
        delay(2500)
        tunnel2.send("hello from client-2")
        delay(2500)

        println("msgs1: ${msgs1.map { it.text }}")
        println("msgs2: ${msgs2.map { it.text }}")

        assertTrue(msgs1.any { it.text.contains("hello from client-2") },
            "tunnel1 should receive msg from tunnel2: $msgs1")
        assertTrue(msgs2.any { it.text.contains("hello from client-1") },
            "tunnel2 should receive msg from tunnel1: $msgs2")

        tunnel1.stop()
        tunnel2.stop()
    }

    @Test
    fun `Multi-instance with same channel shares history`() = runBlocking {
        val storePath = File.createTempFile("wlya-local-test2", ".json").apply { deleteOnExit() }.absolutePath
        val channel = "shared-seed"

        val tunnel1 = createTunnel("t3", "c3", channel, storePath, MemoryStore())
        val tunnel2 = createTunnel("t4", "c4", channel, storePath, MemoryStore())

        tunnel1.start()
        tunnel2.start()
        delay(2500)

        tunnel1.send("first message")
        delay(2500)
        tunnel2.send("second message")
        delay(2500)

        val shared = LocalStore(storePath)
        val msgs = shared.poll(0)
        println("Shared store messages: ${msgs.size}")
        assertTrue(msgs.size >= 2, "Expected at least 2 messages in shared store")

        tunnel1.stop()
        tunnel2.stop()
    }

    @Test
    fun `Tunnel persistence via FileStore`() = runBlocking {
        val tempFile = File.createTempFile("wlya-store-test", ".json").apply { deleteOnExit() }
        val store = FileStore(tempFile.absolutePath)

        val channel = "persist-seed"
        val tunnel = Tunnel(
            store = store,
            handlers = object : TunnelHandlers {
                override fun onMessage(msg: TunnelMessage, direction: String) {}
                override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
            },
            config = TunnelConfig(
                id = "t-persist",
                label = "Persist Test",
                channel = channel,
                clientId = "c-p",
            ),
        )

        tunnel.start()
        tunnel.send("test msg")
        tunnel.stop()

        val saved = store.getObject<TunnelConfig>("tunnel:t-persist")
        assertNotNull(saved)
        assertEquals("c-p", saved.clientId)
        assertEquals(channel, saved.channel)
        assertEquals("", saved.secret)
        assertTrue(saved.tunnelSeq >= 1)
        assertFalse(saved.running)
    }

    @Test
    fun `stop start does not replay already ingested inbound`() = runBlocking {
        val storePath = File.createTempFile("wlya-noreplay-msgs", ".json").apply { deleteOnExit() }.absolutePath
        val persist = File.createTempFile("wlya-noreplay-cfg", ".json").apply { deleteOnExit() }
        val store = FileStore(persist.absolutePath)
        val channel = "noreplay-seed"
        val incoming = mutableListOf<String>()

        val recv = Tunnel(
            store = store,
            handlers = object : TunnelHandlers {
                override fun onMessage(msg: TunnelMessage, direction: String) {
                    if (direction == "in" && !msg.text.startsWith("⚠️")) incoming.add(msg.text)
                }
                override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
            },
            config = TunnelConfig(
                id = "t-noreplay",
                label = "recv",
                channel = channel,
                clientId = "recv-1",
                adapters = listOf(
                    AdapterInstanceConfig("mock", "m-recv", "Mock", mapOf("storePath" to storePath)),
                ),
            ),
        )
        val send = createTunnel("t-noreplay-send", "send-1", channel, storePath, MemoryStore())

        recv.start()
        send.start()
        delay(400)
        send.send("tap-once")
        delay(2500)
        assertEquals(1, incoming.count { it == "tap-once" }, "first ingest: $incoming")

        recv.updateAdapter("m-recv", mapOf("storePath" to storePath))
        delay(2500)
        assertEquals(1, incoming.count { it == "tap-once" }, "after upsert while running: $incoming")

        recv.stop()
        recv.start()
        delay(2500)
        assertEquals(1, incoming.count { it == "tap-once" }, "after stop/start same instance: $incoming")

        recv.stop()
        val saved = store.getObject<TunnelConfig>("tunnel:t-noreplay")
        assertNotNull(saved)
        assertTrue(saved.seenIds.isNotEmpty() || saved.lastTransportSeqs.isNotEmpty())

        val recv2 = Tunnel(
            store = store,
            handlers = object : TunnelHandlers {
                override fun onMessage(msg: TunnelMessage, direction: String) {
                    if (direction == "in" && !msg.text.startsWith("⚠️")) incoming.add(msg.text)
                }
                override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
            },
            config = saved,
        )
        recv2.start()
        delay(2500)
        assertEquals(1, incoming.count { it == "tap-once" }, "after reload from store: $incoming")

        recv2.stop()
        send.stop()
    }

    @Test
    fun `legacy seed JSON loads as channel`() {
        val json = """{"id":"t","label":"L","seed":"old-seed","clientId":"c"}"""
        val cfg = kotlinx.serialization.json.Json.decodeFromString<TunnelConfig>(json)
        assertEquals("old-seed", cfg.channel)
        assertEquals("old-seed", cfg.cryptoSecret())
    }

    @Test
    fun `explicit secret used for crypto not channel`() {
        val cfg = TunnelConfig(id = "t", label = "L", channel = "chan", secret = "sekrit", clientId = "c")
        assertEquals("sekrit", cfg.cryptoSecret())
        val enc = Crypto.encrypt(cfg.cryptoSecret(), "hello")
        assertEquals("hello", Crypto.decrypt("sekrit", enc.cipher, enc.iv))
        var failed = false
        try {
            Crypto.decrypt("chan", enc.cipher, enc.iv)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `recv loop keeps polling after HTTP 502`() = runBlocking {
        registerAllAdapters()
        val primary = FlakyAdapter("p", failTimes = 3)
        val backup = FlakyAdapter("b", failTimes = 0)
        Registry.register(
            AdapterManifest(
                type = "flaky",
                label = "Flaky",
                factory = { cfg ->
                    when (cfg["id"] as? String) {
                        "p" -> primary
                        else -> backup
                    }
                },
            )
        )

        val tunnel = Tunnel(
            store = MemoryStore(),
            handlers = object : TunnelHandlers {
                override fun onMessage(msg: TunnelMessage, direction: String) {}
                override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
            },
            config = TunnelConfig(
                id = "t-flaky",
                label = "Flaky",
                channel = "flaky-chan",
                clientId = "c-flaky",
                adapters = listOf(
                    AdapterInstanceConfig(
                        "flaky", "p", "Primary",
                        mapOf("role" to "primary", "pollIntervalMs" to "250"),
                    ),
                    AdapterInstanceConfig(
                        "flaky", "b", "Backup",
                        mapOf("role" to "backup", "pollIntervalMs" to "250", "sleepPollMs" to "3600000"),
                    ),
                ),
            ),
        )

        tunnel.start()
        delay(2_500)
        assertTrue(tunnel.running, "tunnel must stay running after poll 502s")
        assertTrue(primary.okPolls >= 2, "primary must resume polling after 502 (ok=${primary.okPolls} fails=${primary.failPolls})")
        assertTrue(primary.failPolls >= 3, "expected the injected 502s, got ${primary.failPolls}")
        assertTrue(
            primary.log.any { it.contains("HTTP 502") },
            "primary log should keep the 502, not freeze the loop: ${primary.log.takeLast(5)}",
        )
        tunnel.stop()
    }

    @Test
    fun `send retry delay backs off and treats 429 longer`() {
        val rate = IllegalStateException("WLYA-Server rate limit")
        val other = IllegalStateException("WLYA-Server send HTTP 502: bad gateway")
        assertEquals(500L, Tunnel.sendRetryDelayMs(1, rate))
        assertEquals(1000L, Tunnel.sendRetryDelayMs(2, rate))
        assertEquals(8000L, Tunnel.sendRetryDelayMs(8, rate))
        assertEquals(250L, Tunnel.sendRetryDelayMs(1, other))
        assertEquals(500L, Tunnel.sendRetryDelayMs(2, other))
    }

    @Test
    fun `send retries after adapter errors`() = runBlocking {
        registerAllAdapters()
        val adapter = FlakySendAdapter("s", failTimes = 2)
        Registry.register(
            AdapterManifest(
                type = "flaky-send",
                label = "FlakySend",
                factory = { adapter },
            )
        )
        val tunnel = Tunnel(
            store = MemoryStore(),
            handlers = object : TunnelHandlers {
                override fun onMessage(msg: TunnelMessage, direction: String) {}
                override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
            },
            config = TunnelConfig(
                id = "t-send-retry",
                label = "Send retry",
                channel = "send-retry-chan",
                clientId = "c-send-retry",
                adapters = listOf(
                    AdapterInstanceConfig(
                        "flaky-send", "s", "Primary",
                        mapOf("role" to "primary", "pollIntervalMs" to "250"),
                    ),
                ),
            ),
        )
        tunnel.start()
        tunnel.send("hello retry")
        assertEquals(3, adapter.sendAttempts)
        assertEquals(1, adapter.okSends)
        assertTrue(adapter.log.any { it.contains("retry send part") }, adapter.log.takeLast(6).toString())
        tunnel.stop()
    }
}

private class FlakyAdapter(
    instanceId: String,
    private val failTimes: Int,
) : BaseAdapter() {
    override val name: String = "flaky:$instanceId"
    override val pollIntervalMs: Int = 250
    override val serialIo: Boolean = false

    @Volatile var failPolls = 0
    @Volatile var okPolls = 0

    override suspend fun init(channel: String) {
        logEvent("flaky ready failTimes=$failTimes")
    }

    override suspend fun poll(lastTransportSeq: Int): List<TransportMessage> {
        if (failPolls < failTimes) {
            failPolls++
            throw IllegalStateException("WLYA-Server poll HTTP 502: bad gateway")
        }
        okPolls++
        return emptyList()
    }

    override suspend fun send(msg: TransportMessage) {}
    override suspend fun clearHistory() {}
}

private class FlakySendAdapter(
    instanceId: String,
    private val failTimes: Int,
) : BaseAdapter() {
    override val name: String = "flaky-send:$instanceId"
    override val pollIntervalMs: Int = 250
    override val serialIo: Boolean = false

    @Volatile var sendAttempts = 0
    @Volatile var okSends = 0

    override suspend fun init(channel: String) {
        logEvent("flaky-send ready failTimes=$failTimes")
    }

    override suspend fun poll(lastTransportSeq: Int): List<TransportMessage> = emptyList()

    override suspend fun send(msg: TransportMessage) {
        sendAttempts++
        if (okSends == 0 && sendAttempts <= failTimes) {
            throw IllegalStateException("WLYA-Server rate limit")
        }
        okSends++
    }

    override suspend fun clearHistory() {}
}

// ── helpers ──

private fun createTunnel(
    id: String,
    clientId: String,
    channel: String,
    storePath: String,
    store: Store,
): Tunnel = Tunnel(
    store = store,
    handlers = object : TunnelHandlers {
        override fun onMessage(msg: TunnelMessage, direction: String) {}
        override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
    },
    config = TunnelConfig(
        id = id,
        label = "test",
        channel = channel,
        clientId = clientId,
        adapters = listOf(AdapterInstanceConfig("mock", "m-$id", "Mock", mapOf("storePath" to storePath))),
    ),
)
