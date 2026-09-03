package pro.potoki.bekon.phone

import android.app.Application

class PhoneApp : Application() {
    lateinit var recents: RecentsRepository
        private set
    lateinit var contacts: ContactsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        recents = RecentsRepository(this)
        contacts = ContactsRepository(this)
        if (CallPrefs(this).autoConnect) {
            CallService.connect(this)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: PhoneApp
            private set
    }
}
