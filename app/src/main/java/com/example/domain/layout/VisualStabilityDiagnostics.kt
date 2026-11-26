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
    val renderedFrameSamples: Int = 0,
    val renderedLargeJumps: Int = 0,
    val renderedOscillations: Int = 0,
    val entryAnimationReplays: Int = 0,
    val maxRenderedStepNorm: Float = 0f,
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

    private data class RenderedPosSample(
        val cxNorm: Float,
        val cyNorm: Float,
        val prevCxNorm: Float? = null,
        val prevCyNorm: Float? = null,
    )

    private var renderedFrameSamples = 0
    private var renderedLargeJumps = 0
    private var renderedOscillations = 0
    private var entryAnimationReplays = 0
    private var maxRenderedStepNorm = 0f
    private val renderedHistoryByCue = mutableMapOf<String, RenderedPosSample>()
    private val seenEntryKeys = mutableSetOf<String>()

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
        renderedFrameSamples = 0
        renderedLargeJumps = 0
        renderedOscillations = 0
        entryAnimationReplays = 0
        maxRenderedStepNorm = 0f
        renderedHistoryByCue.clear()
        seenEntryKeys.clear()
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

    /**
     * Records an actual rendered/animated on-screen pixel placement for [cueKey], detecting real
     * rendered jumps and A-B-A oscillations directly from what the viewer sees.
     */
    fun recordRenderedFrame(
        cueKey: String,
        renderedLeftPx: Float,
        renderedTopPx: Float,
        widthPx: Float,
        heightPx: Float,
        containerWidthPx: Float,
        containerHeightPx: Float,
    ) = synchronized(lock) {
        if (containerWidthPx <= 1f || containerHeightPx <= 1f || widthPx <= 0f || heightPx <= 0f) return@synchronized
        renderedFrameSamples++
        val cx = ((renderedLeftPx + widthPx * 0.5f) / containerWidthPx).coerceIn(0f, 1f)
        val cy = ((renderedTopPx + heightPx * 0.5f) / containerHeightPx).coerceIn(0f, 1f)
        val prev = renderedHistoryByCue[cueKey]
        if (prev != null) {
            val step = hypot((cx - prev.cxNorm).toDouble(), (cy - prev.cyNorm).toDouble()).toFloat()
            if (step > maxRenderedStepNorm) {
                maxRenderedStepNorm = step
            }
            if (step >= RENDERED_LARGE_JUMP_THRESHOLD) {
                renderedLargeJumps++
            }
            val pPrevX = prev.prevCxNorm
            val pPrevY = prev.prevCyNorm
            var countedOscillation = false
            if (pPrevX != null && pPrevY != null && step >= RENDERED_ABA_STEP_THRESHOLD) {
                val distToTwoAgo = hypot((cx - pPrevX).toDouble(), (cy - pPrevY).toDouble()).toFloat()
                val prevStep = hypot((prev.cxNorm - pPrevX).toDouble(), (prev.cyNorm - pPrevY).toDouble()).toFloat()
                if (prevStep >= RENDERED_ABA_STEP_THRESHOLD && distToTwoAgo <= RENDERED_ABA_RETURN_TOLERANCE) {
                    renderedOscillations++
                    countedOscillation = true
                }
            }
            // Sliding-window gradual multi-frame A->B->A reversal check (catches spring-smoothed drift reversals)
            val trail = renderedTrailsByCue.getOrPut(cueKey) { ArrayDeque() }
            if (step >= 0.008f) {
                if (!countedOscillation && trail.size >= 3) {
                    val oldest = trail.first()
                    val peakDist = trail.maxOf { pt ->
                        hypot((pt.first - oldest.first).toDouble(), (pt.second - oldest.second).toDouble()).toFloat()
                    }
                    val returnDist = hypot((cx - oldest.first).toDouble(), (cy - oldest.second).toDouble()).toFloat()
                    if (peakDist >= RENDERED_GRADUAL_ABA_PEAK && returnDist <= RENDERED_ABA_RETURN_TOLERANCE) {
                        renderedOscillations++
                        trail.clear()
                    }
                }
                if (trail.size >= 8) trail.removeFirst()
                trail.addLast(cx to cy)
                renderedHistoryByCue[cueKey] = RenderedPosSample(
                    cxNorm = cx,
                    cyNorm = cy,
                    prevCxNorm = prev.cxNorm,
                    prevCyNorm = prev.cyNorm,
                )
            }
        } else {
            if (renderedHistoryByCue.size > 200) {
                val oldest = renderedHistoryByCue.keys.firstOrNull()
                if (oldest != null) {
                    renderedHistoryByCue.remove(oldest)
                    renderedTrailsByCue.remove(oldest)
                }
            }
            renderedHistoryByCue[cueKey] = RenderedPosSample(cxNorm = cx, cyNorm = cy)
            renderedTrailsByCue[cueKey] = ArrayDeque<Pair<Float, Float>>().apply { addLast(cx to cy) }
        }
    }

    /**
     * Tracks entry animation invocations per persistent cue identity. If the same cueKey triggers
     * entry animation more than once while active, increments [entryAnimationReplays].
     */
    fun recordEntryAnimationStarted(cueKey: String) = synchronized(lock) {
        if (!seenEntryKeys.add(cueKey)) {
            entryAnimationReplays++
        }
        if (seenEntryKeys.size > 240) {
            val first = seenEntryKeys.firstOrNull()
            if (first != null) seenEntryKeys.remove(first)
        }
    }

    fun clearEntryAnimationKey(cueKey: String) {
        synchronized(lock) {
            seenEntryKeys.remove(cueKey)
            renderedHistoryByCue.remove(cueKey)
            renderedTrailsByCue.remove(cueKey)
        }
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
            renderedFrameSamples = renderedFrameSamples,
            renderedLargeJumps = renderedLargeJumps,
            renderedOscillations = renderedOscillations,
            entryAnimationReplays = entryAnimationReplays,
            maxRenderedStepNorm = maxRenderedStepNorm,
        )
    }

    private val renderedTrailsByCue = linkedMapOf<String, ArrayDeque<Pair<Float, Float>>>()

    companion object {
        private const val RENDERED_LARGE_JUMP_THRESHOLD = 0.14f
        private const val RENDERED_ABA_STEP_THRESHOLD = 0.055f
        private const val RENDERED_GRADUAL_ABA_PEAK = 0.075f
        private const val RENDERED_ABA_RETURN_TOLERANCE = 0.025f
    }
}
