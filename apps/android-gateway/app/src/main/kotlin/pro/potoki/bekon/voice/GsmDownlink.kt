package pro.potoki.bekon.voice

import pro.potoki.bekon.call.VoicePcm

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

internal object GsmDownlink {
    private const val TAG = "GsmDownlink"

    fun openRecord(): AudioRecord? {
        for (src in listOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_CALL,
        )) {
            try {
                val r = AudioRecord(
                    src,
                    VoicePcm.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    VoicePcm.recBufBytes(),
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "rec $src: ${e.message}")
            }
        }
        return null
    }
}
