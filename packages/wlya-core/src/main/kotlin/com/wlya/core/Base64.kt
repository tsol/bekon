package com.wlya.core

/**
 * Minimal, portable Base64 (RFC 4648) codec.
 *
 * Pure Kotlin so it runs identically on the JVM and on Android API 24+ (java.util.Base64
 * is only available from API 26, and we do not want to depend on android.util.Base64 in
 * the shared core). Output matches standard Base64 with padding and interoperates with
 * both java.util.Base64 and android.util.Base64.
 */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(256) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1

            sb.append(ALPHABET[b0 ushr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) (b1 ushr 4) else 0)])

            if (b1 >= 0) {
                sb.append(ALPHABET[((b1 and 0x0f) shl 2) or (if (b2 >= 0) (b2 ushr 6) else 0)])
            } else {
                sb.append('=')
            }

            if (b2 >= 0) {
                sb.append(ALPHABET[b2 and 0x3f])
            } else {
                sb.append('=')
            }
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.filter { it != '=' }
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = DECODE[c.code]
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}