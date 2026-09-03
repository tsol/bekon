package pro.potoki.bekon.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import pro.potoki.bekon.RootDetector
import pro.potoki.bekon.SetupActivity

class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        private const val CHANNEL_ID = "bekon_voice"
        private const val NOTIFICATION_ID = 42
        private const val STATE_MS = 2000L

        /**
         * The modem voice session is not fully programmed the instant telephony
         * reports offhook, and a tap built into a half-open path reads zeros.
         * The manual WAV tests never hit this because a human pressed the button
         * seconds later.
         */
        private const val TAP_SETTLE_MS = 900L

        /** Consecutive non-offhook pulses before the pulse is allowed to drop the tap. */
        private const val IDLE_READS_TO_DROP = 2
        private const val RECONNECT_MIN_MS = 500L
        private const val RECONNECT_MAX_MS = 15_000L
        const val ACTION_START = "pro.potoki.bekon.voice.START"
        const val ACTION_STOP = "pro.potoki.bekon.voice.STOP"

        @Volatile var instance: VoiceService? = null
            private set

        @Volatile var status: String = "idle"
            private set

        @Volatile var phoneState: String = ""
            private set

        @Volatile var uiMode: String = VoiceLineState.MODE_PHONE
            private set

        val connected: Boolean
            get() = status == "joined" ||
                status == "connecting" ||
                status.startsWith("reconnecting") ||
                status.startsWith("error")

        val socketJoined: Boolean get() = status == "joined"

        fun sendPcm(pcm: ByteArray) {
            instance?.client?.sendPcm(pcm)
        }

        fun setCaptureEnabled(on: Boolean) {
            instance?.pcm?.setCapture(on)
            instance?.line?.setCapture(on)
        }

        val gsmLineLive: Boolean
            get() {
                val s = instance ?: return false
                return s.line != null || s.mode == VoiceLineState.MODE_PHONE
            }

        val displayMode: String
            get() {
                if (instance == null) return "off"
                return instance?.mode ?: VoiceLineState.MODE_PHONE
            }

        val callUi: String get() = instance?.callLabel ?: "idle"

        val incomingUi: String get() = instance?.incomingNumber ?: ""

        fun requestMode(wanted: String) {
            val s = instance ?: return
            s.handler.post {
                try {
                    s.setMode(wanted)
                    s.syncAudio()
                    s.emitState()
                } catch (e: Exception) {
                    Log.e(TAG, "mode $wanted: ${e.message}")
                }
            }
        }

        fun start(context: Context) {
            val i = Intent(context, VoiceService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceService::class.java).setAction(ACTION_STOP))
        }
    }

    private var client: WlyaCallClient? = null
    private var pcm: PcmBridge? = null
    private var line: RootCallBridge? = null
    private var speakerOn = false
    private var rooted = false
    private var mode = VoiceLineState.MODE_PHONE
    private var incomingNumber = ""
    private var dialResult = ""
    @Volatile private var callLabel = "idle"
    private var lineGen = 0
    private var lineStartPending = false
    private var notOffhookReads = 0
    private val handler = Handler(Looper.getMainLooper())
    private var phoneListen: PhoneStateListener? = null
    private var telephonyCallback: Any? = null
    @Volatile private var wantSocket = false
    private var reconnectDelayMs = RECONNECT_MIN_MS
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val pulseState = object : Runnable {
        override fun run() {
            emitState()
            handler.postDelayed(this, STATE_MS)
        }
    }
    private val reconnect = Runnable { openSocket() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        rooted = RootDetector.detect()
        mode = VoiceLineState.MODE_PHONE
        uiMode = mode
        val prefs = VoicePrefs(this)
        VoiceMeters.debug = prefs.debugMeters
        UplinkGain.load(prefs)
        createChannel()
        listenCallState()
        listenNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startCall()
        }
        return START_STICKY
    }

    private fun startCall() {
        if (wantSocket && client != null) return
        GsmEchoTest.stop()
        GsmLevelProbe.stop()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val bridge = PcmBridge { bytes ->
            if (mode == VoiceLineState.MODE_WALKIE) client?.sendPcm(bytes)
        }
        val call = WlyaCallClient(
            onPcm = { bytes ->
                GsmWsRecord.append(bytes)
                if (mode == VoiceLineState.MODE_PHONE) {
                    line?.play(bytes)
                } else {
                    pcm?.play(bytes)
                }
            },
            onStatus = { s ->
                status = s
                Log.i(TAG, s)
                if (s == "joined") {
                    reconnectDelayMs = RECONNECT_MIN_MS
                    handler.post {
                        ensurePulse()
                        syncAudio()
                        emitState()
                    }
                }
            },
            onJson = { text -> handler.post { onPeerJson(text) } },
            onDropped = { why -> handler.post { onSocketDropped(why) } },
        )
        pcm?.stop()
        pcm = bridge
        client = call
        wantSocket = true
        reconnectDelayMs = RECONNECT_MIN_MS
        openSocket()
    }

    private fun openSocket() {
        if (!wantSocket) return
        handler.removeCallbacks(reconnect)
        val call = client ?: return
        val prefs = VoicePrefs(this)
        try {
            call.connect(prefs.url, prefs.seed, prefs.room, prefs.clientId)
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}", e)
            onSocketDropped(e.message ?: "connect")
        }
    }

    private fun onSocketDropped(why: String) {
        if (!wantSocket) return
        val wait = reconnectDelayMs
        status = "reconnecting ${delayLabel(wait)} ($why)"
        Log.w(TAG, status)
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, wait)
        reconnectDelayMs = (wait * 2).coerceAtMost(RECONNECT_MAX_MS)
    }

    private fun delayLabel(ms: Long): String =
        if (ms < 1000L) "${ms}ms" else "${ms / 1000L}s"

    private fun ensurePulse() {
        handler.removeCallbacks(pulseState)
        handler.postDelayed(pulseState, STATE_MS)
    }

    private fun listenNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    if (!wantSocket || status == "joined" || status == "connecting") return@post
                    Log.i(TAG, "network up, retry socket now")
                    reconnectDelayMs = RECONNECT_MIN_MS
                    openSocket()
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (!wantSocket) return@post
                    Log.w(TAG, "network lost")
                }
            }
        }
        netCallback = cb
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) {
            Log.e(TAG, "network callback: ${e.message}")
            netCallback = null
        }
    }

    private fun unlistenNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.e(TAG, "unregister network: ${e.message}")
        }
    }

    private fun onPeerJson(text: String) {
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (o.optString("type")) {
            VoiceLineState.TYPE_PING -> {
                val id = o.optString("id")
                if (id.isBlank()) return
                sendAck(id, ok = true, error = null)
            }
            VoiceLineState.TYPE_CTRL -> {
                val ctrl = VoiceCtrlMsg.parse(text) ?: return
                try {
                    applyCtrl(ctrl)
                    val state = currentState()
                    phoneState = state.label()
                    sendAck(id = ctrl.id, ok = true, error = null, state = state)
                } catch (e: Exception) {
                    Log.e(TAG, "ctrl failed", e)
                    sendAck(id = ctrl.id, ok = false, error = e.message ?: "ctrl")
                }
            }
        }
    }

    private fun sendAck(
        id: String,
        ok: Boolean,
        error: String?,
        state: VoiceLineState = currentState(),
    ) {
        val o = JSONObject()
            .put("type", VoiceLineState.TYPE_ACK)
            .put("id", id)
            .put("ok", ok)
            .put("state", state.toJson())
        if (error != null) o.put("error", error)
        client?.sendJson(o.toString())
    }

    private fun applyCtrl(ctrl: VoiceCtrlMsg) {
        ctrl.mode?.let { setMode(it) }
        val action = ctrl.action
        if (action != null && action.startsWith("local-")) {
            // Route/mode changes reprogram Qualcomm's physical RX controls.
            // Apply endpoint mute last so syncAudio cannot immediately undo it.
            syncAudio()
            runAction(action, ctrl.number)
        } else {
            action?.let { runAction(it, ctrl.number) }
            syncAudio()
        }
        if (mode == VoiceLineState.MODE_WALKIE) {
            val bridge = pcm ?: return
            ctrl.source?.let { bridge.setSource(it) }
            ctrl.capture?.let { bridge.setCapture(it) }
            ctrl.playback?.let { bridge.setPlayback(it) }
            ctrl.speaker?.let { setSpeaker(it) }
        }
    }

    private fun runAction(action: String, number: String?) {
        when (action) {
            VoiceLineState.ACTION_PICKUP -> GsmCallActions.pickup(this)
            VoiceLineState.ACTION_CANCEL -> GsmCallActions.cancel(this)
            VoiceLineState.ACTION_DIAL -> {
                val n = GsmCallActions.dial(this, number ?: "")
                dialResult = "ok $n"
            }
            VoiceLineState.ACTION_LOCAL_TX_NORMAL -> runLocalMute {
                LocalLineMute.setTx(LocalLineMute.NORMAL)
            }
            VoiceLineState.ACTION_LOCAL_TX_ADC0 -> runLocalMute {
                LocalLineMute.setTx(LocalLineMute.TX_ADC0)
            }
            VoiceLineState.ACTION_LOCAL_TX_DEC0 -> runLocalMute {
                LocalLineMute.setTx(LocalLineMute.TX_DEC0)
            }
            VoiceLineState.ACTION_LOCAL_TX_MUX_ZERO -> runLocalMute {
                LocalLineMute.setTx(LocalLineMute.TX_MUX_ZERO)
            }
            VoiceLineState.ACTION_LOCAL_RESTORE -> {
                LocalLineMute.restoreAll()
                dialResult = LocalLineMute.lastResult
            }
            VoiceLineState.ACTION_UPLINK_GAIN -> {
                val db = number?.toIntOrNull()
                    ?: throw IllegalStateException("uplink-gain needs number (dB)")
                UplinkGain.setGainDb(db, VoicePrefs(this))
                QcomVocTap.applyInjectGain()
                dialResult = "uplink ${UplinkGain.label()}"
            }
            VoiceLineState.ACTION_UPLINK_TILT -> {
                UplinkGain.setPreEmphasis(number != "0", VoicePrefs(this))
                dialResult = "uplink ${UplinkGain.label()}"
            }
            else -> throw IllegalStateException("unknown action $action")
        }
    }

    private fun runLocalMute(block: () -> String) {
        check(mode == VoiceLineState.MODE_PHONE && callLabel == "offhook" && line != null) {
            "local mute needs PHONE + offhook + TAP"
        }
        dialResult = block()
    }

    private fun setMode(wantedRaw: String) {
        val wanted = VoiceLineState.normalizeMode(wantedRaw)
        val backends = backends()
        if (wanted !in backends) {
            throw IllegalStateException("mode $wanted not in $backends")
        }
        if (wanted == mode) return
        GsmEchoTest.stop()
        GsmRecordTest.stop()
        GsmPlayTest.stop()
        GsmWsPlay.stop()
        GsmWsRecord.stop()
        GsmLevelProbe.stop()
        mode = wanted
        uiMode = mode
        if (wanted == VoiceLineState.MODE_WALKIE) {
            speakerOn = true
            stopLine()
        } else {
            speakerOn = false
            pcm?.stop()
        }
        Log.i(TAG, "mode=$mode")
    }

    private fun backends(): List<String> = listOf(VoiceLineState.MODE_WALKIE, VoiceLineState.MODE_PHONE)

    private fun setSpeaker(on: Boolean) {
        speakerOn = on
        applyCallRoute()
    }

    private fun syncAudio() {
        callLabel = readCallState()
        if (mode == VoiceLineState.MODE_WALKIE) {
            stopLine()
            try {
                pcm?.start()
            } catch (e: Exception) {
                Log.e(TAG, "pcm: ${e.message}")
            }
            speakerOn = true
            applyCallRoute()
            return
        }
        pcm?.stop()
        applyCallRoute()
        if (callLabel == "offhook") {
            startLine()
        } else {
            stopLine()
        }
    }

    private fun startLine() {
        if (line != null || lineStartPending) return
        lineGen++
        lineStartPending = true
        val gen = lineGen
        handler.postDelayed({
            lineStartPending = false
            if (gen != lineGen || line != null) return@postDelayed
            if (mode != VoiceLineState.MODE_PHONE || readCallState() != "offhook") return@postDelayed
            openLine()
            emitState()
        }, TAP_SETTLE_MS)
    }

    private fun openLine() {
        GsmLevelProbe.stop()
        val tap = RootCallBridge { bytes ->
            if (mode == VoiceLineState.MODE_PHONE) client?.sendPcm(bytes)
        }
        try {
            tap.start(this)
            line = tap
            try {
                // PHONE is a gateway mode: keep the physical Motorola mic out
                // of GSM uplink while Incall_Music remains connected.
                dialResult = LocalLineMute.setTx(LocalLineMute.TX_MUX_ZERO)
            } catch (e: Exception) {
                Log.e(TAG, "automatic local mic mute failed", e)
                dialResult = "local mic mute failed: ${e.message}"
            }
            Log.i(TAG, "GSM↔WS tap up")
        } catch (e: Exception) {
            Log.e(TAG, "phone tap: ${e.message}")
            line = null
            dialResult = "tap failed: ${e.message}"
        }
    }

    private fun stopLine() {
        lineGen++
        lineStartPending = false
        LocalLineMute.restoreAll()
        line?.stop()
        line = null
    }

    private fun applyCallRoute() {
        val am = getSystemService(AudioManager::class.java) ?: return
        @Suppress("DEPRECATION")
        if (mode == VoiceLineState.MODE_PHONE) {
            if (callLabel == "offhook") am.mode = AudioManager.MODE_IN_CALL
            LineHandsetMute.apply(am)
            return
        }
        LineHandsetMute.release(am)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        if (Build.VERSION.SDK_INT >= 31) {
            val speaker = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) am.setCommunicationDevice(speaker)
        }
    }

    private fun currentState(): VoiceLineState {
        val bridge = pcm
        val tap = line
        val capture = if (mode == VoiceLineState.MODE_PHONE) tap?.captureOn() ?: (callLabel == "offhook")
        else bridge?.captureOn() ?: true
        val playback = if (mode == VoiceLineState.MODE_PHONE) tap?.playbackOn() ?: (callLabel == "offhook")
        else bridge?.playbackOn() ?: true
        val source = if (mode == VoiceLineState.MODE_PHONE) {
            VoiceLineState.SOURCE_VOICE_DOWNLINK
        } else {
            bridge?.source() ?: VoiceLineState.SOURCE_VOICE_COMM
        }
        return VoiceLineState(
            call = callLabel,
            capture = capture,
            playback = playback,
            source = source,
            speaker = speakerOn,
            root = rooted,
            mode = mode,
            backends = backends(),
            number = incomingNumber,
            dialResult = dialResult,
            bridge = line != null,
            tapDiag = tap?.diag() ?: if (lineStartPending) "tap settling" else "",
            localTxMute = LocalLineMute.txMode,
            localMuteResult = LocalLineMute.lastResult,
            uplinkGainDb = UplinkGain.gainDb,
            uplinkTilt = UplinkGain.preEmphasis,
        )
    }

    private fun emitState() {
        VoiceMeters.sampleDevice(getSystemService(AudioManager::class.java))
        val prev = callLabel
        callLabel = readCallState()
        if (callLabel == "offhook") notOffhookReads = 0 else notOffhookReads++
        val needTap = mode == VoiceLineState.MODE_PHONE && callLabel == "offhook"
        val phone = mode == VoiceLineState.MODE_PHONE
        // A single stale callState read used to tear the tap down, and nothing
        // rebuilt it until the next telephony transition.
        val wantSync = when {
            needTap && line == null && !lineStartPending -> true
            phone && !needTap && line != null -> notOffhookReads >= IDLE_READS_TO_DROP
            prev != callLabel -> true
            else -> false
        }
        if (wantSync) syncAudio()
        val state = currentState()
        phoneState = state.label()
        uiMode = mode
        val json = state.toJson().put("type", VoiceLineState.TYPE_STATE).toString()
        client?.sendJson(json)
    }

    private fun onCallChanged(state: Int, number: String?) {
        callLabel = when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_IDLE -> "idle"
            else -> "unknown"
        }
        if (!number.isNullOrBlank()) incomingNumber = number
        if (callLabel == "idle") incomingNumber = ""
        handler.post {
            syncAudio()
            emitState()
        }
    }

    private fun readCallState(): String {
        return try {
            val tm = getSystemService(TelephonyManager::class.java) ?: return callLabel
            @Suppress("DEPRECATION")
            when (tm.callState) {
                TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                TelephonyManager.CALL_STATE_RINGING -> "ringing"
                TelephonyManager.CALL_STATE_IDLE -> "idle"
                else -> callLabel
            }
        } catch (_: Exception) {
            callLabel
        }
    }

    @Suppress("DEPRECATION")
    private fun listenCallState() {
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onCallChanged(state, null)
                }
            }
            telephonyCallback = cb
            tm.registerTelephonyCallback(mainExecutor, cb)
            return
        }
        val listen = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onCallChanged(state, phoneNumber)
            }
        }
        phoneListen = listen
        tm.listen(listen, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun unlistenCallState() {
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = telephonyCallback as? TelephonyCallback
            if (cb != null && tm != null) tm.unregisterTelephonyCallback(cb)
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneListen?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
            phoneListen = null
        }
    }

    private fun teardown() {
        wantSocket = false
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(pulseState)
        unlistenNetwork()
        unlistenCallState()
        GsmWsRecord.stop()
        client?.disconnect()
        client = null
        stopLine()
        pcm?.stop()
        pcm = null
        mode = VoiceLineState.MODE_PHONE
        uiMode = "off"
        incomingNumber = ""
        dialResult = ""
        callLabel = "idle"
        LineHandsetMute.release(getSystemService(AudioManager::class.java))
        status = "idle"
        phoneState = ""
        VoiceMeters.reset()
    }

    override fun onDestroy() {
        teardown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("WLYA Voice")
            .setContentText("PHONE / WALKIE connected")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }
}
