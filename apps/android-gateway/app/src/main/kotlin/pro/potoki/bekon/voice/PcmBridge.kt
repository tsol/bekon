package pro.potoki.bekon.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread

class PcmBridge(
    private val onCapture: (ByteArray) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var captureEnabled = true
    @Volatile private var playbackEnabled = true
    @Volatile private var sourceName = VoiceLineState.SOURCE_VOICE_COMM
    @Volatile private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recThread: Thread? = null

    fun start() {
        if (running) return
        captureEnabled = true
        playbackEnabled = true
        val rec = openRecord(sourceName)
        record = rec.record
        sourceName = rec.source
        val play = openTrack()
        track = play
        running = true
        rec.record.startRecording()
        if (playbackEnabled) play.play()
        recThread = thread(name = "voice-rec") {
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
                if (n == VoicePcm.FRAME_BYTES) {
                    VoiceMeters.noteMic(buf)
                    if (captureEnabled) onCapture(buf.copyOf())
                }
            }
        }
        Log.i(TAG, "pcm started source=$sourceName")
    }

    fun play(pcm: ByteArray) {
        if (!playbackEnabled) return
        val t = track ?: return
        if (pcm.isEmpty()) return
        VoiceMeters.noteWalkieSpk(pcm)
        t.write(pcm, 0, pcm.size)
    }

    fun setCapture(on: Boolean) {
        captureEnabled = on
    }

    /** Release the walkie mic. setCapture(false) is not enough — HAL still holds VOICE_COMMUNICATION. */
    fun parkMic() {
        val old = record
        record = null
        try {
            old?.stop()
        } catch (_: Exception) {
        }
        old?.release()
    }

    fun unparkMic() {
        if (!running || record != null) return
        val rec = openRecord(sourceName)
        record = rec.record
        sourceName = rec.source
        rec.record.startRecording()
        Log.i(TAG, "mic unparked source=$sourceName")
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

    fun setSource(name: String): String {
        val wanted = name.ifBlank { VoiceLineState.SOURCE_VOICE_COMM }
        if (!running) {
            sourceName = wanted
            return wanted
        }
        val opened = openRecord(wanted)
        val old = record
        record = opened.record
        sourceName = opened.source
        opened.record.startRecording()
        try { old?.stop() } catch (_: Exception) {}
        old?.release()
        Log.i(TAG, "source -> $sourceName (wanted $wanted)")
        return sourceName
    }

    fun source(): String = sourceName
    fun captureOn(): Boolean = captureEnabled
    fun playbackOn(): Boolean = playbackEnabled

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

    private data class OpenedRecord(val record: AudioRecord, val source: String)

    private fun openRecord(wanted: String): OpenedRecord {
        val chain = sourceChain(wanted)
        var lastError: Exception? = null
        for (name in chain) {
            try {
                val rec = AudioRecord(
                    audioSourceConst(name),
                    VoicePcm.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recBuf(),
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    continue
                }
                tryDisableFx(rec.audioSessionId)
                return OpenedRecord(rec, name)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("AudioRecord init failed")
    }

    private fun openTrack(): AudioTrack {
        val minPlay = AudioTrack.getMinBufferSize(
            VoicePcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
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
                .setBufferSizeInBytes(minPlay.coerceAtLeast(VoicePcm.FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                VoicePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minPlay.coerceAtLeast(VoicePcm.FRAME_BYTES * 4),
                AudioTrack.MODE_STREAM,
            )
        }
    }

    private fun recBuf(): Int {
        val minRec = AudioRecord.getMinBufferSize(
            VoicePcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return minRec.coerceAtLeast(VoicePcm.FRAME_BYTES * 4)
    }

    private fun sourceChain(wanted: String): List<String> {
        val first = when (wanted) {
            VoiceLineState.SOURCE_UNPROCESSED,
            VoiceLineState.SOURCE_MIC,
            VoiceLineState.SOURCE_VOICE_COMM -> wanted
            else -> VoiceLineState.SOURCE_VOICE_COMM
        }
        return listOf(first, VoiceLineState.SOURCE_MIC).distinct()
    }

    private fun audioSourceConst(name: String): Int = when (name) {
        VoiceLineState.SOURCE_UNPROCESSED ->
            if (Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.MIC
        VoiceLineState.SOURCE_MIC -> MediaRecorder.AudioSource.MIC
        else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    private fun tryDisableFx(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.enabled = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "aec: ${e.message}")
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.enabled = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "ns: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PcmBridge"
    }
}
