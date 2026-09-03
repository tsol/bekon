package pro.potoki.bekon.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

class ScreenCapture(private val context: Context) : CaptureProvider {
    companion object {
        private const val TAG = "ScreenCapture"
        private const val TIMEOUT_MS = 5000L
        private const val SKIP_BLACK_MS = 1500L
        private const val BLACK_CHANNEL = 8
    }

    override var width: Int
    override var height: Int
    private val screenW: Int
    private val screenH: Int
    private var mediaProjectionData: Intent? = null
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val captureThread = HandlerThread("ScreenCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    @Volatile private var skipBlackUntil = 0L
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            captureHandler.post {
                releasePreviewDisplay()
                mediaProjection = null
            }
        }
    }

    init {
        val (w, h) = DisplayMetricsHelper.realScreenSize(context)
        screenW = w
        screenH = h
        val preview = CapturePrefs.pixelSize(w, h, CapturePrefs.previewScale(context))
        width = preview.first
        height = preview.second
        Log.i(TAG, "MediaProjection mode: preview ${width}x${height}, screen ${screenW}x${screenH}")
    }

    /** Store permission data. start() uses it later. */
    fun setPermissionData(data: Intent, resultCode: Int) {
        mediaProjectionData = data
        mediaProjectionResultCode = resultCode
    }

    override fun start() {
        val data = mediaProjectionData ?: run {
            Log.w(TAG, "No permission data yet")
            return
        }
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mgr.getMediaProjection(mediaProjectionResultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection: ${e.message}")
            return
        }
        if (mp == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return
        }
        // Android 14+ requires a callback before createVirtualDisplay; harmless on 13/Itel.
        mp.registerCallback(projectionCallback, captureHandler)
        mediaProjection = mp
        bindPreviewDisplay()
        Log.i(TAG, "Virtual display created: ${width}x${height}")
    }

    override fun capture(opts: CaptureOpts): CaptureFrame? {
        val (tw, th) = CapturePrefs.pixelSize(screenW, screenH, opts.scale)
        if (!opts.hiRes) {
            syncPreviewFromPrefs()
            val reader = imageReader
            if (reader != null && tw == width && th == height) {
                return waitEncoded(reader, opts.quality, tw, th)
            }
        }
        return captureBySwap(tw, th, opts.quality)
    }

    override fun onDisplayWoke() {
        skipBlackUntil = System.currentTimeMillis() + SKIP_BLACK_MS
        drain(imageReader)
    }

    private fun syncPreviewFromPrefs() {
        val (tw, th) = CapturePrefs.pixelSize(screenW, screenH, CapturePrefs.previewScale(context))
        if (tw == width && th == height && imageReader != null) return
        width = tw
        height = th
        releasePreviewDisplay()
        bindPreviewDisplay()
    }

    /**
     * MediaProjection typically allows one virtual display. Tear down the preview,
     * capture at [w]×[h] JPEG, then restore preview.
     */
    private fun captureBySwap(w: Int, h: Int, quality: Int): CaptureFrame? {
        val mp = mediaProjection ?: return null
        val dpi = context.resources.displayMetrics.densityDpi
        releasePreviewDisplay()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = try {
            mp.createVirtualDisplay(
                "BekonHiRes", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, captureHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "swap VD: ${e.message}")
            reader.close()
            bindPreviewDisplay()
            return null
        }
        try {
            return waitEncoded(reader, quality, w, h)
        } finally {
            vd.release()
            reader.close()
            bindPreviewDisplay()
        }
    }

    private fun bindPreviewDisplay() {
        val mp = mediaProjection ?: return
        val dpi = context.resources.displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = try {
            mp.createVirtualDisplay(
                "Bekon", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, captureHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "preview VD: ${e.message}")
            imageReader?.close()
            imageReader = null
            null
        }
    }

    private fun releasePreviewDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun waitEncoded(
        reader: ImageReader,
        quality: Int,
        expectW: Int,
        expectH: Int,
    ): CaptureFrame? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val img = reader.acquireLatestImage()
            if (img != null) {
                val bmp = imageToBitmap(img)
                img.close()
                val skipBlack = System.currentTimeMillis() < skipBlackUntil
                if (skipBlack && bmp.isNearlyBlack(BLACK_CHANNEL)) {
                    Log.i(TAG, "Skipping black frame after wake")
                    bmp.recycle()
                    Thread.sleep(50)
                    continue
                }
                val sized = if (bmp.width == expectW && bmp.height == expectH) bmp else {
                    val s = Bitmap.createScaledBitmap(bmp, expectW, expectH, true)
                    bmp.recycle()
                    s
                }
                val bytes = bitmapToJpeg(sized, quality)
                sized.recycle()
                skipBlackUntil = 0L
                return CaptureFrame(bytes, expectW, expectH, CaptureFrame.JPEG)
            }
            Thread.sleep(50)
        }
        Log.w(TAG, "Capture timeout (jpeg ${expectW}x${expectH} q$quality)")
        return null
    }

    private fun drain(reader: ImageReader?) {
        if (reader == null) return
        while (true) {
            val img = reader.acquireLatestImage() ?: break
            img.close()
        }
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val planes = img.planes
        val buf = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val bitmap = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buf)
        return if (rowPadding == 0) bitmap else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, img.width, img.height)
            bitmap.recycle()
            cropped
        }
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    override fun stop() {
        releasePreviewDisplay()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) { }
        mediaProjection?.stop(); mediaProjection = null
        captureThread.quitSafely()
    }
}
