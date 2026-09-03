package pro.potoki.bekon.voice

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Level shaping for WS PCM before it is injected into GSM uplink.
 *
 * Incall_Music arrives at the vocoder without the mic path's AGC, so desktop
 * speech reaches the far end far quieter than the same speech in WALKIE mode.
 * Gain is applied here rather than on the desktop so the WebSocket keeps a
 * clean, unclipped signal for meters and recording.
 */
object UplinkGain {
    const val MAX_DB = 24

    /** Above this the soft knee starts, so peaks compress instead of clipping. */
    private const val KNEE = 24_000f
    private const val CEILING = 32_767f

    /**
     * AMR narrowband keeps only 300–3400 Hz, and that band carries consonants.
     * A mild first-order tilt makes speech intelligible after the vocoder.
     */
    private const val TILT = 0.45f

    @Volatile var gainDb: Int = 12
    @Volatile var preEmphasis: Boolean = true

    /** Filter state; only the single tap playback thread touches it. */
    private var prev = 0f

    fun load(prefs: VoicePrefs) {
        gainDb = prefs.uplinkGainDb.coerceIn(0, MAX_DB)
        preEmphasis = prefs.uplinkPreEmphasis
    }

    fun setGainDb(db: Int, prefs: VoicePrefs): Int {
        val v = db.coerceIn(0, MAX_DB)
        gainDb = v
        prefs.uplinkGainDb = v
        return v
    }

    fun setPreEmphasis(on: Boolean, prefs: VoicePrefs): Boolean {
        preEmphasis = on
        prefs.uplinkPreEmphasis = on
        return on
    }

    fun reset() {
        prev = 0f
    }

    fun label(): String = "+${gainDb}dB${if (preEmphasis) " tilt" else ""}"

    /** Shapes [pcm] (mono LE16) in place. */
    fun apply(pcm: ByteArray) {
        val tilt = preEmphasis
        val gain = 10f.pow(gainDb / 20f)
        if (!tilt && gain <= 1.0001f) return
        var i = 0
        while (i + 1 < pcm.size) {
            val raw = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            var v = raw.toFloat()
            if (tilt) {
                val hp = v - prev
                prev = v
                v += TILT * hp
            }
            val s = softClip(v * gain).toInt()
            pcm[i] = (s and 0xFF).toByte()
            pcm[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val room = CEILING - KNEE
        val comp = KNEE + room * (1f - exp(-(a - KNEE) / room))
        return if (x >= 0f) comp else -comp
    }
}
