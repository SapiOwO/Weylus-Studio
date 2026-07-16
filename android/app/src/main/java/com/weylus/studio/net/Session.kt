package com.weylus.studio.net

import android.util.Log
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

/**
 * Telemetry container tracking the latency of each pipeline stage for a single frame.
 *
 * Pipeline: Capture (PC) → Encode (PC) → Network → [receiveTimestampUs] → Decode → Present
 *
 * Glass-to-glass latency = presentTimestampUs - captureTimestampUs (if server embeds capture time).
 * Network latency (approximation) = receiveTimestampUs - captureTimestampUs.
 * Decode latency = decodeTimestampUs - receiveTimestampUs.
 * Present latency = presentTimestampUs - decodeTimestampUs.
 */
data class FrameTiming(
    /** Monotonic microsecond timestamp when the binary frame arrived from the network. */
    val receiveTimestampUs: Long,
    /** Monotonic microsecond timestamp when MediaCodec finished decoding (set externally). */
    var decodeTimestampUs: Long = 0L,
    /** Monotonic microsecond timestamp when the frame was presented to the display surface. */
    var presentTimestampUs: Long = 0L,
    /** Number of bytes in the encoded frame payload. */
    val frameSizeBytes: Int = 0
) {
    /** Returns total network+decode+present latency in milliseconds, or -1 if incomplete. */
    fun totalLatencyMs(): Float {
        if (decodeTimestampUs == 0L || presentTimestampUs == 0L) return -1f
        return (presentTimestampUs - receiveTimestampUs) / 1000f
    }

    /** Returns decode latency in milliseconds, or -1 if incomplete. */
    fun decodeLatencyMs(): Float {
        if (decodeTimestampUs == 0L) return -1f
        return (decodeTimestampUs - receiveTimestampUs) / 1000f
    }

    /** Returns present latency (scheduler → surface) in milliseconds, or -1 if incomplete. */
    fun presentLatencyMs(): Float {
        if (decodeTimestampUs == 0L || presentTimestampUs == 0L) return -1f
        return (presentTimestampUs - decodeTimestampUs) / 1000f
    }

    fun log(tag: String = "FrameTiming") {
        Log.d(
            tag,
            "FrameTiming | size=${frameSizeBytes}B | " +
                "decode=${String.format("%.2f", decodeLatencyMs())}ms | " +
                "present=${String.format("%.2f", presentLatencyMs())}ms | " +
                "total=${String.format("%.2f", totalLatencyMs())}ms"
        )
    }
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

    // Telemetry: tracks the most recent frame's timing across the pipeline.
    @Volatile
    var lastFrameTiming: FrameTiming? = null
        private set

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
        val wrapped = PointerEventMessage(event)
        val text = json.encodeToString(wrapped)
        transport.sendText(text)
    }

    fun sendDisplayChanged(width: Int, height: Int, rotation: Int) {
        if (state != SessionState.STREAMING) return
        val event = DisplayChanged(width = width, height = height, rotation = rotation)
        val wrapped = DisplayChangedMessage(event)
        val text = json.encodeToString(wrapped)
        transport.sendText(text)
    }

    /**
     * Called by [com.weylus.studio.video.MediaCodecDecoder] after a frame finishes decoding.
     * Updates [lastFrameTiming] with the decode completion timestamp.
     */
    fun onFrameDecoded(decodeTimestampUs: Long) {
        lastFrameTiming?.decodeTimestampUs = decodeTimestampUs
    }

    /**
     * Called by [com.weylus.studio.video.ChoreographerFrameScheduler] after a frame is presented
     * to the display surface. Completes [lastFrameTiming] and emits a log entry.
     */
    fun onFramePresented(presentTimestampUs: Long) {
        val timing = lastFrameTiming ?: return
        timing.presentTimestampUs = presentTimestampUs
        timing.log()
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
        // Move immediately to STREAMING so MirrorCanvas (and the decoder) is ready
        // before the first video frame arrives from the server.
        updateState(SessionState.NEGOTIATING)
        
        val disp = capabilities?.display
        val targetWidth = disp?.width ?: 1920
        val targetHeight = disp?.height ?: 1080
        val targetFps = disp?.refresh_rate?.toDouble() ?: 60.0

        Log.i("Session", "[WEYLUS] Handshake with display capability: ${targetWidth}x${targetHeight} @ ${targetFps}Hz")
        listener?.onVideoConfigReceived(targetWidth, targetHeight)

        val handshake = ClientConfiguration(
            uinput_support = true,
            capturable_id = 0,
            capture_cursor = true,
            max_width = targetWidth,
            max_height = targetHeight,
            client_name = clientName,
            frame_rate = targetFps,
            capabilities = capabilities
        )
        val wrapped = ConfigMessage(handshake)
        transport.sendText(json.encodeToString(wrapped))

        // Ask server for a fresh keyframe after config is set.
        // ResumeVideo is a Rust unit enum variant — serde serializes it as the bare string "ResumeVideo".
        transport.sendText("\"ResumeVideo\"")

        // Transition now so MirrorCanvas is mounted before the first binary frame arrives.
        updateState(SessionState.STREAMING)
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
        // Ensure we are in STREAMING state (redundant guard, state is set in onConnected now).
        if (state == SessionState.NEGOTIATING) {
            updateState(SessionState.STREAMING)
        }
        if (state != SessionState.STREAMING && state != SessionState.RECOVERING) return

        // Record receive timestamp for this frame immediately on arrival.
        lastFrameTiming = FrameTiming(
            receiveTimestampUs = System.nanoTime() / 1_000L,
            frameSizeBytes = bytes.size
        )
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
