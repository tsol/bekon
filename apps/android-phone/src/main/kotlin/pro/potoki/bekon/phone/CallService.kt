package pro.potoki.bekon.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pro.potoki.bekon.call.CallProtocol
import pro.potoki.bekon.call.CtrlAck
import pro.potoki.bekon.call.RemotePhoneState
import pro.potoki.bekon.call.VoiceLatency
import pro.potoki.bekon.call.WlyaCallClient
import org.json.JSONObject

class CallService : Service() {

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL_LINK = "bekon_phone_link"
        private const val CHANNEL_CALL = "bekon_phone_call"
        private const val NOTIFICATION_ID = 71
        const val ACTION_CONNECT = "pro.potoki.bekon.phone.CONNECT"
        const val ACTION_DISCONNECT = "pro.potoki.bekon.phone.DISCONNECT"
        const val ACTION_DIAL = "pro.potoki.bekon.phone.DIAL"
        const val ACTION_PICKUP = "pro.potoki.bekon.phone.PICKUP"
        const val ACTION_CANCEL = "pro.potoki.bekon.phone.CANCEL"
        const val ACTION_SET_MODE = "pro.potoki.bekon.phone.SET_MODE"
        const val ACTION_MUTE = "pro.potoki.bekon.phone.MUTE"
        const val ACTION_SPEAKER = "pro.potoki.bekon.phone.SPEAKER"
        const val ACTION_GW_LATENCY = "pro.potoki.bekon.phone.GW_LATENCY"
        const val ACTION_LOCAL_LATENCY = "pro.potoki.bekon.phone.LOCAL_LATENCY"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ON = "on"
        const val EXTRA_PRESET = "preset"

        @Volatile var instance: CallService? = null
            private set

        @Volatile var lastState: RemotePhoneState? = null
            private set

        val ui = kotlinx.coroutines.flow.MutableStateFlow(CallUiState())
        val trace = kotlinx.coroutines.flow.MutableStateFlow<List<ChannelLogLine>>(emptyList())

        fun clearTrace() {
            trace.value = emptyList()
        }

        fun note(dir: String, text: String) {
            val line = ChannelLogLine(System.currentTimeMillis(), dir, text.trim().take(400))
            val cur = trace.value
            trace.value = (cur + line).takeLast(80)
        }

        fun connect(context: Context) = start(context, ACTION_CONNECT)
        fun disconnect(context: Context) = start(context, ACTION_DISCONNECT)
        fun dial(context: Context, number: String) =
            start(context, ACTION_DIAL) { it.putExtra(EXTRA_NUMBER, number) }
        fun pickup(context: Context) = start(context, ACTION_PICKUP)
        fun cancel(context: Context) = start(context, ACTION_CANCEL)
        fun setMode(context: Context, mode: String) =
            start(context, ACTION_SET_MODE) { it.putExtra(EXTRA_MODE, mode) }
        fun muteMic(context: Context, muted: Boolean) =
            start(context, ACTION_MUTE) { it.putExtra(EXTRA_ON, muted) }
        fun speaker(context: Context, on: Boolean) =
            start(context, ACTION_SPEAKER) { it.putExtra(EXTRA_ON, on) }
        fun gatewayLatencyPreset(context: Context, preset: String) =
            start(context, ACTION_GW_LATENCY) { it.putExtra(EXTRA_PRESET, preset) }
        fun localLatencyPreset(context: Context, preset: String) =
            start(context, ACTION_LOCAL_LATENCY) { it.putExtra(EXTRA_PRESET, preset) }

        private fun start(context: Context, action: String, extra: (Intent) -> Unit = {}) {
            val i = Intent(context, CallService::class.java).setAction(action)
            extra(i)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: WlyaCallClient? = null
    private var pcm: ClientPcmBridge? = null
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var muted = false
    private var speakerOn = false
    private var lastCall = "idle"
    private var inboundId: Long? = null
    private var inboundRinging = false
    private var inboundAnswered = false
    private var activeId: Long? = null
    private var activeStartedAt = 0L
    private var incomingShown = false
    private var userStopped = false
    private var pendingId: String? = null
    private var pendingKind: String? = null
    private var lastError = ""
    private var lastAck = ""
    private var lastDialNumber = ""
    private var outbound = false
    private var lastLoggedState = ""
    private val pendingTimeout = Runnable {
        if (pendingId == null) return@Runnable
        lastError = "no ack"
        lastAck = "timeout"
        pendingId = null
        pendingKind = null
        note("·", "ack timeout")
        finishActive(0, "No ack")
        pushUi()
    }
    private var remoteWsRttMs: Long? = null
    private var remoteBufMult: Int? = null
    private var remoteLatencyPreset: String? = null
    private val pingLoop = object : Runnable {
        override fun run() {
            if (client != null && pendingKind != "ping") sendPing()
            handler.postDelayed(this, 2_000L)
        }
    }
    private val reconnect = Runnable {
        if (!userStopped && client == null) connect()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                userStopped = true
                handler.removeCallbacks(reconnect)
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DIAL -> dial(intent.getStringExtra(EXTRA_NUMBER).orEmpty())
            ACTION_PICKUP -> pickup()
            ACTION_CANCEL -> cancel()
            ACTION_SET_MODE -> setMode(intent.getStringExtra(EXTRA_MODE) ?: CallProtocol.MODE_PHONE)
            ACTION_MUTE -> muteMic(intent.getBooleanExtra(EXTRA_ON, false))
            ACTION_SPEAKER -> speaker(intent.getBooleanExtra(EXTRA_ON, false))
            ACTION_GW_LATENCY -> gatewayLatencyPreset(intent.getStringExtra(EXTRA_PRESET).orEmpty())
            ACTION_LOCAL_LATENCY -> localLatencyPreset(intent.getStringExtra(EXTRA_PRESET).orEmpty())
            else -> connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun connect() {
        userStopped = false
        if (client != null) {
            promoteForeground()
            return
        }
        promoteForeground()
        val prefs = CallPrefs(this)
        if (prefs.seed.isBlank()) {
            Log.w(TAG, "seed empty — set it in Settings")
            pushUi("idle")
            return
        }
        val call = WlyaCallClient(
            onPcm = { bytes -> pcm?.play(bytes) },
            onStatus = { s ->
                handler.post {
                    if (s == "joined" && pendingKind == "connect") {
                        pendingKind = null
                        pendingId = null
                    }
                    if (s == "joined") {
                        handler.removeCallbacks(pingLoop)
                        handler.postDelayed(pingLoop, 1_000L)
                    }
                    if (s == "idle" || s.startsWith("error") || s.startsWith("dropped")) {
                        handler.removeCallbacks(pingLoop)
                    }
                    if (s == "connecting") pendingKind = pendingKind ?: "connect"
                    note("·", s)
                    pushUi(s)
                    promoteForeground()
                }
                Log.i(TAG, s)
            },
            onJson = { text ->
                handler.post { onCallJson(text) }
            },
            onDropped = { why ->
                Log.w(TAG, "dropped: $why")
                handler.post {
                    client = null
                    stopPcm()
                    lastState = null
                    clearPending()
                    lastError = why
                    lastAck = ""
                    note("·", "dropped $why")
                    pushUi("idle")
                    if (!userStopped) handler.postDelayed(reconnect, 2_000L)
                }
            },
        )
        client = call
        acquireWake()
        note("→", "connect room=${prefs.room}")
        call.connect(prefs.url, prefs.seed, prefs.room, prefs.clientId)
    }

    fun disconnect() {
        handler.removeCallbacks(pingLoop)
        stopRingtone()
        hideIncoming()
        stopPcm()
        client?.disconnect()
        client = null
        releaseWake()
        lastCall = "idle"
        lastState = null
        clearPending()
        lastError = ""
        lastAck = ""
        lastDialNumber = ""
        outbound = false
        pushUi("idle")
    }

    fun dial(number: String) {
        if (client == null) connect()
        val n = number.trim()
        if (n.isBlank()) return
        lastDialNumber = n
        outbound = true
        lastError = ""
        scope.launch {
            val name = PhoneApp.instance.contacts.displayName(n)
            val id = PhoneApp.instance.recents.insertOut(n, name)
            handler.post {
                activeId = id
                activeStartedAt = System.currentTimeMillis()
            }
        }
        sendCtrl("dial", CallProtocol.dial(n))
    }

    fun pickup() {
        if (client == null) connect()
        lastError = ""
        sendCtrl("pickup", CallProtocol.pickup())
        stopRingtone()
        hideIncoming()
    }

    fun cancel() {
        if (client == null) connect()
        lastError = ""
        sendCtrl("cancel", CallProtocol.cancel())
        stopRingtone()
        hideIncoming()
    }

    fun setMode(mode: String) {
        if (client == null) connect()
        lastError = ""
        sendCtrl("mode", CallProtocol.setMode(mode))
    }

    fun muteMic(muted: Boolean) {
        this.muted = muted
        pcm?.setCapture(!muted && (lastState?.capture ?: true))
    }

    fun gatewayLatencyPreset(preset: String) {
        if (client == null) connect()
        val p = preset.trim()
        if (p.isBlank()) return
        sendCtrl("gw-latency", CallProtocol.latencyPreset(p))
    }

    fun localLatencyPreset(preset: String) {
        val p = preset.trim()
        if (p.isBlank()) return
        pcm?.applyPreset(p) ?: VoiceLatency.applyPreset(p)
        note("·", "local latency $p buf=${VoiceLatency.bufMult}")
        pushUi()
    }

    private fun sendPing() {
        if (client == null) return
        val id = CallProtocol.newId()
        val json = VoiceLatency.pingJson(id)
        client?.sendJson(json)
    }

    fun speaker(on: Boolean) {
        speakerOn = on
        val am = getSystemService(AudioManager::class.java) ?: return
        pcm?.setSpeakerphone(am, on)
    }

    private fun sendCtrl(kind: String, json: String) {
        val id = try {
            org.json.JSONObject(json).optString("id")
        } catch (_: Exception) {
            CallProtocol.newId()
        }
        pendingId = id.ifBlank { CallProtocol.newId() }
        pendingKind = kind
        handler.removeCallbacks(pendingTimeout)
        handler.postDelayed(pendingTimeout, 12_000L)
        note("→", "$kind $json")
        pushUi()
        client?.sendJson(json)
    }

    private fun clearPending() {
        handler.removeCallbacks(pendingTimeout)
        pendingId = null
        pendingKind = null
    }

    private fun onCallJson(text: String) {
        if (replyPeerPing(text)) return
        logIncoming(text)
        CallProtocol.parseAck(text)?.let { ack ->
            onAck(ack)
            return
        }
        CallProtocol.parsePhoneState(text)?.let { onPhoneState(it) }
    }

    private fun replyPeerPing(text: String): Boolean {
        return try {
            val o = JSONObject(text)
            if (o.optString("type") != VoiceLatency.TYPE_PING) return false
            val id = o.optString("id")
            if (id.isBlank()) return true
            val t = o.optLong("t", 0L)
            val ack = JSONObject()
                .put("type", CallProtocol.TYPE_ACK)
                .put("id", id)
                .put("ok", true)
            if (t > 0L) ack.put("t", t).put("tEcho", System.currentTimeMillis())
            client?.sendJson(ack.toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun logIncoming(text: String) {
        CallProtocol.parseAck(text)?.let { ack ->
            val err = ack.error?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
            val call = ack.state?.call.orEmpty()
            val dial = ack.state?.dialResult.orEmpty()
            note("←", "ack ${if (ack.ok) "ok" else "fail"}$err call=$call dial=$dial")
            return
        }
        CallProtocol.parsePhoneState(text)?.let { st ->
            val key = "${st.call}|${st.mode}|${st.number}|${st.dialResult}|${st.bridge}"
            if (key == lastLoggedState) return
            lastLoggedState = key
            note("←", "state call=${st.call} mode=${st.mode} n=${st.number} dial=${st.dialResult}")
            return
        }
        note("←", text)
    }

    private fun onAck(ack: CtrlAck) {
        if (ack.pingAt > 0L) {
            VoiceLatency.recordRtt(ack.pingAt)
            note("·", "rtt ${VoiceLatency.lastRttMs}ms")
        }
        val kind = if (pendingId == null || ack.id == pendingId) pendingKind else null
        if (pendingId == null || ack.id == pendingId) {
            lastAck = if (ack.ok) "ok" else "fail"
            lastError = if (ack.ok) "" else (ack.error ?: "ctrl failed")
            clearPending()
        }
        val st = ack.state
        val stateCall = st?.call?.ifBlank { lastCall } ?: lastCall
        if (kind == "dial" && (stateCall == "idle" || !ack.ok)) {
            val why = when {
                !ack.ok -> ack.error ?: "Failed"
                st != null && st.dialResult.isNotBlank() -> st.dialResult
                else -> "No answer"
            }
            finishActive(0, why)
        }
        ack.state?.let { onPhoneState(it) } ?: pushUi()
    }

    private fun onPhoneState(state: RemotePhoneState) {
        lastState = state
        state.latency?.let { lat ->
            remoteWsRttMs = lat.wsRttMs
            remoteBufMult = lat.bufMult
            remoteLatencyPreset = lat.latencyPreset
        }
        val prev = lastCall
        val call = state.call.ifBlank { "idle" }
        syncAudio(state)

        if (call == "ringing" && prev != "ringing") {
            inboundRinging = true
            inboundAnswered = false
            startRingtone()
            showIncoming(state.number)
            scope.launch {
                val name = PhoneApp.instance.contacts.displayName(state.number)
                val id = PhoneApp.instance.recents.insertIn(state.number, name)
                handler.post {
                    inboundId = id
                    activeId = id
                    activeStartedAt = System.currentTimeMillis()
                }
            }
        }

        if (call == "idle" || call == "offhook") {
            stopRingtone()
        }

        if (call == "offhook" && inboundRinging) {
            inboundAnswered = true
            inboundRinging = false
            hideIncoming()
        }

        if (call == "idle" && prev != "idle") {
            hideIncoming()
            val started = activeStartedAt
            val dur = if (started > 0L) (System.currentTimeMillis() - started).coerceAtLeast(0L) else 0L
            val why = when {
                prev == "ringing" && !inboundAnswered -> "Missed"
                prev == "offhook" && dur < 4_000L && outbound -> "No answer"
                prev == "offhook" && dur > 0L -> formatCallElapsed(dur)
                outbound -> lastError.ifBlank { state.dialResult }.ifBlank { "Failed" }
                else -> state.dialResult.ifBlank { "Ended" }
            }
            if (prev == "ringing" && !inboundAnswered) {
                val miss = inboundId
                if (miss != null) scope.launch { PhoneApp.instance.recents.markMissed(miss) }
            } else {
                finishActive(dur, why)
            }
            inboundId = null
            inboundRinging = false
            inboundAnswered = false
            activeId = null
            activeStartedAt = 0L
            outbound = false
            lastDialNumber = ""
        }

        lastCall = call
        pushUi()
        promoteForeground()
    }

    private fun finishActive(durationMs: Long, result: String) {
        val id = activeId ?: return
        scope.launch { PhoneApp.instance.recents.finish(id, durationMs, result) }
    }

    private fun formatCallElapsed(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun pushUi(socket: String? = null) {
        val cur = ui.value
        val phone = lastState
        val shownNumber = when {
            phone?.number?.isNotBlank() == true -> phone.number
            lastDialNumber.isNotBlank() -> lastDialNumber
            else -> ""
        }
        ui.value = cur.copy(
            socket = socket ?: cur.socket,
            phone = phone,
            pending = pendingKind.orEmpty(),
            lastError = lastError,
            lastAck = lastAck,
            number = shownNumber,
            outbound = outbound,
            wsRttMs = VoiceLatency.lastRttMs,
            localLatencyPreset = VoiceLatency.preset,
            localBufMult = VoiceLatency.bufMult,
            remoteWsRttMs = remoteWsRttMs,
            remoteBufMult = remoteBufMult,
            remoteLatencyPreset = remoteLatencyPreset,
        )
    }

    private fun syncAudio(state: RemotePhoneState) {
        val live = state.mode == CallProtocol.MODE_WALKIE ||
            state.call == "offhook" ||
            state.bridge
        if (live) {
            if (pcm == null) {
                val bridge = ClientPcmBridge { bytes -> client?.sendPcm(bytes) }
                pcm = bridge
                try {
                    bridge.start()
                } catch (e: Exception) {
                    Log.e(TAG, "pcm start: ${e.message}", e)
                    pcm = null
                    return
                }
                val am = getSystemService(AudioManager::class.java)
                if (am != null) bridge.setSpeakerphone(am, speakerOn)
            }
            pcm?.setCapture(!muted && state.capture)
            pcm?.setPlayback(state.playback)
        } else {
            stopPcm()
        }
    }

    private fun stopPcm() {
        pcm?.stop()
        pcm = null
    }

    private fun startRingtone() {
        stopRingtone()
        val stored = CallPrefs(this).ringtone
        val uri = if (stored.isNotBlank()) {
            Uri.parse(stored)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
        val rt = try {
            RingtoneManager.getRingtone(this, uri)
        } catch (_: Exception) {
            null
        } ?: return
        if (Build.VERSION.SDK_INT >= 28) rt.isLooping = true
        ringtone = rt
        try {
            rt.play()
        } catch (e: Exception) {
            Log.w(TAG, "ringtone: ${e.message}")
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
    }

    private fun showIncoming(number: String) {
        if (incomingShown) return
        incomingShown = true
        val i = Intent(this, IncomingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(IncomingActivity.EXTRA_NUMBER, number)
        startActivity(i)
        promoteForeground(fullScreen = true, number = number)
    }

    private fun hideIncoming() {
        incomingShown = false
        IncomingActivity.finishIfOpen()
        promoteForeground(fullScreen = false)
    }

    private fun inCallLike(): Boolean {
        val s = lastState ?: return false
        return s.call == "offhook" || s.call == "ringing" || s.bridge ||
            (s.mode == CallProtocol.MODE_WALKIE && pcm != null)
    }

    private fun promoteForeground(fullScreen: Boolean = false, number: String = "") {
        val live = fullScreen || inCallLike()
        val notification = buildNotification(fullScreen, number, live)
        val type = fgsType(live)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun fgsType(live: Boolean): Int {
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (live && Build.VERSION.SDK_INT >= 30) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun buildNotification(fullScreen: Boolean, number: String, live: Boolean): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = if (fullScreen || lastState?.call == "ringing") CHANNEL_CALL else CHANNEL_LINK
        val text = when {
            fullScreen && number.isNotBlank() -> number
            lastState?.call == "offhook" -> "On call"
            lastState?.call == "ringing" -> "Incoming"
            lastState?.mode == CallProtocol.MODE_WALKIE && live -> "Walkie"
            else -> "Connected"
        }
        val b = NotificationCompat.Builder(this, channel)
            .setSmallIcon(
                if (live) android.R.drawable.sym_action_call
                else android.R.drawable.stat_notify_sync,
            )
            .setContentTitle("Bekon Phone")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(launch)
            .setSilent(!fullScreen)
            .setCategory(
                if (live) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE,
            )
        if (fullScreen) {
            val incoming = PendingIntent.getActivity(
                this,
                1,
                Intent(this, IncomingActivity::class.java)
                    .putExtra(IncomingActivity.EXTRA_NUMBER, number),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.setFullScreenIntent(incoming, true)
            b.priority = NotificationCompat.PRIORITY_HIGH
        } else {
            b.priority = NotificationCompat.PRIORITY_LOW
        }
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Line", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Calls", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bekon-phone:call").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWake() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}

data class CallUiState(
    val socket: String = "idle",
    val phone: RemotePhoneState? = null,
    val pending: String = "",
    val lastError: String = "",
    val lastAck: String = "",
    val number: String = "",
    val outbound: Boolean = false,
    val wsRttMs: Long = -1L,
    val localLatencyPreset: String = VoiceLatency.PRESET_BALANCED,
    val localBufMult: Int = 4,
    val remoteWsRttMs: Long? = null,
    val remoteBufMult: Int? = null,
    val remoteLatencyPreset: String? = null,
)

data class ChannelLogLine(
    val at: Long,
    val dir: String,
    val text: String,
)
