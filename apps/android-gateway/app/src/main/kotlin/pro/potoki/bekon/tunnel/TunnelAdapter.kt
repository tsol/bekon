package pro.potoki.bekon.tunnel

/**
 * Adapter interface connecting the phone agent to Igor's tunnel.
 * Receives JSON commands, sends JSON results.
 */
interface TunnelAdapter {
    fun connect(onMessage: (String) -> Unit, onError: (String) -> Unit)
    fun send(message: String)
    fun disconnect()
}
