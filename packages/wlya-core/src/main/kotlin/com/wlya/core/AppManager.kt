package com.wlya.core

import java.util.UUID

/**
 * Multi-tunnel manager with UI state and persistence.
 *
 * Shared across JVM (desktop) and Android; takes any [Store] implementation
 * (FileStore on the JVM, AndroidStore on Android).
 */
class AppManager(private val store: Store) {
    private val views = mutableMapOf<String, TunnelView>()
    private var inited = false

    suspend fun ensureInit() {
        if (inited) return
        inited = true
        loadAll()
    }

    suspend fun list(): List<TunnelListItem> {
        ensureInit()
        return views.values.map { v ->
            TunnelListItem(
                id = v.tunnel.config.id,
                label = v.tunnel.config.label,
                channel = v.tunnel.config.channel,
                running = v.tunnel.running,
                autostart = v.tunnel.config.autostart,
                adapters = v.tunnel.adapterListItems(),
            )
        }
    }

    suspend fun create(label: String, channel: String? = null, secret: String? = null): TunnelView {
        ensureInit()
        val id = "tunnel-${System.currentTimeMillis()}-${(1..4).map { ('a'..'z').random() }.joinToString("")}"
        val config = TunnelConfig(
            id = id,
            label = label,
            channel = channel ?: UUID.randomUUID().toString().replace("-", "").take(32),
            secret = secret ?: "",
            clientId = "",
        )
        store.setObject("tunnel:$id", config)

        val tunnelList = store.getObject<List<String>>("tunnels") ?: emptyList()
        store.setObject("tunnels", tunnelList + id)

        return buildView(config)
    }

    suspend fun delete(id: String) {
        ensureInit()
        val view = views[id]
        if (view != null && view.tunnel.running) {
            view.tunnel.stop()
        }
        views.remove(id)
        store.remove("tunnel:$id")
        store.remove("view:$id")

        val tunnelList = store.getObject<List<String>>("tunnels") ?: emptyList()
        store.setObject("tunnels", tunnelList.filter { it != id })
    }

    /** Drop every tunnel except [id]. Used so phone resume cannot pick a stale channel. */
    suspend fun keepOnly(id: String) {
        ensureInit()
        val listed = (store.getObject<List<String>>("tunnels") ?: emptyList()) + views.keys
        for (other in listed.distinct()) {
            if (other != id) delete(other)
        }
    }

    fun get(id: String): TunnelView? = views[id]

    private suspend fun loadAll() {
        val tunnelList = store.getObject<List<String>>("tunnels") ?: emptyList()
        val toStart = mutableListOf<TunnelView>()
        for (id in tunnelList) {
            val config = store.getObject<TunnelConfig>("tunnel:$id")
            if (config != null) {
                val view = buildView(config)
                if (config.autostart) toStart.add(view)
            }
        }
        for (view in toStart) {
            try {
                view.tunnel.start()
            } catch (e: Exception) {
                System.err.println("autostart failed for ${view.tunnel.config.id}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun buildView(config: TunnelConfig): TunnelView {
        val tunnel = Tunnel(
            store = store,
            handlers = object : TunnelHandlers {
                override fun onMessage(msg: TunnelMessage, direction: String) {}
                override fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String) {}
            },
            config = config,
        )
        val view = TunnelView(tunnel)
        tunnel.handlers = view.handlers
        views[config.id] = view
        return view
    }
}
