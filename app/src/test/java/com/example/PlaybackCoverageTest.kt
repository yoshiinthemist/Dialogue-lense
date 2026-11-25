package com.example

import com.example.data.model.DialogueEvent
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

    @Test fun partialOutcomeDoesNotRetryForever() {
        repeat(3) { ledger.recordOutcome(0, 50_000, CoverageStatus.PARTIAL_RECOVERY, incrementRetry = true) }
        assertNull(ledger.nextPriorityInterval(1_000))
    }
}
