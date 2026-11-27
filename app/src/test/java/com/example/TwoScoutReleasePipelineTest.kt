package com.example

import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CaptionDisplayMode
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
import com.example.data.model.VideoChapter
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.subtitle.RawSubtitleCue
import com.example.data.subtitle.SubtitleParser
import com.example.data.subtitle.YouTubeCaptionFetcher
import com.example.domain.analysis.HierarchicalContextEngine
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.pipeline.AttributionReviewStatus
import com.example.domain.pipeline.DialogueReviewStatus
import com.example.domain.pipeline.DraftInterval
import com.example.domain.pipeline.ReleasedTimelineStore
import com.example.domain.pipeline.Scout2IndependentReviewer
import com.example.domain.pipeline.YouTubeCapabilityReport
import com.example.domain.playback.PlaybackReadinessController
import com.example.domain.playback.UserPlaybackIntent
import com.example.domain.sentinel.SentinelTaskKind
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import com.example.ui.state.HumanReadableStatus
import com.example.ui.state.ScoutCaptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end regression suite for the Two-Scout architecture (Prepare -> Review & Repair -> Release)
 * covering all 12 Section 10 regression scenarios plus RzagPoxaolQ gap/echo invariants and
 * YouTube capability honesty.
 */
class TwoScoutReleasePipelineTest {

    private fun baseProject(
        id: String = "proj_rzag",
        sourceLang: String = "English",
        targetLang: String = "English",
        durationMs: Long = 1_095_000L,
        speakers: List<Speaker> = listOf(
            Speaker(id = "spk_a", defaultLabel = "Host", accentIndex = 0, preferredScreenSideX = 0.28f),
            Speaker(id = "spk_b", defaultLabel = "Guest", accentIndex = 1, preferredScreenSideX = 0.72f),
        ),
        chapters: List<VideoChapter> = listOf(
            VideoChapter("ch_1", "Intro", 0L, 120_000L),
            VideoChapter("ch_2", "Policy Debate", 120_000L, 420_000L),
        ),
        events: List<DialogueEvent> = emptyList(),
    ): DialogueLensProject = DialogueLensProject(
        id = id,
        title = "RzagPoxaolQ Test",
        createdAtEpochMs = 1_000L,
        sourceLanguage = sourceLang,
        targetLanguage = targetLang,
        subtitleSource = SubtitleSourceKind.AI_DRAFT,
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
        speakers = speakers,
        faceTracks = emptyList(),
        dialogueEvents = events,
    )

    private fun event(
        id: String,
        startMs: Long,
        endMs: Long,
        text: String,
        translated: String = text,
        speakerId: String? = "spk_a",
        speakerConfidence: Float = 0.88f,
        anchor: NormalizedPoint? = NormalizedPoint(0.28f, 0.45f),
        placementMode: PlacementMode = PlacementMode.AUTO,
        stableKey: String = id,
    ): DialogueEvent = DialogueEvent(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        sourceText = text,
        translatedText = translated,
        speakerId = speakerId,
        speakerConfidence = speakerConfidence,
        faceTrackId = null,
        faceConfidence = if (anchor != null) 0.8f else 0f,
        shotId = "shot_1",
        dialogueType = DialogueType.SPOKEN,
        anchorPosition = anchor,
        placementMode = placementMode,
        stableCueKey = stableKey,
    )

    // -------------------------------------------------------------------------
    // Scenario 1: Native captions arrive late in Best Model mode
    // -------------------------------------------------------------------------
    @Test
    fun scenario01_lateNativeCaptionsPreservedAsImmutableReferenceWithoutReplacingVisibleTimeline() {
        val store = ReleasedTimelineStore()
        val existingVisible = listOf(
            event("ai_1", 10_000L, 14_000L, "Scout 1 dialogue already on screen", speakerId = "spk_a"),
        )
        store.resetSession(
            mediaId = "proj_rzag",
            sessionToken = 1L,
            seekGeneration = 0L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            initialEvents = existingVisible,
        )

        // Simulate late native captions arriving in Best Model mode
        val rawNativeCues = listOf(
            RawSubtitleCue(startTimeMs = 10_120L, endTimeMs = 13_410L, text = "Original authored caption text"),
            RawSubtitleCue(startTimeMs = 14_000L, endTimeMs = 17_200L, text = "Second original caption line"),
        )
        val refTrack = YouTubeCaptionFetcher.buildReferenceTrackFromObservedTimedText(
            mediaId = "proj_rzag",
            observedUrl = "https://www.youtube.com/api/timedtext?v=RzagPoxaolQ&lang=en",
            cues = rawNativeCues,
        )
        // Best Model mode must NOT allow passive WebView timedtext override
        assertFalse(
            LookaheadWindowPlanner.shouldAllowPassiveWebTimedTextOverride(
                quotaSaverMode = QuotaSaverMode.PRO_ACCURACY,
                quotaCircuitBreakerTripped = false,
                subtitleSource = SubtitleSourceKind.AI_DRAFT,
                events = existingVisible,
            )
        )

        // Yet native captions ARE stored as immutable reference evidence
        assertNotNull(refTrack)
        assertTrue(store.updateReferenceTrack(refTrack!!, sessionToken = 1L))
        val storedRef = store.currentReferenceTrack()
        assertNotNull(storedRef)
        assertEquals(CaptionOriginKind.AUTHORED, storedRef!!.originKind)
        assertEquals(10_120L, storedRef.cues.first().startMs)
        assertEquals(13_410L, storedRef.cues.first().endMs)

        // Visible released timeline must NOT be replaced automatically
        val snap = store.snapshot()
        assertEquals(1, snap.events.size)
        assertEquals("Scout 1 dialogue already on screen", snap.events.first().translatedText)

        // Reading-speed adjustments on editable events never mutate reference timestamps
        val paced = SubtitleParser.applyReadingPacingGuardrails(
            rawNativeCues.map { cue ->
                event("edit_${cue.startTimeMs}", cue.startTimeMs, cue.endTimeMs, cue.text)
            }
        )
        assertTrue(paced.isNotEmpty())
        assertEquals(10_120L, store.currentReferenceTrack()!!.cues.first().startMs)
        assertEquals(13_410L, store.currentReferenceTrack()!!.cues.first().endMs)
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Missing dialogue in an interval (including RzagPoxaolQ 05:15-05:55 gap)
    // -------------------------------------------------------------------------
    @Test
    fun scenario02_missingDialogueDetectedAndRepairedOrHeldUnverifiedNeverFakeSilence() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = baseProject()
        val gapStart = 315_000L // 05:15.000
        val gapEnd = 355_000L   // 05:55.000

        // Case A: Empty Scout 1 draft with NO reference and NO confirmed silence -> GAP_UNRESOLVED, never VERIFIED_SILENCE
        val unconfirmedEmptyDraft = DraftInterval(
            intervalId = "win_315_355",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = gapStart,
            endMs = gapEnd,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = emptyList(),
            speakers = project.speakers,
            isConfirmedSilence = false,
            hasIndependentAudioOrFrameEvidence = false,
        )
        val emptyOutcome = reviewer.reviewDraft(unconfirmedEmptyDraft, null, layout, project)
        assertEquals(DialogueReviewStatus.GAP_UNRESOLVED, emptyOutcome.dialogueStatus)
        assertEquals(CoverageStatus.SUSPECTED_MISSING_DIALOGUE, emptyOutcome.coverageStatus)
        assertFalse(emptyOutcome.isIndependentlyVerified)
        assertTrue(emptyOutcome.repairedEvents.isEmpty())

        // Case B: Reference captions contain spoken dialogue inside 05:15-05:55 -> Scout 2 recovers it!
        val refTrack = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "yt_en_authored",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            provenanceLabel = "test_authored",
            cues = listOf(
                ReferenceCaptionCue("ref_1", 320_000L, 326_000L, "Here is the dialogue spoken inside the gap."),
            ),
        )
        val repairedOutcome = reviewer.reviewDraft(unconfirmedEmptyDraft, refTrack, layout, project)
        assertEquals(DialogueReviewStatus.REPAIRED_FROM_REFERENCE, repairedOutcome.dialogueStatus)
        assertEquals(1, repairedOutcome.repairedEvents.size)
        assertEquals("Here is the dialogue spoken inside the gap.", repairedOutcome.repairedEvents.first().sourceText)
        assertEquals(CoverageStatus.VERIFIED_DIALOGUE, repairedOutcome.coverageStatus)
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Real silence or non-speech interval
    // -------------------------------------------------------------------------
    @Test
    fun scenario03_realSilenceConfirmedWithAudioEvidenceInventsNoDialogue() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = baseProject()
        val silenceDraft = DraftInterval(
            intervalId = "win_silence",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = 60_000L,
            endMs = 90_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = emptyList(),
            speakers = project.speakers,
            isConfirmedSilence = true,
            hasIndependentAudioOrFrameEvidence = true,
        )
        val outcome = reviewer.reviewDraft(silenceDraft, null, layout, project)
        assertEquals(DialogueReviewStatus.CONFIRMED_SILENCE, outcome.dialogueStatus)
        assertEquals(CoverageStatus.VERIFIED_SILENCE, outcome.coverageStatus)
        assertTrue(outcome.isIndependentlyVerified)
        assertTrue("Real silence must never invent dialogue", outcome.repairedEvents.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Wrong or drifted words (including translation language preservation!)
    // -------------------------------------------------------------------------
    @Test
    fun scenario04_wrongWordsRepairedAndTranslationNeverOverwrittenBySourceLanguageReference() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()

        // 4A. Same-language authored reference fixes materially wrong draft text
        val enProject = baseProject(sourceLang = "English", targetLang = "English")
        val authoredEnRef = ReferenceCaptionTrack(
            mediaId = enProject.id,
            trackId = "en_authored",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            provenanceLabel = "authored",
            cues = listOf(
                ReferenceCaptionCue("r1", 5_000L, 9_000L, "Childcare funding passed the council vote today"),
            ),
        )
        val driftedDraft = DraftInterval(
            intervalId = "win_0_40",
            mediaId = enProject.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = 0L,
            endMs = 40_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(
                event("e1", 5_000L, 9_000L, "Completely hallucinated unrelated sentence about mars"),
            ),
            speakers = enProject.speakers,
        )
        val repairedEn = reviewer.reviewDraft(driftedDraft, authoredEnRef, layout, enProject)
        assertEquals(DialogueReviewStatus.REPAIRED_FROM_REFERENCE, repairedEn.dialogueStatus)
        assertEquals("Childcare funding passed the council vote today", repairedEn.repairedEvents.first().translatedText)

        // 4B. Translation mode (Japanese -> English) with Japanese reference:
        // Scout 2 fixes sourceText ONLY and NEVER overwrites English translatedText with Japanese!
        val jaToEnProject = baseProject(sourceLang = "Japanese", targetLang = "English")
        val authoredJaRef = ReferenceCaptionTrack(
            mediaId = jaToEnProject.id,
            trackId = "ja_authored",
            languageCode = "ja",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            provenanceLabel = "authored_ja",
            cues = listOf(
                ReferenceCaptionCue("rj1", 5_000L, 9_000L, "今日は新しい計画を発表します"),
            ),
        )
        val translatedDraft = DraftInterval(
            intervalId = "win_ja_en",
            mediaId = jaToEnProject.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "Japanese|English|NATURAL|PRO_ACCURACY",
            startMs = 0L,
            endMs = 40_000L,
            sourceLanguage = "Japanese",
            targetLanguage = "English",
            events = listOf(
                event(
                    id = "ej1",
                    startMs = 5_000L,
                    endMs = 9_000L,
                    text = "誤認識されたテキスト",
                    translated = "Today we are announcing a new plan.",
                ),
            ),
            speakers = jaToEnProject.speakers,
        )
        val repairedTranslation = reviewer.reviewDraft(translatedDraft, authoredJaRef, layout, jaToEnProject)
        val outEvt = repairedTranslation.repairedEvents.first()
        assertEquals("今日は新しい計画を発表します", outEvt.sourceText)
        assertFalse(
            "Translated display text must NEVER be overwritten with source-language reference text",
            outEvt.translatedText.contains("今日"),
        )
        assertEquals(
            "Material source repair must invalidate stale English translation while re-translation is pending",
            "",
            outEvt.translatedText,
        )
        assertTrue(outEvt.needsTranslation)
        assertEquals(2, outEvt.sourceTextRevision)
        assertFalse(repairedTranslation.isTranslationVerified)
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Uncertain speaker with a visible listener face
    // -------------------------------------------------------------------------
    @Test
    fun scenario05_uncertainSpeakerNeverAttachesToVisibleListenerFace() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = baseProject()
        val listenerFace = LiveFaceObservation(
            trackingId = 99,
            boundingBox = NormalizedRect(0.55f, 0.18f, 0.80f, 0.56f),
            mouthPoint = NormalizedPoint(0.675f, 0.44f),
            headYawDeg = 0f,
            capturedAtMs = 14_000L,
        )
        val uncertainCue = event(
            id = "e_uncertain",
            startMs = 12_000L,
            endMs = 16_000L,
            text = "Off-camera narrator speaking over a reaction shot.",
            speakerId = "spk_b",
            speakerConfidence = 0.32f,
            anchor = NormalizedPoint(0.675f, 0.44f),
            placementMode = PlacementMode.AUTO,
        )
        val draft = DraftInterval(
            intervalId = "win_uncertain",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = 10_000L,
            endMs = 20_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(uncertainCue),
            speakers = project.speakers,
            hasIndependentAudioOrFrameEvidence = true,
        )
        val outcome = reviewer.reviewDraft(
            draft = draft,
            referenceTrack = null,
            layoutEngine = layout,
            projectTemplate = project,
            liveFaces = listOf(listenerFace),
        )
        assertEquals(AttributionReviewStatus.NEUTRAL_UNCERTAIN, outcome.attributionStatus)
        val outEvt = outcome.repairedEvents.first()
        assertEquals(PlacementMode.NEUTRAL_FALLBACK, outEvt.placementMode)
        assertNull("Uncertain cue must not anchor to visible listener face", outEvt.anchorPosition)
        assertEquals("Off-camera narrator speaking over a reaction shot.", outEvt.translatedText)

        // Verify BalloonLayoutEngine also refuses to attach this neutral cue to the visible listener face
        val placed = layout.computeLayout(
            project = project.copy(dialogueEvents = listOf(outEvt)),
            currentMs = 14_000L,
            styleSettings = BalloonStyleSettings(),
            liveFaces = listOf(listenerFace),
            hasLiveVisionSignal = true,
        )
        assertEquals(1, placed.size)
        assertNull("Neutral uncertain cue must NOT draw a tail to a visible listener face", placed.first().tailTarget)
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Rolling or progressive captions preserve cue identity
    // -------------------------------------------------------------------------
    @Test
    fun scenario06_rollingProgressiveCaptionsPreservePersistentCueIdentity() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = baseProject()
        val rollingEvents = listOf(
            event("roll_1", 1_000L, 2_200L, "We need to fix", stableKey = "stable_roll_1"),
            event("roll_2", 2_250L, 3_600L, "We need to fix the potholes downtown", stableKey = "roll_2"),
            event("roll_3", 3_650L, 4_500L, "We need to fix the potholes downtown", stableKey = "roll_3"),
        )
        val draft = DraftInterval(
            intervalId = "win_rolling",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = 0L,
            endMs = 10_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = rollingEvents,
            speakers = project.speakers,
            hasIndependentAudioOrFrameEvidence = true,
        )
        val outcome = reviewer.reviewDraft(draft, null, layout, project)
        assertEquals(1, outcome.repairedEvents.size)
        val merged = outcome.repairedEvents.first()
        assertEquals("roll_1", merged.id)
        assertEquals("stable_roll_1", merged.stableCueKey)
        assertEquals(1_000L, merged.startTimeMs)
        assertEquals(4_500L, merged.endTimeMs)
        assertEquals("We need to fix the potholes downtown", merged.translatedText)
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Chapter marker without a visual cut
    // -------------------------------------------------------------------------
    @Test
    fun scenario07_chapterMarkerAloneDoesNotResetSceneEpochOrClearSpeakerContinuity() {
        val contextEngine = HierarchicalContextEngine()
        val project = baseProject(
            chapters = listOf(
                VideoChapter("ch_1", "Part 1", 0L, 30_000L),
                VideoChapter("ch_2", "Part 2", 30_000L, 60_000L),
            ),
        )
        contextEngine.resetForProject(project)
        val beforeBoundary = contextEngine.onPlaybackPosition(project, 29_000L)
        val initialEpoch = beforeBoundary.sceneMemory.sceneEpoch
        assertEquals("ch_1", beforeBoundary.chapterMemory.activeChapter?.id)

        // Cross chapter marker at 30_000ms with NO visual cut
        val afterBoundary = contextEngine.onPlaybackPosition(project, 31_000L)
        assertEquals("ch_2", afterBoundary.chapterMemory.activeChapter?.id)
        assertEquals(
            "Chapter marker alone must NEVER increment sceneEpoch or simulate a visual cut",
            initialEpoch,
            afterBoundary.sceneMemory.sceneEpoch,
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Noisy face detections / tracking dropouts
    // -------------------------------------------------------------------------
    @Test
    fun scenario08_noisyFaceDetectionsAndTrackingDropoutsDoNotOscillateBalloon() {
        val layout = BalloonLayoutEngine()
        val cue = event("e_stable", 1_000L, 8_000L, "Steady dialogue during shaky face tracking")
        val project = baseProject(events = listOf(cue))
        val style = BalloonStyleSettings()

        // Alternating noisy face jitter & brief dropouts across 12 frames
        val xs = mutableListOf<Float>()
        for (i in 0 until 12) {
            val frameMs = 1_500L + i * 250L
            val faces = when (i % 3) {
                0 -> listOf(
                    LiveFaceObservation(1, NormalizedRect(0.20f, 0.25f, 0.36f, 0.52f), NormalizedPoint(0.28f, 0.44f), 0f, frameMs),
                )
                1 -> listOf(
                    LiveFaceObservation(1, NormalizedRect(0.23f, 0.26f, 0.39f, 0.53f), NormalizedPoint(0.31f, 0.45f), 2f, frameMs),
                )
                else -> emptyList() // brief single-frame tracking dropout
            }
            val placed = layout.computeLayout(
                project = project,
                currentMs = frameMs,
                styleSettings = style,
                liveFaces = faces,
                hasLiveVisionSignal = true,
                liveFrameObservedEmpty = faces.isEmpty(),
            )
            assertEquals(1, placed.size)
            xs.add(placed.first().rect.centerX)
        }
        val maxSwing = (xs.maxOrNull()!! - xs.minOrNull()!!)
        assertTrue("Balloon horizontal drift under noisy tracking must stay minimal (was $maxSwing)", maxSwing < 0.08f)
        val diag = layout.stabilityDiagnostics.snapshot()
        assertEquals("No A-B-A oscillations should commit during noisy tracking", 0, diag.totalRelocations)
    }

    // -------------------------------------------------------------------------
    // Scenario 9: Rapid seeking and configuration change reject stale async callbacks
    // -------------------------------------------------------------------------
    @Test
    fun scenario09_staleSessionSeekOrConfigCallbacksRejectedByReleaseGate() {
        val store = ReleasedTimelineStore()
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = baseProject()

        store.resetSession(
            mediaId = project.id,
            sessionToken = 10L,
            seekGeneration = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
        )
        val staleDraft = DraftInterval(
            intervalId = "win_stale",
            mediaId = project.id,
            sessionToken = 10L,
            seekGeneration = 1L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = 0L,
            endMs = 35_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(event("stale_1", 2_000L, 5_000L, "Stale seek result")),
            speakers = project.speakers,
            hasIndependentAudioOrFrameEvidence = true,
        )
        assertTrue(store.stageDraft(staleDraft))

        // User rapidly seeks & switches translation mode before Scout 2 finishes
        store.updateSeekOrConfigGeneration(
            seekGeneration = 2L,
            configFingerprint = "English|Spanish|LITERAL|PRO_ACCURACY",
        )

        val outcome = reviewer.reviewDraft(staleDraft, null, layout, project)
        val committed = store.commitReviewedRelease(staleDraft, outcome)
        assertNull("Stale seek/config callback must be rejected by Release Gate", committed)
        val snap = store.snapshot()
        assertTrue("Visible timeline must remain clean of stale results", snap.events.isEmpty())
        assertTrue("Stale rejection counter must increment", snap.staleCallbacksRejected >= 1)
    }

    // -------------------------------------------------------------------------
    // Scenario 10: Review deadline expiration degrades predictably without faking verification
    // -------------------------------------------------------------------------
    @Test
    fun scenario10_reviewDeadlineFallbackDegradesPredictablyAndNeverClaimsVerified() {
        val store = ReleasedTimelineStore()
        val project = baseProject()
        store.resetSession(
            mediaId = project.id,
            sessionToken = 5L,
            seekGeneration = 0L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
        )

        val overdueDraft = DraftInterval(
            intervalId = "win_deadline",
            mediaId = project.id,
            sessionToken = 5L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = 20_000L,
            endMs = 50_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(event("fb_1", 22_000L, 26_000L, "Playable draft text", speakerConfidence = 0.42f)),
            speakers = project.speakers,
            createdAtEpochMs = 1_000L,
            deadlineEpochMs = 5_000L,
        )
        assertTrue(store.stageDraft(overdueDraft))
        // Before deadline and before review, draft is NOT visible to playback!
        assertTrue("Unreviewed draft must not appear before release", store.snapshot().events.isEmpty())

        // Deadline expires -> degrades predictably
        val releasedFallbacks = store.applyDeadlineFallbackIfDue(nowEpochMs = 6_000L, playheadMs = 19_000L)
        assertEquals(1, releasedFallbacks.size)
        val fb = releasedFallbacks.first()
        assertTrue(fb.isPlayableFallback)
        assertFalse("Deadline fallback MUST NEVER set isIndependentlyVerified = true", fb.reviewOutcome.isIndependentlyVerified)
        assertEquals(CoverageStatus.RELEASED_FALLBACK, fb.reviewOutcome.coverageStatus)
        assertEquals(PlacementMode.NEUTRAL_FALLBACK, fb.events.first().placementMode)
        assertEquals(1, store.snapshot().fallbackReleaseCount)
    }

    // -------------------------------------------------------------------------
    // Scenario 11: Controlled correction of a materially wrong visible cue
    // -------------------------------------------------------------------------
    @Test
    fun scenario11_controlledVisibleCorrectionPreservesIdentityAndPreventsOscillation() {
        val store = ReleasedTimelineStore()
        val project = baseProject()
        val initialCue = event("vis_1", 10_000L, 15_000L, "Wrong heard word", stableKey = "stable_vis_1")
        store.resetSession(
            mediaId = project.id,
            sessionToken = 7L,
            initialEvents = listOf(initialCue),
        )

        val firstFix = store.applyControlledVisibleCorrection(
            mediaId = project.id,
            sessionToken = 7L,
            targetEventId = "vis_1",
            correctedText = "Verified corrected word",
            neutralizeAttribution = true,
            nowEpochMs = 10_000L,
        )
        assertNotNull(firstFix)
        val updatedCue = firstFix!!.events.single()
        assertEquals("vis_1", updatedCue.id)
        assertEquals("stable_vis_1", updatedCue.stableCueKey)
        assertEquals("Verified corrected word", updatedCue.translatedText)
        assertEquals(PlacementMode.NEUTRAL_FALLBACK, updatedCue.placementMode)

        // Rapid second rewrite inside cooldown window is suppressed to prevent oscillation
        val rapidSecondFix = store.applyControlledVisibleCorrection(
            mediaId = project.id,
            sessionToken = 7L,
            targetEventId = "vis_1",
            correctedText = "Oscillating rewrite",
            nowEpochMs = 11_000L,
        )
        assertNull("Rapid repeated rewrite within cooldown must be suppressed", rapidSecondFix)
        assertEquals("Verified corrected word", store.snapshot().events.single().translatedText)

        // After cooldown, removal of a confirmed hallucinated cue works atomically
        val removal = store.applyControlledVisibleCorrection(
            mediaId = project.id,
            sessionToken = 7L,
            targetEventId = "vis_1",
            removeHallucinatedCue = true,
            nowEpochMs = 15_000L,
        )
        assertNotNull(removal)
        assertTrue(removal!!.events.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Scenario 12: Viewport resize / orientation / font-scale change invalidates layout cache only
    // -------------------------------------------------------------------------
    @Test
    fun scenario12_viewportOrFontScaleChangeInvalidatesLayoutOnlyPreservingDialogue() {
        val store = ReleasedTimelineStore()
        val layout = BalloonLayoutEngine()
        val cue = event("keep_1", 2_000L, 6_000L, "Preserved dialogue across rotation")
        val project = baseProject(events = listOf(cue))
        store.resetSession(
            mediaId = project.id,
            sessionToken = 3L,
            initialEvents = listOf(cue),
            initialSpeakers = project.speakers,
        )
        val ref = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "ref_en",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            provenanceLabel = "test",
            cues = listOf(ReferenceCaptionCue("c1", 2_000L, 6_000L, "Preserved dialogue across rotation")),
        )
        assertTrue(store.updateReferenceTrack(ref, sessionToken = 3L))
        val revBefore = store.snapshot().layoutCacheRevision

        layout.invalidateLayoutCacheForViewportOrStyleChange()
        val snapAfter = store.invalidateLayoutCacheOnly()

        assertEquals(revBefore + 1L, snapAfter.layoutCacheRevision)
        assertEquals(1, snapAfter.events.size)
        assertEquals("Preserved dialogue across rotation", snapAfter.events.first().translatedText)
        assertNotNull("Reference track must survive viewport/font-scale invalidation", store.currentReferenceTrack())
    }

    // -------------------------------------------------------------------------
    // Dependent Scheduler, Adaptive Readiness Lead, RzagPoxaolQ Echo & Honest Capabilities
    // -------------------------------------------------------------------------
    @Test
    fun dependentStageSchedulerNeverSuspendsScout2ReviewAndSeparatesReleasedFromVerifiedLead() {
        val coordinator = SentinelWorkforceCoordinator()
        val project = baseProject()
        coordinator.resetForProject(project)
        // Even with ZERO_CLOUD + active buffering + 0ms lead (which suspends background patrol!),
        // Scout 2 required review MUST still be claimable!
        coordinator.updateRuntimeConditions(
            quotaMode = QuotaSaverMode.ZERO_CLOUD,
            isPlaybackBuffering = true,
            contiguousPlayheadLeadMs = 0L,
        )
        coordinator.enqueueScout2Review("win_far", 120_000L, 160_000L, playheadMs = 10_000L, nowEpochMs = 1_000L)
        coordinator.enqueueScout2Review("win_near", 15_000L, 45_000L, playheadMs = 10_000L, nowEpochMs = 1_000L)

        val claimed = coordinator.claimRequiredReviewWork(
            workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
            playheadMs = 10_000L,
            nowEpochMs = 1_100L,
        )
        assertNotNull("Scout 2 required review must NEVER be suspended when lead is short", claimed)
        assertEquals(SentinelTaskKind.SCOUT2_REQUIRED_REVIEW, claimed!!.first.kind)
        assertEquals("Nearest unresolved interval ahead of playhead must be claimed first", 15_000L, claimed.first.startMs)

        // Adaptive readiness controller separates released lead from verified lead and adapts to latency
        val ledger = TranscriptCoverageLedger(initialDurationMs = 120_000L)
        ledger.recordOutcome(0L, 30_000L, CoverageStatus.RELEASED_FALLBACK, SubtitleSourceKind.AI_DRAFT, dialogueCount = 5)
        val readiness = PlaybackReadinessController()
        readiness.resetForMedia(0L, 120_000L, requiresCoverageGate = true, initialIntent = UserPlaybackIntent.PLAY)
        val baseSnap = readiness.evaluateCoverage(ledger)
        assertEquals(30_000L, baseSnap.contiguousReleasedLeadMs)
        assertEquals(0L, baseSnap.contiguousVerifiedLeadMs)
        assertTrue(baseSnap.isUsingUnverifiedFallback)
        assertFalse("Playable released fallback should allow smooth playback without infinite buffering hold", baseSnap.isBufferingHoldActive)

        // Recording higher latency scales target lead upward
        readiness.recordProcessingLatencyMs(12_000L)
        readiness.recordProcessingLatencyMs(12_000L)
        val slowSnap = readiness.evaluateCoverage(ledger)
        assertTrue(
            "Measured processing latency should increase required/healthy lead target",
            slowSnap.diagnostics.measuredProcessingLatencyMs > 0L && slowSnap.diagnostics.healthyLeadTargetMs >= baseSnap.diagnostics.healthyLeadTargetMs,
        )

        // Calm viewer labels remain free of internal jargon
        val calmLabel = HumanReadableStatus.resolve(
            project = project,
            scoutState = ScoutCaptionState.Idle,
            coverageSummary = ledger.summary(),
            readinessSnapshot = baseSnap,
            releaseSnapshot = ReleasedTimelineStore().snapshot().copy(fallbackReleaseCount = 1),
        )
        assertEquals("Using fallback captions here", calmLabel.label)
        assertFalse(calmLabel.label.lowercase().contains("scout"))

        // RzagPoxaolQ 04:09 echo sequence filtered out when repeating 02:46 sequence
        val earlier = listOf(
            event("e_early_1", 166_800L, 173_000L, "Democratic politicians are debating Mamdani's accomplishments on childcare funding"),
            event("e_early_2", 180_300L, 188_000L, "We need real pothole repairs and universal childcare across the city"),
        )
        val echoedLater = listOf(
            event("e_echo_1", 249_500L, 255_000L, "Democratic politicians are debating Mamdani's accomplishments on childcare funding"),
            event("e_echo_2", 260_000L, 267_000L, "We need real pothole repairs and universal childcare across the city"),
        )
        val filtered = LookaheadWindowPlanner.filterEchoedOpeningCues(earlier, echoedLater, windowStartMs = 245_000L)
        assertTrue("04:09 window echo repeating 02:46 sequence must be filtered", filtered.isEmpty())

        // Honest YouTube capability report never claims raw audio or future frame access
        val ytCap = YouTubeCapabilityReport()
        assertFalse(ytCap.hasRawAudioAccess)
        assertFalse(ytCap.hasFutureFrameAccess)
        assertTrue(ytCap.hasEmbeddedPlayer)
    }
}
