package pro.potoki.bekon.phone

import android.content.Context
import java.util.UUID

class CallPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var room: String
        get() = prefs.getString(KEY_ROOM, DEFAULT_ROOM) ?: DEFAULT_ROOM
        set(value) = prefs.edit().putString(KEY_ROOM, value).apply()

    var seed: String
        get() = prefs.getString(KEY_SEED, DEFAULT_SEED) ?: DEFAULT_SEED
        set(value) = prefs.edit().putString(KEY_SEED, value).apply()

    val clientId: String
        get() {
            val existing = prefs.getString(KEY_CLIENT, null)
            if (!existing.isNullOrBlank()) return existing
            val id = "phone-${UUID.randomUUID()}"
            prefs.edit().putString(KEY_CLIENT, id).apply()
            return id
        }

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    var ringtone: String
        get() = prefs.getString(KEY_RINGTONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RINGTONE, value).apply()

    companion object {
        const val PREFS = "bekon_phone"
        /** Empty until user configures relay; example: wss://your-relay.example/v1/call */
        const val DEFAULT_URL = ""
        const val DEFAULT_ROOM = "voice"
        const val DEFAULT_SEED = ""
        private const val KEY_URL = "url"
        private const val KEY_ROOM = "room"
        private const val KEY_SEED = "seed"
        private const val KEY_CLIENT = "client"
        private const val KEY_AUTO = "auto_connect"
        private const val KEY_RINGTONE = "ringtone"
    }
}
