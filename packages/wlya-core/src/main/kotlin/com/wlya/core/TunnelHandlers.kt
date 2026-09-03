package com.wlya.core

/**
 * Callbacks invoked by [Tunnel] when messages arrive or debug data is available.
 */
interface TunnelHandlers {
    fun onMessage(msg: TunnelMessage, direction: String)
    fun onDebug(adapterName: String, tMsg: TransportMessage, decryptedJson: String)
}
