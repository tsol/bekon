package pro.potoki.bekon

import android.content.Context
import android.os.PowerManager
import android.util.Log
import pro.potoki.bekon.capture.DisplayMetricsHelper
import pro.potoki.bekon.touch.TouchController

/**
 * Wakes the display from sleep, dim, or screensaver when the phone has been idle.
 * Without this, the first tap after idle often only wakes the screen and the intended action is lost.
 */
object ScreenWakeHelper {
    private const val TAG = "ScreenWake"
    const val PREFS_WAKE_AFTER_SLEEP_MS = "wake_after_sleep_ms"
    const val DEFAULT_WAKE_AFTER_SLEEP_MS = 3000
    private const val WAKE_POLL_MS = 50L
    private const val WAKE_TIMEOUT_MS = 1500L
    private const val POST_WAKE_SETTLE_MS = 500L

    fun wakeAfterSleepMs(context: Context): Int =
        context.getSharedPreferences("wlya_prefs", Context.MODE_PRIVATE)
            .getInt(PREFS_WAKE_AFTER_SLEEP_MS, DEFAULT_WAKE_AFTER_SLEEP_MS)
            .coerceIn(0, 30_000)

    fun setWakeAfterSleepMs(context: Context, ms: Int) {
        context.getSharedPreferences("wlya_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt(PREFS_WAKE_AFTER_SLEEP_MS, ms.coerceIn(0, 30_000))
            .apply()
    }

    /** @return true if the display was actually woken (was off, dim, or dreaming). */
    fun ensureAwake(context: Context, touchController: TouchController? = null): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        var woke = false

        if (!pm.isInteractive) {
            Log.i(TAG, "Screen not interactive — waking")
            wakeDisplay(context)
            woke = true
            waitUntilInteractive(pm)
            if (!pm.isInteractive) {
                nudgeDisplay(context, touchController)
                woke = true
                waitUntilInteractive(pm)
            }
        }

        if (isDreaming()) {
            Log.i(TAG, "Dream/screensaver active — dismissing")
            nudgeDisplay(context, touchController)
            woke = true
        }

        if (woke) Thread.sleep(POST_WAKE_SETTLE_MS)
        return woke
    }

    private fun wakeDisplay(context: Context) {
        if (RootDetector.isRooted) {
            RootDetector.exec("input keyevent 26") // KEYCODE_WAKEUP
        }
        @Suppress("DEPRECATION")
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Bekon::ScreenWake",
        )
        try {
            wl.acquire(2000L)
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock failed: ${e.message}")
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    private fun waitUntilInteractive(pm: PowerManager) {
        val deadline = System.currentTimeMillis() + WAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !pm.isInteractive) {
            Thread.sleep(WAKE_POLL_MS)
        }
        if (!pm.isInteractive) Log.w(TAG, "Screen still not interactive after wake")
    }

    private fun isDreaming(): Boolean {
        if (!RootDetector.isRooted) return false
        val out = RootDetector.exec("dumpsys dream") ?: return false
        return out.contains("mDreaming=true", ignoreCase = true)
    }

    /** Swipe up from the bottom — dismisses ambient display / screensaver on many devices. */
    private fun nudgeDisplay(context: Context, touchController: TouchController?) {
        val (w, h) = DisplayMetricsHelper.realScreenSize(context)
        val cx = w / 2
        val y1 = (h * 0.85).toInt()
        val y2 = (h * 0.35).toInt()

        if (RootDetector.isRooted) {
            RootDetector.exec("input keyevent 26")
            RootDetector.exec("input swipe $cx $y1 $cx $y2 250")
        } else if (touchController != null) {
            touchController.swipe(cx.toFloat(), y1.toFloat(), cx.toFloat(), y2.toFloat(), 250)
        } else {
            wakeDisplay(context)
        }
    }
}
