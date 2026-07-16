package com.weylus.studio.net.protocol

import kotlinx.serialization.Serializable

@Serializable
data class DisplayCapability(
    val width: Int,
    val height: Int,
    val refresh_rate: Float
)

@Serializable
data class ClientCapabilities(
    val virtual_keyboard: Boolean = true,
    val uinput: Boolean = true,
    val hover: Boolean = true,
    val clipboard: Boolean = true,
    val pressure: Boolean = true,
    val raw_h264: Boolean = true,
    val display: DisplayCapability? = null
)
