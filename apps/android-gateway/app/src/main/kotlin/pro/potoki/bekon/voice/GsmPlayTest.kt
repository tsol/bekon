package pro.potoki.bekon.voice

import pro.potoki.bekon.call.VoicePcm

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

/** Play the last GSM-record WAV into the call (Incall_Music), same inject as Echo. */
object GsmPlayTest {
    private const val TAG = "GsmPlay"

    @Volatile var running = false
        private set
    @Volatile var status = "Play to GSM idle."
        private set

    private val lock = Any()
    private var track: AudioTrack? = null
    private var loop: Thread? = null

    fun start(ctx: Context) {
        GsmEchoTest.stop()
        GsmRecordTest.stop()
        GsmRecordTest.stopSpeaker()
        GsmWsPlay.stop()
        GsmWsRecord.stop()
        synchronized(lock) {
            if (running) return
            val file = GsmRecordTest.resolvedFile(ctx)
            if (file == null || !file.isFile) {
                status = "No saved recording."
                return
            }
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val offhook = try {
                @Suppress("DEPRECATION")
                tm?.callState == TelephonyManager.CALL_STATE_OFFHOOK
            } catch (_: Exception) {
                false
            }
            if (!offhook) {
                status = "No live call. Answer, then Play to GSM."
                return
            }
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am != null) am.mode = AudioManager.MODE_IN_CALL
            GsmLevelProbe.stop()
            LineRoute.load(VoicePrefs(ctx))
            QcomVocTap.acquire()
            QcomVocTap.setInject(true)
            val play = IncallMusicTrack.open()
            if (play.state != AudioTrack.STATE_INITIALIZED) {
                play.release()
                QcomVocTap.setInject(false)
                QcomVocTap.release()
                status = "Play: Incall track failed."
                GsmLevelProbe.sync(ctx)
                return
            }
            track = play
            running = true
            try {
                play.play()
            } catch (e: Exception) {
                Log.w(TAG, "play: ${e.message}")
            }
            val wav = file
            loop = thread(name = "gsm-play") {
                pump(wav, play)
                if (running) stop()
            }
            status = "Playing ${file.name} into GSM"
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
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            track = null
            QcomVocTap.setInject(false)
            QcomVocTap.release()
            status = "Play to GSM stopped."
        }
    }

    private fun pump(file: File, play: AudioTrack) {
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
                while (running) {
                    val got = ins.read(buf)
                    if (got <= 0) break
                    val frame = if (got == buf.size) buf else buf.copyOf(got)
                    IncallMusicTrack.writeMono(play, frame)
                    VoiceMeters.noteGsmOut(frame, frame.size)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pump: ${e.message}")
        }
    }
}
