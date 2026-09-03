package pro.potoki.bekon.intent

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import pro.potoki.bekon.AgentForegroundService
import pro.potoki.bekon.ScreenWakeHelper
import pro.potoki.bekon.capture.CaptureProvider
import pro.potoki.bekon.capture.CapturePrefs
import pro.potoki.bekon.capture.DisplayMetricsHelper
import pro.potoki.bekon.touch.A11yDump
import pro.potoki.bekon.touch.TouchController
import pro.potoki.bekon.touch.TouchService
import org.json.JSONArray
import org.json.JSONObject

class IntentHandler(private val context: Context) {
    companion object {
        private const val TAG = "IntentHandler"
        private const val MAX_SLEEP_MS = 30_000L
        private const val MAX_INPUT_CHARS = 4000
    }

    @Volatile var captureProvider: CaptureProvider? = null
    /** ElapsedRealtime of last non-sleep/ping command (and of the last wake). 0 = never. */
    private var lastActivityAt = 0L
    private val heavyLock = Any()

    fun handle(raw: String): String {
        when (raw) {
            "PING" -> return "PONG"
            "PONG" -> return ""
        }
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return ""
        return try {
            if (isPingOnlyBatch(trimmed)) handleBatch(trimmed)
            else synchronized(heavyLock) { handleBatch(trimmed) }
        } catch (e: Exception) {
            Log.e(TAG, "Parse: ${e.message}")
            try {
                TouchService.instance?.controller?.endHeld()
            } catch (_: Exception) { }
            JSONArray().put(JSONObject().put("error", e.message)).toString()
        }
    }

    private fun handleBatch(trimmed: String): String {
        val arr = JSONArray(trimmed)
        val out = JSONArray()
        try {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                out.put(
                    if (item == null) errorObj("", "unknown")
                    else handleOne(item),
                )
            }
        } finally {
            TouchService.instance?.controller?.endHeld()
        }
        return out.toString()
    }

    private fun handleOne(cmd: JSONObject): JSONObject {
        val id = cmd.optString("id", "")
        val cmdName = cmd.optString("cmd")
        if (cmdName.isEmpty()) return errorObj(id, "unknown")
        return try {
            wakeIfStale(cmdName)
            when (cmdName) {
                "screenshot" -> {
                    val hiRes = cmd.optBoolean("hiRes", false)
                    val scale = if (cmd.has("scale")) cmd.optDouble("scale").toFloat() else null
                    val quality = if (cmd.has("quality")) cmd.optInt("quality") else null
                    val opts = CapturePrefs.resolve(context, hiRes, scale, quality)
                    val frame = captureProvider?.capture(opts)
                    if (frame == null) errorObj(id, "capture failed")
                    else {
                        val b64 = Base64.encodeToString(frame.bytes, Base64.NO_WRAP)
                        val (sw, sh) = DisplayMetricsHelper.realScreenSize(context)
                        val fields = mutableMapOf<String, Any?>(
                            "type" to "screenshot",
                            "data" to b64,
                            "size" to frame.bytes.size,
                            "mime" to frame.mime,
                            "captureW" to frame.width,
                            "captureH" to frame.height,
                            "screenW" to sw,
                            "screenH" to sh,
                        )
                        A11yDump.gzipBase64OrNull(context)?.let { fields["a11y"] = it }
                        okObj(id, fields)
                    }
                }
                "ping" -> okObj(id, mapOf("ok" to true))
                // tap/swipe/longPress/drag: device screen pixels (same as a11y bounds).
                "tap" -> touchAction(id, "tap") { tc ->
                    val x = cmd.getDouble("x").toFloat()
                    val y = cmd.getDouble("y").toFloat()
                    if (!tc.tap(x, y)) throw Exception("tap cancelled")
                }
                "swipe" -> touchAction(id, "swipe") { tc ->
                    val x1 = cmd.getDouble("x1").toFloat()
                    val y1 = cmd.getDouble("y1").toFloat()
                    val x2 = cmd.getDouble("x2").toFloat()
                    val y2 = cmd.getDouble("y2").toFloat()
                    if (!tc.swipe(x1, y1, x2, y2)) throw Exception("swipe cancelled")
                }
                "longPress" -> touchAction(id, "longPress") { tc ->
                    val x = cmd.getDouble("x").toFloat()
                    val y = cmd.getDouble("y").toFloat()
                    if (!tc.longPress(x, y)) throw Exception("longPress cancelled")
                }
                "drag" -> touchAction(id, "drag") { tc ->
                    val x = cmd.getDouble("x").toFloat()
                    val y = cmd.getDouble("y").toFloat()
                    if (!tc.drag(x, y)) throw Exception("drag cancelled")
                }
                "release" -> touchAction(id, "release") { tc ->
                    if (!tc.release()) throw Exception("release cancelled")
                }
                "back" -> touchAction(id, "back") { tc ->
                    if (!tc.back()) throw Exception("back failed")
                }
                "home" -> touchAction(id, "home") { tc ->
                    if (!tc.home()) throw Exception("home failed")
                }
                "recentApps" -> touchAction(id, "recentApps") { tc ->
                    if (!tc.recentApps()) throw Exception("recentApps failed")
                }
                "sleep" -> {
                    val ms = cmd.optLong("ms", 0).coerceIn(0, MAX_SLEEP_MS)
                    if (ms > 0) Thread.sleep(ms)
                    ackObj(id, "sleep")
                }
                "input" -> {
                    val text = cmd.optString("text", "")
                    val asKeys = cmd.optString("mode") == "keys"
                    if (text.length > MAX_INPUT_CHARS) {
                        errorObj(id, "input too long")
                    } else {
                        touchAction(id, "input") { tc ->
                            if (!tc.input(text, asKeys)) {
                                throw Exception(
                                    if (asKeys) "no_keys: cannot inject KeyEvents"
                                    else "no_input: no editable field focused",
                                )
                            }
                        }
                    }
                }
                "key" -> {
                    val key = cmd.optString("key")
                    val n = cmd.optInt("n", 1)
                    val result = touchAction(id, "key") { tc ->
                        if (!tc.key(key, n)) throw Exception("key failed: $key")
                    }
                    if (result.optBoolean("ok") && (key == "copy" || key == "cut")) {
                        Thread.sleep(50)
                        clipFromPhone()?.let { result.put("text", it) }
                    }
                    result
                }
                "clipboard" -> {
                    val text = clipFromPhone()
                    if (text == null) errorObj(id, "clipboard empty or unavailable")
                    else okObj(id, mapOf("type" to "clipboard", "cmd" to "clipboard", "text" to text))
                }
                "putFile" -> {
                    val name = cmd.optString("name", "file.bin")
                    val mime = cmd.optString("mime", "application/octet-stream")
                    val data = cmd.optString("data", "")
                    if (data.isBlank()) errorObj(id, "no data")
                    else {
                        val bytes = Base64.decode(data, Base64.DEFAULT)
                        val dest = FileInbox.save(context, name, mime, bytes)
                        okObj(
                            id,
                            mapOf(
                                "type" to "putFile",
                                "path" to dest.path,
                                "name" to dest.name,
                                "size" to dest.size,
                                "mime" to mime,
                                "uri" to dest.uri,
                                "publicPath" to dest.publicPath,
                            ),
                        )
                    }
                }
                "share" -> {
                    val path = cmd.optString("path", "")
                    val uri = cmd.optString("uri", "")
                    val mime = cmd.optString("mime", "")
                    val pkg = cmd.optString("package", cmd.optString("pkg", ""))
                    FileInbox.share(context, path, mime, pkg.ifBlank { null }, uri.ifBlank { null })
                    ackObj(id, "share")
                }
                "logs" -> {
                    if (!ApkSelfUpdate.logsEnabled(context)) {
                        errorObj(id, "logs sharing disabled")
                    } else {
                        val n = cmd.optInt("n", 80).coerceIn(10, 200)
                        val mgr = AgentForegroundService.instance?.wlyaManager
                        val adapter = mgr?.snapshotAdapterLogLines()?.takeLast(n).orEmpty()
                        val messages = mgr?.snapshotMessageLines()?.takeLast(n).orEmpty()
                        val core = mgr?.activeTunnelLogSnapshot(n).orEmpty()
                        val update = ApkSelfUpdate.recentLog(n)
                        okObj(
                            id,
                            mapOf(
                                "type" to "logs",
                                "adapter" to adapter,
                                "messages" to messages,
                                "core" to core,
                                "apkUpdate" to update,
                            ),
                        )
                    }
                }
                else -> errorObj(id, "unknown")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cmd $cmdName: ${e.message}")
            errorObj(id, e.message ?: "$cmdName failed")
        } finally {
            markActivity(cmdName)
        }
    }

    private fun isPingOnlyBatch(trimmed: String): Boolean {
        if (trimmed.length > 4000) return false
        return try {
            val arr = JSONArray(trimmed)
            if (arr.length() == 0) return false
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("cmd") != "ping") return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * If idle (wall time since last real command, including a `sleep` in this batch
     * or a gap between executes) ≥ the Status setting, wake before this command.
     * Applies to every kind except ping, including screenshot.
     */
    private fun wakeIfStale(cmdName: String) {
        if (cmdName == "ping") return
        val threshold = ScreenWakeHelper.wakeAfterSleepMs(context)
        if (threshold <= 0) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (lastActivityAt == 0L) Long.MAX_VALUE else now - lastActivityAt
        if (elapsed < threshold) return
        if (ScreenWakeHelper.ensureAwake(context, TouchService.instance?.controller)) {
            captureProvider?.onDisplayWoke()
        }
        lastActivityAt = SystemClock.elapsedRealtime()
    }

    private fun markActivity(cmdName: String) {
        if (cmdName == "sleep" || cmdName == "ping") return
        lastActivityAt = SystemClock.elapsedRealtime()
    }

    private fun clipFromPhone(): String? {
        val raw = TouchService.instance?.controller?.clipboardText() ?: return null
        return if (raw.length > MAX_INPUT_CHARS) raw.take(MAX_INPUT_CHARS) else raw
    }

    private inline fun touchAction(
        id: String,
        cmd: String,
        crossinline action: (TouchController) -> Unit,
    ): JSONObject {
        val tc = TouchService.instance?.controller ?: return errorObj(
            id,
            "no_touch: accessibility listed but not bound — toggle Bekon Touch off/on",
        )
        return try {
            action(tc)
            ackObj(id, cmd)
        } catch (e: Exception) {
            errorObj(id, e.message ?: "$cmd failed")
        }
    }

    private fun ackObj(id: String, cmd: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("ok", true)
            .put("type", "ack")
            .put("cmd", cmd)

    private fun okObj(id: String, m: Map<String, Any?>): JSONObject {
        val j = JSONObject().put("id", id).put("ok", true)
        m.forEach { (k, v) ->
            when (v) {
                null -> { }
                is Collection<*> -> {
                    val arr = JSONArray()
                    v.forEach { arr.put(it) }
                    j.put(k, arr)
                }
                else -> j.put(k, v)
            }
        }
        return j
    }

    private fun errorObj(id: String, msg: String): JSONObject =
        JSONObject().put("id", id).put("ok", false).put("error", msg)
}
