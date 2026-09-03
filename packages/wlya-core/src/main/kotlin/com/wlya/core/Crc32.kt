package com.wlya.core

import java.util.zip.CRC32

/**
 * CRC-32 checksum matching the TypeScript implementation.
 * Returns 8-char lowercase hex string.
 */
object Crc32 {
    fun crc32(data: String): String {
        val c = CRC32()
        c.update(data.toByteArray(Charsets.UTF_8))
        return c.value.toInt().toUInt().toString(16).padStart(8, '0')
    }
}
