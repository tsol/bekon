package com.wlya.core

import kotlin.random.Random

/**
 * Primary stays active. Backups sleep (slow poll, no send) unless they recently
 * saw inbound from someone else, or the effective primary's poll is failing.
 */
class AdapterDutyCoordinator(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
) {
    data class Entry(
        var type: String,
        var role: String,
        var pollIntervalMs: Long,
        var sleepPollMs: Long,
        var sleepJitterMs: Long,
        var idleMs: Long,
        var running: Boolean = false,
        var lastForeignInboundAtMs: Long? = null,
        var lastPollAtMs: Long? = null,
        var lastPollError: String? = null,
        var lastPollErrorAtMs: Long? = null,
        var nextPollAtMs: Long? = null,
        var sleepAtMs: Long? = null,
    )

    data class Snapshot(
        val role: String,
        val effectiveRole: String,
        val duty: String,
        val nextPollAtMs: Long?,
        val lastInboundAtMs: Long?,
        val idleUntilMs: Long?,
        val lastPollAtMs: Long?,
        val lastPollError: String?,
        val lastPollErrorAtMs: Long?,
    )

    private val order = mutableListOf<String>()
    private val entries = HashMap<String, Entry>()
    private var primaryFailing = false

    @Synchronized
    fun clear() {
        order.clear()
        entries.clear()
        primaryFailing = false
    }

    @Synchronized
    fun sync(configs: List<AdapterInstanceConfig>, runningIds: Set<String>) {
        val keep = configs.map { it.id }.toSet()
        order.clear()
        order.addAll(configs.map { it.id })
        entries.keys.retainAll(keep)
        for (ac in configs) {
            val parsed = parse(ac)
            val existing = entries[ac.id]
            if (existing == null) {
                entries[ac.id] = parsed.copy(running = ac.id in runningIds)
            } else {
                existing.type = parsed.type
                existing.role = parsed.role
                existing.pollIntervalMs = parsed.pollIntervalMs
                existing.sleepPollMs = parsed.sleepPollMs
                existing.sleepJitterMs = parsed.sleepJitterMs
                existing.idleMs = parsed.idleMs
                existing.running = ac.id in runningIds
            }
        }
        val primary = effectivePrimaryIdLocked()
        if (primary == null || entries[primary]?.running != true) {
            primaryFailing = false
        }
    }

    @Synchronized
    fun drop(adapterId: String) {
        order.remove(adapterId)
        entries.remove(adapterId)
        if (effectivePrimaryIdLocked() == null) primaryFailing = false
    }

    @Synchronized
    fun isSendActive(adapterId: String): Boolean = isActiveLocked(adapterId)

    @Synchronized
    fun isActive(adapterId: String): Boolean = isActiveLocked(adapterId)

    @Synchronized
    fun effectivePrimaryId(): String? = effectivePrimaryIdLocked()

    /** @return true if this adapter just became active (recv delay should be cancelled). */
    @Synchronized
    fun onForeignInbound(adapterId: String): Boolean {
        val e = entries[adapterId] ?: return false
        val was = isActiveLocked(adapterId)
        e.lastForeignInboundAtMs = nowMs()
        val now = isActiveLocked(adapterId)
        if (now && !was) e.sleepAtMs = null
        return now && !was
    }

    @Synchronized
    fun onPollOk(adapterId: String) {
        val e = entries[adapterId] ?: return
        e.lastPollAtMs = nowMs()
        e.lastPollError = null
        if (adapterId == effectivePrimaryIdLocked()) primaryFailing = false
        scheduleNextLocked(e, adapterId)
    }

    /**
     * @return adapter ids (other than [adapterId]) that should poll immediately.
     */
    @Synchronized
    fun onPollFailed(adapterId: String, message: String): List<String> {
        val e = entries[adapterId] ?: return emptyList()
        e.lastPollAtMs = nowMs()
        e.lastPollError = message
        e.lastPollErrorAtMs = nowMs()
        val wake = mutableListOf<String>()
        if (adapterId == effectivePrimaryIdLocked()) {
            primaryFailing = true
            for (id in order) {
                if (id == adapterId) continue
                val other = entries[id] ?: continue
                if (!other.running) continue
                other.sleepAtMs = null
                other.nextPollAtMs = nowMs()
                wake.add(id)
            }
        }
        // Failed poll retries on the normal interval, never a sleep-hour.
        e.sleepAtMs = null
        e.nextPollAtMs = nowMs() + e.pollIntervalMs
        return wake
    }

    @Synchronized
    fun pollDelayMs(adapterId: String): Long {
        val e = entries[adapterId] ?: return DEFAULT_POLL_MS
        return scheduleNextLocked(e, adapterId)
    }

    @Synchronized
    fun snapshot(adapterId: String, running: Boolean): Snapshot {
        val e = entries[adapterId]
        val role = e?.role ?: ROLE_BACKUP
        if (e == null || !running) {
            return Snapshot(
                role = role,
                effectiveRole = role,
                duty = DUTY_STOPPED,
                nextPollAtMs = null,
                lastInboundAtMs = e?.lastForeignInboundAtMs,
                idleUntilMs = null,
                lastPollAtMs = e?.lastPollAtMs,
                lastPollError = e?.lastPollError,
                lastPollErrorAtMs = e?.lastPollErrorAtMs,
            )
        }
        val primaryId = effectivePrimaryIdLocked()
        val effectiveRole = if (adapterId == primaryId) ROLE_PRIMARY else ROLE_BACKUP
        val active = isActiveLocked(adapterId)
        val idleUntil = if (active && adapterId != primaryId && e.lastForeignInboundAtMs != null && !primaryFailing) {
            e.lastForeignInboundAtMs!! + e.idleMs
        } else {
            null
        }
        return Snapshot(
            role = e.role,
            effectiveRole = effectiveRole,
            duty = if (active) DUTY_ACTIVE else DUTY_SLEEPING,
            nextPollAtMs = e.nextPollAtMs,
            lastInboundAtMs = e.lastForeignInboundAtMs,
            idleUntilMs = idleUntil,
            lastPollAtMs = e.lastPollAtMs,
            lastPollError = e.lastPollError,
            lastPollErrorAtMs = e.lastPollErrorAtMs,
        )
    }

    private fun isActiveLocked(adapterId: String): Boolean {
        val e = entries[adapterId] ?: return false
        if (!e.running) return false
        if (adapterId == effectivePrimaryIdLocked()) return true
        if (primaryFailing) return true
        val last = e.lastForeignInboundAtMs ?: return false
        return nowMs() - last < e.idleMs
    }

    private fun effectivePrimaryIdLocked(): String? {
        val running = order.mapNotNull { id -> entries[id]?.takeIf { it.running }?.let { id } }
        return running.firstOrNull { entries[it]?.role == ROLE_PRIMARY }
            ?: running.firstOrNull()
    }

    private fun scheduleNextLocked(e: Entry, adapterId: String): Long {
        val now = nowMs()
        val delay = if (isActiveLocked(adapterId)) {
            e.sleepAtMs = null
            e.pollIntervalMs
        } else {
            val existing = e.sleepAtMs
            if (existing == null || existing <= now) {
                e.sleepAtMs = now + jitteredSleep(e)
            }
            (e.sleepAtMs!! - now).coerceAtLeast(0)
        }
        e.nextPollAtMs = now + delay
        return delay
    }

    private fun jitteredSleep(e: Entry): Long {
        val j = e.sleepJitterMs.coerceAtLeast(0)
        val base = e.sleepPollMs.coerceAtLeast(1)
        val lo = (base - j).coerceAtLeast(1)
        val hi = (base + j).coerceAtLeast(lo)
        return if (hi == lo) lo else lo + random.nextLong(hi - lo + 1)
    }

    companion object {
        const val ROLE_PRIMARY = "primary"
        const val ROLE_BACKUP = "backup"
        const val DUTY_ACTIVE = "active"
        const val DUTY_SLEEPING = "sleeping"
        const val DUTY_STOPPED = "stopped"

        const val DEFAULT_POLL_MS = 2_000L
        const val DEFAULT_SLEEP_POLL_MS = 3_600_000L
        const val DEFAULT_SLEEP_JITTER_MS = 900_000L
        const val DEFAULT_IDLE_MS = 600_000L

        fun defaultRole(type: String): String =
            if (type == "wlyaserver") ROLE_PRIMARY else ROLE_BACKUP

        fun parse(ac: AdapterInstanceConfig): Entry {
            val roleRaw = ac.config["role"]?.lowercase()?.trim()
            val role = if (roleRaw == ROLE_PRIMARY || roleRaw == ROLE_BACKUP) roleRaw else defaultRole(ac.type)
            return Entry(
                type = ac.type,
                role = role,
                pollIntervalMs = parseLong(ac.config["pollIntervalMs"], DEFAULT_POLL_MS).coerceAtLeast(250),
                sleepPollMs = parseLong(ac.config["sleepPollMs"], DEFAULT_SLEEP_POLL_MS).coerceAtLeast(1_000),
                sleepJitterMs = parseLong(ac.config["sleepJitterMs"], DEFAULT_SLEEP_JITTER_MS).coerceAtLeast(0),
                idleMs = parseLong(ac.config["idleMs"], DEFAULT_IDLE_MS).coerceAtLeast(1_000),
            )
        }

        private fun parseLong(raw: String?, default: Long): Long {
            if (raw.isNullOrBlank()) return default
            return raw.toLongOrNull() ?: default
        }
    }
}
