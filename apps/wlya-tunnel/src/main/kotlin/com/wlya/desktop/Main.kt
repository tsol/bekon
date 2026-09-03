package com.wlya.desktop

fun main() {
    val server = ApiServer(port = System.getenv("WLYA_PORT")?.toIntOrNull() ?: 18080)
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            println("Shutting down WLYA desktop server...")
            server.stop()
        }
    })
    server.start()
}