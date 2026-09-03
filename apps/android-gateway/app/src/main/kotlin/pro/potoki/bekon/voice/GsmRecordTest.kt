package pro.potoki.bekon.voice

import pro.potoki.bekon.call.VoicePcm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Same GSM downlink tap as Echo, written to a WAV instead of looped back. */
object GsmRecordTest {
    private const val TAG = "GsmRecord"

    @Volatile var running = false
        private set
    @Volatile var status = "GSM record off."
        private set
    @Volatile var lastFile: File? = null
        private set

    private val lock = Any()
    private var rec: AudioRecord? = null
    private var loop: Thread? = null
    private var out: RandomAccessFile? = null
    private var pcmBytes = 0L
    private var dest: File? = null
    private var player: MediaPlayer? = null

    fun start(ctx: Context) {
        GsmEchoTest.stop()
        GsmPlayTest.stop()
        GsmWsPlay.stop()
        GsmWsRecord.stop()
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
                status = "No live call. Answer a GSM call, then Record."
                return
            }
            GsmLevelProbe.stop()
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am != null) am.mode = AudioManager.MODE_IN_CALL
            LineRoute.load(VoicePrefs(ctx))
            QcomVocTap.acquire()
            val record = GsmDownlink.openRecord()
            if (record == null) {
                QcomVocTap.release()
                status = "Cannot read GSM in (need Magisk priv-app)."
                GsmLevelProbe.sync(ctx)
                return
            }
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val name = "gsm-in-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
            val file = File(dir, name)
            val raf = try {
                RandomAccessFile(file, "rw").also { WavPcm16.writeHeader(it, 0) }
            } catch (e: Exception) {
                record.release()
                QcomVocTap.release()
                status = "Cannot create ${file.name}: ${e.message}"
                GsmLevelProbe.sync(ctx)
                return
            }
            rec = record
            out = raf
            dest = file
            pcmBytes = 0
            running = true
            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.w(TAG, "start: ${e.message}")
            }
            loop = thread(name = "gsm-record") { pump(record, raf) }
            status = "Recording GSM in → ${file.name}"
            Log.i(TAG, status)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            loop?.join(800)
            loop = null
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
            rec = null
            val raf = out
            val file = dest
            out = null
            dest = null
            if (raf != null && file != null) {
                try {
                    WavPcm16.writeHeader(raf, pcmBytes)
                    raf.close()
                    lastFile = file
                    status = "Saved ${file.name} (${pcmBytes} bytes PCM)"
                } catch (e: Exception) {
                    status = "Save failed: ${e.message}"
                    Log.w(TAG, "close: ${e.message}")
                }
            } else {
                status = "GSM record off."
            }
            QcomVocTap.release()
        }
    }

    fun resolvedFile(ctx: Context): File? {
        lastFile?.takeIf { it.isFile }?.let { return it }
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val found = dir.listFiles { f -> f.isFile && isClipName(f.name) }
            ?.maxByOrNull { it.lastModified() }
        if (found != null) lastFile = found
        return found
    }

    fun adopt(file: File) {
        lastFile = file
    }

    fun isClipName(name: String): Boolean {
        return name.endsWith(".wav") && (name.startsWith("gsm-in-") || name.startsWith("ws-in-"))
    }

    fun playLast(ctx: Context) {
        val file = resolvedFile(ctx)
        if (file == null || !file.isFile) {
            status = "No recording yet."
            return
        }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                prepare()
                setOnCompletionListener { it.release(); if (player === it) player = null }
                start()
            }
            status = "Playing ${file.name}"
        } catch (e: Exception) {
            status = "Play failed: ${e.message}"
            Log.w(TAG, "play: ${e.message}")
        }
    }

    fun stopSpeaker() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
    }

    private fun pump(record: AudioRecord, raf: RandomAccessFile) {
        val buf = ByteArray(VoicePcm.FRAME_BYTES)
        while (running) {
            val n = try {
                record.read(buf, 0, buf.size)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) continue
            VoiceMeters.noteGsmIn(buf, n)
            try {
                raf.write(buf, 0, n)
                pcmBytes += n
            } catch (e: Exception) {
                Log.w(TAG, "write: ${e.message}")
                break
            }
        }
    }
}
