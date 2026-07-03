package com.weylus.studio.net

import com.weylus.studio.net.protocol.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

interface SessionListener {
    fun onStateChanged(state: SessionState)
    fun onVideoConfigReceived(width: Int, height: Int)
    fun onVideoFrameReceived(bytes: ByteArray)
    fun onError(message: String)
}

enum class SessionState {
    DISCONNECTED,
    CONNECTING,
    NEGOTIATING,
    STREAMING,
    RECOVERING,
    DISCONNECTING
}

class Session(
    private val transport: Transport,
    private val clientName: String = "Android Native Client"
) : TransportListener {
    private var listener: SessionListener? = null
    private var state = SessionState.DISCONNECTED
    private var host: String = "127.0.0.1"
    private var port: Int = 1701
    private var capabilities: ClientCapabilities? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun start(
        host: String,
        port: Int,
        capabilities: ClientCapabilities,
        listener: SessionListener
    ) {
        this.listener = listener
        this.host = host
        this.port = port
        this.capabilities = capabilities
        updateState(SessionState.CONNECTING)
        transport.connect(host, port, this)
    }

    fun sendPointerEvent(event: PointerEvent) {
        if (state != SessionState.STREAMING) return
        val msg = MessageInbound(
            type = "PointerEvent",
            is_primary = event.is_primary,
            pointer_type = event.pointer_type,
            event_type = event.event_type,
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            tilt_x = event.tilt_x,
            tilt_y = event.tilt_y
        )
        val text = json.encodeToString(msg)
        transport.sendText(text)
    }

    fun sendDisplayChanged(width: Int, height: Int, rotation: Int) {
        if (state != SessionState.STREAMING) return
        val msg = MessageInbound(
            type = "DisplayChanged",
            width = width,
            height = height,
            rotation = rotation
        )
        val text = json.encodeToString(msg)
        transport.sendText(text)
    }

    fun stop() {
        updateState(SessionState.DISCONNECTING)
        transport.disconnect()
        updateState(SessionState.DISCONNECTED)
    }

    private fun updateState(newState: SessionState) {
        state = newState
        listener?.onStateChanged(newState)
    }

    override fun onConnected() {
        updateState(SessionState.NEGOTIATING)
        val handshake = MessageInbound(
            type = "ClientConfiguration",
            uinput_support = true,
            capturable_id = 0,
            capture_cursor = true,
            max_width = 1920,
            max_height = 1080,
            client_name = clientName,
            frame_rate = 60.0,
            capabilities = capabilities
        )
        val text = json.encodeToString(handshake)
        transport.sendText(text)
    }

    override fun onDisconnected(reason: String?) {
        if (state == SessionState.STREAMING || state == SessionState.NEGOTIATING) {
            updateState(SessionState.RECOVERING)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (state == SessionState.RECOVERING) {
                    transport.connect(host, port, this)
                }
            }, 2000)
        } else if (state == SessionState.RECOVERING) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (state == SessionState.RECOVERING) {
                    transport.connect(host, port, this)
                }
            }, 2000)
        } else {
            updateState(SessionState.DISCONNECTED)
            listener?.onError(reason ?: "Connection closed")
        }
    }

    override fun onBinaryMessageReceived(bytes: ByteArray) {
        if (state == SessionState.NEGOTIATING) {
            updateState(SessionState.STREAMING)
        }
        listener?.onVideoFrameReceived(bytes)
    }

    override fun onTextMessageReceived(text: String) {
        try {
            if (state == SessionState.NEGOTIATING) {
                updateState(SessionState.STREAMING)
            }
        } catch (e: Exception) {
            listener?.onError("Failed to parse text message: ${e.message}")
        }
    }
}
