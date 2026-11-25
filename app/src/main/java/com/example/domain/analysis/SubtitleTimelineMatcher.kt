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
 * High-precision monotonic playback clock synchronized against Media3 ExoPlayer position and
 * playback rate (for local media) or JS bridge position heartbeats (for embedded YouTube / Web streams).
 * Prevents 30Hz/220ms polling staircases and eliminates cumulative frame-drift at 1x–2x speeds.
 */
class PlaybackClockAnchor {
    private var anchorPosMs = 0L
    private var anchorNs = 0L
    private var lastReportMs = 0L
    private var lastAdvanceNs = 0L
    private var lastPresentedMs = 0L
    private var playbackSpeed = 1f
    private var isPlaying = false

    private var authoritativeMediaMs = 0L
    private var lastCorrectionDeltaMs = 0L
    private var suppressedBackwardJitters = 0
    private var stallExtrapolationClamps = 0
    private var hardDiscontinuityResets = 0
    private var isMediaStalled = false

    fun diagnostics() = PlaybackClockDiagnostics(
        authoritativeMediaMs, lastPresentedMs, lastCorrectionDeltaMs, suppressedBackwardJitters,
        stallExtrapolationClamps, hardDiscontinuityResets, isMediaStalled,
    )

    fun reset(
        positionMs: Long = 0L,
        speed: Float = 1f,
        playing: Boolean = false,
        nowNs: Long = System.nanoTime(),
    ) {
        anchorPosMs = positionMs.coerceAtLeast(0L)
        authoritativeMediaMs = anchorPosMs
        lastCorrectionDeltaMs = 0L
        isMediaStalled = false
        lastPresentedMs = anchorPosMs
        lastReportMs = anchorPosMs
        anchorNs = nowNs
        lastAdvanceNs = nowNs
        playbackSpeed = speed.coerceIn(0.25f, 4f)
        isPlaying = playing
    }

    fun updatePlaybackState(
        positionMs: Long,
        speed: Float,
        playing: Boolean,
        nowNs: Long = System.nanoTime(),
    ) = reset(positionMs, speed, playing, nowNs)

    fun sampleLocalExoPosition(
        exoPosMs: Long,
        speed: Float,
        playing: Boolean,
        maxDurationMs: Long = Long.MAX_VALUE,
        nowNs: Long = System.nanoTime(),
    ): Long {
        val report = exoPosMs.coerceIn(0L, maxDurationMs.coerceAtLeast(0L))
        val rate = speed.coerceIn(0.25f, 4f)
        val discontinuity = abs(report - lastReportMs) > BACKWARD_DISCONTINUITY_THRESHOLD_MS
        if (discontinuity && playing && isPlaying) hardDiscontinuityResets++
        if (report < authoritativeMediaMs && !discontinuity && playing) suppressedBackwardJitters++
        authoritativeMediaMs = report
        lastCorrectionDeltaMs = report - lastPresentedMs
        if (!playing || !isPlaying || rate != playbackSpeed || discontinuity) {
            reset(report, rate, playing, nowNs)
        } else if (report > lastReportMs) {
            anchorPosMs = report
            lastReportMs = report
            lastAdvanceNs = nowNs
            anchorNs = nowNs
        }
        isMediaStalled = playing && elapsedMediaMs(nowNs, lastAdvanceNs) > 48L
        return present(minOf(anchorPosMs + elapsedMediaMs(nowNs, anchorNs), lastReportMs + 48L), maxDurationMs)
    }

    fun reconcileRemoteSample(
        reportedPosMs: Long,
        speed: Float,
        playing: Boolean,
        nowNs: Long = System.nanoTime(),
    ) {
        val report = reportedPosMs.coerceAtLeast(0L)
        val rate = speed.coerceIn(0.25f, 4f)
        val discontinuity = report < lastReportMs - BACKWARD_DISCONTINUITY_THRESHOLD_MS ||
            report > lastPresentedMs + FORWARD_DISCONTINUITY_THRESHOLD_MS
        if (discontinuity && playing && isPlaying) hardDiscontinuityResets++
        if (!playing || !isPlaying || rate != playbackSpeed || discontinuity) {
            reset(report, rate, playing, nowNs)
            return
        }
        // Equal samples are common while the embedded player stalls. Only media advancement
        // extends our prediction horizon. Ignore late small backward reports.
        val prediction = sampleInterpolatedMs(nowNs = nowNs)
        val drift = report - prediction
        lastCorrectionDeltaMs = drift
        if (report != authoritativeMediaMs && drift < 0) suppressedBackwardJitters++
        authoritativeMediaMs = report
        if (report <= lastReportMs) return
        isMediaStalled = false
        anchorPosMs = if (abs(drift) > 220L) report else prediction + (drift * 0.55).roundToLong()
        lastReportMs = report
        lastAdvanceNs = nowNs
        anchorNs = nowNs
    }

    fun sampleInterpolatedMs(
        maxDurationMs: Long = Long.MAX_VALUE,
        nowNs: Long = System.nanoTime(),
    ): Long {
        val deadlineNs = lastAdvanceNs + MAX_REMOTE_EXTRAPOLATION_MS * 1_000_000L
        if (isPlaying && nowNs > deadlineNs && !isMediaStalled) {
            isMediaStalled = true
            stallExtrapolationClamps++
        }
        val boundedNs = minOf(nowNs, deadlineNs)
        val elapsed = if (isPlaying) elapsedMediaMs(boundedNs, anchorNs) else 0L
        return present(anchorPosMs + elapsed, maxDurationMs)
    }

    private fun elapsedMediaMs(timeNs: Long, startNs: Long): Long =
        ((timeNs - startNs).coerceAtLeast(0L) / 1_000_000.0 * playbackSpeed).roundToLong()

    private fun present(candidate: Long, maxDurationMs: Long): Long {
        lastPresentedMs = maxOf(lastPresentedMs, candidate).coerceIn(0L, maxDurationMs.coerceAtLeast(0L))
        return lastPresentedMs
    }

    companion object {
        const val FORWARD_DISCONTINUITY_THRESHOLD_MS = 420L
        const val BACKWARD_DISCONTINUITY_THRESHOLD_MS = 380L
        const val MAX_REMOTE_EXTRAPOLATION_MS = 250L
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

        // Media cue intervals are half-open. At a shared boundary only the incoming line is active.
        val exact = mutableListOf<DialogueEvent>()
        var idx = (cursorIdx - 2).coerceAtLeast(0)
        while (idx < sortedEvents.size) {
            val evt = sortedEvents[idx]
            if (evt.startTimeMs > currentMs + frameToleranceMs) break
            if (currentMs >= evt.startTimeMs && currentMs < evt.endTimeMs) {
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
