package com.example.domain.sentinel

import com.example.data.model.ChapterSource
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeacon
import com.example.data.model.PersistedSchedulingState
import com.example.data.model.QuotaSaverMode
import com.example.data.model.VideoChapter
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.pipeline.TimestampAdjustmentRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    SCOUT2_REQUIRED_REVIEW,
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

/**
 * Configurable scheduling policy owned by [SentinelWorkforceCoordinator].
 * Keeps all automatic expensive Scout 1 / Scout 2 / patrol / repair work strictly bounded to
 * `min(durationMs, acknowledgedViewerPosMs + horizonMs)` (default 180 seconds).
 */
data class RollingBoundaryPolicy(
    val horizonMs: Long = 180_000L,
    val refillAdvanceMs: Long = 15_000L,
    val refillLeadThresholdMs: Long = 150_000L,
    val stopThresholdMs: Long = 180_000L,
    val minSubmittableSliceMs: Long = 2_000L,
    val chunkOverlapMs: Long = 0L,
    val maxPendingReviewDrafts: Int = 2,
    val maxRetriesPerInterval: Int = 3,
    val pausedQualityDeadlineMs: Long = 60_000L,
)

/**
 * Distinct, honest scheduler outcomes — reaching the 3-minute boundary is NEVER reported as
 * whole-video completion.
 */
enum class SchedulerOutcomeState(val label: String) {
    ACTIVE("Active within 3-min window"),
    WINDOW_READY_IDLE("Current window ready"),
    WAITING_FOR_EVIDENCE_OR_CAPABILITY("Waiting for evidence or capability"),
    UNRESOLVED_AFTER_BOUNDED_RECOVERY("Unresolved after bounded recovery"),
    WHOLE_VIDEO_COMPLETE("Whole video complete"),
}

sealed interface BoundarySubmissionDecision {
    data class Allowed(
        val startMs: Long,
        val endMs: Long,
        val wasClipped: Boolean,
        val effectiveSubmittedEndMs: Long,
        val schedulingGeneration: Long,
    ) : BoundarySubmissionDecision

    data class DeferredOutsideBoundary(
        val reason: String,
        val boundaryEndMs: Long,
    ) : BoundarySubmissionDecision
}

data class SchedulerStateSnapshot(
    val acknowledgedViewerPosMs: Long = 0L,
    val durationMs: Long = 0L,
    val boundaryEndMs: Long = 0L,
    val schedulingGeneration: Long = 0L,
    val isViewerPaused: Boolean = false,
    val isScrubberPreviewActive: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val continuousVerifiedLeadMs: Long = 0L,
    val viewingTimeReserveMs: Long = 0L,
    val pendingReviewDraftCount: Int = 0,
    val isScout1ThrottledForReviewBackpressure: Boolean = false,
    val outcomeState: SchedulerOutcomeState = SchedulerOutcomeState.ACTIVE,
    val isTemporarilyIdle: Boolean = false,
    val lastIdleAnchorMs: Long = -1L,
    val lastIdleBoundaryEndMs: Long = -1L,
    val lastWakeReason: String = "",
    val idleReason: String = "",
    val pausedDeadlineReached: Boolean = false,
    val policy: RollingBoundaryPolicy = RollingBoundaryPolicy(),
)

data class SentinelWorkItem(
    val id: String,
    val tier: SentinelPriorityTier,
    val kind: SentinelTaskKind,
    val startMs: Long,
    val endMs: Long,
    val beaconId: String? = null,
    val chapterId: String? = null,
    val chapterTitle: String? = null,
    val draftRevision: Long = 0L,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val schedulingGeneration: Long = 0L,
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
    val schedulerState: SchedulerStateSnapshot = SchedulerStateSnapshot(),
    val latestScout1ReportSummary: String = "",
    val totalTimestampsVerified: Int = 0,
    val totalTimestampsAdjusted: Int = 0,
    val recentTimestampAdjustments: List<TimestampAdjustmentRecord> = emptyList(),
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
    private var latestScout1ReportSummary: String = ""
    private var totalTimestampsVerified: Int = 0
    private var totalTimestampsAdjusted: Int = 0
    private val recentTimestampAdjustments = mutableListOf<TimestampAdjustmentRecord>()

    // Single authoritative rolling 3-minute boundary & idle/wake state
    private var policy: RollingBoundaryPolicy = RollingBoundaryPolicy()
    private var boundaryAnchored: Boolean = false
    private var pendingAsyncSeekAck: Boolean = false
    private var acknowledgedViewerPosMs: Long = 0L
    private var durationMs: Long = 0L
    private var boundaryEndMs: Long = 0L
    private var schedulingGeneration: Long = 0L
    private var isViewerPaused: Boolean = false
    private var pausedSinceEpochMs: Long = 0L
    private var isScrubberPreviewActive: Boolean = false
    private var playbackSpeed: Float = 1.0f
    private var pendingReviewDraftCount: Int = 0
    private var outcomeState: SchedulerOutcomeState = SchedulerOutcomeState.ACTIVE
    private var isTemporarilyIdle: Boolean = false
    private var lastIdleAnchorMs: Long = -1L
    private var lastIdleBoundaryEndMs: Long = -1L
    private var lastWakeReason: String = ""
    private var idleReason: String = ""
    private var pausedDeadlineReached: Boolean = false

    fun configurePolicy(newPolicy: RollingBoundaryPolicy) {
        synchronized(lock) {
            policy = newPolicy.copy(
                horizonMs = newPolicy.horizonMs.coerceAtLeast(15_000L),
                refillAdvanceMs = newPolicy.refillAdvanceMs.coerceAtLeast(1_000L),
                maxPendingReviewDrafts = newPolicy.maxPendingReviewDrafts.coerceAtLeast(1),
                maxRetriesPerInterval = newPolicy.maxRetriesPerInterval.coerceAtLeast(1),
            )
            recomputeBoundaryLocked()
        }
    }

    fun currentPolicy(): RollingBoundaryPolicy = synchronized(lock) { policy }

    fun currentSchedulingGeneration(): Long = synchronized(lock) { schedulingGeneration }

    fun currentBoundaryEndMs(): Long = synchronized(lock) {
        if (boundaryAnchored) boundaryEndMs else minOf(durationMs.takeIf { it > 0L } ?: policy.horizonMs, acknowledgedViewerPosMs + policy.horizonMs)
    }

    fun currentAcknowledgedViewerPosMs(): Long = synchronized(lock) { acknowledgedViewerPosMs }

    fun isScrubberPreviewActive(): Boolean = synchronized(lock) { isScrubberPreviewActive }

    fun isAnalysisDispatchAllowed(): Boolean = synchronized(lock) {
        !isScrubberPreviewActive && quotaMode != QuotaSaverMode.ZERO_CLOUD
    }

    fun updatePendingReviewCount(count: Int) {
        synchronized(lock) {
            pendingReviewDraftCount = count.coerceAtLeast(0)
        }
    }

    /**
     * Backpressure guard: when Scout 1 gets ahead of Scout 2 and pending drafts hit
     * [RollingBoundaryPolicy.maxPendingReviewDrafts], throttle/pause new Scout 1 preparation
     * until Scout 2 drains review work.
     */
    fun shouldThrottleScout1ForReviewBackpressure(): Boolean = synchronized(lock) {
        pendingReviewDraftCount >= policy.maxPendingReviewDrafts
    }

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
            latestScout1ReportSummary = ""
            totalTimestampsVerified = 0
            totalTimestampsAdjusted = 0
            recentTimestampAdjustments.clear()

            val projDur = maxOf(
                project.videoSource.durationMs,
                project.chapters.maxOfOrNull { it.endMs } ?: 0L,
                project.dialogueEvents.maxOfOrNull { it.endTimeMs } ?: 0L,
            ).coerceAtLeast(0L)
            durationMs = projDur
            val persisted = project.schedulingState
            if (persisted != null) {
                acknowledgedViewerPosMs = persisted.acknowledgedViewerPosMs.coerceIn(0L, projDur.coerceAtLeast(0L))
                schedulingGeneration = persisted.schedulingGeneration.coerceAtLeast(0L)
                isViewerPaused = persisted.isViewerPaused
                lastIdleAnchorMs = persisted.lastIdleAnchorMs
                lastIdleBoundaryEndMs = persisted.lastIdleBoundaryEndMs
                outcomeState = SchedulerOutcomeState.entries.firstOrNull { it.name == persisted.schedulerOutcome }
                    ?: SchedulerOutcomeState.ACTIVE
                isTemporarilyIdle = outcomeState == SchedulerOutcomeState.WINDOW_READY_IDLE ||
                    outcomeState == SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY ||
                    outcomeState == SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE
            } else {
                acknowledgedViewerPosMs = project.lastPlaybackPositionMs.coerceIn(0L, projDur.coerceAtLeast(0L))
                schedulingGeneration = 0L
                isViewerPaused = false
                lastIdleAnchorMs = -1L
                lastIdleBoundaryEndMs = -1L
                outcomeState = SchedulerOutcomeState.ACTIVE
                isTemporarilyIdle = false
            }
            boundaryAnchored = projDur > 0L
            isScrubberPreviewActive = false
            pendingReviewDraftCount = 0
            pausedSinceEpochMs = 0L
            pausedDeadlineReached = false
            idleReason = ""
            lastWakeReason = "project_reset"
            recomputeBoundaryLocked()
            planDefaultChapterPatrolLocked(project)
        }
    }

    /**
     * Updates the rolling boundary from the viewer's ACTUAL acknowledged playback position.
     * - Ignored while [isScrubberPreviewActive] is true so preview drags NEVER move the boundary.
     * - Rejects stale callbacks carrying an older [seekGeneration].
     * - While viewer is intentionally paused, the boundary stays strictly pinned to the paused
     *   anchor and NEVER drifts forward as scouts advance.
     *
     * Returns `true` iff the scheduler transitioned from idle -> active due to meaningful playback
     * advancement (hysteresis refill threshold crossed), so the worker loop wakes cleanly without
     * timer-tick or self-publication churn.
     */
    fun updateAcknowledgedViewerPosition(
        positionMs: Long,
        durationMs: Long = this.durationMs,
        isPlaying: Boolean = !this.isViewerPaused,
        playbackSpeed: Float = this.playbackSpeed,
        seekGeneration: Long = this.schedulingGeneration,
        nowEpochMs: Long = System.currentTimeMillis(),
        seekAcknowledged: Boolean = false,
    ): Boolean = synchronized(lock) {
        if (isScrubberPreviewActive) return@synchronized false
        if (seekGeneration < schedulingGeneration) return@synchronized false

        val nextDur = maxOf(durationMs, 0L)
        if (nextDur > 0L) {
            this.durationMs = nextDur
        }
        this.playbackSpeed = playbackSpeed.coerceIn(0.25f, 4.0f)

        val clampedPos = if (this.durationMs > 0L) {
            positionMs.coerceIn(0L, this.durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }

        val isSeekAckLanding = seekAcknowledged || pendingAsyncSeekAck
        // Useful pausing rule: while paused without a landing seek acknowledgement,
        // NEVER drift the anchored viewer position or boundary forward!
        if (!isSeekAckLanding && !isPlaying && isViewerPaused && boundaryAnchored) {
            recomputeBoundaryLocked()
            return@synchronized false
        }

        val wasPaused = isViewerPaused
        isViewerPaused = !isPlaying
        if (isViewerPaused && !wasPaused) {
            pausedSinceEpochMs = nowEpochMs
            pausedDeadlineReached = false
        } else if (!isViewerPaused) {
            pausedSinceEpochMs = 0L
            pausedDeadlineReached = false
        }

        acknowledgedViewerPosMs = clampedPos
        pendingAsyncSeekAck = false
        boundaryAnchored = this.durationMs > 0L
        recomputeBoundaryLocked()
        if (isSeekAckLanding) {
            queue.removeAll { item ->
                val outsideNewWindow = boundaryAnchored &&
                    (item.endMs <= acknowledgedViewerPosMs || item.startMs >= boundaryEndMs)
                item.kind != SentinelTaskKind.USER_HEAL_BEACON && outsideNewWindow
            }
            val obsoleteRes = reservations.values.filter { res ->
                boundaryAnchored && (res.endMs <= acknowledgedViewerPosMs || res.startMs >= boundaryEndMs)
            }.map { it.reservationId }
            obsoleteRes.forEach { reservations.remove(it) }
            isTemporarilyIdle = false
            outcomeState = SchedulerOutcomeState.ACTIVE
            idleReason = ""
            lastIdleAnchorMs = -1L
            lastIdleBoundaryEndMs = -1L
            lastWakeReason = "seek_ack_${acknowledgedViewerPosMs / 1000L}s_gen$schedulingGeneration"
            return@synchronized true
        }

        // Hysteresis wake-up check when temporarily idle
        if (isTemporarilyIdle && outcomeState != SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE) {
            val anchorAdvance = if (lastIdleAnchorMs >= 0L) {
                abs(acknowledgedViewerPosMs - lastIdleAnchorMs)
            } else {
                Long.MAX_VALUE
            }
            val boundaryAdvance = if (lastIdleBoundaryEndMs >= 0L) {
                boundaryEndMs - lastIdleBoundaryEndMs
            } else {
                0L
            }
            val reachedVideoEndDelta = this.durationMs > 0L &&
                boundaryEndMs >= this.durationMs &&
                lastIdleBoundaryEndMs in 0L until this.durationMs
            val leadDroppedBelowRefill = contiguousPlayheadLeadMs < policy.refillLeadThresholdMs &&
                boundaryAdvance >= policy.minSubmittableSliceMs

            if (anchorAdvance >= policy.refillAdvanceMs ||
                boundaryAdvance >= policy.refillAdvanceMs ||
                reachedVideoEndDelta ||
                leadDroppedBelowRefill
            ) {
                isTemporarilyIdle = false
                outcomeState = SchedulerOutcomeState.ACTIVE
                idleReason = ""
                lastWakeReason = "playback_advance_${acknowledgedViewerPosMs / 1000L}s"
                return@synchronized true
            }
        }
        false
    }

    fun onViewerPauseChanged(
        paused: Boolean,
        acknowledgedPositionMs: Long,
        durationMs: Long = this.durationMs,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            if (durationMs > 0L) this.durationMs = durationMs
            val clamped = if (this.durationMs > 0L) {
                acknowledgedPositionMs.coerceIn(0L, this.durationMs)
            } else {
                acknowledgedPositionMs.coerceAtLeast(0L)
            }
            isViewerPaused = paused
            acknowledgedViewerPosMs = clamped
            boundaryAnchored = this.durationMs > 0L
            if (paused) {
                pausedSinceEpochMs = nowEpochMs
                pausedDeadlineReached = false
            } else {
                pausedSinceEpochMs = 0L
                pausedDeadlineReached = false
            }
            recomputeBoundaryLocked()
        }
    }

    fun onPlaybackSpeedChanged(speed: Float) {
        synchronized(lock) {
            playbackSpeed = speed.coerceIn(0.25f, 4.0f)
        }
    }

    fun onScrubberPreviewStateChanged(isPreviewing: Boolean) {
        synchronized(lock) {
            isScrubberPreviewActive = isPreviewing
        }
    }

    /**
     * Committed seek: bumps [schedulingGeneration], clears preview mode, re-anchors the rolling
     * 3-minute boundary to [targetPositionMs], prunes obsolete queued work outside the new
     * boundary or from older generations, and wakes the scheduler cleanly.
     */
    fun onCommittedSeek(
        targetPositionMs: Long,
        durationMs: Long = this.durationMs,
        isPlaying: Boolean = !this.isViewerPaused,
        externalSeekGeneration: Long? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        anchorBoundaryImmediately: Boolean = true,
    ): Long = synchronized(lock) {
        if (durationMs > 0L) this.durationMs = durationMs
        isScrubberPreviewActive = false
        schedulingGeneration = if (externalSeekGeneration != null && externalSeekGeneration > schedulingGeneration) {
            externalSeekGeneration
        } else {
            schedulingGeneration + 1L
        }
        val clamped = if (this.durationMs > 0L) {
            targetPositionMs.coerceIn(0L, this.durationMs)
        } else {
            targetPositionMs.coerceAtLeast(0L)
        }
        if (anchorBoundaryImmediately) {
            acknowledgedViewerPosMs = clamped
            pendingAsyncSeekAck = false
        } else {
            pendingAsyncSeekAck = true
        }
        isViewerPaused = !isPlaying
        pausedSinceEpochMs = if (isViewerPaused) nowEpochMs else 0L
        pausedDeadlineReached = false
        boundaryAnchored = this.durationMs > 0L
        recomputeBoundaryLocked()

        // Cancel/prune obsolete queued items from prior generations (and outside anchored window)
        queue.removeAll { item ->
            val staleGen = item.schedulingGeneration > 0L && item.schedulingGeneration < schedulingGeneration
            val outsideNewWindow = anchorBoundaryImmediately &&
                boundaryAnchored &&
                (item.endMs <= acknowledgedViewerPosMs || item.startMs >= boundaryEndMs)
            staleGen || (item.kind != SentinelTaskKind.USER_HEAL_BEACON && outsideNewWindow)
        }
        // Release stale reservations outside the new window
        val obsoleteRes = reservations.values.filter { res ->
            anchorBoundaryImmediately && boundaryAnchored &&
                (res.endMs <= acknowledgedViewerPosMs || res.startMs >= boundaryEndMs)
        }.map { it.reservationId }
        obsoleteRes.forEach { reservations.remove(it) }

        isTemporarilyIdle = false
        outcomeState = SchedulerOutcomeState.ACTIVE
        idleReason = ""
        lastIdleAnchorMs = -1L
        lastIdleBoundaryEndMs = -1L
        lastWakeReason = "committed_seek_${clamped / 1000L}s_gen$schedulingGeneration"
        schedulingGeneration
    }

    /**
     * Explicit external wake (new caption evidence, language/mode change, manual user repair/retry,
     * restored network/quota capability). Never called by timer ticks or scout self-publication.
     */
    fun wakeForReason(reason: String, nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            isTemporarilyIdle = false
            pausedDeadlineReached = false
            if (isViewerPaused && pausedSinceEpochMs > 0L) {
                pausedSinceEpochMs = nowEpochMs
            }
            if (outcomeState != SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE) {
                outcomeState = SchedulerOutcomeState.ACTIVE
            }
            idleReason = ""
            lastWakeReason = reason
        }
    }

    /**
     * Authoritative submission gate checked before ANY preparation, review, changeover pre-listen,
     * patrol, or automatic repair request is dispatched.
     * Accounts for [overlapMs] and optional ceiling-to-second rounding so the ACTUAL submitted
     * interval end NEVER crosses `min(durationMs, acknowledgedViewerPosMs + policy.horizonMs)`.
     */
    fun clipOrDeferToBoundary(
        startMs: Long,
        endMs: Long,
        overlapMs: Long = policy.chunkOverlapMs,
        roundingUpToSec: Boolean = true,
        minSliceMs: Long = policy.minSubmittableSliceMs,
    ): BoundarySubmissionDecision = synchronized(lock) {
        if (isScrubberPreviewActive) {
            return@synchronized BoundarySubmissionDecision.DeferredOutsideBoundary(
                reason = "Scrubber drag preview active; boundary locked at ${acknowledgedViewerPosMs / 1000L}s",
                boundaryEndMs = boundaryEndMs,
            )
        }
        val capMs = if (boundaryAnchored) {
            boundaryEndMs
        } else {
            val dur = durationMs.takeIf { it > 0L } ?: maxOf(endMs, acknowledgedViewerPosMs + policy.horizonMs)
            minOf(dur, acknowledgedViewerPosMs + policy.horizonMs)
        }
        val safeStart = startMs.coerceAtLeast(0L)
        if (safeStart >= capMs) {
            return@synchronized BoundarySubmissionDecision.DeferredOutsideBoundary(
                reason = "Interval start ${safeStart}ms is at or beyond 3-minute boundary ${capMs}ms",
                boundaryEndMs = capMs,
            )
        }
        val safeOverlap = overlapMs.coerceAtLeast(0L)
        val roundedCapMs = if (roundingUpToSec) {
            (capMs / 1_000L) * 1_000L
        } else {
            capMs
        }
        val maxRawEndMs = (roundedCapMs - safeOverlap).coerceAtLeast(0L)
        val clippedEnd = minOf(endMs, maxRawEndMs)
        val spanMs = clippedEnd - safeStart
        val reachesActualEnd = durationMs > 0L && spanMs > 0L && (
            clippedEnd >= durationMs ||
                (roundingUpToSec && safeOverlap == 0L && clippedEnd >= (durationMs / 1_000L) * 1_000L && capMs >= durationMs)
            )
        if (spanMs <= 0L || (!reachesActualEnd && spanMs < minSliceMs)) {
            return@synchronized BoundarySubmissionDecision.DeferredOutsideBoundary(
                reason = "Clipped span ${spanMs}ms below minimum slice ${minSliceMs}ms at boundary ${capMs}ms",
                boundaryEndMs = capMs,
            )
        }
        val effectiveSubmittedEnd = if (roundingUpToSec) {
            val endSec = ((clippedEnd + safeOverlap + 999L) / 1_000L)
            endSec * 1_000L
        } else {
            clippedEnd + safeOverlap
        }
        if (effectiveSubmittedEnd > capMs) {
            // Double-check safety invariant: never allow rounded+overlapped end to cross capMs
            val safeEndSec = (capMs / 1_000L)
            val tightenedRawEnd = (safeEndSec * 1_000L - safeOverlap).coerceAtLeast(safeStart)
            val tightenedSpan = tightenedRawEnd - safeStart
            if (tightenedSpan <= 0L || (!reachesActualEnd && tightenedSpan < minSliceMs)) {
                return@synchronized BoundarySubmissionDecision.DeferredOutsideBoundary(
                    reason = "Rounded/overlapped submission would cross boundary ${capMs}ms",
                    boundaryEndMs = capMs,
                )
            }
            return@synchronized BoundarySubmissionDecision.Allowed(
                startMs = safeStart,
                endMs = tightenedRawEnd,
                wasClipped = tightenedRawEnd < endMs,
                effectiveSubmittedEndMs = minOf(capMs, tightenedRawEnd + safeOverlap),
                schedulingGeneration = schedulingGeneration,
            )
        }
        BoundarySubmissionDecision.Allowed(
            startMs = safeStart,
            endMs = clippedEnd,
            wasClipped = clippedEnd < endMs,
            effectiveSubmittedEndMs = effectiveSubmittedEnd,
            schedulingGeneration = schedulingGeneration,
        )
    }

    /**
     * Evaluates the honest, terminal or temporary-idle state inside the active 3-minute window:
     * 1. [SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE] ONLY when the entire video is verified.
     * 2. [SchedulerOutcomeState.WAITING_FOR_EVIDENCE_OR_CAPABILITY] when ZERO_CLOUD / quota-limited
     *    and unverified work remains inside the window.
     * 3. [SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY] when retries or paused deadline
     *    are exhausted and unresolved gaps remain in `[acknowledgedViewerPosMs, boundaryEndMs]`.
     * 4. [SchedulerOutcomeState.WINDOW_READY_IDLE] when `[acknowledgedViewerPosMs, boundaryEndMs]`
     *    has no remaining eligible prep/review/repair work, while remaining video past boundary exists.
     * 5. [SchedulerOutcomeState.ACTIVE] otherwise.
     */
    fun evaluateSchedulerOutcome(
        ledger: TranscriptCoverageLedger,
        quotaMode: QuotaSaverMode = this.quotaMode,
        quotaExhausted: Boolean = false,
        hasPendingReviews: Boolean = pendingReviewDraftCount > 0,
        hasEligibleQualityWork: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): SchedulerStateSnapshot = synchronized(lock) {
        this.quotaMode = quotaMode
        val ledgerDur = ledger.totalDurationMs()
        if (ledgerDur > 0L) {
            durationMs = maxOf(durationMs, ledgerDur)
            boundaryAnchored = true
            recomputeBoundaryLocked()
        }
        val leadMs = ledger.continuousVerifiedLeadFrom(acknowledgedViewerPosMs)
        contiguousPlayheadLeadMs = leadMs

        val windowStart = acknowledgedViewerPosMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val windowEnd = boundaryEndMs.coerceIn(windowStart, durationMs.coerceAtLeast(0L))

        val pausedDeadlineExpired = isViewerPaused &&
            pausedSinceEpochMs > 0L &&
            (nowEpochMs - pausedSinceEpochMs) >= policy.pausedQualityDeadlineMs
        pausedDeadlineReached = pausedDeadlineExpired

        val nextWorkInWindow = ledger.nextPriorityInterval(
            playheadMs = windowStart,
            maxRetryAttempts = policy.maxRetriesPerInterval,
            lookaheadHorizonMs = policy.horizonMs,
            enforceHardHorizon = true,
            chunkOverlapMs = policy.chunkOverlapMs,
            roundingUpToSecond = true,
            minSliceMs = policy.minSubmittableSliceMs,
        )
        val wholeVideoVerified = durationMs > 0L &&
            windowEnd >= durationMs &&
            ledger.isFullyVerified() &&
            !hasPendingReviews
        val windowVerified = durationMs > 0L &&
            ledger.isWindowVerified(windowStart, windowEnd) &&
            !hasPendingReviews
        val unresolvedInWindow = ledger.unresolvedIntervalsInWindow(windowStart, windowEnd)
        val hasExhaustedUnresolved = ledger.hasExhaustedUnresolvedInWindow(
            windowStartMs = windowStart,
            windowEndMs = windowEnd,
            maxRetryAttempts = policy.maxRetriesPerInterval,
        )

        val nextOutcome: SchedulerOutcomeState
        val nextIdle: Boolean
        val nextIdleReason: String

        when {
            wholeVideoVerified -> {
                nextOutcome = SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE
                nextIdle = true
                nextIdleReason = "Entire video verified"
            }
            (quotaMode == QuotaSaverMode.ZERO_CLOUD || quotaExhausted) && !windowVerified && !hasPendingReviews -> {
                nextOutcome = SchedulerOutcomeState.WAITING_FOR_EVIDENCE_OR_CAPABILITY
                nextIdle = true
                nextIdleReason = if (quotaMode == QuotaSaverMode.ZERO_CLOUD) {
                    "Free CC Only mode active (0 AI requests)"
                } else {
                    "Cloud quota exhausted; waiting for captions or capability"
                }
            }
            unresolvedInWindow.isNotEmpty() && !hasPendingReviews &&
                (nextWorkInWindow == null || hasExhaustedUnresolved || pausedDeadlineExpired) -> {
                nextOutcome = SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY
                nextIdle = true
                nextIdleReason = if (pausedDeadlineExpired) {
                    "Paused recovery deadline reached with ${unresolvedInWindow.size} unresolved span(s)"
                } else {
                    "Bounded retries exhausted for ${unresolvedInWindow.size} unresolved span(s)"
                }
            }
            windowVerified && !hasEligibleQualityWork -> {
                nextOutcome = SchedulerOutcomeState.WINDOW_READY_IDLE
                nextIdle = true
                nextIdleReason = "Current window ready through ${windowEnd / 1000L}s"
            }
            nextWorkInWindow == null && !hasPendingReviews && !hasEligibleQualityWork -> {
                nextOutcome = if (unresolvedInWindow.isEmpty()) {
                    SchedulerOutcomeState.WINDOW_READY_IDLE
                } else {
                    SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY
                }
                nextIdle = true
                nextIdleReason = if (unresolvedInWindow.isEmpty()) {
                    "No eligible work inside boundary (${windowEnd / 1000L}s)"
                } else {
                    "Unresolved gap(s) remain after bounded recovery"
                }
            }
            else -> {
                nextOutcome = SchedulerOutcomeState.ACTIVE
                nextIdle = false
                nextIdleReason = ""
            }
        }

        if (nextIdle && (!isTemporarilyIdle || outcomeState != nextOutcome)) {
            lastIdleAnchorMs = windowStart
            lastIdleBoundaryEndMs = windowEnd
        }
        outcomeState = nextOutcome
        isTemporarilyIdle = nextIdle
        idleReason = nextIdleReason
        buildSchedulerSnapshotLocked()
    }

    fun schedulerSnapshot(): SchedulerStateSnapshot = synchronized(lock) {
        buildSchedulerSnapshotLocked()
    }

    fun exportPersistedState(nowEpochMs: Long = System.currentTimeMillis()): PersistedSchedulingState = synchronized(lock) {
        PersistedSchedulingState(
            acknowledgedViewerPosMs = acknowledgedViewerPosMs,
            horizonMs = policy.horizonMs,
            schedulingGeneration = schedulingGeneration,
            isViewerPaused = isViewerPaused,
            lastIdleAnchorMs = lastIdleAnchorMs,
            lastIdleBoundaryEndMs = lastIdleBoundaryEndMs,
            schedulerOutcome = outcomeState.name,
            savedAtEpochMs = nowEpochMs,
        )
    }

    fun restorePersistedState(persisted: PersistedSchedulingState, mediaDurationMs: Long) {
        synchronized(lock) {
            durationMs = mediaDurationMs.coerceAtLeast(0L)
            policy = policy.copy(horizonMs = persisted.horizonMs.coerceAtLeast(15_000L))
            acknowledgedViewerPosMs = persisted.acknowledgedViewerPosMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
            schedulingGeneration = persisted.schedulingGeneration.coerceAtLeast(0L)
            isViewerPaused = persisted.isViewerPaused
            lastIdleAnchorMs = persisted.lastIdleAnchorMs
            lastIdleBoundaryEndMs = persisted.lastIdleBoundaryEndMs
            outcomeState = SchedulerOutcomeState.entries.firstOrNull { it.name == persisted.schedulerOutcome }
                ?: SchedulerOutcomeState.ACTIVE
            isTemporarilyIdle = outcomeState == SchedulerOutcomeState.WINDOW_READY_IDLE ||
                outcomeState == SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY ||
                outcomeState == SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE
            boundaryAnchored = durationMs > 0L
            recomputeBoundaryLocked()
        }
    }

    private fun recomputeBoundaryLocked() {
        boundaryEndMs = if (durationMs > 0L) {
            minOf(durationMs, acknowledgedViewerPosMs + policy.horizonMs)
        } else {
            acknowledgedViewerPosMs + policy.horizonMs
        }
    }

    private fun buildSchedulerSnapshotLocked(): SchedulerStateSnapshot {
        val safeSpeed = playbackSpeed.coerceIn(0.25f, 4.0f)
        val viewingReserveMs = (contiguousPlayheadLeadMs.toDouble() / safeSpeed.toDouble()).toLong().coerceAtLeast(0L)
        return SchedulerStateSnapshot(
            acknowledgedViewerPosMs = acknowledgedViewerPosMs,
            durationMs = durationMs,
            boundaryEndMs = boundaryEndMs,
            schedulingGeneration = schedulingGeneration,
            isViewerPaused = isViewerPaused,
            isScrubberPreviewActive = isScrubberPreviewActive,
            playbackSpeed = safeSpeed,
            continuousVerifiedLeadMs = contiguousPlayheadLeadMs,
            viewingTimeReserveMs = viewingReserveMs,
            pendingReviewDraftCount = pendingReviewDraftCount,
            isScout1ThrottledForReviewBackpressure = pendingReviewDraftCount >= policy.maxPendingReviewDrafts,
            outcomeState = outcomeState,
            isTemporarilyIdle = isTemporarilyIdle,
            lastIdleAnchorMs = lastIdleAnchorMs,
            lastIdleBoundaryEndMs = lastIdleBoundaryEndMs,
            lastWakeReason = lastWakeReason,
            idleReason = idleReason,
            pausedDeadlineReached = pausedDeadlineReached,
            policy = policy,
        )
    }

    private fun isWithinActiveBoundaryLocked(item: SentinelWorkItem): Boolean {
        if (!boundaryAnchored) return true
        if (item.kind == SentinelTaskKind.USER_HEAL_BEACON) return true
        // Must overlap [acknowledgedViewerPosMs, boundaryEndMs) and not extend past boundaryEndMs
        return item.startMs < boundaryEndMs && item.endMs <= boundaryEndMs
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
            if (quotaMode == QuotaSaverMode.ZERO_CLOUD) {
                // Immediately reject queued cloud-requiring work when Free CC Only is enabled;
                // retain strictly local deterministic Scout 2 review work.
                queue.removeAll { it.kind != SentinelTaskKind.SCOUT2_REQUIRED_REVIEW }
            }
        }
    }

    fun cancelQueuedCloudWork(): Int = synchronized(lock) {
        val before = queue.size
        queue.removeAll { it.kind != SentinelTaskKind.SCOUT2_REQUIRED_REVIEW }
        before - queue.size
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
            val withinBoundary = !boundaryAnchored || windowStart < boundaryEndMs
            val clippedEnd = if (boundaryAnchored) minOf(windowEnd, boundaryEndMs) else windowEnd
            if (existing == null) {
                val record = ChapterPatrolRecord(
                    chapterId = leadChapter.id,
                    chapterTitle = combinedTitle,
                    boundaryMs = leadChapter.startMs,
                    windowStartMs = windowStart,
                    windowEndMs = windowEnd,
                    status = if (withinBoundary) {
                        ChapterInspectionStatus.INSPECTION_QUEUED
                    } else {
                        ChapterInspectionStatus.NOT_INSPECTED
                    },
                    summary = if (withinBoundary) {
                        "Queued for chapter transition check"
                    } else {
                        "Deferred until within 3-minute boundary"
                    },
                )
                chapterRecords[leadChapter.id] = record
                if (withinBoundary && clippedEnd > windowStart) {
                    enqueueLocked(
                        SentinelWorkItem(
                            id = "patrol_${leadChapter.id}",
                            tier = SentinelPriorityTier.PREVENTIVE_MAINTENANCE,
                            kind = SentinelTaskKind.CHAPTER_BOUNDARY_PATROL,
                            startMs = windowStart,
                            endMs = clippedEnd,
                            chapterId = leadChapter.id,
                            chapterTitle = combinedTitle,
                            schedulingGeneration = schedulingGeneration,
                        )
                    )
                }
            } else if (existing.status == ChapterInspectionStatus.NOT_INSPECTED && withinBoundary && clippedEnd > windowStart) {
                chapterRecords[leadChapter.id] = existing.copy(
                    status = ChapterInspectionStatus.INSPECTION_QUEUED,
                    summary = "Queued for chapter transition check",
                )
                enqueueLocked(
                    SentinelWorkItem(
                        id = "patrol_${leadChapter.id}",
                        tier = SentinelPriorityTier.PREVENTIVE_MAINTENANCE,
                        kind = SentinelTaskKind.CHAPTER_BOUNDARY_PATROL,
                        startMs = windowStart,
                        endMs = clippedEnd,
                        chapterId = leadChapter.id,
                        chapterTitle = combinedTitle,
                        schedulingGeneration = schedulingGeneration,
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

        val effectiveAnchor = if (boundaryAnchored) acknowledgedViewerPosMs else playheadMs.coerceAtLeast(0L)
        val hardBoundaryEnd = if (boundaryAnchored) {
            boundaryEndMs
        } else {
            minOf(durationMs, effectiveAnchor + policy.horizonMs)
        }
        val lookaheadOrigin = if (isViewerPaused) {
            effectiveAnchor
        } else {
            maxOf(effectiveAnchor, playheadMs.coerceAtLeast(0L))
        }
        val minHorizon = (lookaheadOrigin + 2_000L).coerceAtLeast(0L)
        val maxHorizon = minOf(lookaheadOrigin + lookaheadMaxMs, hardBoundaryEnd, durationMs)
        val preferredMin = lookaheadOrigin + lookaheadMinMs
        if (maxHorizon <= minHorizon) return@synchronized emptyList()

        val newlyQueued = mutableListOf<SentinelWorkItem>()
        val candidates = mergedSeams
            .filter { (seamMs, _) -> seamMs in minHorizon..maxHorizon }
            .sortedBy { (seamMs, _) ->
                if (seamMs >= preferredMin) seamMs - preferredMin else seamMs - minHorizon
            }

        for ((seamMs, chapter) in candidates) {
            val seamKey = changeoverSeamKey(seamMs)
            if (seamKey in preListenedSeamKeys) continue
            val rawWinStart = (seamMs - CHANGEOVER_PRE_MS).coerceAtLeast(0L)
            val rawWinEnd = (seamMs + CHANGEOVER_POST_MS).coerceAtMost(durationMs)
            // Enforce rolling boundary INCLUDING second-rounding margin so changeover pre-listen NEVER crosses boundaryEndMs
            val roundedCapMs = (hardBoundaryEnd / 1_000L) * 1_000L
            val winEnd = minOf(rawWinEnd, roundedCapMs)
            if (winEnd - rawWinStart < 4_000L) continue

            val item = SentinelWorkItem(
                id = seamKey,
                tier = SentinelPriorityTier.PREVENTIVE_MAINTENANCE,
                kind = SentinelTaskKind.CHANGEOVER_PRELISTEN,
                startMs = rawWinStart,
                endMs = winEnd,
                chapterId = chapter?.id,
                chapterTitle = chapter?.title ?: "Transition ${seamMs / 1000L}s",
                createdAtEpochMs = nowEpochMs + (seamMs / 1000L),
                schedulingGeneration = schedulingGeneration,
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
        if (isScrubberPreviewActive || quotaMode == QuotaSaverMode.ZERO_CLOUD) return@synchronized null
        val activeCount = workers.values.count { it.isActive }
        val isWorkerAlreadyActive = workers[workerId]?.isActive == true
        if (!isWorkerAlreadyActive && activeCount >= maxAllowedWorkersLocked()) {
            return@synchronized null
        }
        if (isPatrolSuspendedLocked()) return@synchronized null
        // Priority order rule: required Scout 2 review always runs before preventive changeover pre-listen!
        if (queue.any { it.kind == SentinelTaskKind.SCOUT2_REQUIRED_REVIEW }) return@synchronized null

        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.kind != SentinelTaskKind.CHANGEOVER_PRELISTEN) continue
            if (item.schedulingGeneration > 0L && item.schedulingGeneration < schedulingGeneration) {
                iterator.remove()
                continue
            }
            if (!isWithinActiveBoundaryLocked(item)) continue
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
     * Enqueues a mandatory Scout 2 independent review for a newly staged Scout 1 draft interval.
     * Always scheduled at [SentinelPriorityTier.CRITICAL_PLAYBACK_RECOVERY] so required review
     * remains eligible even when playback lead is short or buffering is active.
     */
    fun enqueueScout2Review(
        intervalId: String,
        startMs: Long,
        endMs: Long,
        playheadMs: Long,
        draftRevision: Long = 0L,
        nowEpochMs: Long = System.currentTimeMillis(),
        schedulingGeneration: Long = this.schedulingGeneration,
    ): Boolean = synchronized(lock) {
        // Supersede any older queued review task for the same intervalId so stale drafts are never claimed
        val existingForInterval = queue.firstOrNull {
            it.kind == SentinelTaskKind.SCOUT2_REQUIRED_REVIEW && it.beaconId == intervalId
        }
        if (existingForInterval != null) {
            if (draftRevision > 0L && draftRevision <= existingForInterval.draftRevision &&
                existingForInterval.schedulingGeneration == schedulingGeneration
            ) {
                return@synchronized false
            }
            queue.remove(existingForInterval)
        }
        val distanceAhead = if (endMs >= playheadMs) {
            (startMs - playheadMs).coerceAtLeast(0L)
        } else {
            1_000_000_000L + (playheadMs - endMs)
        }
        val item = SentinelWorkItem(
            id = "scout2_review_${intervalId}_rev${draftRevision}",
            tier = SentinelPriorityTier.CRITICAL_PLAYBACK_RECOVERY,
            kind = SentinelTaskKind.SCOUT2_REQUIRED_REVIEW,
            startMs = startMs,
            endMs = endMs,
            beaconId = intervalId,
            chapterTitle = "Review ${startMs / 1000L}s–${endMs / 1000L}s",
            draftRevision = draftRevision,
            createdAtEpochMs = distanceAhead.coerceAtLeast(0L),
            schedulingGeneration = schedulingGeneration,
        )
        enqueueLocked(item)
    }

    /**
     * Claims the nearest unresolved [SentinelTaskKind.SCOUT2_REQUIRED_REVIEW] interval ahead of
     * [playheadMs] (or an exact [targetIntervalId] if requested). Deliberately bypasses
     * [isPatrolSuspendedLocked] because Scout 2 review is a required stage in Prepare -> Review -> Release,
     * while strictly enforcing scrubber-preview, boundary, and scheduling-generation validity.
     */
    fun claimRequiredReviewWork(
        workerId: String = WORKER_2_ID,
        playheadMs: Long = 0L,
        nowEpochMs: Long = System.currentTimeMillis(),
        targetIntervalId: String? = null,
    ): Pair<SentinelWorkItem, IntervalReservation>? = synchronized(lock) {
        if (isScrubberPreviewActive) return@synchronized null
        val activeCount = workers.values.count { it.isActive }
        val isWorkerAlreadyActive = workers[workerId]?.isActive == true
        if (!isWorkerAlreadyActive && activeCount >= maxAllowedWorkersLocked()) {
            return@synchronized null
        }

        // Prune stale-generation required reviews so obsolete seek/project items cannot be claimed
        queue.removeAll { item ->
            item.kind == SentinelTaskKind.SCOUT2_REQUIRED_REVIEW &&
                item.schedulingGeneration > 0L &&
                item.schedulingGeneration != schedulingGeneration
        }

        val reviewCandidates = queue
            .filter {
                it.kind == SentinelTaskKind.SCOUT2_REQUIRED_REVIEW &&
                    (targetIntervalId == null || it.beaconId == targetIntervalId) &&
                    isWithinActiveBoundaryLocked(it)
            }
            .sortedWith(
                compareBy<SentinelWorkItem> { item ->
                    if (item.endMs >= playheadMs) 0 else 1
                }.thenBy { item ->
                    if (item.endMs >= playheadMs) (item.startMs - playheadMs).coerceAtLeast(0L) else (playheadMs - item.endMs)
                }
            )
        for (item in reviewCandidates) {
            val reservation = tryReserveInterval(workerId, item.startMs, item.endMs, item.tier, nowEpochMs)
                ?: continue
            queue.remove(item)
            workers[workerId] = SentinelWorkerState(
                workerId = workerId,
                isActive = true,
                currentTaskLabel = "Reviewing ${item.chapterTitle ?: "upcoming dialogue"}",
                activeStartMs = item.startMs,
                activeEndMs = item.endMs,
                tier = item.tier,
            )
            return@synchronized item to reservation
        }
        null
    }

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
     * Enqueues a [HealBeacon] into the unified coordinator queue so repairs share priority tiers,
     * reservations, generation tokens, and 3-minute boundary enforcement with Scout workers.
     */
    fun enqueueHealBeacon(
        beacon: HealBeacon,
        isUserDirected: Boolean = !beacon.autoTriggered,
        generationToken: Long = schedulingGeneration,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): SentinelWorkItem? = synchronized(lock) {
        val tier = if (isUserDirected) {
            SentinelPriorityTier.USER_DIRECTED_REPAIR
        } else {
            SentinelPriorityTier.PREVENTIVE_MAINTENANCE
        }
        val kind = if (isUserDirected) {
            SentinelTaskKind.USER_HEAL_BEACON
        } else {
            SentinelTaskKind.CHAPTER_BOUNDARY_PATROL
        }
        val clippedEnd = if (!isUserDirected && boundaryAnchored) {
            minOf(beacon.windowEndMs, boundaryEndMs)
        } else {
            beacon.windowEndMs
        }
        if (clippedEnd <= beacon.windowStartMs) return@synchronized null
        val item = SentinelWorkItem(
            id = beacon.coordinatorJobId.ifBlank { "heal_${beacon.id}_rev${beacon.repairRevision}" },
            tier = tier,
            kind = kind,
            startMs = beacon.windowStartMs,
            endMs = clippedEnd,
            beaconId = beacon.id,
            chapterId = beacon.patrolChapterId,
            chapterTitle = if (isUserDirected) "Heal moment" else "Auto heal patrol",
            draftRevision = beacon.repairRevision.toLong(),
            createdAtEpochMs = nowEpochMs,
            schedulingGeneration = generationToken,
        )
        enqueueLocked(item)
        item
    }

    /**
     * Claims a queued Heal Beacon task (for user-directed or patrol repair) through the shared
     * reservation and worker table so Scouts and Heal workers never race on the same interval.
     */
    fun claimHealBeaconWork(
        workerId: String = WORKER_2_ID,
        targetBeaconId: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Pair<SentinelWorkItem, IntervalReservation>? = synchronized(lock) {
        val activeCount = workers.values.count { it.isActive }
        val isWorkerAlreadyActive = workers[workerId]?.isActive == true
        if (!isWorkerAlreadyActive && activeCount >= maxAllowedWorkersLocked()) {
            return@synchronized null
        }
        val candidates = queue.filter { item ->
            (item.kind == SentinelTaskKind.USER_HEAL_BEACON ||
                (item.kind == SentinelTaskKind.CHAPTER_BOUNDARY_PATROL && item.beaconId != null)) &&
                (targetBeaconId == null || item.beaconId == targetBeaconId) &&
                (item.schedulingGeneration <= 0L || item.schedulingGeneration >= schedulingGeneration) &&
                isWithinActiveBoundaryLocked(item)
        }
        for (item in candidates) {
            val reservation = tryReserveInterval(workerId, item.startMs, item.endMs, item.tier, nowEpochMs)
                ?: continue
            queue.remove(item)
            workers[workerId] = SentinelWorkerState(
                workerId = workerId,
                isActive = true,
                currentTaskLabel = if (item.kind == SentinelTaskKind.USER_HEAL_BEACON) {
                    "Healing marked moment"
                } else {
                    "Checking chapter moment"
                },
                activeStartMs = item.startMs,
                activeEndMs = item.endMs,
                tier = item.tier,
            )
            return@synchronized item to reservation
        }
        null
    }

    fun cancelOrSupersedeStaleHealTasks(activeGenerationToken: Long): Int = synchronized(lock) {
        val before = queue.size
        queue.removeAll { item ->
            (item.kind == SentinelTaskKind.USER_HEAL_BEACON || item.beaconId != null) &&
                item.schedulingGeneration in 1L until activeGenerationToken
        }
        before - queue.size
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
        val hasRequiredReview = queue.any { it.kind == SentinelTaskKind.SCOUT2_REQUIRED_REVIEW }
        if (hasRequiredReview && !isBatteryOrThermalConstrained) {
            // Deterministic Scout 2 review is local & zero-cloud, so it stays active even in ZERO_CLOUD or quota-limited runs
            return 2
        }
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
     * - Patrol suspension during playback buffering / low lead (never suspends SCOUT2_REQUIRED_REVIEW)
     * - Shared interval reservations (never double-assigns overlapping windows)
     */
    fun claimNextWork(
        workerId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        allowedKinds: Set<SentinelTaskKind>? = null,
    ): Pair<SentinelWorkItem, IntervalReservation>? = synchronized(lock) {
        val activeCount = workers.values.count { it.isActive }
        val isWorkerAlreadyActive = workers[workerId]?.isActive == true
        if (!isWorkerAlreadyActive && activeCount >= maxAllowedWorkersLocked()) {
            return@synchronized null
        }

        if (isScrubberPreviewActive) return@synchronized null
        val suspendPatrol = isPatrolSuspendedLocked()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.schedulingGeneration > 0L && item.schedulingGeneration < schedulingGeneration) {
                iterator.remove()
                continue
            }
            if (allowedKinds != null && item.kind !in allowedKinds) {
                continue
            }
            if (!isWithinActiveBoundaryLocked(item)) {
                continue
            }
            if (item.kind == SentinelTaskKind.PLAYBACK_LOOKAHEAD &&
                pendingReviewDraftCount >= policy.maxPendingReviewDrafts
            ) {
                // Backpressure: pause new Scout 1 preparation while Scout 2 review queue is full
                continue
            }
            if (suspendPatrol &&
                item.kind != SentinelTaskKind.SCOUT2_REQUIRED_REVIEW &&
                item.tier.rank >= SentinelPriorityTier.PREVENTIVE_MAINTENANCE.rank
            ) {
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
                    SentinelTaskKind.SCOUT2_REQUIRED_REVIEW -> "Reviewing ${item.chapterTitle ?: "upcoming dialogue"}"
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

    fun recordScout1TimestampReportSummary(summary: String) {
        if (summary.isBlank()) return
        synchronized(lock) {
            latestScout1ReportSummary = summary
        }
    }

    fun recordScoutTimestampAudit(
        reportSummary: String,
        verifiedCount: Int,
        adjustedCount: Int,
        adjustments: List<TimestampAdjustmentRecord>,
    ) {
        synchronized(lock) {
            if (reportSummary.isNotBlank()) {
                latestScout1ReportSummary = reportSummary
            }
            totalTimestampsVerified += verifiedCount.coerceAtLeast(0)
            totalTimestampsAdjusted += adjustedCount.coerceAtLeast(0)
            if (adjustments.isNotEmpty()) {
                recentTimestampAdjustments.addAll(adjustments)
                while (recentTimestampAdjustments.size > 12) {
                    recentTimestampAdjustments.removeAt(0)
                }
            }
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
            schedulerState = buildSchedulerSnapshotLocked(),
            latestScout1ReportSummary = latestScout1ReportSummary,
            totalTimestampsVerified = totalTimestampsVerified,
            totalTimestampsAdjusted = totalTimestampsAdjusted,
            recentTimestampAdjustments = recentTimestampAdjustments.takeLast(6),
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
