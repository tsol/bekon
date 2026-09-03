package pro.potoki.bekon

import android.app.Application

class AgentApplication : Application() {
    companion object {
        @Volatile
        var instance: AgentApplication? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        System.setProperty("java.net.preferIPv4Stack", "true")
    }
}
