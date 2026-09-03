package pro.potoki.bekon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pro.potoki.bekon.voice.VoicePrefs
import pro.potoki.bekon.voice.VoiceService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_USER_UNLOCKED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext
                RootBootstrap.apply(app)
                AgentForegroundService.start(app)
                if (VoicePrefs(app).autoStart) {
                    VoiceService.start(app)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
