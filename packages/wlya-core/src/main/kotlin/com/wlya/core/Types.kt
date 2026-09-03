package com.wlya.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Raw adapter-level message. Encrypted content of a TunnelMessage (or a part of it).
 */
@Serializable
data class TransportMessage(
    val id: String,
    val from: String,
    val content: String,
    val iv: String,
    val crc: String,
    val timestamp: Long,
    val transportSeq: Int,
    val partOf: String? = null,
    val partIndex: Int? = null,
    val totalParts: Int? = null,
)

/**
 * Attachment carried inside a tunnel message.
 */
@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Int,
    val data: String, // base64
)

/**
 * Logical tunnel-level message (plaintext, after decrypt).
 */
@Serializable
data class TunnelMessage(
    val id: String,
    val seq: Int,
    val from: String,
    val text: String,
    val timestamp: Long,
    val attachments: List<Attachment>? = null,
)

/**
 * Persisted configuration for a single adapter instance.
 * All config values are stored as strings for simple serialization.
 */
@Serializable
data class AdapterInstanceConfig(
    val type: String,
    val id: String,
    val label: String,
    val config: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

/**
 * Adapter row for UI/API: persisted config plus live runtime flag.
 */
@Serializable
data class AdapterListItem(
    val type: String,
    val id: String,
    val label: String,
    val config: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val running: Boolean = false,
    val role: String = AdapterDutyCoordinator.ROLE_BACKUP,
    val effectiveRole: String = AdapterDutyCoordinator.ROLE_BACKUP,
    val duty: String = AdapterDutyCoordinator.DUTY_STOPPED,
    val nextPollAtMs: Long? = null,
    val lastInboundAtMs: Long? = null,
    val idleUntilMs: Long? = null,
    val lastPollAtMs: Long? = null,
    val lastPollError: String? = null,
    val lastPollErrorAtMs: Long? = null,
)

/**
 * Application payload sent as [TunnelMessage.text] to share adapter configs with peers.
 */
@Serializable
data class AdvertiseAdaptersPayload(
    val cmd: String = "advertise-adapters",
    val adapters: List<AdapterInstanceConfig> = emptyList(),
)

/**
 * Persisted tunnel configuration.
 *
 * [channel] is the relay/queue id (HMAC / X-WLYA-Seed). Old JSON used `"seed"`.
 * [secret] is AES-GCM key material and never leaves the client. Blank → use [channel].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TunnelConfig(
    val id: String,
    val label: String,
    @JsonNames("seed")
    val channel: String,
    val secret: String = "",
    val clientId: String,
    val running: Boolean = false,
    val autostart: Boolean = false,
    val tunnelSeq: Int = 0,
    val lastTransportSeqs: Map<String, Int> = emptyMap(),
    val writeSeqs: Map<String, Int> = emptyMap(),
    /** Recently ingested tunnel-message ids; survives stop/start so inbound is not replayed. */
    val seenIds: List<String> = emptyList(),
    val adapters: List<AdapterInstanceConfig> = emptyList(),
) {
    fun cryptoSecret(): String = secret.ifBlank { channel }
}

/**
 * Information about an attachment exposed in the UI/API.
 */
@Serializable
data class AttachmentInfo(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Int,
    val data: String,
)

/**
 * Message as shown in the UI.
 */
@Serializable
data class UIMessage(
    val seq: Int,
    val from: String,
    val plaintext: String,
    val direction: String, // "in" or "out"
    val timestamp: Long,
    val attachments: List<AttachmentInfo>? = null,
)

/**
 * Debug-level view of a transport message.
 */
@Serializable
data class DebugMessage(
    val transportSeq: Int,
    val from: String,
    val raw: String? = null,
    val iv: String? = null,
    val crc: String? = null,
    val tunnelId: String? = null,
    val tunnelSeq: Int? = null,
    val plaintext: String,
    val timestamp: Long,
    val partOf: String? = null,
    val partIndex: Int? = null,
    val totalParts: Int? = null,
)

/**
 * Lightweight summary for listing tunnels in UI/API.
 */
@Serializable
data class TunnelListItem(
    val id: String,
    val label: String,
    val channel: String,
    val running: Boolean,
    val autostart: Boolean = false,
    val adapters: List<AdapterListItem> = emptyList(),
)
