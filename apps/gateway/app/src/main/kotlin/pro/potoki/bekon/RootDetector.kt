package pro.potoki.bekon

import android.util.Log
import java.io.File

/**
 * Detects root access by checking for su binary in common locations.
 * Also verifies we can actually execute su.
 */
object RootDetector {
    private const val TAG = "RootDetector"

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/local/tmp/su",
        "/su/bin/su",
        "/system_ext/bin/su",
        "/product/bin/su",
        "/debug_ramdisk/su",
    )

    @Volatile
    var isRooted: Boolean = false
        private set

    fun detect(): Boolean {
        isRooted = SU_PATHS.any { File(it).exists() }
        if (isRooted) {
            Log.i(TAG, "Root detected — su binary found")
            // Verify we can actually execute
            isRooted = testSu()
        }
        Log.i(TAG, "Root mode: $isRooted")
        return isRooted
    }

    private fun testSu(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val result = process.inputStream.bufferedReader().readText()
            val hasRoot = result.contains("uid=0") || result.contains("root")
            process.waitFor()
            hasRoot
        } catch (e: Exception) {
            Log.w(TAG, "su test failed: ${e.message}")
            false
        }
    }

    /** Execute command as root. Returns trimmed stdout or null on failure. Text only. */
    fun exec(cmd: String): String? {
        val bytes = execBytes(cmd) ?: return null
        return bytes.toString(Charsets.UTF_8).trim().ifEmpty { null }
    }

    /** Raw stdout of `su -c`. Do not decode as UTF-8 — screencap PNG is binary. */
    fun execBytes(cmd: String): ByteArray? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            var err = ByteArray(0)
            val errThread = Thread {
                err = process.errorStream.readBytes()
            }
            errThread.start()
            val out = process.inputStream.readBytes()
            errThread.join(8_000)
            val code = process.waitFor()
            if (err.isNotEmpty()) Log.w(TAG, "su stderr: ${err.toString(Charsets.UTF_8).take(400)}")
            if (out.isEmpty()) {
                Log.w(TAG, "su stdout empty code=$code cmd=$cmd")
                null
            } else out
        } catch (e: Exception) {
            Log.e(TAG, "su execBytes failed: ${e.message}")
            null
        }
    }

    fun pngFromSuStdout(raw: ByteArray): ByteArray? {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (raw.size < sig.size) return null
        if (raw.copyOfRange(0, sig.size).contentEquals(sig)) return raw
        outer@ for (i in 0..raw.size - sig.size) {
            for (j in sig.indices) {
                if (raw[i + j] != sig[j]) continue@outer
            }
            return raw.copyOfRange(i, raw.size)
        }
        return null
    }
}
