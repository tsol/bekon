package com.wlya.core.adapters

import com.wlya.core.BaseAdapter

/**
 * Registry of adapter types. Manifests are registered via generated [registerAllAdapters].
 */
data class AdapterManifest(
    val type: String,
    val label: String,
    val defaultConfig: Map<String, Any> = emptyMap(),
    val factory: (config: Map<String, Any>) -> BaseAdapter,
)

object Registry {
    private val manifests = mutableMapOf<String, AdapterManifest>()

    fun register(manifest: AdapterManifest) {
        manifests[manifest.type] = manifest
    }

    fun get(type: String): AdapterManifest? = manifests[type]

    fun list(): List<AdapterManifest> = manifests.values.toList()

    fun createAdapter(type: String, config: Map<String, Any>): BaseAdapter {
        val manifest = manifests[type] ?: throw IllegalArgumentException("Unknown adapter type: $type")
        return manifest.factory(config)
    }

    fun getDefaultConfig(type: String): Map<String, Any> {
        val manifest = manifests[type] ?: throw IllegalArgumentException("Unknown adapter type: $type")
        return manifest.defaultConfig.toMap()
    }
}
