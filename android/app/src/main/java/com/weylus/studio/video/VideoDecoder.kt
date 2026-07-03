package com.weylus.studio.video

import android.view.Surface

interface DecoderListener {
    fun onFrameDecoded(timestampUs: Long)
    fun onError(error: Throwable)
}

interface VideoDecoder {
    fun configure(surface: Surface, width: Int, height: Int, listener: DecoderListener)
    fun feedPacket(bytes: ByteArray, timestampUs: Long)
    fun flush()
    fun release()
    val codecType: CodecType
}

enum class CodecType {
    H264_AVC,
    H265_HEVC,
    AV1
}
