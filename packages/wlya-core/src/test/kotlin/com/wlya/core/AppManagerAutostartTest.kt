package com.wlya.core

import com.wlya.core.adapters.registerAllAdapters
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppManagerAutostartTest {
    @BeforeEach
    fun setup() { registerAllAdapters() }

    @Test
    fun `autostart tunnel starts on loadAll`() = runBlocking {
        val tmp = File.createTempFile("wlya-autostart", ".json").apply { deleteOnExit() }
        val store = FileStore(tmp.absolutePath)
        val mgr = AppManager(store)
        val view = mgr.create("auto-one")
        val id = view.tunnel.config.id
        view.tunnel.updateConfig(mapOf("autostart" to "true"))
        assertTrue(view.tunnel.config.autostart)
        assertFalse(view.tunnel.running)

        val mgr2 = AppManager(store)
        mgr2.ensureInit()
        val loaded = mgr2.get(id)!!
        assertTrue(loaded.tunnel.config.autostart)
        assertTrue(loaded.tunnel.running)
        loaded.tunnel.stop()
    }

    @Test
    fun `no autostart stays stopped on loadAll`() = runBlocking {
        val tmp = File.createTempFile("wlya-noauto", ".json").apply { deleteOnExit() }
        val store = FileStore(tmp.absolutePath)
        val mgr = AppManager(store)
        val view = mgr.create("manual")
        val id = view.tunnel.config.id
        view.tunnel.start()
        view.tunnel.stop()
        assertFalse(view.tunnel.config.autostart)

        val mgr2 = AppManager(store)
        mgr2.ensureInit()
        val loaded = mgr2.get(id)!!
        assertFalse(loaded.tunnel.running)
    }
}
