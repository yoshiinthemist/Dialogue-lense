package com.example.domain.analysis

import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus

/** On-demand single repair worker: explicit user marks before automatic marks, independently of playhead. */
object SentinelPolicy {
    fun nextBeacon(beacons: List<HealBeacon>): HealBeacon? =
        beacons.asSequence().filter { it.status == HealBeaconStatus.MARKED }
            .minWithOrNull(compareBy<HealBeacon> { it.autoTriggered }.thenBy { it.createdAtEpochMs }.thenBy { it.id })

    fun isStillCurrent(beacons: List<HealBeacon>, attempt: HealBeacon): Boolean =
        beacons.any { it.id == attempt.id && it.status == HealBeaconStatus.HEALING &&
            it.updatedAtEpochMs == attempt.updatedAtEpochMs &&
            it.windowStartMs == attempt.windowStartMs && it.windowEndMs == attempt.windowEndMs }

    fun hasVerifiedRepairCoverage(beacon: HealBeacon, outcome: SegmentExecutionOutcome.Completed): Boolean {
        if (outcome.failedRanges.isNotEmpty() || outcome.partialRanges.isNotEmpty()) return false
        var cursor = beacon.windowStartMs
        for ((start, end) in (outcome.verifiedDialogueRanges + outcome.verifiedSilentRanges).sortedBy { it.first }) {
            val startMs = start.toLong() * 1000L
            val endMs = end.toLong() * 1000L
            if (endMs <= cursor) continue
            if (startMs > cursor) return false
            cursor = maxOf(cursor, endMs)
            if (cursor >= beacon.windowEndMs) return true
        }
        return false
    }
}
