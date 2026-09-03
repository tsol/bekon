package com.wlya.core

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {

    @Test
    fun `crc32 for test string matches TS`() {
        // TypeScript output: d87f7e0c
        assertEquals("d87f7e0c", Crc32.crc32("test"))
    }

    @Test
    fun `crc32 for hello`() {
        // Known CRC-32 for "hello" = 3610a686
        assertEquals("3610a686", Crc32.crc32("hello"))
    }

    @Test
    fun `crc32 for empty string`() {
        assertEquals("00000000", Crc32.crc32(""))
    }

    @Test
    fun `crc32 for unicode`() {
        // Just verify deterministic and 8-char padded
        val r = Crc32.crc32("Привет")
        assertEquals(8, r.length)
        assertEquals(r, Crc32.crc32("Привет"))
    }
}
