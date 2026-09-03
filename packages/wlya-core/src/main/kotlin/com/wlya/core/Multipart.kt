package com.wlya.core

import java.util.Timer
import java.util.concurrent.ConcurrentHashMap

/**
 * Part split info returned by [Multipart.splitParts].
 */
data class PartInfo(
    val partIndex: Int,
    val totalParts: Int,
    val partData: String,
)

/**
 * Multipart sequencer: splits encrypted content into chunks and reassembles them.
 *
 * Port of TS multipart.ts. Cleanup timer removes stale assemblies after [STALE_TIMEOUT_MS].
 */
class MultipartSequencer {
    companion object {
        const val STALE_TIMEOUT_MS = 300_000L
        const val CLEANUP_INTERVAL_MS = 15_000L
    }

    private data class PendingAssembly(
        val totalParts: Int,
        val parts: MutableMap<Int, String> = ConcurrentHashMap(),
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val pending = ConcurrentHashMap<String, PendingAssembly>()
    private val timer: Timer?

    init {
        timer = Timer("MultipartCleanup", true).apply {
            scheduleAtFixedRate(
                object : java.util.TimerTask() {
                    override fun run() = cleanup()
                },
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
            )
        }
    }

    /**
     * Split [data] into chunks of at most [windowSize] bytes.
     * Returns an empty list when data fits in one chunk.
     */
    fun splitParts(data: String, windowSize: Int): List<PartInfo> {
        val byteLen = data.toByteArray(Charsets.UTF_8).size
        if (byteLen <= windowSize) return emptyList()

        val parts = mutableListOf<PartInfo>()
        var offset = 0
        var partIndex = 0
        val chars = data.length

        while (offset < chars) {
            // Approximate: windowSize UTF-8 bytes ~= windowSize chars for ASCII/hex
            var end = kotlin.math.min(offset + windowSize, chars)
            val chunk = data.substring(offset, end)
            parts.add(PartInfo(partIndex = partIndex, totalParts = 0, partData = chunk))
            offset = end
            partIndex++
        }

        val total = parts.size
        return parts.map { it.copy(totalParts = total) }
    }

    /**
     * Feed a received part. Returns the assembled data when all parts are present,
     * or `null` if still waiting.
     */
    fun addPart(partOf: String, partIndex: Int, totalParts: Int, partData: String): String? {
        if (totalParts <= 1) return partData

        var assembly = pending[partOf]
        if (assembly == null) {
            assembly = PendingAssembly(totalParts)
            pending[partOf] = assembly
        }

        // Sanity: if totalParts changed, reset
        if (assembly.totalParts != totalParts) {
            assembly = PendingAssembly(totalParts)
            pending[partOf] = assembly
        }

        assembly.parts[partIndex] = partData

        if (assembly.parts.size == totalParts) {
            pending.remove(partOf)
            val chunks = (0 until totalParts).mapNotNull { assembly.parts[it] }
            return if (chunks.size == totalParts) chunks.joinToString("") else null
        }
        return null
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { (_, v) ->
            now - v.createdAt > STALE_TIMEOUT_MS
        }
    }

    fun stop() {
        timer?.cancel()
        pending.clear()
    }
}
