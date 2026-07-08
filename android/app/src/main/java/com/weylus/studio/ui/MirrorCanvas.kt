package com.weylus.studio.ui

import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.weylus.studio.input.CoordinateMapper
import com.weylus.studio.net.protocol.PointerEvent
import com.weylus.studio.net.protocol.PointerEventType
import com.weylus.studio.net.protocol.PointerType
import com.weylus.studio.video.DecoderListener
import com.weylus.studio.video.FrameScheduler
import com.weylus.studio.video.MediaCodecDecoder

@Composable
fun MirrorCanvas(
    decoder: MediaCodecDecoder,
    scheduler: FrameScheduler,
    onPointerEvent: (PointerEvent) -> Unit,
    onError: (String) -> Unit
) {
    val mapper = CoordinateMapper()

    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        mapper.updateViewport(1920, 1080, width, height)

                        decoder.configure(
                            surface = holder.surface,
                            width = 1920,
                            height = 1080,
                            scheduler = scheduler,
                            listener = object : DecoderListener {
                                override fun onFrameDecoded(timestampUs: Long) {}
                                override fun onError(error: Throwable) {
                                    onError(error.message ?: "Decoder error")
                                }
                            }
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        mapper.updateViewport(1920, 1080, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        decoder.release()
                    }
                })

                setOnTouchListener { _, event ->
                    val pointerType = when (event.getToolType(0)) {
                        MotionEvent.TOOL_TYPE_STYLUS -> PointerType.Pen
                        MotionEvent.TOOL_TYPE_FINGER -> PointerType.Touch
                        else -> PointerType.Mouse
                    }

                    val eventType = when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> PointerEventType.Down
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> PointerEventType.Up
                        MotionEvent.ACTION_MOVE -> PointerEventType.Move
                        MotionEvent.ACTION_CANCEL -> PointerEventType.Cancel
                        else -> null
                    }

                    if (eventType != null) {
                        val point = mapper.map(event.x, event.y)

                        val pointerEvent = PointerEvent(
                            is_primary = event.actionIndex == 0,
                            pointer_type = pointerType,
                            event_type = eventType,
                            x = point.x,
                            y = point.y,
                            pressure = event.pressure,
                            tilt_x = event.getAxisValue(MotionEvent.AXIS_TILT, 0),
                            tilt_y = 0.0f
                        )
                        onPointerEvent(pointerEvent)
                    }
                    true
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
