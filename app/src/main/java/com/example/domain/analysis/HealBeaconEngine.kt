package com.example.domain.analysis

import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealScope
import java.util.UUID
import com.example.data.model.SubtitleSourceKind

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
        val durationMs = project.videoSource.durationMs.takeIf { it > 0L } ?: maxOf(
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
                val activeShot = project.shots.firstOrNull { clampedTs >= it.startMs && (clampedTs < it.endMs || clampedTs == durationMs && it.endMs == durationMs) }
                val shotSpan = if (activeShot != null) activeShot.endMs - activeShot.startMs else 0L
                val isUsefulShot = activeShot != null && shotSpan in 6_000L..90_000L
                val rawStart = if (isUsefulShot) {
                    (activeShot.startMs - 2_000L).coerceAtLeast(0L)
                } else {
                    (clampedTs - 22_000L).coerceAtLeast(0L)
                }
                val rawEnd = if (isUsefulShot) {
                    (activeShot.endMs + 2_000L).coerceAtMost(durationMs)
                } else {
                    (clampedTs + 22_000L).coerceAtMost(durationMs)
                }
                return expandToDialogueBoundaries(rawStart, rawEnd, clampedTs, 45_000L, durationMs, project.dialogueEvents, scope)
            }
            HealScope.CHAPTER -> {
                val chapter = project.chapters.firstOrNull { clampedTs >= it.startMs && (clampedTs < it.endMs || clampedTs == durationMs && it.endMs == durationMs) }
                if (chapter != null && chapter.startMs < durationMs && chapter.endMs > chapter.startMs) {
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
                    return HealWindow(startMs = start, endMs = end, scope = scope)
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
            endMs = end.coerceIn(start.coerceAtMost(durationMs), durationMs),
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
        window: HealWindow = computeWindow(timestampMs, scope, project),
    ): Pair<DialogueLensProject, HealBeacon> {

        require(window.endMs > window.startMs) { "Repair window must have positive duration" }
        val running = project.healBeacons.filter { it.status == HealBeaconStatus.HEALING &&
            it.windowStartMs < window.endMs && it.windowEndMs > window.startMs }.sortedBy { it.windowStartMs }
        var updated = project
        var result: HealBeacon? = null
        var cursor = window.startMs
        val uncovered = mutableListOf<HealWindow>()
        for (active in running) {
            if (active.windowStartMs > cursor) uncovered.add(window.copy(startMs = cursor, endMs = active.windowStartMs))
            if (timestampMs in active.windowStartMs until active.windowEndMs) {
                result = if (!autoTriggered && active.autoTriggered) {
                    // Keep the active analysis request immutable while making the manual mark visible.
                    val promoted = active.copy(autoTriggered = false, timestampMs = timestampMs)
                    updated = updated.copy(healBeacons = updated.healBeacons.map { if (it.id == active.id) promoted else it })
                    promoted
                } else active
            }
            cursor = maxOf(cursor, active.windowEndMs)
        }
        if (cursor < window.endMs) uncovered.add(window.copy(startMs = cursor))
        for (piece in uncovered) {
            val existing = updated.healBeacons.firstOrNull { pending ->
                pending.status == HealBeaconStatus.MARKED &&
                    windowsOverlap(pending.windowStartMs, pending.windowEndMs, piece.startMs, piece.endMs) &&
                    running.none { active -> active.windowStartMs < maxOf(piece.endMs, pending.windowEndMs) &&
                        active.windowEndMs > minOf(piece.startMs, pending.windowStartMs) }
            }
            val markedTime = timestampMs.coerceIn(piece.startMs, piece.endMs)
            val mark = existing?.copy(
                timestampMs = if (existing.autoTriggered && !autoTriggered) markedTime else existing.timestampMs,
                windowStartMs = minOf(existing.windowStartMs, piece.startMs),
                windowEndMs = maxOf(existing.windowEndMs, piece.endMs),
                scope = if (scope.ordinal > existing.scope.ordinal) scope else existing.scope,
                updatedAtEpochMs = nowEpochMs, autoTriggered = existing.autoTriggered && autoTriggered,
            ) ?: HealBeacon(
                id = "beacon_${UUID.randomUUID()}", timestampMs = markedTime, windowStartMs = piece.startMs,
                windowEndMs = piece.endMs, scope = scope, createdAtEpochMs = nowEpochMs, updatedAtEpochMs = nowEpochMs,
                statusNote = "Queued for localized repair", autoTriggered = autoTriggered,
            )
            updated = updated.copy(healBeacons = (updated.healBeacons.filterNot { it.id == mark.id } + mark).sortedBy { it.timestampMs })
            if (result == null || timestampMs in piece.startMs until piece.endMs) result = mark
        }
        return updated to (result ?: running.first())
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

        if (beacon.status != HealBeaconStatus.HEALING) {
            return LocalizedRepairOutcome(project, beacon, false, 0, 0, "Attempt superseded")
        }
        val originals = windowEvents(project, beacon)
        val protectedIds = project.corrections.mapTo(mutableSetOf()) { it.eventId }
        val locked = originals.filter { it.lockedByUser || it.id in protectedIds }
        val authoritative = project.subtitleSource in setOf(SubtitleSourceKind.UPLOADED_FILE, SubtitleSourceKind.EMBEDDED_TRACK)
        val writable = originals.filter {
            it !in locked && it.startTimeMs >= beacon.windowStartMs && it.endTimeMs <= beacon.windowEndMs
        }
        val replacements = linkedMapOf<String, DialogueEvent>()
        val additions = linkedMapOf<String, DialogueEvent>()
        val existingIds = project.dialogueEvents.mapTo(mutableSetOf()) { it.id }
        val validCandidates = candidateEvents.filter {
            it.id.isNotBlank() && it.startTimeMs >= beacon.windowStartMs && it.endTimeMs <= beacon.windowEndMs &&
                it.endTimeMs > it.startTimeMs && it.translatedText.ifBlank { it.sourceText }.isNotBlank()
        }
        var uncertain = validCandidates.size != candidateEvents.size
        for (candidate in validCandidates) {
            val overlaps = originals.filter { candidate.startTimeMs < it.endTimeMs && candidate.endTimeMs > it.startTimeMs }
            if (overlaps.any { it in locked }) continue
            val exact = writable.firstOrNull { it.id == candidate.id }
            val match = exact ?: overlaps.singleOrNull()?.takeIf {
                it in writable && temporalOverlapFraction(it.startTimeMs, it.endTimeMs, candidate.startTimeMs, candidate.endTimeMs) >= 0.65f &&
                    (it.speakerId == null || candidate.speakerId == null || it.speakerId == candidate.speakerId ||
                        it.sourceText.equals(candidate.sourceText, ignoreCase = true))
            }
            if (match != null && !replacements.containsKey(match.id)) {
                // The cue identity follows the utterance through text, timing and spatial refinements.
                val refined = if (authoritative) {
                    match.copy(anchorPosition = candidate.anchorPosition, placementMode = candidate.placementMode,
                        faceTrackId = candidate.faceTrackId, faceConfidence = candidate.faceConfidence)
                } else candidate.copy(id = match.id)
                replacements[match.id] = refined
            } else if (!authoritative && overlaps.isEmpty() && candidate.id !in existingIds &&
                candidate.id !in additions && additions.values.none { it.startTimeMs < candidate.endTimeMs &&
                    it.endTimeMs > candidate.startTimeMs && it.sourceText.equals(candidate.sourceText, ignoreCase = true) }) {
                additions[candidate.id] = candidate
            } else {
                uncertain = true
            }
        }
        val changed = writable.filter { replacements[it.id]?.let { after -> after != it } == true }
        val appliedEvents = changed.map { replacements.getValue(it.id) } + additions.values
        val confirmedQuiet = silenceExplicitlyConfirmed && originals.isEmpty() && candidateEvents.isEmpty()
        val success = appliedEvents.isNotEmpty() || confirmedQuiet
        val note = when {
            confirmedQuiet -> "Verified silence in this moment."
            appliedEvents.isNotEmpty() && uncertain -> "Updated ${appliedEvents.size} lines. Other observations still need review."
            appliedEvents.isNotEmpty() -> "Updated ${appliedEvents.size} lines. Other dialogue was preserved."
            silenceExplicitlyConfirmed && originals.isNotEmpty() -> "The silence result conflicts with existing dialogue. Your lines were kept."
            else -> "No change could be verified. Your existing dialogue was kept."
        }
        val updated = beacon.copy(
            status = if (success && !uncertain) HealBeaconStatus.HEALED else HealBeaconStatus.NEEDS_ATTENTION,
            attemptCount = beacon.attemptCount + 1,
            updatedAtEpochMs = nowEpochMs,
            statusNote = note,
            preHealSnapshotEvents = if (appliedEvents.isEmpty()) beacon.preHealSnapshotEvents else changed,
            postHealSnapshotEvents = appliedEvents.ifEmpty { beacon.postHealSnapshotEvents },
            replacedEventIds = if (appliedEvents.isEmpty()) beacon.replacedEventIds else appliedEvents.map { it.id },
        )
        val dialogue = if (appliedEvents.isEmpty()) project.dialogueEvents else
            (project.dialogueEvents.map { replacements[it.id] ?: it } + additions.values)
                .sortedWith(compareBy<DialogueEvent> { it.startTimeMs }.thenBy { it.endTimeMs })
        return LocalizedRepairOutcome(project.copy(
            dialogueEvents = dialogue,
            healBeacons = project.healBeacons.map { if (it.id == beaconId) updated else it },
        ), updated, success, appliedEvents.size, locked.size, note)
    }

    fun windowEvents(project: DialogueLensProject, beacon: HealBeacon): List<DialogueEvent> =
        project.dialogueEvents.filter { it.startTimeMs < beacon.windowEndMs && it.endTimeMs > beacon.windowStartMs }

    fun undoBeaconRepair(
        project: DialogueLensProject,
        beaconId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DialogueLensProject {
        val beacon = project.healBeacons.firstOrNull { it.id == beaconId } ?: return project
        if (beacon.status == HealBeaconStatus.HEALING || beacon.status == HealBeaconStatus.UNDONE ||
            (beacon.replacedEventIds.isEmpty() && beacon.preHealSnapshotEvents.isEmpty() && beacon.preRepairCoverage.isEmpty())) return project
        val expected = beacon.postHealSnapshotEvents.associateBy { it.id }
        val original = beacon.preHealSnapshotEvents.associateBy { it.id }
        val legacy = expected.isEmpty()
        val correctedIds = project.corrections.mapTo(mutableSetOf()) { it.eventId }
        var skipped = false
        val result = project.dialogueEvents.mapNotNull { current ->
            if (current.id !in beacon.replacedEventIds) return@mapNotNull current
            if (current.lockedByUser || current.id in correctedIds || (!legacy && expected[current.id] != current)) {
                skipped = true
                current
            } else original[current.id]
        }.toMutableList()
        if (legacy) {
            // Older projects used fresh IDs on every repair. Do not duplicate a later user edit.
            for (old in original.values) {
                if (result.none { it.id == old.id || it.startTimeMs < old.endTimeMs && it.endTimeMs > old.startTimeMs }) result.add(old)
            }
        }
        val undone = beacon.copy(status = HealBeaconStatus.UNDONE, replacedEventIds = emptyList(),
            preHealSnapshotEvents = emptyList(), postHealSnapshotEvents = emptyList(), preRepairCoverage = emptyList(), coverageRepairToken = null,
            updatedAtEpochMs = nowEpochMs, revision = beacon.revision + 1,
            statusNote = if (skipped) "Repair undone where safe. Later edits were preserved." else "Repair undone. Original dialogue restored.")
        return project.copy(dialogueEvents = result.sortedWith(compareBy<DialogueEvent> { it.startTimeMs }.thenBy { it.endTimeMs }),
            healBeacons = project.healBeacons.map { if (it.id == beaconId) undone else it })
    }

    fun dismissBeacon(project: DialogueLensProject, beaconId: String): DialogueLensProject =
        project.copy(healBeacons = project.healBeacons.filterNot { it.id == beaconId })

    private fun windowsOverlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
        minOf(aEnd, bEnd) - maxOf(aStart, bStart) >= -2_000L

    private fun temporalOverlapFraction(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Float {
        val overlap = (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)
        val unionMs = (maxOf(aEnd, bEnd) - minOf(aStart, bStart)).coerceAtLeast(1L)
        return overlap.toFloat() / unionMs.toFloat()
    }
}
