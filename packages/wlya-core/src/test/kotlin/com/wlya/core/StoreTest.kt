package com.wlya.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File

class StoreTest {

    @Test
    fun `MemoryStore get-set-remove`() = runTest {
        val store = MemoryStore()
        store.set("key1", "value1")
        assertEquals("value1", store.get("key1"))
        store.set("num", 42)
        assertEquals(42, store.get("num"))
        store.remove("key1")
        assertNull(store.get("key1"))
    }

    @Test
    fun `MemoryStore nested map`() = runTest {
        val store = MemoryStore()
        store.set("map", mapOf("a" to 1, "b" to listOf(1, 2, 3)))
        @Suppress("UNCHECKED_CAST")
        val result = store.get("map") as Map<String, Any?>
        assertEquals(1, result["a"])
    }

    @Test
    fun `FileStore persists data`() = runTest {
        val file = File.createTempFile("wlya-test", ".json")
        file.deleteOnExit()
        val store = FileStore(file.absolutePath)

        store.set("key1", "value1")
        store.set("num", 42)
        store.set("list", listOf("a", "b", "c"))

        // Re-create FileStore to simulate restart
        val store2 = FileStore(file.absolutePath)
        assertEquals("value1", store2.get("key1"))
        assertEquals(42, store2.get("num"))
        @Suppress("UNCHECKED_CAST")
        val list = store2.get("list") as List<String>
        assertEquals(listOf("a", "b", "c"), list)

        store2.remove("key1")
        assertNull(store2.get("key1"))
    }

    @Test
    fun `FileStore nested map roundtrip`() = runTest {
        val file = File.createTempFile("wlya-test2", ".json")
        file.deleteOnExit()
        val store = FileStore(file.absolutePath)
        store.set("cfg", mapOf("id" to "t1", "running" to true, "seq" to 5L))

        val store2 = FileStore(file.absolutePath)
        @Suppress("UNCHECKED_CAST")
        val cfg = store2.get("cfg") as Map<String, Any?>
        assertEquals("t1", cfg["id"])
        assertEquals(true, cfg["running"])
    }
}
