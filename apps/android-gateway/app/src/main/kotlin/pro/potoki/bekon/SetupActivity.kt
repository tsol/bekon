package pro.potoki.bekon

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import pro.potoki.bekon.ime.BekonImeService
import pro.potoki.bekon.voice.AlsaCaptureScan
import pro.potoki.bekon.voice.LineRoute
import pro.potoki.bekon.voice.LineRouteMap
import pro.potoki.bekon.voice.GsmLevelProbe
import pro.potoki.bekon.voice.VoiceMeters
import pro.potoki.bekon.voice.VoicePrefs
import pro.potoki.bekon.voice.VoiceService
import pro.potoki.bekon.voice.VoiceLineState
import pro.potoki.bekon.capture.CapturePrefs
import pro.potoki.bekon.intent.ApkSelfUpdate
import pro.potoki.bekon.touch.GestureOverlay
import pro.potoki.bekon.touch.TouchService
import pro.potoki.bekon.wlya.WlyaTunnelManager
import com.wlya.adapters.ui.GeneratedAndroidFormRegistry
import com.wlya.core.AdapterInstanceConfig
import com.wlya.core.AdapterListItem
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Tabbed setup: Status | Tunnel | Settings | Voice | Log
 *
 * Start All runs a sequential flow:
 *   Service → Capture permission → Touch accessibility → WLYA tunnel
 * Each step shows a spinner / clear missing state so the UI doesn't feel stuck.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
        private const val REQ_CAPTURE = 3001
        private const val REQ_TOUCH = 3002
        private const val REQ_MIC = 3003
        private const val REQ_VOICE = 3004
        private const val STATUS_POLL_MS = 1000L
        private const val DUTY_SNAPSHOT_MS = 2000L
        private const val VOICE_METER_MS = 200L
        private const val SERVICE_WAIT_MS = 250L
        private const val SERVICE_WAIT_MAX = 20

        const val PREFS_NAME = "wlya_prefs"
        const val PREFS_CHANNEL = "wlya_channel"
        const val PREFS_SECRET = "wlya_secret"
        /** Legacy dual-purpose seed; used if [PREFS_CHANNEL] is empty. */
        const val PREFS_SEED = "wlya_seed"
        const val PREFS_ADAPTER_TYPE = "wlya_adapter_type"
        const val PREFS_ADAPTER_CONFIG = "wlya_adapter_config_json"
        const val PREFS_ADAPTERS = "wlya_adapters_json"
        const val PREFS_ACCEPT_ADVERTISED = "wlya_accept_advertised"
        /** Legacy keys — migrated into adapter config on first load. */
        const val PREFS_EMAIL = "wlya_email"
        const val PREFS_PASSWORD = "wlya_password"

        const val DEFAULT_CHANNEL = ""
        const val DEFAULT_SEED = ""
        const val DEFAULT_ADAPTER_TYPE = "wlyaserver"
        const val DEFAULT_WLYA_SERVER_URL = "https://relay.example"
        const val DEFAULT_EMAIL = ""
        const val DEFAULT_PASSWORD = ""

        private val configJson = Json { ignoreUnknownKeys = true }

        private val COLOR_OK = BekonUi.ok
        private val COLOR_MISSING = BekonUi.error
        private val COLOR_PENDING = BekonUi.warn
        private val COLOR_IDLE = BekonUi.muted
        private val COLOR_HINT = BekonUi.muted
        private val COLOR_TITLE = BekonUi.onSurface
    }

    private enum class StepState { IDLE, PENDING, OK, MISSING, ERROR }

    private data class StatusRow(
        val key: String,
        val title: String,
        val row: LinearLayout,
        val spinner: ProgressBar,
        val icon: TextView,
        val detail: TextView,
        var state: StepState = StepState.IDLE,
        var detailText: String = "—",
    )

    private val handler = Handler(Looper.getMainLooper())
    private val rows = linkedMapOf<String, StatusRow>()

    private lateinit var tabContent: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logMessageList: LinearLayout
    private lateinit var logAdapterList: LinearLayout
    private lateinit var logAdapterSpinner: Spinner
    private lateinit var hidePollCheck: SwitchMaterial
    private val uiLogNotes = mutableListOf<String>()
    private var logAdapterFilter = ""
    private lateinit var statusLayout: LinearLayout
    private lateinit var tunnelLayout: LinearLayout
    private lateinit var logLayout: LinearLayout
    private lateinit var voiceScroll: ScrollView
    private lateinit var voiceUrlInput: TextInputEditText
    private lateinit var voiceRoomInput: TextInputEditText
    private lateinit var voiceSeedInput: TextInputEditText
    private lateinit var voiceStatus: TextView
    private lateinit var voiceModeHint: TextView
    private lateinit var voiceCallValue: TextView
    private lateinit var voiceCallExtra: TextView
    private lateinit var voiceSocketValue: TextView
    private lateinit var voiceLineValue: TextView
    private lateinit var voiceConnectBtn: MaterialButton
    private lateinit var voicePhoneBtn: MaterialButton
    private lateinit var voiceWalkieBtn: MaterialButton
    private lateinit var voiceModeToggle: MaterialButtonToggleGroup
    private var voicePhoneToggleId = 0
    private var voiceWalkieToggleId = 0
    private var voiceApplyingModeToggle = false
    private lateinit var voiceDebugBox: LinearLayout
    private lateinit var voiceDebugChevron: TextView
    private var voiceDebugExpanded = false
    private lateinit var voiceMetersBox: LinearLayout
    private val voiceMeterBars = linkedMapOf<String, LinearProgressIndicator>()
    private lateinit var voiceMeterHint: TextView
    private lateinit var voiceAlsaDump: TextView
    private lateinit var voiceRouteDump: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton
    private lateinit var progressHint: TextView
    private lateinit var tabLayout: TabLayout

    private lateinit var channelInput: TextInputEditText
    private lateinit var secretInput: TextInputEditText
    private lateinit var acceptAdvertisedCheck: SwitchMaterial
    private lateinit var tunnelScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private lateinit var adaptersListContainer: LinearLayout
    private lateinit var adapterEditor: LinearLayout
    private lateinit var adapterTypeSpinner: Spinner
    private lateinit var adapterFormContainer: LinearLayout
    private var currentAdapterFormView: View? = null
    private val adapterInstances = mutableListOf<AdapterInstanceConfig>()
    /** null = adding a new instance; otherwise editing this id. */
    private var editingAdapterId: String? = null
    private var editorOpen = false
    private var adapterDialog: AlertDialog? = null
    private var closingEditor = false

    private var setupRunning = false
    private var waitingForCapture = false
    /** True after we launch Accessibility settings; processed only after a real pause/resume. */
    private var waitingForTouch = false
    private var pausedWhileWaitingTouch = false
    private var activeTab = "status"
    private val adapterDutySubtitles = mutableMapOf<String, TextView>()

    private val statusPoller = object : Runnable {
        override fun run() {
            bindAdvertisedListener()
            if (!setupRunning) refreshLiveStatus()
            if (activeTab == "log") refreshLogUi()
            if (activeTab == "voice") refreshVoiceStatus()
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private val voiceMeterPoller = object : Runnable {
        override fun run() {
            if (activeTab == "voice" && voiceDebugExpanded) {
                refreshVoiceMeters()
                handler.postDelayed(this, VOICE_METER_MS)
            }
        }
    }

    private val dutySnapshotPoller = object : Runnable {
        override fun run() {
            if (activeTab != "tunnel") return
            applyAdapterDutySnapshot()
            handler.postDelayed(this, DUTY_SNAPSHOT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        showTab("status")
        refreshLiveStatus()
        LineRoute.load(VoicePrefs(this))
        maybeAutoStartVoice()
        maybeResumeTunnel()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(statusPoller)
        handler.post(statusPoller)
        startDutySnapshotIfVisible()
        startVoiceMeterPoll()
        bindAdvertisedListener()
        reloadAdaptersFromPrefs(showToast = false)

        // Only treat as "returned from Accessibility" after we actually left the activity.
        // Avoids a false trigger when Accessibility is launched from onActivityResult
        // (Android calls onResume before the settings screen appears).
        if (waitingForTouch && pausedWhileWaitingTouch) {
            waitingForTouch = false
            pausedWhileWaitingTouch = false
            onTouchSettingsReturned()
        } else if (!setupRunning) {
            refreshLiveStatus()
        }
    }

    override fun onPause() {
        if (waitingForTouch) pausedWhileWaitingTouch = true
        if (::channelInput.isInitialized) saveSettings()
        if (::voiceUrlInput.isInitialized) saveVoicePrefs()
        handler.removeCallbacks(statusPoller)
        handler.removeCallbacks(dutySnapshotPoller)
        handler.removeCallbacks(voiceMeterPoller)
        AgentForegroundService.instance?.wlyaManager?.onAdvertisedAdaptersApplied = null
        super.onPause()
    }

    override fun onDestroy() {
        GsmLevelProbe.stop()
        AlsaCaptureScan.stop()
        super.onDestroy()
    }

    // ── UI ────────────────────────────────────────────────────────

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BekonUi.bg)
        }

        tabLayout = TabLayout(this).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            tabGravity = TabLayout.GRAVITY_FILL
            setBackgroundColor(BekonUi.surface)
        }
        listOf("Status", "Tunnel", "Settings", "Voice", "Log").forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val id = when (tab.position) {
                    1 -> "tunnel"
                    2 -> "settings"
                    3 -> "voice"
                    4 -> "log"
                    else -> "status"
                }
                showTab(id, fromTabs = true)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        root.addView(tabLayout)

        tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(BekonUi.dp(this@SetupActivity, 16), BekonUi.dp(this@SetupActivity, 8), BekonUi.dp(this@SetupActivity, 16), BekonUi.dp(this@SetupActivity, 16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(tabContent)

        statusLayout = buildStatusTab()
        tunnelLayout = buildTunnelTab()
        tunnelScroll = ScrollView(this).apply {
            addView(tunnelLayout)
            layoutParams = BekonUi.matchParent()
        }
        settingsScroll = ScrollView(this).apply {
            addView(buildPhoneSettingsTab())
            layoutParams = BekonUi.matchParent()
        }
        logLayout = buildLogTab()
        voiceScroll = ScrollView(this).apply {
            addView(buildVoiceTab())
            layoutParams = BekonUi.matchParent()
        }
        return root
    }

    private fun showTab(tag: String, fromTabs: Boolean = false) {
        activeTab = tag
        tabContent.removeAllViews()
        when (tag) {
            "status" -> tabContent.addView(statusLayout)
            "tunnel" -> tabContent.addView(tunnelScroll)
            "settings" -> tabContent.addView(settingsScroll)
            "voice" -> {
                tabContent.addView(voiceScroll)
                refreshVoiceStatus()
                startVoiceMeterPoll()
            }
            "log" -> {
                tabContent.addView(logLayout)
                refreshLogUi()
            }
        }
        if (!fromTabs && ::tabLayout.isInitialized) {
            val pos = when (tag) {
                "tunnel" -> 1
                "settings" -> 2
                "voice" -> 3
                "log" -> 4
                else -> 0
            }
            if (tabLayout.selectedTabPosition != pos) {
                tabLayout.getTabAt(pos)?.select()
            }
        }
        startDutySnapshotIfVisible()
        startVoiceMeterPoll()
    }

    private fun startVoiceMeterPoll() {
        handler.removeCallbacks(voiceMeterPoller)
        if (activeTab == "voice" && voiceDebugExpanded) handler.post(voiceMeterPoller)
    }

    private fun startDutySnapshotIfVisible() {
        handler.removeCallbacks(dutySnapshotPoller)
        if (activeTab == "tunnel") {
            applyAdapterDutySnapshot()
            handler.postDelayed(dutySnapshotPoller, DUTY_SNAPSHOT_MS)
        }
    }

    private fun buildStatusTab(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = BekonUi.matchParent()
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(BekonUi.screenHeadline(this, "Bekon Gateway", BekonUi.installedVersion(this)))
        progressHint = BekonUi.bodyHint(this, "Press Start All to launch service, permissions and tunnel.")
        inner.addView(progressHint)

        val serviceCard = BekonUi.sectionCard(this)
        val serviceCol = BekonUi.cardColumn(this)
        serviceCol.addView(sectionCaption("Services"))
        serviceCol.addView(makeStatusRow("Service", "Foreground service"))
        serviceCol.addView(makeStatusRow("WLYA", "Tunnel entity", title = "WLYA Tunnel"))
        serviceCard.addView(serviceCol)
        inner.addView(serviceCard)

        val androidCard = BekonUi.sectionCard(this)
        val androidCol = BekonUi.cardColumn(this)
        androidCol.addView(sectionCaption("Android"))
        listOf(
            "Touch" to "Accessibility touch control",
            "Keyboard" to "Bekon Keys IME",
            "Capture" to "Screen capture",
        ).forEach { (key, subtitle) ->
            androidCol.addView(makeStatusRow(key, subtitle))
        }
        androidCard.addView(androidCol)
        inner.addView(androidCard)
        rows["Keyboard"]?.row?.setOnClickListener { openKeyboardSettings() }
        rows["Touch"]?.row?.setOnClickListener { openTouchSettings() }

        scroll.addView(inner)
        layout.addView(scroll)

        startBtn = BekonUi.filledButton(this, "Start All").apply {
            minimumHeight = BekonUi.dp(this@SetupActivity, 40)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { runSetup() }
        }
        stopBtn = BekonUi.outlinedButton(this, "Stop All").apply {
            minimumHeight = BekonUi.dp(this@SetupActivity, 40)
            textSize = 14f
            setTextColor(BekonUi.error)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = BekonUi.dp(this@SetupActivity, 8)
            }
            setOnClickListener { stopEverything() }
        }
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, BekonUi.dp(this@SetupActivity, 4), 0, 0)
            addView(startBtn)
            addView(stopBtn)
        })

        return layout
    }

    private fun sectionCaption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_HINT)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, BekonUi.dp(this@SetupActivity, 4))
    }

    private fun makeStatusRow(key: String, defaultDetail: String, title: String = key): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = BekonUi.dp(this@SetupActivity, 56)
            setPadding(0, BekonUi.dp(this@SetupActivity, 8), 0, BekonUi.dp(this@SetupActivity, 8))
            isClickable = true
            isFocusable = true
            layoutParams = BekonUi.wrap()
        }

        val spinner = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(BekonUi.dp(this@SetupActivity, 28), BekonUi.dp(this@SetupActivity, 28)).apply {
                marginEnd = BekonUi.dp(this@SetupActivity, 12)
            }
        }

        val icon = TextView(this).apply {
            text = "○"
            textSize = 18f
            setTextColor(COLOR_IDLE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(BekonUi.dp(this@SetupActivity, 28), BekonUi.dp(this@SetupActivity, 28)).apply {
                marginEnd = BekonUi.dp(this@SetupActivity, 12)
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(COLOR_TITLE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val detail = TextView(this).apply {
            text = defaultDetail
            textSize = 13f
            setTextColor(COLOR_HINT)
        }
        textCol.addView(detail)

        row.addView(spinner)
        row.addView(icon)
        row.addView(textCol)

        rows[key] = StatusRow(
            key = key,
            title = key,
            row = row,
            spinner = spinner,
            icon = icon,
            detail = detail,
            detailText = defaultDetail,
        )
        return row
    }

    private fun selectableAdapterTypes(): List<String> =
        GeneratedAndroidFormRegistry.listTypes().filter { it != "mock" }

    private fun buildPhoneSettingsTab(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(BekonUi.screenTitle(this, "Settings"))
        layout.addView(BekonUi.bodyHint(this, "Phone control: overlay, updates, wake, and screenshot JPEG."))

        val card = BekonUi.sectionCard(this)
        val col = BekonUi.cardColumn(this)
        col.addView(sectionCaption("Phone Control"))

        val overlayRow = BekonUi.row(this).apply {
            minimumHeight = BekonUi.dp(this@SetupActivity, 56)
        }
        overlayRow.addView(TextView(this).apply {
            text = "Show tap/swipe overlay"
            textSize = 16f
            setTextColor(BekonUi.onSurface)
            layoutParams = BekonUi.lpWeight(1f)
        })
        overlayRow.addView(SwitchMaterial(this).apply {
            isChecked = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(GestureOverlay.PREFS_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                GestureOverlay.setEnabled(this@SetupActivity, checked)
            }
        })
        col.addView(overlayRow)

        val autoUpdateRow = BekonUi.row(this).apply {
            minimumHeight = BekonUi.dp(this@SetupActivity, 56)
        }
        autoUpdateRow.addView(TextView(this).apply {
            text = "Auto-update APK via tunnel"
            textSize = 16f
            setTextColor(BekonUi.onSurface)
            layoutParams = BekonUi.lpWeight(1f)
        })
        autoUpdateRow.addView(SwitchMaterial(this).apply {
            isChecked = ApkSelfUpdate.enabled(this@SetupActivity)
            setOnCheckedChangeListener { _, checked ->
                ApkSelfUpdate.setEnabled(this@SetupActivity, checked)
            }
        })
        col.addView(autoUpdateRow)

        val shareLogsRow = BekonUi.row(this).apply {
            minimumHeight = BekonUi.dp(this@SetupActivity, 56)
        }
        shareLogsRow.addView(TextView(this).apply {
            text = "Share logs on request"
            textSize = 16f
            setTextColor(BekonUi.onSurface)
            layoutParams = BekonUi.lpWeight(1f)
        })
        shareLogsRow.addView(SwitchMaterial(this).apply {
            isChecked = ApkSelfUpdate.logsEnabled(this@SetupActivity)
            setOnCheckedChangeListener { _, checked ->
                ApkSelfUpdate.setLogsEnabled(this@SetupActivity, checked)
            }
        })
        col.addView(shareLogsRow)

        val wakeField = BekonUi.outlinedField(this, "Wake if idle ≥ ms (0 = off)")
        wakeField.edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        wakeField.edit.setText(ScreenWakeHelper.wakeAfterSleepMs(this).toString())
        wakeField.edit.addTextChangedListener(BekonUi.debounceSave(handler) {
            val ms = wakeField.edit.text?.toString()?.trim()?.toIntOrNull() ?: ScreenWakeHelper.DEFAULT_WAKE_AFTER_SLEEP_MS
            ScreenWakeHelper.setWakeAfterSleepMs(this, ms)
        })
        col.addView(wakeField.layout)
        col.addView(TextView(this).apply {
            text = "Screenshots (JPEG). Command scale/quality override these."
            textSize = 13f
            setTextColor(COLOR_HINT)
            setPadding(0, BekonUi.dp(this@SetupActivity, 12), 0, BekonUi.dp(this@SetupActivity, 4))
        })
        val previewScaleField = BekonUi.outlinedField(this, "Preview scale (0.1–1, default 0.5)")
        previewScaleField.edit.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        previewScaleField.edit.setText(CapturePrefs.previewScale(this).toString())
        previewScaleField.edit.addTextChangedListener(BekonUi.debounceSave(handler) {
            val v = previewScaleField.edit.text?.toString()?.trim()?.toFloatOrNull()
                ?: CapturePrefs.DEFAULT_PREVIEW_SCALE
            CapturePrefs.setPreviewScale(this, v)
        })
        col.addView(previewScaleField.layout)
        val previewQField = BekonUi.outlinedField(this, "Preview JPEG quality (1–100, default 45)")
        previewQField.edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        previewQField.edit.setText(CapturePrefs.previewQuality(this).toString())
        previewQField.edit.addTextChangedListener(BekonUi.debounceSave(handler) {
            val v = previewQField.edit.text?.toString()?.trim()?.toIntOrNull()
                ?: CapturePrefs.DEFAULT_PREVIEW_QUALITY
            CapturePrefs.setPreviewQuality(this, v)
        })
        col.addView(previewQField.layout)
        val hiresScaleField = BekonUi.outlinedField(this, "Hi-res scale (0.1–1, default 1)")
        hiresScaleField.edit.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        hiresScaleField.edit.setText(CapturePrefs.hiresScale(this).toString())
        hiresScaleField.edit.addTextChangedListener(BekonUi.debounceSave(handler) {
            val v = hiresScaleField.edit.text?.toString()?.trim()?.toFloatOrNull()
                ?: CapturePrefs.DEFAULT_HIRES_SCALE
            CapturePrefs.setHiresScale(this, v)
        })
        col.addView(hiresScaleField.layout)
        val hiresQField = BekonUi.outlinedField(this, "Hi-res JPEG quality (1–100, default 70)")
        hiresQField.edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        hiresQField.edit.setText(CapturePrefs.hiresQuality(this).toString())
        hiresQField.edit.addTextChangedListener(BekonUi.debounceSave(handler) {
            val v = hiresQField.edit.text?.toString()?.trim()?.toIntOrNull()
                ?: CapturePrefs.DEFAULT_HIRES_QUALITY
            CapturePrefs.setHiresQuality(this, v)
        })
        col.addView(hiresQField.layout)
        card.addView(col)
        layout.addView(card)
        return layout
    }

    private fun buildTunnelTab(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        layout.addView(BekonUi.screenTitle(this, "Tunnel"))
        layout.addView(BekonUi.bodyHint(this, "Channel and secret for the tunnel. Adapters are used when you tap Start All."))

        val creds = BekonUi.sectionCard(this)
        val credsCol = BekonUi.cardColumn(this)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val channelField = BekonUi.outlinedField(this, "Channel")
        channelInput = channelField.edit
        channelInput.setText(
            prefs.getString(PREFS_CHANNEL, null)?.takeIf { it.isNotBlank() }
                ?: prefs.getString(PREFS_SEED, DEFAULT_CHANNEL)
        )
        channelInput.addTextChangedListener(BekonUi.debounceSave(handler) { saveSettings() })
        credsCol.addView(channelField.layout)

        val secretField = BekonUi.outlinedField(this, "Secret (empty = channel)", password = true)
        secretInput = secretField.edit
        secretInput.setText(prefs.getString(PREFS_SECRET, "") ?: "")
        secretInput.addTextChangedListener(BekonUi.debounceSave(handler) { saveSettings() })
        credsCol.addView(secretField.layout)

        val advertisedRow = BekonUi.row(this).apply {
            minimumHeight = BekonUi.dp(this@SetupActivity, 48)
        }
        advertisedRow.addView(TextView(this).apply {
            text = "Accept advertised adapters"
            textSize = 15f
            setTextColor(BekonUi.onSurface)
            layoutParams = BekonUi.lpWeight(1f)
        })
        acceptAdvertisedCheck = SwitchMaterial(this).apply {
            isChecked = prefs.getBoolean(PREFS_ACCEPT_ADVERTISED, true)
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        advertisedRow.addView(acceptAdvertisedCheck)
        credsCol.addView(advertisedRow)
        creds.addView(credsCol)
        layout.addView(creds)

        layout.addView(BekonUi.sectionLabel(this, "Adapters"))
        adaptersListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(adaptersListContainer)
        adapterInstances.clear()
        adapterInstances.addAll(loadAdapterInstances())
        refreshAdaptersList()

        layout.addView(BekonUi.outlinedButton(this, "Add adapter").apply {
            setOnClickListener { openAdapterEditor(newInstance = true) }
        })

        return layout
    }

    private fun bindAdvertisedListener() {
        val mgr = AgentForegroundService.instance?.wlyaManager ?: return
        mgr.onAdvertisedAdaptersApplied = { applied ->
            reloadAdaptersFromPrefs(showToast = true, applied = applied)
        }
    }

    private fun reloadAdaptersFromPrefs(
        showToast: Boolean,
        applied: List<AdapterInstanceConfig>? = null,
    ) {
        if (!::adaptersListContainer.isInitialized) return
        val next = applied?.filter { it.type != "mock" } ?: loadAdapterInstances()
        val same = next.size == adapterInstances.size &&
            next.indices.all { i ->
                val a = next[i]
                val b = adapterInstances[i]
                a.id == b.id && a.type == b.type && a.label == b.label && a.config == b.config && a.enabled == b.enabled
            }
        if (same && !showToast) return
        if (editorOpen) closeAdapterEditor()
        adapterInstances.clear()
        adapterInstances.addAll(next)
        refreshAdaptersList()
        if (!setupRunning) refreshLiveStatus()
        if (showToast) {
            val names = next.joinToString(", ") { it.label.ifBlank { it.type } }.ifBlank { "none" }
            Toast.makeText(this, "Advertised adapters applied: $names", Toast.LENGTH_SHORT).show()
            appendLog("Advertised adapters applied: $names")
        }
    }

    private fun refreshAdaptersList() {
        if (!::adaptersListContainer.isInitialized) return
        adaptersListContainer.removeAllViews()
        adapterDutySubtitles.clear()
        val labels = GeneratedAndroidFormRegistry.labels()
        if (adapterInstances.isEmpty()) {
            adaptersListContainer.addView(TextView(this).apply {
                text = "No adapters yet. Add email or WLYA Server."
                textSize = 14f
                setTextColor(COLOR_HINT)
                setPadding(0, BekonUi.dp(this@SetupActivity, 8), 0, BekonUi.dp(this@SetupActivity, 8))
            })
            return
        }
        val dutyById = currentDutyItems().associateBy { it.id }
        adapterInstances.forEach { instance ->
            val card = BekonUi.sectionCard(this)
            val row = BekonUi.row(this).apply {
                minimumHeight = BekonUi.dp(this@SetupActivity, 56)
                setPadding(
                    BekonUi.dp(this@SetupActivity, 16),
                    BekonUi.dp(this@SetupActivity, 8),
                    BekonUi.dp(this@SetupActivity, 4),
                    BekonUi.dp(this@SetupActivity, 8),
                )
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = instance.label.ifBlank { labels[instance.type] ?: instance.type }
                textSize = 16f
                setTextColor(COLOR_TITLE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            textCol.addView(TextView(this).apply {
                text = adapterDutySubtitle(instance, dutyById[instance.id])
                textSize = 13f
                setTextColor(COLOR_HINT)
                adapterDutySubtitles[instance.id] = this
            })
            row.addView(textCol)
            val toggle = BekonUi.iconButton(
                this,
                if (instance.enabled) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (instance.enabled) "Stop" else "Start",
            ).apply {
                setOnClickListener { toggleAdapterEnabled(instance, this) }
            }
            row.addView(toggle)
            row.addView(BekonUi.iconButton(this, android.R.drawable.ic_menu_edit, "Edit").apply {
                setOnClickListener { openAdapterEditor(newInstance = false, instanceId = instance.id) }
            })
            row.addView(BekonUi.iconButton(this, android.R.drawable.ic_menu_delete, "Delete", BekonUi.error).apply {
                setOnClickListener {
                    adapterInstances.removeAll { it.id == instance.id }
                    if (editingAdapterId == instance.id) closeAdapterEditor()
                    refreshAdaptersList()
                    saveSettings()
                    val mgr = AgentForegroundService.instance?.wlyaManager
                    if (mgr != null && mgr.isRunning()) {
                        mgr.removeLiveAdapter(instance.id)
                    }
                }
            })
            card.addView(row)
            adaptersListContainer.addView(card)
        }
    }

    private fun currentDutyItems(): List<AdapterListItem> {
        return try {
            AgentForegroundService.instance?.wlyaManager?.adapterListItems() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun applyAdapterDutySnapshot() {
        if (!::adaptersListContainer.isInitialized) return
        val items = currentDutyItems()
        val byId = items.associateBy { it.id }
        val idsChanged = adapterDutySubtitles.keys != adapterInstances.map { it.id }.toSet()
        if (idsChanged) {
            refreshAdaptersList()
            return
        }
        adapterInstances.forEach { instance ->
            adapterDutySubtitles[instance.id]?.text = adapterDutySubtitle(instance, byId[instance.id])
        }
    }

    private fun adapterDutySubtitle(
        instance: AdapterInstanceConfig,
        live: AdapterListItem?,
    ): String {
        val role = live?.role ?: instance.config["role"] ?: if (instance.type == "wlyaserver") "primary" else "backup"
        val duty = when {
            live != null -> live.duty
            instance.enabled -> "stopped"
            else -> "stopped"
        }
        val bits = mutableListOf(instance.type, role, duty)
        if (live?.running == true) {
            live.nextPollAtMs?.let { bits.add("poll ${formatRemain(it)}") }
            live.idleUntilMs?.let { bits.add("idle ${formatRemain(it)}") }
        }
        return bits.joinToString(" · ")
    }

    private fun formatRemain(atMs: Long): String {
        val ms = atMs - System.currentTimeMillis()
        if (ms <= 0) return "now"
        val s = (ms / 1000.0).toInt()
        if (s < 60) return "${s}s"
        val m = (s / 60.0).roundToInt()
        if (m < 60) return "${m}m"
        val h = m / 60
        val rm = m % 60
        return if (rm == 0) "${h}h" else "${h}h${rm}m"
    }

    private fun toggleAdapterEnabled(instance: AdapterInstanceConfig, button: View? = null) {
        val enable = !instance.enabled
        button?.isEnabled = false
        val mgr = AgentForegroundService.instance?.wlyaManager
        val applyLocal = {
            val idx = adapterInstances.indexOfFirst { it.id == instance.id }
            if (idx >= 0) adapterInstances[idx] = instance.copy(enabled = enable)
            refreshAdaptersList()
            saveSettings()
        }
        if (mgr != null && mgr.isRunning()) {
            val onDone = {
                adapterInstances.clear()
                adapterInstances.addAll(loadAdapterInstances())
                refreshAdaptersList()
            }
            val onError = { err: String ->
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                refreshAdaptersList()
            }
            if (enable) mgr.upsertAdapter(instance.copy(enabled = true), onDone, onError)
            else mgr.stopAdapter(instance.id, onDone, onError)
        } else {
            applyLocal()
        }
    }

    private fun buildAdapterEditor() {
        adapterEditor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                BekonUi.dp(this@SetupActivity, 8),
                BekonUi.dp(this@SetupActivity, 8),
                BekonUi.dp(this@SetupActivity, 8),
                BekonUi.dp(this@SetupActivity, 8),
            )
        }
        val types = selectableAdapterTypes()
        val labels = GeneratedAndroidFormRegistry.labels()

        adapterTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SetupActivity,
                android.R.layout.simple_spinner_dropdown_item,
                types.map { labels[it] ?: it },
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (!editorOpen) return
                    val type = types.getOrElse(position) { return }
                    val existing = editingAdapterId?.let { eid -> adapterInstances.find { it.id == eid } }
                    val initial = if (existing != null && existing.type == type) existing.config else emptyMap()
                    showAdapterForm(type, initial)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        adapterEditor.addView(adapterTypeSpinner)

        adapterFormContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        adapterEditor.addView(adapterFormContainer)
    }

    private fun openAdapterEditor(newInstance: Boolean, instanceId: String? = null) {
        val types = selectableAdapterTypes()
        if (types.isEmpty()) {
            Toast.makeText(this, "No adapter types available", Toast.LENGTH_SHORT).show()
            return
        }
        closeAdapterEditor()
        editorOpen = true
        buildAdapterEditor()
        if (newInstance) {
            editingAdapterId = null
            adapterTypeSpinner.isEnabled = true
            val idx = types.indexOf("wlyaserver").takeIf { it >= 0 }
                ?: types.indexOf("email").coerceAtLeast(0)
            adapterTypeSpinner.setSelection(idx)
            showAdapterForm(types[idx], emptyMap())
        } else {
            val instance = adapterInstances.find { it.id == instanceId } ?: return
            editingAdapterId = instance.id
            adapterTypeSpinner.isEnabled = false
            val idx = types.indexOf(instance.type).coerceAtLeast(0)
            adapterTypeSpinner.setSelection(idx)
            showAdapterForm(instance.type, instance.config)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(adapterEditor)
        }
        adapterDialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (newInstance) "Add adapter" else "Edit adapter")
            .setView(scroll)
            .setNegativeButton("Cancel") { _, _ -> closeAdapterEditor() }
            .setPositiveButton("Save") { _, _ -> commitAdapterEditor() }
            .setOnDismissListener { closeAdapterEditor() }
            .create()
            .also { it.show() }
    }

    private fun closeAdapterEditor() {
        if (closingEditor) return
        closingEditor = true
        editorOpen = false
        editingAdapterId = null
        currentAdapterFormView = null
        adapterDialog?.setOnDismissListener(null)
        adapterDialog?.dismiss()
        adapterDialog = null
        closingEditor = false
    }

    private fun commitAdapterEditor() {
        val type = selectedAdapterType()
        val form = GeneratedAndroidFormRegistry.get(type) ?: return
        val view = currentAdapterFormView ?: return
        val config = form.readConfig(view).toMutableMap()
        val labels = GeneratedAndroidFormRegistry.labels()
        val label = config["label"]?.takeIf { it.isNotBlank() }
            ?: config["login"]?.takeIf { it.isNotBlank() }
            ?: config["serverUrl"]?.takeIf { it.isNotBlank() }
            ?: (labels[type] ?: type)
        val id = editingAdapterId ?: newAdapterId(type)
        val enabled = adapterInstances.find { it.id == id }?.enabled ?: true
        val instance = AdapterInstanceConfig(
            type = type, id = id, label = label, config = config, enabled = enabled,
        )
        val idx = adapterInstances.indexOfFirst { it.id == id }
        if (idx >= 0) adapterInstances[idx] = instance else adapterInstances.add(instance)
        closeAdapterEditor()
        refreshAdaptersList()
        saveSettings()
        val mgr = AgentForegroundService.instance?.wlyaManager
        if (mgr != null && mgr.isRunning()) {
            mgr.upsertAdapter(instance)
        }
        Toast.makeText(this, "Adapter saved", Toast.LENGTH_SHORT).show()
    }

    private fun newAdapterId(type: String): String {
        val used = adapterInstances.map { it.id }.toSet()
        var n = 1
        var id = type
        while (id in used) {
            n++
            id = "$type-$n"
        }
        return id
    }

    private fun showAdapterForm(type: String, initial: Map<String, String> = emptyMap()) {
        val form = GeneratedAndroidFormRegistry.get(type) ?: return
        adapterFormContainer.removeAllViews()
        currentAdapterFormView = form.createView(this, initial)
        adapterFormContainer.addView(currentAdapterFormView)
    }

    private fun selectedAdapterType(): String {
        val types = selectableAdapterTypes()
        val idx = if (::adapterTypeSpinner.isInitialized) adapterTypeSpinner.selectedItemPosition else 0
        return types.getOrElse(idx) { DEFAULT_ADAPTER_TYPE }
    }

    private fun buildVoiceTab(): LinearLayout {
        val prefs = VoicePrefs(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(BekonUi.screenTitle(this, "Voice"))
        layout.addView(BekonUi.bodyHint(this, "PHONE bridges GSM to the desktop when a call is offhook. WALKIE TALKIE is speakerphone, always on. Stop Voice turns the socket OFF."))

        val card = BekonUi.sectionCard(this)
        val col = BekonUi.cardColumn(this)
        val url = BekonUi.outlinedField(this, "WebSocket URL")
        voiceUrlInput = url.edit
        voiceUrlInput.setText(prefs.url)
        col.addView(url.layout)

        val room = BekonUi.outlinedField(this, "Room")
        voiceRoomInput = room.edit
        voiceRoomInput.setText(prefs.room)
        col.addView(room.layout)

        val seed = BekonUi.outlinedField(this, "Secret", password = true)
        voiceSeedInput = seed.edit
        voiceSeedInput.setText(prefs.seed)
        col.addView(seed.layout)

        val autoRow = BekonUi.row(this).apply { minimumHeight = BekonUi.dp(this@SetupActivity, 48) }
        autoRow.addView(TextView(this).apply {
            text = "Auto start"
            textSize = 16f
            setTextColor(BekonUi.onSurface)
            layoutParams = BekonUi.lpWeight(1f)
        })
        autoRow.addView(SwitchMaterial(this).apply {
            isChecked = prefs.autoStart
            setOnCheckedChangeListener { _, checked ->
                VoicePrefs(this@SetupActivity).autoStart = checked
            }
        })
        col.addView(autoRow)

        voiceConnectBtn = BekonUi.filledButton(this, "Connect").apply {
            setOnClickListener { toggleVoice() }
        }
        col.addView(voiceConnectBtn)
        card.addView(col)
        layout.addView(card)

        layout.addView(buildVoiceLineCard())
        layout.addView(buildVoiceDebugCard())
        return layout
    }

    private fun buildVoiceLineCard(): MaterialCardView {
        val card = BekonUi.sectionCard(this)
        val col = BekonUi.cardColumn(this)
        col.addView(sectionCaption("Line"))

        voicePhoneToggleId = View.generateViewId()
        voiceWalkieToggleId = View.generateViewId()
        voicePhoneBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = voicePhoneToggleId
            text = "Phone"
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minimumHeight = BekonUi.dp(this@SetupActivity, 48)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        voiceWalkieBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = voiceWalkieToggleId
            text = "Walkie"
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minimumHeight = BekonUi.dp(this@SetupActivity, 48)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        voiceModeToggle = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = false
            addView(voicePhoneBtn)
            addView(voiceWalkieBtn)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (voiceApplyingModeToggle || !isChecked) return@addOnButtonCheckedListener
                when (checkedId) {
                    voicePhoneToggleId -> VoiceService.requestMode(VoiceLineState.MODE_PHONE)
                    voiceWalkieToggleId -> VoiceService.requestMode(VoiceLineState.MODE_WALKIE)
                }
            }
        }
        col.addView(voiceModeToggle)

        voiceModeHint = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_HINT)
            setPadding(0, BekonUi.dp(this@SetupActivity, 8), 0, BekonUi.dp(this@SetupActivity, 4))
        }
        col.addView(voiceModeHint)

        val callMetric = BekonUi.metricRow(this, "Call")
        voiceCallValue = callMetric.value
        voiceCallExtra = callMetric.extra
        col.addView(callMetric.layout)

        val socketMetric = BekonUi.metricRow(this, "Socket")
        voiceSocketValue = socketMetric.value
        voiceStatus = voiceSocketValue
        col.addView(socketMetric.layout)

        val lineMetric = BekonUi.metricRow(this, "Telephony")
        voiceLineValue = lineMetric.value
        col.addView(lineMetric.layout)

        card.addView(col)
        return card
    }

    private fun buildVoiceDebugCard(): MaterialCardView {
        val prefs = VoicePrefs(this)
        val card = BekonUi.sectionCard(this)
        val col = BekonUi.cardColumn(this)

        voiceDebugChevron = TextView(this).apply {
            text = "▸"
            textSize = 18f
            setTextColor(COLOR_HINT)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                BekonUi.dp(this@SetupActivity, 28),
                BekonUi.dp(this@SetupActivity, 48),
            )
        }
        val header = BekonUi.row(this).apply {
            minimumHeight = BekonUi.dp(this@SetupActivity, 48)
            isClickable = true
            isFocusable = true
            contentDescription = "Expand debug"
            val out = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
        }
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = BekonUi.lpWeight(1f)
        }
        headerText.addView(TextView(this).apply {
            text = "Debug"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TITLE)
        })
        headerText.addView(TextView(this).apply {
            text = "PCM meters, route map, ALSA"
            textSize = 13f
            setTextColor(COLOR_HINT)
        })
        header.addView(headerText)
        header.addView(voiceDebugChevron)
        header.setOnClickListener { setVoiceDebugExpanded(!voiceDebugExpanded) }
        col.addView(header)

        voiceDebugBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, BekonUi.dp(this@SetupActivity, 8), 0, 0)
        }

        val debugRow = BekonUi.row(this).apply { minimumHeight = BekonUi.dp(this@SetupActivity, 48) }
        debugRow.addView(TextView(this).apply {
            text = "PCM meters"
            textSize = 16f
            setTextColor(BekonUi.onSurface)
            layoutParams = BekonUi.lpWeight(1f)
        })
        debugRow.addView(SwitchMaterial(this).apply {
            isChecked = prefs.debugMeters
            VoiceMeters.debug = prefs.debugMeters
            setOnCheckedChangeListener { _, checked ->
                VoicePrefs(this@SetupActivity).debugMeters = checked
                if (::voiceMetersBox.isInitialized) {
                    voiceMetersBox.visibility = if (checked) View.VISIBLE else View.GONE
                }
                GsmLevelProbe.stop()
                startVoiceMeterPoll()
            }
        })
        voiceDebugBox.addView(debugRow)

        voiceMetersBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.debugMeters) View.VISIBLE else View.GONE
        }
        voiceMeterHint = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_HINT)
            setPadding(0, 0, 0, BekonUi.dp(this@SetupActivity, 8))
        }
        voiceMetersBox.addView(voiceMeterHint)
        listOf(
            "mic" to "Walkie mic",
            "walkieSpk" to "Walkie speaker",
            "wsIn" to "WebSocket in",
            "wsOut" to "WebSocket out",
            "gsmIn" to "GSM downlink",
            "gsmOut" to "GSM uplink",
        ).forEach { (key, label) ->
            voiceMetersBox.addView(makeVoiceMeterRow(key, label))
        }
        voiceDebugBox.addView(voiceMetersBox)
        voiceDebugBox.addView(TextView(this).apply {
            text = "Scan is read-only tinymix and /proc/asound. Do not probe mixers during a live call."
            textSize = 13f
            setTextColor(COLOR_HINT)
            setPadding(0, BekonUi.dp(this@SetupActivity, 8), 0, BekonUi.dp(this@SetupActivity, 4))
        })
        val actions = BekonUi.row(this)
        actions.addView(BekonUi.outlinedButton(this, "Scan routes").apply {
            layoutParams = BekonUi.lpWeight(1f).apply {
                marginEnd = BekonUi.dp(this@SetupActivity, 8)
                topMargin = BekonUi.dp(this@SetupActivity, 8)
            }
            setOnClickListener { LineRouteMap.scanAsync() }
        })
        actions.addView(BekonUi.outlinedButton(this, "Copy map").apply {
            layoutParams = BekonUi.lpWeight(1f).apply {
                topMargin = BekonUi.dp(this@SetupActivity, 8)
            }
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("bekon-line", LineRouteMap.text))
                Toast.makeText(this@SetupActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        voiceDebugBox.addView(actions)

        val well = BekonUi.dumpWell(this)
        voiceRouteDump = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(COLOR_HINT)
            text = LineRouteMap.text
        }
        well.addView(HorizontalScrollView(this).apply { addView(voiceRouteDump) })
        voiceAlsaDump = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(COLOR_HINT)
            setPadding(0, BekonUi.dp(this@SetupActivity, 8), 0, 0)
            text = "Expand Debug to watch ALSA hw_ptr"
        }
        well.addView(voiceAlsaDump)
        voiceDebugBox.addView(well)

        col.addView(voiceDebugBox)
        card.addView(col)
        return card
    }

    private fun setVoiceDebugExpanded(open: Boolean) {
        voiceDebugExpanded = open
        if (!::voiceDebugBox.isInitialized) return
        voiceDebugBox.visibility = if (open) View.VISIBLE else View.GONE
        voiceDebugChevron.text = if (open) "▾" else "▸"
        if (::voiceDebugChevron.isInitialized) {
            (voiceDebugChevron.parent as? View)?.contentDescription =
                if (open) "Collapse debug" else "Expand debug"
        }
        if (open) {
            AlsaCaptureScan.start(this)
            startVoiceMeterPoll()
        } else {
            AlsaCaptureScan.stop()
            startVoiceMeterPoll()
        }
    }

    private fun saveVoicePrefs() {
        val prefs = VoicePrefs(this)
        prefs.url = voiceUrlInput.text.toString().trim().ifEmpty { VoicePrefs.DEFAULT_URL }
        prefs.room = voiceRoomInput.text.toString().trim().ifEmpty { VoicePrefs.DEFAULT_ROOM }
        prefs.seed = voiceSeedInput.text.toString()
    }

    private fun refreshVoiceStatus() {
        if (!::voiceConnectBtn.isInitialized) return
        val on = VoiceService.connected || VoiceService.instance != null
        val mode = if (!on) "off" else VoiceService.displayMode
        if (::voiceModeHint.isInitialized) {
            voiceModeHint.text = when {
                !on -> "Connect to choose Phone or Walkie."
                mode == VoiceLineState.MODE_WALKIE -> "Speakerphone stays on until you stop Voice."
                else -> "GSM audio goes to the desktop when the call is off-hook."
            }
        }
        if (::voiceCallValue.isInitialized) {
            val call = VoiceService.callUi.lowercase()
            voiceCallValue.text = when (call) {
                "idle" -> "Idle"
                "ringing" -> "Ringing"
                "offhook" -> "Off-hook"
                else -> VoiceService.callUi.replaceFirstChar { it.uppercase() }
            }
            voiceCallValue.setTextColor(
                when (call) {
                    "ringing" -> COLOR_PENDING
                    "offhook" -> COLOR_OK
                    "idle" -> COLOR_HINT
                    else -> COLOR_TITLE
                },
            )
            val num = VoiceService.incomingUi
            if (num.isNotBlank()) {
                voiceCallExtra.visibility = View.VISIBLE
                voiceCallExtra.text = num
            } else {
                voiceCallExtra.visibility = View.GONE
            }
        }
        if (::voiceSocketValue.isInitialized) {
            val sock = VoiceService.status
            voiceSocketValue.text = sock.ifBlank { "—" }
            voiceSocketValue.setTextColor(
                when {
                    sock == "joined" -> COLOR_OK
                    sock.startsWith("error") -> COLOR_MISSING
                    sock == "connecting" || sock.startsWith("reconnecting") -> COLOR_PENDING
                    else -> COLOR_HINT
                },
            )
        }
        if (::voiceLineValue.isInitialized) {
            voiceLineValue.text = VoiceService.phoneState.ifBlank { "—" }
        }
        if (::voiceModeToggle.isInitialized) {
            voiceApplyingModeToggle = true
            val wantId = when {
                !on -> View.NO_ID
                mode == VoiceLineState.MODE_WALKIE -> voiceWalkieToggleId
                else -> voicePhoneToggleId
            }
            if (voiceModeToggle.checkedButtonId != wantId) {
                if (wantId == View.NO_ID) voiceModeToggle.clearChecked()
                else voiceModeToggle.check(wantId)
            }
            voiceApplyingModeToggle = false
            voiceModeToggle.isEnabled = on
            voicePhoneBtn.isEnabled = on
            voiceWalkieBtn.isEnabled = on
        }
        voiceConnectBtn.isEnabled = true
        voiceConnectBtn.text = if (on) "Stop Voice" else "Connect"
        if (::voiceUrlInput.isInitialized) {
            voiceUrlInput.isEnabled = !on
            voiceRoomInput.isEnabled = !on
            voiceSeedInput.isEnabled = !on
        }
    }

    private fun voicePermsNeeded(): Array<String> {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        need.add(Manifest.permission.CALL_PHONE)
        need.add(Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 26) need.add(Manifest.permission.ANSWER_PHONE_CALLS)
        return need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun maybeAutoStartVoice() {
        if (!VoicePrefs(this).autoStart) return
        if (VoiceService.instance != null) return
        val missing = voicePermsNeeded()
        if (missing.isNotEmpty()) {
            requestPermissions(missing, REQ_VOICE)
            return
        }
        VoiceService.start(this)
    }

    /** After APK replace / Recents, FGS is dead. Resume if Start All had left PREF_RUNNING. */
    private fun maybeResumeTunnel() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(WlyaTunnelManager.PREF_RUNNING, false)) return
        if (AgentForegroundService.instance != null) return
        AgentForegroundService.start(this)
    }

    private fun toggleVoice() {
        if (VoiceService.connected || VoiceService.instance != null) {
            VoiceService.stop(this)
            handler.postDelayed({ refreshVoiceStatus() }, 400)
            return
        }
        saveVoicePrefs()
        voiceConnectBtn.isEnabled = false
        voiceConnectBtn.text = "Connecting…"
        val missing = voicePermsNeeded()
        if (missing.isNotEmpty()) {
            requestPermissions(missing, REQ_VOICE)
            return
        }
        VoiceService.start(this)
        handler.postDelayed({ refreshVoiceStatus() }, 400)
    }

    private fun makeVoiceMeterRow(key: String, label: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, BekonUi.dp(this@SetupActivity, 4), 0, BekonUi.dp(this@SetupActivity, 4))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(COLOR_HINT)
        })
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            setProgressCompat(0, false)
            trackCornerRadius = BekonUi.dp(this@SetupActivity, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        voiceMeterBars[key] = bar
        row.addView(bar)
        return row
    }

    private fun refreshVoiceMeters() {
        if (!voiceDebugExpanded) return
        if (VoiceMeters.debug && ::voiceMetersBox.isInitialized) {
            VoiceMeters.sampleDevice(getSystemService(AudioManager::class.java))
            val j = VoiceMeters.toJson()
            val spk = if (j.optBoolean("speakerphone")) "speakerphone" else "earpiece"
            val callKnob = j.optString("volCallText", "")
            val musicKnob = j.optString("volMusicText", "")
            voiceMeterHint.text = buildString {
                append("audioMode=${j.optString("audioMode", "?")}  route=$spk")
                append('\n')
                append("knobs: VOICE_CALL $callKnob  MUSIC $musicKnob")
                append('\n')
                append("knobs = settings, not signal.")
                val tap = j.optString("tapHint", "")
                if (tap.isNotBlank()) {
                    append('\n')
                    append(tap)
                }
            }
            fun bar(key: String) {
                voiceMeterBars[key]?.setProgressCompat(j.optInt(key, 0), true)
            }
            bar("mic")
            bar("walkieSpk")
            bar("volCall")
            bar("volMusic")
            bar("wsIn")
            bar("wsOut")
            bar("gsmIn")
            bar("gsmOut")
            VoiceMeters.decay()
        }
        if (::voiceRouteDump.isInitialized) {
            voiceRouteDump.text = LineRouteMap.text
        }
        if (::voiceAlsaDump.isInitialized) {
            voiceAlsaDump.text = AlsaCaptureScan.render()
            AlsaCaptureScan.decay()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC || requestCode == REQ_VOICE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                VoiceService.start(this)
            } else if (requestCode == REQ_VOICE && voicePermsNeeded().isEmpty()) {
                VoiceService.start(this)
            } else {
                Toast.makeText(this, "Voice needs mic and phone permissions", Toast.LENGTH_SHORT).show()
            }
            refreshVoiceStatus()
        }
    }

    private fun buildLogTab(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        layout.addView(BekonUi.screenTitle(this, "Log"))

        logAdapterSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    logAdapterFilter = if (position <= 0) "" else (parent?.getItemAtPosition(position) as? String) ?: ""
                    refreshLogUi()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        layout.addView(logAdapterSpinner)

        val actions = BekonUi.row(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            gravity = Gravity.CENTER_VERTICAL
        }
        hidePollCheck = SwitchMaterial(this).apply {
            text = "Hide poll"
            textSize = 14f
            isChecked = true
            setOnCheckedChangeListener { _, _ -> refreshLogUi() }
        }
        actions.addView(hidePollCheck)
        actions.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        actions.addView(BekonUi.tonalButton(this, "Clear").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                uiLogNotes.clear()
                AgentForegroundService.instance?.wlyaManager?.clearLogs()
                refreshLogUi()
            }
        })
        layout.addView(actions)

        logView = TextView(this).apply { visibility = View.GONE }
        val messagesPane = makeLogPane("Messages")
        logMessageList = messagesPane.second
        layout.addView(messagesPane.first)
        val adapterPane = makeLogPane("Adapter")
        logAdapterList = adapterPane.second
        layout.addView(adapterPane.first)
        return layout
    }

    private fun makeLogPane(title: String): Pair<android.view.ViewGroup, LinearLayout> {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(BekonUi.dp(this@SetupActivity, 12), BekonUi.dp(this@SetupActivity, 8), BekonUi.dp(this@SetupActivity, 12), BekonUi.dp(this@SetupActivity, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val card = BekonUi.sectionCard(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TITLE)
            setPadding(
                BekonUi.dp(this@SetupActivity, 12),
                BekonUi.dp(this@SetupActivity, 8),
                BekonUi.dp(this@SetupActivity, 12),
                BekonUi.dp(this@SetupActivity, 4),
            )
        })
        col.addView(ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(HorizontalScrollView(this@SetupActivity).apply {
                isFillViewport = true
                addView(list)
            })
        })
        card.addView(col)
        return card to list
    }

    private fun refreshLogUi() {
        if (!::logMessageList.isInitialized || !::logAdapterList.isInitialized) return
        val mgr = AgentForegroundService.instance?.wlyaManager
        val names = mgr?.adapterLogNames().orEmpty()
        syncLogAdapterSpinner(names)

        val hidePoll = ::hidePollCheck.isInitialized && hidePollCheck.isChecked
        val filter = logAdapterFilter
        val messageLines = (uiLogNotes + (mgr?.snapshotMessageLines() ?: emptyList()))
            .asReversed()
        val adapterLines = (mgr?.snapshotAdapterLogLines() ?: emptyList())
            .filter { line ->
                if (filter.isNotEmpty() && !line.contains(filter)) return@filter false
                if (hidePoll && isPollLogLine(line)) return@filter false
                true
            }
            .asReversed()

        fillLogLines(logMessageList, messageLines, "(no messages yet)")
        fillLogLines(logAdapterList, adapterLines, "(no adapter log yet)")
    }

    private fun fillLogLines(target: LinearLayout, lines: List<String>, empty: String) {
        target.removeAllViews()
        if (lines.isEmpty()) {
            target.addView(TextView(this).apply {
                text = empty
                textSize = 9f
                setTextColor(COLOR_HINT)
            })
            return
        }
        lines.take(200).forEach { line ->
            target.addView(TextView(this).apply {
                text = line
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(BekonUi.onSurface)
                setSingleLine(true)
                ellipsize = null
                setHorizontallyScrolling(true)
                setPadding(0, 2, 0, 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
        }
    }

    private fun syncLogAdapterSpinner(names: List<String>) {
        if (!::logAdapterSpinner.isInitialized) return
        val items = listOf("All adapters") + names
        val current = logAdapterSpinner.selectedItem as? String
        val adapter = logAdapterSpinner.adapter as? ArrayAdapter<*>
        val same = adapter != null && adapter.count == items.size &&
            items.indices.all { adapter.getItem(it) == items[it] }
        if (!same) {
            logAdapterSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                items,
            )
            val idx = items.indexOf(current).takeIf { it >= 0 } ?: 0
            logAdapterSpinner.setSelection(idx)
            if (idx <= 0) logAdapterFilter = ""
        }
    }

    private fun isPollLogLine(line: String): Boolean {
        if (line.contains("FAILED", ignoreCase = true)) return false
        if (line.contains("ERROR", ignoreCase = true)) return false
        return Regex("\\] poll\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
    }

    // ── Start All (sequential) ────────────────────────────────────

    private fun runSetup() {
        if (setupRunning) return
        setupRunning = true
        setBusy(true)
        showTab("status")
        appendLog("— Start All —")
        setHint("Starting…")

        val settings = getWlyaSettings()
        if (settings.channel.isEmpty()) {
            finishSetup(false, "Channel required (Tunnel tab)")
            return
        }
        if (settings.adapters.isEmpty()) {
            finishSetup(false, "Add at least one adapter (Tunnel tab)")
            return
        }
        saveSettings()

        setRow("Service", StepState.PENDING, "Starting…")
        setRow("WLYA", StepState.IDLE, "Queued")
        setRow("Touch", StepState.IDLE, "Queued")
        setRow("Keyboard", StepState.IDLE, "Queued")
        setRow("Capture", StepState.IDLE, "Queued")

        AgentForegroundService.start(this)
        waitForService(0) {
            setRow("Service", StepState.OK, "Running")
            setRow("WLYA", StepState.PENDING, "Connecting tunnel…")
            continueWithCapture(it)
        }
    }

    private fun waitForService(attempt: Int, onReady: (AgentForegroundService) -> Unit) {
        val svc = AgentForegroundService.instance
        if (svc != null) {
            onReady(svc)
            return
        }
        if (attempt >= SERVICE_WAIT_MAX) {
            setRow("Service", StepState.ERROR, "Failed to start")
            setRow("WLYA", StepState.IDLE, "Service failed")
            finishSetup(false, "Service failed to start")
            return
        }
        setRow("Service", StepState.PENDING, "Starting… (${attempt + 1}/$SERVICE_WAIT_MAX)")
        handler.postDelayed({ waitForService(attempt + 1, onReady) }, SERVICE_WAIT_MS)
    }

    private fun continueWithCapture(svc: AgentForegroundService) {
        if (svc.isRooted || svc.captureProvider != null) {
            setRow(
                "Capture",
                StepState.OK,
                if (svc.isRooted) "Root capture" else "Permission already granted"
            )
            continueWithTouch()
            return
        }

        setHint("Grant screen capture permission…")
        setRow("Capture", StepState.PENDING, "Waiting for Android permission dialog…")
        waitingForCapture = true
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun continueWithTouch() {
        if (isTouchBound()) {
            setRow("Touch", StepState.OK, "Accessibility connected")
            continueWithWlya()
            return
        }

        if (RootDetector.isRooted) {
            setHint("Root: enabling Bekon Touch and keyboard…")
            setRow("Touch", StepState.PENDING, "Granting a11y / IME via root…")
            Thread {
                RootBootstrap.apply(this)
                runOnUiThread { waitForTouchBound(0) }
            }.start()
            return
        }

        setHint("Enable Bekon Touch in Accessibility settings…")
        setRow("Touch", StepState.PENDING, "Open Accessibility → enable Bekon / Touch")
        waitingForTouch = true
        pausedWhileWaitingTouch = false
        startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQ_TOUCH)
    }

    private fun waitForTouchBound(attempt: Int) {
        if (isTouchBound()) {
            setRow("Touch", StepState.OK, "Accessibility connected")
            continueWithWlya()
            return
        }
        if (attempt >= SERVICE_WAIT_MAX) {
            setRow("Touch", StepState.MISSING, "Root enable did not bind — toggle Bekon Touch")
            continueWithWlya()
            return
        }
        setRow("Touch", StepState.PENDING, "Waiting for a11y… (${attempt + 1}/$SERVICE_WAIT_MAX)")
        handler.postDelayed({ waitForTouchBound(attempt + 1) }, SERVICE_WAIT_MS)
    }

    private fun onTouchSettingsReturned() {
        if (!setupRunning) {
            refreshLiveStatus()
            return
        }
        if (isTouchBound()) {
            setRow("Touch", StepState.OK, "Accessibility connected")
        } else {
            setRow("Touch", StepState.MISSING, "Still disabled — enable Bekon in Accessibility")
            appendLog("Touch not enabled yet")
        }
        // Continue even if missing — tunnel can still start; UI shows what's wrong.
        continueWithWlya()
    }

    private fun continueWithWlya() {
        val settings = getWlyaSettings()
        setHint("Connecting WLYA tunnel…")
        setRow("WLYA", StepState.PENDING, "Connecting tunnel…")

        fun startOnce(attempts: Int) {
            val svc = AgentForegroundService.instance
            if (svc == null) {
                if (attempts < SERVICE_WAIT_MAX) {
                    handler.postDelayed({ startOnce(attempts + 1) }, SERVICE_WAIT_MS)
                } else {
                    setRow("Service", StepState.ERROR, "Gone")
                    setRow("WLYA", StepState.ERROR, "Service gone")
                    finishSetup(false, "Service disappeared before WLYA start")
                }
                return
            }

            svc.startWlyaTunnel(
                settings.channel,
                settings.secret,
                settings.adapters,
                onReady = {
                    runOnUiThread {
                        setRow("WLYA", StepState.OK, "Connected")
                        finishSetup(true, "All set")
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        setRow("WLYA", StepState.ERROR, err)
                        finishSetup(false, "WLYA failed: $err")
                    }
                },
                onMessage = { msg ->
                    runOnUiThread { appendLog("${msg.from.take(8)}: ${msg.text}") }
                }
            )
        }
        startOnce(0)
    }

    private fun finishSetup(ok: Boolean, message: String) {
        setupRunning = false
        waitingForCapture = false
        waitingForTouch = false
        pausedWhileWaitingTouch = false
        setBusy(false)
        setHint(message)
        appendLog(message)
        refreshLiveStatus(preservePending = false)
        updateActionButtons()
        AgentForegroundService.instance?.refreshNotification()
        if (!ok) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopEverything() {
        setupRunning = false
        waitingForCapture = false
        waitingForTouch = false
        pausedWhileWaitingTouch = false
        AgentForegroundService.instance?.wlyaManager?.stop()
        AgentForegroundService.stop(this)
        setBusy(false)
        setHint("Stopped")
        appendLog("— Stop All —")
        handler.postDelayed({
            refreshLiveStatus()
            updateActionButtons()
        }, 400)
    }

    // ── Activity results ──────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAPTURE -> {
                waitingForCapture = false
                if (!setupRunning) {
                    refreshLiveStatus()
                    return
                }
                if (resultCode == RESULT_OK && data != null) {
                    AgentForegroundService.onCapturePermissionGranted(data, resultCode)
                    setRow("Capture", StepState.OK, "Permission granted")
                    continueWithTouch()
                } else {
                    setRow("Capture", StepState.MISSING, "Permission denied — needed for non-root capture")
                    appendLog("Capture permission denied")
                    // Still continue: root devices / messaging may work without it.
                    continueWithTouch()
                }
            }
            REQ_TOUCH -> {
                // Handled in onResume via waitingForTouch — avoid double-continue.
            }
        }
    }

    // ── Status rendering ──────────────────────────────────────────

    private fun refreshLiveStatus(preservePending: Boolean = true) {
        if (setupRunning && preservePending) return

        val svc = AgentForegroundService.instance
        if (svc == null) {
            setRow("Service", StepState.IDLE, "Not running")
            setRow("WLYA", StepState.IDLE, "Not running")
            setRow("Touch", if (isTouchBound()) StepState.OK else StepState.MISSING,
                touchStatusDetail())
            setRow("Keyboard", keyboardRowState(), BekonImeService.statusDetail(contentResolver))
            setRow("Capture", StepState.IDLE, "Service off")
            updateActionButtons()
            return
        }

        val wlyaOk = svc.wlyaManager.isRunning()
        setRow("Service", StepState.OK, "Running")
        setRow(
            "WLYA",
            if (wlyaOk) StepState.OK else StepState.MISSING,
            if (wlyaOk) "Connected" else "Not connected",
        )
        setRow(
            "Touch",
            if (isTouchBound()) StepState.OK else StepState.MISSING,
            touchStatusDetail()
        )
        setRow("Keyboard", keyboardRowState(), BekonImeService.statusDetail(contentResolver))
        val captureOk = svc.isRooted || svc.captureProvider != null
        setRow(
            "Capture",
            if (captureOk) StepState.OK else StepState.MISSING,
            when {
                svc.isRooted -> "Root capture"
                svc.captureProvider != null -> "MediaProjection active"
                else -> "Permission needed"
            }
        )
        updateActionButtons()
    }

    private fun setRow(key: String, state: StepState, detail: String) {
        val row = rows[key] ?: return
        row.state = state
        row.detailText = detail
        row.detail.text = detail

        when (state) {
            StepState.PENDING -> {
                row.spinner.visibility = View.VISIBLE
                row.icon.visibility = View.GONE
                row.detail.setTextColor(COLOR_PENDING)
            }
            StepState.OK -> {
                row.spinner.visibility = View.GONE
                row.icon.visibility = View.VISIBLE
                row.icon.text = "✓"
                row.icon.setTextColor(COLOR_OK)
                row.detail.setTextColor(COLOR_OK)
            }
            StepState.MISSING -> {
                row.spinner.visibility = View.GONE
                row.icon.visibility = View.VISIBLE
                row.icon.text = "!"
                row.icon.setTextColor(COLOR_MISSING)
                row.detail.setTextColor(COLOR_MISSING)
            }
            StepState.ERROR -> {
                row.spinner.visibility = View.GONE
                row.icon.visibility = View.VISIBLE
                row.icon.text = "✗"
                row.icon.setTextColor(COLOR_MISSING)
                row.detail.setTextColor(COLOR_MISSING)
            }
            StepState.IDLE -> {
                row.spinner.visibility = View.GONE
                row.icon.visibility = View.VISIBLE
                row.icon.text = "○"
                row.icon.setTextColor(COLOR_IDLE)
                row.detail.setTextColor(COLOR_HINT)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        if (!::startBtn.isInitialized) return
        if (busy) {
            startBtn.isEnabled = false
            startBtn.text = "Starting…"
            stopBtn.isEnabled = false
        } else {
            updateActionButtons()
        }
    }

    private fun updateActionButtons() {
        if (!::startBtn.isInitialized) return
        startBtn.isEnabled = !setupRunning
        startBtn.text = if (setupRunning) "Starting…" else "Start All"
        stopBtn.isEnabled = AgentForegroundService.instance != null
    }

    private fun setHint(text: String) {
        progressHint.text = text
    }

    private fun appendLog(line: String) {
        val compact = pro.potoki.bekon.wlya.WlyaTunnelManager.compactLine(line)
        uiLogNotes.add(compact)
        Log.i(TAG, line)
        if (activeTab == "log") refreshLogUi()
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun isTouchBound(): Boolean = TouchService.isBound()

    private fun isTouchListed(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("pro.potoki.bekon") && enabled.contains("TouchService")
    }

    private fun touchStatusDetail(): String = when {
        isTouchBound() -> "Accessibility connected"
        isTouchListed() -> "Listed but not bound — toggle Bekon Touch off/on"
        else -> "Enable in Accessibility settings"
    }

    private fun openTouchSettings() {
        Toast.makeText(this, "Enable Bekon / Touch", Toast.LENGTH_SHORT).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun keyboardRowState(): StepState = when {
        BekonImeService.isSelected(contentResolver) -> StepState.OK
        BekonImeService.isEnabled(contentResolver) -> StepState.MISSING
        else -> StepState.MISSING
    }

    private fun openKeyboardSettings() {
        if (BekonImeService.isEnabled(contentResolver) && !BekonImeService.isSelected(contentResolver)) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
            Toast.makeText(this, "Pick Bekon Keys", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        Toast.makeText(this, "Enable Bekon Keys, then select it as the keyboard", Toast.LENGTH_LONG).show()
    }

    private data class WlyaSettings(
        val channel: String,
        val secret: String,
        val adapters: List<AdapterInstanceConfig>,
    )

    private fun loadAdapterInstances(): List<AdapterInstanceConfig> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(PREFS_ADAPTERS, null)
        if (!json.isNullOrBlank()) {
            return try {
                configJson.decodeFromString(ListSerializer(AdapterInstanceConfig.serializer()), json)
                    .filter { it.type != "mock" }
            } catch (_: Exception) {
                migrateLegacyAdapters(prefs)
            }
        }
        return migrateLegacyAdapters(prefs)
    }

    private fun migrateLegacyAdapters(prefs: android.content.SharedPreferences): List<AdapterInstanceConfig> {
        val type = prefs.getString(PREFS_ADAPTER_TYPE, null)
        val json = prefs.getString(PREFS_ADAPTER_CONFIG, null)
        if (type != null && type != "mock" && json != null) {
            val config = try {
                configJson.decodeFromString(
                    MapSerializer(String.serializer(), String.serializer()),
                    json,
                )
            } catch (_: Exception) {
                migrateLegacyAdapterConfig(prefs)
            }
            val label = config["label"]?.takeIf { it.isNotBlank() }
                ?: config["login"]?.takeIf { it.isNotBlank() }
                ?: type
            return listOf(AdapterInstanceConfig(type = type, id = type, label = label, config = config))
        }
        val legacyEmail = prefs.getString(PREFS_EMAIL, null)?.takeIf { it.isNotBlank() }
        val legacyPassword = prefs.getString(PREFS_PASSWORD, null)?.takeIf { it.isNotBlank() }
        if (legacyEmail != null || legacyPassword != null) {
            val config = migrateLegacyAdapterConfig(prefs)
            return listOf(
                AdapterInstanceConfig(
                    type = "email",
                    id = "email",
                    label = config["login"] ?: "email",
                    config = config,
                )
            )
        }
        return listOf(defaultWlyaServerInstance())
    }

    private fun defaultWlyaServerInstance(): AdapterInstanceConfig =
        AdapterInstanceConfig(
            type = "wlyaserver",
            id = "wlyaserver",
            label = "WLYA Server",
            config = mapOf(
                "serverUrl" to DEFAULT_WLYA_SERVER_URL,
                "role" to "primary",
            ),
        )

    private fun migrateLegacyAdapterConfig(prefs: android.content.SharedPreferences): Map<String, String> {
        val email = prefs.getString(PREFS_EMAIL, DEFAULT_EMAIL) ?: DEFAULT_EMAIL
        val password = prefs.getString(PREFS_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        return mapOf(
            "login" to email,
            "password" to password,
            "host" to "imap.mail.ru",
            "smtpHost" to "smtp.mail.ru",
        )
    }

    private fun saveSettings() {
        val channel = if (::channelInput.isInitialized) channelInput.text.toString().trim()
        else storedChannel(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        val secret = if (::secretInput.isInitialized) secretInput.text.toString()
        else getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREFS_SECRET, "") ?: ""
        val adapters = adapterInstances.toList()
        val first = adapters.firstOrNull()
        val acceptAdvertised = if (::acceptAdvertisedCheck.isInitialized) acceptAdvertisedCheck.isChecked
        else getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREFS_ACCEPT_ADVERTISED, true)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREFS_CHANNEL, channel)
            .putString(PREFS_SECRET, secret)
            .putString(PREFS_SEED, channel)
            .putBoolean(PREFS_ACCEPT_ADVERTISED, acceptAdvertised)
            .putString(PREFS_ADAPTERS, configJson.encodeToString(
                ListSerializer(AdapterInstanceConfig.serializer()),
                adapters,
            ))
            .putString(PREFS_ADAPTER_TYPE, first?.type ?: "")
            .putString(PREFS_ADAPTER_CONFIG, first?.let {
                configJson.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    it.config,
                )
            } ?: "")
            .apply()
    }

    private fun getWlyaSettings(): WlyaSettings {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val channel = if (::channelInput.isInitialized) channelInput.text.toString().trim()
        else storedChannel(prefs)
        val secret = if (::secretInput.isInitialized) secretInput.text.toString()
        else prefs.getString(PREFS_SECRET, "") ?: ""
        val adapters = if (adapterInstances.isNotEmpty() || ::adaptersListContainer.isInitialized) {
            adapterInstances.toList()
        } else {
            loadAdapterInstances()
        }
        return WlyaSettings(channel = channel, secret = secret, adapters = adapters)
    }

    private fun storedChannel(prefs: android.content.SharedPreferences): String {
        val ch = prefs.getString(PREFS_CHANNEL, null)
        if (!ch.isNullOrBlank()) return ch
        return prefs.getString(PREFS_SEED, DEFAULT_CHANNEL) ?: DEFAULT_CHANNEL
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
