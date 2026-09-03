package com.wlya.core

import com.wlya.core.adapters.EmailAdapter
import com.wlya.core.adapters.LocalStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

/**
 * Live email roundtrip test.
 *
 * Requires env vars:
 *   WLYA_TEST_EMAIL_LOGIN   (e.g. megaboots@mail.ru)
 *   WLYA_TEST_EMAIL_PASSWORD
 *
 * Can be disabled by omitting these variables.
 */
class EmailAdapterTest {

    private val login = System.getenv("WLYA_TEST_EMAIL_LOGIN")
    private val password = System.getenv("WLYA_TEST_EMAIL_PASSWORD")

    @Test
    fun `EmailAdapter IMAP connection`() = runTest {
        if (login.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("SKIP: no email credentials")
            return@runTest
        }
        val adapter = EmailAdapter("test", mapOf(
            "host" to "imap.mail.ru",
            "port" to 993,
            "useSSL" to true,
            "smtpPort" to 465,
            "smtpUseSSL" to true,
            "login" to login,
            "password" to password,
        ))
        adapter.init("seed")
    }

    @Test
    fun `EmailAdapter send via SMTP poll via IMAP roundtrip`() = runTest {
        if (login.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("SKIP: no email credentials")
            return@runTest
        }

        val seed = "email-test-${System.currentTimeMillis()}"
        val adapter = EmailAdapter("test", mapOf(
            "host" to "imap.mail.ru",
            "port" to 993,
            "useSSL" to true,
            "smtpPort" to 465,
            "smtpUseSSL" to true,
            "login" to login,
            "password" to password,
        ))
        adapter.init(seed)

        val msg = TransportMessage(
            id = "test-msg-1",
            from = "client-test",
            content = "encrypted-content-placeholder",
            iv = "deadbeef",
            crc = "12345678",
            timestamp = System.currentTimeMillis(),
            transportSeq = 1,
        )

        adapter.send(msg)
        println("Email sent, transportSeq=1")

        // Poll up to 60s for the message to appear
        var found: List<TransportMessage> = emptyList()
        var attempts = 0
        while (found.isEmpty() && attempts < 30) {
            delay(2000)
            found = adapter.poll(0)
            attempts++
        }

        println("Poll attempts=$attempts, found=${found.size} messages")
        assertTrue(found.isNotEmpty(), "Should receive at least one message via IMAP within 60s")
        assertTrue(found.any { it.transportSeq == 1 }, "Should find our message with transportSeq=1")
    }

    @Test
    fun `EmailAdapter send via IMAP APPEND poll via IMAP roundtrip`() = runTest {
        if (login.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("SKIP: no email credentials")
            return@runTest
        }

        val adapter = EmailAdapter("test-imap", mapOf(
            "host" to "imap.mail.ru",
            "port" to 993,
            "useSSL" to true,
            "login" to login,
            "password" to password,
            "sendMode" to "imap",
            "imapFolder" to "INBOX",
        ))
        adapter.init("seed-imap")

        val msg = TransportMessage(
            id = "test-imap-msg-1",
            from = "client-imap",
            content = "encrypted-content-placeholder",
            iv = "deadbeef",
            crc = "12345678",
            timestamp = System.currentTimeMillis(),
            transportSeq = 99,
        )

        adapter.send(msg)
        println("IMAP append sent, transportSeq=99")

        var found: List<TransportMessage> = emptyList()
        var attempts = 0
        while (found.isEmpty() && attempts < 15) {
            delay(2000)
            found = adapter.poll(0)
            attempts++
        }

        assertTrue(found.isNotEmpty(), "Should receive message via IMAP APPEND within 30s")
        assertTrue(found.any { it.transportSeq == 99 }, "Should find message with transportSeq=99")
    }
}
