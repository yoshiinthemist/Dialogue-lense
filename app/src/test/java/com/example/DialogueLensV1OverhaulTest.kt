package com.example

import com.example.data.model.BalloonStyleSettings
import com.example.data.model.ChapterSource
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealScope
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoChapter
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.subtitle.YouTubeCaptionFetcher
import com.example.domain.analysis.AutoSelfHealDetector
import com.example.domain.analysis.CoverageSummary
import com.example.domain.analysis.HealBeaconEngine
import com.example.domain.analysis.HierarchicalContextEngine
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.LiveStageFrameSnapshot
import com.example.domain.analysis.RenderPresentationMode
import com.example.domain.analysis.SceneMemory
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.TailDirection
import com.example.domain.media.MediaCapabilities
import com.example.domain.media.MediaProviderKind
import com.example.domain.media.MediaSourceAdapter
import com.example.domain.media.MediaSourceAdapterRegistry
import com.example.domain.media.ResolvedMediaSource
import com.example.ui.state.HumanReadableStatus
import com.example.ui.state.ReadinessTone
import com.example.ui.state.ScoutCaptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueLensV1OverhaulTest {

    // -------------------------------------------------------------------------
    // 1. Chapter Extraction & Editorial Fallback
    // -------------------------------------------------------------------------

    @Test
    fun youtubeChaptersExtractedFromDescriptionCleanly() {
        val description = """
            Welcome to the interview!
            0:00 Introduction & Setup
            02:15 - Meeting the Guest
            1:05:30 Final Takeaways
            Thanks for watching!
        """.trimIndent()

        val chapters = YouTubeCaptionFetcher.extractChaptersFromDescription(
            description = description,
            durationMs = 4_200_000L,
        )

        assertEquals(3, chapters.size)
        assertEquals("Introduction & Setup", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(135_000L, chapters[0].endMs)
        assertEquals(ChapterSource.YOUTUBE_DESCRIPTION, chapters[0].source)

        assertEquals("Meeting the Guest", chapters[1].title)
        assertEquals(135_000L, chapters[1].startMs)
        assertEquals(3_930_000L, chapters[1].endMs)

        assertEquals("Final Takeaways", chapters[2].title)
        assertEquals(3_930_000L, chapters[2].startMs)
        assertEquals(4_200_000L, chapters[2].endMs)
    }

    @Test
    fun editorialChaptersInferredWhenNoDescriptionChaptersExist() {
        val events = listOf(
            sampleEvent("e1", 1_000L, 4_000L, "Hello", "spk_a"),
            sampleEvent("e2", 5_000L, 8_000L, "Welcome", "spk_b"),
            // 37s pause past 40s minimum segment -> editorial boundary at 45s
            sampleEvent("e3", 45_000L, 48_000L, "Next topic", "spk_a"),
            sampleEvent("e4", 49_000L, 52_000L, "Agreed", "spk_b"),
        )
        val chapters = HierarchicalContextEngine.inferEditorialChapters(
            events = events,
            durationMs = 90_000L,
        )
        assertTrue("Expected inferred chapters across long pause", chapters.size >= 2)
        assertTrue(chapters.all { it.source == ChapterSource.INFERRED_EDITORIAL })
        assertEquals(0L, chapters.first().startMs)
        assertEquals(90_000L, chapters.last().endMs)
    }

    // -------------------------------------------------------------------------
    // 2. Hierarchical 3-Layer Context Engine
    // -------------------------------------------------------------------------

    @Test
    fun hierarchicalContextPreservesLayerAIdentityWhileInvalidatingLayerCOnVisualCut() {
        val engine = HierarchicalContextEngine()
        val alice = Speaker("spk_a", "Speaker A", customName = "Alice", accentIndex = 0, isLocked = true)
        val project = sampleProject(
            speakers = listOf(alice),
            chapters = listOf(
                VideoChapter("ch_1", "Act 1", 0L, 30_000L, ChapterSource.YOUTUBE_DESCRIPTION),
                VideoChapter("ch_2", "Act 2", 30_000L, 60_000L, ChapterSource.YOUTUBE_DESCRIPTION),
            ),
            events = listOf(sampleEvent("e1", 1_000L, 4_000L, "Hi there", "spk_a", locked = true)),
        )
        engine.resetForProject(project)
        val initialSnap = engine.onLiveFrameSnapshot(
            snapshot = LiveStageFrameSnapshot(
                faces = listOf(sampleFace(0.28f, 0.32f, capturedAtMs = 2_000L)),
                visualCutDetected = false,
                cutDistance = 0.02f,
                frameLumaAverage = 0.45f,
                capturedAtMs = 2_000L,
            ),
            playbackMs = 2_000L,
        )
        val epochBeforeCut = initialSnap.sceneMemory.sceneEpoch
        assertTrue(initialSnap.sceneMemory.coordinatesValidated)

        // Visual cut to empty/B-roll frame must bump scene epoch and clear stale Layer C locations while preserving Layer A identity
        val afterCut = engine.onLiveFrameSnapshot(
            snapshot = LiveStageFrameSnapshot(
                faces = emptyList(),
                visualCutDetected = true,
                cutDistance = 0.42f,
                frameLumaAverage = 0.20f,
                capturedAtMs = 5_000L,
            ),
            playbackMs = 5_000L,
        )
        assertTrue(afterCut.sceneMemory.sceneEpoch > epochBeforeCut)
        assertTrue(afterCut.sceneMemory.visibleFaces.isEmpty())
        assertTrue(afterCut.sceneMemory.liveFrameObservedEmpty)
        assertFalse(afterCut.sceneMemory.coordinatesValidated)
        assertEquals("Alice", afterCut.videoMemory.confirmedSpeakersById["spk_a"]?.displayName)
        assertTrue(afterCut.videoMemory.userLockedEventIds.contains("e1"))

        // Chapter transition updates Layer B cleanly without discarding Layer A
        val chapterSnap = engine.onPlaybackPosition(project, playbackMs = 35_000L)
        assertEquals("ch_2", chapterSnap.chapterMemory.activeChapter?.id)
        assertEquals("Alice", chapterSnap.videoMemory.confirmedSpeakersById["spk_a"]?.displayName)
    }

    @Test
    fun confidenceSeparationChoosesTaillessWhenIdentityHighAndLocationUncertain() {
        val alice = Speaker("spk_a", "Speaker A", customName = "Alice", accentIndex = 0, isLocked = true)
        val event = sampleEvent("e1", 1_000L, 4_000L, "Line during B-roll", "spk_a", confidence = 0.9f)
        val emptyScene = SceneMemory(
            sceneEpoch = 2,
            hasLiveVisionSignal = true,
            visibleFaces = emptyList(),
            liveFrameObservedEmpty = true,
            coordinatesValidated = false,
        )

        val assessment = HierarchicalContextEngine.assessConfidence(
            event = event,
            speaker = alice,
            hasVerifiedFaceMatch = false,
            sceneMemory = emptyScene,
            isOffScreen = false,
        )

        assertTrue(assessment.identityConfidence >= 0.7f)
        assertTrue(assessment.locationConfidence < 0.4f)
        assertEquals(RenderPresentationMode.SPEAKER_TAILLESS_BUBBLE, assessment.presentationMode)
    }

    // -------------------------------------------------------------------------
    // 3. BalloonLayoutEngine 1.0 Stability, B-Roll Guard & Accessibility
    // -------------------------------------------------------------------------

    @Test
    fun emptyLiveBrollFrameNeverDrawsDirectionalTailToStaleScoutAnchor() {
        val layout = BalloonLayoutEngine()
        val alice = Speaker("spk_a", "Speaker A", customName = "Alice", accentIndex = 0, isLocked = true)
        val event = sampleEvent(
            id = "e1",
            startMs = 10_000L,
            endMs = 14_000L,
            text = "Narration over scenic mountain shot",
            speakerId = "spk_a",
            confidence = 0.88f,
            anchor = NormalizedPoint(0.25f, 0.35f),
        )
        val project = sampleProject(speakers = listOf(alice), events = listOf(event))

        val placed = layout.computeLayout(
            project = project,
            currentMs = 11_500L,
            styleSettings = BalloonStyleSettings(showTail = true),
            liveFaces = emptyList(),
            sceneEpoch = 2,
            hasLiveVisionSignal = true,
            liveFrameObservedEmpty = true,
        )

        assertEquals(1, placed.size)
        assertNull("Empty B-roll frame must not draw a directional tail to stale coordinates", placed[0].tailTarget)
        assertEquals(TailDirection.NONE, placed[0].tailDirection)
        assertEquals("Alice", placed[0].speaker?.displayName)
    }

    @Test
    fun stablePositioningModeKeepsSpeakerIdentityWithCalmTaillessPlacement() {
        val layout = BalloonLayoutEngine()
        val bob = Speaker("spk_b", "Speaker B", customName = "Bob", accentIndex = 1, isLocked = true)
        val event = sampleEvent("e1", 2_000L, 5_000L, "Stay steady", "spk_b", confidence = 0.9f)
        val project = sampleProject(speakers = listOf(bob), events = listOf(event))

        val placed = layout.computeLayout(
            project = project,
            currentMs = 3_000L,
            styleSettings = BalloonStyleSettings(showTail = true, stablePositioning = true),
            liveFaces = listOf(sampleFace(0.22f, 0.30f, capturedAtMs = 3_000L)),
            sceneEpoch = 0,
            hasLiveVisionSignal = true,
            liveFrameObservedEmpty = false,
        )

        assertEquals(1, placed.size)
        assertNull("Stable Positioning must suppress directional tails", placed[0].tailTarget)
        assertEquals(TailDirection.NONE, placed[0].tailDirection)
        assertEquals("Bob", placed[0].speaker?.displayName)
    }

    @Test
    fun expiredDialogueDoesNotOverhangPastEndBoundary() {
        val layout = BalloonLayoutEngine()
        val event = sampleEvent("e1", 1_000L, 3_000L, "Done speaking", "spk_a")
        val project = sampleProject(events = listOf(event))

        val stillActive = layout.computeLayout(project, currentMs = 2_900L, styleSettings = BalloonStyleSettings())
        val afterEnd = layout.computeLayout(project, currentMs = 3_300L, styleSettings = BalloonStyleSettings())

        assertEquals(1, stillActive.size)
        assertTrue("Ended dialogue must clear cleanly without lingering", afterEnd.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 4. Heal Beacon Engine — Localized Repair, Lock Preservation, Undo & Recovery
    // -------------------------------------------------------------------------

    @Test
    fun healBeaconAdaptiveAndChapterWindowsExpandCleanlyToBounds() {
        val project = sampleProject(
            durationMs = 120_000L,
            chapters = listOf(
                VideoChapter("ch_1", "Intro", 0L, 50_000L, ChapterSource.YOUTUBE_DESCRIPTION),
                VideoChapter("ch_2", "Main", 50_000L, 120_000L, ChapterSource.YOUTUBE_DESCRIPTION),
            ),
            events = listOf(
                sampleEvent("e1", 37_000L, 42_000L, "Straddling left edge.", "spk_a"),
                sampleEvent("e2", 52_000L, 56_000L, "At mark.", "spk_a"),
                sampleEvent("e3", 68_000L, 73_000L, "Straddling right edge.", "spk_b"),
            ),
        )

        // Adaptive window around 54s starts at 39s..69s and expands to avoid bisecting e1 (37s..42s) & e3 (68s..73s)
        val adaptiveWindow = HealBeaconEngine.computeWindow(
            timestampMs = 54_000L,
            scope = HealScope.ADAPTIVE,
            project = project,
        )
        assertTrue("Adaptive window must pull back to avoid bisecting left cue", adaptiveWindow.startMs <= 37_000L)
        assertTrue("Adaptive window must extend to avoid bisecting right cue", adaptiveWindow.endMs >= 73_000L)

        // Chapter scope strictly scopes to chapter 2 [50s..120s]
        val chapterWindow = HealBeaconEngine.computeWindow(
            timestampMs = 54_000L,
            scope = HealScope.CHAPTER,
            project = project,
        )
        assertEquals(50_000L, chapterWindow.startMs)
        assertEquals(120_000L, chapterWindow.endMs)
    }

    @Test
    fun localizedRepairPreservesUnaffectedDialogueAndUserLockedEditsAndSupportsUndo() {
        val beforeCue = sampleEvent("before", 5_000L, 8_000L, "Unaffected earlier line", "spk_a")
        val lockedCue = sampleEvent("locked", 24_000L, 27_000L, "My manual correction", "spk_b", locked = true)
        val brokenCue = sampleEvent("broken", 29_000L, 32_000L, "Garbled line", "spk_a")
        val afterCue = sampleEvent("after", 75_000L, 79_000L, "Unaffected later line", "spk_b")

        val baseProject = sampleProject(
            durationMs = 90_000L,
            events = listOf(beforeCue, lockedCue, brokenCue, afterCue),
        )

        val beacon = HealBeacon(
            id = "hb_1",
            timestampMs = 30_000L,
            windowStartMs = 20_000L,
            windowEndMs = 40_000L,
            status = HealBeaconStatus.HEALING,
            scope = HealScope.ADAPTIVE,
        )
        val withBeacon = baseProject.copy(healBeacons = listOf(beacon), subtitleSource = SubtitleSourceKind.AI_DRAFT)

        val repairedCue = sampleEvent("repaired", 29_100L, 32_200L, "Crystal clear repaired line", "spk_a")
        val outcome = HealBeaconEngine.applyValidatedLocalizedRepair(
            project = withBeacon,
            beaconId = "hb_1",
            candidateEvents = listOf(repairedCue),
            silenceExplicitlyConfirmed = false,
        )

        assertTrue(outcome.applied)
        val updatedEvents = outcome.updatedProject.dialogueEvents
        assertTrue(updatedEvents.any { it.id == "before" })
        assertTrue(updatedEvents.any { it.id == "after" })
        assertTrue("User-locked edit inside Heal window must be preserved", updatedEvents.any { it.id == "locked" })
        assertTrue(updatedEvents.any { it.sourceText == "Crystal clear repaired line" })
        assertEquals("broken", updatedEvents.single { it.sourceText == "Crystal clear repaired line" }.id)

        val updatedBeacon = outcome.updatedProject.healBeacons.first { it.id == "hb_1" }
        assertEquals(HealBeaconStatus.HEALED, updatedBeacon.status)

        // Undo must restore the pre-heal dialogue snapshot cleanly
        val undone = HealBeaconEngine.undoBeaconRepair(outcome.updatedProject, "hb_1")
        assertTrue(undone.dialogueEvents.any { it.id == "broken" })
        assertFalse(undone.dialogueEvents.any { it.sourceText == "Crystal clear repaired line" })
    }

    @Test
    fun unconfirmedEmptyRepairNeverDeletesExistingDialogue() {
        val existingCue = sampleEvent("keep_me", 25_000L, 29_000L, "Legitimate speech", "spk_a")
        val beacon = HealBeacon(
            id = "hb_empty",
            timestampMs = 27_000L,
            windowStartMs = 15_000L,
            windowEndMs = 35_000L,
            status = HealBeaconStatus.HEALING,
        )
        val project = sampleProject(events = listOf(existingCue), beacons = listOf(beacon))

        val outcome = HealBeaconEngine.applyValidatedLocalizedRepair(
            project = project,
            beaconId = "hb_empty",
            candidateEvents = emptyList(),
            silenceExplicitlyConfirmed = false,
        )

        assertFalse(outcome.applied)
        assertTrue(
            "Existing dialogue must never be wiped on unconfirmed empty AI output",
            outcome.updatedProject.dialogueEvents.any { it.id == "keep_me" },
        )
        assertEquals(HealBeaconStatus.NEEDS_ATTENTION, outcome.updatedProject.healBeacons.first().status)
    }

    @Test
    fun sparseRepairKeepsUnmentionedCuesAndUndoPreservesANewerEdit() {
        val first = sampleEvent("a", 20_000, 23_000, "One", "spk_a")
        val second = sampleEvent("b", 23_500, 26_000, "Two", "spk_b")
        val beacon = HealBeacon("mark", 23_000, 10_000, 35_000, HealBeaconStatus.HEALING)
        val base = sampleProject(events = listOf(first, second), beacons = listOf(beacon))
            .copy(subtitleSource = SubtitleSourceKind.AI_DRAFT)
        val repaired = HealBeaconEngine.applyValidatedLocalizedRepair(base, beacon.id,
            listOf(first.copy(id = "fresh", sourceText = "First", translatedText = "First"))).updatedProject
        assertEquals(listOf("a", "b"), repaired.dialogueEvents.map { it.id })
        assertEquals(second, repaired.dialogueEvents.last())
        val edit = repaired.dialogueEvents.first().copy(translatedText = "My later edit")
        val undone = HealBeaconEngine.undoBeaconRepair(repaired.copy(dialogueEvents = listOf(edit, second)), beacon.id)
        assertEquals(listOf(edit, second), undone.dialogueEvents)
        assertEquals(HealBeaconStatus.UNDONE, undone.healBeacons.single().status)
        assertEquals(undone, HealBeaconEngine.restoreInterruptedBeacons(undone))
    }

    @Test
    fun authoritativeTextAndBoundaryStraddlingLinesSurviveModelChanges() {
        val first = sampleEvent("a", 20_000, 23_000, "Attached line", "spk_a")
        val acrossEnd = sampleEvent("b", 24_000, 28_000, "On the edge", "spk_b")
        val beacon = HealBeacon("mark", 23_000, 10_000, 25_000, HealBeaconStatus.HEALING)
        val base = sampleProject(events = listOf(first, acrossEnd), beacons = listOf(beacon))
        val proposed = first.copy(id = "other", translatedText = "AI guess")
        assertEquals(base.dialogueEvents, HealBeaconEngine.applyValidatedLocalizedRepair(base, beacon.id,
            listOf(proposed, acrossEnd.copy(translatedText = "Changed"))).updatedProject.dialogueEvents)
        assertEquals(base.dialogueEvents, HealBeaconEngine.applyValidatedLocalizedRepair(base, beacon.id,
            emptyList(), silenceExplicitlyConfirmed = true).updatedProject.dialogueEvents)
    }

    @Test
    fun chapterPatrolInspectsAheadUsingVerifiedEvidenceWithoutAManualMark() {
        val chapters = listOf(VideoChapter("intro","Intro",0,30_000), VideoChapter("ch2","Interview",30_000,60_000))
        val cue = sampleEvent("a", 29_000, 33_000, "Hello", "spk_a", anchor = NormalizedPoint(1.4f, 0.5f))
        val project = sampleProject(chapters = chapters, events = listOf(cue))
        assertNull(com.example.domain.analysis.ChapterPatrol.nextInspection(project, emptyList(), 0))
        val span = com.example.data.model.CoverageInterval(0, 60_000, com.example.data.model.CoverageStatus.VERIFIED_DIALOGUE)
        val result = requireNotNull(com.example.domain.analysis.ChapterPatrol.nextInspection(project, listOf(span), 0))
        assertEquals(20_000L, result.window.startMs)
        assertEquals(45_000L, result.window.endMs)
        assertTrue(result.inspection.finding.contains("pointer"))
        val inspected = project.copy(chapterInspections = listOf(result.inspection))
        assertNull(com.example.domain.analysis.ChapterPatrol.nextInspection(inspected, listOf(span), 0))
    }

    @Test
    fun rollbackRestoresOnlyEvidenceOwnedByTheRepair() {
        val ledger = com.example.domain.analysis.TranscriptCoverageLedger()
        val verified = com.example.data.model.CoverageStatus.VERIFIED_DIALOGUE
        val pending = com.example.data.model.CoverageStatus.PENDING
        ledger.reset(30_000)
        val originals = ledger.recordRepairEvidence(listOf(com.example.data.model.CoverageInterval(10_000, 20_000, verified)), "attempt")
        ledger.recordOutcome(12_000, 16_000, verified)
        ledger.undoRepairEvidence(originals, "attempt")
        assertEquals(pending, ledger.intervalsSnapshot().single { 11_000L in it.startMs until it.endMs }.status)
        assertEquals(verified, ledger.intervalsSnapshot().single { 14_000L in it.startMs until it.endMs }.status)
    }

    @Test
    fun scoutSkipsOnlyTheReservedPartOfAGap() {
        val ledger = com.example.domain.analysis.TranscriptCoverageLedger()
        ledger.reset(60_000)
        assertTrue(ledger.tryReserveRepair("first", 10_000, 20_000))
        assertFalse(ledger.tryReserveRepair("second", 15_000, 25_000))
        assertEquals(10_000L, ledger.nextPriorityInterval(0)?.endMs)
        ledger.releaseRepair("first")
        assertTrue((ledger.nextPriorityInterval(0)?.endMs ?: 0L) > 10_000)
    }

    @Test
    fun newMarkQueuesOnlyTheUncoveredPartOfAnActiveRepair() {
        val running = HealBeacon("active", 30_000, 20_000, 45_000, HealBeaconStatus.HEALING)
        val project = sampleProject(beacons = listOf(running))
        val (updated, added) = HealBeaconEngine.placeOrMergeBeacon(project, 44_000, HealScope.QUICK)
        assertEquals(running, updated.healBeacons.first { it.id == running.id })
        assertEquals(45_000L, added.windowStartMs)
        assertEquals(52_000L, added.windowEndMs)
    }

    @Test
    fun silenceAndClockTimeAloneDoNotInventChaptersInAnUnanalyzedVideo() {
        val cues = (0..3).map { index ->
            sampleEvent("line$index", index * 50_000L, index * 50_000L + 2_000L, "Hi", "spk_a")
        }
        val project = sampleProject(durationMs = 500_000L, events = cues).copy(subtitleSource = SubtitleSourceKind.AI_DRAFT)
        assertTrue(com.example.domain.analysis.ChapterPatrol.resolveChapters(project, emptyList()).isEmpty())
        assertTrue(HierarchicalContextEngine.inferEditorialChapters(cues.mapIndexed { i, cue ->
            cue.copy(startTimeMs = i * 2_000L, endTimeMs = i * 2_000L + 2_000L)
        }, 500_000L).isEmpty())
    }

    @Test
    fun interruptedHealingBeaconsRestoreToMarkedOnReload() {
        val project = sampleProject(
            beacons = listOf(
                HealBeacon("hb_1", 10_000L, 0L, 25_000L, status = HealBeaconStatus.HEALING),
                HealBeacon("hb_2", 40_000L, 25_000L, 55_000L, status = HealBeaconStatus.HEALED),
            ),
        )
        val restored = HealBeaconEngine.restoreInterruptedBeacons(project)
        assertEquals(HealBeaconStatus.MARKED, restored.healBeacons.first { it.id == "hb_1" }.status)
        assertEquals(HealBeaconStatus.HEALED, restored.healBeacons.first { it.id == "hb_2" }.status)
    }

    // -------------------------------------------------------------------------
    // 5. Automatic Self-Healing Detector — Safe Local Fixes & Cloud Bounds
    // -------------------------------------------------------------------------

    @Test
    fun localAnchorRepairPreservesDistinctSpeakersWithMatchingNames() {
        val dup1 = Speaker("spk_a", "Speaker A", customName = "Alice", accentIndex = 0, isLocked = true)
        val dup2 = Speaker("spk_b", "Speaker B", customName = "alice", accentIndex = 1, isIdentityConfirmed = false)
        val badEvent = sampleEvent(
            id = "e1",
            startMs = 2_000L,
            endMs = 5_000L,
            text = "Hello",
            speakerId = "spk_b",
            anchor = NormalizedPoint(Float.NaN, 1.5f),
        )
        val project = sampleProject(speakers = listOf(dup1, dup2), events = listOf(badEvent))

        val sanitizedEvents = AutoSelfHealDetector.sanitizeCorruptedAnchors(project.dialogueEvents)
        assertEquals("spk_b", sanitizedEvents.single().speakerId)
        assertNull("Invalid NaN anchor must be cleared", sanitizedEvents.first().anchorPosition)
    }

    @Test
    fun autoSelfHealRespectsZeroCloudCooldownAndPerVideoCap() {
        val detector = AutoSelfHealDetector()
        val project = sampleProject()

        assertFalse(
            "ZERO_CLOUD must never allow automatic cloud healing",
            detector.canTriggerAutomaticCloudHeal(project, 20_000L, QuotaSaverMode.ZERO_CLOUD, nowEpochMs = 1_000L),
        )

        assertTrue(detector.canTriggerAutomaticCloudHeal(project, 20_000L, QuotaSaverMode.PRO_ACCURACY, nowEpochMs = 10_000L))
        detector.recordAutomaticCloudHealTriggered(20_000L, nowEpochMs = 10_000L)

        // Same 30s bucket within 60s cooldown -> blocked
        assertFalse(detector.canTriggerAutomaticCloudHeal(project, 24_000L, QuotaSaverMode.PRO_ACCURACY, nowEpochMs = 25_000L))

        // Distinct regions up to cap of 4
        detector.recordAutomaticCloudHealTriggered(60_000L, nowEpochMs = 20_000L)
        detector.recordAutomaticCloudHealTriggered(100_000L, nowEpochMs = 30_000L)
        detector.recordAutomaticCloudHealTriggered(140_000L, nowEpochMs = 40_000L)

        // 5th auto heal in same video -> blocked by max-4 cap
        assertFalse(detector.canTriggerAutomaticCloudHeal(project, 200_000L, QuotaSaverMode.PRO_ACCURACY, nowEpochMs = 90_000L))
    }

    // -------------------------------------------------------------------------
    // 6. Plain-Language Status (Zero Developer Jargon on Watch)
    // -------------------------------------------------------------------------

    @Test
    fun humanReadableStatusNeverExposesInternalScoutOrModelJargon() {
        val project = sampleProject(
            events = listOf(sampleEvent("e1", 1_000L, 4_000L, "Hello", "spk_a")),
        )
        val states = listOf(
            HumanReadableStatus.resolve(project, ScoutCaptionState.FetchingCaptions("Fetching timedtext"), CoverageSummary()),
            HumanReadableStatus.resolve(
                project,
                ScoutCaptionState.AiTranscribing("gemini-2.5-flash Scout 4/20", completedWindows = 4, cuesSoFar = 12),
                CoverageSummary(),
            ),
            HumanReadableStatus.resolve(project, ScoutCaptionState.Ready("Full · 344", 344, 2), CoverageSummary(verifiedFraction = 1f)),
            HumanReadableStatus.resolve(project, ScoutCaptionState.Error("HTTP 429 quota"), CoverageSummary()),
        )
        val forbiddenTerms = listOf("scout", "gemini", "429", "full ·", "%", "/")
        for (status in states) {
            val lower = status.label.lowercase()
            for (term in forbiddenTerms) {
                assertFalse(
                    "Watch status '${status.label}' must not contain technical jargon '$term'",
                    lower.contains(term),
                )
            }
        }

        // Active Heal Beacon surfaces cleanly
        val healingProject = project.copy(
            healBeacons = listOf(HealBeacon("hb_1", 2_000L, 0L, 15_000L, status = HealBeaconStatus.HEALING)),
        )
        val healingReadiness = HumanReadableStatus.resolve(healingProject, ScoutCaptionState.Idle, CoverageSummary())
        assertEquals(ReadinessTone.ACTIVE, healingReadiness.tone)
        assertTrue(healingReadiness.label.contains("Healing", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // 7. Section 23 — Cross-Platform Media-Source Adapter Compatibility
    // -------------------------------------------------------------------------

    @Test
    fun mediaAdapterIndependentlyClassifiesAllSourcesAndRespectsDrm() {
        val registry = MediaSourceAdapterRegistry.default

        // 1. YouTube
        val yt = registry.resolveInput("https://youtu.be/3AFJtcP2cp0?t=12")
        assertNotNull(yt)
        assertEquals(MediaProviderKind.YOUTUBE_EMBED, yt!!.providerKind)
        assertEquals("3AFJtcP2cp0", yt.youtubeVideoId)
        assertTrue(yt.isSupported)
        assertTrue(yt.capabilities.supportsPlayback && yt.capabilities.supportsHealBeacon && yt.capabilities.supportsPersistence)

        // 2. Direct Media URLs (.mp4, .m3u8, .webm) -> ExoPlayer native path (VideoSourceKind.LOCAL_MP4)
        val directMp4 = registry.resolveInput("https://cdn.example.org/videos/interview.mp4?token=abc")
        val directHls = registry.resolveInput("https://streams.example.org/live/master.m3u8")
        assertNotNull(directMp4)
        assertNotNull(directHls)
        assertEquals(MediaProviderKind.DIRECT_MEDIA_URL, directMp4!!.providerKind)
        assertEquals(VideoSourceKind.LOCAL_MP4, directMp4.videoSourceKind)
        assertTrue(directMp4.capabilities.supportsPlaybackSync)
        assertTrue(directMp4.capabilities.supportsAudioExtraction)
        assertTrue(directMp4.capabilities.supportsSeeking)
        assertEquals(MediaProviderKind.DIRECT_MEDIA_URL, directHls!!.providerKind)

        // 3. Local Device File
        val local = registry.resolveInput("content://media/external/video/media/42")
        assertNotNull(local)
        assertEquals(MediaProviderKind.LOCAL_FILE, local!!.providerKind)
        assertTrue(local.capabilities.supportsAudioExtraction)
        assertTrue(local.capabilities.supportsLiveVisualTracking)

        // 4. Generic HTML5 Web Embed — honest audio restriction until non-DRM stream URL discovered
        val web = registry.resolveInput("https://vimeo.com/123456789")
        assertNotNull(web)
        assertEquals(MediaProviderKind.HTML5_WEB_EMBED, web!!.providerKind)
        assertEquals(VideoSourceKind.WEB_STREAM, web.videoSourceKind)
        assertTrue(web.isSupported)
        assertFalse("Generic HTML5 embed must honestly mark cross-origin audio as restricted by default", web.capabilities.supportsAudioExtraction)

        val webSource = VideoSource(
            uriString = "https://vimeo.com/123456789",
            displayName = "Vimeo",
            kind = VideoSourceKind.WEB_STREAM,
            durationMs = 120_000L,
            width = 1920,
            height = 1080,
        )
        val upgradedCaps = registry.capabilitiesFor(webSource, hasDiscoveredDirectAudio = true)
        assertTrue("Discovered non-DRM audio stream upgrades audio capability dynamically", upgradedCaps.supportsAudioExtraction)

        // 5. DRM-Protected Services -> NEVER advertised as supported!
        val drmUrls = listOf(
            "https://www.netflix.com/watch/80057281",
            "https://www.disneyplus.com/video/abc123",
            "https://play.max.com/video/watch/xyz",
            "https://www.primevideo.com/detail/0ABC",
            "https://tv.apple.com/us/show/ted-lasso",
            "https://www.hulu.com/watch/12345",
        )
        for (url in drmUrls) {
            val resolved = registry.resolveInput(url)
            assertNotNull("Expected DRM adapter match for $url", resolved)
            assertEquals(MediaProviderKind.UNSUPPORTED_DRM_PLATFORM, resolved!!.providerKind)
            assertFalse("DRM platform $url must not be marked supported", resolved.isSupported)
            assertTrue(resolved.capabilities.isDrmRestricted)
            assertFalse(resolved.capabilities.supportsPlayback)
            assertTrue(resolved.userNotice.contains("DRM", ignoreCase = true))
        }
    }

    @Test
    fun canonicalCacheKeysIsolateProvidersAndSupportFutureAdapterRegistration() {
        val registry = MediaSourceAdapterRegistry.default
        val ytSource = VideoSource(
            uriString = "https://youtu.be/3AFJtcP2cp0",
            displayName = "YT",
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            durationMs = 60_000L,
            width = 1920,
            height = 1080,
            youtubeVideoId = "3AFJtcP2cp0",
        )
        val directSource = VideoSource(
            uriString = "https://cdn.example.org/a.mp4?sig=1",
            displayName = "a.mp4",
            kind = VideoSourceKind.LOCAL_MP4,
            durationMs = 60_000L,
            width = 1920,
            height = 1080,
        )
        val directSourceNewToken = VideoSource(
            uriString = "https://cdn.example.org/a.mp4?sig=2",
            displayName = "a.mp4",
            kind = VideoSourceKind.LOCAL_MP4,
            durationMs = 60_000L,
            width = 1920,
            height = 1080,
        )
        val webSource = VideoSource(
            uriString = "https://example.org/watch/1",
            displayName = "Web",
            kind = VideoSourceKind.WEB_STREAM,
            durationMs = 60_000L,
            width = 1920,
            height = 1080,
        )

        val ytKey = registry.canonicalCacheKey(ytSource, "English")
        val directKey1 = registry.canonicalCacheKey(directSource, "English")
        val directKey2 = registry.canonicalCacheKey(directSourceNewToken, "English")
        val webKey = registry.canonicalCacheKey(webSource, "English")

        assertEquals("yt_3AFJtcP2cp0_english", ytKey)
        assertEquals("Signed query tokens on the same direct MP4 should share cache key", directKey1, directKey2)
        assertNotEquals(ytKey, directKey1)
        assertNotEquals(directKey1, webKey)

        // Extensibility check: adding a custom future provider without editing core engines
        val customAdapter = object : MediaSourceAdapter {
            override val providerKind = MediaProviderKind.HTML5_WEB_EMBED
            override val displayName = "PeerTube"
            override fun canHandleInput(rawInput: String) = rawInput.contains("peertube.example")
            override fun resolveInput(rawInput: String) = ResolvedMediaSource(
                providerKind = providerKind,
                providerName = displayName,
                normalizedUri = rawInput.trim(),
                videoSourceKind = VideoSourceKind.WEB_STREAM,
                capabilities = MediaCapabilities(
                    providerKind = providerKind,
                    providerName = displayName,
                    supportsPlayback = true,
                    supportsPlaybackSync = true,
                    supportsNativeCaptions = true,
                    supportsAudioExtraction = true,
                    supportsLiveVisualTracking = true,
                    supportsSeeking = true,
                    supportsSpeechBubbles = true,
                    supportsHealBeacon = true,
                    supportsPersistence = true,
                ),
            )
            override fun canHandleSource(source: VideoSource) = source.uriString.contains("peertube.example")
            override fun capabilitiesFor(source: VideoSource) = resolveInput(source.uriString).capabilities
            override fun canonicalCacheKey(source: VideoSource, targetLanguage: String) = "peertube_custom_$targetLanguage"
        }

        val extendedRegistry = registry.withAdditionalAdapter(customAdapter)
        val customResolved = extendedRegistry.resolveInput("https://peertube.example/w/abc")
        assertNotNull(customResolved)
        assertEquals("PeerTube", customResolved!!.providerName)
        assertTrue(customResolved.capabilities.supportsAudioExtraction)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleFace(
        centerX: Float,
        centerY: Float,
        capturedAtMs: Long,
    ): LiveFaceObservation = LiveFaceObservation(
        trackingId = 1,
        boundingBox = NormalizedRect(
            left = (centerX - 0.07f).coerceAtLeast(0f),
            top = (centerY - 0.12f).coerceAtLeast(0f),
            right = (centerX + 0.07f).coerceAtMost(1f),
            bottom = (centerY + 0.12f).coerceAtMost(1f),
        ),
        mouthPoint = NormalizedPoint(centerX, (centerY + 0.05f).coerceAtMost(1f)),
        headYawDeg = 0f,
        capturedAtMs = capturedAtMs,
    )

    private fun sampleEvent(
        id: String,
        startMs: Long,
        endMs: Long,
        text: String,
        speakerId: String?,
        confidence: Float = 0.85f,
        anchor: NormalizedPoint? = null,
        locked: Boolean = false,
    ): DialogueEvent = DialogueEvent(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        sourceText = text,
        translatedText = text,
        speakerId = speakerId,
        speakerConfidence = confidence,
        faceTrackId = null,
        faceConfidence = if (anchor != null) 0.7f else 0f,
        shotId = "shot_1",
        dialogueType = DialogueType.SPOKEN,
        anchorPosition = anchor,
        placementMode = if (anchor != null) PlacementMode.AUTO else PlacementMode.NEUTRAL_FALLBACK,
        lockedByUser = locked,
    )

    private fun sampleProject(
        durationMs: Long = 60_000L,
        speakers: List<Speaker> = listOf(
            Speaker("spk_a", "Speaker A", customName = "Alice", accentIndex = 0, isLocked = true),
            Speaker("spk_b", "Speaker B", customName = "Bob", accentIndex = 1, isLocked = true),
        ),
        chapters: List<VideoChapter> = emptyList(),
        events: List<DialogueEvent> = emptyList(),
        beacons: List<HealBeacon> = emptyList(),
    ): DialogueLensProject = DialogueLensProject(
        id = "proj_test",
        title = "Test Project",
        createdAtEpochMs = 1_000L,
        videoSource = VideoSource(
            uriString = "https://youtu.be/3AFJtcP2cp0",
            displayName = "Test Video",
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            durationMs = durationMs,
            width = 1920,
            height = 1080,
            youtubeVideoId = "3AFJtcP2cp0",
        ),
        subtitleSource = SubtitleSourceKind.EMBEDDED_TRACK,
        sourceLanguage = "English",
        targetLanguage = "English",
        speakers = speakers,
        shots = listOf(Shot("shot_1", 0L, durationMs)),
        faceTracks = emptyList(),
        dialogueEvents = events,
        chapters = chapters,
        healBeacons = beacons,
    )
}
