package com.weylus.studio.net.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ClientConfiguration(
    val uinput_support: Boolean,
    val capturable_id: Int,
    val capture_cursor: Boolean,
    val max_width: Int,
    val max_height: Int,
    val client_name: String? = null,
    val frame_rate: Double,
    val capabilities: ClientCapabilities? = null
)
