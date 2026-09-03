package com.wlya.core

import com.wlya.core.adapters.parseTransportTimestamp
import com.wlya.core.adapters.tunnelMessageAgeMs
import com.wlya.core.adapters.unwrapTransportJson
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EmailAdapterHelpersTest {

    @Test
    fun `unwrapTransportJson decodes WLYA wrapper`() {
        val json = """{"timestamp":1700000000000,"transportSeq":1}"""
        val wrapped = "-----BEGIN WLYA-----\n${java.util.Base64.getEncoder().encodeToString(json.toByteArray())}\n-----END WLYA-----"
        assertEquals(json, unwrapTransportJson(wrapped))
    }

    @Test
    fun `tunnelMessageAgeMs prefers transport timestamp`() {
        val ts = 1_700_000_000_000L
        val json = """{"timestamp":$ts,"transportSeq":1}"""
        val age = tunnelMessageAgeMs(json, Date(1_000L), Date(2_000L))
        assertEquals(ts, age)
    }

    @Test
    fun `tunnelMessageAgeMs falls back to received date`() {
        val received = Date(1_700_000_000_000L)
        val age = tunnelMessageAgeMs("not-json", received, null)
        assertEquals(received.time, age)
    }

    @Test
    fun `parseTransportTimestamp extracts timestamp field`() {
        assertEquals(42L, parseTransportTimestamp("""{"timestamp":42}"""))
        assertNull(parseTransportTimestamp("""{"transportSeq":1}"""))
    }

    @Test
    fun `tunnelMessageAgeMs from wrapped body`() {
        val ts = 1_800_000_000_000L
        val json = """{"timestamp":$ts,"transportSeq":5}"""
        val wrapped = "-----BEGIN WLYA-----\n${java.util.Base64.getEncoder().encodeToString(json.toByteArray())}\n-----END WLYA-----"
        assertNotNull(tunnelMessageAgeMs(wrapped, null, null))
        assertEquals(ts, tunnelMessageAgeMs(wrapped, null, null))
    }
}
