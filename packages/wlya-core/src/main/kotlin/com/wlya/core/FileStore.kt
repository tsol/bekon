package com.wlya.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/**
 * JSON file-based Store implementation.
 *
 * Converts primitives, maps, and lists to JSON automatically. The round-trip
 * supports `String`, `Number`, `Boolean`, `List`, and `Map`.
 *
 * For kotlinx-serializable data classes, callers should use [setObject] / [getObject]
 * which encode via kotlinx.serialization.
 */
class FileStore(private val path: String) : Store {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var data = mutableMapOf<String, String>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val file = File(path)
        if (file.exists()) {
            try {
                data = json.decodeFromString(file.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                data = mutableMapOf()
            }
        }
    }

    private fun save() {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(data), Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[FileStore] save error: ${e.message}")
        }
    }

    override suspend fun get(key: String): Any? {
        load()
        val raw = data[key] ?: return null
        return try {
            val element = json.parseToJsonElement(raw)
            jsonElementToAny(element)
        } catch (_: Exception) {
            raw
        }
    }

    override suspend fun set(key: String, value: Any) {
        load()
        data[key] = json.encodeToString(anyToJsonElement(value))
        save()
    }

    override suspend fun remove(key: String) {
        load()
        data.remove(key)
        save()
    }

    // ---------- helpers ----------

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
        else -> throw IllegalArgumentException("Unsupported type: ${value::class}")
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
