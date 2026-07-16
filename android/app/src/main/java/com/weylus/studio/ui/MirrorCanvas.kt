package com.weylus.studio.ui

import android.util.Log
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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment

private const val TAG = "WeylusInput"

@Composable
fun MirrorCanvas(
    decoder: MediaCodecDecoder,
    scheduler: FrameScheduler,
    videoWidth: Int,
    videoHeight: Int,
    onPointerEvent: (PointerEvent) -> Unit,
    onError: (String) -> Unit
) {
    val mapper = CoordinateMapper()
    val ratio = if (videoWidth > 0 && videoHeight > 0) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        1.7778f
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    // Allow stylus hover events to reach this view
                    isFocusable = true
                    isFocusableInTouchMode = true

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val w = width.takeIf { it > 0 } ?: videoWidth
                            val h = height.takeIf { it > 0 } ?: videoHeight
                            mapper.updateViewport(videoWidth, videoHeight, w, h)
                            Log.i(TAG, "[WEYLUS] Surface created: ${w}x${h} (Video source: ${videoWidth}x${videoHeight})")

                            decoder.configure(
                                surface = holder.surface,
                                width = videoWidth,
                                height = videoHeight,
                                scheduler = scheduler,
                                listener = object : DecoderListener {
                                    override fun onFrameDecoded(timestampUs: Long) {}
                                    override fun onError(error: Throwable) {
                                        Log.e(TAG, "[WEYLUS] Decoder error: ${error.message}")
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
                            mapper.updateViewport(videoWidth, videoHeight, width, height)
                            Log.d(TAG, "[WEYLUS] Surface changed: ${width}x${height}")
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            Log.i(TAG, "[WEYLUS] Surface destroyed")
                            decoder.release()
                        }
                    })

                    // Touch handler: handles finger, stylus touch/drag
                    setOnTouchListener { v, event ->
                        // Request unbuffered dispatch for minimal stylus latency
                        v.requestUnbufferedDispatch(event)
                        processMotionEvent(event, mapper, onPointerEvent)
                        true
                    }

                    // Hover handler: handles stylus hover ABOVE the surface (before touching)
                    // This is critical for Xiaomi Smart Pen hover detection
                    setOnHoverListener { v, event ->
                        v.requestUnbufferedDispatch(event)
                        processMotionEvent(event, mapper, onPointerEvent)
                        true
                    }

                    // Generic motion: catches any missed events (e.g. eraser button on stylus)
                    setOnGenericMotionListener { v, event ->
                        v.requestUnbufferedDispatch(event)
                        processMotionEvent(event, mapper, onPointerEvent)
                        true
                    }
                }
            },
            modifier = Modifier
                .wrapContentSize()
                .aspectRatio(ratio)
        )
    }
}

private fun processMotionEvent(
    event: MotionEvent,
    mapper: CoordinateMapper,
    onPointerEvent: (PointerEvent) -> Unit
) {
    val actionMasked = event.actionMasked
    val actionIndex = event.actionIndex

    // For MOVE and HOVER_MOVE, process ALL active pointers
    val pointerIndices = when (actionMasked) {
        MotionEvent.ACTION_MOVE,
        MotionEvent.ACTION_HOVER_MOVE -> (0 until event.pointerCount).toList()
        else -> listOf(actionIndex)
    }

    for (pointerIndex in pointerIndices) {
        val toolType = event.getToolType(pointerIndex)

        val pointerType = when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> PointerType.Pen
            MotionEvent.TOOL_TYPE_ERASER -> PointerType.Pen   // Eraser end of stylus
            MotionEvent.TOOL_TYPE_FINGER -> PointerType.Touch
            MotionEvent.TOOL_TYPE_MOUSE -> PointerType.Mouse
            else -> PointerType.Touch
        }

        val eventType = when (actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> PointerEventType.Down
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> PointerEventType.Up
            MotionEvent.ACTION_MOVE -> PointerEventType.Move
            MotionEvent.ACTION_CANCEL -> PointerEventType.Cancel
            MotionEvent.ACTION_HOVER_ENTER -> PointerEventType.Enter
            MotionEvent.ACTION_HOVER_EXIT -> PointerEventType.Leave
            MotionEvent.ACTION_HOVER_MOVE -> PointerEventType.Move
            else -> {
                Log.v(TAG, "[WEYLUS] Unhandled action: $actionMasked (tool=$toolType)")
                null
            }
        } ?: continue

        // Also batch historical points for smooth stylus strokes
        // Historical samples fill gaps between event deliveries at high pen sample rate
        val historicalCount = event.historySize
        for (h in 0 until historicalCount) {
            val hPoint = mapper.map(
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h)
            )
            val hPressure = event.getHistoricalPressure(pointerIndex, h)
            val hTiltRad = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h)
            val hOrientation = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, h)
            val hTiltX = (Math.toDegrees(hTiltRad.toDouble()) * Math.sin(hOrientation.toDouble())).toInt()
            val hTiltY = (Math.toDegrees(hTiltRad.toDouble()) * Math.cos(hOrientation.toDouble())).toInt()

            onPointerEvent(PointerEvent(
                event_type = PointerEventType.Move,
                pointer_id = event.getPointerId(pointerIndex).toLong(),
                timestamp = event.getHistoricalEventTime(h),
                is_primary = pointerIndex == 0,
                pointer_type = pointerType,
                button = 0,
                buttons = mapButtons(event, toolType),
                x = hPoint.x,
                y = hPoint.y,
                pressure = hPressure.toDouble().coerceIn(0.0, 1.0),
                tilt_x = hTiltX,
                tilt_y = hTiltY,
                twist = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, h).let {
                    (Math.toDegrees(it.toDouble()) + 180).toInt() % 360
                },
                width = event.getHistoricalTouchMajor(pointerIndex, h).toDouble().coerceAtLeast(1.0),
                height = event.getHistoricalTouchMinor(pointerIndex, h).toDouble().coerceAtLeast(1.0)
            ))
        }

        // Current sample
        val point = mapper.map(event.getX(pointerIndex), event.getY(pointerIndex))
        val pressure = event.getPressure(pointerIndex)
        val tiltRad = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        val orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
        val tiltX = (Math.toDegrees(tiltRad.toDouble()) * Math.sin(orientation.toDouble())).toInt()
        val tiltY = (Math.toDegrees(tiltRad.toDouble()) * Math.cos(orientation.toDouble())).toInt()
        val buttonsPressed = mapButtons(event, toolType)
        val changedButton = when (eventType) {
            PointerEventType.Down -> if (buttonsPressed != 0) buttonsPressed else 1
            PointerEventType.Up -> if (buttonsPressed != 0) buttonsPressed else 1
            else -> 0
        }

        Log.v(TAG, "[WEYLUS] $pointerType.$eventType id=${event.getPointerId(pointerIndex)} " +
            "x=${point.x.toInt()} y=${point.y.toInt()} pressure=${"%.2f".format(pressure)} " +
            "tilt=${tiltX}°/${tiltY}°")

        onPointerEvent(PointerEvent(
            event_type = eventType,
            pointer_id = event.getPointerId(pointerIndex).toLong(),
            timestamp = event.eventTime,
            is_primary = pointerIndex == 0,
            pointer_type = pointerType,
            button = changedButton,
            buttons = buttonsPressed,
            x = point.x,
            y = point.y,
            pressure = pressure.toDouble().coerceIn(0.0, 1.0),
            tilt_x = tiltX,
            tilt_y = tiltY,
            twist = orientation.let { (Math.toDegrees(it.toDouble()) + 180).toInt() % 360 },
            width = event.getTouchMajor(pointerIndex).toDouble().coerceAtLeast(1.0),
            height = event.getTouchMinor(pointerIndex).toDouble().coerceAtLeast(1.0)
        ))
    }
}

private fun mapButtons(event: MotionEvent, toolType: Int): Int {
    var flags = 0
    if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) flags = flags or 1
    if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) flags = flags or 2
    if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) flags = flags or 4
    if (event.buttonState and MotionEvent.BUTTON_BACK != 0) flags = flags or 8
    if (event.buttonState and MotionEvent.BUTTON_FORWARD != 0) flags = flags or 16
    if (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) flags = flags or 2
    if (toolType == MotionEvent.TOOL_TYPE_ERASER) flags = flags or 32
    return flags
}
