package pro.potoki.bekon.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Debug-only GSM meters. Does not need Voice WS or the WLYA tunnel.
 * gsmIn = VOICE_DOWNLINK (remote), gsmOut = VOICE_UPLINK (your voice on the radio).
 */
object GsmLevelProbe {
    private const val TAG = "GsmLevelProbe"

    @Volatile private var running = false
    private var downRec: AudioRecord? = null
    private var downThread: Thread? = null
    private var upCap: QcomVocTap.UplinkCap? = null
    private val lock = Any()

    fun sync(ctx: Context) {
        if (GsmEchoTest.running || VoiceService.gsmLineLive || GsmRecordTest.running || GsmPlayTest.running) {
            stop()
            return
        }
        if (VoiceMeters.debug) start(ctx.applicationContext) else stop()
    }

    fun start(ctx: Context) {
        synchronized(lock) {
            if (running) return
            if (GsmEchoTest.running || VoiceService.gsmLineLive || GsmRecordTest.running || GsmPlayTest.running) return
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                VoiceMeters.setTapHint("GSM tap: RECORD_AUDIO not granted")
                return
            }
            val am = ctx.getSystemService(AudioManager::class.java)
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val offhook = try {
                @Suppress("DEPRECATION")
                tm?.callState == TelephonyManager.CALL_STATE_OFFHOOK
            } catch (_: Exception) {
                false
            }
            if (offhook && am != null) am.mode = AudioManager.MODE_IN_CALL
            LineRoute.load(VoicePrefs(ctx))
            QcomVocTap.acquire()
            QcomVocTap.setUlTap(true)

            val skipDown = VoiceService.gsmLineLive || GsmEchoTest.running || GsmRecordTest.running || GsmPlayTest.running
            val down = if (skipDown) null else open(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                ?: open(MediaRecorder.AudioSource.VOICE_CALL)

            if (down == null && !skipDown) {
                QcomVocTap.release()
                VoiceMeters.setTapHint(
                    "GSM tap failed (need Magisk priv-app CAPTURE_AUDIO_OUTPUT). call=${callLabel(tm)}",
                )
                return
            }
            running = true
            downRec = down
            try {
                down?.startRecording()
            } catch (e: Exception) {
                Log.w(TAG, "down start: ${e.message}")
            }
            if (down != null) {
                downThread = thread(name = "gsm-dl") {
                    drain(down) { buf, n -> VoiceMeters.noteGsmIn(buf, n) }
                }
            }
            val ul = QcomVocTap.UplinkCap(ctx.cacheDir) { buf, n ->
                VoiceMeters.noteGsmOut(buf, n)
                VoiceMeters.noteMic(if (n == buf.size) buf else buf.copyOf(n))
            }
            upCap = ul
            ul.start()
            val dl = when {
                skipDown -> "line-bridge"
                down != null -> srcName(down.audioSource)
                else -> "fail"
            }
            VoiceMeters.setTapHint(
                "GSM tap dl=$dl ul=${LineRoute.ulMixer} pcm${LineRoute.ulPcm} call=${callLabel(tm)} ${QcomVocTap.lastHint}",
            )
            Log.i(TAG, VoiceMeters.tapHint)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            downThread?.join(400)
            downThread = null
            upCap?.stop()
            upCap = null
            release(downRec)
            downRec = null
            QcomVocTap.release()
            VoiceMeters.setTapHint("")
        }
    }

    private fun drain(rec: AudioRecord, note: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(VoicePcm.FRAME_BYTES)
        while (running) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (_: Exception) {
                -1
            }
            if (n > 0) note(buf, n)
        }
    }

    private fun open(source: Int): AudioRecord? {
        return try {
            val rec = AudioRecord(
                source,
                VoicePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                VoicePcm.recBufBytes(),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                null
            } else rec
        } catch (e: Exception) {
            Log.w(TAG, "open $source: ${e.message}")
            null
        }
    }

    private fun release(rec: AudioRecord?) {
        if (rec == null) return
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
    }

    private fun callLabel(tm: TelephonyManager?): String = try {
        @Suppress("DEPRECATION")
        when (tm?.callState) {
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_IDLE -> "idle"
            else -> "unknown"
        }
    } catch (_: Exception) {
        "unknown"
    }

    private fun srcName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        else -> if (Build.VERSION.SDK_INT >= 24) "src=$source" else "src=$source"
    }
}
