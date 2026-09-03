package pro.potoki.bekon.ime

import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.TextView

/**
 * Silent keyboard so we can type into Termux (and any focused field) on Android 11+.
 * Enable it in Language & input, then select it as the current IME.
 */
class BekonImeService : InputMethodService() {
    companion object {
        private const val TAG = "BekonIme"

        @Volatile var instance: BekonImeService? = null
            private set

        fun isEnabled(resolver: android.content.ContentResolver): Boolean {
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS)
                ?: return false
            return enabled.split(':').any { it.contains("pro.potoki.bekon") && it.contains("BekonImeService") }
        }

        fun isSelected(resolver: android.content.ContentResolver): Boolean {
            val current = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?: return false
            return current.contains("BekonImeService")
        }

        fun statusDetail(resolver: android.content.ContentResolver): String = when {
            isSelected(resolver) -> "Current keyboard"
            isEnabled(resolver) -> "Enabled — tap to select"
            else -> "Not enabled — tap to open settings"
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        return TextView(this).apply {
            text = "Bekon Keys"
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }
    }

    fun liveConnection(): InputConnection? = currentInputConnection
}
