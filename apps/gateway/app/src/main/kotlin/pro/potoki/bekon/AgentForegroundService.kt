package pro.potoki.bekon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import androidx.core.app.NotificationCompat
import pro.potoki.bekon.capture.CaptureProvider
import pro.potoki.bekon.capture.RootCapture
import pro.potoki.bekon.capture.ScreenCapture
import pro.potoki.bekon.intent.IntentHandler
import pro.potoki.bekon.tunnel.StubTunnelAdapter
import pro.potoki.bekon.tunnel.TunnelAdapter
import pro.potoki.bekon.tunnel.WebSocketTunnelAdapter
import pro.potoki.bekon.wlya.WlyaTunnelManager
import com.wlya.core.AdapterInstanceConfig
import com.wlya.core.TunnelMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "bekon_v1"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "Bekon::WakeLock"
        private const val TAG = "AgentService"
        private const val WLYA_RETRY_MS = 30_000L

        @Volatile var instance: AgentForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }

        fun onCapturePermissionGranted(data: Intent, resultCode: Int) {
            instance?.handleCapturePermission(data, resultCode)
                ?: Log.w(TAG, "Permission granted but service not running")
        }
    }

    // internal — shared with SetupActivity (same package)
    internal lateinit var tunnel: TunnelAdapter
    lateinit var wlyaManager: WlyaTunnelManager
        private set
    private lateinit var intentHandler: IntentHandler
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: WakeLock? = null
    @Volatile var captureProvider: CaptureProvider? = null
        private set
    @Volatile var isRooted = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquireWakeLock()
        createNotificationChannel()
        isRooted = RootDetector.detect()
        if (isRooted) {
            serviceScope.launch(Dispatchers.IO) { RootBootstrap.apply(this@AgentForegroundService) }
        }
        initCapture()
        initLegacyTunnel()
        initWlya()
    }

    private fun initCapture() {
        intentHandler = IntentHandler(this)
        if (isRooted) {
            val rc = RootCapture(this)
            rc.start()
            captureProvider = rc
            Log.i(TAG, "Root capture ready")
        }
        intentHandler.captureProvider = captureProvider
    }

    private fun initLegacyTunnel() {
        val wsTunnel = WebSocketTunnelAdapter(port = 9090)
        tunnel = if (wsTunnel.start()) {
            Log.i(TAG, "WebSocket tunnel on :9090")
            wsTunnel
        } else {
            Log.i(TAG, "Fallback to ADB stub tunnel")
            StubTunnelAdapter(this)
        }
        tunnel.connect(
            onMessage = { raw ->
                val resp = intentHandler.handle(raw)
                tunnel.send(resp)
            },
            onError = { e -> Log.e(TAG, "Tunnel error: $e") }
        )
    }

    private fun initWlya() {
        wlyaManager = WlyaTunnelManager(this)
        wlyaManager.commandHandler = intentHandler::handle  // wire phone-control commands
        wlyaManager.loadExisting()

        // Auto-resume if prefs say we were running
        val prefs = getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val running = prefs.getBoolean(WlyaTunnelManager.PREF_RUNNING, false)
        val adaptersJson = prefs.getString(SetupActivity.PREFS_ADAPTERS, null)
        val hasAdapters = !adaptersJson.isNullOrBlank() && adaptersJson != "[]"
        val hasLegacyAdapter = prefs.getString(SetupActivity.PREFS_ADAPTER_TYPE, "")?.isNotBlank() == true
        val hasConfig = (
            prefs.getString(SetupActivity.PREFS_CHANNEL, "")?.isNotBlank() == true ||
                prefs.getString(SetupActivity.PREFS_SEED, "")?.isNotBlank() == true
            ) && (hasAdapters || hasLegacyAdapter)
        if (!hasConfig) return
        if (isRooted && !running) {
            prefs.edit().putBoolean(WlyaTunnelManager.PREF_RUNNING, true).apply()
        }
        if (!prefs.getBoolean(WlyaTunnelManager.PREF_RUNNING, false)) return
        serviceScope.launch { watchWlyaResume() }
    }

    /** Keep trying until the tunnel is up. Stop All clears PREF_RUNNING and ends this loop. */
    private suspend fun watchWlyaResume() {
        val prefs = getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        while (currentCoroutineContext().isActive) {
            if (!prefs.getBoolean(WlyaTunnelManager.PREF_RUNNING, false)) {
                Log.i(TAG, "WLYA watchdog: PREF_RUNNING=false, stop")
                return
            }
            if (!wlyaManager.isRunning()) {
                val net = if (isDeviceOnline()) "online" else "offline"
                Log.i(TAG, "WLYA watchdog: not connected ($net), restartFromStored")
                wlyaManager.restartFromStored()
                refreshNotification()
            }
            delay(WLYA_RETRY_MS)
        }
    }

    /** Convenience bridge: start WLYA tunnel from UI. */
    fun startWlyaTunnel(
        channel: String,
        secret: String,
        adapters: List<AdapterInstanceConfig>,
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onMessage: ((TunnelMessage) -> Unit)? = null,
    ) {
        wlyaManager.createTunnel(channel, secret, adapters,
            onReady = {
                refreshNotification()
                onReady?.invoke()
            },
            onError = onError,
            onMessage = onMessage,
        )
    }

    fun isLegacyTunnelReady(): Boolean = ::tunnel.isInitialized

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    fun handleCapturePermission(data: Intent, resultCode: Int) {
        val sc = ScreenCapture(this)
        sc.setPermissionData(data, resultCode)
        sc.start()
        captureProvider = sc
        intentHandler.captureProvider = sc
        Log.i(TAG, "MediaProjection capture ready")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::wlyaManager.isInitialized) wlyaManager.stop()
        if (::tunnel.isInitialized) tunnel.disconnect()
        captureProvider?.stop()
        captureProvider = null
        serviceScope.cancel()
        releaseWakeLock()
        instance = null
        super.onDestroy()
    }

    /** Check if device has an active internet connection. */
    private fun isDeviceOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release(); wakeLock = null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // Don't delete — Android blocks deletion of channels used by fg services
            // Just create (idempotent: existing channel is updated if params differ)
            val ch = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Bekon Gateway", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Bekon Gateway is running"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val setupPi = PendingIntent.getActivity(this, 0,
            Intent(this, SetupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val wlyaOn = ::wlyaManager.isInitialized && wlyaManager.isRunning()
        val text = if (wlyaOn) "Running · WLYA" else "Running"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Bekon Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(setupPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}
