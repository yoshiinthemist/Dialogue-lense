package com.example.domain.analysis

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

    /**
     * When cloud AI credits are available, jump straight to the AI Scout without
     * spending 8-10 seconds searching YouTube CC endpoints first. Only run the
     * free CC search upfront when Zero-Cloud is selected or credits have run dry.
     */
    fun shouldSkipUpfrontFreeCaptionSearch(
        quotaSaverMode: QuotaSaverMode,
        quotaCircuitBreakerTripped: Boolean,
    ): Boolean = quotaSaverMode != QuotaSaverMode.ZERO_CLOUD && !quotaCircuitBreakerTripped

    fun nextFallbackTierOnQuotaError(failedTier: ScoutExecutionTier): ScoutExecutionTier = when (failedTier) {
        ScoutExecutionTier.BEST_AI -> ScoutExecutionTier.LITE_AI
        ScoutExecutionTier.LITE_AI, ScoutExecutionTier.FREE_LOCAL -> ScoutExecutionTier.FREE_LOCAL
    }

    fun shouldAllowPassiveWebTimedTextOverride(
        quotaSaverMode: QuotaSaverMode,
        quotaCircuitBreakerTripped: Boolean,
        subtitleSource: SubtitleSourceKind,
        events: List<DialogueEvent>,
    ): Boolean {
        if (hasLoadedSubtitlesLocked(subtitleSource, events)) return false
        if (quotaSaverMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped) return true
        if (quotaSaverMode == QuotaSaverMode.PRO_ACCURACY) return false
        return !hasRealSubtitles(events)
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
     * Returns true when active playback reaches a pending window not yet completed by Scout
     * (and no existing dialogue cue covers the next ~1.1s), allowing a brief auto-buffer pause.
     */
    fun isPlayheadWaitingOnUnbufferedWindow(
        playheadMs: Long,
        totalDurationMs: Long,
        bufferedRanges: List<TimeWindowRange>,
        events: List<DialogueEvent>,
        isScoutActive: Boolean,
    ): Boolean {
        if (!isScoutActive || totalDurationMs <= 30_000L || bufferedRanges.isEmpty()) return false
        if (playheadMs >= totalDurationMs - 2_000L) return false
        val insideBuffered = bufferedRanges.any { playheadMs in it.startMs until it.endMs }
        if (insideBuffered) return false
        val hasUpcomingCue = events.any { evt ->
            evt.endTimeMs >= (playheadMs - 250L) &&
                evt.startTimeMs <= (playheadMs + 1_100L) &&
                !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                !evt.sourceText.startsWith("Web Cinema ready")
        }
        return !hasUpcomingCue
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

            // Lookback pre-boundary cue rescue: only drop if the exact pre-boundary moment
            // already has a covered cue from the same/compatible speaker or falls >4.5s earlier.
            val stalePreBoundaryMatch = if (candidate.startTimeMs < windowStartMs) {
                if (candidate.startTimeMs < (windowStartMs - 4_500L)) {
                    candidate
                } else {
                    merged.firstOrNull { prev ->
                        val overlapsOrSameMoment =
                            (candidate.startTimeMs <= prev.endTimeMs + 350L && candidate.endTimeMs >= prev.startTimeMs - 350L) ||
                                abs(prev.startTimeMs - candidate.startTimeMs) < 900L
                        overlapsOrSameMoment && (
                            areDuplicateUtterances(prev, candidate) ||
                                areSpeakersCompatible(prev.speakerId, candidate.speakerId)
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
                        incomingTextPreview = candidate.sourceText.take(48),
                        incomingStartMs = candidate.startTimeMs,
                        matchedStartMs = stalePreBoundaryMatch.startTimeMs,
                        speakerId = candidate.speakerId,
                    )
                )
                continue
            }

            merged.add(candidate)
            acceptedCount++
            if (decisions.size < 24) {
                decisions.add(
                    DuplicateDecisionRecord(
                        accepted = true,
                        reason = "ACCEPTED_EVENT",
                        incomingTextPreview = candidate.sourceText.take(48),
                        incomingStartMs = candidate.startTimeMs,
                        speakerId = candidate.speakerId,
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

    /**
     * Suppresses hallucinated opening-scene subtitle echoes ONLY when a late video window replays
     * opening dialogue at the start of the window (or across the majority of the chunk) with
     * compatible speaker identity. Legitimate repeated phrases, choruses, callbacks, or distinct-speaker
     * echoes elsewhere in a window are never discarded.
     */
    fun filterEchoedOpeningCues(
        existing: List<DialogueEvent>,
        incoming: List<DialogueEvent>,
        windowStartMs: Long,
    ): List<DialogueEvent> {
        if (incoming.isEmpty() || existing.isEmpty() || windowStartMs < 35_000L) return incoming
        val priorAnchors = existing.filter {
            it.startTimeMs < (windowStartMs - 40_000L).coerceAtLeast(0L) &&
                normalizeCueComparisonText(it.sourceText).length >= 16
        }
        if (priorAnchors.isEmpty()) return incoming

        val matchingIndices = mutableSetOf<Int>()
        incoming.forEachIndexed { idx, evt ->
            val norm = normalizeCueComparisonText(evt.sourceText)
            if (norm.length < 16) return@forEachIndexed
            val matchedPrior = priorAnchors.any { prior ->
                normalizeCueComparisonText(prior.sourceText) == norm &&
                    areSpeakersCompatible(prior.speakerId, evt.speakerId)
            }
            if (matchedPrior) {
                matchingIndices.add(idx)
            }
        }

        // Whole-chunk opening hallucination: >=2 matching lines making up >=50% of incoming chunk
        if (matchingIndices.size >= 2 && matchingIndices.size * 10 >= incoming.size * 5) {
            return emptyList()
        }

        // Leading-edge echo guard: only suppress a lone opening-edge replay (within first 4s of windowStartMs,
        // >=26 normalized chars) from the same/unassigned speaker when matching an earlier-scene line.
        return incoming.filterIndexed { idx, evt ->
            if (idx !in matchingIndices) return@filterIndexed true
            val norm = normalizeCueComparisonText(evt.sourceText)
            val atWindowOpening = (evt.startTimeMs - windowStartMs) in -1_000L..4_000L
            !(atWindowOpening && norm.length >= 26)
        }
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

