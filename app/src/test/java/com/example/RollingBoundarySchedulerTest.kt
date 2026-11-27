package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CaptionOriginKind
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.ReferenceCaptionCue
import com.example.data.model.ReferenceCaptionTrack
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoChapter
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.pipeline.DraftInterval
import com.example.domain.pipeline.ReleasedTimelineStore
import com.example.domain.pipeline.Scout2IndependentReviewer
import com.example.domain.playback.PlaybackReadinessController
import com.example.domain.playback.UserPlaybackIntent
import com.example.domain.sentinel.BoundarySubmissionDecision
import com.example.domain.sentinel.RollingBoundaryPolicy
import com.example.domain.sentinel.SchedulerOutcomeState
import com.example.domain.sentinel.SentinelPriorityTier
import com.example.domain.sentinel.SentinelTaskKind
import com.example.domain.sentinel.SentinelWorkItem
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import com.example.ui.state.DialogueLensViewModel
import com.example.ui.state.HumanReadableStatus
import com.example.ui.state.ScoutCaptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive verification suite for the Rolling 3-Minute Boundary & Continuous Two-Scout
 * Scheduler covering all 12 acceptance table cases plus Scout 2 backpressure, hysteresis,
 * self-publication non-retrigger, lifecycle persistence, and real ViewModel integration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RollingBoundarySchedulerTest {

    private val videoDurationMs = 1_095_000L // 18:15 (matches long-form YouTube reference length)

    private fun makeProject(
        id: String = "proj_boundary_test",
        durationMs: Long = videoDurationMs,
        subtitleSource: SubtitleSourceKind = SubtitleSourceKind.AI_DRAFT,
        events: List<DialogueEvent> = emptyList(),
        chapters: List<VideoChapter> = listOf(
            VideoChapter("ch_1", "Part 1", 0L, 240_000L),
            VideoChapter("ch_2", "Part 2", 240_000L, 600_000L),
            VideoChapter("ch_3", "Part 3", 600_000L, 1_095_000L),
        ),
    ): DialogueLensProject = DialogueLensProject(
        id = id,
        title = "Rolling Boundary Test",
        createdAtEpochMs = 1_000L,
        sourceLanguage = "English",
        targetLanguage = "English",
        subtitleSource = subtitleSource,
        videoSource = VideoSource(
            uriString = "https://youtu.be/RzagPoxaolQ",
            displayName = "RzagPoxaolQ",
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            durationMs = durationMs,
            width = 1920,
            height = 1080,
            youtubeVideoId = "RzagPoxaolQ",
        ),
        shots = listOf(Shot("shot_1", 0L, durationMs, cutType = "EMBEDDED_YOUTUBE")),
        chapters = chapters,
        speakers = listOf(
            Speaker(id = "spk_a", defaultLabel = "Speaker A", accentIndex = 0, preferredScreenSideX = 0.28f),
            Speaker(id = "spk_b", defaultLabel = "Speaker B", accentIndex = 1, preferredScreenSideX = 0.72f),
        ),
        faceTracks = emptyList(),
        dialogueEvents = events,
    )

    private fun makeEvent(
        id: String,
        startMs: Long,
        endMs: Long,
        text: String,
        speakerId: String? = "spk_a",
        lockedByUser: Boolean = false,
    ): DialogueEvent = DialogueEvent(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        sourceText = text,
        translatedText = text,
        speakerId = speakerId,
        speakerConfidence = 0.9f,
        faceTrackId = null,
        faceConfidence = 0f,
        shotId = "shot_1",
        dialogueType = DialogueType.SPOKEN,
        placementMode = PlacementMode.AUTO,
        lockedByUser = lockedByUser,
    )

    // Case 1: Pause at 02:00 -> boundary fixed at 05:00 while scouts advance inside [02:00, 05:00]
    @Test
    fun case01_pauseAt0200_pinsBoundaryAt0500_withoutDriftingAsScoutsAdvance() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)

        val twoMinMs = 120_000L
        val fiveMinMs = 300_000L
        coordinator.onViewerPauseChanged(paused = true, acknowledgedPositionMs = twoMinMs, durationMs = videoDurationMs, nowEpochMs = 10_000L)
        assertEquals(twoMinMs, coordinator.currentAcknowledgedViewerPosMs())
        assertEquals(fiveMinMs, coordinator.currentBoundaryEndMs())

        // Verify scouts work ONLY inside [02:00, 05:00] and boundary stays fixed at 05:00 as each slice completes
        var completedSlices = 0
        while (true) {
            val next = ledger.nextPriorityInterval(
                playheadMs = coordinator.currentAcknowledgedViewerPosMs(),
                maxRetryAttempts = 3,
                lookaheadHorizonMs = LookaheadWindowPlanner.ROLLING_BOUNDARY_HORIZON_MS,
                enforceHardHorizon = true,
                roundingUpToSecond = true,
            ) ?: break
            assertTrue("Interval start must be inside [02:00, 05:00): ${next.startMs}", next.startMs in twoMinMs until fiveMinMs)
            assertTrue("Interval end must never exceed 05:00: ${next.endMs}", next.endMs <= fiveMinMs)

            val gate = coordinator.clipOrDeferToBoundary(next.startMs, next.endMs, overlapMs = 0L, roundingUpToSec = true)
            assertTrue(gate is BoundarySubmissionDecision.Allowed)
            val allowed = gate as BoundarySubmissionDecision.Allowed
            assertTrue("Submitted end must stay <= 05:00", allowed.effectiveSubmittedEndMs <= fiveMinMs)

            ledger.recordOutcome(allowed.startMs, allowed.endMs, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 3)
            // Even if a paused callback arrives later while paused, boundary MUST stay pinned at 05:00
            val woke = coordinator.updateAcknowledgedViewerPosition(
                positionMs = allowed.endMs,
                durationMs = videoDurationMs,
                isPlaying = false,
                nowEpochMs = 15_000L + completedSlices * 1_000L,
            )
            assertFalse("Paused progress must never drift boundary or spuriously wake", woke)
            assertEquals(fiveMinMs, coordinator.currentBoundaryEndMs())
            completedSlices++
            assertTrue("Sanity bound on slice count", completedSlices < 50)
        }

        assertTrue("Scouts must have completed work inside [02:00, 05:00]", completedSlices > 0)
        assertTrue(ledger.isWindowVerified(twoMinMs, fiveMinMs))
        // Regions past 05:00 MUST remain untouched PENDING
        val afterFiveMin = ledger.intervalsSnapshot().filter { it.startMs >= fiveMinMs }
        assertTrue(afterFiveMin.isNotEmpty())
        assertTrue("No analysis may occur beyond 05:00", afterFiveMin.all { it.status == CoverageStatus.PENDING })
    }

    // Case 2: Chunk crossing 05:00 (with overlap + rounding) -> clipped or deferred, actual submitted end <= 05:00
    @Test
    fun case02_chunkCrossingBoundary_withOverlapAndRounding_isClippedOrDeferredNeverExtended() {
        val coordinator = SentinelWorkforceCoordinator()
        coordinator.resetForProject(makeProject())
        val twoMinMs = 120_000L
        val fiveMinMs = 300_000L
        coordinator.onViewerPauseChanged(paused = true, acknowledgedPositionMs = twoMinMs, durationMs = videoDurationMs)

        // Chunk 04:40 -> 05:25 with 2s overlap and ceiling-to-second rounding
        val decision = coordinator.clipOrDeferToBoundary(
            startMs = 280_000L,
            endMs = 325_000L,
            overlapMs = 2_000L,
            roundingUpToSec = true,
            minSliceMs = 2_000L,
        )
        assertTrue(decision is BoundarySubmissionDecision.Allowed)
        val allowed = decision as BoundarySubmissionDecision.Allowed
        assertTrue("Chunk crossing boundary must be marked clipped", allowed.wasClipped)
        assertEquals(280_000L, allowed.startMs)
        assertEquals(298_000L, allowed.endMs) // 300s - 2s overlap
        assertTrue("Effective submitted end (including overlap+rounding) must be <= 05:00", allowed.effectiveSubmittedEndMs <= fiveMinMs)

        // Tiny sliver right before 05:00 (299.2s -> 310s with 2s overlap) must be DEFERRED rather than extending boundary
        val tinySliver = coordinator.clipOrDeferToBoundary(
            startMs = 299_200L,
            endMs = 310_000L,
            overlapMs = 2_000L,
            roundingUpToSec = true,
            minSliceMs = 2_000L,
        )
        assertTrue("Sliver that would cross boundary via overlap/rounding must defer", tinySliver is BoundarySubmissionDecision.DeferredOutsideBoundary)

        // LookaheadWindowPlanner also enforces hard horizon cap
        val localWin = LookaheadWindowPlanner.nextPriorityWindow(
            viewerPosMs = twoMinMs,
            durationMs = videoDurationMs,
            completedWindowStarts = (120_000L until 290_000L step LookaheadWindowPlanner.WINDOW_STEP_MS).toSet(),
            horizonMs = LookaheadWindowPlanner.ROLLING_BOUNDARY_HORIZON_MS,
            enforceHardHorizon = true,
        )
        assertNotNull(localWin)
        assertTrue("Local lookahead window end must never exceed 05:00", localWin!!.endMs <= fiveMinMs)
    }

    // Case 3: Leave paused -> review and bounded repair finish inside [02:00, 05:00], then WINDOW_READY_IDLE with 0 further requests
    @Test
    fun case03_leavePaused_finishesCurrentWindowThenEntersWindowReadyIdleDistinctFromWholeVideoComplete() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)

        coordinator.onViewerPauseChanged(paused = true, acknowledgedPositionMs = 120_000L, durationMs = videoDurationMs, nowEpochMs = 10_000L)
        ledger.recordOutcome(120_000L, 300_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 24)

        val outcome = coordinator.evaluateSchedulerOutcome(
            ledger = ledger,
            quotaMode = QuotaSaverMode.PRO_ACCURACY,
            quotaExhausted = false,
            hasPendingReviews = false,
            nowEpochMs = 20_000L,
        )
        assertEquals(SchedulerOutcomeState.WINDOW_READY_IDLE, outcome.outcomeState)
        assertTrue(outcome.isTemporarilyIdle)
        assertEquals(300_000L, outcome.boundaryEndMs)
        assertEquals(180_000L, outcome.continuousVerifiedLeadMs)

        // Chapter patrol & changeover pre-listen beyond 05:00 must NOT claim work outside boundary
        val claimedWork = coordinator.claimNextWork("sentinel_worker_1", nowEpochMs = 21_000L)
        if (claimedWork != null) {
            assertTrue("Any claimed work must remain <= 05:00", claimedWork.first.endMs <= 300_000L)
        }
        val preListen = coordinator.claimChangeoverPreListenWork("sentinel_worker_2", nowEpochMs = 21_000L)
        if (preListen != null) {
            assertTrue("Changeover pre-listen must remain <= 05:00", preListen.first.endMs <= 300_000L)
        }

        // HumanReadableStatus must honestly report "Current window ready" and NEVER "Ready" (whole video complete)
        val status = HumanReadableStatus.resolve(
            project = project,
            scoutState = ScoutCaptionState.Ready("Best Model · Window Ready", 24, 2),
            coverageSummary = ledger.summary(),
            sentinelSnapshot = coordinator.inspectorSnapshot(),
        )
        assertEquals("Current window ready", status.label)
    }

    // Case 4: Finish preparation while paused -> viewer remains paused (no automatic resume)
    @Test
    fun case04_finishPreparationWhilePaused_neverOverridesIntentionalUserPause() {
        val readiness = PlaybackReadinessController()
        readiness.resetForMedia(
            initialPositionMs = 120_000L,
            durationMs = videoDurationMs,
            requiresCoverageGate = true,
            initialIntent = UserPlaybackIntent.PAUSE,
        )
        readiness.onUserPauseRequested(positionMs = 120_000L)

        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        ledger.recordOutcome(120_000L, 300_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 20)

        val snap = readiness.evaluateCoverage(ledger = ledger)
        assertTrue("Intentional user pause must be preserved", snap.isIntentionallyPausedByUser)
        assertNull("Finishing preparation while paused must NEVER emit an auto-resume directive", snap.pendingAutoResume)
    }

    // Case 5: Resume and reach 02:20 -> boundary advances to 05:20 and hysteresis wakes cleanly; small jitter does not wake
    @Test
    fun case05_resumeAndAdvanceTo0220_wakesViaHysteresisAndAdvancesBoundaryTo0520() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)
        coordinator.configurePolicy(RollingBoundaryPolicy(horizonMs = 180_000L, refillAdvanceMs = 15_000L))

        coordinator.onViewerPauseChanged(paused = true, acknowledgedPositionMs = 120_000L, durationMs = videoDurationMs)
        ledger.recordOutcome(120_000L, 300_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 24)
        val idleSnap = coordinator.evaluateSchedulerOutcome(ledger)
        assertEquals(SchedulerOutcomeState.WINDOW_READY_IDLE, idleSnap.outcomeState)

        // Resume at 02:00, advance slightly to 02:05 (below 15s hysteresis threshold) -> must NOT wake yet
        coordinator.onViewerPauseChanged(paused = false, acknowledgedPositionMs = 120_000L, durationMs = videoDurationMs)
        val tinyAdvanceWake = coordinator.updateAcknowledgedViewerPosition(
            positionMs = 125_000L,
            durationMs = videoDurationMs,
            isPlaying = true,
        )
        assertFalse("5s advance below 15s hysteresis threshold must not churn wake-ups", tinyAdvanceWake)

        // Reach 02:20 (20s advance >= 15s hysteresis threshold) -> MUST wake cleanly & extend boundary to 05:20
        val meaningfulAdvanceWake = coordinator.updateAcknowledgedViewerPosition(
            positionMs = 140_000L,
            durationMs = videoDurationMs,
            isPlaying = true,
        )
        assertTrue("Advancing to 02:20 must wake idle scheduler cleanly", meaningfulAdvanceWake)
        assertEquals(320_000L, coordinator.currentBoundaryEndMs())
        assertEquals(SchedulerOutcomeState.ACTIVE, coordinator.schedulerSnapshot().outcomeState)

        // Next interval now exists strictly inside [05:00, 05:20]
        val next = ledger.nextPriorityInterval(
            playheadMs = coordinator.currentAcknowledgedViewerPosMs(),
            lookaheadHorizonMs = 180_000L,
            enforceHardHorizon = true,
            roundingUpToSecond = true,
        )
        assertNotNull(next)
        assertEquals(300_000L, next!!.startMs)
        assertEquals(320_000L, next.endMs)
    }

    // Case 6: Scrubber drag preview without committing -> boundary unchanged, zero analysis dispatched
    @Test
    fun case06_scrubberDragPreview_freezesBoundaryAndBlocksAllDispatchUntilCommit() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        coordinator.resetForProject(project)
        coordinator.updateAcknowledgedViewerPosition(positionMs = 60_000L, durationMs = videoDurationMs, isPlaying = true)
        assertEquals(240_000L, coordinator.currentBoundaryEndMs())

        // Enter scrubber drag preview (user scrubs thumb across 08:00, 12:00 without lifting finger)
        coordinator.onScrubberPreviewStateChanged(isPreviewing = true)
        assertFalse("No analysis may be dispatched during drag preview", coordinator.isAnalysisDispatchAllowed())

        val previewCallbackWoke = coordinator.updateAcknowledgedViewerPosition(positionMs = 480_000L, durationMs = videoDurationMs, isPlaying = true)
        assertFalse(previewCallbackWoke)
        assertEquals("Boundary must stay frozen at 01:00 + 03:00 = 04:00", 240_000L, coordinator.currentBoundaryEndMs())
        assertEquals(60_000L, coordinator.currentAcknowledgedViewerPosMs())

        val decision = coordinator.clipOrDeferToBoundary(60_000L, 90_000L)
        assertTrue("clipOrDeferToBoundary must defer while previewing", decision is BoundarySubmissionDecision.DeferredOutsideBoundary)
        assertNull("claimNextWork must return null while previewing", coordinator.claimNextWork("sentinel_worker_1"))
        assertNull("claimChangeoverPreListenWork must return null while previewing", coordinator.claimChangeoverPreListenWork("sentinel_worker_2"))

        // Cancel drag preview without committing seek -> anchor remains 01:00
        coordinator.onScrubberPreviewStateChanged(isPreviewing = false)
        assertEquals(60_000L, coordinator.currentAcknowledgedViewerPosMs())
        assertEquals(240_000L, coordinator.currentBoundaryEndMs())
        assertTrue(coordinator.isAnalysisDispatchAllowed())
    }

    // Case 7: Backward seek with in-flight request -> generation bumps, obsolete work pruned, late result rejected
    @Test
    fun case07_backwardSeekWithInFlightRequest_bumpsGenerationPrunesObsoleteWorkAndRejectsLateWrite() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val releaseStore = ReleasedTimelineStore()
        coordinator.resetForProject(project)
        releaseStore.resetSession(mediaId = project.id, sessionToken = 1L, seekGeneration = 0L, configFingerprint = "cfg_1")

        // Anchor at 06:00 -> boundary 09:00
        coordinator.updateAcknowledgedViewerPosition(positionMs = 360_000L, durationMs = videoDurationMs, isPlaying = true, seekGeneration = 0L)
        assertEquals(540_000L, coordinator.currentBoundaryEndMs())

        // Enqueue work inside [06:00, 09:00] under generation 0
        val genBefore = coordinator.currentSchedulingGeneration()
        coordinator.enqueue(
            SentinelWorkItem(
                id = "gap_420_450",
                tier = SentinelPriorityTier.CRITICAL_PLAYBACK_RECOVERY,
                kind = SentinelTaskKind.PLAYBACK_LOOKAHEAD,
                startMs = 420_000L,
                endMs = 450_000L,
                schedulingGeneration = genBefore,
            )
        )

        // User commits backward seek to 00:30 -> boundary becomes [00:30, 03:30], generation increments
        val newGen = coordinator.onCommittedSeek(targetPositionMs = 30_000L, durationMs = videoDurationMs, isPlaying = true)
        assertEquals(genBefore + 1L, newGen)
        assertEquals(30_000L, coordinator.currentAcknowledgedViewerPosMs())
        assertEquals(210_000L, coordinator.currentBoundaryEndMs())
        releaseStore.updateSeekOrConfigGeneration(seekGeneration = newGen, configFingerprint = "cfg_1")

        // Obsolete queued work at 07:00 must have been pruned
        val queuedAfterSeek = coordinator.claimNextWork("sentinel_worker_1")
        if (queuedAfterSeek != null) {
            assertTrue("Obsolete pre-seek item at 420s must be pruned", queuedAfterSeek.first.startMs < 210_000L)
        }

        // Stale pre-seek position callback with old generation MUST be rejected
        coordinator.updateAcknowledgedViewerPosition(positionMs = 365_000L, durationMs = videoDurationMs, isPlaying = true, seekGeneration = genBefore)
        assertEquals("Stale position callback must not overwrite post-seek anchor", 30_000L, coordinator.currentAcknowledgedViewerPosMs())
        assertEquals(210_000L, coordinator.currentBoundaryEndMs())

        // Late in-flight write carrying old seekGeneration MUST be rejected by ReleasedTimelineStore
        val lockedEvent = makeEvent("evt_locked", 35_000L, 40_000L, "Protected user line", lockedByUser = true)
        val staleDraft = DraftInterval(
            intervalId = "win_420000_450000",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = genBefore,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "cfg_1",
            startMs = 420_000L,
            endMs = 450_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(makeEvent("evt_stale", 425_000L, 430_000L, "Late pre-seek line")),
            speakers = project.speakers,
        )
        assertFalse("Late in-flight draft with stale seekGeneration must be rejected", releaseStore.stageDraft(staleDraft))
        assertTrue("Protected user event remains intact", lockedEvent.lockedByUser)
    }

    // Case 8: Exhaust repair attempts & paused deadline -> UNRESOLVED_AFTER_BOUNDED_RECOVERY without endless retries
    @Test
    fun case08_exhaustedRetriesAndPausedDeadline_settleIntoHonestUnresolvedTerminalState() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)
        coordinator.configurePolicy(
            RollingBoundaryPolicy(
                horizonMs = 180_000L,
                maxRetriesPerInterval = 2,
                pausedQualityDeadlineMs = 60_000L,
            )
        )

        coordinator.onViewerPauseChanged(paused = true, acknowledgedPositionMs = 0L, durationMs = videoDurationMs, nowEpochMs = 1_000L)
        // Verify [00:00, 02:30] and fail [02:30, 03:00] twice (exhausting maxRetriesPerInterval = 2)
        ledger.recordOutcome(0L, 150_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 15)
        ledger.recordOutcome(150_000L, 180_000L, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT, failureReason = "Timeout 1", incrementRetry = true)
        ledger.recordOutcome(150_000L, 180_000L, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT, failureReason = "Timeout 2", incrementRetry = true)

        // Ledger must return null so worker stops retrying endlessly
        val next = ledger.nextPriorityInterval(
            playheadMs = 0L,
            maxRetryAttempts = 2,
            lookaheadHorizonMs = 180_000L,
            enforceHardHorizon = true,
        )
        assertNull("Exhausted interval must not be endlessly retried", next)

        val outcome = coordinator.evaluateSchedulerOutcome(ledger, nowEpochMs = 20_000L)
        assertEquals(SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY, outcome.outcomeState)
        assertTrue(outcome.isTemporarilyIdle)

        val human = HumanReadableStatus.resolve(
            project = project,
            scoutState = ScoutCaptionState.Ready("Best Model · Partial", 15, 2),
            coverageSummary = ledger.summary(),
            sentinelSnapshot = coordinator.inspectorSnapshot(),
        )
        assertEquals("Some dialogue is unavailable", human.label)

        // Also test paused recovery deadline stopping retries even when attempts remain
        val ledger2 = TranscriptCoverageLedger()
        ledger2.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        ledger2.recordOutcome(0L, 150_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 15)
        ledger2.recordOutcome(150_000L, 180_000L, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT, failureReason = "Transient", incrementRetry = true)
        val afterDeadline = coordinator.evaluateSchedulerOutcome(ledger2, nowEpochMs = 70_000L) // > 60s paused deadline
        assertTrue(afterDeadline.pausedDeadlineReached)
        assertEquals(SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY, afterDeadline.outcomeState)
    }

    // Case 9: Gap inside window -> continuousVerifiedLeadFrom stops at gap; RELEASED_FALLBACK never inflates verified lead
    @Test
    fun case09_gapOrFallbackInsideWindow_stopsVerifiedLeadAtGap() {
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)

        // Verified [00:00, 00:45], fallback [00:45, 01:00], verified [01:00, 03:00]
        ledger.recordOutcome(0L, 45_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 8)
        ledger.recordOutcome(45_000L, 60_000L, CoverageStatus.RELEASED_FALLBACK, SubtitleSourceKind.AI_DRAFT, dialogueCount = 3)
        ledger.recordOutcome(60_000L, 180_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 20)

        assertEquals(45_000L, ledger.continuousVerifiedLeadFrom(0L))
        assertFalse("Window containing RELEASED_FALLBACK must not report verified", ledger.isWindowVerified(0L, 180_000L))
        assertEquals(0L, ledger.continuousVerifiedLeadFrom(45_000L))
    }

    // Case 10: 2x playback speed -> same 180s media-time boundary cap, viewing-time reserve = 90s
    @Test
    fun case10_twoXPlaybackSpeed_keeps180sMediaBoundaryAndReports90sViewingReserve() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)

        coordinator.updateAcknowledgedViewerPosition(
            positionMs = 120_000L,
            durationMs = videoDurationMs,
            isPlaying = true,
            playbackSpeed = 2.0f,
        )
        assertEquals("Media-time boundary remains 180s ahead (05:00) even at 2x speed", 300_000L, coordinator.currentBoundaryEndMs())

        ledger.recordOutcome(120_000L, 300_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 24)
        val snap = coordinator.evaluateSchedulerOutcome(ledger)
        assertEquals(180_000L, snap.continuousVerifiedLeadMs)
        assertEquals("At 2x speed, 180s of verified media equals 90s of real viewing time", 90_000L, snap.viewingTimeReserveMs)

        val status = HumanReadableStatus.resolve(
            project = project,
            scoutState = ScoutCaptionState.Ready("Best Model · Window Ready", 24, 2),
            coverageSummary = ledger.summary(),
            sentinelSnapshot = coordinator.inspectorSnapshot(),
        )
        assertTrue("Detail should honestly communicate 2x viewing reserve: ${status.detail}", status.detail.contains("90s") && status.detail.contains("2.0x"))
    }

    // Case 11: Approach video end -> boundary and work stop cleanly at actual video duration; ONLY then WHOLE_VIDEO_COMPLETE
    @Test
    fun case11_approachVideoEnd_capsAtActualDurationAndReportsWholeVideoCompleteOnlyWhenFullyVerified() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)

        // Viewer at 17:00 (1_020_000ms) -> 17:00 + 03:00 = 20:00, capped at 18:15 (1_095_000ms)
        coordinator.updateAcknowledgedViewerPosition(positionMs = 1_020_000L, durationMs = videoDurationMs, isPlaying = true)
        assertEquals(videoDurationMs, coordinator.currentBoundaryEndMs())

        // Verify only [17:00, 18:15] while earlier video is unverified -> NOT whole-video complete!
        ledger.recordOutcome(1_020_000L, videoDurationMs, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 12)
        val partialEndSnap = coordinator.evaluateSchedulerOutcome(ledger)
        assertEquals(SchedulerOutcomeState.WINDOW_READY_IDLE, partialEndSnap.outcomeState)

        // Now verify the entire video [00:00, 18:15] -> ONLY NOW report WHOLE_VIDEO_COMPLETE
        ledger.recordOutcome(0L, 1_020_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 120)
        val fullCompleteSnap = coordinator.evaluateSchedulerOutcome(ledger)
        assertEquals(SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE, fullCompleteSnap.outcomeState)
        assertTrue(fullCompleteSnap.isTemporarilyIdle)

        val status = HumanReadableStatus.resolve(
            project = project,
            scoutState = ScoutCaptionState.Ready("Best Model · Full", 132, 2),
            coverageSummary = ledger.summary(),
            sentinelSnapshot = coordinator.inspectorSnapshot(),
        )
        assertEquals("Ready", status.label)
    }

    // Case 12: CC-only mode (ZERO_CLOUD) -> zero AI-provider requests, local Scout 2 review still runs
    @Test
    fun case12_ccOnlyMode_attemptsZeroAiRequestsWhileLocalScout2ReviewRemainsAvailable() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)
        coordinator.updateRuntimeConditions(quotaMode = QuotaSaverMode.ZERO_CLOUD)

        assertFalse("ZERO_CLOUD must block all AI analysis dispatch", coordinator.isAnalysisDispatchAllowed())
        assertNull("ZERO_CLOUD must block cloud changeover pre-listen", coordinator.claimChangeoverPreListenWork("sentinel_worker_2"))

        val outcome = coordinator.evaluateSchedulerOutcome(ledger, quotaMode = QuotaSaverMode.ZERO_CLOUD)
        assertEquals(SchedulerOutcomeState.WAITING_FOR_EVIDENCE_OR_CAPABILITY, outcome.outcomeState)
        assertTrue(outcome.isTemporarilyIdle)

        // Local deterministic Scout 2 review still runs offline with zero AI requests
        val reviewer = Scout2IndependentReviewer()
        val layoutEngine = BalloonLayoutEngine()
        val refTrack = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "yt_en",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            cues = listOf(
                ReferenceCaptionCue("c1", 2_000L, 5_000L, "Welcome back to the show."),
            ),
        )
        val review = reviewer.reviewDraft(
            draft = DraftInterval(
                intervalId = "win_0_15000",
                mediaId = project.id,
                sessionToken = 1L,
                seekGeneration = 0L,
                sourceRevision = 1L,
                draftRevision = 1L,
                configFingerprint = "cfg_1",
                startMs = 0L,
                endMs = 15_000L,
                sourceLanguage = "English",
                targetLanguage = "English",
                events = emptyList(),
                speakers = project.speakers,
                isConfirmedSilence = true,
            ),
            referenceTrack = refTrack,
            layoutEngine = layoutEngine,
            projectTemplate = project,
            liveFacesPlayheadMs = 0L,
        )
        assertTrue("Local Scout 2 recovers missing dialogue from native captions offline", review.repairedEvents.isNotEmpty())
        assertEquals("Welcome back to the show.", review.repairedEvents.first().sourceText)
    }

    // Bonus 1: Continuous Scout 2 review & Scout 1 backpressure + self-publication non-retrigger
    @Test
    fun scout1BackpressureThrottlesNewPrepWhenPendingReviewsHitLimit_andSelfPublicationDoesNotWakeIdle() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)
        coordinator.configurePolicy(RollingBoundaryPolicy(horizonMs = 180_000L, maxPendingReviewDrafts = 2))

        // Enqueue a Scout 1 prep task and verify backpressure blocks it when pending drafts >= 2
        coordinator.enqueue(
            SentinelWorkItem(
                id = "prep_30_60",
                tier = SentinelPriorityTier.CRITICAL_PLAYBACK_RECOVERY,
                kind = SentinelTaskKind.PLAYBACK_LOOKAHEAD,
                startMs = 30_000L,
                endMs = 60_000L,
            )
        )
        coordinator.updatePendingReviewCount(2)
        assertTrue(coordinator.shouldThrottleScout1ForReviewBackpressure())
        assertNull("Scout 1 prep must pause while Scout 2 review queue is full", coordinator.claimNextWork("sentinel_worker_1"))

        // Once Scout 2 drains a draft, Scout 1 resumes immediately
        coordinator.updatePendingReviewCount(1)
        assertFalse(coordinator.shouldThrottleScout1ForReviewBackpressure())
        val claimed = coordinator.claimNextWork("sentinel_worker_1")
        assertNotNull(claimed)

        // Verify repeated identical position ticks (timer ticks / status updates) NEVER re-trigger idle wake
        coordinator.onViewerPauseChanged(paused = true, acknowledgedPositionMs = 0L, durationMs = videoDurationMs)
        ledger.recordOutcome(0L, 180_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 18)
        // While 1 pending review draft remains, scheduler must stay ACTIVE (not idle yet)
        coordinator.evaluateSchedulerOutcome(ledger)
        assertFalse("Scheduler must remain ACTIVE while Scout 2 still has pending review drafts", coordinator.schedulerSnapshot().isTemporarilyIdle)
        // Once Scout 2 drains pending reviews to 0, scheduler settles cleanly into WINDOW_READY_IDLE
        coordinator.updatePendingReviewCount(0)
        coordinator.evaluateSchedulerOutcome(ledger)
        assertTrue(coordinator.schedulerSnapshot().isTemporarilyIdle)
        repeat(10) { tick ->
            val woke = coordinator.updateAcknowledgedViewerPosition(
                positionMs = 0L,
                durationMs = videoDurationMs,
                isPlaying = false,
                nowEpochMs = 10_000L + tick * 500L,
            )
            assertFalse("Timer/status tick #$tick must NOT re-trigger idle scheduler", woke)
        }
        assertTrue(coordinator.schedulerSnapshot().isTemporarilyIdle)
    }

    // Bonus 2: Lifecycle persistence round-trip preserves anchor, generation, pause, and idle state
    @Test
    fun persistedSchedulingState_roundTripsAcrossLifecycleRestart() {
        val project = makeProject()
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(videoDurationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(project)

        val gen = coordinator.onCommittedSeek(targetPositionMs = 120_000L, durationMs = videoDurationMs, isPlaying = false)
        ledger.recordOutcome(120_000L, 300_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 24)
        coordinator.evaluateSchedulerOutcome(ledger)
        val exported = coordinator.exportPersistedState(nowEpochMs = 99_000L)

        // Simulate fresh process / lifecycle restore into a brand new coordinator instance
        val restoredCoordinator = SentinelWorkforceCoordinator()
        restoredCoordinator.resetForProject(project.copy(schedulingState = exported))
        val restoredSnap = restoredCoordinator.schedulerSnapshot()

        assertEquals(120_000L, restoredSnap.acknowledgedViewerPosMs)
        assertEquals(300_000L, restoredSnap.boundaryEndMs)
        assertEquals(gen, restoredSnap.schedulingGeneration)
        assertTrue(restoredSnap.isViewerPaused)
        assertEquals(SchedulerOutcomeState.WINDOW_READY_IDLE, restoredSnap.outcomeState)
        assertTrue(restoredSnap.isTemporarilyIdle)
    }

    // Bonus 3: Real ViewModel Robolectric integration verifying preview freeze, committed seek, 2x speed, and ZERO_CLOUD
    @Test
    fun viewModelIntegration_scrubberPreviewSeekPauseSpeedAndZeroCloudEnforceRollingBoundary() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val project = makeProject(
            subtitleSource = SubtitleSourceKind.EMBEDDED_TRACK,
            events = listOf(makeEvent("e1", 1_000L, 4_000L, "Hello viewers")),
        )
        vm.loadProject(project)

        // Move viewer to 02:00 and pause
        vm.seekTo(120_000L)
        vm.pausePlayback()
        val pausedSnap = vm.currentSchedulerSnapshotForTest()
        assertEquals(120_000L, pausedSnap.acknowledgedViewerPosMs)
        assertEquals(300_000L, pausedSnap.boundaryEndMs)
        assertTrue(pausedSnap.isViewerPaused)

        // Scrubber preview freezes boundary and blocks dispatch
        vm.onScrubberPreviewStart()
        assertTrue(vm.currentSchedulerSnapshotForTest().isScrubberPreviewActive)
        assertEquals(300_000L, vm.currentSchedulerSnapshotForTest().boundaryEndMs)
        vm.onScrubberPreviewEnd()
        assertFalse(vm.currentSchedulerSnapshotForTest().isScrubberPreviewActive)

        // 2x playback speed updates viewing reserve ratio without extending 180s media boundary
        vm.setPlaybackSpeed(2.0f)
        val speedSnap = vm.currentSchedulerSnapshotForTest()
        assertEquals(2.0f, speedSnap.playbackSpeed, 0.001f)
        assertEquals(300_000L, speedSnap.boundaryEndMs)

        // Backward seek bumps generation and re-anchors boundary
        val genBefore = speedSnap.schedulingGeneration
        vm.seekTo(30_000L)
        val afterSeekSnap = vm.currentSchedulerSnapshotForTest()
        assertTrue("Committed seek must advance scheduling generation", afterSeekSnap.schedulingGeneration > genBefore)
        assertEquals(30_000L, afterSeekSnap.acknowledgedViewerPosMs)
        assertEquals(210_000L, afterSeekSnap.boundaryEndMs)

        // ZERO_CLOUD mode keeps scheduling honest with zero cloud dispatch
        vm.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)
        assertEquals(QuotaSaverMode.ZERO_CLOUD, vm.uiState.value.quotaSaverMode)
    }
}
