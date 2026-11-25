package com.example

import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.domain.analysis.ScoutExecutionTier
import com.example.domain.analysis.SegmentExecutionOutcome
import com.example.domain.analysis.SentinelPolicy
import org.junit.Assert.*
import org.junit.Test

class SentinelPolicyTest {
    private fun beacon(id: String, timestamp: Long, created: Long, auto: Boolean = false) =
        HealBeacon(id = id, timestampMs = timestamp, windowStartMs = timestamp, windowEndMs = timestamp + 10000,
            createdAtEpochMs = created, updatedAtEpochMs = created, autoTriggered = auto)

    @Test fun manualMarksRunInCreationOrderRegardlessOfTimestamp() {
        val auto = beacon("auto", 0, 1, true)
        val firstManual = beacon("first", 100000, 2)
        val secondManual = beacon("second", 1000, 3)
        assertEquals(firstManual, SentinelPolicy.nextBeacon(listOf(auto, secondManual, firstManual)))
        assertEquals(secondManual, SentinelPolicy.nextBeacon(listOf(auto, secondManual, firstManual.copy(status = HealBeaconStatus.HEALING))))
    }

    @Test fun staleOrDismissedAttemptCannotCommit() {
        val attempt = beacon("x", 1000, 1).copy(status = HealBeaconStatus.HEALING)
        assertTrue(SentinelPolicy.isStillCurrent(listOf(attempt), attempt))
        assertFalse(SentinelPolicy.isStillCurrent(emptyList(), attempt))
        assertFalse(SentinelPolicy.isStillCurrent(listOf(attempt.copy(status = HealBeaconStatus.MARKED)), attempt))
        assertFalse(SentinelPolicy.isStillCurrent(listOf(attempt.copy(windowEndMs = 12000)), attempt))
    }

    @Test fun repairRequiresContinuousVerifiedCoverage() {
        val mark = beacon("x", 10000, 1)
        val good = SegmentExecutionOutcome.Completed(
            cues = emptyList(), speakerUpdates = emptyList(), summary = "",
            resolvedTier = ScoutExecutionTier.LITE_AI, downgradedToLite = false,
            failedRanges = emptyList(), usedBisection = false,
            verifiedDialogueRanges = listOf(10 to 15), verifiedSilentRanges = listOf(15 to 20))
        assertTrue(SentinelPolicy.hasVerifiedRepairCoverage(mark, good))
        assertFalse(SentinelPolicy.hasVerifiedRepairCoverage(mark, good.copy(verifiedSilentRanges = listOf(16 to 20))))
        assertFalse(SentinelPolicy.hasVerifiedRepairCoverage(mark, good.copy(partialRanges = listOf(14 to 15))))
        assertFalse(SentinelPolicy.hasVerifiedRepairCoverage(mark, good.copy(failedRanges = listOf(14 to 15))))
    }
}
