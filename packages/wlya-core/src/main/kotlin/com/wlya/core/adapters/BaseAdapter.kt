package com.wlya.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Base class for all transport adapters.
 *
 * Port of TS `BaseAdapter`. Uses java.text.SimpleDateFormat (rather than java.time)
 * so it runs on the JVM and on Android API 24+ without desugaring.
 */
abstract class BaseAdapter {
    abstract val name: String

    /** Maximum content bytes per [TransportMessage]. Larger content is auto-split into multipart. */
    open val windowSize: Int = 4096

    /** Recv-loop interval. Core reads this; adapters override from config. */
    open val pollIntervalMs: Int = DEFAULT_POLL_INTERVAL_MS

    /**
     * When true, poll and send never overlap (needed for IMAP). HTTP adapters
     * override this so a hung GET cannot stall POST.
     */
    open val serialIo: Boolean = true

    val log = mutableListOf<String>()

    private val ioMutex = Mutex()

    fun logEvent(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] [$name] $msg"
        log.add(line)
        log.keepLast()
        println(line)
    }

    /** [channel] is the relay/queue id, not the AES secret. */
    abstract suspend fun init(channel: String)
    abstract suspend fun poll(lastTransportSeq: Int): List<TransportMessage>
    abstract suspend fun send(msg: TransportMessage)
    abstract suspend fun clearHistory()

    suspend fun lockedPoll(lastTransportSeq: Int): List<TransportMessage> {
        if (!serialIo) return poll(lastTransportSeq)
        val t0 = System.currentTimeMillis()
        return ioMutex.withLock {
            val waited = System.currentTimeMillis() - t0
            if (waited >= 50) logEvent("poll waited ${waited}ms for ioMutex")
            poll(lastTransportSeq)
        }
    }

    suspend fun lockedSend(msg: TransportMessage) {
        if (!serialIo) {
            send(msg)
            return
        }
        val t0 = System.currentTimeMillis()
        ioMutex.withLock {
            val waited = System.currentTimeMillis() - t0
            if (waited >= 50) logEvent("send waited ${waited}ms for ioMutex")
            send(msg)
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 2000
        const val MIN_POLL_INTERVAL_MS = 250
        const val DEFAULT_WINDOW_SIZE = 262144
        const val MIN_WINDOW_SIZE = 256
        const val MAX_WINDOW_SIZE = 1_048_576

        fun parsePollIntervalMs(config: Map<String, Any>, default: Int): Int {
            val v = config["pollIntervalMs"] ?: return default.coerceAtLeast(MIN_POLL_INTERVAL_MS)
            val n = when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: default
                else -> default
            }
            return n.coerceAtLeast(MIN_POLL_INTERVAL_MS)
        }

        /** Cipher chunk size; larger payloads are split into multipart. */
        fun parseWindowSize(config: Map<String, Any>, default: Int = DEFAULT_WINDOW_SIZE): Int {
            val v = config["windowSize"] ?: return default.coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)
            val n = when (v) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: default
                else -> default
            }
            return n.coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)
        }
    }
}
