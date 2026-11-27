package com.example

import com.example.data.ai.AiTranscribedCue
import com.example.data.ai.AiYouTubeTranscriptionResult
import com.example.data.ai.ScoutHeartbeatService
import com.example.data.ai.TranslationAiService
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CaptionOriginKind
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.ReferenceCaptionCue
import com.example.data.model.ReferenceCaptionTrack
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.ResilientYouTubeSegmentExecutor
import com.example.domain.analysis.ScoutExecutionTier
import com.example.domain.analysis.SegmentExecutionOutcome
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.attribution.SpeakerAttributionEngine
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.VisualStabilityDiagnostics
import com.example.domain.pipeline.DialogueReviewStatus
import com.example.domain.pipeline.DraftInterval
import com.example.domain.pipeline.ReleasedTimelineStore
import com.example.domain.pipeline.Scout2IndependentReviewer
import com.example.domain.playback.PlaybackReadinessController
import com.example.domain.playback.PlaybackReadinessState
import com.example.domain.playback.UserPlaybackIntent
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression test suite verifying every core domain repair from the 2026-11-25 audit:
 * - Per-cue verification & semantic/negation equivalence
 * - Language integrity & translation bypass fixes (empty-draft & Auto-Detect)
 * - Monotonic draftRevision / sourceRevision release-store checks
 * - Exact review task matching & ZERO_CLOUD queue cancellation
 * - Bounded retry exhaustion, uploaded-file gap recovery, saved AI resume
 * - TimeoutCancellationException retry/bisection vs genuine CancellationException propagation
 * - Audio chunk upper-bound timestamp rejection
 * - Null-ID speaker correction isolation
 * - Bubble stability across Ordinary, Fixed Zones, Reading Comfort, distinct detectorFrameId dropout,
 *   NEUTRAL_FALLBACK non-attachment, and gradual multi-frame reversal detection
 * - Real PlaybackReadinessController with RELEASED_FALLBACK & user pause respect
 */
class DialogueLensAuditRepairsTest {

    private fun testProject(
        id: String = "proj_audit",
        sourceLang: String = "English",
        targetLang: String = "English",
        subtitleSource: SubtitleSourceKind = SubtitleSourceKind.EMBEDDED_TRACK,
        events: List<DialogueEvent> = emptyList(),
    ): DialogueLensProject {
        val spk1 = Speaker(id = "spk_1", defaultLabel = "Host", accentIndex = 0)
        val spk2 = Speaker(id = "spk_2", defaultLabel = "Guest", accentIndex = 1)
        return DialogueLensProject(
            id = id,
            title = "Audit Video",
            createdAtEpochMs = 1_000L,
            videoSource = VideoSource(
                uriString = "https://youtu.be/RzagPoxaolQ",
                displayName = "Audit Video",
                kind = VideoSourceKind.YOUTUBE_COMPLIANT,
                durationMs = 120_000L,
                width = 1920,
                height = 1080,
                youtubeVideoId = "RzagPoxaolQ",
            ),
            subtitleSource = subtitleSource,
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            shots = listOf(Shot("shot_1", 0L, 120_000L, cutType = "EMBEDDED_YOUTUBE")),
            speakers = listOf(spk1, spk2),
            faceTracks = emptyList(),
            dialogueEvents = events,
        )
    }

    private fun testEvent(
        id: String,
        startMs: Long,
        endMs: Long,
        sourceText: String,
        translatedText: String = sourceText,
        speakerId: String? = "spk_1",
        faceTrackId: String? = null,
        placementMode: PlacementMode = PlacementMode.AUTO,
        anchor: NormalizedPoint? = NormalizedPoint(0.32f, 0.56f),
    ): DialogueEvent = DialogueEvent(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        sourceText = sourceText,
        translatedText = translatedText,
        speakerId = speakerId,
        speakerConfidence = if (speakerId != null) 0.90f else 0f,
        faceTrackId = faceTrackId,
        faceConfidence = if (faceTrackId != null) 0.85f else 0f,
        shotId = "shot_1",
        dialogueType = DialogueType.SPOKEN,
        anchorPosition = anchor,
        placementMode = placementMode,
        stableCueKey = "stable_$id",
    )

    @Test
    fun perCueVerificationNeverMarksWholeIntervalVerifiedWhenOneCueIsUnsupported() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = testProject()
        val refTrack = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "yt_en",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            cues = listOf(
                ReferenceCaptionCue("r1", 1_000L, 4_000L, "Welcome back to the workshop."),
            ),
        )
        val draft = DraftInterval(
            intervalId = "iv_0_20",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|BALANCED",
            startMs = 0L,
            endMs = 20_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(
                testEvent("c1", 1_000L, 4_000L, "Welcome back to the workshop."),
                testEvent("c2", 10_000L, 14_000L, "Totally unsupported extra line."),
            ),
            speakers = project.speakers,
            referenceTrackId = refTrack.trackId,
        )

        val outcome = reviewer.reviewDraft(draft, refTrack, layout, project)
        assertEquals(2, outcome.perCueReviews.size)
        assertFalse(
            "One matching cue + one unsupported cue must NEVER verify the whole interval",
            outcome.isIndependentlyVerified,
        )
        assertEquals(DialogueReviewStatus.FALLBACK_UNVERIFIED, outcome.dialogueStatus)
    }

    @Test
    fun semanticEquivalenceCatchesNegationFlipAndNumericChange() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = testProject()
        val refTrack = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "yt_en",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            cues = listOf(
                ReferenceCaptionCue("r1", 1_000L, 4_500L, "I did not send it in 50 minutes."),
            ),
        )
        val draft = DraftInterval(
            intervalId = "iv_neg",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|BALANCED",
            startMs = 0L,
            endMs = 10_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(
                testEvent("c1", 1_000L, 4_500L, "I did send it in 5 minutes."),
            ),
            speakers = project.speakers,
            referenceTrackId = refTrack.trackId,
        )

        val outcome = reviewer.reviewDraft(draft, refTrack, layout, project)
        assertEquals(DialogueReviewStatus.REPAIRED_FROM_REFERENCE, outcome.dialogueStatus)
        assertEquals("I did not send it in 50 minutes.", outcome.repairedEvents.first().sourceText)
    }

    @Test
    fun unrelatedLanguageReferenceDoesNotClaimRepairAndSourceRepairDoesNotVerifyTranslation() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = testProject(sourceLang = "Japanese", targetLang = "English")

        // Case A: Unrelated French reference for Japanese -> English
        val frenchRef = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "yt_fr",
            languageCode = "fr",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            cues = listOf(ReferenceCaptionCue("r1", 1_000L, 4_000L, "Bonjour tout le monde.")),
        )
        val draftA = DraftInterval(
            intervalId = "iv_fr",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "Japanese|English|NATURAL|BALANCED",
            startMs = 0L,
            endMs = 10_000L,
            sourceLanguage = "Japanese",
            targetLanguage = "English",
            events = listOf(testEvent("c1", 1_000L, 4_000L, "こんにちは", "Hello everyone")),
            speakers = project.speakers,
            referenceTrackId = frenchRef.trackId,
        )
        val outcomeA = reviewer.reviewDraft(draftA, frenchRef, layout, project)
        assertEquals(DialogueReviewStatus.FALLBACK_UNVERIFIED, outcomeA.dialogueStatus)
        assertFalse(outcomeA.isIndependentlyVerified)
        assertEquals("Hello everyone", outcomeA.repairedEvents.first().translatedText)

        // Case B: Japanese source reference repairs sourceText but MUST NOT verify English translation
        val jaRef = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "yt_ja",
            languageCode = "ja",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            cues = listOf(ReferenceCaptionCue("r1", 1_000L, 4_000L, "おはようございます")),
        )
        val outcomeB = reviewer.reviewDraft(
            draftA.copy(intervalId = "iv_ja", referenceTrackId = jaRef.trackId),
            jaRef,
            layout,
            project,
        )
        assertEquals("おはようございます", outcomeB.repairedEvents.first().sourceText)
        assertEquals("Material source repair must invalidate obsolete English translation", "", outcomeB.repairedEvents.first().translatedText)
        assertTrue(outcomeB.repairedEvents.first().needsTranslation)
        assertEquals(2, outcomeB.repairedEvents.first().sourceTextRevision)
        assertFalse("Source-language repair cannot mark target translation verified", outcomeB.isTranslationVerified)
    }

    @Test
    fun bothTranslationBypassesPreventRawForeignReferenceFromFillingTranslatedText() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()

        // Bypass 1: Empty-draft recovery when reference is Japanese and target is English
        val projExplicit = testProject(sourceLang = "Japanese", targetLang = "English")
        val jaRef = ReferenceCaptionTrack(
            mediaId = projExplicit.id,
            trackId = "yt_ja",
            languageCode = "ja",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            cues = listOf(ReferenceCaptionCue("r1", 2_000L, 5_000L, "今すぐ出発しよう")),
        )
        val emptyDraft = DraftInterval(
            intervalId = "iv_empty_ja",
            mediaId = projExplicit.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "Japanese|English|NATURAL|BALANCED",
            startMs = 0L,
            endMs = 10_000L,
            sourceLanguage = "Japanese",
            targetLanguage = "English",
            events = emptyList(),
            speakers = projExplicit.speakers,
            referenceTrackId = jaRef.trackId,
            isConfirmedSilence = true,
        )
        val out1 = reviewer.reviewDraft(emptyDraft, jaRef, layout, projExplicit)
        assertEquals(1, out1.repairedEvents.size)
        val cue1 = out1.repairedEvents.first()
        assertEquals("今すぐ出発しよう", cue1.sourceText)
        assertEquals("Untranslated foreign reference must NEVER be copied into translatedText", "", cue1.translatedText)
        assertTrue("Confirmed Decision 1: recovered foreign cue must set needsTranslation=true", cue1.needsTranslation)
        assertFalse(out1.isIndependentlyVerified)
        assertFalse(out1.isTranslationVerified)

        // Bypass 2: Auto-Detect sourceLanguage with Japanese reference and English target
        val projAuto = testProject(sourceLang = "Auto-Detect", targetLang = "English")
        val autoDraft = emptyDraft.copy(
            intervalId = "iv_auto_ja",
            sourceLanguage = "Auto-Detect",
            configFingerprint = "Auto-Detect|English|NATURAL|BALANCED",
        )
        val out2 = reviewer.reviewDraft(autoDraft, jaRef, layout, projAuto)
        assertEquals(1, out2.repairedEvents.size)
        val cue2 = out2.repairedEvents.first()
        assertEquals("今すぐ出発しよう", cue2.sourceText)
        assertEquals("Auto-Detect must NOT bypass foreign-reference translation guard", "", cue2.translatedText)
        assertTrue(cue2.needsTranslation)
        assertFalse(out2.isIndependentlyVerified)
    }

    @Test
    fun releaseStoreEnforcesExactStagedDraftMonotonicRevisionAndSourceRevision() {
        val store = ReleasedTimelineStore()
        val project = testProject()
        val ref = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "yt_en",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 2L,
            cues = listOf(ReferenceCaptionCue("r1", 1_000L, 4_000L, "Hello world")),
        )
        store.resetSession(project.id, sessionToken = 1L, seekGeneration = 0L, configFingerprint = "fp1")
        store.updateReferenceTrack(ref, sessionToken = 1L)

        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()

        // Commit draftRevision 2 first
        val draftV2 = DraftInterval(
            intervalId = "iv_0",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 2L,
            draftRevision = 2L,
            configFingerprint = "fp1",
            startMs = 0L,
            endMs = 10_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(testEvent("c_v2", 1_000L, 4_000L, "Hello world")),
            speakers = project.speakers,
            referenceTrackId = ref.trackId,
        )
        assertTrue(store.stageDraft(draftV2))
        val outcomeV2 = reviewer.reviewDraft(draftV2, ref, layout, project)
        assertNotNull(store.commitReviewedRelease(draftV2, outcomeV2))

        // Older draftRevision 1 MUST be rejected on stageDraft AND commitReviewedRelease
        val olderDraftV1 = draftV2.copy(
            draftRevision = 1L,
            events = listOf(testEvent("c_v1", 1_000L, 4_000L, "Stale older draft")),
        )
        assertFalse("Older draftRevision must be rejected at stageDraft", store.stageDraft(olderDraftV1))
        assertNull(
            "Older draftRevision must be rejected at commitReviewedRelease",
            store.commitReviewedRelease(olderDraftV1, outcomeV2.copy(draftRevision = 1L)),
        )

        // Mismatched sourceRevision MUST be rejected
        val wrongSourceRev = draftV2.copy(draftRevision = 3L, sourceRevision = 99L)
        assertTrue(store.stageDraft(wrongSourceRev))
        assertNull(
            "Mismatched sourceRevision must be rejected on commit",
            store.commitReviewedRelease(wrongSourceRev, outcomeV2.copy(draftRevision = 3L, sourceRevision = 99L)),
        )
        assertEquals("Hello world", store.snapshot().events.first().sourceText)
    }

    @Test
    fun sentinelCoordinatorMatchesExactTargetIntervalSupersedesOlderRevisionsAndCancelsCloudOnZeroCloud() {
        val coord = SentinelWorkforceCoordinator()
        coord.resetForProject(testProject(id = "m1"))

        coord.enqueueScout2Review("iv_A", 0L, 10_000L, playheadMs = 0L, nowEpochMs = 1_000L, draftRevision = 1L)
        coord.enqueueScout2Review("iv_B", 10_000L, 20_000L, playheadMs = 0L, nowEpochMs = 1_010L, draftRevision = 1L)
        // Newer revision for iv_A supersedes revision 1
        coord.enqueueScout2Review("iv_A", 0L, 10_000L, playheadMs = 0L, nowEpochMs = 1_020L, draftRevision = 2L)

        // Claim specifically iv_B even though iv_A is earlier in queue
        val claimedB = coord.claimRequiredReviewWork(
            workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
            playheadMs = 0L,
            nowEpochMs = 1_030L,
            targetIntervalId = "iv_B",
        )
        assertNotNull(claimedB)
        assertEquals("iv_B", claimedB!!.first.beaconId)
        coord.markWorkerIdle(SentinelWorkforceCoordinator.WORKER_2_ID, claimedB.second.reservationId, repairCompleted = true)

        // Claim iv_A and confirm we get revision 2 (revision 1 was superseded)
        val claimedA = coord.claimRequiredReviewWork(
            workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
            playheadMs = 0L,
            nowEpochMs = 1_040L,
            targetIntervalId = "iv_A",
        )
        assertNotNull(claimedA)
        assertEquals(2L, claimedA!!.first.draftRevision)
        coord.markWorkerIdle(SentinelWorkforceCoordinator.WORKER_2_ID, claimedA.second.reservationId, repairCompleted = true)

        // Switching to ZERO_CLOUD cancels queued cloud work and prevents new cloud dispatch
        coord.updateRuntimeConditions(quotaMode = QuotaSaverMode.QUOTA_SAVER)
        coord.enqueue(
            com.example.domain.sentinel.SentinelWorkItem(
                id = "cloud_patrol_1",
                tier = com.example.domain.sentinel.SentinelPriorityTier.PREVENTIVE_MAINTENANCE,
                kind = com.example.domain.sentinel.SentinelTaskKind.CHAPTER_BOUNDARY_PATROL,
                startMs = 25_000L,
                endMs = 45_000L,
                chapterId = "ch_1",
                chapterTitle = "Chapter seam",
                createdAtEpochMs = 1_050L,
            ),
        )
        val removed = coord.cancelQueuedCloudWork()
        assertTrue("cancelQueuedCloudWork must drop queued cloud pre-listen work", removed >= 1)
        coord.updateRuntimeConditions(quotaMode = QuotaSaverMode.ZERO_CLOUD)
        val cloudClaim = coord.claimNextWork(
            workerId = SentinelWorkforceCoordinator.WORKER_1_ID,
            nowEpochMs = 1_070L,
        )
        assertNull("ZERO_CLOUD must never dispatch cloud work items", cloudClaim)
    }

    @Test
    fun coverageLedgerEnforcesRetryBudgetSchedulesUploadedFileGapsAndRestoresSavedAiHonesty() {
        val ledger = TranscriptCoverageLedger()
        ledger.reset(durationMs = 90_000L)

        // 1. Retry budget must stop infinite FAILED / PARTIAL_RECOVERY / SUSPECTED_MISSING_DIALOGUE loops
        repeat(3) {
            ledger.recordOutcome(
                startMs = 0L,
                endMs = 20_000L,
                status = CoverageStatus.FAILED,
                source = SubtitleSourceKind.AI_DRAFT,
                dialogueCount = 0,
                failureReason = "err",
                incrementRetry = true,
            )
        }
        val pickedAfterExhaustion = ledger.nextPriorityInterval(playheadMs = 0L, maxRetryAttempts = 3)
        assertTrue(
            "Exhausted FAILED interval must not be re-picked past maxRetryAttempts",
            pickedAfterExhaustion == null || pickedAfterExhaustion.startMs >= 20_000L,
        )

        // 2. Uploaded subtitle file with >=15s uncovered gap marks CAPTION_GAP & schedules recovery (Decision 2)
        val uploadLedger = TranscriptCoverageLedger()
        uploadLedger.reset(durationMs = 60_000L)
        uploadLedger.markAuthoritativeTrack(
            events = listOf(
                testEvent("u1", 1_000L, 4_000L, "Opening imported line"),
                testEvent("u2", 42_000L, 46_000L, "Closing imported line"),
            ),
            source = SubtitleSourceKind.UPLOADED_FILE,
            durationMs = 60_000L,
        )
        val gapInterval = uploadLedger.nextPriorityInterval(playheadMs = 10_000L, maxRetryAttempts = 3)
        assertNotNull("Uploaded file >=15s gap must be scheduled for recovery", gapInterval)
        assertEquals(CoverageStatus.CAPTION_GAP, gapInterval!!.status)

        // 3. Saved AI project restore must NOT claim full verification on uncovered spans
        val savedLedger = TranscriptCoverageLedger()
        savedLedger.reset(durationMs = 120_000L)
        savedLedger.restoreFromSavedAiEvents(
            events = listOf(testEvent("ai1", 1_000L, 12_000L, "Saved first window")),
            durationMs = 120_000L,
        )
        val summary = savedLedger.summary()
        assertFalse("Incomplete saved AI events must not report isFullyVerified", summary.isFullyVerified)
        assertTrue(savedLedger.continuousReleasedLeadFrom(1_000L) >= 10_000L)
        assertEquals(CoverageStatus.PENDING, savedLedger.coverageStatusAt(60_000L))
    }

    @Test
    fun timeoutCancellationRetriesAndBisectsWhileGenuineCancellationPropagates() = runBlocking {
        val heartbeat = ScoutHeartbeatService(
            baseDelayMs = 1L,
            maxDelayMs = 5L,
            probeFlashLite = { Result.success(10L) },
        )

        // 1. ScoutHeartbeatService retries TimeoutCancellationException
        var attempts = 0
        val retryRes = heartbeat.executeWithBackoffRetry(maxRetries = 2) {
            attempts++
            if (attempts == 1) {
                withTimeout(1L) { delay(50L) }
            }
            Result.success("recovered")
        }
        assertTrue("TimeoutCancellationException must trigger retry and succeed", retryRes.isSuccess)
        assertEquals("recovered", retryRes.getOrNull())
        assertEquals(2, attempts)

        // 2. Genuine CancellationException MUST still propagate
        var propagated = false
        try {
            heartbeat.executeWithBackoffRetry<String>(maxRetries = 2) {
                throw CancellationException("parent cancelled")
            }
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue("Genuine parent CancellationException must propagate", propagated)

        // 3. ResilientYouTubeSegmentExecutor handles TimeoutCancellationException via bisection
        val executor = ResilientYouTubeSegmentExecutor(heartbeat)
        val outcome = executor.executeSegmentDetailed(
            startSec = 0,
            endSec = 40,
            initialTier = ScoutExecutionTier.LITE_AI,
        ) { winStart, winEnd, _, _ ->
            if (winEnd - winStart >= 30) {
                withTimeout(1L) { delay(50L) }
            }
            Result.success(
                AiYouTubeTranscriptionResult(
                    cues = listOf(
                        AiTranscribedCue(
                            startMs = winStart * 1_000L + 500L,
                            endMs = winStart * 1_000L + 3_500L,
                            sourceText = "Line $winStart",
                            naturalTranslation = "Line $winStart",
                            literalTranslation = "Line $winStart",
                            romanization = "",
                            dialogueType = DialogueType.SPOKEN,
                            suggestedSpeakerSlot = "SPK_1",
                            contextReason = "spoken",
                            culturalNote = "",
                        ),
                    ),
                ),
            )
        }
        assertTrue("Outcome should be Completed", outcome is SegmentExecutionOutcome.Completed)
        val completed = outcome as SegmentExecutionOutcome.Completed
        assertTrue("Timeout on 40s window must trigger bisection instead of aborting", completed.usedBisection)
        assertTrue("Bisected sub-windows must recover cues", completed.cues.isNotEmpty())
    }

    @Test
    fun audioTranscriptionPayloadRejectsOutOfBoundsOffsets() {
        val raw = """
            {
              "cues": [
                {"startOffsetSec": 1.0, "endOffsetSec": 4.0, "sourceText": "Valid line", "naturalTranslation": "Valid line"},
                {"startOffsetSec": 70.0, "endOffsetSec": 74.0, "sourceText": "Hallucinated out of bounds", "naturalTranslation": "Bad"}
              ]
            }
        """.trimIndent()
        val parsed = TranslationAiService.parseAudioTranscriptionPayload(
            rawJson = raw,
            chunkStartMs = 0L,
            chunkDurationMs = 45_000L,
        )
        assertEquals(1, parsed.cues.size)
        assertEquals("Valid line", parsed.cues.first().sourceText)
    }

    @Test
    fun speakerCorrectionNeverRelabelsUnrelatedNullIdCues() {
        val engine = SpeakerAttributionEngine()
        val events = listOf(
            testEvent("e1", 1_000L, 3_000L, "Line one", speakerId = null, faceTrackId = null),
            testEvent("e2", 4_000L, 6_000L, "Unrelated line two", speakerId = null, faceTrackId = null),
            testEvent("e3", 7_000L, 9_000L, "Known speaker line", speakerId = "spk_1", faceTrackId = "face_1"),
        )
        val updatedProject = engine.applyUserCorrection(
            project = testProject(events = events),
            eventId = "e1",
            newSpeakerId = "spk_2",
        )
        assertEquals("spk_2", updatedProject.dialogueEvents.first { it.id == "e1" }.speakerId)
        assertNull(
            "Null speakerId & null faceTrackId on e1 must NEVER relabel unrelated e2",
            updatedProject.dialogueEvents.first { it.id == "e2" }.speakerId,
        )
        assertEquals("spk_1", updatedProject.dialogueEvents.first { it.id == "e3" }.speakerId)
    }

    @Test
    fun balloonLayoutStabilityAcrossModesDetectorFrameDropoutAndNeutralFallback() {
        val engine = BalloonLayoutEngine()
        val baseProject = testProject()
        val evt = testEvent("c1", 1_000L, 9_000L, "Hold steady please", faceTrackId = "face_7")
        val proj = baseProject.copy(dialogueEvents = listOf(evt))

        val face = LiveFaceObservation(
            trackingId = 7,
            boundingBox = NormalizedRect(0.24f, 0.28f, 0.42f, 0.56f),
            mouthPoint = NormalizedPoint(0.33f, 0.48f),
            headYawDeg = 0f,
            capturedAtMs = 1_500L,
        )

        // 1. Distinct detectorFrameId dropout grace: 30fps ticks with same detectorFrameId do NOT expire grace
        val firstPlaced = engine.computeLayout(
            project = proj,
            currentMs = 1_500L,
            liveFaces = listOf(face),
            hasLiveVisionSignal = true,
            detectorFrameId = 1L,
        ).first()
        var lastRect = firstPlaced.rect
        repeat(10) { tick ->
            val placed = engine.computeLayout(
                project = proj,
                currentMs = 1_533L + tick * 33L,
                liveFaces = emptyList(),
                hasLiveVisionSignal = true,
                liveFrameObservedEmpty = true,
                detectorFrameId = 1L, // same detector observation!
            ).first()
            assertTrue(
                "Repeated 30fps ticks with same detectorFrameId must not prematurely jump bubble",
                abs(placed.rect.left - lastRect.left) < 0.01f && abs(placed.rect.top - lastRect.top) < 0.01f,
            )
            lastRect = placed.rect
        }

        // 2. Fixed Zones and Reading Comfort (stablePositioning) stay committed without oscillation under jittery faces
        val modeConfigs = listOf(
            "FixedZones" to Pair(true, BalloonStyleSettings()),
            "ReadingComfort" to Pair(false, BalloonStyleSettings(stablePositioning = true)),
        )
        for ((label, cfg) in modeConfigs) {
            val modeEngine = BalloonLayoutEngine()
            val (fixedZones, style) = cfg
            val p1 = modeEngine.computeLayout(
                project = proj,
                currentMs = 2_000L,
                styleSettings = style,
                liveFaces = listOf(face),
                hasLiveVisionSignal = true,
                fixedZoneTaillessMode = fixedZones,
                detectorFrameId = 10L,
            ).first()
            val p2 = modeEngine.computeLayout(
                project = proj,
                currentMs = 2_250L,
                styleSettings = style,
                liveFaces = listOf(
                    face.copy(
                        boundingBox = NormalizedRect(0.25f, 0.29f, 0.43f, 0.57f),
                        mouthPoint = NormalizedPoint(0.34f, 0.49f),
                    ),
                ),
                hasLiveVisionSignal = true,
                fixedZoneTaillessMode = fixedZones,
                detectorFrameId = 11L,
            ).first()
            assertTrue(
                "$label must remain stable under minor face jitter",
                abs(p2.rect.left - p1.rect.left) < 0.02f && abs(p2.rect.top - p1.rect.top) < 0.02f,
            )
        }

        // 3. NEUTRAL_FALLBACK must NEVER re-attach to faceTrackId or anchorPosition
        val fbEngine = BalloonLayoutEngine()
        val fallbackEvt = evt.copy(
            id = "c_fb",
            stableCueKey = "stable_fb",
            placementMode = PlacementMode.NEUTRAL_FALLBACK,
            faceTrackId = "face_7",
            anchorPosition = NormalizedPoint(0.33f, 0.48f),
        )
        val placedFb = fbEngine.computeLayout(
            project = baseProject.copy(dialogueEvents = listOf(fallbackEvt)),
            currentMs = 2_000L,
            liveFaces = listOf(face),
            hasLiveVisionSignal = true,
            detectorFrameId = 20L,
        ).first()
        assertEquals(PlacementMode.NEUTRAL_FALLBACK, placedFb.placementMode)
        assertNull("NEUTRAL_FALLBACK must not emit a face-attached tailTarget", placedFb.tailTarget)

        // 4. VisualStabilityDiagnostics detects gradual multi-frame A -> B -> A reversal
        val diag = VisualStabilityDiagnostics()
        val frames = listOf(100f, 120f, 142f, 166f, 144f, 122f, 102f)
        frames.forEach { leftPx ->
            diag.recordRenderedFrame(
                cueKey = "gradual_cue",
                renderedLeftPx = leftPx,
                renderedTopPx = 80f,
                widthPx = 180f,
                heightPx = 70f,
                containerWidthPx = 640f,
                containerHeightPx = 360f,
            )
        }
        assertTrue(
            "Gradual multi-frame A->B->A reversal must be detected by VisualStabilityDiagnostics",
            diag.snapshot().renderedOscillations >= 1,
        )
    }

    @Test
    fun realPlaybackReadinessControllerStartsOnReleasedFallbackAndRespectsUserPause() {
        val ledger = TranscriptCoverageLedger()
        ledger.reset(durationMs = 60_000L)
        ledger.recordOutcome(0L, 15_000L, CoverageStatus.RELEASED_FALLBACK, SubtitleSourceKind.AI_DRAFT, 2)

        val readiness = PlaybackReadinessController()
        readiness.resetForMedia(
            initialPositionMs = 0L,
            durationMs = 60_000L,
            requiresCoverageGate = true,
            initialIntent = UserPlaybackIntent.PAUSE,
        )

        val playSnap = readiness.onUserPlayRequested(positionMs = 0L, ledger = ledger, nowEpochMs = 1_000L)
        assertEquals(
            "PLAY must start cleanly on RELEASED_FALLBACK without infinite buffering hold",
            PlaybackReadinessState.PLAYING,
            playSnap.state,
        )
        assertTrue(playSnap.isUsingUnverifiedFallback)

        val pauseSnap = readiness.onUserPauseRequested(positionMs = 3_000L)
        assertEquals(PlaybackReadinessState.PAUSED_BY_USER, pauseSnap.state)
        assertTrue(pauseSnap.isIntentionallyPausedByUser)
        assertNull("Intentional user pause must never keep a pending auto-resume", pauseSnap.pendingAutoResume)
    }
}
