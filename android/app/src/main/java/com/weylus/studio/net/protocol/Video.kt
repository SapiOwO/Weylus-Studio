package com.weylus.studio.net.protocol

import kotlinx.serialization.Serializable

@Serializable
data class VideoConfig(
    val width: Int,
    val height: Int
)
