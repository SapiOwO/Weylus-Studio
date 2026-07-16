package com.weylus.studio.net.protocol

import kotlinx.serialization.Serializable

@Serializable
data class DisplayChanged(
    val width: Int,
    val height: Int,
    val rotation: Int
)

// Externally tagged wrappers to match Rust's MessageInbound enum serialization format

@Serializable
data class ConfigMessage(
    val Config: ClientConfiguration
)

@Serializable
data class PointerEventMessage(
    val PointerEvent: PointerEvent
)

@Serializable
data class DisplayChangedMessage(
    val DisplayChanged: DisplayChanged
)

/**
 * Sent after Config to request the server restart video delivery.
 * Rust expects: { "ResumeVideo": null } — but since serde unit variant serializes
 * as just the string "ResumeVideo", we use a raw string wrapper approach.
 * This class serializes to: {"ResumeVideo": null}
 */
@Serializable
data class ResumeVideoMessage(
    val ResumeVideo: String? = null
)
