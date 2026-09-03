package pro.potoki.bekon.voice

import pro.potoki.bekon.call.VoiceLatency
import pro.potoki.bekon.call.VoicePcm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/** Same path as the working Echo test: MUSIC + Incall_Music mixer. No extra flags. */
object IncallMusicTrack {
    fun open(): AudioTrack = build().apply {
        // Track attenuation stacks on top of the stream index and the ADSP
        // session gain; the uplink needs every dB it can get.
        setVolume(AudioTrack.getMaxVolume())
    }

    private fun build(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            VoicePcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bytes = min.coerceAtLeast(VoicePcm.FRAME_BYTES * VoiceLatency.injectMult)
        return if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(VoicePcm.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                VoicePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bytes,
                AudioTrack.MODE_STREAM,
            )
        }
    }

    fun writeMono(track: AudioTrack, mono: ByteArray) {
        if (mono.isEmpty()) return
        val stereo = upmix(mono)
        track.write(stereo, 0, stereo.size)
    }

    private fun upmix(mono: ByteArray): ByteArray {
        val stereo = ByteArray(mono.size * 2)
        var o = 0
        var i = 0
        while (i + 1 < mono.size) {
            val a = mono[i]
            val b = mono[i + 1]
            stereo[o] = a
            stereo[o + 1] = b
            stereo[o + 2] = a
            stereo[o + 3] = b
            i += 2
            o += 4
        }
        return stereo
    }
}
