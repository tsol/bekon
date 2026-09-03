package pro.potoki.bekon.voice

import org.json.JSONObject
import pro.potoki.bekon.call.VoiceLatency

data class VoiceLineState(
    val call: String = "idle",
    val capture: Boolean = true,
    val playback: Boolean = true,
    val source: String = SOURCE_VOICE_COMM,
    val speaker: Boolean = false,
    val root: Boolean = false,
    val mode: String = MODE_PHONE,
    val backends: List<String> = listOf(MODE_WALKIE, MODE_PHONE),
    val number: String = "",
    val dialResult: String = "",
    val bridge: Boolean = false,
    val tapDiag: String = "",
    val localTxMute: String = LocalLineMute.NORMAL,
    val localMuteResult: String = "",
    val uplinkGainDb: Int = VoicePrefs.DEFAULT_UL_GAIN_DB,
    val uplinkTilt: Boolean = true,
) {
    fun toJson(): JSONObject {
        val be = org.json.JSONArray()
        for (b in backends) be.put(b)
        val o = JSONObject()
            .put("call", call)
            .put("capture", capture)
            .put("playback", playback)
            .put("source", source)
            .put("speaker", speaker)
            .put("root", root)
            .put("mode", mode)
            .put("active", mode)
            .put("backends", be)
            .put("number", number)
            .put("dialResult", dialResult)
            .put("bridge", bridge)
            .put("tapDiag", tapDiag)
            .put("localTxMute", localTxMute)
            .put("localMuteResult", localMuteResult)
            .put("uplinkGainDb", uplinkGainDb)
            .put("uplinkTilt", uplinkTilt)
            .put("flow", VoiceMeters.flowJson())
        VoiceLatency.putJson(o)
        if (VoiceMeters.debug) o.put("meters", VoiceMeters.toJson())
        return o
    }

    fun label(): String {
        val num = if (number.isNotBlank()) " $number" else ""
        val dial = if (dialResult.isNotBlank()) " dial=$dialResult" else ""
        val tap = if (bridge) " TAP" else ""
        val flow = " ${VoiceMeters.flowLabel()}"
        val why = if (tapDiag.isNotBlank()) " [$tapDiag]" else ""
        return "call=$call$num mode=$mode$tap$flow$dial$why"
    }

    companion object {
        const val TYPE_CTRL = "ctrl"
        const val TYPE_PING = "ping"
        const val TYPE_ACK = "ack"
        const val TYPE_STATE = "phone-state"

        const val SOURCE_VOICE_COMM = "voice_comm"
        const val SOURCE_UNPROCESSED = "unprocessed"
        const val SOURCE_MIC = "mic"
        const val SOURCE_VOICE_DOWNLINK = "voice_downlink"
        const val SOURCE_VOICE_UPLINK = "voice_uplink"
        const val SOURCE_VOICE_CALL = "voice_call"

        const val MODE_WALKIE = "walkie"
        const val MODE_PHONE = "phone"
        /** @deprecated same as [MODE_PHONE] */
        const val MODE_LINE = MODE_PHONE
        const val MODE_ACOUSTIC = "acoustic"

        const val ACTION_PICKUP = "pickup"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_DIAL = "dial"
        const val ACTION_LOCAL_TX_NORMAL = "local-tx-normal"
        const val ACTION_LOCAL_TX_ADC0 = "local-tx-adc0"
        const val ACTION_LOCAL_TX_DEC0 = "local-tx-dec0"
        const val ACTION_LOCAL_TX_MUX_ZERO = "local-tx-mux-zero"
        const val ACTION_LOCAL_RESTORE = "local-restore"
        const val ACTION_UPLINK_GAIN = "uplink-gain"
        const val ACTION_UPLINK_TILT = "uplink-tilt"
        const val ACTION_LATENCY_PRESET = VoiceLatency.ACTION_LATENCY_PRESET
        const val ACTION_BUF_MULT = VoiceLatency.ACTION_BUF_MULT
        const val ACTION_INJECT_MULT = VoiceLatency.ACTION_INJECT_MULT
        const val ACTION_LATENCY_RESET = VoiceLatency.ACTION_LATENCY_RESET

        fun normalizeMode(raw: String): String = when (raw) {
            "line", MODE_PHONE -> MODE_PHONE
            MODE_ACOUSTIC -> MODE_WALKIE
            else -> raw
        }
    }
}

data class VoiceCtrlMsg(
    val id: String,
    val capture: Boolean?,
    val playback: Boolean?,
    val source: String?,
    val speaker: Boolean?,
    val mode: String?,
    val action: String?,
    val number: String?,
) {
    companion object {
        fun parse(text: String): VoiceCtrlMsg? {
            return try {
                val o = JSONObject(text)
                if (o.optString("type") != VoiceLineState.TYPE_CTRL) return null
                val id = o.optString("id")
                if (id.isBlank()) return null
                VoiceCtrlMsg(
                    id = id,
                    capture = if (o.has("capture")) o.optBoolean("capture") else null,
                    playback = if (o.has("playback")) o.optBoolean("playback") else null,
                    source = o.optString("source").takeIf { it.isNotBlank() },
                    speaker = if (o.has("speaker")) o.optBoolean("speaker") else null,
                    mode = o.optString("mode").takeIf { it.isNotBlank() },
                    action = o.optString("action").takeIf { it.isNotBlank() },
                    number = o.optString("number").takeIf { it.isNotBlank() },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
