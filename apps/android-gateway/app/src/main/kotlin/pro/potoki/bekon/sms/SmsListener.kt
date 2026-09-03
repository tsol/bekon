package pro.potoki.bekon.sms

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class SmsListener : NotificationListenerService() {
    companion object {
        private const val TAG = "SmsListener"
        var onSms: ((sender: String, body: String, ts: Long) -> Unit)? = null

        private val SMS_PACKAGES = setOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "org.telegram.messenger",
            "com.whatsapp",
            "com.facebook.orca",
            "com.discord",
            "com.viber.voip",
            "com.linecorp.line"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in SMS_PACKAGES) return

        val extras = sbn.notification.extras
        val sender = extras.getString("android.title") ?: return
        val body = extras.getCharSequence("android.text")?.toString() ?: return
        val ts = sbn.postTime

        Log.d(TAG, "SMS from $sender: $body")
        onSms?.invoke(sender, body, ts)
    }

    override fun onListenerConnected() {
        Log.i(TAG, "Connected")
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Disconnected")
    }
}
