package com.wlya.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdapterDutyCoordinatorTest {
    @Test
    fun primaryStaysActiveBackupSleepsUntilForeignInbound() {
        var now = 1_000_000L
        val duty = AdapterDutyCoordinator(nowMs = { now }, random = kotlin.random.Random(0))
        val primary = AdapterInstanceConfig("wlyaserver", "w1", "W", mapOf("role" to "primary"))
        val backup = AdapterInstanceConfig("email", "e1", "E", mapOf("role" to "backup", "idleMs" to "600000"))
        duty.sync(listOf(primary, backup), setOf("w1", "e1"))

        assertTrue(duty.isActive("w1"))
        assertFalse(duty.isActive("e1"))
        assertEquals("w1", duty.effectivePrimaryId())

        val woke = duty.onForeignInbound("e1")
        assertTrue(woke)
        assertTrue(duty.isActive("e1"))

        now += 599_000
        assertTrue(duty.isActive("e1"))
        now += 2_000
        assertFalse(duty.isActive("e1"))
    }

    @Test
    fun primaryPollFailWakesBackupsUntilPrimaryOkAndIdleExpired() {
        var now = 1_000_000L
        val duty = AdapterDutyCoordinator(nowMs = { now }, random = kotlin.random.Random(0))
        val primary = AdapterInstanceConfig("wlyaserver", "w1", "W", emptyMap())
        val backup = AdapterInstanceConfig("email", "e1", "E", mapOf("idleMs" to "600000"))
        duty.sync(listOf(primary, backup), setOf("w1", "e1"))

        val wake = duty.onPollFailed("w1", "timeout")
        assertEquals(listOf("e1"), wake)
        assertTrue(duty.isActive("e1"))

        val afterFail = duty.snapshot("w1", true)
        val wait = (afterFail.nextPollAtMs ?: now) - now
        assertTrue(wait in 250..2_000, "failed primary must retry soon, not sleep-hour: $wait")

        duty.onPollOk("w1")
        assertFalse(duty.isActive("e1"))
    }
}
