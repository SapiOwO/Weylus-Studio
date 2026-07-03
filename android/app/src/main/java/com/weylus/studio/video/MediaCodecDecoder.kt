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
        // Fallback overload
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
            
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            
            codec = MediaCodec.createDecoderByType("video/avc").apply {
                configure(format, surface, null, 0)
                start()
            }
            
            isConfigured = true
            this.scheduler?.start()
            startDecodingLoop()
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    override fun feedPacket(bytes: ByteArray, timestampUs: Long) {
        val activeCodec = codec ?: return
        if (!isConfigured) return

        try {
            val inputBufferId = activeCodec.dequeueInputBuffer(5000)
            if (inputBufferId >= 0) {
                val inputBuffer = activeCodec.getInputBuffer(inputBufferId)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(bytes)
                    activeCodec.queueInputBuffer(inputBufferId, 0, bytes.size, timestampUs, 0)
                }
            }
        } catch (e: Exception) {
            Log.e("MediaCodecDecoder", "Error feeding packet: ${e.message}")
            listener?.onError(e)
        }
    }

    private fun startDecodingLoop() {
        renderHandlerThread = HandlerThread("DecoderOutputThread").apply {
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
                            val outputBufferId = activeCodec.dequeueOutputBuffer(bufferInfo, 2000)
                            if (outputBufferId >= 0) {
                                // Delegate render callback to FrameScheduler instead of direct rendering
                                scheduler?.onFrameAvailable(bufferInfo.presentationTimeUs) {
                                    try {
                                        activeCodec.releaseOutputBuffer(outputBufferId, true)
                                        listener?.onFrameDecoded(bufferInfo.presentationTimeUs)
                                    } catch (e: Exception) {
                                        Log.e("MediaCodecDecoder", "Error releasing buffer on render: ${e.message}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MediaCodecDecoder", "Error dequeuing output buffer: ${e.message}")
                        }
                    }
                    if (decodingRunning) {
                        renderHandler?.postDelayed(this, 1)
                    }
                }
            })
        }
    }

    override fun flush() {
        try {
            codec?.flush()
        } catch (e: Exception) {
            Log.e("MediaCodecDecoder", "Error flushing codec: ${e.message}")
        }
    }

    override fun release() {
        decodingRunning = false
        scheduler?.stop()
        renderHandlerThread?.quitSafely()
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e("MediaCodecDecoder", "Error releasing codec: ${e.message}")
        } finally {
            codec = null
            isConfigured = false
        }
    }
}
