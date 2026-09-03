package com.wlya.core.adapters.mock.ui.android

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.wlya.adapters.ui.AdapterAndroidForm
import com.wlya.adapters.ui.AdapterDutySection

class MockAdapterForm : AdapterAndroidForm {
    override val adapterType = "mock"

    override fun createView(context: Context, initial: Map<String, String>): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(context).apply {
            text = "In-memory adapter for local tests."
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 8, 0, 8)
        })
        val duty = AdapterDutySection().addTo(context, root, "backup", "2000")
        root.tag = duty
        applyConfig(root, initial)
        return root
    }

    override fun readConfig(view: View): Map<String, String> {
        val duty = view.tag as AdapterDutySection.Holder
        return AdapterDutySection().read(duty)
    }

    override fun applyConfig(view: View, config: Map<String, String>) {
        val duty = view.tag as AdapterDutySection.Holder
        AdapterDutySection().apply(duty, config, "backup", "2000")
    }
}
