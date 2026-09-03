package pro.potoki.bekon.touch

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Full-screen calibration overlay drawn via TYPE_ACCESSIBILITY_OVERLAY.
 * Does not consume touches (FLAG_NOT_TOUCHABLE), so the real gesture still reaches the UI.
 */
object GestureOverlay {
    private const val TAG = "GestureOverlay"
    const val PREFS_ENABLED = "gesture_overlay_enabled"

    private val main = Handler(Looper.getMainLooper())
    private var host: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var view: OverlayView? = null
    private var attached = false

    @Volatile var enabled: Boolean = true

    fun attach(svc: AccessibilityService) {
        host = svc
        enabled = svc.getSharedPreferences("wlya_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREFS_ENABLED, true)
        main.post { install(svc) }
    }

    fun detach() {
        main.post {
            val v = view
            val wm = windowManager
            if (v != null && wm != null && attached) {
                try { wm.removeView(v) } catch (_: Exception) {}
            }
            view = null
            windowManager = null
            host = null
            attached = false
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences("wlya_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREFS_ENABLED, value).apply()
        val svc = host
        main.post {
            if (value && svc != null) install(svc) else uninstall()
        }
    }

    fun showTap(x: Float, y: Float, label: String) {
        if (!enabled) return
        main.post {
            ensureInstalled()
            view?.show(OverlayView.Mark.Tap(PointF(x, y), label))
        }
    }

    fun showLongPress(x: Float, y: Float, label: String) {
        if (!enabled) return
        main.post {
            ensureInstalled()
            view?.show(OverlayView.Mark.LongPress(PointF(x, y), label))
        }
    }

    fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float, label: String) {
        if (!enabled) return
        main.post {
            ensureInstalled()
            view?.show(OverlayView.Mark.Swipe(PointF(x1, y1), PointF(x2, y2), label))
        }
    }

    private fun ensureInstalled() {
        val svc = host ?: return
        if (!attached) install(svc)
    }

    private fun install(svc: AccessibilityService) {
        if (attached || !enabled) return
        try {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val overlay = OverlayView(svc)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "BekonGestureOverlay"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                    setFitInsetsSides(0)
                }
            }
            overlay.fitsSystemWindows = false
            wm.addView(overlay, params)
            windowManager = wm
            view = overlay
            attached = true
            Log.i(TAG, "Overlay attached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay: ${e.message}")
        }
    }

    private fun uninstall() {
        val v = view
        val wm = windowManager
        if (v != null && wm != null && attached) {
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        view = null
        attached = false
    }

    private class OverlayView(context: Context) : View(context) {
        sealed class Mark {
            abstract val label: String
            data class Tap(val p: PointF, override val label: String) : Mark()
            data class LongPress(val p: PointF, override val label: String) : Mark()
            data class Swipe(val a: PointF, val b: PointF, override val label: String) : Mark()
        }

        private val marks = ArrayDeque<Mark>()
        private val hideRunnable = Runnable {
            marks.clear()
            invalidate()
        }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(140, 255, 64, 129)
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = Color.argb(220, 255, 213, 79)
            strokeCap = Paint.Cap.ROUND
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        private val banner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
        }

        fun show(mark: Mark) {
            marks.addLast(mark)
            while (marks.size > 6) marks.removeFirst()
            removeCallbacks(hideRunnable)
            postDelayed(hideRunnable, 3500)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (marks.isEmpty()) return

            // Gesture coords are display pixels; ColorOS insets this overlay below the
            // status bar / cutout, so (0,0) in the view is not the top of the screen.
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val ox = loc[0].toFloat()
            val oy = loc[1].toFloat()

            canvas.drawRect(0f, 0f, width.toFloat(), 72f, banner)
            canvas.drawText("Bekon taps (calibration)", 24f, 48f, text)

            for (m in marks) {
                when (m) {
                    is Mark.Tap -> {
                        val x = m.p.x - ox
                        val y = m.p.y - oy
                        canvas.drawCircle(x, y, 48f, fill)
                        canvas.drawCircle(x, y, 48f, stroke)
                        canvas.drawLine(x - 70f, y, x + 70f, y, stroke)
                        canvas.drawLine(x, y - 70f, x, y + 70f, stroke)
                        drawLabel(canvas, x, y + 90f, m.label)
                    }
                    is Mark.LongPress -> {
                        val x = m.p.x - ox
                        val y = m.p.y - oy
                        canvas.drawCircle(x, y, 64f, fill)
                        canvas.drawCircle(x, y, 64f, stroke)
                        canvas.drawCircle(x, y, 88f, stroke)
                        drawLabel(canvas, x, y + 110f, m.label)
                    }
                    is Mark.Swipe -> {
                        val x1 = m.a.x - ox
                        val y1 = m.a.y - oy
                        val x2 = m.b.x - ox
                        val y2 = m.b.y - oy
                        canvas.drawLine(x1, y1, x2, y2, line)
                        canvas.drawCircle(x1, y1, 28f, fill)
                        canvas.drawCircle(x2, y2, 40f, fill)
                        canvas.drawCircle(x2, y2, 40f, stroke)
                        drawLabel(canvas, x2, y2 + 90f, m.label)
                    }
                }
            }
        }

        private fun drawLabel(canvas: Canvas, x: Float, y: Float, label: String) {
            val w = text.measureText(label)
            val left = (x - w / 2f).coerceIn(8f, (width - w - 8f).coerceAtLeast(8f))
            canvas.drawText(label, left, y.coerceIn(100f, height - 24f), text)
        }
    }
}
