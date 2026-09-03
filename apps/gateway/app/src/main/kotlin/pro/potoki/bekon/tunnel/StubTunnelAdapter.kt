package pro.potoki.bekon.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * Stub tunnel for testing without a live tunnel.
 * Prints outgoing messages to logcat.
 * Incoming "commands" are simulated via ADB broadcast:
 *   adb shell am broadcast -a pro.potoki.bekon.CMD \
 *       -e cmd '{"id":"test","cmd":"ping"}'
 */
class StubTunnelAdapter(private val context: Context) : TunnelAdapter {

    companion object { private const val TAG = "TunnelStub" }

    private var onMsg: ((String) -> Unit)? = null
    private var onErr: ((String) -> Unit)? = null

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent?.getStringExtra("cmd")?.let { raw ->
                Log.d(TAG, "STUB recv: $raw")
                onMsg?.invoke(raw)
            }
        }
    }

    override fun connect(onMessage: (String) -> Unit, onError: (String) -> Unit) {
        onMsg = onMessage
        onErr = onError
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                cmdReceiver,
                IntentFilter("pro.potoki.bekon.CMD"),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                cmdReceiver,
                IntentFilter("pro.potoki.bekon.CMD")
            )
        }
        Log.i(TAG, "Stub tunnel connected (ADB broadcast mode)")
    }

    override fun send(message: String) {
        Log.d(TAG, "STUB send: $message")
    }

    override fun disconnect() {
        try { context.unregisterReceiver(cmdReceiver) } catch (_: Exception) {}
        onMsg = null
        onErr = null
        Log.i(TAG, "Stub tunnel disconnected")
    }
}
