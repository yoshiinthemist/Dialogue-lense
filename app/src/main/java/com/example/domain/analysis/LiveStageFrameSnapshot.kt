package com.example.domain.analysis

import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect

data class LiveFaceObservation(
    val trackingId: Int?,
    val boundingBox: NormalizedRect,
    val mouthPoint: NormalizedPoint,
    val headYawDeg: Float,
    val capturedAtMs: Long,
)

data class LiveStageFrameSnapshot(
    val faces: List<LiveFaceObservation>,
    val visualCutDetected: Boolean,
    val cutDistance: Float,
    val frameLumaAverage: Float,
    val capturedAtMs: Long,
    val hasLiveVisionSignal: Boolean = true,
    val projectId: String? = null,
    val playbackGeneration: Int? = null,
    val seekGeneration: Long = playbackGeneration?.toLong() ?: 0L,
    val unmaskedSampleCount: Int = 48,
    val emptyFrameConsecutiveCount: Int = 0,
) {
    companion object {
        const val CONFIRMED_EMPTY_FRAME_STREAK = 2
    }
}
