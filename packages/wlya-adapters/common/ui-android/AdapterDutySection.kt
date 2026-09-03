package com.wlya.adapters.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class AdapterDutySection {
    data class Holder(
        val role: Spinner,
        val pollInterval: EditText,
        val sleepPoll: EditText,
        val sleepJitter: EditText,
        val idle: EditText,
    )

    fun addTo(
        context: Context,
        root: LinearLayout,
        defaultRole: String,
        defaultPollMs: String,
    ): Holder {
        fun caption(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, 8, 0, 4)
        }

        fun field(hint: String): EditText = EditText(context).apply {
            this.hint = hint
            textSize = 16f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val heading = TextView(context).apply {
            text = "Polling / role"
            textSize = 13f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 16, 0, 4)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val role = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("primary", "backup"),
            )
        }
        val pollInterval = field("Active poll interval (ms)")
        val sleepPoll = field("Sleep poll interval (ms)")
        val sleepJitter = field("Sleep jitter (ms)")
        val idle = field("Backup idle before sleep (ms)")

        root.addView(heading)
        root.addView(caption("Role"))
        root.addView(role)
        root.addView(caption("Active poll interval (ms)"))
        root.addView(pollInterval)
        root.addView(caption("Sleep poll interval (ms)"))
        root.addView(sleepPoll)
        root.addView(caption("Sleep jitter (ms)"))
        root.addView(sleepJitter)
        root.addView(caption("Backup idle before sleep (ms)"))
        root.addView(idle)

        val holder = Holder(role, pollInterval, sleepPoll, sleepJitter, idle)
        apply(holder, emptyMap(), defaultRole, defaultPollMs)
        return holder
    }

    fun read(h: Holder): Map<String, String> = mapOf(
        "role" to (h.role.selectedItem?.toString() ?: "backup"),
        "pollIntervalMs" to h.pollInterval.text.toString().trim(),
        "sleepPollMs" to h.sleepPoll.text.toString().trim(),
        "sleepJitterMs" to h.sleepJitter.text.toString().trim(),
        "idleMs" to h.idle.text.toString().trim(),
    )

    fun apply(
        h: Holder,
        config: Map<String, String>,
        defaultRole: String,
        defaultPollMs: String,
    ) {
        val role = config["role"] ?: defaultRole
        h.role.setSelection(if (role == "primary") 0 else 1)
        h.pollInterval.setText(config["pollIntervalMs"] ?: defaultPollMs)
        h.sleepPoll.setText(config["sleepPollMs"] ?: "3600000")
        h.sleepJitter.setText(config["sleepJitterMs"] ?: "900000")
        h.idle.setText(config["idleMs"] ?: "600000")
    }
}
