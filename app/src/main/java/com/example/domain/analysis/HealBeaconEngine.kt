package com.example.domain.analysis

import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealScope
import java.util.UUID
import kotlin.math.abs

data class HealWindow(
    val startMs: Long,
    val endMs: Long,
    val scope: HealScope,
)

data class LocalizedRepairOutcome(
    val updatedProject: DialogueLensProject,
    val updatedBeacon: HealBeacon,
    val applied: Boolean,
    val replacedCount: Int,
    val preservedLockedCount: Int,
    val reason: String,
)

/**
 * Pure, deterministic core engine for DialogueLens's Heal Beacon system ("Pause. Mark. Heal. Continue").
 * Handles:
 * - Adaptive ~±15s window expansion snapped to dialogue, sentence, shot, or chapter boundaries
 * - Quick / Scene / Chapter explicit repair scopes for Studio & Beacon Detail
 * - Overlapping beacon merging & duplicate-request suppression
 * - Safe, atomic, validated localized replacement preserving unaffected cues & user-locked edits
 * - Full Undo recovery and restart restoration of interrupted HEALING beacons
 */
object HealBeaconEngine {

    const val DEFAULT_HALF_WINDOW_MS = 15_000L
    const val QUICK_HALF_WINDOW_MS = 8_000L
    const val MAX_ADAPTIVE_HALF_WINDOW_MS = 28_000L
    const val MAX_CHAPTER_HEAL_SPAN_MS = 180_000L

    /**
     * Computes the exact time window to reanalyze for a Heal Beacon.
     * - [HealScope.ADAPTIVE]: starts at ±15s and expands smoothly to capture complete overlapping
     *   dialogue lines, sentence boundaries, nearby shot cuts, and chapter boundaries.
     * - [HealScope.QUICK]: tight ±8s around the marked moment.
     * - [HealScope.SCENE]: covers the full affected shot/scene (bounded safely).
     * - [HealScope.CHAPTER]: covers the full active chapter (capped around the marked timestamp if very long).
     */
    fun computeWindow(
        timestampMs: Long,
        scope: HealScope,
        project: DialogueLensProject,
    ): HealWindow {
        val durationMs = maxOf(
            project.videoSource.durationMs,
            project.dialogueEvents.maxOfOrNull { it.endTimeMs } ?: 0L,
            timestampMs + DEFAULT_HALF_WINDOW_MS,
        ).coerceAtLeast(15_000L)
        val clampedTs = timestampMs.coerceIn(0L, durationMs)

        when (scope) {
            HealScope.QUICK -> {
                val baseStart = (clampedTs - QUICK_HALF_WINDOW_MS).coerceAtLeast(0L)
                val baseEnd = (clampedTs + QUICK_HALF_WINDOW_MS).coerceAtMost(durationMs)
                return expandToDialogueBoundaries(baseStart, baseEnd, clampedTs, 14_000L, durationMs, project.dialogueEvents, scope)
            }
            HealScope.SCENE -> {
                val activeShot = project.shots.firstOrNull { clampedTs in it.startMs..it.endMs }
                val shotSpan = if (activeShot != null) activeShot.endMs - activeShot.startMs else 0L
                val isUsefulShot = activeShot != null && shotSpan in 6_000L..90_000L
                val rawStart = if (isUsefulShot) {
                    (activeShot!!.startMs - 2_000L).coerceAtLeast(0L)
                } else {
                    (clampedTs - 22_000L).coerceAtLeast(0L)
                }
                val rawEnd = if (isUsefulShot) {
                    (activeShot!!.endMs + 2_000L).coerceAtMost(durationMs)
                } else {
                    (clampedTs + 22_000L).coerceAtMost(durationMs)
                }
                return expandToDialogueBoundaries(rawStart, rawEnd, clampedTs, 45_000L, durationMs, project.dialogueEvents, scope)
            }
            HealScope.CHAPTER -> {
                val chapter = project.chapters.firstOrNull { clampedTs in it.startMs..it.endMs }
                if (chapter != null) {
                    val span = chapter.endMs - chapter.startMs
                    if (span <= MAX_CHAPTER_HEAL_SPAN_MS) {
                        return HealWindow(
                            startMs = chapter.startMs.coerceAtLeast(0L),
                            endMs = chapter.endMs.coerceAtMost(durationMs),
                            scope = scope,
                        )
                    }
                    // Very long chapter: focus on a 2-minute window centered on the marked moment inside the chapter
                    val start = maxOf(chapter.startMs, clampedTs - 60_000L)
                    val end = minOf(chapter.endMs, clampedTs + 60_000L).coerceAtMost(durationMs)
                    return HealWindow(startMs = start, endMs = end.coerceAtLeast(start + 10_000L), scope = scope)
                }
                val start = (clampedTs - 45_000L).coerceAtLeast(0L)
                val end = (clampedTs + 45_000L).coerceAtMost(durationMs)
                return HealWindow(startMs = start, endMs = end, scope = scope)
            }
            HealScope.ADAPTIVE -> {
                var start = (clampedTs - DEFAULT_HALF_WINDOW_MS).coerceAtLeast(0L)
                var end = (clampedTs + DEFAULT_HALF_WINDOW_MS).coerceAtMost(durationMs)

                // Include nearby chapter transition if within 6s of window edge so context stays coherent
                val nearbyChapterBoundary = project.chapters.flatMap { listOf(it.startMs, it.endMs) }
                    .firstOrNull { boundary ->
                        (boundary in (start - 6_000L)..start) || (boundary in end..(end + 6_000L))
                    }
                if (nearbyChapterBoundary != null) {
                    if (nearbyChapterBoundary < start) start = (nearbyChapterBoundary - 2_000L).coerceAtLeast(0L)
                    if (nearbyChapterBoundary > end) end = (nearbyChapterBoundary + 2_000L).coerceAtMost(durationMs)
                }

                // Expand to complete dialogue segments & sentence endings
                return expandToDialogueBoundaries(
                    rawStart = start,
                    rawEnd = end,
                    anchorTs = clampedTs,
                    maxHalfSpan = MAX_ADAPTIVE_HALF_WINDOW_MS,
                    durationMs = durationMs,
                    events = project.dialogueEvents,
                    scope = scope,
                )
            }
        }
    }

    private fun expandToDialogueBoundaries(
        rawStart: Long,
        rawEnd: Long,
        anchorTs: Long,
        maxHalfSpan: Long,
        durationMs: Long,
        events: List<DialogueEvent>,
        scope: HealScope,
    ): HealWindow {
        val minAllowedStart = (anchorTs - maxHalfSpan).coerceAtLeast(0L)
        val maxAllowedEnd = (anchorTs + maxHalfSpan).coerceAtMost(durationMs)
        var start = rawStart.coerceAtLeast(minAllowedStart)
        var end = rawEnd.coerceAtMost(maxAllowedEnd)

        val sorted = events.sortedBy { it.startTimeMs }
        for (evt in sorted) {
            // Pull start back to avoid bisecting a cue at the left edge
            if (evt.startTimeMs < start && evt.endTimeMs > start) {
                start = evt.startTimeMs.coerceAtLeast(minAllowedStart)
            }
            // Push end forward to avoid bisecting a cue at the right edge
            if (evt.startTimeMs < end && evt.endTimeMs > end) {
                end = evt.endTimeMs.coerceAtMost(maxAllowedEnd)
            }
        }

        // If the boundary cue does not end with terminal punctuation, extend one cue further if within budget
        val lastInside = sorted.lastOrNull { it.endTimeMs <= end && it.endTimeMs >= start }
        if (lastInside != null && !endsWithSentencePunctuation(lastInside.translatedText.ifBlank { lastInside.sourceText })) {
            val nextCue = sorted.firstOrNull { it.startTimeMs >= lastInside.endTimeMs && it.startTimeMs - lastInside.endTimeMs <= 2_500L }
            if (nextCue != null && nextCue.endTimeMs <= maxAllowedEnd) {
                end = nextCue.endTimeMs
            }
        }

        return HealWindow(
            startMs = start.coerceIn(0L, durationMs),
            endMs = end.coerceIn(start + 1_000L, maxOf(durationMs, start + 1_000L)),
            scope = scope,
        )
    }

    private fun endsWithSentencePunctuation(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val last = trimmed.last()
        return last == '.' || last == '!' || last == '?' || last == '。' || last == '！' || last == '？'
    }

    /**
     * Creates or merges a Heal Beacon for [timestampMs].
     * If an active (`MARKED` or `HEALING`) beacon already overlaps the computed window, merges
     * them cleanly into a single unified window rather than launching duplicate conflicting cloud jobs.
     */
    fun placeOrMergeBeacon(
        project: DialogueLensProject,
        timestampMs: Long,
        scope: HealScope = HealScope.ADAPTIVE,
        autoTriggered: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Pair<DialogueLensProject, HealBeacon> {
        val window = computeWindow(timestampMs, scope, project)

        // Check for overlapping active beacon to prevent duplicate overlapping requests
        val coveringRunning = project.healBeacons.firstOrNull { existing ->
            existing.status == HealBeaconStatus.HEALING &&
                existing.windowStartMs <= window.startMs && existing.windowEndMs >= window.endMs
        }
        if (coveringRunning != null) return project to coveringRunning
        val overlappingActive = project.healBeacons.firstOrNull { existing ->
            existing.status == HealBeaconStatus.MARKED &&
                windowsOverlap(existing.windowStartMs, existing.windowEndMs, window.startMs, window.endMs)
        }
        if (overlappingActive != null) {
            val merged = overlappingActive.copy(
                windowStartMs = minOf(overlappingActive.windowStartMs, window.startMs),
                windowEndMs = maxOf(overlappingActive.windowEndMs, window.endMs),
                scope = if (scope.ordinal > overlappingActive.scope.ordinal) scope else overlappingActive.scope,
                updatedAtEpochMs = nowEpochMs,
                autoTriggered = overlappingActive.autoTriggered && autoTriggered,
                statusNote = "Queued for localized repair",
            )
            val updatedBeacons = project.healBeacons.map { if (it.id == merged.id) merged else it }
            return project.copy(healBeacons = updatedBeacons) to merged
        }

        val beacon = HealBeacon(
            id = "beacon_${UUID.randomUUID().toString().take(8)}",
            timestampMs = timestampMs.coerceAtLeast(0L),
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            status = HealBeaconStatus.MARKED,
            scope = scope,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            statusNote = "Queued for localized repair",
            attemptCount = 0,
            autoTriggered = autoTriggered,
        )
        val updatedBeacons = (project.healBeacons + beacon).sortedBy { it.timestampMs }
        return project.copy(healBeacons = updatedBeacons) to beacon
    }

    /**
     * Restores any beacons left in [HealBeaconStatus.HEALING] across an app crash/restart back to
     * [HealBeaconStatus.MARKED] so unfinished repairs survive restarts safely.
     */
    fun restoreInterruptedBeacons(
        project: DialogueLensProject,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DialogueLensProject {
        if (project.healBeacons.none { it.status == HealBeaconStatus.HEALING }) return project
        val restored = project.healBeacons.map { beacon ->
            if (beacon.status == HealBeaconStatus.HEALING) {
                beacon.copy(
                    status = HealBeaconStatus.MARKED,
                    updatedAtEpochMs = nowEpochMs,
                    statusNote = "Ready to resume healing",
                )
            } else {
                beacon
            }
        }
        return project.copy(healBeacons = restored)
    }

    /**
     * Validates candidate replacement cues for [beacon] and applies them atomically ONLY inside
     * `[beacon.windowStartMs, beacon.windowEndMs]`:
     * - Unaffected dialogue outside the repair window is strictly untouched.
     * - User-confirmed (`lockedByUser = true`) dialogue inside the window is strictly preserved.
     * - Rejects empty/invalid candidate sets when existing cues are present unless silence was
     *   explicitly confirmed, marking the beacon [HealBeaconStatus.NEEDS_ATTENTION] instead of
     *   silently wiping good subtitles.
     * - Stores [HealBeacon.preHealSnapshotEvents] so the user can Undo the repair cleanly.
     */
    fun applyValidatedLocalizedRepair(
        project: DialogueLensProject,
        beaconId: String,
        candidateEvents: List<DialogueEvent>,
        silenceExplicitlyConfirmed: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): LocalizedRepairOutcome {
        val beacon = project.healBeacons.firstOrNull { it.id == beaconId }
            ?: return LocalizedRepairOutcome(
                updatedProject = project,
                updatedBeacon = HealBeacon(
                    id = beaconId,
                    timestampMs = 0L,
                    windowStartMs = 0L,
                    windowEndMs = 0L,
                    status = HealBeaconStatus.NEEDS_ATTENTION,
                    statusNote = "Beacon not found",
                ),
                applied = false,
                replacedCount = 0,
                preservedLockedCount = 0,
                reason = "Beacon not found",
            )

        val winStart = beacon.windowStartMs
        val winEnd = beacon.windowEndMs

        // Partition existing events: strictly outside window vs inside window
        val unaffectedEvents = mutableListOf<DialogueEvent>()
        val lockedInsideWindow = mutableListOf<DialogueEvent>()
        val replaceableInsideWindow = mutableListOf<DialogueEvent>()

        for (evt in project.dialogueEvents) {
            val mid = (evt.startTimeMs + evt.endTimeMs) / 2L
            val inWindow = mid in winStart..winEnd
            if (!inWindow) {
                unaffectedEvents.add(evt)
            } else if (evt.lockedByUser) {
                lockedInsideWindow.add(evt)
            } else {
                replaceableInsideWindow.add(evt)
            }
        }

        // Filter candidates strictly to the repair window and sanitize invalid timestamps/blank text
        val validCandidates = candidateEvents
            .filter { cand ->
                val text = cand.translatedText.ifBlank { cand.sourceText }.trim()
                val mid = (cand.startTimeMs + cand.endTimeMs) / 2L
                text.isNotBlank() &&
                    cand.endTimeMs > cand.startTimeMs &&
                    mid in (winStart - 500L)..(winEnd + 500L)
            }
            .map { cand ->
                cand.copy(
                    startTimeMs = cand.startTimeMs.coerceAtLeast(0L),
                    endTimeMs = cand.endTimeMs.coerceAtLeast(cand.startTimeMs + 350L),
                )
            }
            // Never duplicate or overwrite a user-locked cue covering the same time slot
            .filter { cand ->
                lockedInsideWindow.none { locked ->
                    temporalOverlapFraction(cand.startTimeMs, cand.endTimeMs, locked.startTimeMs, locked.endTimeMs) >= 0.45f
                }
            }
            .sortedBy { it.startTimeMs }

        // Validation gate: do NOT blindly declare success if AI returned empty when dialogue existed and silence wasn't verified
        if (validCandidates.isEmpty() && !silenceExplicitlyConfirmed) {
            val failedBeacon = beacon.copy(
                status = HealBeaconStatus.NEEDS_ATTENTION,
                updatedAtEpochMs = nowEpochMs,
                attemptCount = beacon.attemptCount + 1,
                statusNote = if (replaceableInsideWindow.isNotEmpty()) {
                    "Could not verify a clearer repair — kept your existing dialogue."
                } else {
                    "No clearer dialogue could be verified for this moment."
                },
            )
            val updatedProject = project.copy(
                healBeacons = project.healBeacons.map { if (it.id == beaconId) failedBeacon else it },
            )
            return LocalizedRepairOutcome(
                updatedProject = updatedProject,
                updatedBeacon = failedBeacon,
                applied = false,
                replacedCount = 0,
                preservedLockedCount = lockedInsideWindow.size,
                reason = failedBeacon.statusNote,
            )
        }

        // Preserve any prior snapshot if retrying an already-healed beacon, else snapshot pre-heal state
        val snapshotToStore = if (beacon.preHealSnapshotEvents.isNotEmpty()) {
            beacon.preHealSnapshotEvents
        } else {
            replaceableInsideWindow
        }

        val mergedEvents = (unaffectedEvents + lockedInsideWindow + validCandidates)
            .sortedWith(compareBy<DialogueEvent> { it.startTimeMs }.thenBy { it.endTimeMs })

        val healedBeacon = beacon.copy(
            status = HealBeaconStatus.HEALED,
            updatedAtEpochMs = nowEpochMs,
            attemptCount = beacon.attemptCount + 1,
            preHealSnapshotEvents = snapshotToStore,
            replacedEventIds = validCandidates.map { it.id },
            statusNote = when {
                validCandidates.isEmpty() && silenceExplicitlyConfirmed ->
                    "Verified silence in this moment."
                lockedInsideWindow.isNotEmpty() ->
                    "Updated ${validCandidates.size} line(s) · preserved ${lockedInsideWindow.size} manual edit(s)."
                else ->
                    "Updated ${validCandidates.size} dialogue line(s) in this moment."
            },
        )

        val updatedProject = project.copy(
            dialogueEvents = mergedEvents,
            healBeacons = project.healBeacons.map { if (it.id == beaconId) healedBeacon else it },
        )

        return LocalizedRepairOutcome(
            updatedProject = updatedProject,
            updatedBeacon = healedBeacon,
            applied = true,
            replacedCount = validCandidates.size,
            preservedLockedCount = lockedInsideWindow.size,
            reason = healedBeacon.statusNote,
        )
    }

    /**
     * Reverts a healed beacon back to its pre-heal snapshot while preserving unaffected cues
     * and any user-locked edits made after the heal.
     */
    fun undoBeaconRepair(
        project: DialogueLensProject,
        beaconId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DialogueLensProject {
        val beacon = project.healBeacons.firstOrNull { it.id == beaconId } ?: return project
        if (beacon.status != HealBeaconStatus.HEALED) return project
        if (beacon.preHealSnapshotEvents.isEmpty() && beacon.replacedEventIds.isEmpty()) return project

        val replacedIds = beacon.replacedEventIds.toSet()
        val withoutReplaced = project.dialogueEvents.filterNot { evt ->
            evt.id in replacedIds && !evt.lockedByUser
        }
        val existingIds = withoutReplaced.map { it.id }.toSet()
        val restoredOriginals = beacon.preHealSnapshotEvents.filter { it.id !in existingIds }

        val mergedEvents = (withoutReplaced + restoredOriginals)
            .sortedWith(compareBy<DialogueEvent> { it.startTimeMs }.thenBy { it.endTimeMs })

        val revertedBeacon = beacon.copy(
            status = HealBeaconStatus.MARKED,
            updatedAtEpochMs = nowEpochMs,
            replacedEventIds = emptyList(),
            statusNote = "Repair undone — original dialogue restored.",
        )

        return project.copy(
            dialogueEvents = mergedEvents,
            healBeacons = project.healBeacons.map { if (it.id == beaconId) revertedBeacon else it },
        )
    }

    fun dismissBeacon(project: DialogueLensProject, beaconId: String): DialogueLensProject =
        project.copy(healBeacons = project.healBeacons.filterNot { it.id == beaconId })

    private fun windowsOverlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
        minOf(aEnd, bEnd) - maxOf(aStart, bStart) >= -2_000L

    private fun temporalOverlapFraction(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Float {
        val overlap = (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)
        val minDur = minOf(aEnd - aStart, bEnd - bStart).coerceAtLeast(1L)
        return overlap.toFloat() / minDur.toFloat()
    }
}
