package pro.potoki.bekon.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

/** Stream the last GSM-record WAV to the Voice WebSocket (0xA1 PCM). */
object GsmWsPlay {
    private const val TAG = "GsmWsPlay"
    private const val FRAME_MS = 20L
    /** Extra frames before pacing so the desktop jitter buffer is not empty. */
    private const val PREROLL = 10

    @Volatile var running = false
        private set
    @Volatile var status = "Play to WebSocket idle."
        private set

    private val lock = Any()
    private var loop: Thread? = null

    fun start(ctx: Context) {
        GsmEchoTest.stop()
        GsmRecordTest.stop()
        GsmPlayTest.stop()
        GsmRecordTest.stopSpeaker()
        GsmWsRecord.stop()
        synchronized(lock) {
            if (running) return
            val file = GsmRecordTest.resolvedFile(ctx)
            if (file == null || !file.isFile) {
                status = "No saved recording."
                return
            }
            if (!VoiceService.socketJoined) {
                status = "Connect Voice first (WebSocket)."
                return
            }
            VoiceService.setCaptureEnabled(false)
            running = true
            val wav = file
            loop = thread(name = "gsm-ws-play") {
                pump(wav)
                if (running) stop()
            }
            status = "Playing ${file.name} to WebSocket"
            Log.i(TAG, status)
        }
    }

    fun stop() {
        val self = Thread.currentThread()
        synchronized(lock) {
            if (!running) return
            running = false
            if (loop != null && loop !== self) {
                loop?.join(800)
            }
            loop = null
            VoiceService.setCaptureEnabled(true)
            status = "Play to WebSocket stopped."
        }
    }

    private fun pump(file: File) {
        try {
            FileInputStream(file).use { ins ->
                val hdr = ByteArray(44)
                var n = 0
                while (n < 44) {
                    val r = ins.read(hdr, n, 44 - n)
                    if (r <= 0) return
                    n += r
                }
                val buf = ByteArray(VoicePcm.FRAME_BYTES)
                var t0 = 0L
                var paced = 0
                while (running) {
                    val got = ins.read(buf)
                    if (got <= 0) break
                    val packet = if (got == buf.size) buf else buf.copyOf(got)
                    VoiceService.sendPcm(if (got == buf.size) buf else buf.copyOf(got))
                    VoiceMeters.noteWsOut(packet)
                    paced++
                    if (paced == PREROLL) {
                        t0 = SystemClock.elapsedRealtime()
                    } else if (paced > PREROLL) {
                        val wait = t0 + (paced - PREROLL) * FRAME_MS - SystemClock.elapsedRealtime()
                        if (wait > 1) Thread.sleep(wait)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pump: ${e.message}")
        }
    }
}
