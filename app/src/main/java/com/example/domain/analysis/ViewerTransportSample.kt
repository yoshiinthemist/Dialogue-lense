package com.example.domain.analysis

enum class PlaybackPhase {
    READY, USER_PAUSED, PREPARING, WAITING_FOR_SEEK, BUFFERING_MEDIA,
    BUFFERING_DIALOGUE, PLAYING, LIMITED, PROCESSING_UNAVAILABLE, ENDED,
}

enum class MediaTransport { UNAVAILABLE, PAUSED, BUFFERING, PLAYING, ENDED }

data class ViewerTransportSample(
    val positionMs: Long,
    val durationMs: Long,
    val transport: MediaTransport,
    val seekSequence: Int,
    val playSequence: Int,
    val canSeek: Boolean = durationMs > 0L,
)

data class ReadinessDecision(
    val phase: PlaybackPhase,
    val minimumLeadMs: Long,
    val playCommand: Boolean?,
)
