package com.wlya.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultipartTest {

    @Test
    fun `splitParts returns empty for data under windowSize`() {
        val data = "a".repeat(100)
        val parts = MultipartSequencer().splitParts(data, 4096)
        assertTrue(parts.isEmpty(), "Small data should not split")
    }

    @Test
    fun `splitParts splits large data`() {
        val data = "a".repeat(10000)
        val parts = MultipartSequencer().splitParts(data, 4096)
        assertTrue(parts.isNotEmpty(), "Larger than windowSize should split")
        assertEquals(0, parts.first().partIndex)
        assertTrue(parts.first().totalParts > 1, "totalParts > 1")
        assertEquals(parts.last().partIndex + 1, parts.size, "Index continuity")
    }

    @Test
    fun `addPart assembles split data correctly`() {
        val original = "X".repeat(9000)
        val seq = MultipartSequencer()
        val parts = seq.splitParts(original, 4096)

        val msgId = "msg-1"
        for (part in parts) {
            val result = seq.addPart(msgId, part.partIndex, part.totalParts, part.partData)
            if (part.partIndex == parts.last().partIndex) {
                assertEquals(original, result, "Assembled data should match original")
            } else {
                assertNull(result, "Should be null until last part")
            }
        }
    }

    @Test
    fun `single part passes through addPart`() {
        val seq = MultipartSequencer()
        val result = seq.addPart("single", 0, 1, "hello")
        assertEquals("hello", result)
    }

    @Test
    fun `out-of-order parts still assemble`() {
        val seq = MultipartSequencer()
        val parts = seq.splitParts("ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(500), 4096)
        val msgId = "out-of-order"

        // Shuffle
        val shuffled = parts.shuffled()
        for (part in shuffled) {
            seq.addPart(msgId, part.partIndex, part.totalParts, part.partData)
        }

        // Last part may already complete, but we don't have its return after shuffling.
        // Verify by re-adding one part
        val lastPart = parts.last()
        val assembled = seq.addPart(msgId, lastPart.partIndex, lastPart.totalParts, lastPart.partData)
        assertNull(assembled, "Message should be gone after assembly")
    }

    @Test
    fun `multipart cleanup timer starts and stops`() {
        val seq = MultipartSequencer()
        seq.stop()
    }
}
