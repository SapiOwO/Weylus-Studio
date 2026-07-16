package com.weylus.studio.net

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

private const val TAG = "WeylusWS"

class WebSocketTransport(override val transportType: TransportType = TransportType.WEBSOCKET_ADB) : Transport {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var listener: TransportListener? = null
    private var connected = false

    override val isConnected: Boolean
        get() = connected

    override fun connect(host: String, port: Int, listener: TransportListener) {
        this.listener = listener
        val url = "ws://$host:$port/ws"
        Log.i(TAG, "[WEYLUS] Connecting to $url")

        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                Log.i(TAG, "[WEYLUS] Connected OK — HTTP ${response.code}")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "[WEYLUS] Text message (${text.length}B): ${text.take(200)}")
                listener.onTextMessageReceived(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "[WEYLUS] Binary frame received: ${bytes.size}B")
                listener.onBinaryMessageReceived(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "[WEYLUS] Server closing WebSocket — code=$code reason='$reason'")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                Log.w(TAG, "[WEYLUS] WebSocket closed — code=$code reason='$reason'")
                listener.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                Log.e(TAG, "[WEYLUS] WebSocket FAILURE — ${t.javaClass.simpleName}: ${t.message} | HTTP=${response?.code}")
                listener.onDisconnected("ERROR: ${t.message}")
            }
        })
    }

    override fun sendBinary(bytes: ByteArray): Boolean {
        val webSock = webSocket ?: run {
            Log.e(TAG, "[WEYLUS] sendBinary: no active WebSocket!")
            return false
        }
        return webSock.send(bytes.toByteString())
    }

    override fun sendText(text: String): Boolean {
        val webSock = webSocket ?: run {
            Log.e(TAG, "[WEYLUS] sendText: no active WebSocket! msg=$text")
            return false
        }
        val ok = webSock.send(text)
        if (!ok) Log.e(TAG, "[WEYLUS] sendText FAILED (buffer full?): $text")
        return ok
    }

    override fun disconnect() {
        Log.i(TAG, "[WEYLUS] Disconnect requested (Manual disconnect)")
        webSocket?.close(1000, "Manual disconnect")
        client?.dispatcher?.executorService?.shutdown()
        connected = false
    }
}
