package com.wlya.core.adapters

import com.wlya.core.Base64
import com.wlya.core.BaseAdapter
import com.wlya.core.TransportMessage
import com.sun.mail.imap.IMAPFolder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.Date
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.SubjectTerm

/**
 * Email adapter: sends encrypted tunnel messages via SMTP or IMAP APPEND,
 * polls for them via IMAP. Shared mailbox clients cooperatively delete stale
 * [TUNNEL] messages older than [tunnelMessageTtlSeconds].
 */
class EmailAdapter(
    instanceId: String,
    config: Map<String, Any>,
) : BaseAdapter() {
    override val name: String = "email:$instanceId"
    override val windowSize: Int = BaseAdapter.parseWindowSize(config, 262144)
    override val pollIntervalMs: Int = BaseAdapter.parsePollIntervalMs(config, 10_000)

    private val host: String
    private val port: Int
    private val useSSL: Boolean
    private val smtpHost: String
    private val smtpPort: Int
    private val smtpUseSSL: Boolean
    private val login: String
    private val password: String
    private val imapFolderName: String
    private val sendMode: String
    private val smtpTo: String
    private val tunnelMessageTtlSeconds: Int

    /** Message-IDs already processed (dedup for shared mailbox). */
    private val seenMessageIds = mutableSetOf<String>()

    init {
        host = cfgString(config, "host", "imap.mail.ru")
        port = cfgInt(config, "port", 993)
        useSSL = cfgBool(config, "useSSL", true)
        smtpPort = cfgInt(config, "smtpPort", 465)
        smtpUseSSL = cfgBool(config, "smtpUseSSL", true)
        login = cfgString(config, "login", "")
        password = cfgString(config, "password", "")
        imapFolderName = cfgString(config, "imapFolder", "INBOX")
        sendMode = cfgString(config, "sendMode", "smtp").lowercase()
        smtpTo = cfgString(config, "smtpTo", "").ifBlank { login }
        tunnelMessageTtlSeconds = cfgInt(config, "tunnelMessageTtlSeconds", 900)
        val rawSmtpHost = cfgString(config, "smtpHost", "")
        smtpHost = rawSmtpHost.ifBlank {
            if (host.startsWith("imap.")) host.replace(Regex("^imap\\."), "smtp.") else host
        }
    }

    private fun cfgString(config: Map<String, Any>, key: String, default: String): String {
        val v = config[key] ?: return default
        return v.toString()
    }

    private fun cfgInt(config: Map<String, Any>, key: String, default: Int): Int {
        val v = config[key] ?: return default
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun cfgBool(config: Map<String, Any>, key: String, default: Boolean): Boolean {
        val v = config[key] ?: return default
        return when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> default
        }
    }

    private fun imapProperties(timeoutMs: Int = 15000): Properties =
        Properties().apply {
            this["mail.imap.ssl.enable"] = useSSL.toString()
            this["mail.imap.connectiontimeout"] = timeoutMs.toString()
            this["mail.imap.timeout"] = timeoutMs.toString()
        }

    private fun connectStore(session: Session): Store {
        val store = session.getStore("imap")
        store.connect(host, port, login, password)
        return store
    }

    private fun openImapFolder(store: Store, mode: Int = Folder.READ_WRITE): Folder {
        val folder = store.getFolder(imapFolderName)
        if (!folder.exists()) {
            try {
                folder.create(Folder.HOLDS_MESSAGES)
            } catch (e: Exception) {
                logEvent("folder create skipped: ${e.message}")
            }
        }
        folder.open(mode)
        return folder
    }

    private fun cleanupTunnelMessages(folder: Folder, deleteAll: Boolean): Int {
        var deleted = 0
        val now = System.currentTimeMillis()
        val ttlMs = tunnelMessageTtlSeconds * 1000L
        try {
            val candidates = folder.search(SubjectTerm(TUNNEL_SUBJECT_MARKER))
            for (msg in candidates) {
                try {
                    val shouldDelete = if (deleteAll) {
                        true
                    } else {
                        val body = extractBody(msg)
                        val ageMs = tunnelMessageAgeMs(body, msg.receivedDate, msg.sentDate)
                        ageMs != null && (now - ageMs) > ttlMs
                    }
                    if (shouldDelete) {
                        msg.setFlag(Flags.Flag.DELETED, true)
                        deleted++
                        val msgId = (msg as? MimeMessage)?.getMessageID() ?: ""
                        if (msgId.isNotEmpty()) seenMessageIds.remove(msgId)
                    }
                } catch (e: Exception) {
                    logEvent("cleanup msg error: ${e.message}")
                }
            }
            if (deleted > 0) {
                try {
                    (folder as? IMAPFolder)?.expunge()
                } catch (e: Exception) {
                    logEvent("expunge error: ${e.message}")
                }
                logEvent("cleanup: deleted $deleted tunnel message(s)")
            }
        } catch (e: Exception) {
            logEvent("cleanup search error: ${e.message}")
        }
        return deleted
    }

    override suspend fun init(channel: String) {
        logEvent("init: $login @ $host:$port folder=$imapFolderName mode=$sendMode windowSize=$windowSize")
        try {
            val session = Session.getInstance(imapProperties(10000))
            val store = connectStore(session)
            val folder = openImapFolder(store)
            cleanupTunnelMessages(folder, deleteAll = false)
            folder.close(false)
            store.close()
            logEvent("IMAP connected OK")
        } catch (e: Exception) {
            logEvent("IMAP FAILED: ${e.message}")
        }
    }

    override suspend fun poll(lastTransportSeq: Int): List<TransportMessage> {
        val session = Session.getInstance(imapProperties())
        val store = connectStore(session)
        val folder = openImapFolder(store)
        val messages = mutableListOf<TransportMessage>()

        try {
            cleanupTunnelMessages(folder, deleteAll = false)

            val candidates = folder.search(SubjectTerm(TUNNEL_SUBJECT_MARKER))
            for (msg in candidates) {
                try {
                    val body = extractBody(msg)
                    val msgId = (msg as? MimeMessage)?.getMessageID() ?: ""
                    if (msgId.isNotEmpty() && seenMessageIds.contains(msgId)) continue

                    val json = unwrapTransportJson(body)
                    if (json.isEmpty()) {
                        logEvent("poll skip: no WLYA body")
                        continue
                    }
                    val transportMsg = tryParseTransportJson(json)
                    if (transportMsg != null) {
                        messages.add(transportMsg)
                        logEvent("poll $imapFolderName: found ts=${transportMsg.transportSeq}")
                        if (msgId.isNotEmpty()) seenMessageIds.add(msgId)
                    } else {
                        logEvent("poll skip: transport json parse failed")
                    }
                } catch (e: Exception) {
                    logEvent("poll msg error: ${e.message}")
                }
            }
            folder.close(false)
            store.close()
        } catch (e: Exception) {
            try {
                folder.close(false)
            } catch (_: Exception) {
            }
            try {
                store.close()
            } catch (_: Exception) {
            }
            logEvent("poll FAILED: ${e.message}")
            throw IllegalStateException("Email poll failed: ${e.message}")
        }
        return messages
    }

    override suspend fun send(msg: TransportMessage) {
        if (sendMode == "imap") {
            sendViaImap(msg)
        } else {
            sendViaSmtp(msg)
        }
    }

    private fun buildMimeMessage(session: Session, msg: TransportMessage): MimeMessage {
        val mimeMsg = MimeMessage(session)
        mimeMsg.setFrom(InternetAddress(login))
        mimeMsg.setRecipients(Message.RecipientType.TO, smtpTo)
        mimeMsg.subject = "$TUNNEL_SUBJECT_MARKER ${msg.from.take(8)} ts=${msg.transportSeq}"
        mimeMsg.setSentDate(Date())
        mimeMsg.setText(wrapTransportJson(transportMessageToJson(msg)), "UTF-8", "plain")
        return mimeMsg
    }

    private suspend fun sendViaSmtp(msg: TransportMessage) {
        val props = Properties()
        props["mail.smtp.host"] = smtpHost
        props["mail.smtp.port"] = smtpPort.toString()
        if (smtpUseSSL) props["mail.smtp.ssl.enable"] = "true"
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.connectiontimeout"] = "10000"
        props["mail.smtp.timeout"] = "10000"

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(login, password)
        })

        val mimeMsg = buildMimeMessage(session, msg)
        try {
            Transport.send(mimeMsg)
            logEvent("send smtp: ts=${msg.transportSeq} ok")
        } catch (e: Exception) {
            logEvent("send smtp FAILED: ${e.message}")
            throw IllegalStateException("Email send failed: ${e.message}")
        }
    }

    private suspend fun sendViaImap(msg: TransportMessage) {
        try {
            val session = Session.getInstance(imapProperties())
            val store = connectStore(session)
            val folder = openImapFolder(store)
            val imapFolder = folder as? IMAPFolder
                ?: throw IllegalStateException("IMAP APPEND requires IMAP folder")
            val mimeMsg = buildMimeMessage(session, msg)
            imapFolder.appendMessages(arrayOf(mimeMsg))
            folder.close(false)
            store.close()
            logEvent("send imap: ts=${msg.transportSeq} ok")
        } catch (e: Exception) {
            logEvent("send imap FAILED: ${e.message}")
            throw IllegalStateException("Email send failed: ${e.message}")
        }
    }

    private fun extractBody(msg: Message): String {
        return try {
            when (val content = msg.content) {
                null -> ""
                is String -> content
                is Multipart -> {
                    for (i in 0 until content.count) {
                        val part = content.getBodyPart(i)
                        if (part.contentType?.contains("text/plain") == true) {
                            return (part.content as? String) ?: ""
                        }
                    }
                    ""
                }
                else -> content.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun wrapTransportJson(json: String): String {
        val b64 = Base64.encode(json.toByteArray(Charsets.UTF_8))
        return "-----BEGIN WLYA-----\n$b64\n-----END WLYA-----"
    }

    private fun transportMessageToJson(msg: TransportMessage): String {
        val obj = buildJsonObject {
            put("id", msg.id)
            put("from", msg.from)
            put("content", msg.content)
            put("iv", msg.iv)
            put("crc", msg.crc)
            put("timestamp", msg.timestamp)
            put("transportSeq", msg.transportSeq)
            msg.partOf?.let { put("partOf", it) }
            msg.partIndex?.let { put("partIndex", it) }
            msg.totalParts?.let { put("totalParts", it) }
        }
        return Json.encodeToString(obj)
    }

    private fun tryParseTransportJson(body: String): TransportMessage? {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        try {
            val element = Json.parseToJsonElement(body.substring(start, end + 1))
            if (element !is JsonObject) return null
            return TransportMessage(
                id = element["id"]?.jsonPrimitive?.content ?: return null,
                from = element["from"]?.jsonPrimitive?.content ?: return null,
                content = element["content"]?.jsonPrimitive?.content ?: return null,
                iv = element["iv"]?.jsonPrimitive?.content ?: return null,
                crc = element["crc"]?.jsonPrimitive?.content ?: return null,
                timestamp = element["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                transportSeq = element["transportSeq"]?.jsonPrimitive?.intOrNull ?: 0,
                partOf = element["partOf"]?.jsonPrimitive?.contentOrNull,
                partIndex = element["partIndex"]?.jsonPrimitive?.intOrNull,
                totalParts = element["totalParts"]?.jsonPrimitive?.intOrNull,
            )
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun clearHistory() {
        log.clear()
        try {
            val session = Session.getInstance(imapProperties())
            val store = connectStore(session)
            val folder = openImapFolder(store)
            val n = cleanupTunnelMessages(folder, deleteAll = true)
            folder.close(false)
            store.close()
            logEvent("mailbox cleared: $n tunnel message(s) deleted")
        } catch (e: Exception) {
            logEvent("clearHistory mailbox error: ${e.message}")
        }
    }
}
