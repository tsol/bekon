package com.wlya.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*

/**
 * Android SharedPreferences Store implementation.
 * Values are stored as JSON strings; callers cast on retrieval.
 */
class AndroidStore(context: Context, name: String = "wlya_store") : Store {
    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun get(key: String): Any? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val element = json.parseToJsonElement(raw)
            jsonElementToAny(element)
        } catch (_: Exception) {
            raw
        }
    }

    override suspend fun set(key: String, value: Any) {
        prefs.edit().putString(key, json.encodeToString(anyToJsonElement(value))).apply()
    }

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun keys(): Set<String> = prefs.all.keys
    fun clear() = prefs.edit().clear().apply()

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        is Map<*, *> -> JsonObject(
            value.mapKeys { it.key.toString() }
                .mapValues { anyToJsonElement(it.value) }
        )
        else -> JsonPrimitive(value.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
        is JsonPrimitive -> {
            if (element.isString) element.content
            else element.booleanOrNull
                ?: element.intOrNull
                ?: element.longOrNull
                ?: element.doubleOrNull
                ?: element.content
        }
        is JsonArray -> element.map { jsonElementToAny(it) }
        is JsonObject -> element.mapValues { jsonElementToAny(it.value) }
        else -> null
    }
}
