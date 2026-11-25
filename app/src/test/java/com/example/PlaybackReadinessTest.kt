package com.example

import com.example.data.model.CoverageStatus
import com.example.data.model.SubtitleSourceKind
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.analysis.MediaTransport
import com.example.domain.analysis.PlaybackPhase
import com.example.domain.playback.PlaybackReadinessController
import org.junit.Assert.*
import org.junit.Test

class PlaybackReadinessTest {
    private val controller = PlaybackReadinessController()

    private fun update(lead: Long, pos: Long = 0, duration: Long? = 120_000, speed: Float = 1f,
                       transport: MediaTransport = MediaTransport.PAUSED, now: Long = 0, failed: Boolean = false) =
        controller.evaluate(pos, duration, speed, lead, true, failed, transport, now)

    @Test fun preparedStartIssuesOnePlayCommandEvenWithRepeatedCoverageUpdates() {
        controller.setIntent(true)
        assertEquals(true, update(7_000).playCommand)
        assertNull(update(7_000).playCommand)
        assertEquals(PlaybackPhase.PLAYING, update(7_000, transport = MediaTransport.PLAYING).phase)
    }

    @Test fun playingBuffersBelowCriticalThenRequiresFullResumeLead() {
        controller.setIntent(true)
        update(7_000)
        assertNull(update(2_000, transport = MediaTransport.PLAYING).playCommand)
        assertEquals(false, update(1_999, transport = MediaTransport.PLAYING).playCommand)
        assertEquals(PlaybackPhase.BUFFERING_DIALOGUE, update(6_999).phase)
        assertEquals(true, update(7_000).playCommand)
        assertNull(update(7_500).playCommand)
    }

    @Test fun seeksSupersedeEarlierAcknowledgementsAndRequireCoverageAtDestination() {
        controller.setIntent(true)
        update(7_000)
        controller.seek(20_000, 4)
        assertEquals(false, update(30_000).playCommand)
        controller.seek(80_000, 6)
        assertFalse(controller.acknowledge(4, 20_000, true))
        assertFalse(controller.acknowledge(6, 20_000, true))
        assertFalse(controller.acknowledge(6, 80_000, false))
        assertEquals(PlaybackPhase.WAITING_FOR_SEEK, update(30_000, pos = 80_000).phase)
        assertTrue(controller.acknowledge(6, 80_030, true))
        assertNull(update(0, pos = 80_030).playCommand)
        assertEquals(true, update(7_000, pos = 80_030).playCommand)
    }

    @Test fun pauseDuringSeekSurvivesLateMediaAndAnalysisCallbacks() {
        controller.setIntent(true)
        update(0)
        controller.seek(80_000, 2)
        controller.setIntent(false)
        assertTrue(controller.acknowledge(2, 80_000, true))
        val ready = update(20_000, pos = 80_000)
        assertEquals(PlaybackPhase.USER_PAUSED, ready.phase)
        assertNull(ready.playCommand)
    }

    @Test fun speedAndKnownEndScaleTheResumeThreshold() {
        for ((speed, expected) in listOf(0.5f to 3_500L, 1f to 7_000L, 1.5f to 10_500L, 2f to 14_000L)) {
            controller.setIntent(false)
            update(0)
            controller.setIntent(true)
            assertEquals(expected, update(expected - 1, duration = null, speed = speed).minimumLeadMs)
            assertNull(update(expected - 1, duration = null, speed = speed).playCommand)
            assertEquals(true, update(expected, duration = null, speed = speed).playCommand)
        }
        controller.seek(5_500, 7)
        update(0)
        controller.acknowledge(7, 5_500, true)
        assertEquals(500L, update(0, 5_500, 6_000, 2f).minimumLeadMs)
        assertEquals(true, update(500, 5_500, 6_000, 2f).playCommand)
    }

    @Test fun timeoutAndFailuresExposeRecoveryWithoutAuthorizingPlay() {
        controller.setIntent(true)
        update(0, now = 1)
        val timeout = update(0, now = 18_001)
        assertEquals(PlaybackPhase.PROCESSING_UNAVAILABLE, timeout.phase)
        assertNull(timeout.playCommand)
        assertNull(update(0, failed = true).playCommand)
        controller.continueWithAvailable(0, 1f, 3_500)
        assertEquals(true, update(0).playCommand)
        assertEquals(PlaybackPhase.LIMITED, update(0, transport = MediaTransport.PLAYING).phase)
        assertEquals(false, update(0, pos = 3_500).playCommand)
    }

    @Test fun backgroundingCancelsIntentWithoutPresentingAnEditingPause() {
        controller.setIntent(true)
        update(7_000)
        controller.suspendPlayback()
        assertEquals(false, update(7_000).playCommand)
        assertEquals(PlaybackPhase.READY, update(7_000).phase)
        assertNull(update(7_000, now = 50_000).playCommand)
    }

    @Test fun unacknowledgedPausedSeekAlsoHasBoundedRecovery() {
        controller.setIntent(false)
        controller.seek(80_000, 2)
        update(7_000, now = 10)
        assertEquals(PlaybackPhase.PROCESSING_UNAVAILABLE, update(7_000, now = 18_010).phase)
        assertNull(update(7_000).playCommand)
    }

    @Test fun preparedResumeIntentCannotDispatchBeforeTheCurrentSeekIsAcknowledged() {
        val ledger = TranscriptCoverageLedger(120_000)
        controller.resetForMedia(0L, 120_000L, true)
        controller.onUserPlayRequested(0L, ledger, 10)
        controller.onUserSeekRequested(40_000L, ledger, 20)
        ledger.recordOutcome(40_000L, 50_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, 2)
        val prepared = controller.evaluateCoverage(ledger, 30).pendingAutoResume!!
        assertEquals(40_000L, prepared.targetPositionMs)
        assertEquals(prepared, controller.consumeAutoResumeDirective(prepared.id).pendingAutoResume)
        assertFalse(controller.acknowledge(0, 40_000L, true))
        assertTrue(controller.acknowledge(1, 40_000L, true))
        assertEquals(true, update(10_000L, pos = 40_000L, now = 40).playCommand)
        assertNull(controller.consumeAutoResumeDirective(prepared.id).pendingAutoResume)
        assertEquals(1, controller.consumeAutoResumeDirective(prepared.id).diagnostics.autoResumeCount)
        assertNull(update(10_000L, pos = 40_000L, now = 45).playCommand)
    }

    @Test fun transportStallDoesNotStartClockOrRepeatedlyIssuePlay() {
        controller.setIntent(true)
        val waiting = update(10_000, transport = MediaTransport.BUFFERING)
        assertEquals(PlaybackPhase.BUFFERING_MEDIA, waiting.phase)
        assertNull(waiting.playCommand)
        assertEquals(true, update(10_000).playCommand)
        assertNull(update(10_000, transport = MediaTransport.BUFFERING).playCommand)
        controller.setIntent(false)
        assertEquals(false, update(10_000).playCommand)
    }
}
