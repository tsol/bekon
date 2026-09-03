package com.wlya.core.adapters

import com.wlya.core.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Date

internal const val TUNNEL_SUBJECT_MARKER = "[TUNNEL]"

internal fun unwrapTransportJson(body: String): String {
    val start = body.indexOf("-----BEGIN WLYA-----")
    val end = body.indexOf("-----END WLYA-----")
    if (start == -1 || end == -1 || end <= start) {
        return body.replace(Regex("^[^\\{]*"), "").replace(Regex("[^\\}]*$"), "")
    }
    val b64 = body.substring(start + "-----BEGIN WLYA-----".length, end).trim()
    return try {
        String(Base64.decode(b64), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }
}

internal fun tunnelMessageAgeMs(body: String, receivedDate: Date?, sentDate: Date?): Long? {
    val json = unwrapTransportJson(body)
    if (json.isNotEmpty()) {
        val ts = parseTransportTimestamp(json)
        if (ts != null && ts > 0L) return ts
    }
    return receivedDate?.time ?: sentDate?.time
}

internal fun parseTransportTimestamp(json: String): Long? {
    val start = json.indexOf('{')
    val end = json.lastIndexOf('}')
    if (start == -1 || end == -1 || end <= start) return null
    return try {
        val element = Json.parseToJsonElement(json.substring(start, end + 1))
        if (element !is JsonObject) return null
        element["timestamp"]?.jsonPrimitive?.longOrNull
    } catch (_: Exception) {
        null
    }
}
