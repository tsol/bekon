package pro.potoki.bekon.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Incoming /v1/call PCM → WAV in the same folder as GSM record. */
object GsmWsRecord {
    private const val TAG = "GsmWsRecord"

    @Volatile var running = false
        private set
    @Volatile var status = "WebSocket record off."
        private set

    private val lock = Any()
    private var out: RandomAccessFile? = null
    private var dest: File? = null
    private var pcmBytes = 0L

    fun start(ctx: Context) {
        GsmEchoTest.stop()
        GsmRecordTest.stop()
        GsmPlayTest.stop()
        GsmWsPlay.stop()
        GsmRecordTest.stopSpeaker()
        synchronized(lock) {
            if (running) return
            if (!VoiceService.socketJoined) {
                status = "Connect Voice first (WebSocket)."
                return
            }
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val name = "ws-in-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
            val file = File(dir, name)
            val raf = try {
                RandomAccessFile(file, "rw").also { WavPcm16.writeHeader(it, 0) }
            } catch (e: Exception) {
                status = "Cannot create ${file.name}: ${e.message}"
                return
            }
            out = raf
            dest = file
            pcmBytes = 0
            running = true
            status = "Recording WebSocket → ${file.name}"
            Log.i(TAG, status)
        }
    }

    fun append(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        synchronized(lock) {
            if (!running) return
            val raf = out ?: return
            try {
                raf.write(pcm)
                pcmBytes += pcm.size
            } catch (e: Exception) {
                Log.w(TAG, "write: ${e.message}")
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running && out == null) return
            running = false
            val raf = out
            val file = dest
            out = null
            dest = null
            if (raf != null && file != null) {
                try {
                    WavPcm16.writeHeader(raf, pcmBytes)
                    raf.close()
                    GsmRecordTest.adopt(file)
                    status = "Saved ${file.name} (${pcmBytes} bytes PCM)"
                    Log.i(TAG, status)
                } catch (e: Exception) {
                    status = "Save failed: ${e.message}"
                    Log.w(TAG, "close: ${e.message}")
                }
            } else {
                status = "WebSocket record off."
            }
        }
    }
}
