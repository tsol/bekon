package com.wlya.core

import kotlinx.serialization.Serializable

/**
 * View layer: per-tunnel UI state (messages, debug).
 *
 * Port of TS TunnelView.
 */
class TunnelView(
    val tunnel: Tunnel,
) {
    private val messages = mutableListOf<UIMessage>()
    private val fullInbound = ArrayDeque<UIMessage>()
    private val debug = mutableMapOf<String, MutableList<DebugMessage>>()
    private var deliveredSeq = 0

    @Serializable
    private data class ViewState(val deliveredSeq: Int)

    suspend fun init(store: Store) {
        val saved = store.getObject<ViewState>("view:${tunnel.config.id}")
        saved?.let { deliveredSeq = it.deliveredSeq }
    }

    val handlers: TunnelHandlers
        get() = object : TunnelHandlers {
            override fun onMessage(msg: TunnelMessage, direction: String) {
                val preview = UIMessage(
                    seq = msg.seq,
                    from = msg.from,
                    plaintext = logPreview(msg.text),
                    direction = direction,
                    timestamp = msg.timestamp,
                    attachments = msg.attachments?.map { a ->
                        AttachmentInfo(
                            id = a.id,
                            name = a.name,
                            mimeType = a.mimeType,
                            size = a.size,
                            data = "",
                        )
                    },
                )
                messages.add(preview)
                messages.keepLast()
                if (direction == "in") {
                    val stored = if (msg.text.length <= LOG_FULL_INBOUND_CHARS) msg.text
                    else logPreview(msg.text, LOG_PREVIEW_CHARS)
                    fullInbound.addLast(preview.copy(plaintext = stored))
                    while (fullInbound.size > LOG_FULL_INBOUND) fullInbound.removeFirst()
                }
            }

            override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {
                val list = debug.getOrPut(adapterName) { mutableListOf() }
                val (tunnelId, tunnelSeq) = peekTunnelMeta(decryptedJson)
                list.add(
                    DebugMessage(
                        transportSeq = tMsg.transportSeq,
                        from = tMsg.from,
                        raw = tMsg.content.take(200),
                        iv = tMsg.iv,
                        crc = tMsg.crc,
                        tunnelId = tunnelId,
                        tunnelSeq = tunnelSeq,
                        plaintext = logPreview(decryptedJson),
                        timestamp = System.currentTimeMillis(),
                        partOf = tMsg.partOf,
                        partIndex = tMsg.partIndex,
                        totalParts = tMsg.totalParts,
                    )
                )
                list.keepLast()
            }
        }

    fun getMessages(fullInboundOnly: Boolean = false): List<UIMessage> =
        if (fullInboundOnly) fullInbound.toList() else messages.toList()

    fun clearMessages() {
        messages.clear()
        fullInbound.clear()
    }

    fun getAllDebug(): Map<String, List<DebugMessage>> = debug.mapValues { it.value.toList() }

    fun getDebug(adapterName: String, lastN: Int = 10): List<DebugMessage> =
        (debug[adapterName] ?: emptyList()).takeLast(lastN)

    fun clearAdapterDebug(adapterName: String) {
        debug.remove(adapterName)
    }

    companion object {
        private val ID_FIELD = Regex("\"id\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
        private val SEQ_FIELD = Regex("\"seq\"\\s*:\\s*(-?\\d+)")

        /** Avoid kotlinx parse of APK-sized JSON just to read id/seq. */
        internal fun peekTunnelMeta(json: String): Pair<String?, Int?> {
            val head = json.take(800)
            val id = ID_FIELD.find(head)?.groupValues?.get(1)
            val seq = SEQ_FIELD.find(head)?.groupValues?.get(1)?.toIntOrNull()
            return id to seq
        }
    }
}
