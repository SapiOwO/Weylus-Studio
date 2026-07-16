package com.weylus.studio.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.util.Log

class MediaCodecDecoder : VideoDecoder {
    private var codec: MediaCodec? = null
    private var isConfigured = false
    private var listener: DecoderListener? = null
    private var scheduler: FrameScheduler? = null
    private var renderHandlerThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var decodingRunning = false

    override val codecType: CodecType
        get() = CodecType.H264_AVC

    override fun configure(surface: Surface, width: Int, height: Int, listener: DecoderListener) {
        // Fallback overload — not used in primary path
    }

    fun configure(
        surface: Surface,
        width: Int,
        height: Int,
        scheduler: FrameScheduler,
        listener: DecoderListener
    ) {
        this.listener = listener
        this.scheduler = scheduler
        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height)

            // Low-latency decoding: disable B-frame reordering, real-time priority
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)

            codec = MediaCodec.createDecoderByType("video/avc").apply {
                configure(format, surface, null, 0)
                start()
            }

            isConfigured = true
            this.scheduler?.start()
            startOutputLoop()
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    override fun feedPacket(bytes: ByteArray, timestampUs: Long) {
        val activeCodec = codec ?: return
        if (!isConfigured) return

        try {
            // Non-blocking dequeue — skip this packet if no input buffer is available.
            // This avoids stalling the network receiver thread.
            val inputBufferId = activeCodec.dequeueInputBuffer(0)
            if (inputBufferId >= 0) {
                val inputBuffer = activeCodec.getInputBuffer(inputBufferId)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(bytes)
                    activeCodec.queueInputBuffer(inputBufferId, 0, bytes.size, timestampUs, 0)
                }
            }
        } catch (e: Exception) {
            Log.w("MediaCodecDecoder", "[WEYLUS_DBG] feedPacket error: ${e.message}")
            listener?.onError(e)
        }
    }

    /**
     * Runs a tight output-drain loop on a dedicated thread.
     *
     * Design:
     * - dequeueOutputBuffer with a short timeout keeps the loop reactive.
     * - releaseOutputBuffer(id, true) is called immediately on the decoder thread.
     *   This is safe — Android MediaCodec allows releaseOutputBuffer from any thread.
     * - We intentionally bypass Choreographer V-Sync alignment here to minimise
     *   glass-to-glass latency. The hardware composer handles the actual display timing.
     */
    private fun startOutputLoop() {
        renderHandlerThread = HandlerThread("WeylusDecoderOut", android.os.Process.THREAD_PRIORITY_DISPLAY).apply {
            start()
            decodingRunning = true
            renderHandler = Handler(looper)
            renderHandler?.post(object : Runnable {
                override fun run() {
                    if (!decodingRunning) return
                    val activeCodec = codec
                    if (activeCodec != null && isConfigured) {
                        try {
                            val bufferInfo = MediaCodec.BufferInfo()
                            // Block up to 10ms for output — keeps CPU idle between frames.
                            val outputBufferId = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                            when {
                                outputBufferId >= 0 -> {
                                    val decodeTimestampUs = System.nanoTime() / 1_000L
                                    listener?.onFrameDecoded(decodeTimestampUs)

                                    // Release directly to surface — this is the render call.
                                    // render=true tells MediaCodec to push this frame to the Surface.
                                    activeCodec.releaseOutputBuffer(outputBufferId, true)
                                    Log.d("MediaCodecDecoder", "[WEYLUS_DBG] Frame rendered pts=${bufferInfo.presentationTimeUs}us")
                                }
                                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val newFormat = activeCodec.outputFormat
                                    Log.i("MediaCodecDecoder", "[WEYLUS_DBG] Output format changed: $newFormat")
                                }
                                outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                    // Timeout — no frame ready, loop will retry
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MediaCodecDecoder", "[WEYLUS_DBG] Output loop error: ${e.message}")
                        }
                    }
                    if (decodingRunning) {
                        renderHandler?.post(this)
                    }
                }
            })
        }
    }

    override fun flush() {
        try {
            codec?.flush()
            Log.d("MediaCodecDecoder", "[WEYLUS_DBG] Codec flushed")
        } catch (e: Exception) {
            Log.w("MediaCodecDecoder", "[WEYLUS_DBG] flush error: ${e.message}")
        }
    }

    override fun release() {
        decodingRunning = false
        scheduler?.stop()
        renderHandlerThread?.quitSafely()
        renderHandlerThread = null
        renderHandler = null
        try {
            codec?.stop()
            codec?.release()
            Log.d("MediaCodecDecoder", "[WEYLUS_DBG] Codec released")
        } catch (e: Exception) {
            Log.w("MediaCodecDecoder", "[WEYLUS_DBG] release error: ${e.message}")
        } finally {
            codec = null
            isConfigured = false
        }
    }
}
