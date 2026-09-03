package pro.potoki.bekon.voice

import android.media.AudioManager
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Cheap PCM peak meters. Hot path is a no-op unless [debug] is on.
 *
 * Frame/silence counters are the exception: they are always on, because a peak
 * of 0 cannot tell "leg never ran" apart from "leg ran and carried zeros", and
 * that distinction is the whole diagnosis for the live bridge.
 */
object VoiceMeters {
    const val LEG_GSM_IN = "gsmIn"
    const val LEG_GSM_OUT = "gsmOut"
    const val LEG_WS_IN = "wsIn"
    const val LEG_WS_OUT = "wsOut"

    @Volatile var debug: Boolean = false

    private val mic = AtomicInteger()
    private val walkieSpk = AtomicInteger()
    private val wsIn = AtomicInteger()
    private val wsOut = AtomicInteger()
    private val gsmIn = AtomicInteger()
    private val gsmOut = AtomicInteger()

    private val flow = mapOf(
        LEG_GSM_IN to Leg(),
        LEG_GSM_OUT to Leg(),
        LEG_WS_IN to Leg(),
        LEG_WS_OUT to Leg(),
    )

    @Volatile var volCall = 0
        private set
    @Volatile var volMusic = 0
        private set
    @Volatile var volCallText = ""
        private set
    @Volatile var volMusicText = ""
        private set
    @Volatile var audioMode = "unknown"
        private set
    @Volatile var speakerphone = false
        private set

    fun noteMic(pcm: ByteArray) = bump(mic, pcm)
    fun noteWalkieSpk(pcm: ByteArray) = bump(walkieSpk, pcm)

    fun noteWsIn(pcm: ByteArray) {
        note(LEG_WS_IN, pcm, pcm.size)
        bump(wsIn, pcm)
    }

    fun noteWsOut(pcm: ByteArray) {
        note(LEG_WS_OUT, pcm, pcm.size)
        bump(wsOut, pcm)
    }

    @Volatile var tapHint = ""
        private set

    fun noteGsmIn(pcm: ByteArray, n: Int = pcm.size) {
        note(LEG_GSM_IN, pcm, n)
        bump(gsmIn, pcm, n)
    }

    fun noteGsmOut(pcm: ByteArray, n: Int = pcm.size) {
        note(LEG_GSM_OUT, pcm, n)
        bump(gsmOut, pcm, n)
    }

    /** Frames seen on [leg] since reset. */
    fun frames(leg: String): Long = flow[leg]?.frames?.get() ?: 0L

    /** Frames on [leg] that were not digital silence. Real audio is never exactly 0. */
    fun liveFrames(leg: String): Long = flow[leg]?.live?.get() ?: 0L

    fun setTapHint(text: String) {
        tapHint = text
    }

    fun sampleDevice(am: AudioManager?) {
        if (!debug || am == null) return
        val call = stream(am, AudioManager.STREAM_VOICE_CALL)
        val music = stream(am, AudioManager.STREAM_MUSIC)
        volCall = call.pct
        volMusic = music.pct
        volCallText = call.label
        volMusicText = music.label
        audioMode = when (am.mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> "mode=${am.mode}"
        }
        @Suppress("DEPRECATION")
        speakerphone = am.isSpeakerphoneOn
    }

    fun decay() {
        if (!debug) return
        fade(mic)
        fade(walkieSpk)
        fade(wsIn)
        fade(wsOut)
        fade(gsmIn)
        fade(gsmOut)
    }

    /**
     * One compact line per leg: `frames/liveFrames`. Always emitted, unlike [toJson].
     * `0/0` = leg never ran, `n/0` = leg ran but carried only zeros, `n/m` = audio.
     */
    fun flowJson(): JSONObject {
        val o = JSONObject()
        for ((leg, l) in flow) {
            o.put(leg, "${l.frames.get()}/${l.live.get()}")
        }
        return o
    }

    fun flowLabel(): String = flow.entries.joinToString(" ") { (leg, l) ->
        "$leg=${l.frames.get()}/${l.live.get()}"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("mic", mic.get())
        .put("walkieSpk", walkieSpk.get())
        .put("wsIn", wsIn.get())
        .put("wsOut", wsOut.get())
        .put("gsmIn", gsmIn.get())
        .put("gsmOut", gsmOut.get())
        .put("volCall", volCall)
        .put("volMusic", volMusic)
        .put("volCallText", volCallText)
        .put("volMusicText", volMusicText)
        .put("audioMode", audioMode)
        .put("speakerphone", speakerphone)
        .put("tapHint", tapHint)

    fun reset() {
        mic.set(0)
        walkieSpk.set(0)
        wsIn.set(0)
        wsOut.set(0)
        gsmIn.set(0)
        gsmOut.set(0)
        volCall = 0
        volMusic = 0
        volCallText = ""
        volMusicText = ""
        audioMode = "unknown"
        speakerphone = false
        tapHint = ""
        for (l in flow.values) {
            l.frames.set(0)
            l.live.set(0)
        }
    }

    private fun note(leg: String, pcm: ByteArray, n: Int) {
        val l = flow[leg] ?: return
        l.frames.incrementAndGet()
        if (!allZero(pcm, n)) l.live.incrementAndGet()
    }

    private fun allZero(pcm: ByteArray, n: Int): Boolean {
        val end = minOf(n, pcm.size)
        var i = 0
        while (i < end) {
            if (pcm[i] != 0.toByte()) return false
            i++
        }
        return true
    }

    private fun bump(slot: AtomicInteger, pcm: ByteArray, n: Int = pcm.size) {
        if (!debug) return
        val p = peak(pcm, n)
        slot.updateAndGet { old -> if (p > old) p else old }
    }

    private fun fade(slot: AtomicInteger) {
        slot.updateAndGet { (it * 7) / 10 }
    }

    private fun peak(pcm: ByteArray, n: Int = pcm.size): Int {
        var max = 0
        var i = 0
        while (i + 1 < n && i + 1 < pcm.size) {
            var s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            if (s >= 32768) s -= 65536
            val a = if (s < 0) -s else s
            if (a > max) max = a
            i += 2
        }
        return ((max * 100) / 32768).coerceIn(0, 100)
    }

    private class Leg {
        val frames = AtomicLong()
        val live = AtomicLong()
    }

    private data class StreamKnob(val pct: Int, val label: String)

    private fun stream(am: AudioManager, stream: Int): StreamKnob {
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val n = am.getStreamVolume(stream).coerceIn(0, max)
        return StreamKnob((n * 100) / max, "$n/$max")
    }
}
