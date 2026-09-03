package pro.potoki.bekon.tunnel

import android.util.Base64
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Pure Kotlin WebSocket tunnel adapter.
 * No Node.js — implements RFC 6455 handshake + frames directly.
 * Bekon connects via ws://phone:9090.
 */
class WebSocketTunnelAdapter(private val port: Int = 9090) : TunnelAdapter {

    companion object { private const val TAG = "WSTunnel" }

    private var server: WSServer? = null
    private var onMsg: ((String) -> Unit)? = null
    private var onErr: ((String) -> Unit)? = null
    @Volatile private var started = false

    fun start(): Boolean {
        if (started) return true
        return try {
            val srv = WSServer(port) { raw -> onMsg?.invoke(raw) }
            server = srv
            thread(name = "WSServer") { srv.run() }
            Thread.sleep(300)
            started = true
            Log.i(TAG, "WS server bound 9090")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WS start failed: ${e.message}")
            false
        }
    }

    override fun connect(onMessage: (String) -> Unit, onError: (String) -> Unit) {
        onMsg = onMessage
        onErr = onError
        // Note: start() was already called in AgentForegroundService.initTunnel()
        // Do NOT call start() again here — avoid EADDRINUSE
        Log.i(TAG, "WebSocket tunnel connected")
    }

    override fun send(message: String) { server?.broadcast(message) }

    override fun disconnect() {
        started = false
        server?.stop()
        onMsg = null
        onErr = null
        Log.i(TAG, "WebSocket tunnel disconnected")
    }
}

/** Minimal single-client WebSocket server. */
class WSServer(port: Int, private val onMsg: (String) -> Unit) : Runnable {
    private val ss: ServerSocket
    private var client: ClientHandler? = null
    @Volatile private var running = false

    init { ss = ServerSocket(port) }

    override fun run() {
        running = true
        Log.i("WSServer", "Listening :${ss.localPort}")
        while (running) {
            try {
                val s = ss.accept()
                synchronized(this) {
                    client?.let { try { it.close() } catch (_: Exception) {} }
                    client = ClientHandler(s, onMsg)
                    thread(name = "WS-Client") { client!!.run() }
                }
            } catch (_: Exception) { if (!running) break }
        }
    }

    fun broadcast(msg: String) { synchronized(this) { client?.send(msg) } }
    fun stop() {
        running = false
        try { ss.close() } catch (_: Exception) {}
        client?.let { try { it.close() } catch (_: Exception) {} }
    }
}

/** One WS client — HTTP upgrade + RFC 6455 frames. */
class ClientHandler(private val s: Socket, private val onMsg: (String) -> Unit) : Runnable {
    private val out: OutputStream = s.getOutputStream()
    private val inp: InputStream = s.getInputStream()
    @Volatile private var closed = false

    override fun run() {
        try {
            // === HTTP Upgrade ===
            val br = BufferedReader(InputStreamReader(inp, "ISO-8859-1"))
            val req = br.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var ln: String?
            while (true) {
                ln = br.readLine()
                if (ln == null || ln.isEmpty()) break
                val colon = ln.indexOf(':')
                if (colon > 0) headers[ln.substring(0, colon).trim().lowercase()] =
                    ln.substring(colon + 1).trim()
            }
            if (!req.startsWith("GET") || headers["upgrade"]?.lowercase() != "websocket") {
                s.close(); return
            }

            val accept = genAccept(headers["sec-websocket-key"] ?: "")
            val resp = (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            )
            out.write(resp.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            Log.i("WSClient", "handshake OK")

            // === Frame loop ===
            while (!closed && !s.isClosed) {
                val b1 = readByte(); if (b1 == -1) break
                val b2 = readByte(); if (b2 == -1) break
                val opcode = b1 and 0x0F
                val masked = (b2 and 0x80) != 0
                var len = b2 and 0x7F
                if (len == 126) {
                    len = (readByte() shl 8) or readByte()
                } else if (len == 127) {
                    repeat(8) { readByte() }
                    len = 0
                }
                val maskKey = if (masked) ByteArray(4).also { inp.readFully(it) } else null
                val payload = ByteArray(len)
                inp.readFully(payload)
                if (maskKey != null) {
                    for (i in payload.indices) payload[i] =
                        (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                }
                when (opcode) {
                    0x1 -> { // text
                        onMsg(String(payload, Charsets.UTF_8))
                    }
                    0x8 -> { // close
                        out.write(byteArrayOf(0x88.toByte(), 0x00.toByte()))
                        out.flush()
                        closed = true
                    }
                    0x9 -> { // ping -> pong
                        out.write(0x8A)
                        out.write(payload.size)
                        out.write(payload)
                        out.flush()
                    }
                }
            }
        } catch (_: Exception) {}
        finally { closeQuiet() }
    }

    fun send(msg: String) {
        if (closed || s.isClosed) return
        try {
            val bytes = msg.toByteArray(Charsets.UTF_8)
            out.write(0x81) // FIN + TEXT
            if (bytes.size < 126) {
                out.write(bytes.size)
            } else if (bytes.size < 65536) {
                out.write(126)
                out.write(bytes.size shr 8)
                out.write(bytes.size and 0xFF)
            } else {
                out.write(127)
                for (i in 7 downTo 0) {
                    out.write((bytes.size.toLong() shr (i * 8) and 0xFF).toInt())
                }
            }
            out.write(bytes)
            out.flush()
        } catch (_: Exception) { closed = true }
    }

    private fun readByte(): Int {
        val b = inp.read()
        return b
    }

    private fun InputStream.readFully(dst: ByteArray) {
        var r = 0
        while (r < dst.size) {
            val n = this.read(dst, r, dst.size - r)
            if (n == -1) break
            r += n
        }
    }

    private fun closeQuiet() = try { s.close() } catch (_: Exception) {}
    fun close() { closed = true; closeQuiet() }

    private fun genAccept(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update((key + magic).toByteArray(Charsets.ISO_8859_1))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }
}
