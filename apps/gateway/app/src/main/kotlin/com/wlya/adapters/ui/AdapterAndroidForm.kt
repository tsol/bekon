package com.wlya.adapters.ui

import android.content.Context
import android.view.View

/**
 * Programmatic Android settings form for a WLYA transport adapter type.
 * Implementations live co-located under adapters/&lt;type&gt;/ui-android/.
 */
interface AdapterAndroidForm {
    val adapterType: String

    fun createView(context: Context, initial: Map<String, String>): View

    fun readConfig(view: View): Map<String, String>

    fun applyConfig(view: View, config: Map<String, String>)
}
