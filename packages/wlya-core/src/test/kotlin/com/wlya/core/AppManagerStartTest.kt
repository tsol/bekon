package com.wlya.core

import com.wlya.core.adapters.registerAllAdapters
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class AppManagerStartTest {
    @BeforeEach
    fun setup() { registerAllAdapters() }

    @Test
    fun `start via AppManager with email adapter`() = runBlocking {
        val tmp = File.createTempFile("wlya-am", ".json").apply { deleteOnExit() }
        val store = FileStore(tmp.absolutePath)
        val mgr = AppManager(store)
        val view = mgr.create("desktop", "test-channel")
        view.tunnel.addAdapter(AdapterInstanceConfig(
            type = "email",
            id = "email-1786307009851",
            label = "megaboots@mail.ru",
            config = mapOf(
                "host" to "imap.mail.ru",
                "port" to "993",
                "login" to "megaboots@mail.ru",
                "password" to "lbxZXU6r8zQ9i7K6gMGe",
                "useSSL" to "true",
            )
        ))
        val clientId = view.tunnel.start()
        println("started clientId=$clientId running=${view.tunnel.running}")
        assertTrue(view.tunnel.running)
        view.tunnel.stop()
    }
}
