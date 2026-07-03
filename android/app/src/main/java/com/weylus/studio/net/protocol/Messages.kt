package com.weylus.studio.net.protocol

import kotlinx.serialization.Serializable

@Serializable
data class MessageInbound(
    val type: String,
    
    // ClientConfiguration fields
    val uinput_support: Boolean? = null,
    val capturable_id: Int? = null,
    val capture_cursor: Boolean? = null,
    val max_width: Int? = null,
    val max_height: Int? = null,
    val client_name: String? = null,
    val frame_rate: Double? = null,
    val capabilities: ClientCapabilities? = null,

    // PointerEvent fields
    val is_primary: Boolean? = null,
    val pointer_type: PointerType? = null,
    val event_type: PointerEventType? = null,
    val x: Double? = null,
    val y: Double? = null,
    val pressure: Float? = null,
    val tilt_x: Float? = null,
    val tilt_y: Float? = null
)
