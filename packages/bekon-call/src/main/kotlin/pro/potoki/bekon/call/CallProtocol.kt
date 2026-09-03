package pro.potoki.bekon.call

import org.json.JSONObject
import java.util.UUID

object CallProtocol {
    const val TYPE_CTRL = "ctrl"
    const val TYPE_STATE = "phone-state"
    const val TYPE_ACK = "ack"
    const val TYPE_CTRL_ACK = "ctrl-ack"
    const val MODE_PHONE = "phone"
    const val MODE_WALKIE = "walkie"
    const val ACTION_PICKUP = "pickup"
    const val ACTION_CANCEL = "cancel"
    const val ACTION_DIAL = "dial"

    fun pickup(id: String = newId()): String = ctrlJson(action = ACTION_PICKUP, id = id)
    fun cancel(id: String = newId()): String = ctrlJson(action = ACTION_CANCEL, id = id)
    fun dial(number: String, id: String = newId()): String = ctrlJson(action = ACTION_DIAL, number = number, id = id)
    fun setMode(mode: String, id: String = newId()): String = ctrlJson(mode = mode, id = id)

    fun newId(): String = UUID.randomUUID().toString()

    fun ctrlJson(
        action: String? = null,
        number: String? = null,
        mode: String? = null,
        id: String = newId(),
    ): String {
        val o = JSONObject()
            .put("type", TYPE_CTRL)
            .put("id", id)
        if (action != null) o.put("action", action)
        if (number != null) o.put("number", number)
        if (mode != null) o.put("mode", mode)
        return o.toString()
    }

    fun parsePhoneState(json: String): RemotePhoneState? {
        return try {
            val o = JSONObject(json)
            if (o.optString("type") != TYPE_STATE) return null
            parseStateObject(o)
        } catch (_: Exception) {
            null
        }
    }

    fun parseAck(json: String): CtrlAck? {
        return try {
            val o = JSONObject(json)
            val type = o.optString("type")
            if (type != TYPE_ACK && type != TYPE_CTRL_ACK) return null
            val id = o.optString("id")
            if (id.isBlank()) return null
            val state = if (o.has("state") && o.get("state") is JSONObject) {
                parseStateObject(o.getJSONObject("state"))
            } else {
                null
            }
            CtrlAck(
                id = id,
                ok = o.optBoolean("ok", true),
                error = o.optString("error").takeIf { it.isNotBlank() },
                state = state,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseStateObject(o: JSONObject): RemotePhoneState {
        val rawMode = o.optString("mode").ifBlank { o.optString("active") }
        val mode = when (rawMode) {
            "line" -> MODE_PHONE
            "acoustic" -> MODE_WALKIE
            else -> rawMode.ifBlank { MODE_PHONE }
        }
        return RemotePhoneState(
            call = o.optString("call", "idle").ifBlank { "idle" },
            mode = mode,
            number = o.optString("number"),
            bridge = o.optBoolean("bridge", false),
            capture = o.optBoolean("capture", true),
            playback = o.optBoolean("playback", true),
            dialResult = o.optString("dialResult"),
        )
    }
}

data class RemotePhoneState(
    val call: String,
    val mode: String,
    val number: String,
    val bridge: Boolean,
    val capture: Boolean,
    val playback: Boolean,
    val dialResult: String = "",
)

data class CtrlAck(
    val id: String,
    val ok: Boolean,
    val error: String?,
    val state: RemotePhoneState?,
)

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
                if (o.optString("type") != CallProtocol.TYPE_CTRL) return null
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
