package pro.potoki.bekon.voice

import android.media.AudioManager
import android.os.Build

/**
 * Handset mute is not wired. Every mixer/DSP mute we tried either did nothing
 * (tinymix rejects Device Mute=1) or killed the GSM vocoder after ~1s.
 * Speakerphone off only so we do not also blast MUSIC.
 */
object LineHandsetMute {
    fun apply(am: AudioManager) {
        am.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                am.clearCommunicationDevice()
            } catch (_: Exception) {
            }
        }
    }

    fun release(am: AudioManager?) {
        if (am == null) return
        am.isMicrophoneMute = false
    }
}
