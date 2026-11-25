package com.example.domain.analysis

import com.example.data.model.CoverageInterval
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.QuotaSaverMode
import com.example.data.model.SubtitleSourceKind
import kotlin.math.abs

enum class ScoutExecutionTier(
    val modelId: String?,
    val displayLabel: String,
    val preferLite: Boolean,
) {
    BEST_AI("gemini-3.8-flash", "Gemini 3.8 Flash", false),
    LITE_AI("gemini-3.5-flash-lite", "Flash-Lite", true),
    FREE_LOCAL(null, "Free CC", true),
}

data class TimeWindowRange(
    val startMs: Long,
    val endMs: Long,
) {
    val key: Long get() = startMs
}

data class DialogueTickSegment(
    val startMs: Long,
    val endMs: Long,
    val speakerId: String?,
    val isNonSpeaker: Boolean,
    val isUnknownSpeaker: Boolean,
)

sealed interface ScoutSyncCommand {
    data object Hold : ScoutSyncCommand
    data class SeekAndPlay(val targetMs: Long) : ScoutSyncCommand
    data object PlayForward : ScoutSyncCommand
    data object PauseWithLead : ScoutSyncCommand
}

/**
 * Pure planning & transcript-merging logic for DialogueLens's 2-instance 7-second lookahead engine:
 * - Instance 2 (Scout) always works at least 7 seconds ahead of Instance 1 (Viewer).
 * - Adaptive 5-7s intro holds Viewer cleanly while Scout buffers opening dialogue & speakers.
 * - If Viewer jumps/seeks to a new timestamp, Scout immediately prioritizes [seekPos .. seekPos + 7s].
 */
object LookaheadWindowPlanner {

    const val LOOKAHEAD_LEAD_MS = 7_000L
    const val INTRO_MIN_BUFFER_MS = 3_000L
    const val INTRO_DURATION_MS = 18_000L
    const val OPENING_SCOUT_WINDOW_SEC = 35
    const val ROLLING_SCOUT_WINDOW_SEC = 40
    const val WINDOW_STEP_MS = 7_000L
    const val WINDOW_OVERLAP_MS = 450L
    const val MAX_PREFETCH_HORIZON_MS = 28_000L
    const val DRIFT_TOLERANCE_MS = 2_400L

    fun initialExecutionTier(
        quotaSaverMode: QuotaSaverMode,
        quotaCircuitBreakerTripped: Boolean,
        downgradedToLite: Boolean = false,
    ): ScoutExecutionTier = when {
        quotaSaverMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped ->
            ScoutExecutionTier.FREE_LOCAL
        quotaSaverMode == QuotaSaverMode.QUOTA_SAVER || downgradedToLite ->
            ScoutExecutionTier.LITE_AI
        else ->
            ScoutExecutionTier.BEST_AI
    }

    fun needsNativeTrack(source: SubtitleSourceKind, events: List<DialogueEvent>, fullyVerified: Boolean): Boolean =
        !fullyVerified && !hasLoadedSubtitlesLocked(source, events)

    fun effectiveQuotaMode(requested: QuotaSaverMode, cloudConfigured: Boolean): QuotaSaverMode =
        if (cloudConfigured) requested else QuotaSaverMode.ZERO_CLOUD

    /** Retain reviewed words and edits as-is when an authoritative native track arrives late. */
    fun mergeNativeTrack(
        existing: List<DialogueEvent>,
        native: List<DialogueEvent>,
        intervals: List<CoverageInterval>,
        correctedIds: Set<String> = emptySet(),
    ): List<DialogueEvent> {
        fun intersects(a: Long, b: Long, c: Long, d: Long) = a < d && c < b
        val verified = intervals.filter { it.verified }.sortedBy { it.startMs }
        var index = 0
        val retained = existing.sortedBy { it.startTimeMs }.filter { cue ->
            while (index < verified.size && verified[index].endMs <= cue.startTimeMs) index++
            val span = verified.getOrNull(index)
            cue.lockedByUser || cue.id in correctedIds ||
                (span != null && intersects(cue.startTimeMs, cue.endTimeMs, span.startMs, span.endMs))
        }
        index = 0
        val fill = native.sortedBy { it.startTimeMs }.filter { cue ->
            while (index < retained.size && retained[index].endTimeMs <= cue.startTimeMs) index++
            val previous = retained.getOrNull(index)
            previous == null || !intersects(cue.startTimeMs, cue.endTimeMs, previous.startTimeMs, previous.endTimeMs)
        }
        return (retained + fill).sortedWith(compareBy<DialogueEvent> { it.startTimeMs }.thenBy { it.id })
    }

    fun nextFallbackTierOnQuotaError(failedTier: ScoutExecutionTier): ScoutExecutionTier = when (failedTier) {
        ScoutExecutionTier.BEST_AI -> ScoutExecutionTier.LITE_AI
        ScoutExecutionTier.LITE_AI, ScoutExecutionTier.FREE_LOCAL -> ScoutExecutionTier.FREE_LOCAL
    }

    fun hasRealSubtitles(events: List<DialogueEvent>): Boolean {
        return events.any { evt ->
            (evt.sourceText.isNotBlank() || evt.translatedText.isNotBlank()) &&
                !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                !evt.sourceText.startsWith("Web Cinema ready")
        }
    }

    /**
     * Content-verified caption lock: only prevents Free CC / WebView capture / AI Scout when an
     * authoritative subtitle source (`UPLOADED_FILE` or `EMBEDDED_TRACK`) actually holds real cues.
     */
    fun hasLoadedSubtitlesLocked(
        subtitleSource: SubtitleSourceKind,
        events: List<DialogueEvent>,
    ): Boolean {
        return (subtitleSource == SubtitleSourceKind.UPLOADED_FILE ||
            subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK) &&
            hasRealSubtitles(events)
    }

    fun buildSequentialPreGenWindows(
        durationSec: Int,
        openingWindowSec: Int = OPENING_SCOUT_WINDOW_SEC,
        rollingWindowSec: Int = ROLLING_SCOUT_WINDOW_SEC,
        maxWindows: Int = 180,
    ): List<Pair<Int, Int>> {
        if (durationSec <= 0) return emptyList()
        val safeDur = durationSec
        return buildList {
            add(0 to minOf(safeDur, openingWindowSec))
            var start = openingWindowSec
            while (start < safeDur && size < maxWindows) {
                add(start to minOf(safeDur, start + rollingWindowSec))
                start += rollingWindowSec
            }
        }
    }

    /**
     * Orders remaining uncompleted windows for aggressive continuous lookahead:
     * 1) Window containing [playheadSec]
     * 2) Forward lookahead windows (>= playheadSec) sequentially to video end
     * 3) Any earlier uncached windows (< playheadSec) so backward seeks are also covered.
     */
    fun buildLookaheadPriorityOrder(
        windows: List<Pair<Int, Int>>,
        completedStarts: Set<Int>,
        inFlightStarts: Set<Int>,
        playheadSec: Int,
    ): List<Pair<Int, Int>> {
        val pending = windows.filter { (ws, _) ->
            ws !in completedStarts && ws !in inFlightStarts
        }
        val activeAtPlayhead = pending.filter { (ws, we) -> playheadSec in ws until we }
        val forward = pending.filter { (ws, _) -> ws > playheadSec }.sortedBy { it.first }
        val backward = pending.filter { (_, we) -> we <= playheadSec }.sortedByDescending { it.first }
        return (activeAtPlayhead + forward + backward).distinctBy { it.first }
    }

    fun isOpeningWindowReady(
        project: DialogueLensProject,
        refinedEventIds: Set<String>,
    ): Boolean {
        val realEvents = project.dialogueEvents
            .filter { evt ->
                (evt.sourceText.isNotBlank() || evt.translatedText.isNotBlank()) &&
                    !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                    !evt.sourceText.startsWith("Web Cinema ready")
            }
            .sortedBy { it.startTimeMs }
        if (realEvents.isEmpty()) return false

        val openingWindowEvents = realEvents.filter { it.startTimeMs <= 12_000L }
        val checkSet = if (openingWindowEvents.isNotEmpty()) openingWindowEvents else realEvents.take(4)
        return checkSet.all { evt -> evt.lockedByUser || evt.id in refinedEventIds }
    }

    fun recommendedOpeningLeadWindows(durationMs: Long): Int = when {
        durationMs <= 0L || durationMs == Long.MAX_VALUE || durationMs <= 150_000L -> 1
        durationMs <= 480_000L -> 2
        else -> 3
    }

    fun recommendedIntroMaxDurationMs(durationMs: Long): Long = when {
        durationMs <= 0L || durationMs == Long.MAX_VALUE || durationMs <= 150_000L -> INTRO_DURATION_MS
        durationMs <= 480_000L -> 28_000L
        else -> 42_000L
    }

    fun recommendedWorkerCount(durationMs: Long): Int = when {
        durationMs <= 0L || durationMs == Long.MAX_VALUE || durationMs < 240_000L -> 2
        durationMs <= 720_000L -> 3
        else -> 4
    }

    fun shouldAutoReleaseAdaptiveIntro(
        elapsedMs: Long,
        scoutPosMs: Long,
        hasRealSubtitles: Boolean,
        openingWindowRefined: Boolean,
        durationMs: Long = Long.MAX_VALUE,
        completedOpeningWindows: Int = 1,
        requiredOpeningWindows: Int = 1,
    ): Boolean {
        val maxIntroMs = recommendedIntroMaxDurationMs(durationMs)
        if (elapsedMs >= maxIntroMs) return true
        if (elapsedMs < INTRO_MIN_BUFFER_MS) return false
        val requiredScoutPos = if (durationMs in 1L..9_999L) {
            (durationMs * 0.45f).toLong().coerceAtLeast(1_500L)
        } else {
            4_500L
        }
        val windowsMet = completedOpeningWindows >= requiredOpeningWindows.coerceAtLeast(1)
        return hasRealSubtitles && openingWindowRefined && windowsMet && scoutPosMs >= requiredScoutPos
    }

    /**
     * Authoritative continuous-verified-lead buffering check (Operation Golden Video Section 8).
     * Never treats a single stray cue or a tiny remaining slice (< minLeadMs) as sufficient coverage,
     * works for short videos (<30s) and near-end unverified regions, and scales by playback speed.
     */
    fun isPlayheadWaitingOnUnbufferedWindow(
        playheadMs: Long,
        totalDurationMs: Long,
        bufferedRanges: List<TimeWindowRange>,
        events: List<DialogueEvent>,
        isScoutActive: Boolean,
        playbackSpeed: Float = 1.0f,
        minLeadMs: Long = 2_000L,
    ): Boolean {
        if (!isScoutActive || totalDurationMs <= 0L) return false
        val safePos = playheadMs.coerceIn(0L, totalDurationMs)
        val remainingMs = (totalDurationMs - safePos).coerceAtLeast(0L)
        if (remainingMs <= 500L) return false
        if (bufferedRanges.isEmpty()) return true

        val coalesced = coalesceBufferedRanges(bufferedRanges, bridgeGapMs = 1L)
        val covering = coalesced.firstOrNull { safePos in it.startMs until it.endMs }
            ?: return true

        val contiguousLeadMs = (covering.endMs - safePos).coerceAtLeast(0L)
        // If verified coverage reaches the end of the media (within 500ms), never stall
        if (covering.endMs >= totalDurationMs - 500L) return false

        val speedScaledReq = (minLeadMs.toDouble() * playbackSpeed.coerceIn(0.5f, 3.0f)).toLong()
        val effectiveRequiredLeadMs = minOf(
            speedScaledReq,
            (remainingMs - 500L).coerceAtLeast(minOf(1_000L, remainingMs)),
        )
        return contiguousLeadMs < effectiveRequiredLeadMs
    }

    /**
     * Merges touching or overlapping completed window intervals into smooth continuous tubes
     * so the timeline scrubber never renders adjacent chunks as segmented bubbles.
     */
    fun coalesceBufferedRanges(
        ranges: List<TimeWindowRange>,
        bridgeGapMs: Long = 1_000L,
    ): List<TimeWindowRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val prev = merged.last()
            if (next.startMs <= prev.endMs + bridgeGapMs) {
                merged[merged.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, next.endMs))
            } else {
                merged.add(next)
            }
        }
        return merged
    }

    /**
     * Removes failed micro-slices from completed window ranges so genuine unhealed gaps
     * remain visible on the timeline until Step B/C gap-recovery fills them.
     */
    fun subtractFailedSlices(
        completedRanges: List<TimeWindowRange>,
        failedSlices: List<TimeWindowRange>,
    ): List<TimeWindowRange> {
        if (completedRanges.isEmpty()) return emptyList()
        if (failedSlices.isEmpty()) return coalesceBufferedRanges(completedRanges)

        var current = completedRanges.filter { it.endMs > it.startMs }
        for (hole in failedSlices) {
            if (hole.endMs <= hole.startMs) continue
            val nextPass = mutableListOf<TimeWindowRange>()
            for (seg in current) {
                if (hole.endMs <= seg.startMs || hole.startMs >= seg.endMs) {
                    nextPass.add(seg)
                } else {
                    if (hole.startMs > seg.startMs) {
                        nextPass.add(TimeWindowRange(seg.startMs, minOf(hole.startMs, seg.endMs)))
                    }
                    if (hole.endMs < seg.endMs) {
                        nextPass.add(TimeWindowRange(maxOf(hole.endMs, seg.startMs), seg.endMs))
                    }
                }
            }
            current = nextPass
        }
        return coalesceBufferedRanges(current)
    }

    fun nextPriorityWindow(
        viewerPosMs: Long,
        durationMs: Long,
        completedWindowStarts: Set<Long>,
    ): TimeWindowRange? {
        if (durationMs <= 0L) return null
        val safeViewer = viewerPosMs.coerceIn(0L, durationMs)
        val anchorStart = (safeViewer / WINDOW_STEP_MS) * WINDOW_STEP_MS
        val horizonEnd = minOf(durationMs, anchorStart + MAX_PREFETCH_HORIZON_MS)

        var candidate = anchorStart
        while (candidate < horizonEnd) {
            if (candidate !in completedWindowStarts) {
                val end = minOf(durationMs, candidate + WINDOW_STEP_MS)
                if (end > candidate + 250L) {
                    return TimeWindowRange(startMs = candidate, endMs = end)
                }
            }
            candidate += WINDOW_STEP_MS
        }

        // If the 28s lookahead buffer is already complete, continue ahead linearly through the video
        var tail = horizonEnd - (horizonEnd % WINDOW_STEP_MS)
        while (tail < durationMs) {
            if (tail !in completedWindowStarts) {
                val end = minOf(durationMs, tail + WINDOW_STEP_MS)
                if (end > tail + 250L) {
                    return TimeWindowRange(startMs = tail, endMs = end)
                }
            }
            tail += WINDOW_STEP_MS
        }
        return null
    }

    fun clampScoutTargetMs(
        viewerPosMs: Long,
        durationMs: Long,
        leadMs: Long = LOOKAHEAD_LEAD_MS,
    ): Long {
        if (durationMs <= 0L) return 0L
        val effectiveLead = if (durationMs < 14_000L) {
            (durationMs * 0.45f).toLong().coerceAtLeast(1_500L)
        } else {
            leadMs
        }
        return (viewerPosMs.coerceAtLeast(0L) + effectiveLead).coerceAtMost(durationMs)
    }

    fun computeScoutSyncCommand(
        viewerPosMs: Long,
        scoutPosMs: Long,
        durationMs: Long,
        isViewerPlaying: Boolean,
        isScoutPlaying: Boolean,
        introRemainingMs: Long,
    ): ScoutSyncCommand {
        if (durationMs <= 0L) return ScoutSyncCommand.Hold

        // During the 7s intro, Viewer is held at 0ms while Scout plays through 0s -> 7s
        if (introRemainingMs > 0L) {
            val leadTarget = clampScoutTargetMs(0L, durationMs)
            if (scoutPosMs > leadTarget + DRIFT_TOLERANCE_MS) {
                return ScoutSyncCommand.SeekAndPlay(0L)
            }
            return if (scoutPosMs < leadTarget - 400L) {
                if (!isScoutPlaying) ScoutSyncCommand.PlayForward else ScoutSyncCommand.Hold
            } else {
                if (isScoutPlaying) ScoutSyncCommand.PauseWithLead else ScoutSyncCommand.Hold
            }
        }

        val desiredScoutMs = clampScoutTargetMs(viewerPosMs, durationMs)
        val driftMs = abs(scoutPosMs - desiredScoutMs)

        if (driftMs > DRIFT_TOLERANCE_MS && desiredScoutMs < durationMs - 800L) {
            val seekTarget = if (viewerPosMs <= 500L && !isViewerPlaying) 0L else desiredScoutMs
            return ScoutSyncCommand.SeekAndPlay(seekTarget)
        }

        return if (isViewerPlaying) {
            if (!isScoutPlaying && scoutPosMs < durationMs - 500L) {
                ScoutSyncCommand.PlayForward
            } else {
                ScoutSyncCommand.Hold
            }
        } else {
            // Viewer is paused: keep Scout running ONLY until it secures its +7s lead, then pause
            if (scoutPosMs < desiredScoutMs - 450L) {
                if (!isScoutPlaying) ScoutSyncCommand.PlayForward else ScoutSyncCommand.Hold
            } else {
                if (isScoutPlaying) ScoutSyncCommand.PauseWithLead else ScoutSyncCommand.Hold
            }
        }
    }

    data class WindowMergeReport(
        val events: List<DialogueEvent>,
        val acceptedCount: Int,
        val rejectedCount: Int,
        val decisions: List<DuplicateDecisionRecord>,
    )

    fun mergeWindowDialogueEvents(
        existing: List<DialogueEvent>,
        incoming: List<DialogueEvent>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<DialogueEvent> = mergeWindowDialogueEventsWithReport(
        existing = existing,
        incoming = incoming,
        windowStartMs = windowStartMs,
        windowEndMs = windowEndMs,
    ).events

    fun mergeWindowDialogueEventsWithReport(
        existing: List<DialogueEvent>,
        incoming: List<DialogueEvent>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): WindowMergeReport {
        val incomingIds = incoming.map { it.id }.toSet()
        // Additive & idempotent merge: NEVER blindly wipe existing unlocked cues inside [windowStartMs, windowEndMs),
        // because overlapping windows, bisected partial recoveries, or simultaneous speakers may contribute cues
        // across passes. Only replace an existing unlocked event when an incoming event updates that exact ID.
        val keptExisting = existing.filter { evt ->
            evt.lockedByUser || evt.id !in incomingIds
        }
        val lockedInWindow = keptExisting.filter { evt ->
            evt.lockedByUser && evt.startTimeMs in windowStartMs until windowEndMs
        }

        val merged = keptExisting.toMutableList()
        val decisions = mutableListOf<DuplicateDecisionRecord>()
        var acceptedCount = 0
        var rejectedCount = 0

        for (rawCandidate in incoming.sortedBy { it.startTimeMs }) {
            // Never let an earlier window spill cues past its [windowStartMs, windowEndMs) ceiling into the next section
            if (rawCandidate.startTimeMs >= windowEndMs) {
                rejectedCount++
                decisions.add(
                    DuplicateDecisionRecord(
                        accepted = false,
                        reason = "OUT_OF_WINDOW_SPILLOVER",
                        incomingTextPreview = rawCandidate.sourceText.take(48),
                        incomingStartMs = rawCandidate.startTimeMs,
                        speakerId = rawCandidate.speakerId,
                    )
                )
                continue
            }

            val clampedEndMs = if (rawCandidate.endTimeMs > windowEndMs && windowEndMs - rawCandidate.startTimeMs >= 450L) {
                windowEndMs
            } else {
                rawCandidate.endTimeMs
            }
            val candidate = if (clampedEndMs != rawCandidate.endTimeMs) {
                rawCandidate.copy(endTimeMs = clampedEndMs)
            } else {
                rawCandidate
            }

            // User-locked event protection: only suppress incoming candidate when it genuinely
            // overlaps the same speaker/unassigned turn at the locked time span. Simultaneous or
            // adjacent lines from a distinct speaker are ALWAYS preserved.
            val matchedLocked = lockedInWindow.firstOrNull { locked ->
                areDuplicateUtterances(locked, candidate) ||
                    (areSpeakersCompatible(locked.speakerId, candidate.speakerId) &&
                        temporalOverlapRatio(locked, candidate) >= 0.70f &&
                        abs(locked.startTimeMs - candidate.startTimeMs) <= 350L)
            }
            if (matchedLocked != null) {
                rejectedCount++
                decisions.add(
                    DuplicateDecisionRecord(
                        accepted = false,
                        reason = "OVERLAPS_USER_LOCKED_UTTERANCE",
                        incomingTextPreview = candidate.sourceText.take(48),
                        incomingStartMs = candidate.startTimeMs,
                        matchedStartMs = matchedLocked.startTimeMs,
                        speakerId = candidate.speakerId,
                    )
                )
                continue
            }

            val duplicateMatch = merged.firstOrNull { prev ->
                areDuplicateUtterances(prev, candidate)
            }
            if (duplicateMatch != null) {
                rejectedCount++
                decisions.add(
                    DuplicateDecisionRecord(
                        accepted = false,
                        reason = "TEMPORAL_OVERLAP_SAME_UTTERANCE",
                        incomingTextPreview = candidate.sourceText.take(48),
                        incomingStartMs = candidate.startTimeMs,
                        matchedStartMs = duplicateMatch.startTimeMs,
                        speakerId = candidate.speakerId,
                    )
                )
                continue
            }

            // Authoritative Scout Window-Boundary Stitching (Operation Golden Video Section 5):
            // Handles adjacent cues abutting windowStartMs (where hard clamping eliminated overlapMs)
            // without touching mid-window intentional repeats or simultaneous distinct speakers.
            var stitchedCandidate = candidate
            var droppedByBoundaryStitch = false
            if (windowStartMs > 0L && candidate.startTimeMs in (windowStartMs - 1_500L)..(windowStartMs + 2_200L)) {
                val boundaryNeighbor = merged
                    .filter { prev ->
                        prev.endTimeMs in (windowStartMs - 1_500L)..(windowStartMs + 1_200L) &&
                            (candidate.startTimeMs - prev.endTimeMs) in -1_200L..900L &&
                            areSpeakersCompatible(prev.speakerId, candidate.speakerId)
                    }
                    .maxByOrNull { it.endTimeMs }

                if (boundaryNeighbor != null) {
                    val stitchResult = stitchBoundaryUtterance(boundaryNeighbor, candidate)
                    when (stitchResult) {
                        is BoundaryStitchOutcome.DropAsDuplicate -> {
                            droppedByBoundaryStitch = true
                            rejectedCount++
                            decisions.add(
                                DuplicateDecisionRecord(
                                    accepted = false,
                                    reason = stitchResult.reason,
                                    incomingTextPreview = candidate.sourceText.take(48),
                                    incomingStartMs = candidate.startTimeMs,
                                    matchedStartMs = boundaryNeighbor.startTimeMs,
                                    speakerId = candidate.speakerId,
                                )
                            )
                        }
                        is BoundaryStitchOutcome.TrimPrefix -> {
                            stitchedCandidate = stitchResult.trimmedCandidate
                            if (decisions.size < 24) {
                                decisions.add(
                                    DuplicateDecisionRecord(
                                        accepted = true,
                                        reason = "BOUNDARY_PREFIX_TRIMMED",
                                        incomingTextPreview = stitchedCandidate.sourceText.take(48),
                                        incomingStartMs = stitchedCandidate.startTimeMs,
                                        matchedStartMs = boundaryNeighbor.startTimeMs,
                                        speakerId = stitchedCandidate.speakerId,
                                    )
                                )
                            }
                        }
                        BoundaryStitchOutcome.KeepAsIs -> Unit
                    }
                }
            }
            if (droppedByBoundaryStitch) {
                continue
            }

            // Lookback pre-boundary cue rescue: only drop if the exact pre-boundary moment
            // already has a covered cue from the same/compatible speaker or falls >4.5s earlier.
            val stalePreBoundaryMatch = if (stitchedCandidate.startTimeMs < windowStartMs) {
                if (stitchedCandidate.startTimeMs < (windowStartMs - 4_500L)) {
                    stitchedCandidate
                } else {
                    merged.firstOrNull { prev ->
                        val overlapsOrSameMoment =
                            (stitchedCandidate.startTimeMs <= prev.endTimeMs + 350L && stitchedCandidate.endTimeMs >= prev.startTimeMs - 350L) ||
                                abs(prev.startTimeMs - stitchedCandidate.startTimeMs) < 900L
                        overlapsOrSameMoment && (
                            areDuplicateUtterances(prev, stitchedCandidate) ||
                                areSpeakersCompatible(prev.speakerId, stitchedCandidate.speakerId)
                            )
                    }
                }
            } else {
                null
            }
            if (stalePreBoundaryMatch != null) {
                rejectedCount++
                decisions.add(
                    DuplicateDecisionRecord(
                        accepted = false,
                        reason = "COVERED_PREBOUNDARY_OVERLAP",
                        incomingTextPreview = stitchedCandidate.sourceText.take(48),
                        incomingStartMs = stitchedCandidate.startTimeMs,
                        matchedStartMs = stalePreBoundaryMatch.startTimeMs,
                        speakerId = stitchedCandidate.speakerId,
                    )
                )
                continue
            }

            merged.add(stitchedCandidate)
            acceptedCount++
            if (decisions.size < 24) {
                decisions.add(
                    DuplicateDecisionRecord(
                        accepted = true,
                        reason = "ACCEPTED_EVENT",
                        incomingTextPreview = stitchedCandidate.sourceText.take(48),
                        incomingStartMs = stitchedCandidate.startTimeMs,
                        speakerId = stitchedCandidate.speakerId,
                    )
                )
            }
        }
        return WindowMergeReport(
            events = merged.sortedBy { it.startTimeMs },
            acceptedCount = acceptedCount,
            rejectedCount = rejectedCount,
            decisions = decisions,
        )
    }

    private sealed interface BoundaryStitchOutcome {
        data object KeepAsIs : BoundaryStitchOutcome
        data class DropAsDuplicate(val reason: String) : BoundaryStitchOutcome
        data class TrimPrefix(val trimmedCandidate: DialogueEvent) : BoundaryStitchOutcome
    }

    private fun stitchBoundaryUtterance(
        prev: DialogueEvent,
        cand: DialogueEvent,
    ): BoundaryStitchOutcome {
        val prevText = prev.sourceText.trim()
        val candText = cand.sourceText.trim()
        val prevNorm = normalizeCueComparisonText(prevText)
        val candNorm = normalizeCueComparisonText(candText)
        if (prevNorm.isEmpty() || candNorm.isEmpty()) return BoundaryStitchOutcome.KeepAsIs

        // 1. Exact or near-exact boundary duplicate (including short 2+ word utterances like "That's right" at 12:35)
        val prevWordList = tokenizeOrderedWords(prevText)
        val candWordList = tokenizeOrderedWords(candText)
        if (prevNorm == candNorm && (candWordList.size >= 2 || candNorm.length >= 8)) {
            return BoundaryStitchOutcome.DropAsDuplicate("BOUNDARY_DUPLICATE_STITCHED")
        }
        if (isTextEquivalent(prevText, candText)) {
            return BoundaryStitchOutcome.DropAsDuplicate("BOUNDARY_DUPLICATE_STITCHED")
        }

        // 2. Candidate is wholly contained in the trailing part of prev (partial boundary echo, e.g. 16:35)
        if (candNorm.length >= 10 && prevNorm.endsWith(candNorm)) {
            return BoundaryStitchOutcome.DropAsDuplicate("BOUNDARY_SUFFIX_ECHO_STITCHED")
        }
        if (candWordList.size >= 3 && prevWordList.size >= candWordList.size) {
            val tailSlice = prevWordList.takeLast(candWordList.size + 1)
            val overlapWords = candWordList.count { it in tailSlice }
            if (overlapWords == candWordList.size) {
                return BoundaryStitchOutcome.DropAsDuplicate("BOUNDARY_SUFFIX_ECHO_STITCHED")
            }
        }

        // 3. Suffix-prefix phrase overlap across boundary (e.g. 03:15, 16:35, 17:15):
        // Trim repeated leading words from candidate while keeping new continuation words intact.
        val maxOverlap = minOf(prevWordList.size, candWordList.size)
        var bestOverlapCount = 0
        for (k in maxOverlap downTo 2) {
            val prevSuffix = prevWordList.subList(prevWordList.size - k, prevWordList.size)
            val candPrefix = candWordList.subList(0, k)
            if (prevSuffix == candPrefix) {
                val charLen = candPrefix.sumOf { it.length }
                if (k >= 3 || charLen >= 10) {
                    bestOverlapCount = k
                    break
                }
            }
        }
        if (bestOverlapCount > 0) {
            if (bestOverlapCount >= candWordList.size) {
                return BoundaryStitchOutcome.DropAsDuplicate("BOUNDARY_SUFFIX_ECHO_STITCHED")
            }
            val trimmedSource = dropLeadingWordsPreservingOriginal(candText, bestOverlapCount)
            if (trimmedSource.length < 4) {
                return BoundaryStitchOutcome.DropAsDuplicate("BOUNDARY_SUFFIX_ECHO_STITCHED")
            }
            val trimmedTranslated = if (cand.translatedText.trim().equals(candText, ignoreCase = true)) {
                trimmedSource
            } else {
                val prevTransWords = tokenizeOrderedWords(prev.translatedText)
                val candTransWords = tokenizeOrderedWords(cand.translatedText)
                val transMatch = minOf(bestOverlapCount, prevTransWords.size, candTransWords.size)
                if (transMatch >= 2 &&
                    prevTransWords.takeLast(transMatch) == candTransWords.take(transMatch)
                ) {
                    dropLeadingWordsPreservingOriginal(cand.translatedText.trim(), transMatch)
                        .ifBlank { cand.translatedText }
                } else {
                    cand.translatedText
                }
            }
            return BoundaryStitchOutcome.TrimPrefix(
                cand.copy(
                    sourceText = trimmedSource,
                    translatedText = trimmedTranslated,
                )
            )
        }

        return BoundaryStitchOutcome.KeepAsIs
    }

    private fun tokenizeOrderedWords(raw: String): List<String> =
        raw.trim().lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotBlank() }

    private fun dropLeadingWordsPreservingOriginal(raw: String, wordsToDrop: Int): String {
        if (wordsToDrop <= 0) return raw.trim()
        val matcher = Regex("[\\p{L}\\p{N}']+").findAll(raw).toList()
        if (wordsToDrop >= matcher.size) return ""
        val cutIndex = matcher[wordsToDrop].range.first
        return raw.substring(cutIndex).trimStart(' ', ',', ';', ':', '-', '—', '.').trim()
    }

    /**
     * Suppresses hallucinated opening-scene or earlier-window Scout dialogue echoes (Operation Golden Video Section 4):
     * - Detects ordered multi-cue cross-window sequence echoes (even when speaker assignments differ or
     *   wording is slightly paraphrased/re-segmented) and rejects the contaminated chunk so the ledger
     *   marks the window FAILED for bounded recovery instead of committing echoed dialogue.
     * - Preserves legitimate isolated repeated lines, refrains, callbacks, and simultaneous speakers.
     */
    fun filterEchoedOpeningCues(
        existing: List<DialogueEvent>,
        incoming: List<DialogueEvent>,
        windowStartMs: Long,
    ): List<DialogueEvent> {
        if (incoming.isEmpty() || existing.isEmpty() || windowStartMs < 35_000L) return incoming
        val priorAnchors = existing
            .filter {
                it.startTimeMs < (windowStartMs - 25_000L).coerceAtLeast(0L) &&
                    normalizeCueComparisonText(it.sourceText).length >= 14
            }
            .sortedBy { it.startTimeMs }
        if (priorAnchors.isEmpty()) return incoming

        val exactOrFuzzyMatches = mutableListOf<Pair<Int, DialogueEvent>>() // (incomingIdx, matchedPrior)
        val sameSpeakerMatches = mutableSetOf<Int>()

        incoming.forEachIndexed { idx, evt ->
            val norm = normalizeCueComparisonText(evt.sourceText)
            if (norm.length < 14) return@forEachIndexed
            val matchedPrior = priorAnchors.firstOrNull { prior ->
                isEchoCandidateTextMatch(prior.sourceText, evt.sourceText)
            }
            if (matchedPrior != null) {
                exactOrFuzzyMatches.add(idx to matchedPrior)
                if (areSpeakersCompatible(matchedPrior.speakerId, evt.speakerId)) {
                    sameSpeakerMatches.add(idx)
                }
            }
        }

        // 1. Ordered multi-cue sequence echo (speaker-independent!):
        // If >= 2 incoming cues match distant earlier cues in the same chronological sequence
        // (within a ~90s source cluster) and form >= 40% of the chunk (or >= 3 ordered cues total),
        // the model clearly re-read an earlier scene instead of the requested window!
        if (exactOrFuzzyMatches.size >= 2) {
            val orderedChainLength = longestMonotonicPriorCluster(exactOrFuzzyMatches.map { it.second.startTimeMs })
            val ratioEchoed = exactOrFuzzyMatches.size * 10 >= incoming.size * 4
            if (orderedChainLength >= 3 || (orderedChainLength >= 2 && ratioEchoed)) {
                return emptyList()
            }
        }

        // 2. Classic whole-chunk same-speaker opening hallucination (>= 2 lines & >= 50% of chunk)
        if (sameSpeakerMatches.size >= 2 && sameSpeakerMatches.size * 10 >= incoming.size * 5) {
            return emptyList()
        }

        // 3. Leading-edge echo guard: suppress a lone opening-edge replay (within first 4s of windowStartMs,
        // >= 26 normalized chars) from the same/unassigned speaker when matching an earlier-scene line.
        return incoming.filterIndexed { idx, evt ->
            if (idx !in sameSpeakerMatches) return@filterIndexed true
            val norm = normalizeCueComparisonText(evt.sourceText)
            val atWindowOpening = (evt.startTimeMs - windowStartMs) in -1_000L..4_000L
            !(atWindowOpening && norm.length >= 26)
        }
    }

    private fun isEchoCandidateTextMatch(priorText: String, incomingText: String): Boolean {
        val priorNorm = normalizeCueComparisonText(priorText)
        val incNorm = normalizeCueComparisonText(incomingText)
        if (priorNorm.length < 14 || incNorm.length < 14) return false
        if (priorNorm == incNorm) return true
        val shorter = minOf(priorNorm.length, incNorm.length)
        if (shorter >= 18 && (priorNorm.contains(incNorm) || incNorm.contains(priorNorm))) return true
        val priorWords = tokenizeComparisonWords(priorText)
        val incWords = tokenizeComparisonWords(incomingText)
        if (priorWords.size >= 4 && incWords.size >= 4) {
            val intersection = priorWords.intersect(incWords).size
            val union = priorWords.union(incWords).size.coerceAtLeast(1)
            val minSet = minOf(priorWords.size, incWords.size).coerceAtLeast(1)
            if (intersection.toFloat() / union.toFloat() >= 0.58f ||
                (intersection >= 4 && intersection.toFloat() / minSet.toFloat() >= 0.72f)
            ) {
                return true
            }
        }
        return false
    }

    private fun longestMonotonicPriorCluster(matchedPriorTimesMs: List<Long>): Int {
        if (matchedPriorTimesMs.isEmpty()) return 0
        var best = 1
        for (i in matchedPriorTimesMs.indices) {
            var count = 1
            var lastTime = matchedPriorTimesMs[i]
            val clusterStart = lastTime
            for (j in (i + 1) until matchedPriorTimesMs.size) {
                val t = matchedPriorTimesMs[j]
                if (t >= lastTime - 2_000L && (t - clusterStart) <= 95_000L) {
                    count++
                    lastTime = maxOf(lastTime, t)
                }
            }
            if (count > best) best = count
        }
        return best
    }

    /**
     * Identity-aware utterance deduplication: two events represent the same spoken utterance
     * ONLY when they share compatible speaker identity AND genuine temporal overlap (or tiny
     * re-segmentation boundary jitter) alongside equivalent text.
     *
     * Identical dialogue at distinct non-overlapping timestamps (or from distinct speakers)
     * is ALWAYS preserved.
     */
    fun areDuplicateUtterances(
        prev: DialogueEvent,
        cand: DialogueEvent,
    ): Boolean {
        if (!areSpeakersCompatible(prev.speakerId, cand.speakerId)) return false
        return areTemporallyOverlappingEquivalentText(
            prevStartMs = prev.startTimeMs,
            prevEndMs = prev.endTimeMs,
            prevText = prev.sourceText,
            candStartMs = cand.startTimeMs,
            candEndMs = cand.endTimeMs,
            candText = cand.sourceText,
        )
    }

    fun areTemporallyOverlappingEquivalentText(
        prevStartMs: Long,
        prevEndMs: Long,
        prevText: String,
        candStartMs: Long,
        candEndMs: Long,
        candText: String,
    ): Boolean {
        val prevSafeEnd = maxOf(prevEndMs, prevStartMs + 350L)
        val candSafeEnd = maxOf(candEndMs, candStartMs + 350L)
        val overlapMs = minOf(prevSafeEnd, candSafeEnd) - maxOf(prevStartMs, candStartMs)
        val minDurMs = minOf(prevSafeEnd - prevStartMs, candSafeEnd - candStartMs).coerceAtLeast(350L)
        val startDeltaMs = abs(prevStartMs - candStartMs)
        val endDeltaMs = abs(prevSafeEnd - candSafeEnd)

        // Require genuine interval overlap OR tiny re-encoding boundary jitter
        val hasTemporalOverlap = overlapMs >= minOf(250L, (minDurMs * 0.40f).toLong())
        val hasJitterMatch = startDeltaMs <= 650L && (endDeltaMs <= 900L || overlapMs > 0L)
        if (!hasTemporalOverlap && !hasJitterMatch) {
            return false
        }

        return isTextEquivalent(prevText, candText)
    }

    fun areSpeakersCompatible(firstSpeakerId: String?, secondSpeakerId: String?): Boolean {
        val a = firstSpeakerId?.trim()?.takeIf { it.isNotEmpty() && !it.equals("UNCERTAIN", ignoreCase = true) }
        val b = secondSpeakerId?.trim()?.takeIf { it.isNotEmpty() && !it.equals("UNCERTAIN", ignoreCase = true) }
        if (a != null && b != null) {
            return a.equals(b, ignoreCase = true)
        }
        return true
    }

    private fun temporalOverlapRatio(a: DialogueEvent, b: DialogueEvent): Float {
        val aEnd = maxOf(a.endTimeMs, a.startTimeMs + 350L)
        val bEnd = maxOf(b.endTimeMs, b.startTimeMs + 350L)
        val overlap = (minOf(aEnd, bEnd) - maxOf(a.startTimeMs, b.startTimeMs)).coerceAtLeast(0L)
        val minDur = minOf(aEnd - a.startTimeMs, bEnd - b.startTimeMs).coerceAtLeast(1L)
        return overlap.toFloat() / minDur.toFloat()
    }

    fun isTextEquivalent(prevText: String, candText: String): Boolean {
        val prevNorm = normalizeCueComparisonText(prevText)
        val candNorm = normalizeCueComparisonText(candText)
        if (prevNorm.isEmpty() || candNorm.isEmpty()) return false
        if (prevNorm == candNorm) return true

        val shorter = minOf(prevNorm.length, candNorm.length)
        if (shorter >= 12 && (prevNorm.contains(candNorm) || candNorm.contains(prevNorm))) {
            return true
        }
        val prevWords = tokenizeComparisonWords(prevText)
        val candWords = tokenizeComparisonWords(candText)
        if (prevWords.size >= 3 && candWords.size >= 3) {
            val intersection = prevWords.intersect(candWords).size
            val union = prevWords.union(candWords).size.coerceAtLeast(1)
            if (intersection.toFloat() / union.toFloat() >= 0.72f) {
                return true
            }
        }
        return false
    }

    private fun tokenizeComparisonWords(raw: String): Set<String> =
        raw.trim().lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .toSet()

    private fun normalizeCueComparisonText(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
}

