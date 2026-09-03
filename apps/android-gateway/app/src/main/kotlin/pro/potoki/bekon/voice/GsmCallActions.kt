package pro.potoki.bekon.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

object GsmCallActions {
    private const val TAG = "GsmCall"

    fun pickup(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                if (tm != null) {
                    tm.acceptRingingCall()
                    return
                }
            }
            invokeITelephony(ctx, "answerRingingCall")
        } catch (e: Exception) {
            throw IllegalStateException("pickup: ${e.message}")
        }
    }

    fun cancel(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                if (tm != null && tm.endCall()) return
            }
            invokeITelephony(ctx, "endCall")
        } catch (e: Exception) {
            throw IllegalStateException("cancel: ${e.message}")
        }
    }

    fun dial(ctx: Context, raw: String): String {
        val n = normalize(raw)
        if (n.isEmpty()) throw IllegalArgumentException("empty number")
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return n
    }

    fun normalize(raw: String): String {
        return raw.trim().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    private fun invokeITelephony(ctx: Context, method: String) {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val get = tm.javaClass.getDeclaredMethod("getITelephony")
        get.isAccessible = true
        val stub = get.invoke(tm) ?: throw IllegalStateException("no ITelephony")
        stub.javaClass.getMethod(method).invoke(stub)
        Log.i(TAG, method)
    }
}
