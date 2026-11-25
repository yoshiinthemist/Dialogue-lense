package com.example.ui.state

import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeaconStatus
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.CoverageSummary
import com.example.domain.playback.PlaybackReadinessSnapshot
import com.example.domain.playback.PlaybackReadinessState

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
 * Translates structured playback readiness, coverage ledger state, and Heal Beacon activity into
 * calm, plain-language viewer states for the Watch and Library interfaces (Section 52).
 * Technical diagnostics remain accessible exclusively inside Studio -> Diagnostics.
 */
object HumanReadableStatus {

    fun resolve(
        project: DialogueLensProject?,
        scoutState: ScoutCaptionState,
        coverageSummary: CoverageSummary?,
        isOfflineReady: Boolean = false,
        readinessSnapshot: PlaybackReadinessSnapshot? = null,
    ): HumanReadableReadiness {
        if (project == null) {
            return HumanReadableReadiness(
                label = "Ready",
                detail = "Choose a video to begin.",
                tone = ReadinessTone.READY,
            )
        }

        // 1. Playback readiness buffering or error states take highest honesty precedence
        if (readinessSnapshot != null) {
            when (readinessSnapshot.state) {
                PlaybackReadinessState.BUFFERING_INITIAL_WINDOW,
                PlaybackReadinessState.BUFFERING_FOR_SEEK,
                PlaybackReadinessState.BUFFERING_MID_PLAYBACK -> {
                    val label = if (readinessSnapshot.state == PlaybackReadinessState.BUFFERING_FOR_SEEK) {
                        "Preparing this section"
                    } else {
                        "Preparing dialogue"
                    }
                    val detail = if (readinessSnapshot.willAutoResumeWhenReady) {
                        "Playback will resume automatically once dialogue is ready."
                    } else {
                        "Preparing verified dialogue for this section."
                    }
                    return HumanReadableReadiness(
                        label = label,
                        detail = detail,
                        tone = ReadinessTone.ACTIVE,
                        isInProgress = true,
                    )
                }
                PlaybackReadinessState.WATCHING_WITHOUT_FULL_COVERAGE -> {
                    return HumanReadableReadiness(
                        label = "Some dialogue is unavailable",
                        detail = "Playing while dialogue continues preparing for this section.",
                        tone = ReadinessTone.WARNING,
                        isInProgress = true,
                    )
                }
                PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR -> {
                    return HumanReadableReadiness(
                        label = "Some dialogue is unavailable",
                        detail = readinessSnapshot.failureReason.ifBlank {
                            "A section could not be prepared yet. You can retry or attach subtitles."
                        },
                        tone = ReadinessTone.WARNING,
                    )
                }
                else -> Unit
            }
        }

        // 2. Active user-visible Heal Beacons
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

        val attentionCount = project.healBeacons.count { !it.autoTriggered && it.status == HealBeaconStatus.NEEDS_ATTENTION }
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
