package pro.potoki.bekon.voice

import android.content.Context
import pro.potoki.bekon.SetupActivity
import java.util.UUID

class VoicePrefs(context: Context) {
    private val voice = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val wlya = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)

    var url: String
        get() = voice.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = voice.edit().putString(KEY_URL, value).apply()

    var room: String
        get() = voice.getString(KEY_ROOM, DEFAULT_ROOM) ?: DEFAULT_ROOM
        set(value) = voice.edit().putString(KEY_ROOM, value).apply()

    var seed: String
        get() {
            val stored = voice.getString(KEY_SEED, null)
            if (!stored.isNullOrBlank()) return stored
            return wlya.getString(SetupActivity.PREFS_CHANNEL, null)?.takeIf { it.isNotBlank() }
                ?: wlya.getString(SetupActivity.PREFS_SEED, SetupActivity.DEFAULT_CHANNEL)
                ?: SetupActivity.DEFAULT_CHANNEL
        }
        set(value) = voice.edit().putString(KEY_SEED, value).apply()

    val clientId: String
        get() {
            val existing = voice.getString(KEY_CLIENT, null)
            if (!existing.isNullOrBlank()) return existing
            val id = "phone-${UUID.randomUUID()}"
            voice.edit().putString(KEY_CLIENT, id).apply()
            return id
        }

    var debugMeters: Boolean
        get() = voice.getBoolean(KEY_DEBUG, false)
        set(value) {
            voice.edit().putBoolean(KEY_DEBUG, value).apply()
            VoiceMeters.debug = value
            if (!value) VoiceMeters.reset()
        }

    var autoStart: Boolean
        get() = voice.getBoolean(KEY_AUTO, false)
        set(value) = voice.edit().putBoolean(KEY_AUTO, value).apply()

    var ulMixer: String
        get() = voice.getString(KEY_UL_MIXER, LineRoute.DEFAULT_UL_MIXER) ?: LineRoute.DEFAULT_UL_MIXER
        set(value) = voice.edit().putString(KEY_UL_MIXER, value).apply()

    var ulPcm: Int
        get() = voice.getInt(KEY_UL_PCM, LineRoute.DEFAULT_UL_PCM)
        set(value) = voice.edit().putInt(KEY_UL_PCM, value).apply()

    var dlMixer: String
        get() = voice.getString(KEY_DL_MIXER, LineRoute.DEFAULT_DL_MIXER) ?: LineRoute.DEFAULT_DL_MIXER
        set(value) = voice.edit().putString(KEY_DL_MIXER, value).apply()

    var uplinkGainDb: Int
        get() = voice.getInt(KEY_UL_GAIN, DEFAULT_UL_GAIN_DB)
        set(value) = voice.edit().putInt(KEY_UL_GAIN, value).apply()

    var uplinkPreEmphasis: Boolean
        get() = voice.getBoolean(KEY_UL_TILT, true)
        set(value) = voice.edit().putBoolean(KEY_UL_TILT, value).apply()

    companion object {
        const val PREFS = "wlya_voice"
        /** Empty until user configures relay; example: wss://your-relay.example/v1/call */
        const val DEFAULT_URL = ""
        const val DEFAULT_ROOM = "voice"
        const val DEFAULT_UL_GAIN_DB = 12
        private const val KEY_URL = "url"
        private const val KEY_ROOM = "room"
        private const val KEY_SEED = "seed"
        private const val KEY_CLIENT = "client"
        private const val KEY_DEBUG = "debug_meters"
        private const val KEY_AUTO = "auto_start"
        private const val KEY_UL_MIXER = "ul_mixer"
        private const val KEY_UL_PCM = "ul_pcm"
        private const val KEY_DL_MIXER = "dl_mixer"
        private const val KEY_UL_GAIN = "ul_gain_db"
        private const val KEY_UL_TILT = "ul_pre_emphasis"
    }
}
