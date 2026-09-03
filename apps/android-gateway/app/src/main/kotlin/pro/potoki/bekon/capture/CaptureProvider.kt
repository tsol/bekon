package pro.potoki.bekon.capture

class CaptureFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mime: String,
) {
    companion object {
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
    }
}

internal fun android.graphics.Bitmap.isNearlyBlack(channelMax: Int = 8): Boolean {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return true
    val stepX = (w / 32).coerceAtLeast(1)
    val stepY = (h / 32).coerceAtLeast(1)
    var lit = 0
    var n = 0
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val c = getPixel(x, y)
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            if (r > channelMax || g > channelMax || b > channelMax) lit++
            n++
            x += stepX
        }
        y += stepY
    }
    return lit * 50 < n
}

/**
 * Unified screen capture interface.
 * Non-root: MediaProjection (needs one-time user permission).
 * Root: direct screencap binary (no permission needed).
 */
interface CaptureProvider {
    val width: Int
    val height: Int

    /** Start capture session. For MediaProjection mode, pass the permission intent data. */
    fun start()

    /** Capture a JPEG frame. [CaptureOpts.hiRes] uses a full-screen virtual display (or screencap). */
    fun capture(opts: CaptureOpts): CaptureFrame?

    /**
     * Display just came back from sleep. Drop stale (often black) buffers and
     * skip near-black frames on the next capture until composition catches up.
     */
    fun onDisplayWoke() {}

    /** Release resources. */
    fun stop()
}
