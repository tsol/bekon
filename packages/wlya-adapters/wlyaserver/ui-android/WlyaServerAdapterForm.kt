package com.wlya.core.adapters.wlyaserver.ui.android

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.wlya.adapters.ui.AdapterAndroidForm
import com.wlya.adapters.ui.AdapterDutySection

private data class Holder(
    val label: EditText,
    val serverUrl: EditText,
    val clientId: EditText,
    val windowSize: EditText,
    val duty: AdapterDutySection.Holder,
)

class WlyaServerAdapterForm : AdapterAndroidForm {
    override val adapterType = "wlyaserver"

    override fun createView(context: Context, initial: Map<String, String>): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun caption(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, 8, 0, 4)
        }

        fun field(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(context).apply {
                this.hint = hint
                textSize = 16f
                setSingleLine()
                this.inputType = inputType
            }

        val label = field("Display name")
        val serverUrl = field("Server URL", InputType.TYPE_TEXT_VARIATION_URI)
        val clientId = field("Client ID (optional)")
        val windowSize = field("Max packet bytes", InputType.TYPE_CLASS_NUMBER)
        val duty = AdapterDutySection()

        root.addView(caption("Display name"))
        root.addView(label)
        root.addView(caption("Server URL"))
        root.addView(serverUrl)
        root.addView(caption("Client ID"))
        root.addView(clientId)
        root.addView(caption("Max packet size (bytes, default 262144)"))
        root.addView(windowSize)
        val dutyHolder = duty.addTo(context, root, "primary", "2000")

        root.tag = Holder(label, serverUrl, clientId, windowSize, dutyHolder)
        applyConfig(root, initial)
        return root
    }

    override fun readConfig(view: View): Map<String, String> {
        val h = view.tag as Holder
        val window = h.windowSize.text.toString().trim().ifEmpty { "262144" }
        return mapOf(
            "label" to h.label.text.toString().trim(),
            "serverUrl" to h.serverUrl.text.toString().trim(),
            "clientId" to h.clientId.text.toString().trim(),
            "windowSize" to window,
        ) + AdapterDutySection().read(h.duty)
    }

    override fun applyConfig(view: View, config: Map<String, String>) {
        val h = view.tag as Holder
        h.label.setText(config["label"] ?: "")
        h.serverUrl.setText(config["serverUrl"] ?: "https://relay.example")
        h.clientId.setText(config["clientId"] ?: "")
        h.windowSize.setText(config["windowSize"] ?: "262144")
        AdapterDutySection().apply(h.duty, config, "primary", "2000")
    }
}
