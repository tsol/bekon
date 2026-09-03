package com.wlya.desktop

import com.wlya.core.AppManager
import com.wlya.core.FileStore
import com.wlya.core.TunnelView
import com.wlya.core.adapters.Registry
import com.wlya.core.adapters.registerAllAdapters
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import kotlinx.coroutines.runBlocking
import java.util.UUID

class ApiServer(private val port: Int = 18080) {
    private lateinit var app: Javalin
    private lateinit var manager: AppManager

    fun start() {
        registerAllAdapters()
        val store = FileStore(StorePaths.desktopStoreFile())
        manager = AppManager(store)

        app = Javalin.create { cfg ->
            cfg.showJavalinBanner = false
            cfg.http.maxRequestSize = 12L * 1024 * 1024
        }.apply {
            exception(Exception::class.java) { e, ctx ->
                e.printStackTrace()
                ctx.status(500).json(mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            }
            exception(kotlinx.coroutines.CancellationException::class.java) { e, ctx ->
                e.printStackTrace()
                ctx.status(500).json(mapOf("error" to (e.message ?: "cancelled")))
            }
            // CORS
            before { ctx ->
                ctx.header("Access-Control-Allow-Origin", "*")
                ctx.header("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
                ctx.header("Access-Control-Allow-Headers", "Content-Type")
                if (ctx.method() == io.javalin.http.HandlerType.OPTIONS) {
                    ctx.status(204)
                }
            }

            // Adapter types
            get("/api/adapter-types") { ctx ->
                ctx.json(Registry.list().map { m ->
                    mapOf("type" to m.type, "label" to m.label, "defaultConfig" to m.defaultConfig)
                })
            }

            get("/api/adapter-types/{type}/schema") { ctx ->
                val manifest = Registry.get(ctx.pathParam("type"))
                if (manifest == null) {
                    ctx.status(404).json(mapOf("error" to "unknown adapter type"))
                    return@get
                }
                ctx.json(emptyMap<String, Any>())
            }

            // Tunnels CRUD
            get("/api/tunnels") { ctx ->
                ctx.json(runBlocking { manager.list() })
            }

            get("/api/tunnels/{id}") { ctx ->
                val view = getView(ctx)
                ctx.json(tunnelDetail(view))
            }

            post("/api/tunnels") { ctx ->
                val body = parseBody(ctx)
                val label = body["label"] as? String ?: "New Tunnel"
                val channel = (body["channel"] as? String) ?: (body["seed"] as? String)
                val secret = body["secret"] as? String
                val view = runBlocking { manager.create(label, channel, secret) }
                ctx.status(201).json(tunnelDetail(view))
            }

            delete("/api/tunnels/{id}") { ctx ->
                runBlocking { manager.delete(ctx.pathParam("id")) }
                ctx.json(mapOf("ok" to true))
            }

            patch("/api/tunnels/{id}/config") { ctx ->
                val view = getView(ctx)
                val body = parseBody(ctx)
                val patch = mutableMapOf<String, String>()
                ((body["channel"] as? String) ?: (body["seed"] as? String))?.let { patch["channel"] = it }
                (body["secret"] as? String)?.let { patch["secret"] = it }
                (body["label"] as? String)?.let { patch["label"] = it }
                when (val auto = body["autostart"]) {
                    is Boolean -> patch["autostart"] = auto.toString()
                    is String -> patch["autostart"] = auto
                    is Number -> patch["autostart"] = (auto.toInt() != 0).toString()
                }
                runBlocking { view.tunnel.updateConfig(patch) }
                ctx.json(tunnelDetail(view))
            }

            // Tunnel control
            post("/api/tunnels/{id}/start") { ctx ->
                val view = getView(ctx)
                val clientId = runBlocking { view.tunnel.start() }
                ctx.json(mapOf("clientId" to clientId, "running" to view.tunnel.running))
            }

            post("/api/tunnels/{id}/stop") { ctx ->
                val view = getView(ctx)
                try {
                    runBlocking { view.tunnel.stop() }
                } catch (_: kotlinx.coroutines.CancellationException) {
                }
                ctx.json(mapOf("ok" to true))
            }

            post("/api/tunnels/{id}/send") { ctx ->
                val view = getView(ctx)
                val body = parseBody(ctx)
                val plaintext = (body["plaintext"] as? String) ?: throw IllegalArgumentException("plaintext required")
                @Suppress("UNCHECKED_CAST")
                val attachments = body["attachments"] as? List<Map<String, String>>
                val seq = try {
                    runBlocking { view.tunnel.send(plaintext, attachments) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    ctx.status(503).json(mapOf("error" to "tunnel stopped"))
                    return@post
                } catch (e: Exception) {
                    ctx.status(500).json(mapOf("error" to (e.message ?: e.javaClass.simpleName)))
                    return@post
                }
                ctx.json(mapOf("seq" to seq))
            }

            post("/api/tunnels/{id}/advertise-adapters") { ctx ->
                val view = getView(ctx)
                val body = parseBody(ctx)
                @Suppress("UNCHECKED_CAST")
                val adapterIds = (body["adapterIds"] as? List<Any>)?.map { it.toString() }
                    ?: throw IllegalArgumentException("adapterIds required")
                val seq = runBlocking { view.tunnel.advertiseAdapters(adapterIds) }
                ctx.json(mapOf("seq" to seq))
            }

            // Messages
            get("/api/tunnels/{id}/messages") { ctx ->
                val view = getView(ctx)
                val full = ctx.queryParam("full") == "1" || ctx.queryParam("full") == "true"
                ctx.json(mapOf("messages" to view.getMessages(full)))
            }

            // Adapter management
            get("/api/tunnels/{id}/adapters") { ctx ->
                val view = getView(ctx)
                ctx.json(view.tunnel.adapterListItems())
            }

            post("/api/tunnels/{id}/adapters") { ctx ->
                val view = getView(ctx)
                val body = parseBody(ctx)
                val type = (body["type"] as? String)?.lowercase() ?: throw IllegalArgumentException("type required")
                val adapterId = (body["id"] as? String) ?: java.util.UUID.randomUUID().toString().takeLast(12)
                @Suppress("UNCHECKED_CAST")
                val rawConfig = (body["config"] as? Map<String, Any>) ?: emptyMap()
                val config: Map<String, String> = rawConfig.mapValues { it.value.toString() }
                val label = (body["label"] as? String) ?: adapterId
                runBlocking {
                    view.tunnel.addAdapter(
                        com.wlya.core.AdapterInstanceConfig(
                            type = type, id = adapterId, label = label, config = config,
                        )
                    )
                }
                ctx.json(mapOf("ok" to true, "id" to adapterId))
            }

            patch("/api/tunnels/{id}/adapters/{adapterId}") { ctx ->
                val view = getView(ctx)
                val body = parseBody(ctx)
                val config: Map<String, String> = body.mapValues { it.value.toString() }
                runBlocking { view.tunnel.updateAdapter(ctx.pathParam("adapterId"), config) }
                ctx.json(mapOf("ok" to true, "id" to ctx.pathParam("adapterId")))
            }

            delete("/api/tunnels/{id}/adapters/{adapterId}") { ctx ->
                val view = getView(ctx)
                runBlocking {
                    try {
                        view.tunnel.removeAdapter(ctx.pathParam("adapterId"))
                    } catch (_: IllegalArgumentException) {
                        // Silently ignore already-removed adapter
                    }
                }
                ctx.json(mapOf("ok" to true))
            }

            // Debug
            get("/api/tunnels/{id}/debug") { ctx ->
                val view = getView(ctx)
                ctx.json(view.getAllDebug())
            }

            get("/api/tunnels/{id}/debug/{adapterName}") { ctx ->
                val view = getView(ctx)
                val lastN = ctx.queryParam("last")?.toIntOrNull() ?: 10
                ctx.json(mapOf("messages" to view.getDebug(ctx.pathParam("adapterName"), lastN)))
            }

            get("/api/tunnels/{id}/adapter-log") { ctx ->
                val view = getView(ctx)
                ctx.json(view.tunnel.adapterLogs)
            }

            // Clear adapter history
            post("/api/tunnels/{id}/adapters/{adapterId}/clear") { ctx ->
                val view = getView(ctx)
                runBlocking { view.tunnel.clearAdapter(ctx.pathParam("adapterId")) }
                ctx.json(mapOf("ok" to true))
            }

            post("/api/tunnels/{id}/adapters/{adapterId}/start") { ctx ->
                val view = getView(ctx)
                runBlocking { view.tunnel.startAdapter(ctx.pathParam("adapterId")) }
                ctx.json(mapOf("ok" to true, "id" to ctx.pathParam("adapterId")))
            }

            post("/api/tunnels/{id}/adapters/{adapterId}/stop") { ctx ->
                val view = getView(ctx)
                runBlocking { view.tunnel.stopAdapter(ctx.pathParam("adapterId")) }
                ctx.json(mapOf("ok" to true, "id" to ctx.pathParam("adapterId")))
            }
        }.start(port)

        runBlocking { manager.ensureInit() }
        println("WLYA desktop API running on http://localhost:$port")
    }

    private fun tunnelDetail(view: TunnelView): Map<String, Any> {
        val c = view.tunnel.config
        return mapOf(
            "id" to c.id,
            "label" to c.label,
            "channel" to c.channel,
            "secret" to c.secret,
            "running" to view.tunnel.running,
            "autostart" to c.autostart,
            "clientId" to c.clientId,
        )
    }

    private fun getView(ctx: Context): TunnelView {
        runBlocking { manager.ensureInit() }
        val view = manager.get(ctx.pathParam("id"))
            ?: throw NotFoundResponse("tunnel not found")
        return view
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBody(ctx: Context): Map<String, Any> {
        val raw = ctx.body()
        if (raw.isBlank()) return emptyMap()
        return io.javalin.json.JavalinJackson.defaultMapper().readValue(raw, Map::class.java) as Map<String, Any>
    }

    fun stop() {
        app.stop()
    }
}