package pro.potoki.bekon

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import pro.potoki.bekon.ime.BekonImeService
import pro.potoki.bekon.sms.SmsListener
import pro.potoki.bekon.touch.TouchService

/**
 * Root / priv-app: enable a11y, Bekon Keys IME, runtime grants — no Settings UI.
 */
object RootBootstrap {
    private const val TAG = "RootBootstrap"
    private const val PKG = "pro.potoki.bekon"

    private val A11Y = ComponentName(PKG, TouchService::class.java.name).flattenToString()
    private val IME = ComponentName(PKG, BekonImeService::class.java.name).flattenToShortString()
    private val NOTIF = ComponentName(PKG, SmsListener::class.java.name).flattenToString()

    private val GRANTS = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
    ) + (if (Build.VERSION.SDK_INT >= 26) listOf(Manifest.permission.ANSWER_PHONE_CALLS) else emptyList()) +
        (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())

    fun apply(ctx: Context) {
        if (!RootDetector.detect()) {
            Log.i(TAG, "skip: not rooted")
            return
        }
        Log.i(TAG, "applying a11y=$A11Y ime=$IME")
        grantRuntime(ctx)
        enableAccessibility(ctx)
        enableIme()
        enableNotificationListener(ctx)
        RootDetector.exec("dumpsys deviceidle whitelist +$PKG")
        RootDetector.exec("cmd appops set $PKG RUN_IN_BACKGROUND allow")
        RootDetector.exec("cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow")
    }

    private fun grantRuntime(ctx: Context) {
        for (p in GRANTS) {
            if (ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED) continue
            RootDetector.exec("pm grant $PKG $p")
        }
    }

    private fun enableAccessibility(ctx: Context) {
        appendSecure(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, A11Y)
        putSecureInt(ctx, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    private fun enableIme() {
        RootDetector.exec("ime enable $IME")
        RootDetector.exec("ime set $IME")
    }

    private fun enableNotificationListener(ctx: Context) {
        appendSecure(ctx, "enabled_notification_listeners", NOTIF)
    }

    private fun appendSecure(ctx: Context, key: String, component: String) {
        val cr = ctx.contentResolver
        try {
            val cur = Settings.Secure.getString(cr, key) ?: ""
            if (cur.split(':').any { it == component }) return
            val next = if (cur.isBlank() || cur == "null") component else "$cur:$component"
            if (Settings.Secure.putString(cr, key, next)) return
        } catch (e: Exception) {
            Log.w(TAG, "putString $key: ${e.message}")
        }
        val cur = RootDetector.exec("settings get secure $key") ?: ""
        val clean = if (cur == "null") "" else cur
        if (clean.split(':').any { it == component }) return
        val next = if (clean.isBlank()) component else "$clean:$component"
        RootDetector.exec("settings put secure $key $next")
    }

    private fun putSecureInt(ctx: Context, key: String, value: Int) {
        try {
            if (Settings.Secure.putInt(ctx.contentResolver, key, value)) return
        } catch (e: Exception) {
            Log.w(TAG, "putInt $key: ${e.message}")
        }
        RootDetector.exec("settings put secure $key $value")
    }
}
