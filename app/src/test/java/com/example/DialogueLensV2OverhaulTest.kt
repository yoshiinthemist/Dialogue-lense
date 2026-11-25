package com.example

import com.example.data.model.BalloonStyleSettings
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.ChapterSource
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealProblemHint
import com.example.data.model.HealScope
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.QuotaSaverMode
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoChapter
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.AutoSelfHealDetector
import com.example.domain.analysis.HealBeaconEngine
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.TimeWindowRange
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.analysis.TranscriptionOutcome
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.TailDirection
import com.example.domain.playback.BufferingFailureAction
import com.example.domain.playback.PlaybackReadinessController
import com.example.domain.playback.PlaybackReadinessState
import com.example.domain.playback.UserPlaybackIntent
import com.example.domain.sentinel.ChapterBoundaryAnomalyKind
import com.example.domain.sentinel.ChapterInspectionStatus
import com.example.domain.sentinel.SentinelPriorityTier
import com.example.domain.sentinel.SentinelTaskKind
import com.example.domain.sentinel.SentinelWorkItem
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import com.example.ui.state.HumanReadableStatus
import com.example.ui.state.ScoutCaptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DialogueLensV2OverhaulTest {

    private fun testEvent(
        id: String,
        startTimeMs: Long,
        endTimeMs: Long,
        text: String,
        speakerId: String? = "spk_a",
        speakerConfidence: Float = 0.90f,
        dialogueType: DialogueType = DialogueType.SPOKEN,
        anchorPosition: NormalizedPoint? = null,
    ): DialogueEvent = DialogueEvent(
        id = id,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        sourceText = text,
        translatedText = text,
        speakerId = speakerId,
        speakerConfidence = speakerConfidence,
        faceTrackId = null,
        faceConfidence = 0f,
        shotId = "shot_1",
        dialogueType = dialogueType,
        anchorPosition = anchorPosition,
    )

    private fun sampleProject(
        id: String = "proj_v2",
        durationMs: Long = 180_000L,
        speakers: List<Speaker> = listOf(
            Speaker(id = "spk_a", defaultLabel = "Speaker A", customName = "Maya", accentIndex = 0, isIdentityConfirmed = true, preferredScreenSideX = 0.28f),
            Speaker(id = "spk_b", defaultLabel = "Speaker B", customName = "Liam", accentIndex = 1, isIdentityConfirmed = true, preferredScreenSideX = 0.72f),
        ),
        events: List<DialogueEvent> = listOf(
            testEvent(
                id = "evt_1",
                startTimeMs = 1_000L,
                endTimeMs = 5_000L,
                text = "We should check the harbor before dusk.",
                speakerId = "spk_a",
                speakerConfidence = 0.92f,
            ),
            testEvent(
                id = "evt_2",
                startTimeMs = 6_000L,
                endTimeMs = 10_000L,
                text = "Agreed, the tide turns quickly.",
                speakerId = "spk_b",
                speakerConfidence = 0.90f,
            ),
        ),
        chapters: List<VideoChapter> = emptyList(),
        healBeacons: List<HealBeacon> = emptyList(),
        styleSettings: BalloonStyleSettings = BalloonStyleSettings(),
    ): DialogueLensProject = DialogueLensProject(
        id = id,
        title = "Harbor Documentary",
        createdAtEpochMs = 1_000L,
        sourceLanguage = "en",
        targetLanguage = "en",
        videoSource = VideoSource(
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            uriString = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            displayName = "Harbor Documentary",
            durationMs = durationMs,
            width = 1920,
            height = 1080,
        ),
        shots = listOf(Shot("shot_1", 0L, durationMs)),
        speakers = speakers,
        faceTracks = emptyList(),
        dialogueEvents = events,
        chapters = chapters,
        healBeacons = healBeacons,
        styleSettings = styleSettings,
    )

    // =========================================================================
    // PART I — Visual Stability & Anti-Flicker (BalloonLayoutEngine)
    // =========================================================================

    @Test
    fun deadZoneAndHysteresisSuppressMinorFaceJitter() {
        val engine = BalloonLayoutEngine()
        val project = sampleProject()

        val initialFace = LiveFaceObservation(
            trackingId = 1,
            boundingBox = NormalizedRect(0.22f, 0.25f, 0.36f, 0.50f),
            mouthPoint = NormalizedPoint(0.29f, 0.43f),
            headYawDeg = 0f,
            capturedAtMs = 2_000L,
        )
        val first = engine.computeLayout(
            project = project,
            currentMs = 2_000L,
            liveFaces = listOf(initialFace),
            hasLiveVisionSignal = true,
        )
        assertEquals(1, first.size)
        val firstRect = first.first().rect

        // Apply tiny realistic detector micro-jitter (+0.009 normalized shift)
        val jitteredFace = LiveFaceObservation(
            trackingId = 1,
            boundingBox = NormalizedRect(0.229f, 0.256f, 0.369f, 0.506f),
            mouthPoint = NormalizedPoint(0.299f, 0.436f),
            headYawDeg = 0f,
            capturedAtMs = 2_200L,
        )
        val second = engine.computeLayout(
            project = project,
            currentMs = 2_200L,
            liveFaces = listOf(jitteredFace),
            hasLiveVisionSignal = true,
        )
        assertEquals(1, second.size)
        val secondRect = second.first().rect

        // Bubble center must stay locked inside dead-zone without relocating
        assertTrue(abs(firstRect.centerX - secondRect.centerX) < 0.015f)
        assertTrue(abs(firstRect.centerY - secondRect.centerY) < 0.015f)
        assertEquals(0, engine.stabilityDiagnostics.snapshot().totalRelocations)
    }

    @Test
    fun singleFrameTrackingDropoutPreservesTailWithoutFlicker() {
        val engine = BalloonLayoutEngine()
        val project = sampleProject()
        val face = LiveFaceObservation(
            trackingId = 7,
            boundingBox = NormalizedRect(0.20f, 0.26f, 0.36f, 0.52f),
            mouthPoint = NormalizedPoint(0.28f, 0.44f),
            headYawDeg = 0f,
            capturedAtMs = 2_000L,
        )

        val frame1 = engine.computeLayout(
            project = project,
            currentMs = 2_000L,
            liveFaces = listOf(face),
            hasLiveVisionSignal = true,
        )
        assertEquals(1, frame1.size)
        val tailBefore = frame1.first().tailDirection
        assertTrue("Expected tail pointing to visible speaker", tailBefore != TailDirection.NONE)

        // Single dropped detection frame (not yet confirmed empty): tail must hold steady
        val frame2 = engine.computeLayout(
            project = project,
            currentMs = 2_180L,
            liveFaces = emptyList(),
            hasLiveVisionSignal = true,
            liveFrameObservedEmpty = false,
        )
        assertEquals(1, frame2.size)
        assertEquals(tailBefore, frame2.first().tailDirection)
    }

    @Test
    fun invalidateEventsOnlyPreservesUnmodifiedActiveCueState() {
        val engine = BalloonLayoutEngine()
        val evtA = testEvent(
            id = "evt_a",
            startTimeMs = 1_000L,
            endTimeMs = 6_000L,
            text = "First speaker talking.",
            speakerId = "spk_a",
            speakerConfidence = 0.95f,
        )
        val evtB = testEvent(
            id = "evt_b",
            startTimeMs = 1_500L,
            endTimeMs = 6_000L,
            text = "Second speaker talking.",
            speakerId = "spk_b",
            speakerConfidence = 0.95f,
        )
        val project = sampleProject(events = listOf(evtA, evtB))
        val faces = listOf(
            LiveFaceObservation(1, NormalizedRect(0.16f, 0.25f, 0.30f, 0.50f), NormalizedPoint(0.23f, 0.43f), 0f, 2_500L),
            LiveFaceObservation(2, NormalizedRect(0.68f, 0.25f, 0.82f, 0.50f), NormalizedPoint(0.75f, 0.43f), 0f, 2_500L),
        )

        val before = engine.computeLayout(project, currentMs = 2_500L, liveFaces = faces, hasLiveVisionSignal = true)
        val rectABefore = before.first { it.event.id == "evt_a" }.rect

        // Selectively invalidate only evt_b (as after localized Heal Beacon repair)
        engine.invalidateEventsOnly(setOf("evt_b"))
        val after = engine.computeLayout(project, currentMs = 2_700L, liveFaces = faces, hasLiveVisionSignal = true)
        val rectAAfter = after.first { it.event.id == "evt_a" }.rect

        assertTrue(abs(rectABefore.centerX - rectAAfter.centerX) < 0.01f)
        assertTrue(abs(rectABefore.centerY - rectAAfter.centerY) < 0.01f)
    }

    @Test
    fun readingComfortVisualStyleProducesValidBoundedLayout() {
        val engine = BalloonLayoutEngine()
        val project = sampleProject(
            styleSettings = BalloonStyleSettings(visualStyle = BubbleVisualStyle.READING_COMFORT)
        )
        val placed = engine.computeLayout(project, currentMs = 2_000L)
        assertEquals(1, placed.size)
        val r = placed.first().rect
        assertTrue(r.left >= 0f && r.right <= 1f && r.top >= 0f && r.bottom <= 1f)
        assertTrue(r.width > 0.1f && r.height > 0.04f)
    }

    // =========================================================================
    // PART II — Authoritative 12-State Playback Readiness & Adaptive Buffering
    // =========================================================================

    @Test
    fun resumeAtNonZeroTimestampPreservesExactPositionAndAutoResumesOnce() {
        val ledger = TranscriptCoverageLedger(initialDurationMs = 300_000L)
        val controller = PlaybackReadinessController()
        controller.resetForMedia(
            initialPositionMs = 120_000L,
            durationMs = 300_000L,
            requiresCoverageGate = true,
        )

        // User hits Play at resumed position 2:00 (120_000ms) before coverage is ready
        val buffering = controller.onUserPlayRequested(positionMs = 120_000L, ledger = ledger, nowEpochMs = 1_000L)
        assertEquals(PlaybackReadinessState.BUFFERING_INITIAL_WINDOW, buffering.state)
        assertEquals(120_000L, buffering.targetPositionMs)
        assertTrue(buffering.isBufferingHoldActive)
        assertTrue(buffering.willAutoResumeWhenReady)
        assertFalse("Buffering hold must NEVER look like intentional user pause", buffering.isIntentionallyPausedByUser)

        // Ledger verifies [120_000, 130_000) -> 10s verified lead at 1.0x satisfies 7s threshold
        ledger.recordOutcome(
            startMs = 120_000L,
            endMs = 130_000L,
            status = CoverageStatus.VERIFIED_DIALOGUE,
            source = SubtitleSourceKind.AI_DRAFT,
            dialogueCount = 3,
        )
        val ready = controller.evaluateCoverage(ledger, nowEpochMs = 3_000L)
        assertEquals(PlaybackReadinessState.READY_TO_AUTO_RESUME, ready.state)
        assertNotNull(ready.pendingAutoResume)
        assertEquals(120_000L, ready.pendingAutoResume!!.targetPositionMs)

        // Consuming one-shot directive transitions cleanly into PLAYING and clears directive
        val playing = controller.consumeAutoResumeDirective(ready.pendingAutoResume!!.id)
        assertEquals(PlaybackReadinessState.PLAYING, playing.state)
        assertNull(playing.pendingAutoResume)
    }

    @Test
    fun seekGenerationRejectsStalePreSeekSamplesAndHonorsUserPause() {
        val ledger = TranscriptCoverageLedger(initialDurationMs = 300_000L)
        val controller = PlaybackReadinessController()
        controller.resetForMedia(0L, 300_000L, requiresCoverageGate = true)
        controller.onUserPlayRequested(0L, ledger, nowEpochMs = 1_000L)

        // User seeks to 90_000ms (unverified)
        val seeking = controller.onUserSeekRequested(90_000L, ledger, nowEpochMs = 2_000L)
        assertEquals(PlaybackReadinessState.BUFFERING_FOR_SEEK, seeking.state)
        assertEquals(1L, seeking.diagnostics.seekGeneration)
        assertTrue(seeking.diagnostics.isSeekInFlight)

        // Stale player tick still reporting old position 4_000ms must be ignored
        val staleTick = controller.onPlayerPositionSample(4_000L, playerIsPlaying = false, ledger = ledger, nowEpochMs = 2_200L)
        assertTrue(staleTick.diagnostics.isSeekInFlight)
        assertEquals(90_000L, staleTick.targetPositionMs)

        // Player lands near 90_000ms -> seek generation acknowledged
        val acked = controller.onPlayerPositionSample(90_200L, playerIsPlaying = false, ledger = ledger, nowEpochMs = 2_500L)
        assertFalse(acked.diagnostics.isSeekInFlight)

        // User intentionally pauses while buffering -> must cancel auto-resume even after coverage arrives
        val paused = controller.onUserPauseRequested(90_200L)
        assertEquals(PlaybackReadinessState.PAUSED_BY_USER, paused.state)
        assertTrue(paused.isIntentionallyPausedByUser)
        assertNull(paused.pendingAutoResume)

        ledger.recordOutcome(90_000L, 105_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 4)
        val afterCoverage = controller.evaluateCoverage(ledger, nowEpochMs = 3_000L)
        assertEquals(PlaybackReadinessState.PAUSED_BY_USER, afterCoverage.state)
        assertNull("Never auto-resume after intentional user pause", afterCoverage.pendingAutoResume)
    }

    @Test
    fun speedScaledThresholdsAndShortVideoCapping() {
        val controller = PlaybackReadinessController()
        controller.resetForMedia(0L, 600_000L, requiresCoverageGate = true, playbackSpeed = 1.0f)
        val lead1x = controller.requiredInitialOrSeekLeadMs(10_000L)
        val mid1x = controller.midPlaybackBufferTriggerMs(10_000L)

        controller.setPlaybackSpeed(2.0f)
        val lead2x = controller.requiredInitialOrSeekLeadMs(10_000L)
        val mid2x = controller.midPlaybackBufferTriggerMs(10_000L)
        assertEquals(lead1x * 2L, lead2x)
        assertEquals(mid1x * 2L, mid2x)

        // Short remaining video (4 seconds left at 2.0x) must cap below remaining duration so it never deadlocks
        val shortController = PlaybackReadinessController()
        shortController.resetForMedia(0L, 4_000L, requiresCoverageGate = true, playbackSpeed = 2.0f)
        assertTrue(shortController.requiredInitialOrSeekLeadMs(0L) <= 4_000L)
    }

    @Test
    fun watchNowBypassIsEpisodeScopedAndTimeoutExposesRecoveryActions() {
        val ledger = TranscriptCoverageLedger(initialDurationMs = 200_000L)
        val controller = PlaybackReadinessController(waitTimeoutMs = 5_000L)
        controller.resetForMedia(0L, 200_000L, requiresCoverageGate = true)
        controller.onUserPlayRequested(0L, ledger, nowEpochMs = 1_000L)

        // Exceed bounded wait timeout -> enters PLAYBACK_OR_ANALYSIS_ERROR with explicit recovery actions
        val timedOut = controller.evaluateCoverage(ledger, nowEpochMs = 7_000L)
        assertEquals(PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR, timedOut.state)
        assertTrue(timedOut.waitTimedOut)
        assertTrue(timedOut.availableRecoveryActions.contains(BufferingFailureAction.RETRY))
        assertTrue(timedOut.availableRecoveryActions.contains(BufferingFailureAction.WATCH_WITHOUT_SUBTITLES))
        assertTrue(timedOut.availableRecoveryActions.contains(BufferingFailureAction.ATTACH_SUBTITLES))
        assertFalse(timedOut.isIntentionallyPausedByUser)

        // User chooses Watch Now -> scoped bypass active for this episode only
        val bypassed = controller.onUserWatchNowBypass()
        assertEquals(PlaybackReadinessState.WATCHING_WITHOUT_FULL_COVERAGE, bypassed.state)
        assertTrue(bypassed.isWatchNowBypassActive)

        // Seeking to a new unverified region immediately clears the old bypass and buffers honestly
        val afterSeek = controller.onUserSeekRequested(80_000L, ledger, nowEpochMs = 8_000L)
        assertFalse(afterSeek.isWatchNowBypassActive)
        assertEquals(PlaybackReadinessState.BUFFERING_FOR_SEEK, afterSeek.state)
    }

    // =========================================================================
    // PART III — Adaptive Sentinel Workforce, Chapter Patrol & Heal Beacons
    // =========================================================================

    @Test
    fun sentinelPriorityQueueReservationsAndAdaptiveWorkerScaling() {
        val coordinator = SentinelWorkforceCoordinator()
        coordinator.updateRuntimeConditions(
            quotaMode = QuotaSaverMode.QUOTA_SAVER,
            isPlaybackBuffering = false,
            contiguousPlayheadLeadMs = 30_000L,
        )

        // Only background patrol queued -> stays at 1 worker
        coordinator.enqueue(
            SentinelWorkItem("bg_1", SentinelPriorityTier.BACKGROUND_MAINTENANCE, SentinelTaskKind.BACKGROUND_GAP_FILL, 100_000L, 120_000L)
        )
        assertEquals(1, coordinator.maxAllowedWorkers())

        // High-priority playback recovery + user Heal Beacon arrive -> scales to 2 workers
        coordinator.enqueue(
            SentinelWorkItem("heal_1", SentinelPriorityTier.USER_DIRECTED_REPAIR, SentinelTaskKind.USER_HEAL_BEACON, 40_000L, 60_000L)
        )
        coordinator.enqueue(
            SentinelWorkItem("crit_1", SentinelPriorityTier.CRITICAL_PLAYBACK_RECOVERY, SentinelTaskKind.PLAYBACK_LOOKAHEAD, 10_000L, 30_000L)
        )
        assertEquals(2, coordinator.maxAllowedWorkers())

        // Worker 1 claims highest-priority item (CRITICAL_PLAYBACK_RECOVERY)
        val claim1 = coordinator.claimNextWork(SentinelWorkforceCoordinator.WORKER_1_ID)
        assertNotNull(claim1)
        assertEquals("crit_1", claim1!!.first.id)

        // Overlapping reservation for [15_000, 35_000) by Worker 2 must be rejected
        val overlapAttempt = coordinator.tryReserveInterval(
            ownerId = SentinelWorkforceCoordinator.WORKER_2_ID,
            startMs = 15_000L,
            endMs = 35_000L,
            tier = SentinelPriorityTier.USER_DIRECTED_REPAIR,
        )
        assertNull(overlapAttempt)

        // Worker 2 claims non-overlapping USER_DIRECTED_REPAIR in parallel
        val claim2 = coordinator.claimNextWork(SentinelWorkforceCoordinator.WORKER_2_ID)
        assertNotNull(claim2)
        assertEquals("heal_1", claim2!!.first.id)

        // Quota error or ZERO_CLOUD immediately throttles back to 1 worker
        coordinator.recordQuotaError()
        assertEquals(1, coordinator.maxAllowedWorkers())
        coordinator.recordQuotaSuccess()
        coordinator.updateRuntimeConditions(quotaMode = QuotaSaverMode.ZERO_CLOUD)
        assertEquals(1, coordinator.maxAllowedWorkers())
    }

    @Test
    fun defaultYouTubeChapterPatrolMergesCloseChaptersAndRunsZeroCloudLocalCheck() {
        val chapters = listOf(
            VideoChapter("ch_0", "Intro", 0L, 40_000L, ChapterSource.YOUTUBE_DESCRIPTION),
            VideoChapter("ch_1", "Chapter 1: Harbor", 40_000L, 52_000L, ChapterSource.YOUTUBE_DESCRIPTION),
            VideoChapter("ch_2", "Chapter 2: Docks", 52_000L, 120_000L, ChapterSource.YOUTUBE_DESCRIPTION), // overlaps ch_1's [-10s,+15s]
            VideoChapter("ch_3", "Chapter 3: Sunset", 120_000L, 180_000L, ChapterSource.YOUTUBE_METADATA),
        )
        val events = listOf(
            // Clean boundary around 120_000ms
            testEvent("e_pre_clean", 112_000L, 118_000L, "The sun is dropping fast.", "spk_a", 0.92f),
            testEvent("e_post_clean", 122_000L, 128_000L, "Evening lights come on across the pier.", "spk_b", 0.91f),
            // Echoed prior-chapter dialogue + invalid anchor around 40_000ms
            testEvent("e_pre_bad", 34_000L, 39_000L, "Welcome to the harbor tour.", "spk_a", 0.88f),
            testEvent(
                id = "e_post_bad",
                startTimeMs = 41_000L,
                endTimeMs = 46_000L,
                text = "Welcome to the harbor tour.",
                speakerId = "spk_a",
                speakerConfidence = 0.30f,
                anchorPosition = NormalizedPoint(1.4f, 0.5f),
            ),
        )
        val project = sampleProject(durationMs = 180_000L, events = events, chapters = chapters)
        val ledger = TranscriptCoverageLedger(initialDurationMs = 180_000L)
        ledger.recordOutcome(0L, 180_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 4)

        val coordinator = SentinelWorkforceCoordinator()
        val records = coordinator.planDefaultChapterPatrol(project)

        // ch_0 skipped (starts at 0ms), ch_1 + ch_2 merged into 1 record, ch_3 separate -> 2 patrol records
        assertEquals(2, records.size)
        val mergedRecord = records.first { it.chapterId == "ch_1" }
        assertEquals(30_000L, mergedRecord.windowStartMs)
        assertEquals(67_000L, mergedRecord.windowEndMs)

        // When playback is buffering or lead < 15s, chapter patrol is suspended
        coordinator.updateRuntimeConditions(isPlaybackBuffering = true, contiguousPlayheadLeadMs = 5_000L)
        assertTrue(coordinator.isPatrolSuspendedForPlayback())
        assertNull(coordinator.claimNextWork(SentinelWorkforceCoordinator.WORKER_1_ID))

        // Restore healthy lead -> local zero-cloud check verifies clean ch_3 without cloud escalation
        coordinator.updateRuntimeConditions(isPlaybackBuffering = false, contiguousPlayheadLeadMs = 30_000L)
        val cleanResult = coordinator.runLocalChapterBoundaryCheck(project, "ch_3", ledger)
        assertFalse(cleanResult.hasDefects)
        assertEquals(
            ChapterInspectionStatus.INSPECTED_WITHOUT_DEFECTS,
            coordinator.inspectorSnapshot().chapterPatrolRecords.first { it.chapterId == "ch_3" }.status,
        )

        // Defective ch_1 is flagged REPAIR_REQUIRED with exact anomaly types
        val badResult = coordinator.runLocalChapterBoundaryCheck(project, "ch_1", ledger)
        assertTrue(badResult.hasDefects)
        assertTrue(badResult.anomalies.contains(ChapterBoundaryAnomalyKind.ECHOED_PRIOR_CHAPTER_DIALOGUE))
        assertTrue(badResult.anomalies.contains(ChapterBoundaryAnomalyKind.STALE_OR_INVALID_ANCHOR))
        assertEquals(
            ChapterInspectionStatus.REPAIR_REQUIRED,
            coordinator.inspectorSnapshot().chapterPatrolRecords.first { it.chapterId == "ch_1" }.status,
        )
    }

    @Test
    fun healBeaconImmutableActiveWindowPartialSubRangeInPlaceIdAndUndo() {
        val originalCue1 = testEvent("cue_keep_id", 10_000L, 14_000L, "Hello harbor world", "spk_a", 0.7f)
        val originalCue2 = testEvent("cue_untouched_tail", 26_000L, 29_000L, "Keep me untouched in partial repair", "spk_b", 0.9f)
        val activeBeacon = HealBeacon(
            id = "hb_active",
            timestampMs = 15_000L,
            windowStartMs = 5_000L,
            windowEndMs = 30_000L,
            status = HealBeaconStatus.HEALING,
            scope = HealScope.ADAPTIVE,
            problemHint = HealProblemHint.WRONG_SPEAKER,
            repairRevision = 2,
        )
        val project = sampleProject(
            durationMs = 60_000L,
            events = listOf(originalCue1, originalCue2),
            healBeacons = listOf(activeBeacon),
        )

        // 1. Overlapping mark while HEALING must NOT mutate active beacon's window; queues uncovered tail separately
        val (withFollowUp, followUpBeacon) = HealBeaconEngine.placeOrMergeBeacon(
            project = project,
            timestampMs = 35_000L,
            scope = HealScope.ADAPTIVE,
        )
        val stillActive = withFollowUp.healBeacons.first { it.id == "hb_active" }
        assertEquals(5_000L, stillActive.windowStartMs)
        assertEquals(30_000L, stillActive.windowEndMs)
        assertTrue(followUpBeacon.id != "hb_active")
        assertEquals(HealBeaconStatus.MARKED, followUpBeacon.status)
        assertTrue(followUpBeacon.windowStartMs >= 30_000L)

        // 2. Stale repairRevision commit is rejected
        val staleOutcome = HealBeaconEngine.applyValidatedLocalizedRepair(
            project = project,
            beaconId = "hb_active",
            candidateEvents = listOf(originalCue1.copy(id = "new_id_1", speakerId = "spk_b")),
            expectedRepairRevision = 1,
        )
        assertFalse(staleOutcome.applied)

        // 3. Partial verifiedSubRanges [5_000, 20_000) preserves cue_untouched_tail at 26..29s AND preserves cue_keep_id in place
        val partialOutcome = HealBeaconEngine.applyValidatedLocalizedRepair(
            project = project,
            beaconId = "hb_active",
            candidateEvents = listOf(
                testEvent("temp_gen_id", 10_100L, 13_900L, "Hello harbor world!", "spk_b", 0.96f)
            ),
            verifiedSubRanges = listOf(TimeWindowRange(5_000L, 20_000L)),
            expectedRepairRevision = 2,
        )
        assertTrue(partialOutcome.applied)
        assertEquals(1, partialOutcome.preservedIdentityCount)
        assertTrue(partialOutcome.updatedProject.dialogueEvents.any { it.id == "cue_keep_id" && it.speakerId == "spk_b" })
        assertTrue(partialOutcome.updatedProject.dialogueEvents.any { it.id == "cue_untouched_tail" })

        // 4. Full heal + Undo transitions cleanly to HealBeaconStatus.UNDONE and restores original speaker
        val fullProject = project.copy(healBeacons = listOf(activeBeacon.copy(repairRevision = 2)))
        val fullOutcome = HealBeaconEngine.applyValidatedLocalizedRepair(
            project = fullProject,
            beaconId = "hb_active",
            candidateEvents = listOf(
                testEvent("temp_gen_id", 10_000L, 14_000L, "Hello harbor world!", "spk_b", 0.96f)
            ),
            expectedRepairRevision = 2,
        )
        assertEquals(HealBeaconStatus.HEALED, fullOutcome.updatedBeacon.status)
        val undoneProject = HealBeaconEngine.undoBeaconRepair(fullOutcome.updatedProject, "hb_active")
        assertEquals(HealBeaconStatus.UNDONE, undoneProject.healBeacons.first { it.id == "hb_active" }.status)
        assertTrue(undoneProject.dialogueEvents.any { it.id == "cue_keep_id" && it.speakerId == "spk_a" })
    }

    @Test
    fun autoSelfHealNeverMergesTwoDistinctConfirmedSpeakers() {
        val spkLeftConfirmed = Speaker(id = "s1", defaultLabel = "Speaker A", customName = "Alex", accentIndex = 0, isIdentityConfirmed = true, preferredScreenSideX = 0.25f)
        val spkRightConfirmed = Speaker(id = "s2", defaultLabel = "Speaker B", customName = "Alex", accentIndex = 1, isIdentityConfirmed = true, preferredScreenSideX = 0.75f)
        val spkUnconfirmedDup = Speaker(id = "s3", defaultLabel = "Speaker C", customName = "Maya", accentIndex = 2, isIdentityConfirmed = false, preferredScreenSideX = 0.50f)
        val spkConfirmedMaya = Speaker(id = "s4", defaultLabel = "Speaker D", customName = "Maya", accentIndex = 3, isIdentityConfirmed = true, preferredScreenSideX = 0.52f)

        val project = sampleProject(
            speakers = listOf(spkLeftConfirmed, spkRightConfirmed, spkUnconfirmedDup, spkConfirmedMaya),
            events = listOf(
                testEvent("e1", 0L, 2_000L, "Hi", "s1", 0.9f),
                testEvent("e2", 2_000L, 4_000L, "Hey", "s2", 0.9f),
                testEvent("e3", 4_000L, 6_000L, "Welcome", "s3", 0.7f),
            ),
        )

        val consolidated = AutoSelfHealDetector.consolidateDuplicateSpeakers(project)
        // Both confirmed Alex speakers MUST remain distinct; unconfirmed Maya merges into confirmed Maya
        assertTrue(consolidated.speakers.any { it.id == "s1" })
        assertTrue(consolidated.speakers.any { it.id == "s2" })
        assertEquals(3, consolidated.speakers.size)
        assertEquals("s4", consolidated.dialogueEvents.first { it.id == "e3" }.speakerId)
    }

    // =========================================================================
    // PART IV — Calm Consumer Watch Status (Zero Internal Jargon)
    // =========================================================================

    @Test
    fun humanReadableStatusUsesCalmPlainLanguageWithoutInternalJargon() {
        val project = sampleProject()
        val ledger = TranscriptCoverageLedger(initialDurationMs = 120_000L)
        val controller = PlaybackReadinessController()
        controller.resetForMedia(0L, 120_000L, requiresCoverageGate = true)
        val seekSnap = controller.onUserSeekRequested(45_000L, ledger, nowEpochMs = 1_000L)

        val resolved = HumanReadableStatus.resolve(
            project = project,
            scoutState = ScoutCaptionState.AiTranscribing(message = "Transcribing window", completedWindows = 2, cuesSoFar = 10),
            coverageSummary = ledger.summary(),
            readinessSnapshot = seekSnap,
        )
        val combined = "${resolved.label} ${resolved.detail}".lowercase()
        assertFalse(combined.contains("scout"))
        assertFalse(combined.contains("gemini"))
        assertFalse(combined.contains("429"))
        assertFalse(combined.contains("%"))
        assertFalse(combined.contains("/"))
    }
}
