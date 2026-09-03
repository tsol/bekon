package com.wlya.core.adapters

import com.wlya.core.BaseAdapter
import com.wlya.core.TransportMessage

/**
 * In-memory (but file-backed) mock adapter for testing.
 *
 * All instances sharing the same [LocalStore] see each other's messages,
 * allowing multi-peer testing on a single page/machine.
 */
class MockAdapter(
    instanceId: String,
    private val localStore: LocalStore = LocalStore(),
) : BaseAdapter() {
    override val name: String = "mock:$instanceId"
    override val windowSize: Int = 65536

    override suspend fun init(channel: String) {
        logEvent("MockAdapter ready")
    }

    override suspend fun poll(lastTransportSeq: Int): List<TransportMessage> {
        return localStore.poll(lastTransportSeq)
    }

    override suspend fun send(msg: TransportMessage) {
        localStore.push(msg)
    }

    override suspend fun clearHistory() {
        localStore.clear()
        log.clear()
        logEvent("history cleared")
    }
}
