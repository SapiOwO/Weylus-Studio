package com.weylus.studio.net.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Matches Rust's PointerType enum in src/protocol.rs.
 * SerialName values are mandatory to match Rust serde rename strings.
 */
@Serializable
enum class PointerType {
    @SerialName("")
    Unknown,
    @SerialName("mouse")
    Mouse,
    @SerialName("pen")
    Pen,
    @SerialName("touch")
    Touch
}

/**
 * Matches Rust's PointerEventType enum in src/protocol.rs.
 * Uses Web Pointer Events API naming convention (pointerdown, pointerup, etc.)
 */
@Serializable
enum class PointerEventType {
    @SerialName("pointerdown")
    Down,
    @SerialName("pointerup")
    Up,
    @SerialName("pointercancel")
    Cancel,
    @SerialName("pointermove")
    Move,
    @SerialName("pointerover")
    Over,
    @SerialName("pointerenter")
    Enter,
    @SerialName("pointerleave")
    Leave,
    @SerialName("pointerout")
    Out
}

/**
 * Matches Rust's PointerEvent struct in src/protocol.rs exactly.
 * All fields must be present or have defaults for serialization to succeed.
 *
 * Note: button/buttons are serialized as u8 bitflags in Rust.
 * NONE = 0, PRIMARY = 1, SECONDARY = 2, AUXILIARY = 4, FOURTH = 8, FIFTH = 16, ERASER = 32
 */
@Serializable
data class PointerEvent(
    val event_type: PointerEventType,
    val pointer_id: Long = 0L,
    val timestamp: Long = 0L,
    val is_primary: Boolean,
    val pointer_type: PointerType,
    /** Button that changed state (as bitflag byte). 0 = NONE, 1 = PRIMARY, 2 = SECONDARY. */
    val button: Int = 0,
    /** All currently pressed buttons (as bitflag byte). */
    val buttons: Int = 0,
    val x: Double,
    val y: Double,
    val pressure: Double,
    val tilt_x: Int = 0,
    val tilt_y: Int = 0,
    val twist: Int = 0,
    val width: Double = 1.0,
    val height: Double = 1.0
)
