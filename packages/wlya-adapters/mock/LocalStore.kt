package com.wlya.core.adapters

import com.wlya.core.TransportMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Shared persistent store for MockAdapter testing.
 *
 * All MockAdapter instances using the same [storePath] share message history,
 * allowing multi-tunnel testing on a single machine.
 */
class LocalStore(private val storePath: String = ".wlya/local-store.json") {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(storePath)

    @Serializable
    private data class StoredMessage(
        val id: String,
        val from: String,
        val content: String,
        val iv: String,
        val crc: String,
        val timestamp: Long,
        val transportSeq: Int,
        val partOf: String? = null,
        val partIndex: Int? = null,
        val totalParts: Int? = null,
    )

    @Serializable
    private data class StoreData(
        val messages: List<StoredMessage> = emptyList(),
        val transportSeq: Int = 0,
    )

    private fun load(): StoreData {
        return try {
            file.parentFile?.mkdirs()
            if (!file.exists()) StoreData()
            else json.decodeFromString(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            StoreData()
        }
    }

    private fun save(data: StoreData) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(data), Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[LocalStore] save error: ${e.message}")
        }
    }

    private fun toStored(msg: TransportMessage) = StoredMessage(
        id = msg.id, from = msg.from, content = msg.content, iv = msg.iv, crc = msg.crc,
        timestamp = msg.timestamp, transportSeq = msg.transportSeq,
        partOf = msg.partOf, partIndex = msg.partIndex, totalParts = msg.totalParts,
    )

    private fun fromStored(s: StoredMessage) = TransportMessage(
        id = s.id, from = s.from, content = s.content, iv = s.iv, crc = s.crc,
        timestamp = s.timestamp, transportSeq = s.transportSeq,
        partOf = s.partOf, partIndex = s.partIndex, totalParts = s.totalParts,
    )

    /** Add a message to the shared store. */
    fun push(msg: TransportMessage) {
        val data = load()
        val trimmed = if (data.messages.size > 200) data.messages.takeLast(100) else data.messages
        save(data.copy(messages = trimmed + toStored(msg), transportSeq = msg.transportSeq))
    }

    /** Get all messages with transportSeq > [lastTransportSeq]. */
    fun poll(lastTransportSeq: Int): List<TransportMessage> {
        return load().messages.map { fromStored(it) }.filter { it.transportSeq > lastTransportSeq }
    }

    /** Clear the store. */
    fun clear() {
        save(StoreData())
    }

    /** Current transportSeq. */
    fun transportSeq(): Int = load().transportSeq
}
