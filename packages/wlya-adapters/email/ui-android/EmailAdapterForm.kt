package com.wlya.core.adapters.email.ui.android

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.wlya.adapters.ui.AdapterAndroidForm
import com.wlya.adapters.ui.AdapterDutySection

private data class EmailFormHolder(
    val label: EditText,
    val sendMode: Spinner,
    val imapFolder: EditText,
    val host: EditText,
    val port: EditText,
    val smtpSection: LinearLayout,
    val smtpHost: EditText,
    val smtpPort: EditText,
    val smtpTo: EditText,
    val login: EditText,
    val password: EditText,
    val ttl: EditText,
    val windowSize: EditText,
    val useSsl: CheckBox,
    val smtpUseSsl: CheckBox,
    val duty: AdapterDutySection.Holder,
)

class EmailAdapterForm : AdapterAndroidForm {
    override val adapterType = "email"

    override fun createView(context: Context, initial: Map<String, String>): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun label(text: String): TextView = TextView(context).apply {
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

        val labelField = field("Display name")
        val sendMode = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("smtp", "imap"),
            )
        }
        val imapFolder = field("IMAP folder (INBOX)")
        val host = field("IMAP host")
        val port = field("IMAP port", InputType.TYPE_CLASS_NUMBER)
        val smtpSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val smtpHost = field("SMTP host")
        val smtpPort = field("SMTP port", InputType.TYPE_CLASS_NUMBER)
        val smtpTo = field("SMTP To")
        val login = field("Login", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val ttl = field("Delete messages older than (sec)", InputType.TYPE_CLASS_NUMBER)
        val windowSize = field("Max packet bytes", InputType.TYPE_CLASS_NUMBER)
        val useSsl = CheckBox(context).apply { text = "IMAP SSL/TLS" }
        val smtpUseSsl = CheckBox(context).apply { text = "SMTP SSL/TLS" }

        smtpSection.addView(label("SMTP host"))
        smtpSection.addView(smtpHost)
        smtpSection.addView(label("SMTP port"))
        smtpSection.addView(smtpPort)
        smtpSection.addView(label("SMTP To"))
        smtpSection.addView(smtpTo)
        smtpSection.addView(smtpUseSsl)

        root.addView(label("Display name"))
        root.addView(labelField)
        root.addView(label("Send mode"))
        root.addView(sendMode)
        root.addView(label("IMAP folder"))
        root.addView(imapFolder)
        root.addView(label("Host"))
        root.addView(host)
        root.addView(label("Port"))
        root.addView(port)
        root.addView(smtpSection)
        root.addView(label("Login"))
        root.addView(login)
        root.addView(label("Password"))
        root.addView(password)
        root.addView(label("TTL (seconds)"))
        root.addView(ttl)
        root.addView(label("Max packet size (bytes, default 262144)"))
        root.addView(windowSize)
        root.addView(useSsl)
        val dutyHolder = AdapterDutySection().addTo(context, root, "backup", "10000")

        val holder = EmailFormHolder(
            label = labelField,
            sendMode = sendMode,
            imapFolder = imapFolder,
            host = host,
            port = port,
            smtpSection = smtpSection,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpTo = smtpTo,
            login = login,
            password = password,
            ttl = ttl,
            windowSize = windowSize,
            useSsl = useSsl,
            smtpUseSsl = smtpUseSsl,
            duty = dutyHolder,
        )
        root.tag = holder

        sendMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val smtp = position == 0
                smtpSection.visibility = if (smtp) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        applyConfig(root, initial)
        return root
    }

    override fun readConfig(view: View): Map<String, String> {
        val h = view.tag as EmailFormHolder
        val sendMode = h.sendMode.selectedItem?.toString() ?: "smtp"
        val cfg = mutableMapOf(
            "label" to h.label.text.toString().trim(),
            "sendMode" to sendMode,
            "imapFolder" to h.imapFolder.text.toString().trim(),
            "host" to h.host.text.toString().trim(),
            "port" to h.port.text.toString().trim(),
            "login" to h.login.text.toString().trim(),
            "password" to h.password.text.toString(),
            "tunnelMessageTtlSeconds" to h.ttl.text.toString().trim(),
            "windowSize" to h.windowSize.text.toString().trim().ifEmpty { "262144" },
            "useSSL" to h.useSsl.isChecked.toString(),
        )
        cfg.putAll(AdapterDutySection().read(h.duty))
        if (sendMode == "smtp") {
            cfg["smtpHost"] = h.smtpHost.text.toString().trim()
            cfg["smtpPort"] = h.smtpPort.text.toString().trim()
            cfg["smtpTo"] = h.smtpTo.text.toString().trim()
            cfg["smtpUseSSL"] = h.smtpUseSsl.isChecked.toString()
        }
        return cfg
    }

    override fun applyConfig(view: View, config: Map<String, String>) {
        val h = view.tag as EmailFormHolder
        h.label.setText(config["label"] ?: "")
        val mode = config["sendMode"] ?: "smtp"
        h.sendMode.setSelection(if (mode == "imap") 1 else 0)
        h.imapFolder.setText(config["imapFolder"] ?: "INBOX")
        h.host.setText(config["host"] ?: "imap.mail.ru")
        h.port.setText(config["port"] ?: "993")
        h.smtpHost.setText(config["smtpHost"] ?: "smtp.mail.ru")
        h.smtpPort.setText(config["smtpPort"] ?: "465")
        h.smtpTo.setText(config["smtpTo"] ?: "")
        h.login.setText(config["login"] ?: "")
        h.password.setText(config["password"] ?: "")
        h.ttl.setText(config["tunnelMessageTtlSeconds"] ?: "900")
        h.windowSize.setText(config["windowSize"] ?: "262144")
        h.useSsl.isChecked = config["useSSL"]?.equals("true", ignoreCase = true) ?: true
        AdapterDutySection().apply(h.duty, config, "backup", "10000")
        h.smtpUseSsl.isChecked = config["smtpUseSSL"]?.equals("true", ignoreCase = true) ?: true
        val smtp = mode != "imap"
        h.smtpSection.visibility = if (smtp) View.VISIBLE else View.GONE
    }
}
