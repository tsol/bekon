package com.wlya.core

import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secret-based AES-GCM-256 encryption via PBKDF2.
 *
 * Port of TS crypto.ts using `javax.crypto`. Must produce identical cipher/iv for the same
 * secret+plaintext as the TypeScript version, because both use the same PBKDF2 parameters.
 *
 * Note: like TS, each encrypt call generates a fresh random IV. For deterministic testing
 * we rely on the fact that the same secret always derives the same key.
 */
object Crypto {
    private const val SALT = "tunnel-v1"
    private const val ITERATIONS = 100_000
    private const val AES_KEY_SIZE = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val keyCache = ConcurrentHashMap<String, SecretKey>()

    private fun deriveKey(secret: String): SecretKey =
        keyCache.getOrPut(secret) {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(secret.toCharArray(), SALT.toByteArray(Charsets.UTF_8), ITERATIONS, AES_KEY_SIZE)
            val keyBytes = factory.generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        }

    fun encrypt(secret: String, plaintext: String): CryptoResult {
        val key = deriveKey(secret)
        val iv = ByteArray(IV_BYTES).apply { java.security.SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return CryptoResult(
            cipher = bytesToHex(encrypted),
            iv = bytesToHex(iv),
        )
    }

    fun decrypt(secret: String, cipherHex: String, ivHex: String): String {
        val key = deriveKey(secret)
        val encrypted = hexToBytes(cipherHex)
        val iv = hexToBytes(ivHex)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    // ---- helpers ----

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex string" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
}

data class CryptoResult(val cipher: String, val iv: String)
