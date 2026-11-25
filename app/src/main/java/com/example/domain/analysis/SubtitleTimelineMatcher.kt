package com.example.domain.analysis

import com.example.data.model.DialogueEvent
import kotlin.math.abs
import kotlin.math.roundToLong

data class PlaybackClockDiagnostics(
    val authoritativeMediaMs: Long = 0L,
    val presentationClockMs: Long = 0L,
    val lastCorrectionDeltaMs: Long = 0L,
    val suppressedBackwardJitters: Int = 0,
    val stallExtrapolationClamps: Int = 0,
    val hardDiscontinuityResets: Int = 0,
    val isMediaStalled: Boolean = false,
)

/**
 * Stable, monotonic presentation clock synchronized against Media3 ExoPlayer position and
 * playback rate (for local/direct media) or JS bridge position heartbeats (for YouTube / Web).
 *
 * DialogueLens 2.0 stability guarantees (Section 25):
 * - Separates authoritative media position from presentation clock.
 * - During uninterrupted forward playback, rejects tiny backward IPC/heartbeat corrections
 *   (< [BACKWARD_DISCONTINUITY_THRESHOLD_MS]) so cues never flicker across start/end edges.
 * - Bounds forward extrapolation when remote heartbeats stop ([MAX_REMOTE_EXTRAPOLATION_MS])
 *   so stalls never push subtitles indefinitely ahead of frozen video.
 * - Resets immediately on true seeks, pauses, speed changes, and genuine discontinuities.
 */
class PlaybackClockAnchor {
    private var anchorPosMs: Long = 0L
    private var anchorNanoTime: Long = System.nanoTime()
    private var lastPresentationMs: Long = 0L
    private var authoritativeMediaMs: Long = 0L
    private var playbackSpeed: Float = 1.0f
    private var isPlaying: Boolean = false
    private var lastReportedExoPosMs: Long = -1L
    private var lastExoAdvanceNano: Long = System.nanoTime()
    private var lastRemoteSampleNano: Long = System.nanoTime()
    private var lastRemoteReportedMs: Long = -1L
    private var isRemoteSource: Boolean = false
    private var consecutiveStallPolls: Int = 0
    private var clockLeadFreezeActive: Boolean = false

    // Bounded telemetry counters for Studio Timing Diagnostics (Section 32)
    private var lastCorrectionDeltaMs: Long = 0L
    private var suppressedBackwardJitters: Int = 0
    private var stallExtrapolationClamps: Int = 0
    private var hardDiscontinuityResets: Int = 0
    private var mediaStalled: Boolean = false

    fun reset(
        positionMs: Long = 0L,
        speed: Float = 1.0f,
        playing: Boolean = false,
        nowNs: Long = System.nanoTime(),
    ) {
        val safePos = positionMs.coerceAtLeast(0L)
        anchorPosMs = safePos
        lastPresentationMs = safePos
        authoritativeMediaMs = safePos
        anchorNanoTime = nowNs
        playbackSpeed = speed.coerceIn(0.25f, 4.0f)
        isPlaying = playing
        lastReportedExoPosMs = safePos
        lastExoAdvanceNano = nowNs
        lastRemoteSampleNano = nowNs
        lastRemoteReportedMs = safePos
        consecutiveStallPolls = 0
        clockLeadFreezeActive = false
        mediaStalled = false
        lastCorrectionDeltaMs = 0L
    }

    fun updatePlaybackState(
        positionMs: Long,
        speed: Float,
        playing: Boolean,
        nowNs: Long = System.nanoTime(),
    ) {
        val safePos = positionMs.coerceAtLeast(0L)
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        val speedChanged = abs(safeSpeed - playbackSpeed) > 0.01f
        val playChanged = playing != isPlaying
        val largeJump = abs(safePos - lastPresentationMs) > BACKWARD_DISCONTINUITY_THRESHOLD_MS

        authoritativeMediaMs = safePos
        isRemoteSource = false
        clockLeadFreezeActive = false
        if (!playing || playChanged || speedChanged || largeJump) {
            anchorPosMs = safePos
            lastPresentationMs = safePos
            anchorNanoTime = nowNs
            if (largeJump) hardDiscontinuityResets++
        } else if (safePos >= lastPresentationMs) {
            anchorPosMs = safePos
            lastPresentationMs = safePos
            anchorNanoTime = nowNs
        }
        playbackSpeed = safeSpeed
        isPlaying = playing
        lastReportedExoPosMs = safePos
        lastExoAdvanceNano = nowNs
        mediaStalled = false
    }

    /**
     * Samples Media3 ExoPlayer position directly at 60fps (16ms), interpolating sub-frame movement
     * only over short audio-buffer plateaus (< 48ms) and snapping immediately on any real ExoPlayer step or seek.
     */
    fun sampleLocalExoPosition(
        exoPosMs: Long,
        speed: Float,
        playing: Boolean,
        maxDurationMs: Long = Long.MAX_VALUE,
        nowNs: Long = System.nanoTime(),
    ): Long {
        val upper = maxDurationMs.coerceAtLeast(0L)
        val safeExo = exoPosMs.coerceIn(0L, upper)
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        isRemoteSource = false
        authoritativeMediaMs = safeExo

        if (!playing) {
            updatePlaybackState(safeExo, safeSpeed, false, nowNs)
            return safeExo
        }

        if (safeExo != lastReportedExoPosMs) {
            val isSmallBackwardJitter = lastReportedExoPosMs >= 0L &&
                safeExo < lastPresentationMs &&
                (lastPresentationMs - safeExo) <= LOCAL_BACKWARD_JITTER_TOLERANCE_MS &&
                abs(safeSpeed - playbackSpeed) <= 0.01f

            lastReportedExoPosMs = safeExo
            lastExoAdvanceNano = nowNs
            playbackSpeed = safeSpeed
            isPlaying = true
            mediaStalled = false

            if (isSmallBackwardJitter) {
                suppressedBackwardJitters++
                lastCorrectionDeltaMs = safeExo - lastPresentationMs
                anchorPosMs = lastPresentationMs
                anchorNanoTime = nowNs
                clockLeadFreezeActive = true
                return lastPresentationMs.coerceIn(0L, upper)
            }

            clockLeadFreezeActive = false
            if (abs(safeExo - lastPresentationMs) > BACKWARD_DISCONTINUITY_THRESHOLD_MS) {
                hardDiscontinuityResets++
            }
            lastCorrectionDeltaMs = safeExo - lastPresentationMs
            anchorPosMs = safeExo
            lastPresentationMs = safeExo
            anchorNanoTime = nowNs
            return safeExo
        }

        if (clockLeadFreezeActive) {
            return lastPresentationMs.coerceIn(0L, upper)
        }

        // If ExoPlayer is actively playing and reported the exact same integer ms on a 16ms frame boundary,
        // extrapolate gently (capped at 48ms) until the next audio-clock tick arrives.
        val plateauMs = ((nowNs - lastExoAdvanceNano).coerceAtLeast(0L) / 1_000_000.0 * safeSpeed)
            .roundToLong()
            .coerceIn(0L, 48L)
        val next = maxOf(lastPresentationMs, safeExo + plateauMs).coerceIn(0L, upper)
        lastPresentationMs = next
        return next
    }

    /**
     * Reconciles a remote WebView / YouTube player heartbeat (`reportedPosMs`) against the predicted
     * presentation clock so web streams stay frame-accurate with ZERO backward clock reversals
     * during uninterrupted forward playback and bounded clamping during network stalls.
     */
    fun reconcileRemoteSample(
        reportedPosMs: Long,
        speed: Float,
        playing: Boolean,
        nowNs: Long = System.nanoTime(),
    ) {
        val safePos = reportedPosMs.coerceAtLeast(0L)
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        val speedChanged = abs(safeSpeed - playbackSpeed) > 0.01f
        val predicted = sampleInterpolatedMs(nowNs = nowNs)
        val delta = safePos - predicted
        val sincePrevSampleMs = ((nowNs - lastRemoteSampleNano).coerceAtLeast(0L) / 1_000_000L)

        isRemoteSource = true
        authoritativeMediaMs = safePos
        lastCorrectionDeltaMs = delta

        // Detect genuine remote media stall: playing==true, heartbeats arriving, but reportedPosMs unchanged
        if (playing && isPlaying && lastRemoteReportedMs >= 0L && abs(safePos - lastRemoteReportedMs) <= 2L) {
            if (sincePrevSampleMs >= 100L) {
                consecutiveStallPolls++
            }
            if (consecutiveStallPolls >= 3) {
                mediaStalled = true
                clockLeadFreezeActive = false
                // Freeze cleanly at current monotonic presentation time without stepping backward
                val heldPos = maxOf(safePos, lastPresentationMs)
                anchorPosMs = heldPos
                lastPresentationMs = heldPos
                anchorNanoTime = nowNs
                lastRemoteSampleNano = nowNs
                playbackSpeed = safeSpeed
                return
            }
        } else {
            consecutiveStallPolls = 0
            mediaStalled = false
        }
        lastRemoteReportedMs = safePos
        lastRemoteSampleNano = nowNs

        if (!playing || !isPlaying || speedChanged) {
            clockLeadFreezeActive = false
            anchorPosMs = safePos
            lastPresentationMs = safePos
            anchorNanoTime = nowNs
            playbackSpeed = safeSpeed
            isPlaying = playing
            return
        }

        // Genuine discontinuity (real seek, scrub, or large network jump) -> hard reset immediately
        val isHardForwardJump = delta > FORWARD_DISCONTINUITY_THRESHOLD_MS
        val isHardBackwardJump = safePos < lastPresentationMs - BACKWARD_DISCONTINUITY_THRESHOLD_MS
        if (isHardForwardJump || isHardBackwardJump) {
            clockLeadFreezeActive = false
            anchorPosMs = safePos
            lastPresentationMs = safePos
            anchorNanoTime = nowNs
            hardDiscontinuityResets++
            playbackSpeed = safeSpeed
            isPlaying = playing
            return
        }

        if (delta < 0L) {
            // Strict forward monotonicity during uninterrupted playback (Operation Golden Video):
            // NEVER step lastPresentationMs backward for minor IPC/heartbeat lag (< BACKWARD_DISCONTINUITY_THRESHOLD_MS).
            // Instead hold the presentation clock stationary until authoritative media catches up.
            suppressedBackwardJitters++
            val leadAhead = (lastPresentationMs - safePos).coerceAtLeast(0L)
            anchorPosMs = lastPresentationMs
            anchorNanoTime = nowNs
            clockLeadFreezeActive = leadAhead > 20L
        } else {
            // Smooth forward convergence (absorbs WebView IPC latency monotonically)
            clockLeadFreezeActive = false
            val step = (delta * 0.55).roundToLong()
            val next = maxOf(lastPresentationMs, predicted + step)
            anchorPosMs = next
            lastPresentationMs = next
            anchorNanoTime = nowNs
        }
        playbackSpeed = safeSpeed
        isPlaying = playing
    }

    fun sampleInterpolatedMs(
        maxDurationMs: Long = Long.MAX_VALUE,
        nowNs: Long = System.nanoTime(),
    ): Long {
        val upper = maxDurationMs.coerceAtLeast(0L)
        if (!isPlaying || mediaStalled || clockLeadFreezeActive) {
            return lastPresentationMs.coerceIn(0L, upper)
        }
        val rawWallElapsedMs = ((nowNs - anchorNanoTime).coerceAtLeast(0L) / 1_000_000.0)
        val sinceHeartbeatMs = if (isRemoteSource) {
            ((nowNs - lastRemoteSampleNano).coerceAtLeast(0L) / 1_000_000.0)
        } else {
            0.0
        }
        val clampedWallElapsedMs = if (isRemoteSource &&
            (rawWallElapsedMs > MAX_REMOTE_EXTRAPOLATION_MS || sinceHeartbeatMs > MAX_REMOTE_EXTRAPOLATION_MS)
        ) {
            stallExtrapolationClamps++
            mediaStalled = true
            minOf(rawWallElapsedMs, MAX_REMOTE_EXTRAPOLATION_MS.toDouble())
        } else {
            rawWallElapsedMs
        }
        val elapsedMediaMs = (clampedWallElapsedMs * playbackSpeed).roundToLong()
        val next = maxOf(lastPresentationMs, anchorPosMs + elapsedMediaMs).coerceIn(0L, upper)
        lastPresentationMs = next
        return next
    }

    fun diagnostics(): PlaybackClockDiagnostics = PlaybackClockDiagnostics(
        authoritativeMediaMs = authoritativeMediaMs,
        presentationClockMs = lastPresentationMs,
        lastCorrectionDeltaMs = lastCorrectionDeltaMs,
        suppressedBackwardJitters = suppressedBackwardJitters,
        stallExtrapolationClamps = stallExtrapolationClamps,
        hardDiscontinuityResets = hardDiscontinuityResets,
        isMediaStalled = mediaStalled,
    )

    companion object {
        const val FORWARD_DISCONTINUITY_THRESHOLD_MS = 420L
        const val BACKWARD_DISCONTINUITY_THRESHOLD_MS = 380L
        const val LOCAL_BACKWARD_JITTER_TOLERANCE_MS = 85L
        const val MAX_FORWARD_HOLD_MS = 260L
        const val MAX_REMOTE_EXTRAPOLATION_MS = 320L
    }
}

/**
 * Indexed, monotonic-cursor subtitle timeline matcher that prevents cue skipping during 60fps
 * playback, variable speeds, and seeks by pairing O(1) sequential tracking with O(log N) seek lookup
 * and single-frame edge tolerance (`frameToleranceMs = 18ms`).
 */
class SubtitleTimelineMatcher(
    private val frameToleranceMs: Long = 18L,
) {
    private var lastEventsRef: List<DialogueEvent>? = null
    private var sortedEvents: List<DialogueEvent> = emptyList()
    private var maxCueDurationMs: Long = 0L
    private var cursorIdx: Int = 0
    private var lastQueryMs: Long = Long.MIN_VALUE

    fun reset() {
        lastEventsRef = null
        sortedEvents = emptyList()
        maxCueDurationMs = 0L
        cursorIdx = 0
        lastQueryMs = Long.MIN_VALUE
    }

    fun matchActiveEvents(
        events: List<DialogueEvent>,
        currentMs: Long,
    ): List<DialogueEvent> {
        if (events.isEmpty()) {
            lastEventsRef = events
            sortedEvents = emptyList()
            maxCueDurationMs = 0L
            cursorIdx = 0
            lastQueryMs = currentMs
            return emptyList()
        }

        if (events !== lastEventsRef) {
            sortedEvents = events.sortedBy { it.startTimeMs }
            maxCueDurationMs = sortedEvents.maxOfOrNull { (it.endTimeMs - it.startTimeMs).coerceAtLeast(0L) } ?: 0L
            lastEventsRef = events
            cursorIdx = findFirstCandidateIndex(sortedEvents, currentMs)
            lastQueryMs = currentMs
        } else {
            val isSequentialForward = lastQueryMs != Long.MIN_VALUE &&
                currentMs >= lastQueryMs &&
                (currentMs - lastQueryMs) <= 2_500L
            if (isSequentialForward) {
                while (cursorIdx < sortedEvents.size &&
                    sortedEvents[cursorIdx].endTimeMs < currentMs - frameToleranceMs
                ) {
                    cursorIdx++
                }
            } else {
                cursorIdx = findFirstCandidateIndex(sortedEvents, currentMs)
            }
            lastQueryMs = currentMs
        }

        if (cursorIdx >= sortedEvents.size) return emptyList()

        // First check exact strict range [startTimeMs .. endTimeMs]
        val exact = mutableListOf<DialogueEvent>()
        var idx = (cursorIdx - 2).coerceAtLeast(0)
        while (idx < sortedEvents.size) {
            val evt = sortedEvents[idx]
            if (evt.startTimeMs > currentMs + frameToleranceMs) break
            if (currentMs in evt.startTimeMs..evt.endTimeMs) {
                exact.add(evt)
            }
            idx++
        }
        if (exact.isNotEmpty()) return exact

        // If no cue matched strictly (e.g., 16ms frame landed just within 18ms of cue edge),
        // snap to the closest cue boundary within frameToleranceMs without overlapping neighbors.
        var nearIdx = (cursorIdx - 2).coerceAtLeast(0)
        while (nearIdx < sortedEvents.size) {
            val evt = sortedEvents[nearIdx]
            if (evt.startTimeMs > currentMs + frameToleranceMs) break
            val withinStartEdge = currentMs in (evt.startTimeMs - frameToleranceMs)..evt.startTimeMs
            val withinEndEdge = currentMs in evt.endTimeMs..(evt.endTimeMs + frameToleranceMs)
            if (withinStartEdge || withinEndEdge) {
                return listOf(evt)
            }
            nearIdx++
        }
        return emptyList()
    }

    private fun findFirstCandidateIndex(sorted: List<DialogueEvent>, targetMs: Long): Int {
        if (sorted.isEmpty()) return 0
        val earliestPossibleStart = targetMs - frameToleranceMs - maxCueDurationMs
        var low = 0
        var high = sorted.lastIndex
        var ans = sorted.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sorted[mid].startTimeMs >= earliestPossibleStart) {
                ans = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return ans
    }
}
