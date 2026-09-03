package pro.potoki.bekon.voice

import android.content.Context
import android.media.AudioRecord
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/**
 * Same HAL graph as [GsmEchoTest]: VOICE_DOWNLINK + Incall_Music.
 * Echo loops those two together. This sends DL to the WebSocket and plays WS into UL.
 */
class RootCallBridge(
    private val onCapture: (ByteArray) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var captureEnabled = true
    @Volatile private var playbackEnabled = true
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recThread: Thread? = null
    private var wdThread: Thread? = null
    private var audio: android.media.AudioManager? = null
    private var savedMusicVolume: Int? = null
    @Volatile private var diag = "tap not started"
    @Volatile private var reasserts = 0

    fun start(ctx: Context) {
        if (running) return
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        if (am != null) {
            @Suppress("DEPRECATION")
            am.mode = android.media.AudioManager.MODE_IN_CALL
        }
        audio = am
        raiseMusicVolume(am)
        val prefs = VoicePrefs(ctx)
        LineRoute.load(prefs)
        UplinkGain.load(prefs)
        UplinkGain.reset()
        QcomVocTap.acquire()
        try {
            QcomVocTap.setInject(playbackEnabled)
            val rec = GsmDownlink.openRecord()
                ?: throw IllegalStateException("VOICE_DOWNLINK AudioRecord failed (need priv-app + Magisk)")
            record = rec
            val play = IncallMusicTrack.open()
            if (play.state != AudioTrack.STATE_INITIALIZED) {
                rec.release()
                play.release()
                throw IllegalStateException("Incall_Music track failed")
            }
            track = play
            running = true
            rec.startRecording()
            if (playbackEnabled) play.play()
        } catch (e: Exception) {
            QcomVocTap.setInject(false)
            QcomVocTap.release()
            restoreMusicVolume()
            throw e
        }
        recThread = thread(name = "voice-line") {
            val buf = ByteArray(VoicePcm.FRAME_BYTES)
            val acc = ByteArray(VoicePcm.FRAME_BYTES)
            var accN = 0
            while (running) {
                val r = record ?: break
                val n = try {
                    r.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) continue
                VoiceMeters.noteGsmIn(buf, n)
                if (!captureEnabled) continue
                var off = 0
                while (off < n) {
                    val take = minOf(VoicePcm.FRAME_BYTES - accN, n - off)
                    System.arraycopy(buf, off, acc, accN, take)
                    accN += take
                    off += take
                    if (accN == VoicePcm.FRAME_BYTES) {
                        onCapture(acc.copyOf())
                        accN = 0
                    }
                }
            }
        }
        wdThread = thread(name = "voice-line-wd") { watch() }
        Log.i(TAG, "line started source=voice_downlink ${QcomVocTap.lastHint}")
    }

    /**
     * The DL tap can come up wired to nothing: the read loop keeps returning
     * frames, they are just digital zeros. That happens when the modem voice
     * session is programmed after we set VOC_REC_DL. Re-apply the mixer while
     * the downlink stays exactly silent, then stop and leave the reason in [diag].
     */
    private fun watch() {
        val baseFrames = VoiceMeters.frames(VoiceMeters.LEG_GSM_IN)
        val baseLive = VoiceMeters.liveFrames(VoiceMeters.LEG_GSM_IN)
        var lastLive = baseLive
        var silentSince = System.currentTimeMillis()
        var readback = ""
        var gainPasses = 0
        while (running) {
            try {
                Thread.sleep(WATCH_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) break
            // The ADSP programs session volume when the track actually starts,
            // which happens after our first setInject.
            if (gainPasses < GAIN_PASSES && playbackEnabled) {
                gainPasses++
                QcomVocTap.applyInjectGain()
            }
            val frames = VoiceMeters.frames(VoiceMeters.LEG_GSM_IN) - baseFrames
            val live = VoiceMeters.liveFrames(VoiceMeters.LEG_GSM_IN)
            val now = System.currentTimeMillis()
            if (live > lastLive) {
                lastLive = live
                silentSince = now
            }
            if (readback.isEmpty()) readback = QcomVocTap.readback()
            val silentMs = now - silentSince
            val dead = frames > 0 && live == baseLive
            if (dead && silentMs >= SILENT_REASSERT_MS && reasserts < MAX_REASSERTS) {
                reasserts++
                Log.w(TAG, "DL silent ${silentMs}ms after $frames frames — reassert #$reasserts")
                QcomVocTap.reassert()
                QcomVocTap.setInject(playbackEnabled)
                readback = QcomVocTap.readback()
                silentSince = now
            }
            diag = "frames=$frames live=${live - baseLive} reassert=$reasserts mixer=$readback"
        }
    }

    /** Why the tap is or is not carrying audio. Safe to read from any thread. */
    fun diag(): String = diag

    fun play(pcm: ByteArray) {
        if (!playbackEnabled) return
        val t = track ?: return
        if (pcm.isEmpty()) return
        try {
            UplinkGain.apply(pcm)
            IncallMusicTrack.writeMono(t, pcm)
            VoiceMeters.noteGsmOut(pcm, pcm.size)
        } catch (e: Exception) {
            // Call teardown can invalidate the native AudioTrack pointer before
            // the telephony callback stops this bridge. Never let that race
            // escape into OkHttp's WebSocket callback and close the socket.
            Log.w(TAG, "drop playback frame: ${e.message}")
        }
    }

    fun setCapture(on: Boolean) {
        captureEnabled = on
    }

    fun setPlayback(on: Boolean) {
        playbackEnabled = on
        QcomVocTap.setInject(on)
        val t = track ?: return
        try {
            if (on) t.play() else t.pause()
        } catch (e: Exception) {
            Log.w(TAG, "playback: ${e.message}")
        }
    }

    fun source(): String = VoiceLineState.SOURCE_VOICE_DOWNLINK
    fun captureOn(): Boolean = captureEnabled
    fun playbackOn(): Boolean = playbackEnabled

    fun stop() {
        running = false
        recThread?.join(500)
        recThread = null
        wdThread?.interrupt()
        wdThread?.join(500)
        wdThread = null
        try { record?.stop() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        record?.release()
        track?.release()
        record = null
        track = null
        QcomVocTap.setInject(false)
        QcomVocTap.release()
        restoreMusicVolume()
    }

    /**
     * Incall_Music rides STREAM_MUSIC, so the user's media volume index scales
     * the uplink. Take it to max for the call and put it back afterwards.
     */
    private fun raiseMusicVolume(am: android.media.AudioManager?) {
        if (am == null) return
        val stream = android.media.AudioManager.STREAM_MUSIC
        try {
            val max = am.getStreamMaxVolume(stream)
            val current = am.getStreamVolume(stream)
            if (current >= max) return
            savedMusicVolume = current
            am.setStreamVolume(stream, max, 0)
        } catch (e: Exception) {
            // Do Not Disturb blocks volume changes; the gain stage still helps.
            Log.w(TAG, "music volume: ${e.message}")
        }
    }

    private fun restoreMusicVolume() {
        val am = audio
        val saved = savedMusicVolume
        audio = null
        savedMusicVolume = null
        if (am == null || saved == null) return
        try {
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, saved, 0)
        } catch (e: Exception) {
            Log.w(TAG, "restore music volume: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RootCallBridge"
        private const val WATCH_MS = 500L
        private const val SILENT_REASSERT_MS = 1500L
        private const val MAX_REASSERTS = 4
        private const val GAIN_PASSES = 3
    }
}
