package com.example

import com.example.data.model.DialogueEvent
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.CapturedAudioBacklog
import com.example.domain.analysis.CapturedAudioClip
import com.example.domain.analysis.TimeWindowRange
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import com.example.domain.analysis.ScoutSeekRequests
import kotlinx.coroutines.yield
import com.example.domain.analysis.MediaTransport
import com.example.domain.playback.PlaybackReadinessController
import com.example.data.model.CoverageStatus
import com.example.data.model.SubtitleSourceKind
import com.example.domain.analysis.TranscriptCoverageLedger
import org.junit.Assert.*
import org.junit.Test

class PlaybackCoverageTest {
    private val ledger = TranscriptCoverageLedger(50_000)

    @Test fun verifiedLeadStartsAtDestinationAndStopsAtEvenOneMillisecondOfMissingEvidence() {
        ledger.recordOutcome(0, 10_000, CoverageStatus.VERIFIED_SILENCE)
        ledger.recordOutcome(10_001, 30_000, CoverageStatus.VERIFIED_DIALOGUE, dialogueCount = 3)
        ledger.recordOutcome(30_000, 36_000, CoverageStatus.VERIFIED_SILENCE)
        assertEquals(2_000L, ledger.verifiedLeadMs(8_000))
        assertEquals(0L, ledger.verifiedLeadMs(10_000))
        assertEquals(21_000L, ledger.verifiedLeadMs(15_000))
        assertEquals(2, ledger.bufferedRanges().size)
    }

    @Test fun failedOrPartialWorkCannotReplaceVerifiedEvidenceAndDoesNotBridgeGaps() {
        ledger.recordOutcome(0, 5_000, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 1)
        ledger.recordOutcome(0, 8_000, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT)
        ledger.recordOutcome(8_000, 15_000, CoverageStatus.PARTIAL_RECOVERY)
        ledger.recordOutcome(15_000, 30_000, CoverageStatus.VERIFIED_SILENCE)
        assertEquals(5_000L, ledger.verifiedLeadMs(0))
        assertEquals(0L, ledger.verifiedLeadMs(7_000))
        assertEquals(0L, ledger.verifiedLeadMs(8_000))
    }

    @Test fun decoderDurationExtendsCompleteTracksThroughTheQuietTailForASeek() {
        val cue = DialogueEvent(
            id = "cut", startTimeMs = 7_780L, endTimeMs = 9_400L, sourceText = "After the cut.",
            translatedText = "After the cut.", speakerId = null, speakerConfidence = 0f,
            faceTrackId = null, faceConfidence = 0f, shotId = "shot_1",
        )
        val events = listOf(cue)
        val duration = 12_002L
        val position = 7_800L
        for (source in listOf(SubtitleSourceKind.UPLOADED_FILE, SubtitleSourceKind.EMBEDDED_TRACK)) {
            ledger.markAuthoritativeTrack(cue.endTimeMs, events, source)
            assertEquals(1_600L, ledger.verifiedLeadMs(position))
            ledger.reconcileMediaDuration(duration, events, source)
            assertEquals(duration - position, ledger.verifiedLeadMs(position))
            assertEquals(CoverageStatus.VERIFIED_SILENCE, ledger.coverageStatusAt(11_000L))
            val controller = PlaybackReadinessController()
            controller.seek(position, 1)
            controller.setIntent(true)
            assertTrue(controller.acknowledge(1, position, true))
            val result = controller.evaluate(position, duration, 1f, ledger.verifiedLeadMs(position), true,
                false, MediaTransport.PAUSED, 100L)
            assertEquals(true, result.playCommand)
        }

        ledger.reset(cue.endTimeMs, source = SubtitleSourceKind.EMBEDDED_PARTIAL)
        ledger.recordOutcome(cue.startTimeMs, cue.endTimeMs, CoverageStatus.VERIFIED_DIALOGUE,
            SubtitleSourceKind.EMBEDDED_PARTIAL, dialogueCount = 1)
        ledger.reconcileMediaDuration(duration, events, SubtitleSourceKind.EMBEDDED_PARTIAL)
        assertEquals(1_600L, ledger.verifiedLeadMs(position))
        assertEquals(CoverageStatus.PENDING, ledger.coverageStatusAt(11_000L))
    }

    @Test fun emptyCaptionFileIsNotVerifiedSilence() {
        ledger.markAuthoritativeTrack(50_000L, emptyList(), SubtitleSourceKind.UPLOADED_FILE)
        assertEquals(0L, ledger.verifiedLeadMs(0))
        assertFalse(ledger.isFullyVerified())
    }

    @Test fun rapidSeeksReuseExistingWindowOrReplaceOneWorkerAtTheLatestMissingInstant() {
        val active = listOf(TimeWindowRange(10_000L, 17_000L), TimeWindowRange(30_000L, 37_000L))
        assertNull(LookaheadWindowPlanner.supersededScoutWindow(32_500L, active))
        val latest = 43_875L
        assertEquals(active.first(), LookaheadWindowPlanner.supersededScoutWindow(latest, active))
        ledger.markInProgress(30_000L, 37_000L)
        ledger.recordOutcome(latest, latest + 2_000L, CoverageStatus.VERIFIED_SILENCE)
        val next = ledger.nextPriorityInterval(latest, openingWindowMs = 7_000L, rollingWindowMs = 7_000L, startAtPlayhead = true)!!
        assertEquals(latest + 2_000L, next.startMs)
        assertNull(LookaheadWindowPlanner.supersededScoutWindow(next.startMs,
            active + TimeWindowRange(next.startMs, next.endMs)))
    }

    @Test fun browserRecordingsReuseSharedReservationsAndBoundCloudRequests() {
        val active = listOf(TimeWindowRange(10_000L, 17_000L))
        assertFalse(LookaheadWindowPlanner.canAnalyzeRecording(10_050L, 17_050L, active))
        assertTrue(LookaheadWindowPlanner.canAnalyzeRecording(17_000L, 24_000L, active))
        assertFalse(LookaheadWindowPlanner.canAnalyzeRecording(24_000L, 31_000L, active + TimeWindowRange(17_000L, 24_000L)))
    }

    @Test fun slowBrowserWorkersDrainThreeFollowingRecordingsWithoutDuplicatingAudio() = runTest {
        val backlog = CapturedAudioBacklog()
        val active = mutableListOf<TimeWindowRange>()
        val dispatched = mutableListOf<Long>()
        fun dispatch(gap: Long) {
            while (true) {
                val clip = backlog.take(gap, active) { false } ?: return
                active.add(TimeWindowRange(clip.startMs, clip.endMs))
                dispatched.add(clip.startMs)
            }
        }
        for (index in 0..4) {
            val start = index * 7_000L
            val clip = CapturedAudioClip(start, start + 7_000L, ByteArray(2_000), "audio/webm")
            repeat(2) { backlog.offer(clip, 0L, active) }
            dispatch(0L)
            delay(1_000)
        }
        assertEquals(listOf(0L, 7_000L), dispatched)
        // The first cloud completion occurs after all five recordings have already arrived.
        delay(7_000)
        for (i in 0..2) {
            active.removeAt(0)
            val gap = (i + 2) * 7_000L
            dispatch(gap)
            delay(12_000)
        }
        assertEquals(listOf(0L, 7_000L, 14_000L, 21_000L, 28_000L), dispatched)
        assertNull(backlog.take(35_000L, emptyList()) { false })
    }

    @Test fun captureBacklogEvictsByGapAndByteBudgetWithoutMarkingEvictedDialogueVerified() {
        val backlog = CapturedAudioBacklog(maxClips = 3, maxBytes = 6)
        fun offer(start: Long, bytes: Int) =
            backlog.offer(CapturedAudioClip(start, start + 7_000L, ByteArray(bytes), "audio/webm"), 35_000L, emptyList())
        offer(0L, 2)
        offer(7_000L, 2)
        offer(42_000L, 2)
        offer(35_000L, 3)
        assertTrue(backlog.hasWaitingGap(35_000L, emptyList()))
        assertEquals(35_000L, backlog.take(35_000L, emptyList()) { false }!!.startMs)
        assertEquals(42_000L, backlog.take(35_000L, emptyList()) { false }!!.startMs)
        assertNull(backlog.take(35_000L, emptyList()) { false })
        assertEquals(0L, ledger.verifiedLeadMs(0L))
        offer(35_000L, 2)
        backlog.clear()
        assertNull(backlog.take(35_000L, emptyList()) { false })
    }

    @Test fun scrubbingOnlyDispatchesTheFinalScoutTargetAndWaitingWorkersSeeTheSameTarget() = runTest {
        val dispatched = mutableListOf<Long>()
        val seeks = ScoutSeekRequests(this) { dispatched.add(it) }
        seeks.submit(5_000L)
        val worker = async { seeks.awaitLatest(); dispatched.last() }
        delay(50L)
        seeks.submit(45_000L)
        delay(50L)
        seeks.submit(8_000L)
        delay(100L)
        assertTrue(dispatched.isEmpty())
        assertEquals(8_000L, worker.await())
        assertEquals(listOf(8_000L), dispatched)
    }

    @Test fun temporarilyReservedQueueWakesOnReleaseAndStopsWhenWorkIsComplete() = runBlocking {
        assertTrue(ledger.tryReserveRepair("manual", 0L, 50_000L))
        val version = ledger.workRevision
        assertNull(ledger.nextPriorityInterval(10_000L))
        val waiter = async { ledger.awaitWorkRelease(version) }
        yield()
        assertFalse(waiter.isCompleted)
        ledger.releaseRepair("manual")
        assertTrue(waiter.await())
        assertEquals(10_000L, ledger.nextPriorityInterval(10_000L, startAtPlayhead = true)!!.startMs)
        ledger.recordOutcome(0L, 50_000L, CoverageStatus.VERIFIED_SILENCE)
        assertFalse(ledger.awaitWorkRelease(ledger.workRevision))
    }

    @Test fun partialOutcomeDoesNotRetryForever() {
        repeat(3) { ledger.recordOutcome(0, 50_000, CoverageStatus.PARTIAL_RECOVERY, incrementRetry = true) }
        assertNull(ledger.nextPriorityInterval(1_000))
    }
}
