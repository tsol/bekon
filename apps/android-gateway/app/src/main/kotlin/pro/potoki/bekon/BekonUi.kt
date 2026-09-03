package pro.potoki.bekon

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Programmatic Material 3 helpers (48dp targets, ripple via MaterialButton, 8dp grid).
 */
object BekonUi {
    val bg = Color.parseColor("#F7F5F2")
    val surface = Color.parseColor("#FFFBFF")
    val onSurface = Color.parseColor("#1C1B1F")
    val muted = Color.parseColor("#5C5B60")
    val primary = Color.parseColor("#1B6B4A")
    val onPrimary = Color.WHITE
    val error = Color.parseColor("#B3261E")
    val ok = Color.parseColor("#1B6B4A")
    val warn = Color.parseColor("#C45C00")
    val outline = Color.parseColor("#C8C5C0")
    val surfaceVariant = Color.parseColor("#F3F0EB")

    fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            ctx.resources.displayMetrics,
        ).toInt()

    fun screenHeadline(ctx: Context, title: String, meta: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(screenTitle(ctx, title).apply {
            layoutParams = lpWeight(1f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, dp(ctx, 8), dp(ctx, 8), dp(ctx, 4))
        })
        addView(TextView(ctx).apply {
            text = meta
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            gravity = Gravity.END
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
        })
    }

    fun screenTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(onSurface)
        setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
    }

    /** `versionName` (`versionCode`) from the installed APK. */
    fun installedVersion(ctx: Context): String {
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName ?: "?"} ($code)"
        } catch (_: Exception) {
            "?"
        }
    }

    fun bodyHint(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(muted)
        setPadding(0, 0, 0, dp(ctx, 16))
    }

    fun sectionLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(onSurface)
        setPadding(0, dp(ctx, 16), 0, dp(ctx, 8))
    }

    fun sectionCard(ctx: Context): MaterialCardView = MaterialCardView(ctx).apply {
        radius = dp(ctx, 16).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(surface)
        strokeWidth = dp(ctx, 1)
        strokeColor = outline
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 12) }
        useCompatPadding = false
    }

    data class MetricRow(
        val layout: LinearLayout,
        val value: TextView,
        val extra: TextView,
    )

    /** Label on the left, value (and optional second line) on the right — MD3 list metrics. */
    fun metricRow(ctx: Context, label: String): MetricRow {
        val extra = TextView(ctx).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(muted)
            gravity = Gravity.END
        }
        val value = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(onSurface)
            gravity = Gravity.END
        }
        val valueCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(value)
            addView(extra)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 48)
            addView(TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(muted)
                layoutParams = lpWeight(1f)
            })
            addView(valueCol)
        }
        return MetricRow(layout, value, extra)
    }

    fun dumpWell(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(surfaceVariant)
            cornerRadius = dp(ctx, 12).toFloat()
        }
        setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 8) }
    }

    fun cardColumn(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    fun filledButton(ctx: Context, text: String): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
            isAllCaps = false
            minimumHeight = dp(ctx, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 8) }
        }

    fun outlinedButton(ctx: Context, text: String): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            minimumHeight = dp(ctx, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 8) }
        }

    fun tonalButton(ctx: Context, text: String): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            minimumHeight = dp(ctx, 40)
        }

    fun iconButton(ctx: Context, iconRes: Int, description: String, tint: Int = onSurface): android.widget.ImageButton =
        android.widget.ImageButton(ctx).apply {
            setImageResource(iconRes)
            contentDescription = description
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            val pad = dp(ctx, 12)
            setPadding(pad, pad, pad, pad)
            val out = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
            setBackgroundResource(out.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        }


    data class OutlinedField(
        val layout: TextInputLayout,
        val edit: TextInputEditText,
    )

    fun outlinedField(
        ctx: Context,
        hint: String,
        password: Boolean = false,
    ): OutlinedField {
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val edit = TextInputEditText(layout.context).apply {
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (password) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        layout.addView(edit)
        return OutlinedField(layout, edit)
    }

    fun matchParent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )

    fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun themeColor(view: View, attr: Int, fallback: Int): Int =
        try {
            MaterialColors.getColor(view, attr)
        } catch (_: Exception) {
            fallback
        }

    fun debounceSave(handler: Handler, delayMs: Long = 400L, action: () -> Unit): TextWatcher {
        val run = Runnable { action() }
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, delayMs)
            }
        }
    }

    fun mainLooperHandler(): Handler = Handler(Looper.getMainLooper())

    fun lpWeight(weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
}
