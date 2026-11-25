package com.example.domain.analysis

import com.example.data.model.DialogueEvent
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * High-precision monotonic playback clock synchronized against Media3 ExoPlayer position and
 * playback rate (for local media) or JS bridge position heartbeats (for embedded YouTube / Web streams).
 * Prevents 30Hz/220ms polling staircases and eliminates cumulative frame-drift at 1x–2x speeds.
 */
class PlaybackClockAnchor {
    private var anchorPosMs: Long = 0L
    private var anchorNanoTime: Long = System.nanoTime()
    private var playbackSpeed: Float = 1.0f
    private var isPlaying: Boolean = false
    private var lastReportedExoPosMs: Long = -1L
    private var lastExoAdvanceNano: Long = System.nanoTime()

    fun reset(
        positionMs: Long = 0L,
        speed: Float = 1.0f,
        playing: Boolean = false,
        nowNs: Long = System.nanoTime(),
    ) {
        anchorPosMs = positionMs.coerceAtLeast(0L)
        anchorNanoTime = nowNs
        playbackSpeed = speed.coerceIn(0.25f, 4.0f)
        isPlaying = playing
        lastReportedExoPosMs = anchorPosMs
        lastExoAdvanceNano = nowNs
    }

    fun updatePlaybackState(
        positionMs: Long,
        speed: Float,
        playing: Boolean,
        nowNs: Long = System.nanoTime(),
    ) {
        anchorPosMs = positionMs.coerceAtLeast(0L)
        anchorNanoTime = nowNs
        playbackSpeed = speed.coerceIn(0.25f, 4.0f)
        isPlaying = playing
        lastReportedExoPosMs = anchorPosMs
        lastExoAdvanceNano = nowNs
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
        val safeExo = exoPosMs.coerceIn(0L, maxDurationMs.coerceAtLeast(0L))
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)

        if (!playing) {
            updatePlaybackState(safeExo, safeSpeed, false, nowNs)
            return safeExo
        }

        if (safeExo != lastReportedExoPosMs) {
            lastReportedExoPosMs = safeExo
            lastExoAdvanceNano = nowNs
            anchorPosMs = safeExo
            anchorNanoTime = nowNs
            playbackSpeed = safeSpeed
            isPlaying = true
            return safeExo
        }

        // If ExoPlayer is actively playing and reported the exact same integer ms on a 16ms frame boundary,
        // extrapolate gently (capped at 48ms) until the next audio-clock tick arrives.
        val plateauMs = ((nowNs - lastExoAdvanceNano) / 1_000_000.0 * safeSpeed)
            .roundToLong()
            .coerceIn(0L, 48L)
        return (safeExo + plateauMs).coerceIn(0L, maxDurationMs.coerceAtLeast(0L))
    }

    /**
     * Reconciles a remote WebView / YouTube player heartbeat (`positionMs`) against the predicted
     * 60fps monotonic clock so web streams stay frame-accurate without jitter or backwards skipping.
     */
    fun reconcileRemoteSample(
        reportedPosMs: Long,
        speed: Float,
        playing: Boolean,
        nowNs: Long = System.nanoTime(),
    ) {
        val safePos = reportedPosMs.coerceAtLeast(0L)
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        val predicted = sampleInterpolatedMs(nowNs = nowNs)
        val delta = safePos - predicted

        if (!playing || !isPlaying || abs(delta) > 220L || safePos < anchorPosMs - 40L) {
            anchorPosMs = safePos
            anchorNanoTime = nowNs
        } else {
            // Smooth micro-correction absorbs ~50-120ms IPC latency without causing subtitle skips
            anchorPosMs = (predicted + (delta * 0.55).roundToLong()).coerceAtLeast(0L)
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
        if (!isPlaying) return anchorPosMs.coerceIn(0L, upper)
        val elapsedMs = ((nowNs - anchorNanoTime).coerceAtLeast(0L) / 1_000_000.0 * playbackSpeed).roundToLong()
        return (anchorPosMs + elapsedMs).coerceIn(0L, upper)
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
