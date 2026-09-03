package pro.potoki.bekon.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsRcvr"
        var onSms: ((sender: String, body: String, ts: Long) -> Unit)? = null
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (m in msgs) {
            Log.d(TAG, "SMS: ${m.originatingAddress} -> ${m.messageBody}")
            onSms?.invoke(
                m.originatingAddress ?: "unknown",
                m.messageBody ?: "",
                m.timestampMillis
            )
        }
    }
}
