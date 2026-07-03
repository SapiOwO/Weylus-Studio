package com.weylus.studio.net.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class PointerType {
    Mouse,
    Pen,
    Touch
}

@Serializable
enum class PointerEventType {
    Down,
    Up,
    Move,
    Cancel,
    Enter,
    Leave
}

@Serializable
data class PointerEvent(
    val is_primary: Boolean,
    val pointer_type: PointerType,
    val event_type: PointerEventType,
    val x: Double,
    val y: Double,
    val pressure: Float,
    val tilt_x: Float,
    val tilt_y: Float
)
