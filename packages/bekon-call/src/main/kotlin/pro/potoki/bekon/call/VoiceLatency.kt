package pro.potoki.bekon.call

import org.json.JSONObject

/** Tunable latency knobs + WS RTT stats (shared by Phone and Gateway). */
object VoiceLatency {
    const val TYPE_PING = "ping"

    const val PRESET_LOW = "low"
    const val PRESET_BALANCED = "balanced"
    const val PRESET_STABLE = "stable"

    const val ACTION_LATENCY_PRESET = "latency-preset"
    const val ACTION_BUF_MULT = "buf-mult"
    const val ACTION_INJECT_MULT = "inject-mult"
    const val ACTION_LATENCY_RESET = "latency-reset"

    @Volatile var bufMult: Int = 4
        private set
    @Volatile var injectMult: Int = 8
        private set
    @Volatile var preset: String = PRESET_BALANCED
        private set
    @Volatile var lastRttMs: Long = -1L
    @Volatile var playUnderruns: Int = 0

    fun frameMs(): Int = VoicePcm.FRAME_MS

    fun applyPreset(name: String) {
        preset = when (name) {
            PRESET_LOW -> {
                bufMult = 2
                injectMult = 4
                PRESET_LOW
            }
            PRESET_STABLE -> {
                bufMult = 8
                injectMult = 16
                PRESET_STABLE
            }
            else -> {
                bufMult = 4
                injectMult = 8
                PRESET_BALANCED
            }
        }
    }

    fun setBufMult(mult: Int) {
        bufMult = mult.coerceIn(2, 16)
        preset = "custom"
    }

    fun setInjectMult(mult: Int) {
        injectMult = mult.coerceIn(2, 32)
        preset = "custom"
    }

    fun reset() {
        applyPreset(PRESET_BALANCED)
        playUnderruns = 0
    }

    fun noteUnderrun() {
        playUnderruns++
    }

    fun putJson(o: JSONObject) {
        o.put("frameMs", frameMs())
        o.put("bufMult", bufMult)
        o.put("injectMult", injectMult)
        o.put("latencyPreset", preset)
        if (lastRttMs >= 0) o.put("wsRttMs", lastRttMs)
        if (playUnderruns > 0) o.put("playUnderruns", playUnderruns)
    }

    fun parseLatencyFields(o: JSONObject): LatencyFields = LatencyFields(
        frameMs = if (o.has("frameMs")) o.optInt("frameMs") else null,
        bufMult = if (o.has("bufMult")) o.optInt("bufMult") else null,
        injectMult = if (o.has("injectMult")) o.optInt("injectMult") else null,
        latencyPreset = o.optString("latencyPreset").takeIf { it.isNotBlank() },
        wsRttMs = if (o.has("wsRttMs")) o.optLong("wsRttMs") else null,
        playUnderruns = if (o.has("playUnderruns")) o.optInt("playUnderruns") else null,
    )

    fun pingJson(id: String): String =
        JSONObject()
            .put("type", TYPE_PING)
            .put("id", id)
            .put("t", System.currentTimeMillis())
            .toString()

    fun recordRtt(sentAt: Long) {
        if (sentAt > 0) {
            lastRttMs = (System.currentTimeMillis() - sentAt).coerceAtLeast(0)
        }
    }
}

data class LatencyFields(
    val frameMs: Int?,
    val bufMult: Int?,
    val injectMult: Int?,
    val latencyPreset: String?,
    val wsRttMs: Long?,
    val playUnderruns: Int?,
)
