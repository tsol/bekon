package com.wlya.core

import kotlinx.serialization.json.Json

object AdvertiseAdapters {
    const val CMD = "advertise-adapters"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: AdvertiseAdaptersPayload): String =
        json.encodeToString(AdvertiseAdaptersPayload.serializer(), payload)

    fun parse(text: String): AdvertiseAdaptersPayload? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val payload = json.decodeFromString(AdvertiseAdaptersPayload.serializer(), trimmed)
            if (payload.cmd != CMD) null else payload
        } catch (_: Exception) {
            null
        }
    }
}
