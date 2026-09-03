package pro.potoki.bekon.capture

import android.content.Context
import kotlin.math.roundToInt

data class CaptureOpts(
    val hiRes: Boolean,
    val scale: Float,
    val quality: Int,
)

/** Phone defaults for JPEG capture. Command `scale` / `quality` override these. */
object CapturePrefs {
    const val PREFS_PREVIEW_SCALE = "capture_preview_scale"
    const val PREFS_PREVIEW_QUALITY = "capture_preview_quality"
    const val PREFS_HIRES_SCALE = "capture_hires_scale"
    const val PREFS_HIRES_QUALITY = "capture_hires_quality"

    const val DEFAULT_PREVIEW_SCALE = 0.5f
    const val DEFAULT_PREVIEW_QUALITY = 45
    const val DEFAULT_HIRES_SCALE = 1.0f
    const val DEFAULT_HIRES_QUALITY = 70

    private const val PREFS = "wlya_prefs"

    fun previewScale(ctx: Context): Float =
        ctx.prefs().getFloat(PREFS_PREVIEW_SCALE, DEFAULT_PREVIEW_SCALE).coerceIn(0.1f, 1f)

    fun previewQuality(ctx: Context): Int =
        ctx.prefs().getInt(PREFS_PREVIEW_QUALITY, DEFAULT_PREVIEW_QUALITY).coerceIn(1, 100)

    fun hiresScale(ctx: Context): Float =
        ctx.prefs().getFloat(PREFS_HIRES_SCALE, DEFAULT_HIRES_SCALE).coerceIn(0.1f, 1f)

    fun hiresQuality(ctx: Context): Int =
        ctx.prefs().getInt(PREFS_HIRES_QUALITY, DEFAULT_HIRES_QUALITY).coerceIn(1, 100)

    fun setPreviewScale(ctx: Context, v: Float) {
        ctx.prefs().edit().putFloat(PREFS_PREVIEW_SCALE, v.coerceIn(0.1f, 1f)).apply()
    }

    fun setPreviewQuality(ctx: Context, v: Int) {
        ctx.prefs().edit().putInt(PREFS_PREVIEW_QUALITY, v.coerceIn(1, 100)).apply()
    }

    fun setHiresScale(ctx: Context, v: Float) {
        ctx.prefs().edit().putFloat(PREFS_HIRES_SCALE, v.coerceIn(0.1f, 1f)).apply()
    }

    fun setHiresQuality(ctx: Context, v: Int) {
        ctx.prefs().edit().putInt(PREFS_HIRES_QUALITY, v.coerceIn(1, 100)).apply()
    }

    fun resolve(
        ctx: Context,
        hiRes: Boolean,
        scale: Float? = null,
        quality: Int? = null,
    ): CaptureOpts {
        val defScale = if (hiRes) hiresScale(ctx) else previewScale(ctx)
        val defQ = if (hiRes) hiresQuality(ctx) else previewQuality(ctx)
        return CaptureOpts(
            hiRes = hiRes,
            scale = (scale ?: defScale).coerceIn(0.1f, 1f),
            quality = (quality ?: defQ).coerceIn(1, 100),
        )
    }

    fun pixelSize(screenW: Int, screenH: Int, scale: Float): Pair<Int, Int> {
        val s = scale.coerceIn(0.1f, 1f)
        return (screenW * s).roundToInt().coerceAtLeast(1) to
            (screenH * s).roundToInt().coerceAtLeast(1)
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
