package com.example.domain.sentinel

import com.example.data.model.ChapterSource
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueLensProject
import com.example.data.model.QuotaSaverMode
import com.example.data.model.VideoChapter
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.TranscriptCoverageLedger
import kotlin.math.abs

/**
 * 4-tier unified priority queue across Scout and Sentinel workers (DialogueLens 2.0 Section 16).
 */
enum class SentinelPriorityTier(val rank: Int, val label: String) {
    CRITICAL_PLAYBACK_RECOVERY(0, "Critical Playback Recovery"),
    USER_DIRECTED_REPAIR(1, "User-Directed Repair"),
    PREVENTIVE_MAINTENANCE(2, "Preventive Chapter Patrol"),
    BACKGROUND_MAINTENANCE(3, "Background Maintenance"),
}

enum class SentinelTaskKind {
    PLAYBACK_LOOKAHEAD,
    USER_HEAL_BEACON,
    CHAPTER_BOUNDARY_PATROL,
    CHANGEOVER_PRELISTEN,
    EDITORIAL_TRANSITION_CHECK,
    BACKGROUND_GAP_FILL,
}

/**
 * Exact 7 chapter inspection states required by DialogueLens 2.0 Section 18.
 */
enum class ChapterInspectionStatus(val label: String) {
    NOT_INSPECTED("Not inspected"),
    INSPECTION_QUEUED("Inspection queued"),
    INSPECTING("Inspecting"),
    INSPECTED_WITHOUT_DEFECTS("Verified"),
    REPAIR_REQUIRED("Repair required"),
    REPAIR_IN_PROGRESS("Repairing"),
    REPAIR_VERIFIED("Repair verified"),
}

enum class ChapterBoundaryAnomalyKind(val label: String) {
    ECHOED_PRIOR_CHAPTER_DIALOGUE("Echoed prior-chapter dialogue"),
    MISSING_POST_BOUNDARY_DIALOGUE("Missing post-boundary dialogue"),
    STALE_OR_INVALID_ANCHOR("Stale or invalid coordinate anchor"),
    LOW_CONFIDENCE_OR_COVERAGE_DROP("Low confidence or coverage drop"),
}

data class SentinelWorkItem(
    val id: String,
    val tier: SentinelPriorityTier,
    val kind: SentinelTaskKind,
    val startMs: Long,
    val endMs: Long,
    val beaconId: String? = null,
    val chapterId: String? = null,
    val chapterTitle: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

data class IntervalReservation(
    val reservationId: String,
    val ownerId: String,
    val startMs: Long,
    val endMs: Long,
    val tier: SentinelPriorityTier,
    val reservedAtEpochMs: Long = System.currentTimeMillis(),
)

data class SentinelWorkerState(
    val workerId: String,
    val isActive: Boolean,
    val currentTaskLabel: String = "Idle",
    val activeStartMs: Long? = null,
    val activeEndMs: Long? = null,
    val tier: SentinelPriorityTier? = null,
)

data class ChapterPatrolRecord(
    val chapterId: String,
    val chapterTitle: String,
    val boundaryMs: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val status: ChapterInspectionStatus = ChapterInspectionStatus.NOT_INSPECTED,
    val detectedAnomalies: List<ChapterBoundaryAnomalyKind> = emptyList(),
    val summary: String = "Awaiting inspection",
    val cloudEscalated: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
)

data class ChapterLocalCheckResult(
    val chapterId: String,
    val boundaryMs: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val anomalies: List<ChapterBoundaryAnomalyKind>,
    val summary: String,
) {
    val hasDefects: Boolean get() = anomalies.isNotEmpty()
}

/**
 * Snapshot exposed strictly in Studio -> Diagnostics -> Sentinel Inspector (Section 29).
 * Never surfaced as cluttered agent chrome during normal Watch playback.
 */
data class SentinelInspectorSnapshot(
    val activeWorkerCount: Int = 0,
    val maxAllowedWorkers: Int = 1,
    val workers: List<SentinelWorkerState> = emptyList(),
    val queuedItems: List<SentinelWorkItem> = emptyList(),
    val activeReservations: List<IntervalReservation> = emptyList(),
    val chapterPatrolRecords: List<ChapterPatrolRecord> = emptyList(),
    val patrolSuspendedForPlayback: Boolean = false,
    val patrolSuspensionReason: String = "",
    val quotaMode: QuotaSaverMode = QuotaSaverMode.QUOTA_SAVER,
    val consecutiveQuotaErrors: Int = 0,
    val totalCompletedRepairs: Int = 0,
    val totalLocalCleanChapterChecks: Int = 0,
    val totalCloudEscalatedChapterChecks: Int = 0,
)

/**
 * Adaptive Sentinel Workforce Coordinator (DialogueLens 2.0 Part IV — Sections 15–20).
 *
 * Responsibilities:
 * - Coordinates a 4-tier unified priority queue across Scout and Sentinel workers.
 * - Maintains a shared interval reservation table so two workers never duplicate or race on
 *   overlapping `[startMs, endMs)` intervals.
 * - Dynamically scales from 1 to 2 workers ONLY when high-priority work exists AND quota,
 *   device health, and error signals permit.
 * - Proactively plans and inspects named YouTube chapter boundaries (`-10s/+15s`, clamped and
 *   merged) by default, running fast zero-cloud deterministic checks first and escalating to
 *   cloud repair only when real defects exist and playback lead is healthy.
 */
class SentinelWorkforceCoordinator {

    private val lock = Any()
    private var projectId: String = ""
    private val queue = mutableListOf<SentinelWorkItem>()
    private val reservations = linkedMapOf<String, IntervalReservation>()
    private val workers = linkedMapOf(
        WORKER_1_ID to SentinelWorkerState(workerId = WORKER_1_ID, isActive = false),
        WORKER_2_ID to SentinelWorkerState(workerId = WORKER_2_ID, isActive = false),
    )
    private val chapterRecords = linkedMapOf<String, ChapterPatrolRecord>()
    private val preListenedSeamKeys = mutableSetOf<String>()

    private var quotaMode: QuotaSaverMode = QuotaSaverMode.QUOTA_SAVER
    private var consecutiveQuotaErrors: Int = 0
    private var isBatteryOrThermalConstrained: Boolean = false
    private var isPlaybackBuffering: Boolean = false
    private var contiguousPlayheadLeadMs: Long = 0L
    private var totalCompletedRepairs: Int = 0
    private var totalLocalCleanChapterChecks: Int = 0
    private var totalCloudEscalatedChapterChecks: Int = 0

    fun resetForProject(project: DialogueLensProject) {
        synchronized(lock) {
            projectId = project.id
            queue.clear()
            reservations.clear()
            workers[WORKER_1_ID] = SentinelWorkerState(workerId = WORKER_1_ID, isActive = false)
            workers[WORKER_2_ID] = SentinelWorkerState(workerId = WORKER_2_ID, isActive = false)
            chapterRecords.clear()
            preListenedSeamKeys.clear()
            consecutiveQuotaErrors = 0
            totalCompletedRepairs = 0
            totalLocalCleanChapterChecks = 0
            totalCloudEscalatedChapterChecks = 0
            planDefaultChapterPatrolLocked(project)
        }
    }

    fun updateRuntimeConditions(
        quotaMode: QuotaSaverMode = this.quotaMode,
        isPlaybackBuffering: Boolean = this.isPlaybackBuffering,
        contiguousPlayheadLeadMs: Long = this.contiguousPlayheadLeadMs,
        isBatteryOrThermalConstrained: Boolean = this.isBatteryOrThermalConstrained,
    ) {
        synchronized(lock) {
            this.quotaMode = quotaMode
            this.isPlaybackBuffering = isPlaybackBuffering
            this.contiguousPlayheadLeadMs = contiguousPlayheadLeadMs.coerceAtLeast(0L)
            this.isBatteryOrThermalConstrained = isBatteryOrThermalConstrained
        }
    }

    fun recordQuotaError() {
        synchronized(lock) { consecutiveQuotaErrors++ }
    }

    fun recordQuotaSuccess() {
        synchronized(lock) { consecutiveQuotaErrors = 0 }
    }

    fun isPatrolSuspendedForPlayback(): Boolean = synchronized(lock) {
        isPatrolSuspendedLocked()
    }

    private fun isPatrolSuspendedLocked(): Boolean =
        isPlaybackBuffering || (contiguousPlayheadLeadMs in 1L until PATROL_MIN_HEALTHY_LEAD_MS)

    /**
     * Plans proactive `-10s/+15s` boundary inspection windows for every named YouTube chapter
     * boundary (> 0ms) by default, clamping to media duration and merging overlapping windows
     * so densely packed chapters never trigger redundant work.
     */
    fun planDefaultChapterPatrol(project: DialogueLensProject): List<ChapterPatrolRecord> = synchronized(lock) {
        if (projectId != project.id) {
            projectId = project.id
            chapterRecords.clear()
        }
        planDefaultChapterPatrolLocked(project)
        chapterRecords.values.toList()
    }

    private fun planDefaultChapterPatrolLocked(project: DialogueLensProject) {
        val durationMs = maxOf(
            project.videoSource.durationMs,
            project.chapters.maxOfOrNull { it.endMs } ?: 0L,
            project.dialogueEvents.maxOfOrNull { it.endTimeMs } ?: 0L,
        )
        if (durationMs <= 0L) return

        val namedBoundaries = project.chapters
            .filter { ch ->
                ch.startMs > 0L &&
                    ch.title.isNotBlank() &&
                    (ch.source == ChapterSource.YOUTUBE_DESCRIPTION || ch.source == ChapterSource.YOUTUBE_METADATA)
            }
            .sortedBy { it.startMs }
        if (namedBoundaries.isEmpty()) return

        // Merge closely spaced chapter boundaries whose [-10s, +15s] patrol windows overlap
        val mergedGroups = mutableListOf<MutableList<VideoChapter>>()
        for (chapter in namedBoundaries) {
            val winStart = (chapter.startMs - CHAPTER_PATROL_PRE_MS).coerceAtLeast(0L)
            val prevGroup = mergedGroups.lastOrNull()
            if (prevGroup != null) {
                val prevEnd = (prevGroup.last().startMs + CHAPTER_PATROL_POST_MS).coerceAtMost(durationMs)
                if (winStart <= prevEnd) {
                    prevGroup.add(chapter)
                    continue
                }
            }
            mergedGroups.add(mutableListOf(chapter))
        }

        for (group in mergedGroups) {
            val leadChapter = group.first()
            val windowStart = (group.first().startMs - CHAPTER_PATROL_PRE_MS).coerceAtLeast(0L)
            val windowEnd = (group.last().startMs + CHAPTER_PATROL_POST_MS).coerceAtMost(durationMs)
            if (windowEnd <= windowStart) continue

            val combinedTitle = if (group.size == 1) {
                leadChapter.title
            } else {
                "${leadChapter.title} (+${group.size - 1})"
            }
            val existing = chapterRecords[leadChapter.id]
            if (existing == null) {
                val record = ChapterPatrolRecord(
                    chapterId = leadChapter.id,
                    chapterTitle = combinedTitle,
                    boundaryMs = leadChapter.startMs,
                    windowStartMs = windowStart,
                    windowEndMs = windowEnd,
                    status = ChapterInspectionStatus.INSPECTION_QUEUED,
                    summary = "Queued for chapter transition check",
                )
                chapterRecords[leadChapter.id] = record
                enqueueLocked(
                    SentinelWorkItem(
                        id = "patrol_${leadChapter.id}",
                        tier = SentinelPriorityTier.PREVENTIVE_MAINTENANCE,
                        kind = SentinelTaskKind.CHAPTER_BOUNDARY_PATROL,
                        startMs = windowStart,
                        endMs = windowEnd,
                        chapterId = leadChapter.id,
                        chapterTitle = combinedTitle,
                    )
                )
            }
        }
    }

    /**
     * Proactively plans dedicated 2nd-Scout audio pre-listening for EVERY upcoming known
     * changeover seam ahead of [playheadMs] — combining BOTH:
     * 1. YouTube chapter boundaries (`project.chapters`)
     * 2. Nominal Scout analysis window boundaries (`35s, 75s, 115s, ...`)
     *
     * Merges seams within 12s of each other into one straddling `[seam - 6s, seam + 8s]`
     * pre-listen slice so Worker 2 catches and repairs changeover dropouts, echoes, and
     * speaker swaps BEFORE playback reaches the seam.
     */
    fun planUpcomingChangeoverPreListens(
        project: DialogueLensProject,
        playheadMs: Long,
        lookaheadMinMs: Long = CHANGEOVER_LOOKAHEAD_MIN_MS,
        lookaheadMaxMs: Long = CHANGEOVER_LOOKAHEAD_MAX_MS,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<SentinelWorkItem> = synchronized(lock) {
        if (projectId != project.id) {
            projectId = project.id
            chapterRecords.clear()
            preListenedSeamKeys.clear()
        }
        val durationMs = maxOf(
            project.videoSource.durationMs,
            project.chapters.maxOfOrNull { it.endMs } ?: 0L,
            project.dialogueEvents.maxOfOrNull { it.endTimeMs } ?: 0L,
        )
        if (durationMs <= 15_000L) return@synchronized emptyList()

        val durationSec = (durationMs / 1000L).toInt().coerceAtLeast(35)
        val chapterBoundaries = project.chapters
            .filter { it.startMs > 0L && it.startMs < durationMs }
            .map { it.startMs to (it as VideoChapter?) }
        val windowBoundaries = LookaheadWindowPlanner.buildSequentialPreGenWindows(durationSec)
            .drop(1)
            .map { (startSec, _) -> (startSec * 1000L) to (null as VideoChapter?) }
            .filter { (ms, _) -> ms in 10_000L until durationMs }

        val rawSeams = (chapterBoundaries + windowBoundaries).sortedBy { it.first }
        if (rawSeams.isEmpty()) return@synchronized emptyList()

        // Merge seams within 12s so a chapter boundary near a nominal 40s seam produces one clean straddling slice
        val mergedSeams = mutableListOf<Pair<Long, VideoChapter?>>()
        for ((seamMs, chapter) in rawSeams) {
            val prev = mergedSeams.lastOrNull()
            if (prev != null && abs(seamMs - prev.first) <= CHANGEOVER_MERGE_PROXIMITY_MS) {
                if (prev.second == null && chapter != null) {
                    mergedSeams[mergedSeams.lastIndex] = seamMs to chapter
                }
            } else {
                mergedSeams.add(seamMs to chapter)
            }
        }

        val minHorizon = (playheadMs + 2_000L).coerceAtLeast(0L)
        val maxHorizon = (playheadMs + lookaheadMaxMs).coerceAtMost(durationMs)
        val preferredMin = playheadMs + lookaheadMinMs

        val newlyQueued = mutableListOf<SentinelWorkItem>()
        val candidates = mergedSeams
            .filter { (seamMs, _) -> seamMs in minHorizon..maxHorizon }
            .sortedBy { (seamMs, _) ->
                if (seamMs >= preferredMin) seamMs - preferredMin else seamMs - minHorizon
            }

        for ((seamMs, chapter) in candidates) {
            val seamKey = changeoverSeamKey(seamMs)
            if (seamKey in preListenedSeamKeys) continue
            val winStart = (seamMs - CHANGEOVER_PRE_MS).coerceAtLeast(0L)
            val winEnd = (seamMs + CHANGEOVER_POST_MS).coerceAtMost(durationMs)
            if (winEnd - winStart < 4_000L) continue

            val item = SentinelWorkItem(
                id = seamKey,
                tier = SentinelPriorityTier.PREVENTIVE_MAINTENANCE,
                kind = SentinelTaskKind.CHANGEOVER_PRELISTEN,
                startMs = winStart,
                endMs = winEnd,
                chapterId = chapter?.id,
                chapterTitle = chapter?.title ?: "Transition ${seamMs / 1000L}s",
                createdAtEpochMs = nowEpochMs + (seamMs / 1000L),
            )
            if (enqueueLocked(item)) {
                newlyQueued.add(item)
            }
        }
        newlyQueued
    }

    fun markChangeoverPreListenCompleted(seamItemId: String) {
        synchronized(lock) {
            preListenedSeamKeys.add(seamItemId)
        }
    }

    fun markChangeoverPreListenFailed(seamItemId: String) {
        synchronized(lock) {
            preListenedSeamKeys.remove(seamItemId)
        }
    }

    fun isChangeoverSeamPreListened(seamMs: Long): Boolean = synchronized(lock) {
        changeoverSeamKey(seamMs) in preListenedSeamKeys
    }

    /**
     * Claims the next queued [SentinelTaskKind.CHANGEOVER_PRELISTEN] task for the dedicated
     * 2nd Scout worker while honoring interval reservations, quota gates, and patrol suspension.
     */
    fun claimChangeoverPreListenWork(
        workerId: String = WORKER_2_ID,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Pair<SentinelWorkItem, IntervalReservation>? = synchronized(lock) {
        val activeCount = workers.values.count { it.isActive }
        val isWorkerAlreadyActive = workers[workerId]?.isActive == true
        if (!isWorkerAlreadyActive && activeCount >= maxAllowedWorkersLocked()) {
            return@synchronized null
        }
        if (isPatrolSuspendedLocked()) return@synchronized null

        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.kind != SentinelTaskKind.CHANGEOVER_PRELISTEN) continue
            val reservation = tryReserveInterval(workerId, item.startMs, item.endMs, item.tier, nowEpochMs)
                ?: continue
            iterator.remove()
            workers[workerId] = SentinelWorkerState(
                workerId = workerId,
                isActive = true,
                currentTaskLabel = "Pre-listening ${item.chapterTitle ?: "scene changeover"}",
                activeStartMs = item.startMs,
                activeEndMs = item.endMs,
                tier = item.tier,
            )
            return@synchronized item to reservation
        }
        null
    }

    private fun changeoverSeamKey(seamMs: Long): String = "prelisten_${seamMs / 5_000L}"

    /**
     * Explicit Studio user action: queue or re-queue a chapter boundary for immediate inspection.
     */
    fun requestManualChapterInspection(
        project: DialogueLensProject,
        chapterId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ChapterPatrolRecord? = synchronized(lock) {
        val chapter = project.chapters.firstOrNull { it.id == chapterId } ?: return@synchronized null
        val durationMs = maxOf(project.videoSource.durationMs, chapter.endMs)
        val winStart = (chapter.startMs - CHAPTER_PATROL_PRE_MS).coerceAtLeast(0L)
        val winEnd = (chapter.startMs + CHAPTER_PATROL_POST_MS).coerceAtMost(maxOf(durationMs, winStart + 1_000L))
        val updated = ChapterPatrolRecord(
            chapterId = chapter.id,
            chapterTitle = chapter.title,
            boundaryMs = chapter.startMs,
            windowStartMs = winStart,
            windowEndMs = winEnd,
            status = ChapterInspectionStatus.INSPECTION_QUEUED,
            summary = "Manual inspection queued",
            updatedAtEpochMs = nowEpochMs,
        )
        chapterRecords[chapter.id] = updated
        enqueueLocked(
            SentinelWorkItem(
                id = "manual_patrol_${chapter.id}_$nowEpochMs",
                tier = SentinelPriorityTier.USER_DIRECTED_REPAIR,
                kind = SentinelTaskKind.CHAPTER_BOUNDARY_PATROL,
                startMs = winStart,
                endMs = winEnd,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                createdAtEpochMs = nowEpochMs,
            )
        )
        updated
    }

    /**
     * Enqueues a task into the 4-tier unified queue, deduplicating against identical pending items.
     */
    fun enqueue(item: SentinelWorkItem): Boolean = synchronized(lock) {
        enqueueLocked(item)
    }

    private fun enqueueLocked(item: SentinelWorkItem): Boolean {
        val duplicate = queue.any { existing ->
            existing.kind == item.kind &&
                existing.tier == item.tier &&
                abs(existing.startMs - item.startMs) <= 1_500L &&
                abs(existing.endMs - item.endMs) <= 1_500L &&
                existing.beaconId == item.beaconId &&
                existing.chapterId == item.chapterId
        }
        if (duplicate) return false
        queue.add(item)
        queue.sortWith(compareBy<SentinelWorkItem> { it.tier.rank }.thenBy { it.createdAtEpochMs })
        return true
    }

    /**
     * Shared interval reservation table (Section 16): attempts to reserve `[startMs, endMs)` for
     * [ownerId] (Scout or Sentinel worker). Returns null if an overlapping interval is already
     * reserved by another worker.
     */
    fun tryReserveInterval(
        ownerId: String,
        startMs: Long,
        endMs: Long,
        tier: SentinelPriorityTier,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): IntervalReservation? = synchronized(lock) {
        if (endMs <= startMs) return@synchronized null
        val conflict = reservations.values.any { existing ->
            existing.ownerId != ownerId &&
                minOf(existing.endMs, endMs) > maxOf(existing.startMs, startMs)
        }
        if (conflict) return@synchronized null
        val resId = "res_${ownerId}_${startMs}_${endMs}"
        val reservation = IntervalReservation(
            reservationId = resId,
            ownerId = ownerId,
            startMs = startMs,
            endMs = endMs,
            tier = tier,
            reservedAtEpochMs = nowEpochMs,
        )
        reservations[resId] = reservation
        reservation
    }

    fun releaseReservation(reservationId: String) {
        synchronized(lock) { reservations.remove(reservationId) }
    }

    fun releaseAllReservationsForOwner(ownerId: String) {
        synchronized(lock) {
            val keys = reservations.values.filter { it.ownerId == ownerId }.map { it.reservationId }
            keys.forEach { reservations.remove(it) }
        }
    }

    /**
     * Adaptive 1-to-2 worker gate (Section 15):
     * Scales up to 2 workers ONLY when high-priority work (Tier 1 or Tier 2) is queued,
     * cloud quota is available, there are no active quota errors, and device thermals/battery are healthy.
     */
    fun maxAllowedWorkers(): Int = synchronized(lock) {
        maxAllowedWorkersLocked()
    }

    private fun maxAllowedWorkersLocked(): Int {
        if (quotaMode == QuotaSaverMode.ZERO_CLOUD) return 1
        if (consecutiveQuotaErrors > 0 || isBatteryOrThermalConstrained) return 1
        val hasHighPriorityOrPreListenWork = queue.any {
            it.tier == SentinelPriorityTier.CRITICAL_PLAYBACK_RECOVERY ||
                it.tier == SentinelPriorityTier.USER_DIRECTED_REPAIR ||
                it.kind == SentinelTaskKind.CHANGEOVER_PRELISTEN
        }
        return if (hasHighPriorityOrPreListenWork) 2 else 1
    }

    fun canActivateSecondWorker(): Boolean = synchronized(lock) {
        val activeCount = workers.values.count { it.isActive }
        maxAllowedWorkersLocked() >= 2 && activeCount < 2
    }

    /**
     * Dispatches the highest-priority eligible work item to [workerId] while respecting:
     * - 4-tier priority ordering
     * - Patrol suspension during playback buffering / low lead
     * - Shared interval reservations (never double-assigns overlapping windows)
     */
    fun claimNextWork(
        workerId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Pair<SentinelWorkItem, IntervalReservation>? = synchronized(lock) {
        val activeCount = workers.values.count { it.isActive }
        val isWorkerAlreadyActive = workers[workerId]?.isActive == true
        if (!isWorkerAlreadyActive && activeCount >= maxAllowedWorkersLocked()) {
            return@synchronized null
        }

        val suspendPatrol = isPatrolSuspendedLocked()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (suspendPatrol && item.tier.rank >= SentinelPriorityTier.PREVENTIVE_MAINTENANCE.rank) {
                continue
            }
            val reservation = tryReserveInterval(workerId, item.startMs, item.endMs, item.tier, nowEpochMs)
                ?: continue
            iterator.remove()
            workers[workerId] = SentinelWorkerState(
                workerId = workerId,
                isActive = true,
                currentTaskLabel = when (item.kind) {
                    SentinelTaskKind.PLAYBACK_LOOKAHEAD -> "Preparing playback window"
                    SentinelTaskKind.USER_HEAL_BEACON -> "Healing marked moment"
                    SentinelTaskKind.CHAPTER_BOUNDARY_PATROL -> "Inspecting ${item.chapterTitle ?: "chapter"}"
                    SentinelTaskKind.CHANGEOVER_PRELISTEN -> "Pre-listening ${item.chapterTitle ?: "scene changeover"}"
                    SentinelTaskKind.EDITORIAL_TRANSITION_CHECK -> "Checking scene transition"
                    SentinelTaskKind.BACKGROUND_GAP_FILL -> "Completing background coverage"
                },
                activeStartMs = item.startMs,
                activeEndMs = item.endMs,
                tier = item.tier,
            )
            if (item.chapterId != null) {
                val prev = chapterRecords[item.chapterId]
                if (prev != null) {
                    chapterRecords[item.chapterId] = prev.copy(
                        status = ChapterInspectionStatus.INSPECTING,
                        summary = "Inspecting boundary locally",
                        updatedAtEpochMs = nowEpochMs,
                    )
                }
            }
            return@synchronized item to reservation
        }
        null
    }

    fun markWorkerIdle(workerId: String, reservationId: String? = null, repairCompleted: Boolean = false) {
        synchronized(lock) {
            if (reservationId != null) reservations.remove(reservationId)
            workers[workerId] = SentinelWorkerState(workerId = workerId, isActive = false)
            if (repairCompleted) totalCompletedRepairs++
        }
    }

    /**
     * Fast zero-cloud deterministic boundary inspection (Section 18).
     * Inspects `[boundary - 10s, boundary + 15s]` for:
     * 1. Echoed / repeated prior-chapter dialogue crossing the boundary
     * 2. Missing post-boundary dialogue inside unverified/failed coverage
     * 3. Stale or invalid coordinate anchors (`NaN` or out-of-bounds)
     * 4. Confidence / coverage drops across the chapter boundary
     *
     * Transitions the chapter to [ChapterInspectionStatus.INSPECTED_WITHOUT_DEFECTS] when clean
     * (zero cloud call!) or [ChapterInspectionStatus.REPAIR_REQUIRED] when defects exist.
     */
    fun runLocalChapterBoundaryCheck(
        project: DialogueLensProject,
        chapterId: String,
        ledger: TranscriptCoverageLedger,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ChapterLocalCheckResult = synchronized(lock) {
        val record = chapterRecords[chapterId]
            ?: run {
                val ch = project.chapters.firstOrNull { it.id == chapterId }
                val dur = maxOf(project.videoSource.durationMs, ch?.endMs ?: 0L)
                val b = ch?.startMs ?: 0L
                ChapterPatrolRecord(
                    chapterId = chapterId,
                    chapterTitle = ch?.title ?: chapterId,
                    boundaryMs = b,
                    windowStartMs = (b - CHAPTER_PATROL_PRE_MS).coerceAtLeast(0L),
                    windowEndMs = (b + CHAPTER_PATROL_POST_MS).coerceAtMost(maxOf(dur, b + 1_000L)),
                )
            }

        val boundary = record.boundaryMs
        val winStart = record.windowStartMs
        val winEnd = record.windowEndMs
        val anomalies = mutableListOf<ChapterBoundaryAnomalyKind>()

        val preEvents = project.dialogueEvents.filter { it.endTimeMs in winStart..(boundary + 1_500L) }
        val postEvents = project.dialogueEvents.filter { it.startTimeMs in (boundary - 1_500L)..winEnd }
        val windowEvents = project.dialogueEvents.filter {
            val mid = (it.startTimeMs + it.endTimeMs) / 2L
            mid in winStart..winEnd
        }

        // 1. Echoed prior-chapter dialogue crossing boundary
        val echoed = preEvents.any { pre ->
            val preText = pre.translatedText.ifBlank { pre.sourceText }.trim().lowercase()
            preText.length >= 8 && postEvents.any { post ->
                post.id != pre.id &&
                    post.startTimeMs >= boundary &&
                    post.translatedText.ifBlank { post.sourceText }.trim().lowercase() == preText
            }
        }
        if (echoed) anomalies.add(ChapterBoundaryAnomalyKind.ECHOED_PRIOR_CHAPTER_DIALOGUE)

        // 2. Missing post-boundary dialogue when post-boundary coverage is unverified/failed
        val postStatus = ledger.coverageStatusAt(boundary + 2_000L)
        val postLead = ledger.continuousVerifiedLeadFrom(boundary)
        if (postEvents.isEmpty() && postStatus != CoverageStatus.VERIFIED_SILENCE && postLead < 5_000L) {
            anomalies.add(ChapterBoundaryAnomalyKind.MISSING_POST_BOUNDARY_DIALOGUE)
        }

        // 3. Stale or invalid anchor coordinates in boundary window
        val hasInvalidAnchor = windowEvents.any { evt ->
            val a = evt.anchorPosition
            a != null && (a.x.isNaN() || a.y.isNaN() || a.x !in 0.02f..0.98f || a.y !in 0.02f..0.98f)
        }
        if (hasInvalidAnchor) anomalies.add(ChapterBoundaryAnomalyKind.STALE_OR_INVALID_ANCHOR)

        // 4. Confidence drop or failed interval across boundary
        val lowConfCount = windowEvents.count { it.speakerConfidence < 0.45f }
        if (ledger.hasFailedIntervalAt(boundary) || (windowEvents.isNotEmpty() && lowConfCount >= 2)) {
            anomalies.add(ChapterBoundaryAnomalyKind.LOW_CONFIDENCE_OR_COVERAGE_DROP)
        }

        val summary = if (anomalies.isEmpty()) {
            totalLocalCleanChapterChecks++
            "Verified clean locally — no cloud repair needed"
        } else {
            anomalies.joinToString(" · ") { it.label }
        }

        val nextStatus = if (anomalies.isEmpty()) {
            ChapterInspectionStatus.INSPECTED_WITHOUT_DEFECTS
        } else {
            ChapterInspectionStatus.REPAIR_REQUIRED
        }

        chapterRecords[chapterId] = record.copy(
            status = nextStatus,
            detectedAnomalies = anomalies,
            summary = summary,
            updatedAtEpochMs = nowEpochMs,
        )

        ChapterLocalCheckResult(
            chapterId = chapterId,
            boundaryMs = boundary,
            windowStartMs = winStart,
            windowEndMs = winEnd,
            anomalies = anomalies,
            summary = summary,
        )
    }

    fun markChapterRepairInProgress(chapterId: String, nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val existing = chapterRecords[chapterId] ?: return
            totalCloudEscalatedChapterChecks++
            chapterRecords[chapterId] = existing.copy(
                status = ChapterInspectionStatus.REPAIR_IN_PROGRESS,
                cloudEscalated = true,
                summary = "Repairing chapter boundary",
                updatedAtEpochMs = nowEpochMs,
            )
        }
    }

    fun markChapterRepairVerified(
        chapterId: String,
        summary: String = "Chapter boundary repaired and verified",
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val existing = chapterRecords[chapterId] ?: return
            totalCompletedRepairs++
            chapterRecords[chapterId] = existing.copy(
                status = ChapterInspectionStatus.REPAIR_VERIFIED,
                detectedAnomalies = emptyList(),
                summary = summary,
                updatedAtEpochMs = nowEpochMs,
            )
        }
    }

    fun inspectorSnapshot(): SentinelInspectorSnapshot = synchronized(lock) {
        val suspended = isPatrolSuspendedLocked()
        val suspensionReason = when {
            isPlaybackBuffering -> "Paused while playback prepares upcoming dialogue"
            suspended -> "Waiting for 15s of verified playback lead"
            else -> ""
        }
        SentinelInspectorSnapshot(
            activeWorkerCount = workers.values.count { it.isActive },
            maxAllowedWorkers = maxAllowedWorkersLocked(),
            workers = workers.values.toList(),
            queuedItems = queue.toList(),
            activeReservations = reservations.values.toList(),
            chapterPatrolRecords = chapterRecords.values.toList(),
            patrolSuspendedForPlayback = suspended,
            patrolSuspensionReason = suspensionReason,
            quotaMode = quotaMode,
            consecutiveQuotaErrors = consecutiveQuotaErrors,
            totalCompletedRepairs = totalCompletedRepairs,
            totalLocalCleanChapterChecks = totalLocalCleanChapterChecks,
            totalCloudEscalatedChapterChecks = totalCloudEscalatedChapterChecks,
        )
    }

    companion object {
        const val WORKER_1_ID = "sentinel_worker_1"
        const val WORKER_2_ID = "sentinel_worker_2"
        const val CHAPTER_PATROL_PRE_MS = 10_000L
        const val CHAPTER_PATROL_POST_MS = 15_000L
        const val PATROL_MIN_HEALTHY_LEAD_MS = 15_000L
        const val CHANGEOVER_PRE_MS = 6_000L
        const val CHANGEOVER_POST_MS = 8_000L
        const val CHANGEOVER_MERGE_PROXIMITY_MS = 12_000L
        const val CHANGEOVER_LOOKAHEAD_MIN_MS = 10_000L
        const val CHANGEOVER_LOOKAHEAD_MAX_MS = 55_000L
    }
}
