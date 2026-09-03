package com.wlya.core.adapters

import com.wlya.core.Base64
import com.wlya.core.BaseAdapter
import com.wlya.core.TransportMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HTTP client for the Node WLYA-Server relay.
 * Encrypt/decrypt stays in Tunnel/Crypto; this adapter only HMAC-auths and ships blobs.
 */
class WlyaServerAdapter(
    instanceId: String,
    config: Map<String, Any>,
) : BaseAdapter() {
    override val name: String = "wlyaserver:$instanceId"
    /** User-facing cap is HTTP POST size (DPI). Tunnel splits on cipher bytes, which is smaller. */
    private val packetSize: Int = BaseAdapter.parseWindowSize(config, DEFAULT_PACKET_SIZE)
    override val windowSize: Int = cipherWindowForPacket(packetSize)
    override val pollIntervalMs: Int = BaseAdapter.parsePollIntervalMs(config, BaseAdapter.DEFAULT_POLL_INTERVAL_MS)
    override val serialIo: Boolean = false

    private val serverUrl: String
    private val clientId: String
    private var channel: String = ""
    private var cursor: Long? = null

    init {
        serverUrl = normalizeServerUrl(cfgString(config, "serverUrl", DEFAULT_SERVER_URL))
        val cfgClient = cfgString(config, "clientId", "")
        clientId = cfgClient.ifBlank { UUID.randomUUID().toString() }
    }

    override suspend fun init(channel: String) {
        this.channel = channel
        logEvent("init: $serverUrl client=$clientId packetSize=$packetSize cipherWindow=$windowSize")
        try {
            val conn = open("GET", "/health", signBody = "")
            val code = conn.responseCode
            conn.disconnect()
            logEvent(if (code in 200..299) "health OK" else "health HTTP $code")
        } catch (e: Exception) {
            logEvent("health FAILED: ${e.javaClass.simpleName}: ${e.message} url=$serverUrl/health")
        }
    }

    override suspend fun poll(lastTransportSeq: Int): List<TransportMessage> {
        if (channel.isEmpty()) throw IllegalStateException("adapter not inited")
        val t0 = System.currentTimeMillis()
        try {
            val q = cursor?.let { "?cursor=$it" } ?: ""
            val conn = open("GET", "/v1/messages$q", signBody = "")
            val code = conn.responseCode
            val text = readBody(conn, code)
            conn.disconnect()
            if (code == 429) throw IllegalStateException("WLYA-Server rate limit")
            if (code !in 200..299) throw IllegalStateException("WLYA-Server poll HTTP $code: $text")

            val root = Json.parseToJsonElement(text).jsonObject
            root["next_cursor"]?.jsonPrimitive?.longOrNull?.let { cursor = it }
            val out = mutableListOf<TransportMessage>()
            val arr = root["messages"]?.jsonArray
            if (arr != null) {
                for (el in arr) {
                    val dataB64 = el.jsonObject["data"]?.jsonPrimitive?.content ?: continue
                    val json = try {
                        String(Base64.decode(dataB64), Charsets.UTF_8)
                    } catch (_: Exception) {
                        continue
                    }
                    parseTransport(json)?.let { msg ->
                        if (msg.transportSeq > lastTransportSeq) out.add(msg)
                    }
                }
            }
            logEvent("poll ${out.size} ${System.currentTimeMillis() - t0}ms")
            return out
        } catch (e: Exception) {
            logEvent("poll FAILED ${System.currentTimeMillis() - t0}ms ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    override suspend fun send(msg: TransportMessage) {
        if (channel.isEmpty()) throw IllegalStateException("adapter not inited")
        val t0 = System.currentTimeMillis()
        val blob = Base64.encode(Json.encodeToString(msg).toByteArray(Charsets.UTF_8))
        val body = """{"messages":[{"id":${jsonStr(msg.id)},"data":${jsonStr(blob)},"ts":${msg.timestamp}}]}"""
        val bytes = body.toByteArray(Charsets.UTF_8).size
        val conn = open("POST", "/v1/messages", signBody = body)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val text = readBody(conn, code)
            val ms = System.currentTimeMillis() - t0
            if (code == 429) throw IllegalStateException("WLYA-Server rate limit")
            if (code !in 200..299) {
                logEvent("ERROR sent FAILED ${ms}ms seq=${msg.transportSeq} HTTP $code bytes=$bytes")
                throw IllegalStateException("WLYA-Server send HTTP $code: $text")
            }
            val part = if (msg.totalParts != null && msg.totalParts > 1)
                " part=${(msg.partIndex ?: 0) + 1}/${msg.totalParts}" else ""
            logEvent("sent seq=${msg.transportSeq} ${ms}ms bytes=$bytes$part")
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            if (e is IllegalStateException &&
                (e.message?.contains("HTTP") == true || e.message?.contains("rate limit") == true)
            ) throw e
            logEvent(
                "ERROR sent FAILED ${ms}ms seq=${msg.transportSeq} bytes=$bytes " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            throw e
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun clearHistory() {
        log.clear()
        cursor = null
        logEvent("local cursor cleared")
    }

    private fun open(method: String, path: String, signBody: String): HttpURLConnection {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val sig = hmac(channel, ts, signBody)
        val conn = URI.create("$serverUrl$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("X-WLYA-Seed", channel)
        conn.setRequestProperty("X-WLYA-Client", clientId)
        conn.setRequestProperty("X-WLYA-Timestamp", ts)
        conn.setRequestProperty("X-WLYA-Sig", sig)
        return conn
    }

    private fun hmac(channel: String, timestamp: String, body: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest(channel.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal((channel + timestamp + body).toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
    }

    private fun parseTransport(json: String): TransportMessage? {
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return try {
            Json.decodeFromString<TransportMessage>(json.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun cfgString(config: Map<String, Any>, key: String, default: String): String {
        val v = config[key] ?: return default
        val s = v.toString().trim()
        return s.ifEmpty { default }
    }

    private fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return trimmed.ifEmpty { DEFAULT_SERVER_URL }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://relay.example"
        /** Default on-wire POST cap (TSPU/DPI often drops larger TLS/HTTP bodies). */
        const val DEFAULT_PACKET_SIZE = 262144

        /** Cipher chunk so JSON+base64 wrapping still fits in [packetBytes]. */
        fun cipherWindowForPacket(packetBytes: Int): Int {
            val budget = (packetBytes - 160) * 3 / 4 - 800
            return budget.coerceIn(BaseAdapter.MIN_WINDOW_SIZE, packetBytes)
        }

        init {
            // Cloudflare AAAA often stalls on mobile; prefer IPv4 for HTTP polls.
            System.setProperty("java.net.preferIPv4Stack", "true")
        }
    }
}
