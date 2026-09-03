package pro.potoki.bekon.wlya

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import pro.potoki.bekon.SetupActivity
import pro.potoki.bekon.intent.ApkSelfUpdate
import com.wlya.core.AndroidStore
import com.wlya.core.AdvertiseAdapters
import com.wlya.core.AppManager
import com.wlya.core.TunnelMessage
import com.wlya.core.TunnelView
import com.wlya.core.TransportMessage
import com.wlya.core.TunnelHandlers
import com.wlya.core.adapters.Registry
import com.wlya.core.adapters.registerAllAdapters
import com.wlya.core.AdapterInstanceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages the WLYA tunnel lifecycle inside phone-agent.
 * Handles PING → PONG auto-reply and command dispatch.
 */
class WlyaTunnelManager(context: Context) {

    companion object {
        private const val TAG = "WlyaTunnel"
        const val PREF_RUNNING = "wlya_running"
        const val PREFS_NAME = "wlya_prefs"
        private const val EXTRA_LOG_CAP = 400
        private const val LINE_MAX = 96
        private const val ADAPTER_LINE_MAX = 240

        fun compactLine(raw: String, max: Int = LINE_MAX): String {
            val one = raw.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
            return if (one.length <= max) one else one.take(max - 3) + "..."
        }

        private val TEXT_FIELD = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
    }

    private val ctx = context.applicationContext
    private val store = AndroidStore(ctx, "wlya_phone")
    private val appManager = AppManager(store)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var activeView: TunnelView? = null
        private set
    @Volatile var lastReceived: String? = null
        private set

    /** Application-layer command handler (set before loadExisting). */
    @Volatile var commandHandler: ((String) -> String)? = null

    /** Fired on the main thread after advertised adapters are upserted and persisted. */
    @Volatile var onAdvertisedAdaptersApplied: ((List<AdapterInstanceConfig>) -> Unit)? = null

    private val extraLog = ConcurrentLinkedQueue<String>()

    private fun pushLog(adapter: String, kind: String, payload: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        extraLog.add(compactLine("[$ts] [$adapter] $kind $payload"))
        while (extraLog.size > EXTRA_LOG_CAP) extraLog.poll()
    }

    fun adapterLogNames(): List<String> {
        val fromTunnel = activeView?.tunnel?.getAdapters()?.map { "${it.type}:${it.id}" } ?: emptyList()
        val fromLogs = activeView?.tunnel?.adapterLogs?.keys?.toList() ?: emptyList()
        return (fromTunnel + fromLogs).distinct().sorted()
    }

    fun adapterListItems(): List<com.wlya.core.AdapterListItem> =
        activeView?.tunnel?.adapterListItems() ?: emptyList()

    /** Newest-last adapter/transport lines (poll, send, received). */
    fun snapshotAdapterLogLines(): List<String> {
        val adapterLines = activeView?.tunnel?.adapterLogs?.values?.flatten() ?: emptyList()
        return (adapterLines + extraLog.toList()).map { compactLine(it, ADAPTER_LINE_MAX) }
    }

    /** Newest-last application messages (in/out plaintext). */
    fun snapshotMessageLines(): List<String> {
        val msgs = activeView?.getMessages().orEmpty().takeLast(80).map { m ->
            val arrow = if (m.direction == "in") "←" else "→"
            compactLine("$arrow #${m.seq} ${summarizePlaintext(m.plaintext, m.direction)}")
        }
        return msgs
    }

    fun activeTunnelLogSnapshot(limit: Int = 80): List<String> {
        val snap = activeView?.tunnel?.logSnapshot(limit) ?: return emptyList()
        return snap.flatMap { (name, lines) -> lines.map { "[$name] $it" } }
    }

    fun clearLogs() {
        extraLog.clear()
        activeView?.clearMessages()
        activeView?.tunnel?.adapterList?.forEach { it.log.clear() }
    }

    init {
        registerAllAdapters()
    }

    /** Persist running state to SharedPreferences. */
    private fun persistRunning(running: Boolean) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_RUNNING, running).apply()
    }

    suspend fun loadExistingSuspend() {
        appManager.ensureInit()
        val list = appManager.list()
        val wanted = channelFromPrefs()
        val candidate = if (wanted.isNotBlank()) {
            list.filter { it.channel == wanted }.maxByOrNull { it.id }
        } else {
            null
        }
        if (candidate != null) {
            activeView = appManager.get(candidate.id)
            Log.i(TAG, "Loaded existing tunnel: ${candidate.id} channel=${candidate.channel}")
        }
    }

    /** Non-blocking load. Call before createTunnel if you want to resume. */
    fun loadExisting() {
        scope.launch {
            try {
                loadExistingSuspend()
            } catch (e: Exception) {
                Log.e(TAG, "loadExisting failed: ${e.message}", e)
            }
        }
    }

    /** Create and start a tunnel with one or more plug-in adapters. */
    fun createTunnel(
        channel: String,
        secret: String,
        adapters: List<AdapterInstanceConfig>,
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onMessage: ((TunnelMessage) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                appManager.ensureInit()
                if (adapters.isEmpty()) {
                    throw IllegalArgumentException("At least one adapter is required")
                }

                val desired = normalizeAdapterConfigs(adapters)
                val view = openChannelTunnel(channel, secret, desired)
                applyDesiredAdapters(view, desired)

                installHandlers(view, onMessage)
                val alreadyRunning = view.tunnel.running
                if (!alreadyRunning) {
                    view.tunnel.start()
                }
                activeView = view
                persistRunning(true)
                pruneOtherTunnels(view.tunnel.config.id)
                Log.i(
                    TAG,
                    if (alreadyRunning) {
                        "Tunnel already running: ${view.tunnel.config.id}"
                    } else {
                        "Tunnel started: ${view.tunnel.config.id} adapters=${desired.map { it.type }}"
                    },
                )

                if (!alreadyRunning) {
                    Log.i(TAG, "SELF: Starting...")
                    try {
                        view.tunnel.send("PING")
                        Log.i(TAG, "SELF: PING sent")
                    } catch (e: Exception) {
                        Log.e(TAG, "SELF: PING failed: ${e.message}")
                    }
                }

                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "createTunnel failed: ${e.message}", e)
                Log.e(TAG, "SELF: ERROR ${e.message}")
                onError?.invoke(e.message ?: "Tunnel start failed")
            }
        }
    }

    /** Install handlers with optional command dispatch. PING→PONG always included. */
    private fun installHandlers(view: TunnelView, onMessage: ((TunnelMessage) -> Unit)?) {
        val recorded = view.handlers
        val cmdHandler = commandHandler
        view.tunnel.handlers = object : TunnelHandlers {
            override fun onMessage(msg: TunnelMessage, direction: String) {
                recorded.onMessage(msg, direction)
                Log.i(TAG, "onMessage [$direction] from=${msg.from.take(8)} len=${msg.text.length}")
                if (direction != "in") return
                if (msg.from == view.tunnel.config.clientId) return

                lastReceived = if (msg.text.length > 400) msg.text.take(200) + "…" else msg.text

                if (msg.text == "PING") {
                    Log.i(TAG, "Processing PING → sending PONG")
                    scope.launch {
                        try { view.tunnel.send("PONG"); Log.i(TAG, "PONG sent") }
                        catch (e: Exception) { Log.e(TAG, "PONG reply failed: ${e.message}") }
                    }
                    return
                }
                if (msg.text == "PONG") return

                val advertised = AdvertiseAdapters.parse(msg.text)
                if (advertised != null) {
                    val accept = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(SetupActivity.PREFS_ACCEPT_ADVERTISED, true)
                    if (accept) {
                        scope.launch {
                            try {
                                applyAdvertisedAdapters(view, advertised.adapters)
                            } catch (e: Exception) {
                                Log.e(TAG, "advertise-adapters apply failed: ${e.message}", e)
                            }
                        }
                    } else {
                        Log.i(TAG, "Ignoring advertise-adapters (accept disabled)")
                    }
                    return
                }

                // Replies are JSON with ok/type; do not treat them as new commands.
                if (looksLikePhoneReply(msg.text)) {
                    onMessage?.invoke(msg)
                    return
                }

                if (cmdHandler != null) {
                    val text = msg.text
                    scope.launch {
                        try {
                            val response = cmdHandler(text)
                            if (response.isNotBlank()) {
                                try { view.tunnel.send(response) }
                                catch (e: Exception) { Log.e(TAG, "Response send failed: ${e.message}") }
                                if (response.contains("\"type\":\"putFile\"")) {
                                    ApkSelfUpdate.afterAckSent(ctx, putFileMime(response)) { json ->
                                        scope.launch {
                                            try { view.tunnel.send(json) }
                                            catch (e: Exception) {
                                                Log.e(TAG, "apkUpdate send failed: ${e.message}")
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Cmd handler error: ${e.message}", e)
                        }
                    }
                }

                onMessage?.invoke(msg)
            }

            override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {
                recorded.onDebug(adapterName, tMsg, decryptedJson)
                when {
                    decryptedJson.startsWith("[error]") ->
                        pushLog(adapterName, "ERROR", decryptedJson)
                    decryptedJson.startsWith("[part") ->
                        pushLog(adapterName, "part", decryptedJson)
                    else ->
                        pushLog(adapterName, "received", previewPayload(decryptedJson))
                }
            }
        }
    }

    fun restartActive() {
        scope.launch {
            val view = activeView ?: return@launch
            installHandlers(view, null)
            try {
                view.tunnel.start()
                persistRunning(true)
                Log.i(TAG, "Tunnel restarted: ${view.tunnel.config.id}")
            } catch (e: Exception) {
                Log.e(TAG, "restartActive failed: ${e.message}")
            }
        }
    }

    /** Restart tunnel from stored config (used for auto-resume). */
    fun restartFromStored() {
        scope.launch {
            try {
                appManager.ensureInit()
                val channel = channelFromPrefs()
                val secret = secretFromPrefs()
                val adapters = adaptersFromPrefs()
                if (channel.isBlank() || adapters.isEmpty()) {
                    Log.w(TAG, "restartFromStored: no channel/adapters in prefs")
                    return@launch
                }

                val view = openChannelTunnel(channel, secret, adapters)
                view.tunnel.updateConfig(mapOf("secret" to secret, "channel" to channel))
                applyDesiredAdapters(view, normalizeAdapterConfigs(adapters))
                installHandlers(view, null)
                if (view.tunnel.running) view.tunnel.stop()
                view.tunnel.start()
                activeView = view
                persistRunning(true)
                pruneOtherTunnels(view.tunnel.config.id)
                Log.i(
                    TAG,
                    "restarted tunnel ${view.tunnel.config.id} channel=$channel adapters=${view.tunnel.getAdapters().map { "${it.type}:${it.enabled}" }}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "restartFromStored failed: ${e.message}")
            }
        }
    }

    fun sendMessage(text: String) {
        scope.launch {
            val view = activeView ?: return@launch
            try {
                view.tunnel.send(text)
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                activeView?.tunnel?.stop()
                persistRunning(false)
                Log.i(TAG, "Tunnel stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Stop error: ${e.message}")
            }
        }
    }

    fun isRunning(): Boolean = activeView?.tunnel?.running ?: false

    fun startAdapter(
        adapterId: String,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val view = activeView ?: throw IllegalStateException("No tunnel")
                if (view.tunnel.getAdapters().none { it.id == adapterId }) {
                    val fromPrefs = adaptersFromPrefs().find { it.id == adapterId }
                        ?: throw IllegalArgumentException("Adapter $adapterId not found")
                    view.tunnel.upsertAdapter(fromPrefs.copy(enabled = true))
                } else {
                    view.tunnel.startAdapter(adapterId)
                }
                persistAdapters(view.tunnel.getAdapters())
                Handler(Looper.getMainLooper()).post { onDone?.invoke() }
            } catch (e: Exception) {
                Log.e(TAG, "startAdapter failed: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    onError?.invoke(e.message ?: "start adapter failed")
                }
            }
        }
    }

    fun stopAdapter(
        adapterId: String,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val view = activeView ?: throw IllegalStateException("No tunnel")
                view.tunnel.stopAdapter(adapterId)
                persistAdapters(view.tunnel.getAdapters())
                Handler(Looper.getMainLooper()).post { onDone?.invoke() }
            } catch (e: Exception) {
                Log.e(TAG, "stopAdapter failed: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    onError?.invoke(e.message ?: "stop adapter failed")
                }
            }
        }
    }

    fun upsertAdapter(
        ac: AdapterInstanceConfig,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val view = activeView ?: throw IllegalStateException("No tunnel")
                view.tunnel.upsertAdapter(ac)
                persistAdapters(view.tunnel.getAdapters())
                Handler(Looper.getMainLooper()).post { onDone?.invoke() }
            } catch (e: Exception) {
                Log.e(TAG, "upsertAdapter failed: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    onError?.invoke(e.message ?: "upsert adapter failed")
                }
            }
        }
    }

    fun removeLiveAdapter(
        adapterId: String,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val view = activeView ?: throw IllegalStateException("No tunnel")
                if (view.tunnel.getAdapters().any { it.id == adapterId }) {
                    view.tunnel.removeAdapter(adapterId)
                }
                persistAdapters(view.tunnel.getAdapters())
                Handler(Looper.getMainLooper()).post { onDone?.invoke() }
            } catch (e: Exception) {
                Log.e(TAG, "removeAdapter failed: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    onError?.invoke(e.message ?: "remove adapter failed")
                }
            }
        }
    }

    private suspend fun openChannelTunnel(
        channel: String,
        secret: String,
        adapters: List<AdapterInstanceConfig>,
    ): TunnelView {
        val existing = appManager.list()
            .filter { it.channel == channel }
            .maxByOrNull { it.id }
        return if (existing != null) {
            val view = appManager.get(existing.id)
                ?: throw IllegalStateException("Tunnel exists but not loaded")
            view.tunnel.updateConfig(mapOf("secret" to secret, "channel" to channel))
            view
        } else {
            val tunnelLabel = adapters.firstOrNull()?.label?.takeIf { it.isNotBlank() }
                ?: "phone-${adapters.first().type}"
            appManager.create(tunnelLabel, channel, secret)
        }
    }

    private suspend fun pruneOtherTunnels(keepId: String) {
        appManager.keepOnly(keepId)
        for (key in store.keys().toList()) {
            val drop = when {
                key == "tunnels" -> false
                key.startsWith("tunnel:") && key != "tunnel:$keepId" -> true
                key.startsWith("view:") && key != "view:$keepId" -> true
                else -> false
            }
            if (drop) store.remove(key)
        }
        Log.i(TAG, "pruned tunnel store, keep=$keepId")
    }

    private fun channelFromPrefs(): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ch = prefs.getString(SetupActivity.PREFS_CHANNEL, null)
        if (!ch.isNullOrBlank()) return ch
        return prefs.getString(SetupActivity.PREFS_SEED, "") ?: ""
    }

    private fun secretFromPrefs(): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(SetupActivity.PREFS_SECRET, "") ?: ""
    }

    private fun adaptersFromPrefs(): List<AdapterInstanceConfig> {
        val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SetupActivity.PREFS_ADAPTERS, null)
            ?: return emptyList()
        return try {
            Json.decodeFromString(ListSerializer(AdapterInstanceConfig.serializer()), json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeAdapterConfigs(adapters: List<AdapterInstanceConfig>): List<AdapterInstanceConfig> =
        adapters.map { ac ->
            val manifest = Registry.get(ac.type)
                ?: throw IllegalArgumentException("Unknown adapter type: ${ac.type}")
            val defaults = manifest.defaultConfig.mapValues { (_, v) -> v.toString() }
            val merged = defaults + ac.config
            val adapterId = ac.id.ifBlank { ac.type }
            val adapterLabel = ac.label.ifBlank {
                merged["label"]?.takeIf { it.isNotBlank() } ?: manifest.label
            }
            AdapterInstanceConfig(
                type = ac.type,
                id = adapterId,
                label = adapterLabel,
                config = merged + mapOf("id" to adapterId),
                enabled = ac.enabled,
            )
        }

    private suspend fun applyDesiredAdapters(view: TunnelView, desired: List<AdapterInstanceConfig>) {
        val current = view.tunnel.getAdapters()
        for (old in current) {
            if (desired.none { it.id == old.id }) {
                view.tunnel.removeAdapter(old.id)
            }
        }
        for (ac in desired) {
            view.tunnel.upsertAdapter(ac)
        }
    }

    private suspend fun applyAdvertisedAdapters(view: TunnelView, advertised: List<AdapterInstanceConfig>) {
        for (ac in advertised) {
            view.tunnel.upsertAdapter(ac)
        }
        persistAdapters(view.tunnel.getAdapters())
        Log.i(TAG, "Applied advertised adapters: ${advertised.map { it.id }}")
        val applied = view.tunnel.getAdapters().filter { it.type != "mock" }
        Handler(Looper.getMainLooper()).post {
            val listener = onAdvertisedAdaptersApplied
            if (listener != null) {
                listener(applied)
            } else {
                Toast.makeText(ctx, "Advertised adapters applied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistAdapters(adapters: List<AdapterInstanceConfig>) {
        val kept = adapters.filter { it.type != "mock" }
        val json = Json.encodeToString(ListSerializer(AdapterInstanceConfig.serializer()), kept)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SetupActivity.PREFS_ADAPTERS, json)
            .apply()
    }

    private fun summarizePlaintext(text: String, direction: String): String {
        val t = text.trim()
        if (t.startsWith("[")) {
            return try {
                val arr = org.json.JSONArray(t)
                val bits = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = o.optString("cmd").ifBlank { o.optString("type") }.ifBlank { "?" }
                    val extra = when {
                        o.has("error") -> " ERROR ${o.optString("error")}"
                        kind == "screenshot" || o.has("data") -> {
                            val n = o.optString("data").length
                            val kb = (n * 3 / 4) / 1024
                            " ${kb}KB"
                        }
                        else -> ""
                    }
                    bits.add(kind + extra)
                }
                val prefix = if (direction == "out") "cmds" else "reply"
                "$prefix ${bits.joinToString(",")}"
            } catch (_: Exception) {
                t
            }
        }
        return t
    }

    private fun previewPayload(json: String): String {
        val text = TEXT_FIELD.find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", " ")
        return compactLine(text ?: json, 72)
    }

    private fun putFileMime(response: String): String {
        return try {
            val arr = JSONArray(response)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "putFile") return o.optString("mime")
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun looksLikePhoneReply(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("[")) return false
        return t.contains("\"ok\"") && (
            t.contains("\"type\":\"ack\"") ||
            t.contains("\"type\":\"screenshot\"") ||
            t.contains("\"type\":\"clipboard\"") ||
            t.contains("\"type\":\"putFile\"") ||
            t.contains("\"type\":\"apkUpdate\"") ||
            t.contains("\"type\":\"logs\"") ||
            t.contains("\"data\"")
        )
    }
}
