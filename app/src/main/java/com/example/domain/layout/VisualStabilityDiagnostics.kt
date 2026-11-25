package com.example.domain.layout

import kotlin.math.hypot

data class RelocationReasonRecord(
    val eventId: String,
    val playbackMs: Long,
    val reason: String,
    val displacementNorm: Float,
)

/**
 * Bounded visual stability telemetry exposed in Studio Diagnostics (Section 33) so persistent
 * flicker, A-B-A oscillation, tail toggles, and tracking dropouts can be measured objectively
 * without exposing noisy counters during normal viewing.
 */
data class VisualStabilityDiagnosticsSnapshot(
    val layoutEvaluations: Int = 0,
    val totalRelocations: Int = 0,
    val suppressedAbaOscillations: Int = 0,
    val detectedAbaOscillations: Int = 0,
    val maxFrameDisplacementNorm: Float = 0f,
    val sceneEpochChanges: Int = 0,
    val trackingDropouts: Int = 0,
    val tailVisibilityToggles: Int = 0,
    val activationCount: Int = 0,
    val deactivationCount: Int = 0,
    val identityPreservedRefinements: Int = 0,
    val relocationsPerEventMax: Int = 0,
    val recentRelocations: List<RelocationReasonRecord> = emptyList(),
)

class VisualStabilityDiagnostics {
    private val lock = Any()
    private var layoutEvaluations = 0
    private var totalRelocations = 0
    private var suppressedAbaOscillations = 0
    private var detectedAbaOscillations = 0
    private var maxFrameDisplacementNorm = 0f
    private var sceneEpochChanges = 0
    private var trackingDropouts = 0
    private var tailVisibilityToggles = 0
    private var activationCount = 0
    private var deactivationCount = 0
    private var identityPreservedRefinements = 0
    private var lastSceneEpoch = -1
    private val relocationsByEvent = mutableMapOf<String, Int>()
    private val recentRelocations = ArrayDeque<RelocationReasonRecord>(24)
    private var lastActiveEventIds: Set<String> = emptySet()

    fun reset() = synchronized(lock) {
        layoutEvaluations = 0
        totalRelocations = 0
        suppressedAbaOscillations = 0
        detectedAbaOscillations = 0
        maxFrameDisplacementNorm = 0f
        sceneEpochChanges = 0
        trackingDropouts = 0
        tailVisibilityToggles = 0
        activationCount = 0
        deactivationCount = 0
        identityPreservedRefinements = 0
        lastSceneEpoch = -1
        relocationsByEvent.clear()
        recentRelocations.clear()
        lastActiveEventIds = emptySet()
    }

    fun recordLayoutEvaluation() = synchronized(lock) {
        layoutEvaluations++
    }

    fun recordBubbleActivationDelta(activated: Int, deactivated: Int) = synchronized(lock) {
        activationCount += activated.coerceAtLeast(0)
        deactivationCount += deactivated.coerceAtLeast(0)
    }

    fun recordSceneEpochChange() = synchronized(lock) {
        sceneEpochChanges++
    }

    fun recordLayoutPass(activeIds: Set<String>, sceneEpoch: Int) = synchronized(lock) {
        layoutEvaluations++
        if (lastSceneEpoch >= 0 && sceneEpoch != lastSceneEpoch) {
            sceneEpochChanges++
        }
        lastSceneEpoch = sceneEpoch

        val newlyActive = activeIds - lastActiveEventIds
        val deactivated = lastActiveEventIds - activeIds
        activationCount += newlyActive.size
        deactivationCount += deactivated.size
        lastActiveEventIds = activeIds
    }

    fun recordRelocation(
        eventId: String,
        displacementNormalized: Float,
        reason: String,
        playbackMs: Long,
        isAbaBounce: Boolean = false,
    ) = synchronized(lock) {
        val dist = displacementNormalized.coerceAtLeast(0f)
        totalRelocations++
        if (dist > maxFrameDisplacementNorm) {
            maxFrameDisplacementNorm = dist
        }
        if (isAbaBounce) detectedAbaOscillations++
        val nextCount = (relocationsByEvent[eventId] ?: 0) + 1
        relocationsByEvent[eventId] = nextCount
        if (relocationsByEvent.size > 200) {
            val oldestKey = relocationsByEvent.keys.firstOrNull()
            if (oldestKey != null) relocationsByEvent.remove(oldestKey)
        }
        if (recentRelocations.size >= 20) recentRelocations.removeFirst()
        recentRelocations.addLast(
            RelocationReasonRecord(
                eventId = eventId,
                playbackMs = playbackMs,
                reason = reason,
                displacementNorm = dist,
            )
        )
    }

    fun recordRelocation(
        eventId: String,
        playbackMs: Long,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        reason: String,
        isAbaBounce: Boolean = false,
    ) {
        val dist = hypot((toX - fromX).toDouble(), (toY - fromY).toDouble()).toFloat()
        recordRelocation(
            eventId = eventId,
            displacementNormalized = dist,
            reason = reason,
            playbackMs = playbackMs,
            isAbaBounce = isAbaBounce,
        )
    }

    fun recordOscillationPrevented(
        eventId: String = "",
        playbackMs: Long = 0L,
        reason: String = "",
    ) = synchronized(lock) {
        suppressedAbaOscillations++
    }

    fun recordOscillationDetected(
        eventId: String = "",
        displacementNormalized: Float = 0f,
        playbackMs: Long = 0L,
    ) = synchronized(lock) {
        detectedAbaOscillations++
    }

    fun recordSuppressedAbaOscillation() = synchronized(lock) {
        suppressedAbaOscillations++
    }

    fun recordTrackingDropout() = synchronized(lock) {
        trackingDropouts++
    }

    fun recordTailToggle() = synchronized(lock) {
        tailVisibilityToggles++
    }

    fun recordTailVisibilityToggle() = synchronized(lock) {
        tailVisibilityToggles++
    }

    fun recordIdentityPreservedRefinement() = synchronized(lock) {
        identityPreservedRefinements++
    }

    fun snapshot(): VisualStabilityDiagnosticsSnapshot = synchronized(lock) {
        VisualStabilityDiagnosticsSnapshot(
            layoutEvaluations = layoutEvaluations,
            totalRelocations = totalRelocations,
            suppressedAbaOscillations = suppressedAbaOscillations,
            detectedAbaOscillations = detectedAbaOscillations,
            maxFrameDisplacementNorm = maxFrameDisplacementNorm,
            sceneEpochChanges = sceneEpochChanges,
            trackingDropouts = trackingDropouts,
            tailVisibilityToggles = tailVisibilityToggles,
            activationCount = activationCount,
            deactivationCount = deactivationCount,
            identityPreservedRefinements = identityPreservedRefinements,
            relocationsPerEventMax = relocationsByEvent.values.maxOrNull() ?: 0,
            recentRelocations = recentRelocations.toList(),
        )
    }
}
