package com.wlya.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CryptoTest {

    @Test
    fun `encrypt-decrypt roundtrip`() {
        val secret = "test-seed"
        val plaintext = "hello wlya"
        val result = Crypto.encrypt(secret, plaintext)
        val decrypted = Crypto.decrypt(secret, result.cipher, result.iv)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `deterministic key derivation - same secret decrypts`() {
        val secret = "shared-seed-123"
        val plaintext = "secret message"
        val encrypted = Crypto.encrypt(secret, plaintext)
        val decrypted = Crypto.decrypt(secret, encrypted.cipher, encrypted.iv)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `different secrets do not decrypt same ciphertext`() {
        val encrypted = Crypto.encrypt("seed-a", "data")
        var failed = false
        try {
            Crypto.decrypt("seed-b", encrypted.cipher, encrypted.iv)
        } catch (_: Exception) {
            failed = true
        }
        assertEquals(true, failed, "Decrypting with wrong secret should throw")
    }

    @Test
    fun `encrypt with same secret produces different iv each time`() {
        val secret = "seed"
        val r1 = Crypto.encrypt(secret, "hello")
        val r2 = Crypto.encrypt(secret, "hello")
        assert(r1.iv != r2.iv) { "IV should be random each time" }
        assert(r1.cipher != r2.cipher) { "Cipher should differ because IV differs" }
        // But both should decrypt back
        assertEquals("hello", Crypto.decrypt(secret, r1.cipher, r1.iv))
        assertEquals("hello", Crypto.decrypt(secret, r2.cipher, r2.iv))
    }

    @Test
    fun `matches TS output for known test vector`() = runTest {
        // From TypeScript crypto.ts with seed="seed" plaintext="hello":
        // cipher: f7faf7f3bb55aea2e9a212edb9fbdc2c53059e293b
        // iv: ce021d1cc68a876faa13b5fd
        val tsCipher = "f7faf7f3bb55aea2e9a212edb9fbdc2c53059e293b"
        val tsIv = "ce021d1cc68a876faa13b5fd"
        val decrypted = Crypto.decrypt("seed", tsCipher, tsIv)
        assertEquals("hello", decrypted)
    }

    @Test
    fun `empty plaintext roundtrip`() {
        val r = Crypto.encrypt("seed", "")
        assertEquals("", Crypto.decrypt("seed", r.cipher, r.iv))
    }

    @Test
    fun `unicode plaintext roundtrip`() {
        val text = "Привет мир 🌍 你好世界"
        val r = Crypto.encrypt("unicode-seed", text)
        assertEquals(text, Crypto.decrypt("unicode-seed", r.cipher, r.iv))
    }
}
