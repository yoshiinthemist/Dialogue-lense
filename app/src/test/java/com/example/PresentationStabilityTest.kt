package com.example

import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.project.DemoScenarioFactory
import com.example.domain.analysis.HierarchicalContextEngine
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.LiveStageFrameSnapshot
import com.example.domain.analysis.SubtitleTimelineMatcher
import com.example.domain.analysis.PlaybackClockAnchor
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.MeasuredBalloonLayout
import org.junit.Assert.*
import org.junit.Test

class PresentationStabilityTest {
    private val project = DemoScenarioFactory.createDemoProject()
    private val proposal = BalloonLayoutEngine().computeLayout(project, 1_500L).first()
    private val first = NormalizedRect(.05f, .05f, .35f, .2f)
    private val far = NormalizedRect(.63f, .69f, .93f, .84f)

    @Test fun delayedHeartbeatsCannotReenterACueOrExtrapolateThroughSilence() {
        val clock = PlaybackClockAnchor()
        clock.reset(1_000L, 1f, true, 0L)
        assertEquals(1_100L, clock.sampleInterpolatedMs(nowNs = 100_000_000L))
        clock.reconcileRemoteSample(1_060L, 1f, true, 100_000_000L)
        assertEquals(1_100L, clock.sampleInterpolatedMs(nowNs = 100_000_000L))
        clock.reconcileRemoteSample(1_045L, 1f, true, 110_000_000L)
        val stopped = clock.sampleInterpolatedMs(nowNs = 500_000_000L)
        clock.reconcileRemoteSample(1_060L, 1f, true, 1_000_000_000L)
        assertEquals(stopped, clock.sampleInterpolatedMs(nowNs = 2_000_000_000L))
        clock.reset(120L, 2f, false, 2_000_000_000L)
        assertEquals(120L, clock.sampleInterpolatedMs(nowNs = 3_000_000_000L))
    }

    @Test fun sharedCueBoundaryRetiresPreviousLineAndKeepsSimultaneousSpeakers() {
        val a = proposal.event.copy(id = "a", startTimeMs = 0L, endTimeMs = 100L)
        val b = a.copy(id = "b", startTimeMs = 100L, endTimeMs = 150L)
        val c = b.copy(id = "c", speakerId = "another")
        val cues = listOf(a, b, c)
        val timeline = SubtitleTimelineMatcher()
        assertEquals(listOf("a"), timeline.matchActiveEvents(cues, 99L).map { it.id })
        assertEquals(setOf("b", "c"), timeline.matchActiveEvents(cues, 100L).map { it.id }.toSet())
    }

    @Test fun subframeLocalCorrectionCannotReopenPreviousCue() {
        val clock = PlaybackClockAnchor()
        clock.reset(1_000L, 1f, true, 0L)
        assertEquals(1_016L, clock.sampleLocalExoPosition(1_000L, 1f, true, nowNs = 16_000_000L))
        assertEquals(1_016L, clock.sampleLocalExoPosition(1_009L, 1f, true, nowNs = 20_000_000L))
        assertEquals(1_057L, clock.sampleLocalExoPosition(1_009L, 1f, true, nowNs = 300_000_000L))
    }

    @Test fun bodySurvivesAlternatingProposalsRefinementsAndRealOcclusions() {
        val layout = MeasuredBalloonLayout()
        val id = proposal.event.id
        val sizes = mapOf(id to NormalizedPoint(.3f, .15f))
        fun run(rect: NormalizedRect, scene: String, obstacles: List<NormalizedRect> = emptyList(), text: String = "hello"): NormalizedRect {
            val cue = proposal.copy(rect = rect, layoutSceneKey = scene, event = proposal.event.copy(translatedText = text))
            return layout.place(listOf(cue), sizes, obstacles, .01f, .01f).getValue(id)
        }
        val start = run(first, "A")
        repeat(200) { index ->
            assertEquals(start, run(if (index % 2 == 0) far else first, "A", text = "corrected"))
        }
        assertEquals(0, layout.relocations)
        val moved = run(far, "A", listOf(start))
        assertFalse(moved.intersects(start))
        assertEquals(moved, run(first, "A"))
        assertEquals(1, layout.relocations)
        assertNotEquals(moved, run(first, "B"))
    }

    @Test fun simultaneousCuesKeepPositionAcrossReorderAndManualDrag() {
        val layout = MeasuredBalloonLayout()
        val a = proposal.copy(rect = first)
        val b = a.copy(event = a.event.copy(id = "second", dialogueType = DialogueType.SOUND_DESCRIPTION))
        val sizes = listOf(a,b).associate { it.event.id to NormalizedPoint(.34f,.18f) }
        val before = layout.place(listOf(b, a), sizes, emptyList(), .01f,.01f)
        assertFalse(before.getValue(a.event.id).intersects(before.getValue(b.event.id)))
        assertEquals(before, layout.place(listOf(a, b), sizes, emptyList(), .01f,.01f))
        val edited = a.copy(rect = far, event = a.event.copy(placementMode = PlacementMode.MANUAL_OVERRIDE))
        val after = layout.place(listOf(edited, b), sizes, emptyList(), .01f,.01f)
        assertEquals(far.centerX, after.getValue(a.event.id).centerX, .001f)
    }

    @Test fun staticShotDoesOneSpatialPassUntilCueChange() {
        val layout = BalloonLayoutEngine()
        val cue = proposal.event.copy(startTimeMs = 0L, endTimeMs = 30_000L)
        val static = project.copy(shots = emptyList(), faceTracks = emptyList(), dialogueEvents = listOf(cue))
        repeat(600) { frame -> assertEquals(cue.id, layout.computeLayout(static, frame * 16L).single().event.id) }
        assertEquals(1L, layout.spatialPassCount)
        assertTrue(layout.computeLayout(static, 31_000L).isEmpty())
        layout.computeLayout(static, 1_000L)
        assertEquals(2L, layout.spatialPassCount)
    }

    @Test fun oneDroppedFramePreservesTailAndConfirmedAbsenceClearsIt() {
        val context = HierarchicalContextEngine()
        context.resetForProject(project)
        context.onPlaybackPosition(project, 1_000L)
        val faces = listOf(LiveFaceObservation(1, first, NormalizedPoint(.2f,.2f),0f,0L))
        fun sample(time: Long, seen: Boolean, cut: Boolean = false) = context.onLiveFrameSnapshot(
            LiveStageFrameSnapshot(if (seen) faces else emptyList(), cut, if (cut) .5f else 0f, .4f, time), time
        ).sceneMemory
        assertTrue(sample(1_000L, true).coordinatesValidated)
        assertTrue(sample(1_160L, false).coordinatesValidated)
        assertTrue(sample(1_320L, false).liveFrameObservedEmpty)
        assertFalse(sample(1_480L, true).coordinatesValidated)
        assertTrue(sample(1_640L, true).coordinatesValidated)
        val epoch = sample(1_800L, false, true).sceneEpoch
        assertEquals(epoch, sample(1_400L, true).sceneEpoch)
        assertTrue(context.currentSnapshot().sceneMemory.visibleFaces.isEmpty())
        context.invalidateSceneCoordinates(15_000L)
        assertTrue(context.currentSnapshot().sceneMemory.visibleFaces.isEmpty())
        assertEquals(project.speakers.map { it.id }.toSet(), context.currentSnapshot().videoMemory.confirmedSpeakersById.keys)
    }
}
