package com.weylus.studio.net

interface TransportListener {
    fun onConnected()
    fun onDisconnected(reason: String?)
    fun onBinaryMessageReceived(bytes: ByteArray)
    fun onTextMessageReceived(text: String)
}

interface Transport {
    fun connect(host: String, port: Int, listener: TransportListener)
    fun sendBinary(bytes: ByteArray): Boolean
    fun sendText(text: String): Boolean
    fun disconnect()
    val isConnected: Boolean
    val transportType: TransportType
}

enum class TransportType {
    WEBSOCKET_ADB,
    WEBSOCKET_WIFI,
    USB_BULK_AOA,
    QUIC
}
