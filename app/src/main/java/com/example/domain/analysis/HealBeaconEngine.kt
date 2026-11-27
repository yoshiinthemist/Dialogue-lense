package com.example.domain.analysis

import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealProblemHint
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
    val preservedIdentityCount: Int = 0,
    val modifiedEventIds: Set<String> = emptySet(),
    val reason: String,
)

/**
 * Pure, deterministic core engine for DialogueLens 2.0 Heal Beacons ("Pause. Mark. Heal. Continue").
 * Guarantees:
 * - Adaptive ~±15s window expansion snapped to dialogue, sentence, shot, or chapter boundaries
 * - Quick / Scene / Chapter explicit repair scopes
 * - Active `HEALING` windows are immutable while a worker runs (new overlapping marks queue uncovered slices separately)
 * - Transactionally safe partial sub-range replacement: never deletes cues outside verified sub-ranges
 * - In-place cue ID preservation when refining an existing cue without changing its identity
 * - User-locked cue protection and stale revision rejection
 * - Full Undo recovery transitioning cleanly to [HealBeaconStatus.UNDONE]
 */
object HealBeaconEngine {

    const val DEFAULT_HALF_WINDOW_MS = 15_000L
    const val QUICK_HALF_WINDOW_MS = 8_000L
    const val MAX_ADAPTIVE_HALF_WINDOW_MS = 28_000L
    const val MAX_CHAPTER_HEAL_SPAN_MS = 180_000L

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

                val nearbyChapterBoundary = project.chapters.flatMap { listOf(it.startMs, it.endMs) }
                    .firstOrNull { boundary ->
                        (boundary in (start - 6_000L)..start) || (boundary in end..(end + 6_000L))
                    }
                if (nearbyChapterBoundary != null) {
                    if (nearbyChapterBoundary < start) start = (nearbyChapterBoundary - 2_000L).coerceAtLeast(0L)
                    if (nearbyChapterBoundary > end) end = (nearbyChapterBoundary + 2_000L).coerceAtMost(durationMs)
                }

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
            if (evt.startTimeMs < start && evt.endTimeMs > start) {
                start = evt.startTimeMs.coerceAtLeast(minAllowedStart)
            }
            if (evt.startTimeMs < end && evt.endTimeMs > end) {
                end = evt.endTimeMs.coerceAtMost(maxAllowedEnd)
            }
        }

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
     * - If an overlapping `MARKED` (queued, not yet running) beacon exists, merges cleanly into it.
     * - If an overlapping `HEALING` (actively running) beacon exists, NEVER mutates the running
     *   beacon's window! If the running window already covers [window], returns it; otherwise
     *   queues a separate follow-up `MARKED` beacon for the uncovered slice.
     */
    fun placeOrMergeBeacon(
        project: DialogueLensProject,
        timestampMs: Long,
        scope: HealScope = HealScope.ADAPTIVE,
        autoTriggered: Boolean = false,
        problemHint: HealProblemHint = HealProblemHint.NONE,
        patrolChapterId: String? = null,
        generationToken: Long = 0L,
        coordinatorJobId: String = "",
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Pair<DialogueLensProject, HealBeacon> {
        val window = computeWindow(timestampMs, scope, project)

        // 1. Check actively running HEALING beacons first — never mutate their window in flight
        val runningOverlap = project.healBeacons.firstOrNull { existing ->
            existing.status == HealBeaconStatus.HEALING &&
                windowsOverlap(existing.windowStartMs, existing.windowEndMs, window.startMs, window.endMs)
        }
        if (runningOverlap != null) {
            val fullyCovered = window.startMs >= runningOverlap.windowStartMs &&
                window.endMs <= runningOverlap.windowEndMs
            if (fullyCovered) {
                return project to runningOverlap
            }
            // Queue the uncovered tail/head as a separate MARKED beacon so the running worker remains immutable
            val uncoveredStart = if (window.endMs > runningOverlap.windowEndMs) {
                runningOverlap.windowEndMs
            } else {
                window.startMs
            }
            val uncoveredEnd = if (window.endMs > runningOverlap.windowEndMs) {
                window.endMs
            } else {
                runningOverlap.windowStartMs
            }
            if (uncoveredEnd - uncoveredStart >= 2_000L) {
                val followUp = HealBeacon(
                    id = "beacon_${UUID.randomUUID().toString().take(8)}",
                    timestampMs = timestampMs.coerceAtLeast(0L),
                    windowStartMs = uncoveredStart,
                    windowEndMs = uncoveredEnd,
                    status = HealBeaconStatus.MARKED,
                    scope = scope,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                    statusNote = "Queued right after current repair",
                    attemptCount = 0,
                    autoTriggered = autoTriggered,
                    problemHint = problemHint,
                    patrolChapterId = patrolChapterId,
                    generationToken = generationToken,
                    coordinatorJobId = coordinatorJobId,
                )
                val updated = (project.healBeacons + followUp).sortedBy { it.timestampMs }
                return project.copy(healBeacons = updated) to followUp
            }
            return project to runningOverlap
        }

        // 2. Merge into overlapping queued MARKED beacon to prevent duplicate requests
        val overlappingMarked = project.healBeacons.firstOrNull { existing ->
            existing.status == HealBeaconStatus.MARKED &&
                windowsOverlap(existing.windowStartMs, existing.windowEndMs, window.startMs, window.endMs)
        }
        if (overlappingMarked != null) {
            val merged = overlappingMarked.copy(
                windowStartMs = minOf(overlappingMarked.windowStartMs, window.startMs),
                windowEndMs = maxOf(overlappingMarked.windowEndMs, window.endMs),
                scope = if (scope.ordinal > overlappingMarked.scope.ordinal) scope else overlappingMarked.scope,
                updatedAtEpochMs = nowEpochMs,
                autoTriggered = overlappingMarked.autoTriggered && autoTriggered,
                problemHint = if (problemHint != HealProblemHint.NONE) problemHint else overlappingMarked.problemHint,
                statusNote = "Queued for localized repair",
                generationToken = maxOf(overlappingMarked.generationToken, generationToken),
                coordinatorJobId = coordinatorJobId.ifBlank { overlappingMarked.coordinatorJobId },
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
            problemHint = problemHint,
            patrolChapterId = patrolChapterId,
            generationToken = generationToken,
            coordinatorJobId = coordinatorJobId,
        )
        val updatedBeacons = (project.healBeacons + beacon).sortedBy { it.timestampMs }
        return project.copy(healBeacons = updatedBeacons) to beacon
    }

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
     * Transactionally validates and applies candidate replacement cues for [beaconId].
     * - When [verifiedSubRanges] is provided, replaces non-locked cues ONLY inside those verified
     *   sub-ranges so unverified portions of a wide repair window are NEVER wiped!
     * - Preserves existing cue IDs when a candidate refines an existing cue in place.
     * - Rejects stale commits if [expectedRepairRevision] does not match [HealBeacon.repairRevision].
     * - Never deletes existing dialogue on unconfirmed empty results.
     */
    fun applyValidatedLocalizedRepair(
        project: DialogueLensProject,
        beaconId: String,
        candidateEvents: List<DialogueEvent>,
        silenceExplicitlyConfirmed: Boolean = false,
        verifiedSubRanges: List<TimeWindowRange>? = null,
        expectedRepairRevision: Int? = null,
        expectedGenerationToken: Long? = null,
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

        if (beacon.status == HealBeaconStatus.SUPERSEDED || beacon.status == HealBeaconStatus.CANCELLED) {
            return LocalizedRepairOutcome(
                updatedProject = project,
                updatedBeacon = beacon,
                applied = false,
                replacedCount = 0,
                preservedLockedCount = 0,
                reason = "Beacon already ${beacon.status.name.lowercase()}",
            )
        }

        if (expectedGenerationToken != null &&
            beacon.generationToken > 0L &&
            beacon.generationToken != expectedGenerationToken
        ) {
            val superseded = beacon.copy(
                status = HealBeaconStatus.SUPERSEDED,
                updatedAtEpochMs = nowEpochMs,
                statusNote = "Superseded by newer scheduling generation",
            )
            return LocalizedRepairOutcome(
                updatedProject = project.copy(
                    healBeacons = project.healBeacons.map { if (it.id == beaconId) superseded else it },
                ),
                updatedBeacon = superseded,
                applied = false,
                replacedCount = 0,
                preservedLockedCount = 0,
                reason = "Stale generation token rejected",
            )
        }

        if (expectedRepairRevision != null && beacon.repairRevision != expectedRepairRevision) {
            return LocalizedRepairOutcome(
                updatedProject = project,
                updatedBeacon = beacon,
                applied = false,
                replacedCount = 0,
                preservedLockedCount = 0,
                reason = "Stale repair revision rejected (expected $expectedRepairRevision, current ${beacon.repairRevision})",
            )
        }

        val winStart = beacon.windowStartMs
        val winEnd = beacon.windowEndMs
        val effectiveReplacementRanges = verifiedSubRanges
            ?.filter { it.endMs > it.startMs }
            ?.map { TimeWindowRange(maxOf(winStart, it.startMs), minOf(winEnd, it.endMs)) }
            ?.filter { it.endMs > it.startMs }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(TimeWindowRange(winStart, winEnd))

        val isPartialWindowRepair = verifiedSubRanges != null &&
            (effectiveReplacementRanges.minOf { it.startMs } > winStart + 500L ||
                effectiveReplacementRanges.maxOf { it.endMs } < winEnd - 500L)

        fun isInsideEffectiveRanges(midMs: Long): Boolean =
            effectiveReplacementRanges.any { midMs in it.startMs..it.endMs }

        val unaffectedEvents = mutableListOf<DialogueEvent>()
        val lockedInsideWindow = mutableListOf<DialogueEvent>()
        val replaceableInsideWindow = mutableListOf<DialogueEvent>()

        for (evt in project.dialogueEvents) {
            val mid = (evt.startTimeMs + evt.endTimeMs) / 2L
            if (!isInsideEffectiveRanges(mid)) {
                unaffectedEvents.add(evt)
            } else if (evt.lockedByUser) {
                lockedInsideWindow.add(evt)
            } else {
                replaceableInsideWindow.add(evt)
            }
        }

        val rawValidCandidates = candidateEvents
            .filter { cand ->
                val text = cand.translatedText.ifBlank { cand.sourceText }.trim()
                val mid = (cand.startTimeMs + cand.endTimeMs) / 2L
                text.isNotBlank() &&
                    cand.endTimeMs > cand.startTimeMs &&
                    effectiveReplacementRanges.any { mid in (it.startMs - 500L)..(it.endMs + 500L) }
            }
            .map { cand ->
                cand.copy(
                    startTimeMs = cand.startTimeMs.coerceAtLeast(0L),
                    endTimeMs = cand.endTimeMs.coerceAtLeast(cand.startTimeMs + 350L),
                )
            }
            .filter { cand ->
                lockedInsideWindow.none { locked ->
                    temporalOverlapFraction(cand.startTimeMs, cand.endTimeMs, locked.startTimeMs, locked.endTimeMs) >= 0.45f
                }
            }
            .sortedBy { it.startTimeMs }

        if (rawValidCandidates.isEmpty() && !silenceExplicitlyConfirmed) {
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

        // In-place identity preservation: when a candidate refines an existing cue in place
        // (same/very similar text AND overlapping timestamps), preserve its cue ID so Compose
        // stability and layout anchors stay seamless.
        val claimedOriginalIds = mutableSetOf<String>()
        var preservedIdentityCount = 0
        val validCandidates = rawValidCandidates.map { cand ->
            val candText = cand.translatedText.ifBlank { cand.sourceText }
            val matchingOriginal = replaceableInsideWindow.firstOrNull { orig ->
                orig.id !in claimedOriginalIds &&
                    temporalOverlapFraction(cand.startTimeMs, cand.endTimeMs, orig.startTimeMs, orig.endTimeMs) >= 0.35f &&
                    normalizedTokenSimilarity(candText, orig.translatedText.ifBlank { orig.sourceText }) >= 0.65f
            }
            if (matchingOriginal != null) {
                claimedOriginalIds.add(matchingOriginal.id)
                preservedIdentityCount++
                cand.copy(id = matchingOriginal.id)
            } else {
                cand
            }
        }

        val snapshotToStore = if (beacon.preHealSnapshotEvents.isNotEmpty()) {
            beacon.preHealSnapshotEvents
        } else {
            replaceableInsideWindow
        }

        val mergedEvents = (unaffectedEvents + lockedInsideWindow + validCandidates)
            .sortedWith(compareBy<DialogueEvent> { it.startTimeMs }.thenBy { it.endTimeMs })

        val beforeSample = replaceableInsideWindow
            .take(2)
            .joinToString(" / ") { it.translatedText.ifBlank { it.sourceText }.trim() }
            .take(140)
        val afterSample = validCandidates
            .take(2)
            .joinToString(" / ") { it.translatedText.ifBlank { it.sourceText }.trim() }
            .take(140)
        val summary = when {
            validCandidates.isEmpty() && silenceExplicitlyConfirmed ->
                "Verified silence in this moment."
            isPartialWindowRepair ->
                "Partially verified ${validCandidates.size} line(s); untouched sections preserved."
            lockedInsideWindow.isNotEmpty() ->
                "Updated ${validCandidates.size} line(s) · preserved ${lockedInsideWindow.size} manual edit(s)."
            else ->
                "Updated ${validCandidates.size} dialogue line(s) in this moment."
        }

        val healedBeacon = beacon.copy(
            status = if (isPartialWindowRepair) HealBeaconStatus.NEEDS_ATTENTION else HealBeaconStatus.HEALED,
            updatedAtEpochMs = nowEpochMs,
            attemptCount = beacon.attemptCount + 1,
            repairRevision = beacon.repairRevision + 1,
            preHealSnapshotEvents = snapshotToStore,
            replacedEventIds = validCandidates.map { it.id },
            statusNote = summary,
            changeSummary = summary,
            beforeSampleText = beforeSample,
            afterSampleText = afterSample,
        )

        val updatedProject = project.copy(
            dialogueEvents = mergedEvents,
            healBeacons = project.healBeacons.map { if (it.id == beaconId) healedBeacon else it },
        )
        val modifiedIds = (replaceableInsideWindow.map { it.id } + validCandidates.map { it.id }).toSet()

        return LocalizedRepairOutcome(
            updatedProject = updatedProject,
            updatedBeacon = healedBeacon,
            applied = true,
            replacedCount = validCandidates.size,
            preservedLockedCount = lockedInsideWindow.size,
            preservedIdentityCount = preservedIdentityCount,
            modifiedEventIds = modifiedIds,
            reason = summary,
        )
    }

    /**
     * Reverts a healed beacon back to its pre-heal snapshot while preserving unaffected cues
     * and any user-locked edits made after the heal. Transitions status to [HealBeaconStatus.UNDONE].
     */
    fun undoBeaconRepair(
        project: DialogueLensProject,
        beaconId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DialogueLensProject {
        val beacon = project.healBeacons.firstOrNull { it.id == beaconId } ?: return project
        if (beacon.status != HealBeaconStatus.HEALED && beacon.status != HealBeaconStatus.NEEDS_ATTENTION) return project
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
            status = HealBeaconStatus.UNDONE,
            updatedAtEpochMs = nowEpochMs,
            repairRevision = beacon.repairRevision + 1,
            replacedEventIds = emptyList(),
            statusNote = "Repair undone — original dialogue restored.",
            changeSummary = "Restored ${restoredOriginals.size} original line(s).",
        )

        return project.copy(
            dialogueEvents = mergedEvents,
            healBeacons = project.healBeacons.map { if (it.id == beaconId) revertedBeacon else it },
        )
    }

    fun markBeaconSuperseded(
        project: DialogueLensProject,
        beaconId: String,
        cancelled: Boolean = false,
        reason: String = if (cancelled) "Repair cancelled" else "Superseded by newer playback context",
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DialogueLensProject {
        val nextStatus = if (cancelled) HealBeaconStatus.CANCELLED else HealBeaconStatus.SUPERSEDED
        val updated = project.healBeacons.map { b ->
            if (b.id == beaconId && (b.status == HealBeaconStatus.MARKED || b.status == HealBeaconStatus.HEALING)) {
                b.copy(
                    status = nextStatus,
                    updatedAtEpochMs = nowEpochMs,
                    statusNote = reason,
                )
            } else {
                b
            }
        }
        return project.copy(healBeacons = updated)
    }

    fun supersedeOrCancelStaleBeacons(
        project: DialogueLensProject,
        activeGenerationToken: Long,
        cancelActiveHealing: Boolean = false,
        reason: String = "Superseded after seek or configuration change",
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DialogueLensProject {
        var changed = false
        val updated = project.healBeacons.map { b ->
            val isPending = b.status == HealBeaconStatus.MARKED ||
                (cancelActiveHealing && b.status == HealBeaconStatus.HEALING)
            val isStale = b.generationToken in 1L until activeGenerationToken
            if (isPending && isStale) {
                changed = true
                b.copy(
                    status = if (b.status == HealBeaconStatus.HEALING) HealBeaconStatus.CANCELLED else HealBeaconStatus.SUPERSEDED,
                    updatedAtEpochMs = nowEpochMs,
                    statusNote = reason,
                )
            } else {
                b
            }
        }
        return if (changed) project.copy(healBeacons = updated) else project
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

    private fun normalizedTokenSimilarity(a: String, b: String): Float {
        val tokensA = a.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val tokensB = b.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }
}
