package pro.potoki.bekon.call

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WlyaCallClient(
    private val onPcm: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onJson: (String) -> Unit = {},
    private val onDropped: (String) -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    private val gen = AtomicInteger(0)

    fun connect(url: String, seed: String, room: String, clientId: String) {
        val mine = dropSocket()
        onStatus("connecting")
        val href = signedUrl(url, seed, clientId)
        val req = Request.Builder().url(href).build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (mine != gen.get()) return
                webSocket.send("""{"type":"join","room":${jsonStr(room)}}""")
                onStatus("joined")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (mine != gen.get()) return
                onJson(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (mine != gen.get()) return
                val pcm = VoicePcm.decodeFrame(bytes.toByteArray()) ?: return
                try {
                    onPcm(pcm)
                } catch (e: Exception) {
                    Log.e(TAG, "PCM callback failed: ${e.message}", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (mine != gen.get()) return
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropIfCurrent(mine, webSocket)
                if (mine != gen.get()) return
                val why = if (reason.isNotBlank()) reason else "closed $code"
                Log.i(TAG, "ws closed: $why")
                onDropped(why)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropIfCurrent(mine, webSocket)
                if (mine != gen.get()) return
                val why = t.message?.takeIf { it.isNotBlank() } ?: "socket"
                Log.e(TAG, "ws failed: $why")
                onDropped(why)
            }
        })
    }

    fun sendPcm(pcm: ByteArray) {
        val ws = socket ?: return
        ws.send(VoicePcm.encodeFrame(pcm).toByteString())
    }

    fun sendJson(text: String) {
        val ws = socket ?: return
        ws.send(text)
    }

    fun disconnect() {
        dropSocket()
    }

    private fun dropIfCurrent(mine: Int, webSocket: WebSocket) {
        if (mine != gen.get()) return
        if (socket === webSocket) socket = null
    }

    private fun dropSocket(): Int {
        val ws = socket
        socket = null
        val next = gen.incrementAndGet()
        ws?.cancel()
        return next
    }

    companion object {
        private const val TAG = "WlyaCall"

        fun signedUrl(base: String, seed: String, client: String): String {
            val ts = (System.currentTimeMillis() / 1000L).toString()
            val sig = VoiceHmac.sign(seed, ts, "")
            val parsed = base.trim().replaceFirst("^ws://".toRegex(), "http://")
                .replaceFirst("^wss://".toRegex(), "https://")
                .toHttpUrlOrNull()
                ?: throw IllegalArgumentException("bad url")
            val https = parsed.newBuilder()
                .setQueryParameter("seed", seed)
                .setQueryParameter("client", client)
                .setQueryParameter("ts", ts)
                .setQueryParameter("sig", sig)
                .build()
                .toString()
            return when {
                base.trim().startsWith("ws://") -> https.replaceFirst("http://", "ws://")
                else -> https.replaceFirst("https://", "wss://")
            }
        }

        private fun jsonStr(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
