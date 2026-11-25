package com.example.ui.state

import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeaconStatus
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.CoverageSummary

enum class ReadinessTone {
    READY,
    ACTIVE,
    WARNING,
    ATTENTION,
    OFFLINE,
}

data class HumanReadableReadiness(
    val label: String,
    val detail: String,
    val tone: ReadinessTone,
    val isInProgress: Boolean = false,
)

/**
 * Translates technical pipeline metrics (Scout segments, coverage intervals, model states) into
 * calm, plain-language viewer states for the Watch and Library interfaces. Technical diagnostics
 * remain accessible exclusively inside Studio -> Diagnostics.
 */
object HumanReadableStatus {

    fun resolve(
        project: DialogueLensProject?,
        scoutState: ScoutCaptionState,
        coverageSummary: CoverageSummary?,
        isOfflineReady: Boolean = false,
    ): HumanReadableReadiness {
        if (project == null) {
            return HumanReadableReadiness(
                label = "Ready",
                detail = "Choose a video to begin.",
                tone = ReadinessTone.READY,
            )
        }

        val activeHealingCount = project.healBeacons.count { it.status == HealBeaconStatus.HEALING }
        if (activeHealingCount > 0) {
            return HumanReadableReadiness(
                label = "Healing a moment",
                detail = if (activeHealingCount == 1) {
                    "Re-checking dialogue and speaker timing in the background."
                } else {
                    "Re-checking $activeHealingCount marked moments in the background."
                },
                tone = ReadinessTone.ACTIVE,
                isInProgress = true,
            )
        }

        val attentionCount = project.healBeacons.count { it.status == HealBeaconStatus.NEEDS_ATTENTION }
        if (attentionCount > 0) {
            return HumanReadableReadiness(
                label = "Needs attention",
                detail = if (attentionCount == 1) {
                    "1 marked moment could not be repaired automatically."
                } else {
                    "$attentionCount marked moments need your review."
                },
                tone = ReadinessTone.ATTENTION,
            )
        }

        val isScoutingNow = scoutState is ScoutCaptionState.FetchingCaptions ||
            scoutState is ScoutCaptionState.AiTranscribing
        if (isScoutingNow) {
            val hasDialogue = project.dialogueEvents.isNotEmpty()
            return if (hasDialogue) {
                HumanReadableReadiness(
                    label = "Analyzing ahead",
                    detail = "Upcoming dialogue is preparing smoothly as you watch.",
                    tone = ReadinessTone.ACTIVE,
                    isInProgress = true,
                )
            } else {
                HumanReadableReadiness(
                    label = "Preparing dialogue",
                    detail = "Listening and matching speakers for this video.",
                    tone = ReadinessTone.ACTIVE,
                    isInProgress = true,
                )
            }
        }

        val hasFailedSegments = (coverageSummary?.failedIntervalCount ?: 0) > 0 ||
            scoutState is ScoutCaptionState.Error
        if (hasFailedSegments) {
            return HumanReadableReadiness(
                label = "Some dialogue is unavailable",
                detail = "A section could not be analyzed yet. You can retry or mark moments in Studio.",
                tone = ReadinessTone.WARNING,
            )
        }

        if (project.dialogueEvents.isEmpty() && coverageSummary != null &&
            coverageSummary.verifiedSilenceMs > 0L && coverageSummary.unresolvedGapCount == 0
        ) {
            return HumanReadableReadiness(
                label = "Ready to watch",
                detail = "No spoken dialogue detected in the analyzed portion.",
                tone = ReadinessTone.READY,
            )
        }

        val isComplete = coverageSummary == null ||
            coverageSummary.isFullyVerified ||
            project.subtitleSource == SubtitleSourceKind.BUILT_IN_DEMO ||
            project.subtitleSource == SubtitleSourceKind.UPLOADED_FILE ||
            project.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK

        if (!isComplete && project.dialogueEvents.isNotEmpty()) {
            return HumanReadableReadiness(
                label = "Analyzing ahead",
                detail = "Dialogue is ready here and continues preparing as you watch.",
                tone = ReadinessTone.ACTIVE,
            )
        }

        if (project.dialogueEvents.isEmpty()) {
            return HumanReadableReadiness(
                label = "Preparing dialogue",
                detail = "Start playback or open Studio to import captions.",
                tone = ReadinessTone.ACTIVE,
            )
        }

        val offlineCapable = isOfflineReady ||
            project.videoSource.kind == VideoSourceKind.DEMO_SCENARIO ||
            project.videoSource.kind == VideoSourceKind.LOCAL_MP4

        return if (offlineCapable) {
            HumanReadableReadiness(
                label = "Ready offline",
                detail = "Dialogue is saved with this video.",
                tone = ReadinessTone.OFFLINE,
            )
        } else {
            HumanReadableReadiness(
                label = "Ready to watch",
                detail = "Dialogue and speakers are synced.",
                tone = ReadinessTone.READY,
            )
        }
    }
}
