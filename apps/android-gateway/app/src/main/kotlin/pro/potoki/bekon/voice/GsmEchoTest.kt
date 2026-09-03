package pro.potoki.bekon.voice

import pro.potoki.bekon.call.VoicePcm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Phone-only GSM loopback: downlink PCM → Incall_Music uplink.
 * Other party should hear themselves. No WebSocket.
 */
object GsmEchoTest {
    private const val TAG = "GsmEcho"

    @Volatile var running = false
        private set
    @Volatile var status = "Echo off. Place a GSM call, then start."
        private set

    private val lock = Any()
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var loop: Thread? = null

    fun toggle(ctx: Context) {
        if (running) stop() else start(ctx.applicationContext)
    }

    fun start(ctx: Context) {
        synchronized(lock) {
            if (running) return
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                status = "Need microphone permission."
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
                status = "No live call. Call this phone from another, answer, then Echo."
                return
            }
            GsmRecordTest.stop()
            GsmPlayTest.stop()
            GsmWsPlay.stop()
            GsmWsRecord.stop()
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am != null) am.mode = AudioManager.MODE_IN_CALL
            GsmLevelProbe.stop()
            LineRoute.load(VoicePrefs(ctx))
            QcomVocTap.acquire()
            QcomVocTap.setInject(true)
            val record = GsmDownlink.openRecord()
            if (record == null) {
                QcomVocTap.setInject(false)
                QcomVocTap.release()
                status = "Cannot read GSM in (need Magisk priv-app)."
                GsmLevelProbe.sync(ctx)
                return
            }
            val play = IncallMusicTrack.open()
            if (play.state != AudioTrack.STATE_INITIALIZED) {
                play.release()
                record.release()
                QcomVocTap.setInject(false)
                QcomVocTap.release()
                status = "Echo: playback track failed."
                GsmLevelProbe.sync(ctx)
                return
            }
            rec = record
            track = play
            running = true
            try {
                record.startRecording()
                play.play()
            } catch (e: Exception) {
                Log.w(TAG, "start: ${e.message}")
            }
            loop = thread(name = "gsm-echo") { pump(record, play) }
            status = "Echo ON — speak on the OTHER phone. You should hear yourself back."
            Log.i(TAG, status)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            loop?.join(500)
            loop = null
            try { rec?.stop() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            rec?.release()
            track?.release()
            rec = null
            track = null
            QcomVocTap.setInject(false)
            QcomVocTap.release()
            status = "Echo off."
        }
    }

    private fun pump(record: AudioRecord, play: AudioTrack) {
        val mono = ByteArray(VoicePcm.FRAME_BYTES)
        while (running) {
            val n = try {
                record.read(mono, 0, mono.size)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) continue
            VoiceMeters.noteGsmIn(mono, n)
            IncallMusicTrack.writeMono(play, if (n == mono.size) mono else mono.copyOf(n))
            VoiceMeters.noteGsmOut(mono, n)
        }
    }
}
