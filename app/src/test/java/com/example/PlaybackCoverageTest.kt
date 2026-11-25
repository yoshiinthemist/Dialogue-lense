package com.example

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
        ledger.recordOutcome(0, 5_000, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT)
        ledger.recordOutcome(0, 8_000, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT)
        ledger.recordOutcome(8_000, 15_000, CoverageStatus.PARTIAL_RECOVERY)
        ledger.recordOutcome(15_000, 30_000, CoverageStatus.VERIFIED_SILENCE)
        assertEquals(5_000L, ledger.verifiedLeadMs(0))
        assertEquals(0L, ledger.verifiedLeadMs(7_000))
        assertEquals(0L, ledger.verifiedLeadMs(8_000))
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
