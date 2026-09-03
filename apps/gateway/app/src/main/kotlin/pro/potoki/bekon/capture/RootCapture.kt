package pro.potoki.bekon.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import pro.potoki.bekon.RootDetector
import java.io.ByteArrayOutputStream

/**
 * Root-based screen capture using screencap binary.
 * No permissions, no dialogs. Works on rooted devices.
 */
class RootCapture(context: android.content.Context) : CaptureProvider {
    companion object {
        private const val TAG = "RootCapture"
        private const val SKIP_BLACK_MS = 1500L
        private const val BLACK_CHANNEL = 8
    }

    override val width: Int
    override val height: Int
    private val context = context.applicationContext

    @Volatile private var skipBlackUntil = 0L

    init {
        val (w, h) = DisplayMetricsHelper.realScreenSize(context)
        width = w
        height = h
        Log.i(TAG, "Root mode: ${width}x${height} (full resolution)")
    }

    override fun start() {
        Log.i(TAG, "Root capture ready")
    }

    override fun onDisplayWoke() {
        skipBlackUntil = System.currentTimeMillis() + SKIP_BLACK_MS
    }

    override fun capture(opts: CaptureOpts): CaptureFrame? {
        val deadline = skipBlackUntil
        while (true) {
            val frame = captureOnce(opts) ?: return null
            if (deadline == 0L || System.currentTimeMillis() >= deadline) return frame
            val bmp = BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)
            val black = bmp != null && bmp.isNearlyBlack(BLACK_CHANNEL)
            bmp?.recycle()
            if (!black) {
                skipBlackUntil = 0L
                return frame
            }
            Log.i(TAG, "Skipping black screencap after wake")
            Thread.sleep(80)
        }
    }

    private fun captureOnce(opts: CaptureOpts): CaptureFrame? {
        return try {
            val pngBytes = grabPng()
            if (pngBytes == null) {
                Log.w(TAG, "screencap returned empty")
                return null
            }
            val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode screencap output (${pngBytes.size} bytes)")
                return null
            }
            val (tw, th) = CapturePrefs.pixelSize(bitmap.width, bitmap.height, opts.scale)
            val sized = if (bitmap.width == tw && bitmap.height == th) bitmap else {
                val s = Bitmap.createScaledBitmap(bitmap, tw, th, true)
                bitmap.recycle()
                s
            }
            val out = ByteArrayOutputStream()
            sized.compress(Bitmap.CompressFormat.JPEG, opts.quality, out)
            sized.recycle()
            CaptureFrame(out.toByteArray(), tw, th, CaptureFrame.JPEG)
        } catch (e: Exception) {
            Log.e(TAG, "Root capture failed: ${e.message}")
            null
        }
    }

    private fun grabPng(): ByteArray? {
        RootDetector.execBytes("screencap -p")?.let { raw ->
            RootDetector.pngFromSuStdout(raw)?.let { return it }
            Log.w(TAG, "screencap -p stdout not a PNG (${raw.size} bytes)")
        }
        val tmp = "/data/local/tmp/bekon-scr.png"
        RootDetector.exec("screencap $tmp")
        return RootDetector.execBytes("cat $tmp")?.let { RootDetector.pngFromSuStdout(it) }
    }

    override fun stop() {
    }
}
