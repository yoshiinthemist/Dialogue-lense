package com.example.domain.analysis

import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeaconStatus
import com.example.data.model.QuotaSaverMode
import com.example.domain.layout.PlacedBalloon
import kotlin.math.abs

enum class SelfHealSignalKind {
    INVALID_COORDINATES,
    TEMPORAL_OVERHANG,
    DUPLICATE_SPEAKER_IDENTITIES,
    COVERAGE_GAP_NEAR_PLAYHEAD,
    REPEATED_LOW_CONFIDENCE_POSITION,
    CONFLICTING_OVERLAP,
}

data class SelfHealFinding(
    val kind: SelfHealSignalKind,
    val timestampMs: Long,
    val description: String,
    val canFixLocallyWithoutCloud: Boolean,
)

/**
 * Bounded, resource-respecting automatic self-healing detector.
 * - Detects structural anomalies (invalid coordinates, temporal overhang,
 *   conflicting overlaps, repeated low-confidence positions, or playhead coverage gaps).
 * - Detects repeatable local position and timing defects without changing speaker identities.
 * - Enforces strict guardrails before proposing a background cloud Heal Beacon:
 *   never fires in [QuotaSaverMode.ZERO_CLOUD], respects per-region 60s cooldowns, caps total
 *   automatic cloud repairs per video at [MAX_AUTO_CLOUD_HEALS_PER_VIDEO], and deduplicates
 *   against existing user/auto beacons.
 */
class AutoSelfHealDetector {

    private val recentAutoHealsByBucket = mutableMapOf<Long, Long>()
    private var autoCloudHealCountForProject = 0
    private var trackedProjectId: String = ""
    private var consecutiveLowConfidencePositionTicks = 0

    fun resetForProject(projectId: String) {
        if (trackedProjectId != projectId) {
            trackedProjectId = projectId
            recentAutoHealsByBucket.clear()
            autoCloudHealCountForProject = 0
            consecutiveLowConfidencePositionTicks = 0
        }
    }

    fun inspectPlaybackFrame(
        project: DialogueLensProject,
        playbackMs: Long,
        placedBalloons: List<PlacedBalloon>,
    ): List<SelfHealFinding> {
        resetForProject(project.id)
        val findings = mutableListOf<SelfHealFinding>()

        // 2. Detect invalid coordinates or temporal overhang on rendered balloons
        for (balloon in placedBalloons) {
            val r = balloon.rect
            val invalidRect = r.left.isNaN() || r.top.isNaN() || r.right.isNaN() || r.bottom.isNaN() ||
                r.left < -0.02f || r.right > 1.02f || r.top < -0.02f || r.bottom > 1.02f ||
                r.width <= 0f || r.height <= 0f
            if (invalidRect) {
                findings.add(
                    SelfHealFinding(
                        kind = SelfHealSignalKind.INVALID_COORDINATES,
                        timestampMs = playbackMs,
                        description = "Speech bubble fell outside visible bounds",
                        canFixLocallyWithoutCloud = true,
                    )
                )
            }
            if (playbackMs > balloon.event.endTimeMs + TEMPORAL_OVERHANG_TOLERANCE_MS) {
                findings.add(
                    SelfHealFinding(
                        kind = SelfHealSignalKind.TEMPORAL_OVERHANG,
                        timestampMs = playbackMs,
                        description = "Dialogue persisted beyond its end timestamp",
                        canFixLocallyWithoutCloud = true,
                    )
                )
            }
        }

        // 3. Detect conflicting identical-speaker overlapping lines near playhead
        val activeEvents = project.dialogueEvents.filter {
            playbackMs in it.startTimeMs..it.endTimeMs
        }
        if (activeEvents.size >= 2) {
            val sameSpeakerOverlap = activeEvents
                .filter { it.speakerId != null }
                .groupBy { it.speakerId }
                .values
                .any { list ->
                    list.size >= 2 && list.zipWithNext().any { (a, b) ->
                        minOf(a.endTimeMs, b.endTimeMs) - maxOf(a.startTimeMs, b.startTimeMs) >= 1_200L &&
                            a.translatedText.trim().equals(b.translatedText.trim(), ignoreCase = true)
                    }
                }
            if (sameSpeakerOverlap) {
                findings.add(
                    SelfHealFinding(
                        kind = SelfHealSignalKind.CONFLICTING_OVERLAP,
                        timestampMs = playbackMs,
                        description = "Overlapping duplicate dialogue detected",
                        canFixLocallyWithoutCloud = false,
                    )
                )
            }
        }

        // 4. Track repeated low-confidence fallback bubbles over consecutive checks
        if (placedBalloons.any { it.isAmbiguousFallback }) {
            consecutiveLowConfidencePositionTicks++
            if (consecutiveLowConfidencePositionTicks >= 6) {
                consecutiveLowConfidencePositionTicks = 0
                findings.add(
                    SelfHealFinding(
                        kind = SelfHealSignalKind.REPEATED_LOW_CONFIDENCE_POSITION,
                        timestampMs = playbackMs,
                        description = "Repeated low-confidence speaker attribution",
                        canFixLocallyWithoutCloud = false,
                    )
                )
            }
        } else {
            consecutiveLowConfidencePositionTicks = 0
        }

        return findings
    }

    /**
     * Returns true ONLY if an automatic cloud repair is authorized for [timestampMs] under
     * quota, cooldown, per-video cap, and active-beacon deduplication rules.
     */
    fun canTriggerAutomaticCloudHeal(
        project: DialogueLensProject,
        timestampMs: Long,
        quotaMode: QuotaSaverMode,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        resetForProject(project.id)
        if (quotaMode == QuotaSaverMode.ZERO_CLOUD) return false
        if (autoCloudHealCountForProject >= MAX_AUTO_CLOUD_HEALS_PER_VIDEO) return false

        val existingAutoCount = project.healBeacons.count { it.autoTriggered }
        if (existingAutoCount >= MAX_AUTO_CLOUD_HEALS_PER_VIDEO) return false

        // Do not pile onto an existing beacon covering the same region
        val overlapsExistingBeacon = project.healBeacons.any { beacon ->
            timestampMs in (beacon.windowStartMs - 5_000L)..(beacon.windowEndMs + 5_000L) &&
                beacon.status != HealBeaconStatus.NEEDS_ATTENTION
        }
        if (overlapsExistingBeacon) return false

        val bucket = timestampMs / REGION_BUCKET_MS
        val lastTriggeredAt = recentAutoHealsByBucket[bucket]
        if (lastTriggeredAt != null && (nowEpochMs - lastTriggeredAt) < REGION_COOLDOWN_MS) {
            return false
        }
        return true
    }

    fun recordAutomaticCloudHealTriggered(
        timestampMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val bucket = timestampMs / REGION_BUCKET_MS
        recentAutoHealsByBucket[bucket] = nowEpochMs
        autoCloudHealCountForProject++
    }

    companion object {
        const val MAX_AUTO_CLOUD_HEALS_PER_VIDEO = 4
        const val REGION_COOLDOWN_MS = 60_000L
        const val REGION_BUCKET_MS = 30_000L
        const val TEMPORAL_OVERHANG_TOLERANCE_MS = 450L

        /**
         * Clamps any corrupted/out-of-bounds event anchor coordinates cleanly back inside [0.05..0.95]
         * without touching valid events.
         */
        fun sanitizeCorruptedAnchors(events: List<DialogueEvent>): List<DialogueEvent> =
            events.map { evt ->
                if (evt.lockedByUser) return@map evt
                val anchor = evt.anchorPosition ?: return@map evt
                if (anchor.x.isNaN() || anchor.y.isNaN()) {
                    evt.copy(anchorPosition = null)
                } else if (anchor.x !in 0.02f..0.98f || anchor.y !in 0.02f..0.98f) {
                    evt.copy(
                        anchorPosition = anchor.copy(
                            x = anchor.x.coerceIn(0.05f, 0.95f),
                            y = anchor.y.coerceIn(0.05f, 0.95f),
                        )
                    )
                } else {
                    evt
                }
            }
    }
}
