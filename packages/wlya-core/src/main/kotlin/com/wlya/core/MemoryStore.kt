package com.wlya.core

/**
 * In-memory Store implementation for testing and prototyping.
 */
class MemoryStore : Store {
    private val store = mutableMapOf<String, Any?>()

    override suspend fun get(key: String): Any? = store[key]

    override suspend fun set(key: String, value: Any) {
        store[key] = value
    }

    override suspend fun remove(key: String) {
        store.remove(key)
    }
}
