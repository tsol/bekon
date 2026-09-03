package com.wlya.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Simple key-value store interface.
 * Values are stored as JSON-serializable objects; callers cast on retrieval.
 */
interface Store {
    suspend fun get(key: String): Any?
    suspend fun set(key: String, value: Any)
    suspend fun remove(key: String)
}

/** Serialize a kotlinx-serializable value to JSON and store it. */
suspend inline fun <reified T> Store.setObject(key: String, value: T) {
    set(key, Json.encodeToString(value))
}

/** Retrieve a JSON string and deserialize it via kotlinx.serialization. */
suspend inline fun <reified T> Store.getObject(key: String): T? {
    val raw = get(key) ?: return null
    val str = raw as? String ?: return null
    return try { Json.decodeFromString(str) } catch (_: Exception) { null }
}
