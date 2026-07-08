package com.weylus.studio.net

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class WebSocketTransport(override val transportType: TransportType = TransportType.WEBSOCKET_ADB) : Transport {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var listener: TransportListener? = null
    private var connected = false

    override val isConnected: Boolean
        get() = connected

    override fun connect(host: String, port: Int, listener: TransportListener) {
        this.listener = listener
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val url = "ws://$host:$port/"

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onTextMessageReceived(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onBinaryMessageReceived(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                listener.onDisconnected(t.message)
            }
        })
    }

    override fun sendBinary(bytes: ByteArray): Boolean {
        val webSock = webSocket
        if (webSock != null) {
            return webSock.send(bytes.toByteString())
        }
        return false
    }

    override fun sendText(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }

    override fun disconnect() {
        webSocket?.close(1000, "Manual disconnect")
        client?.dispatcher?.executorService?.shutdown()
        connected = false
    }
}
