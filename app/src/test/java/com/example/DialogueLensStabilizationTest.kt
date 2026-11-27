package com.example

import com.example.data.ai.AiTranslatedLine
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.ChapterSource
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.VideoChapter
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.attribution.SpeakerAttributionEngine
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.TailDirection
import com.example.domain.sentinel.SentinelPriorityTier
import com.example.domain.sentinel.SentinelTaskKind
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import com.example.ui.state.applyCutawayOffScreenSideMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class DialogueLensStabilizationTest {

    private fun testProject(
        durationMs: Long = 240_000L,
        speakers: List<Speaker> = listOf(
            Speaker(id = "spk_a", defaultLabel = "Speaker A", customName = "Host", accentIndex = 0, isIdentityConfirmed = true, preferredScreenSideX = 0.25f),
            Speaker(id = "spk_b", defaultLabel = "Speaker B", customName = "Guest", accentIndex = 1, isIdentityConfirmed = true, preferredScreenSideX = 0.75f),
        ),
        events: List<DialogueEvent> = emptyList(),
        chapters: List<VideoChapter> = emptyList(),
    ): DialogueLensProject = DialogueLensProject(
        id = "proj_stab",
        title = "Stabilization Verification",
        createdAtEpochMs = 1_000L,
        sourceLanguage = "en",
        targetLanguage = "en",
        videoSource = VideoSource(
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            uriString = "https://www.youtube.com/watch?v=RzagPoxaolQ",
            displayName = "Stabilization Verification",
            durationMs = durationMs,
            width = 1920,
            height = 1080,
            youtubeVideoId = "RzagPoxaolQ",
        ),
        shots = listOf(Shot("shot_1", 0L, durationMs)),
        speakers = speakers,
        faceTracks = emptyList(),
        dialogueEvents = events,
        chapters = chapters,
        styleSettings = BalloonStyleSettings(),
    )

    @Test
    fun balloonModeTracksMovingFaceWithGentleBoundedDriftAndNoEdgeYank() {
        val engine = BalloonLayoutEngine()
        val event = DialogueEvent(
            id = "evt_drift",
            startTimeMs = 1_000L,
            endTimeMs = 8_000L,
            sourceText = "Let us walk through the proposal step by step.",
            translatedText = "Let us walk through the proposal step by step.",
            speakerId = "spk_a",
            speakerConfidence = 0.92f,
            faceTrackId = null,
            faceConfidence = 0.85f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            anchorPosition = NormalizedPoint(0.28f, 0.42f),
            placementMode = PlacementMode.AUTO,
        )
        val project = testProject(events = listOf(event))

        // Initial placement at x=0.28
        var prev = engine.computeLayout(
            project = project,
            currentMs = 1_500L,
            styleSettings = project.styleSettings,
            liveFaces = listOf(
                LiveFaceObservation(
                    trackingId = 1,
                    boundingBox = NormalizedRect(0.20f, 0.28f, 0.36f, 0.56f),
                    mouthPoint = NormalizedPoint(0.28f, 0.48f),
                    headYawDeg = 0f,
                    capturedAtMs = 1_500L,
                )
            ),
            sceneEpoch = 1,
            hasLiveVisionSignal = true,
            fixedZoneTaillessMode = false,
        ).single()

        assertNotNull("Default Balloon mode should include pointer tail for on-screen speaker", prev.tailTarget)
        assertTrue(prev.tailDirection != TailDirection.NONE)

        // Move face smoothly across 8 steps from x=0.28 -> 0.52 and verify bounded per-step drift
        val faceCenters = listOf(0.31f, 0.34f, 0.37f, 0.40f, 0.43f, 0.46f, 0.49f, 0.52f)
        var maxStepJump = 0f
        var timeMs = 1_800L
        for (cx in faceCenters) {
            val next = engine.computeLayout(
                project = project,
                currentMs = timeMs,
                styleSettings = project.styleSettings,
                liveFaces = listOf(
                    LiveFaceObservation(
                        trackingId = 1,
                        boundingBox = NormalizedRect(cx - 0.08f, 0.28f, cx + 0.08f, 0.56f),
                        mouthPoint = NormalizedPoint(cx, 0.48f),
                        headYawDeg = 0f,
                        capturedAtMs = timeMs,
                    )
                ),
                sceneEpoch = 1,
                hasLiveVisionSignal = true,
                fixedZoneTaillessMode = false,
            ).single()

            val stepDist = hypot(
                (next.rect.centerX - prev.rect.centerX).toDouble(),
                (next.rect.centerY - prev.rect.centerY).toDouble(),
            ).toFloat()
            if (stepDist > maxStepJump) maxStepJump = stepDist
            prev = next
            timeMs += 250L
        }

        // Gentle drift clamp should prevent violent across-screen edge-yank snaps (> 0.14f)
        assertTrue(
            "Expected gentle per-step drift without violent edge yank, got maxStepJump=$maxStepJump",
            maxStepJump <= 0.14f,
        )
    }

    @Test
    fun legacyFixedZonesMigratesToBalloonsAndSharesBodyAnchorWithoutPointerTail() {
        assertEquals(4, CaptionDisplayMode.primaryModes.size)
        assertFalse(CaptionDisplayMode.FIXED_ZONES in CaptionDisplayMode.primaryModes)
        assertFalse(CaptionDisplayMode.BOTH in CaptionDisplayMode.primaryModes)
        assertEquals(CaptionDisplayMode.BALLOONS, CaptionDisplayMode.FIXED_ZONES.normalizedPrimaryMode())

        val engine = BalloonLayoutEngine()
        val evt1 = DialogueEvent(
            id = "evt_fz_1",
            startTimeMs = 1_000L,
            endTimeMs = 6_000L,
            sourceText = "First calm bubble line.",
            translatedText = "First calm bubble line.",
            speakerId = "spk_a",
            speakerConfidence = 0.95f,
            faceTrackId = null,
            faceConfidence = 0.90f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            anchorPosition = NormalizedPoint(0.25f, 0.40f),
            placementMode = PlacementMode.AUTO,
        )
        val project = testProject(events = listOf(evt1))

        val first = engine.computeLayout(
            project = project,
            currentMs = 1_500L,
            styleSettings = project.styleSettings,
            liveFaces = listOf(
                LiveFaceObservation(
                    trackingId = 1,
                    boundingBox = NormalizedRect(0.18f, 0.30f, 0.34f, 0.58f),
                    mouthPoint = NormalizedPoint(0.26f, 0.49f),
                    headYawDeg = 0f,
                    capturedAtMs = 1_500L,
                )
            ),
            sceneEpoch = 1,
            hasLiveVisionSignal = true,
            displayMode = CaptionDisplayMode.BALLOONS,
        ).single()

        assertNull("BALLOONS (tailless) mode must never attach a pointer tail", first.tailTarget)
        assertEquals(TailDirection.NONE, first.tailDirection)

        // Switching to BUBBLES_WITH_TAILS with the same face keeps the committed body anchor and adds a tail
        val second = engine.computeLayout(
            project = project,
            currentMs = 3_000L,
            styleSettings = project.styleSettings,
            liveFaces = listOf(
                LiveFaceObservation(
                    trackingId = 1,
                    boundingBox = NormalizedRect(0.21f, 0.29f, 0.37f, 0.57f),
                    mouthPoint = NormalizedPoint(0.29f, 0.48f),
                    headYawDeg = 6f,
                    capturedAtMs = 3_000L,
                )
            ),
            sceneEpoch = 1,
            hasLiveVisionSignal = true,
            displayMode = CaptionDisplayMode.BUBBLES_WITH_TAILS,
        ).single()

        assertNotNull("BUBBLES_WITH_TAILS must attach a pointer tail for a visible speaker", second.tailTarget)
        assertEquals(first.rect.left, second.rect.left, 0.001f)
        assertEquals(first.rect.top, second.rect.top, 0.001f)
    }

    @Test
    fun speakerAttributionFreezesSettledLinesAndResetsSpatialSideMemoryPerScene() {
        val engine = SpeakerAttributionEngine()
        val initialSpeakers = listOf(
            Speaker(id = "spk_a", defaultLabel = "Speaker A", customName = "Host", accentIndex = 0, isIdentityConfirmed = true, preferredScreenSideX = 0.22f),
            Speaker(id = "spk_b", defaultLabel = "Speaker B", customName = "Guest", accentIndex = 1, isIdentityConfirmed = true, preferredScreenSideX = 0.78f),
        )
        val shot = Shot("shot_1", 0L, 120_000L)
        val sceneBoundaries = listOf(60_000L)

        // Window 1: settled earlier line assigned to Host (spk_a) in Scene 1 (0..60s)
        val settledEvent = DialogueEvent(
            id = "evt_scene1_1",
            startTimeMs = 5_000L,
            endTimeMs = 9_000L,
            sourceText = "Welcome to tonight's discussion.",
            translatedText = "Welcome to tonight's discussion.",
            speakerId = "spk_a",
            speakerConfidence = 0.91f,
            faceTrackId = null,
            faceConfidence = 0.85f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            anchorPosition = NormalizedPoint(0.22f, 0.45f),
            attributionSummary = "AI vision + voice match",
        )

        // Window 2: later event in Scene 2 (after 60s chapter changeover) + conflicting hint for settledEvent
        val scene2Event = DialogueEvent(
            id = "evt_scene2_1",
            startTimeMs = 64_000L,
            endTimeMs = 68_000L,
            sourceText = "Now let's switch to our second topic.",
            translatedText = "Now let's switch to our second topic.",
            speakerId = null,
            speakerConfidence = 0.40f,
            faceTrackId = null,
            faceConfidence = 0.80f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            anchorPosition = NormalizedPoint(0.24f, 0.45f),
        )
        val scene2OffScreen = DialogueEvent(
            id = "evt_scene2_off",
            startTimeMs = 70_000L,
            endTimeMs = 73_000L,
            sourceText = "And one more follow-up point.",
            translatedText = "And one more follow-up point.",
            speakerId = "spk_a",
            speakerConfidence = 0.88f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            dialogueType = DialogueType.OFF_SCREEN_BEHIND,
            anchorPosition = null,
        )

        // Conflicting turn hint tries to flip the already-settled Scene 1 cue to spk_b
        val conflictingHints = mapOf(
            "evt_scene1_1" to AiTranslatedLine(
                id = "evt_scene1_1",
                naturalTranslation = settledEvent.translatedText,
                literalTranslation = settledEvent.translatedText,
                romanization = "",
                dialogueTypeHint = DialogueType.SPOKEN,
                speakerTurnHint = "CHANGE",
                suggestedSpeakerSlot = "B",
            ),
            "evt_scene2_1" to AiTranslatedLine(
                id = "evt_scene2_1",
                naturalTranslation = scene2Event.translatedText,
                literalTranslation = scene2Event.translatedText,
                romanization = "",
                dialogueTypeHint = DialogueType.SPOKEN,
                speakerTurnHint = "UNCERTAIN",
                suggestedSpeakerSlot = "B",
            ),
        )

        val (updatedSpeakers, attributed) = engine.assignSpeakers(
            events = listOf(settledEvent, scene2Event),
            shots = listOf(shot),
            faceTracks = emptyList(),
            existingSpeakers = initialSpeakers,
            turnHints = conflictingHints,
            settledEventIds = setOf("evt_scene1_1"),
            sceneBoundaryMs = sceneBoundaries,
        )

        val outScene1 = attributed.first { it.id == "evt_scene1_1" }
        val outScene2 = attributed.first { it.id == "evt_scene2_1" }

        // 1. Settled earlier line MUST remain frozen on spk_a despite conflicting B hint
        assertEquals("spk_a", outScene1.speakerId)
        assertEquals("AI vision + voice match", outScene1.attributionSummary)

        // 2. Scene 2 cue cleanly accepts spk_b even though anchor X=0.24 was spk_a's Scene 1 seat
        assertEquals("spk_b", outScene2.speakerId)

        // 3. Per-scene cutaway side memory: spk_a had left-side spoken anchor ONLY in Scene 1;
        //    in Scene 2 (after 60s boundary), spk_a has NOT yet established a Scene 2 side, so
        //    OFF_SCREEN_BEHIND must NOT inherit Scene 1's left edge.
        val resolvedCutaways = applyCutawayOffScreenSideMemory(
            events = listOf(outScene1, outScene2, scene2OffScreen),
            speakers = updatedSpeakers,
            sceneBoundaryMs = sceneBoundaries,
        )
        val outOffScreen = resolvedCutaways.first { it.id == "evt_scene2_off" }
        assertEquals(
            "Scene 2 off-screen cue should not inherit Scene 1 left-side memory",
            DialogueType.OFF_SCREEN_BEHIND,
            outOffScreen.dialogueType,
        )
    }

    @Test
    fun secondScoutPreListensBothChapterAndWindowSeamChangeoversAheadOfPlayback() {
        val coordinator = SentinelWorkforceCoordinator()
        val chapters = listOf(
            VideoChapter(
                id = "ch_0",
                title = "Intro",
                startMs = 0L,
                endMs = 55_000L,
                source = ChapterSource.YOUTUBE_DESCRIPTION,
            ),
            VideoChapter(
                id = "ch_1",
                title = "Main Debate",
                startMs = 55_000L,
                endMs = 130_000L,
                source = ChapterSource.YOUTUBE_DESCRIPTION,
            ),
        )
        val project = testProject(durationMs = 200_000L, chapters = chapters)
        coordinator.resetForProject(project)
        coordinator.updateRuntimeConditions(
            quotaMode = QuotaSaverMode.QUOTA_SAVER,
            isPlaybackBuffering = false,
            contiguousPlayheadLeadMs = 60_000L,
            isBatteryOrThermalConstrained = false,
        )

        // Playhead at 12s -> 55s lookahead horizon (up to 67s) covers:
        // - nominal Scout window seam at 35s
        // - chapter boundary at 55s
        val queued = coordinator.planUpcomingChangeoverPreListens(
            project = project,
            playheadMs = 12_000L,
            nowEpochMs = 10_000L,
        )
        assertEquals(
            "Expected 35s window seam and 55s chapter seam queued at playhead 12s",
            2,
            queued.size,
        )
        assertTrue(queued.all { it.kind == SentinelTaskKind.CHANGEOVER_PRELISTEN })
        assertTrue(queued.any { it.chapterId == "ch_1" })
        assertTrue(queued.any { it.startMs <= 35_000L && it.endMs >= 35_000L })

        // Advancing playhead to 25s extends horizon to 80s and queues the 75s Scout window seam too
        val queuedLater = coordinator.planUpcomingChangeoverPreListens(
            project = project,
            playheadMs = 25_000L,
            nowEpochMs = 10_200L,
        )
        assertTrue(queuedLater.any { it.startMs <= 75_000L && it.endMs >= 75_000L })

        // Worker 1 actively holds a non-overlapping range [0s..20s]; Worker 2 can scale up and claim a changeover pre-listen!
        val w1Res = coordinator.tryReserveInterval(
            ownerId = SentinelWorkforceCoordinator.WORKER_1_ID,
            startMs = 0L,
            endMs = 20_000L,
            tier = SentinelPriorityTier.CRITICAL_PLAYBACK_RECOVERY,
            nowEpochMs = 10_000L,
        )
        assertNotNull(w1Res)

        val claimedByWorker2 = coordinator.claimChangeoverPreListenWork(
            workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
            nowEpochMs = 10_500L,
        )
        assertNotNull("Dedicated 2nd Scout should claim upcoming changeover seam while Worker 1 runs", claimedByWorker2)
        val (claimedItem, w2Res) = claimedByWorker2!!
        assertEquals(SentinelTaskKind.CHANGEOVER_PRELISTEN, claimedItem.kind)

        // Complete and verify seam is marked pre-listened and never re-queued
        coordinator.markChangeoverPreListenCompleted(claimedItem.id)
        coordinator.markWorkerIdle(SentinelWorkforceCoordinator.WORKER_2_ID, w2Res.reservationId, repairCompleted = true)
        val seamMs = claimedItem.startMs + SentinelWorkforceCoordinator.CHANGEOVER_PRE_MS
        assertTrue(coordinator.isChangeoverSeamPreListened(seamMs))

        val reQueued = coordinator.planUpcomingChangeoverPreListens(
            project = project,
            playheadMs = 25_000L,
            nowEpochMs = 12_000L,
        )
        assertFalse(reQueued.any { it.id == claimedItem.id })
    }
}
