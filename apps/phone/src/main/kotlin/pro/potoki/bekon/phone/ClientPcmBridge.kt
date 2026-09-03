package pro.potoki.bekon.phone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import pro.potoki.bekon.call.VoicePcm
import kotlin.concurrent.thread

class ClientPcmBridge(
    private val onCapture: (ByteArray) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var captureEnabled = true
    @Volatile private var playbackEnabled = true
    @Volatile private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recThread: Thread? = null

    fun start() {
        if (running) return
        captureEnabled = true
        playbackEnabled = true
        val rec = openRecord()
        record = rec
        val play = openTrack()
        track = play
        running = true
        rec.startRecording()
        if (playbackEnabled) play.play()
        recThread = thread(name = "phone-rec") {
            val buf = ByteArray(VoicePcm.FRAME_BYTES)
            while (running) {
                val r = record
                if (r == null) {
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }
                val n = try {
                    r.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    -1
                }
                if (n == VoicePcm.FRAME_BYTES && captureEnabled) {
                    onCapture(buf.copyOf())
                }
            }
        }
        Log.i(TAG, "pcm started")
    }

    fun play(pcm: ByteArray) {
        if (!playbackEnabled) return
        val t = track ?: return
        if (pcm.isEmpty()) return
        t.write(pcm, 0, pcm.size)
    }

    fun setCapture(on: Boolean) {
        captureEnabled = on
    }

    fun setPlayback(on: Boolean) {
        playbackEnabled = on
        val t = track ?: return
        try {
            if (on) t.play() else t.pause()
        } catch (e: Exception) {
            Log.w(TAG, "playback: ${e.message}")
        }
    }

    fun setSpeakerphone(am: AudioManager, on: Boolean) {
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        } catch (e: Exception) {
            Log.w(TAG, "speaker: ${e.message}")
        }
    }

    fun stop() {
        running = false
        recThread?.join(500)
        recThread = null
        try { record?.stop() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        record?.release()
        track?.release()
        record = null
        track = null
    }

    private fun openRecord(): AudioRecord {
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            VoicePcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            VoicePcm.recBufBytes(),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord init failed")
        }
        return rec
    }

    private fun openTrack(): AudioTrack {
        val minPlay = AudioTrack.getMinBufferSize(
            VoicePcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buf = minPlay.coerceAtLeast(VoicePcm.FRAME_BYTES * 4)
        return if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(VoicePcm.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                VoicePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf,
                AudioTrack.MODE_STREAM,
            )
        }
    }

    companion object {
        private const val TAG = "ClientPcm"
    }
}
