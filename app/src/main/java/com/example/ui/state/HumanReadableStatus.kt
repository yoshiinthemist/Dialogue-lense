package com.example.ui.state

import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeaconStatus
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.CoverageSummary
import com.example.domain.pipeline.ReleasedPlaybackSnapshot
import com.example.domain.playback.PlaybackReadinessSnapshot
import com.example.domain.playback.PlaybackReadinessState
import com.example.domain.sentinel.SchedulerOutcomeState
import com.example.domain.sentinel.SentinelInspectorSnapshot

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
 * Translates structured playback readiness, coverage ledger state, rolling-window scheduler
 * outcomes, and Heal Beacon activity into calm, plain-language viewer states for Watch and Library.
 * Technical diagnostics remain accessible exclusively inside Studio -> Diagnostics.
 */
object HumanReadableStatus {

    fun resolve(
        project: DialogueLensProject?,
        scoutState: ScoutCaptionState,
        coverageSummary: CoverageSummary?,
        isOfflineReady: Boolean = false,
        readinessSnapshot: PlaybackReadinessSnapshot? = null,
        releaseSnapshot: ReleasedPlaybackSnapshot? = null,
        sentinelSnapshot: SentinelInspectorSnapshot? = null,
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
                    val label = "Preparing captions"
                    val detail = if (readinessSnapshot.willAutoResumeWhenReady) {
                        "Playback will resume automatically once captions are ready."
                    } else {
                        "Preparing upcoming dialogue for this section."
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
                        label = "Using fallback captions here",
                        detail = "Playing while upcoming dialogue continues preparing.",
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

        // 2. Active user-visible Heal Beacons (ignore QUEUED/MARKED, SUPERSEDED, CANCELLED)
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

        val attentionCount = project.healBeacons.count {
            !it.autoTriggered && it.status == HealBeaconStatus.NEEDS_ATTENTION
        }
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

        val schedState = sentinelSnapshot?.schedulerState
        if (schedState != null) {
            when (schedState.outcomeState) {
                SchedulerOutcomeState.WINDOW_READY_IDLE -> {
                    val currentPlayheadReady = if (readinessSnapshot != null) {
                        !readinessSnapshot.isBufferingHoldActive && readinessSnapshot.contiguousReleasedLeadMs > 0L
                    } else {
                        true
                    }
                    if (!currentPlayheadReady && schedState.continuousVerifiedLeadMs <= 0L) {
                        return HumanReadableReadiness(
                            label = "Preparing captions",
                            detail = "Dialogue exists elsewhere, but the current window is not yet prepared.",
                            tone = ReadinessTone.ACTIVE,
                            isInProgress = true,
                        )
                    }
                    val spanSec = ((schedState.boundaryEndMs - schedState.acknowledgedViewerPosMs) / 1000L).coerceAtLeast(0L)
                    val leadSec = (maxOf(schedState.continuousVerifiedLeadMs, readinessSnapshot?.contiguousReleasedLeadMs ?: 0L) / 1000L)
                        .coerceAtLeast(0L)
                    val reserveSec = (schedState.viewingTimeReserveMs / 1000L).coerceAtLeast(0L)
                    val speedSuffix = if (kotlin.math.abs(schedState.playbackSpeed - 1.0f) >= 0.05f && reserveSec > 0L) {
                        " (~${reserveSec}s at ${schedState.playbackSpeed}x)"
                    } else {
                        ""
                    }
                    val spanDesc = when {
                        schedState.boundaryEndMs > 0L && spanSec in 1L..174L ->
                            "Next ${spanSec}s prepared to end$speedSuffix."
                        leadSec > 0L ->
                            "Prepared ${leadSec}s ahead (up to 3-minute window)$speedSuffix."
                        else ->
                            "Upcoming 3-minute window prepared$speedSuffix."
                    }
                    return HumanReadableReadiness(
                        label = "Current window ready",
                        detail = "$spanDesc More loads automatically as you watch.",
                        tone = ReadinessTone.READY,
                        isInProgress = false,
                    )
                }
                SchedulerOutcomeState.WAITING_FOR_EVIDENCE_OR_CAPABILITY -> {
                    return HumanReadableReadiness(
                        label = "Waiting for captions",
                        detail = schedState.idleReason.ifBlank {
                            "Waiting for caption evidence or cloud capability."
                        },
                        tone = ReadinessTone.WARNING,
                        isInProgress = false,
                    )
                }
                SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY -> {
                    return HumanReadableReadiness(
                        label = "Some dialogue is unavailable",
                        detail = schedState.idleReason.ifBlank {
                            "Bounded recovery finished with an unresolved section. You can retry or attach captions."
                        },
                        tone = ReadinessTone.WARNING,
                        isInProgress = false,
                    )
                }
                SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE -> {
                    val offlineCapable = isOfflineReady ||
                        project.videoSource.kind == VideoSourceKind.DEMO_SCENARIO ||
                        project.videoSource.kind == VideoSourceKind.LOCAL_MP4
                    return if (offlineCapable) {
                        HumanReadableReadiness(
                            label = "Ready offline",
                            detail = "Full video captions are verified and saved.",
                            tone = ReadinessTone.OFFLINE,
                        )
                    } else {
                        HumanReadableReadiness(
                            label = "Ready",
                            detail = "Full video captions and speakers are verified.",
                            tone = ReadinessTone.READY,
                        )
                    }
                }
                SchedulerOutcomeState.ACTIVE -> Unit
            }
        }

        val isScoutingNow = scoutState is ScoutCaptionState.FetchingCaptions ||
            scoutState is ScoutCaptionState.AiTranscribing
        if (isScoutingNow) {
            val hasDialogue = project.dialogueEvents.isNotEmpty()
            return if (hasDialogue) {
                HumanReadableReadiness(
                    label = "Checking upcoming dialogue",
                    detail = "Upcoming lines are being checked smoothly within the next 3 minutes.",
                    tone = ReadinessTone.ACTIVE,
                    isInProgress = true,
                )
            } else {
                HumanReadableReadiness(
                    label = "Preparing captions",
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
                detail = "A section could not be prepared yet. You can retry or attach captions in Studio.",
                tone = ReadinessTone.WARNING,
            )
        }

        if (readinessSnapshot?.isUsingUnverifiedFallback == true &&
            (releaseSnapshot?.fallbackReleaseCount ?: 0) > 0
        ) {
            return HumanReadableReadiness(
                label = "Using fallback captions here",
                detail = "Showing preliminary captions while checking upcoming dialogue.",
                tone = ReadinessTone.WARNING,
            )
        }

        if (project.dialogueEvents.isEmpty() && coverageSummary != null &&
            coverageSummary.verifiedSilenceMs > 0L && coverageSummary.unresolvedGapCount == 0
        ) {
            return HumanReadableReadiness(
                label = "Ready",
                detail = "No spoken dialogue detected in the checked portion.",
                tone = ReadinessTone.READY,
            )
        }

        val isComplete = coverageSummary == null ||
            coverageSummary.isFullyVerified ||
            project.subtitleSource == SubtitleSourceKind.BUILT_IN_DEMO ||
            project.subtitleSource == SubtitleSourceKind.UPLOADED_FILE ||
            project.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK

        val currentWindowReady = when {
            readinessSnapshot != null -> !readinessSnapshot.isBufferingHoldActive && readinessSnapshot.contiguousReleasedLeadMs > 0L
            schedState != null -> schedState.continuousVerifiedLeadMs > 0L
            else -> false
        }
        if (!isComplete && project.dialogueEvents.isNotEmpty()) {
            return if (currentWindowReady) {
                val leadSec = ((readinessSnapshot?.contiguousReleasedLeadMs ?: schedState?.continuousVerifiedLeadMs ?: 0L) / 1000L)
                    .coerceAtLeast(0L)
                val detail = if (leadSec > 0L) {
                    "Captions are ready ${leadSec}s ahead and prepare as you watch."
                } else {
                    "Captions are ready here and prepare ahead as you watch."
                }
                HumanReadableReadiness(
                    label = "Current window ready",
                    detail = detail,
                    tone = ReadinessTone.READY,
                )
            } else {
                HumanReadableReadiness(
                    label = "Preparing captions",
                    detail = "Dialogue exists elsewhere in the project, but the current window is not yet prepared.",
                    tone = ReadinessTone.ACTIVE,
                    isInProgress = true,
                )
            }
        }

        if (project.dialogueEvents.isEmpty()) {
            return HumanReadableReadiness(
                label = "Preparing captions",
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
                detail = "Captions are saved with this video.",
                tone = ReadinessTone.OFFLINE,
            )
        } else {
            HumanReadableReadiness(
                label = "Ready",
                detail = "Captions and speakers are synced.",
                tone = ReadinessTone.READY,
            )
        }
    }
}
