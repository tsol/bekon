package pro.potoki.bekon.touch

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

object A11yDump {
    private const val TAG = "A11yDump"
    const val PREFS_DUMP = "a11y_dump"
    private const val PREFS_NAME = "wlya_prefs"
    private const val MAX_NODES = 400
    private const val MAX_TEXT = 200

    fun isDumpEnabled(ctx: Context): Boolean = true

    fun setDumpEnabled(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_DUMP, on)
            .apply()
    }

    /** Gzip+Base64 of a compact JSON array, or null if dump is off/unavailable/empty. */
    fun gzipBase64OrNull(ctx: Context): String? {
        if (!isDumpEnabled(ctx)) return null
        val json = dumpJson() ?: return null
        if (json == "[]") return null
        return try {
            gzipB64(json)
        } catch (e: Exception) {
            Log.w(TAG, "gzip: ${e.message}")
            null
        }
    }

    fun dumpJson(): String? {
        val svc = TouchService.instance ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return dumpOnService(svc)
        }
        val box = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                box[0] = dumpOnService(TouchService.instance)
            } catch (e: Exception) {
                Log.w(TAG, "dump: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(2, TimeUnit.SECONDS)
            box[0]
        } catch (_: InterruptedException) {
            null
        }
    }

    private fun dumpOnService(svc: TouchService?): String? {
        if (svc == null) return null
        val out = JSONArray()
        try {
            val windows = svc.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    if (out.length() >= MAX_NODES) break
                    val root = try {
                        w.root
                    } catch (_: Exception) {
                        null
                    }
                    if (root != null) {
                        walk(root, out)
                        recycleQuiet(root)
                    }
                }
            } else {
                val root = svc.rootInActiveWindow ?: return null
                walk(root, out)
                recycleQuiet(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "walk: ${e.message}")
            return null
        }
        return if (out.length() == 0) null else out.toString()
    }

    private fun walk(node: AccessibilityNodeInfo, out: JSONArray) {
        if (out.length() >= MAX_NODES) return
        val text = clip(node.text?.toString())
        val desc = clip(node.contentDescription?.toString())
        val id = node.viewIdResourceName
        val clickable = node.isClickable
        val editable = node.isEditable
        val checked = node.isCheckable && node.isChecked
        val focused = node.isFocused
        val useful = !text.isNullOrEmpty() || !desc.isNullOrEmpty() || !id.isNullOrEmpty() ||
            clickable || editable || checked || focused
        if (useful) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val obj = JSONObject()
            obj.put("bounds", JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
            if (!text.isNullOrEmpty()) obj.put("text", text)
            if (!desc.isNullOrEmpty()) obj.put("desc", desc)
            val cls = node.className?.toString()?.substringAfterLast('.')
            if (!cls.isNullOrEmpty()) obj.put("class", cls)
            if (!id.isNullOrEmpty()) obj.put("id", id)
            if (clickable) obj.put("clickable", true)
            if (editable) obj.put("editable", true)
            if (checked) obj.put("checked", true)
            if (focused) obj.put("focused", true)
            out.put(obj)
        }
        val n = node.childCount
        for (i in 0 until n) {
            if (out.length() >= MAX_NODES) return
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            walk(child, out)
            recycleQuiet(child)
        }
    }

    private fun clip(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return if (t.length <= MAX_TEXT) t else t.take(MAX_TEXT)
    }

    private fun recycleQuiet(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: Exception) {
            /* ignore */
        }
    }

    private fun gzipB64(json: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            gz.write(json.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
