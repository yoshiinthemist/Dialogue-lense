package com.example.domain.playback

import com.example.data.model.CoverageStatus
import com.example.domain.analysis.TranscriptCoverageLedger
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Explicit 12-state playback readiness machine (DialogueLens 2.0 Section 10).
 * Separates user-intended play/pause from system buffering holds and media failures.
 */
enum class PlaybackReadinessState {
    IDLE,
    LOADING_MEDIA,
    BUFFERING_INITIAL_WINDOW,
    READY_PAUSED,
    PLAYING,
    PAUSED_BY_USER,
    SEEKING,
    BUFFERING_FOR_SEEK,
    BUFFERING_MID_PLAYBACK,
    READY_TO_AUTO_RESUME,
    WATCHING_WITHOUT_FULL_COVERAGE,
    PLAYBACK_OR_ANALYSIS_ERROR,
}

enum class UserPlaybackIntent {
    PLAY,
    PAUSE,
}

enum class BufferingFailureAction {
    RETRY,
    WATCH_WITHOUT_SUBTITLES,
    ATTACH_SUBTITLES,
}

/**
 * One-shot directive emitted by [PlaybackReadinessController] when readiness becomes satisfied
 * and the player must resume playback at the exact requested target timestamp.
 */
data class AutoResumeDirective(
    val id: Long,
    val targetPositionMs: Long,
    val reason: String,
    val seekGeneration: Long,
)

/**
 * Explicit episode-scoped bypass created when the viewer chooses "Watch Now" during a single
 * buffering hold. Replaces broad multi-second global suppression windows.
 */
data class WatchNowBypassScope(
    val episodeId: Long,
    val createdAtPositionMs: Long,
    val seekGeneration: Long,
    val expiresOnNextVerifiedWindowOrSeek: Boolean = true,
)

/**
 * Snapshot of real timing, coverage lead, speed-scaled thresholds, seek acknowledgment, and
 * auto-resume telemetry exposed to Studio Diagnostics (Section 32).
 */
data class PlaybackTimingDiagnostics(
    val readinessState: PlaybackReadinessState = PlaybackReadinessState.IDLE,
    val userIntent: UserPlaybackIntent = UserPlaybackIntent.PAUSE,
    val playbackSpeed: Float = 1.0f,
    val currentPositionMs: Long = 0L,
    val targetPositionMs: Long = 0L,
    val knownDurationMs: Long = 0L,
    val contiguousVerifiedLeadMs: Long = 0L,
    val requiredInitialLeadMs: Long = 7_000L,
    val requiredSeekLeadMs: Long = 7_000L,
    val requiredResumeLeadMs: Long = 7_000L,
    val midPlaybackBufferThresholdMs: Long = 2_000L,
    val healthyLeadTargetMs: Long = 15_000L,
    val seekGeneration: Long = 0L,
    val acknowledgedSeekGeneration: Long = 0L,
    val isSeekInFlight: Boolean = false,
    val bufferingEpisodeCount: Int = 0,
    val bufferingStartedAtEpochMs: Long = 0L,
    val lastBufferingDurationMs: Long = 0L,
    val autoResumeCount: Int = 0,
    val watchNowBypassActive: Boolean = false,
    val waitTimedOut: Boolean = false,
    val statusDetail: String = "",
)

data class PlaybackReadinessSnapshot(
    val state: PlaybackReadinessState = PlaybackReadinessState.IDLE,
    val userIntent: UserPlaybackIntent = UserPlaybackIntent.PAUSE,
    val targetPositionMs: Long = 0L,
    val contiguousVerifiedLeadMs: Long = 0L,
    val requiredLeadMs: Long = 7_000L,
    val isBufferingHoldActive: Boolean = false,
    val isWatchNowBypassActive: Boolean = false,
    val waitTimedOut: Boolean = false,
    val failureReason: String = "",
    val availableRecoveryActions: List<BufferingFailureAction> = emptyList(),
    val pendingAutoResume: AutoResumeDirective? = null,
    val diagnostics: PlaybackTimingDiagnostics = PlaybackTimingDiagnostics(),
) {
    /**
     * True strictly when the user deliberately paused playback (NOT during buffering or seeks).
     * Controls Watch-mode "Heal this moment" visibility (Section 10 & Section 24).
     */
    val isIntentionallyPausedByUser: Boolean
        get() = state == PlaybackReadinessState.PAUSED_BY_USER ||
            (state == PlaybackReadinessState.READY_PAUSED && userIntent == UserPlaybackIntent.PAUSE)

    /**
     * True when playback is holding for verified dialogue coverage and should promise automatic
     * resumption ONLY if the user's intent is [UserPlaybackIntent.PLAY].
     */
    val willAutoResumeWhenReady: Boolean
        get() = isBufferingHoldActive && userIntent == UserPlaybackIntent.PLAY && !waitTimedOut
}

/**
 * Single authoritative controller for playback readiness, speed-scaled adaptive buffering,
 * seek generation acknowledgment, exact one-shot auto-resumption, and bounded failure recovery
 * (DialogueLens 2.0 Part III — Sections 10, 11, 12, 13, 14).
 *
 * Works identically across YouTube, Web Stream, Local Video, and Demo Scenario sources.
 */
class PlaybackReadinessController(
    private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
) {
    private var state: PlaybackReadinessState = PlaybackReadinessState.IDLE
    private var userIntent: UserPlaybackIntent = UserPlaybackIntent.PAUSE
    private var playbackSpeed: Float = 1.0f
    private var currentPositionMs: Long = 0L
    private var targetPositionMs: Long = 0L
    private var knownDurationMs: Long = 0L
    private var seekGeneration: Long = 0L
    private var acknowledgedSeekGeneration: Long = 0L
    private var bufferingEpisodeId: Long = 0L
    private var bufferingStartedAtEpochMs: Long = 0L
    private var lastBufferingDurationMs: Long = 0L
    private var autoResumeSeq: Long = 0L
    private var autoResumeCount: Int = 0
    private var pendingAutoResume: AutoResumeDirective? = null
    private var activeWatchNowBypass: WatchNowBypassScope? = null
    private var waitTimedOut: Boolean = false
    private var failureReason: String = ""
    private var lastContiguousLeadMs: Long = 0L
    private var requiresCoverageGate: Boolean = true

    fun resetForMedia(
        initialPositionMs: Long,
        durationMs: Long,
        requiresCoverageGate: Boolean,
        initialIntent: UserPlaybackIntent = UserPlaybackIntent.PAUSE,
        playbackSpeed: Float = 1.0f,
    ): PlaybackReadinessSnapshot {
        this.requiresCoverageGate = requiresCoverageGate
        this.userIntent = initialIntent
        this.playbackSpeed = playbackSpeed.coerceIn(0.25f, 3.0f)
        this.currentPositionMs = initialPositionMs.coerceAtLeast(0L)
        this.targetPositionMs = initialPositionMs.coerceAtLeast(0L)
        this.knownDurationMs = durationMs.coerceAtLeast(0L)
        this.seekGeneration = 0L
        this.acknowledgedSeekGeneration = 0L
        this.bufferingEpisodeId = 0L
        this.bufferingStartedAtEpochMs = 0L
        this.lastBufferingDurationMs = 0L
        this.pendingAutoResume = null
        this.activeWatchNowBypass = null
        this.waitTimedOut = false
        this.failureReason = ""
        this.lastContiguousLeadMs = 0L
        this.state = if (requiresCoverageGate) {
            PlaybackReadinessState.LOADING_MEDIA
        } else {
            PlaybackReadinessState.READY_PAUSED
        }
        return snapshot()
    }

    fun setPlaybackSpeed(speed: Float): PlaybackReadinessSnapshot {
        playbackSpeed = speed.coerceIn(0.25f, 3.0f)
        return snapshot()
    }

    fun updateDuration(durationMs: Long) {
        if (durationMs > 0L) {
            knownDurationMs = durationMs
        }
    }

    /**
     * Records an explicit user Play request at [positionMs].
     * Preserves [positionMs] verbatim (never rewinds a resumed position back to 0).
     */
    fun onUserPlayRequested(
        positionMs: Long,
        ledger: TranscriptCoverageLedger,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PlaybackReadinessSnapshot {
        userIntent = UserPlaybackIntent.PLAY
        val pos = positionMs.coerceAtLeast(0L)
        currentPositionMs = pos
        targetPositionMs = pos
        waitTimedOut = false
        failureReason = ""

        if (!requiresCoverageGate) {
            state = PlaybackReadinessState.PLAYING
            return snapshot()
        }

        val lead = ledger.continuousVerifiedLeadFrom(pos)
        lastContiguousLeadMs = lead
        val reqInitial = requiredInitialOrSeekLeadMs(pos)
        if (lead >= reqInitial || isAtOrPastVerifiedTail(pos, lead)) {
            activeWatchNowBypass = null
            state = PlaybackReadinessState.PLAYING
            return snapshot()
        }

        if (activeWatchNowBypass != null && activeWatchNowBypass!!.seekGeneration == seekGeneration) {
            state = PlaybackReadinessState.WATCHING_WITHOUT_FULL_COVERAGE
            return snapshot()
        }

        startBufferingEpisode(
            newState = if (bufferingEpisodeId == 0L) {
                PlaybackReadinessState.BUFFERING_INITIAL_WINDOW
            } else {
                PlaybackReadinessState.BUFFERING_MID_PLAYBACK
            },
            targetMs = pos,
            nowEpochMs = nowEpochMs,
        )
        return snapshot()
    }

    /**
     * Records an explicit user Pause request. Immediately cancels any pending auto-resume so
     * DialogueLens NEVER overrides an intentional user pause.
     */
    fun onUserPauseRequested(positionMs: Long = currentPositionMs): PlaybackReadinessSnapshot {
        userIntent = UserPlaybackIntent.PAUSE
        currentPositionMs = positionMs.coerceAtLeast(0L)
        targetPositionMs = currentPositionMs
        pendingAutoResume = null
        waitTimedOut = false
        state = PlaybackReadinessState.PAUSED_BY_USER
        return snapshot()
    }

    /**
     * Records a user seek to [requestedTargetMs]. Increments [seekGeneration], clears any prior
     * Watch Now bypass, and enters [PlaybackReadinessState.SEEKING] or [PlaybackReadinessState.BUFFERING_FOR_SEEK].
     */
    fun onUserSeekRequested(
        requestedTargetMs: Long,
        ledger: TranscriptCoverageLedger,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PlaybackReadinessSnapshot {
        val clamped = if (knownDurationMs > 0L) {
            requestedTargetMs.coerceIn(0L, knownDurationMs)
        } else {
            requestedTargetMs.coerceAtLeast(0L)
        }
        seekGeneration++
        currentPositionMs = clamped
        targetPositionMs = clamped
        pendingAutoResume = null
        activeWatchNowBypass = null
        waitTimedOut = false
        failureReason = ""

        if (!requiresCoverageGate) {
            state = PlaybackReadinessState.SEEKING
            return snapshot()
        }

        val lead = ledger.continuousVerifiedLeadFrom(clamped)
        lastContiguousLeadMs = lead
        val reqSeekLead = requiredInitialOrSeekLeadMs(clamped)
        if (lead >= reqSeekLead || isAtOrPastVerifiedTail(clamped, lead)) {
            state = PlaybackReadinessState.SEEKING
        } else {
            startBufferingEpisode(
                newState = PlaybackReadinessState.BUFFERING_FOR_SEEK,
                targetMs = clamped,
                nowEpochMs = nowEpochMs,
            )
        }
        return snapshot()
    }

    /**
     * Acknowledges that the underlying media player has actually landed near [reportedPositionMs]
     * for the active seek generation. Rejects stale pre-seek ticks until acknowledgment occurs.
     */
    fun onPlayerPositionSample(
        reportedPositionMs: Long,
        playerIsPlaying: Boolean,
        ledger: TranscriptCoverageLedger,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PlaybackReadinessSnapshot {
        val safePos = reportedPositionMs.coerceAtLeast(0L)
        val isSeekPendingAck = seekGeneration > acknowledgedSeekGeneration
        if (isSeekPendingAck) {
            if (abs(safePos - targetPositionMs) <= SEEK_ACK_TOLERANCE_MS) {
                acknowledgedSeekGeneration = seekGeneration
                currentPositionMs = safePos
            } else {
                // Ignore stale pre-seek position sample while player is still moving to targetPositionMs
                return evaluateCoverage(ledger, nowEpochMs)
            }
        } else {
            currentPositionMs = safePos
            if (state == PlaybackReadinessState.PLAYING ||
                state == PlaybackReadinessState.WATCHING_WITHOUT_FULL_COVERAGE
            ) {
                targetPositionMs = safePos
            }
        }

        if (!requiresCoverageGate) {
            state = when {
                userIntent == UserPlaybackIntent.PAUSE -> PlaybackReadinessState.PAUSED_BY_USER
                playerIsPlaying -> PlaybackReadinessState.PLAYING
                else -> PlaybackReadinessState.READY_PAUSED
            }
            return snapshot()
        }

        val lead = ledger.continuousVerifiedLeadFrom(currentPositionMs)
        lastContiguousLeadMs = lead

        // If user chose Watch Now and playback has now re-entered verified coverage, clear the temporary bypass
        val bypass = activeWatchNowBypass
        if (bypass != null && lead >= requiredInitialOrSeekLeadMs(currentPositionMs)) {
            activeWatchNowBypass = null
            if (state == PlaybackReadinessState.WATCHING_WITHOUT_FULL_COVERAGE && userIntent == UserPlaybackIntent.PLAY) {
                state = PlaybackReadinessState.PLAYING
            }
        }

        // Check mid-playback buffer depletion while actively playing
        if (state == PlaybackReadinessState.PLAYING && userIntent == UserPlaybackIntent.PLAY) {
            val triggerThreshold = midPlaybackBufferTriggerMs(currentPositionMs)
            if (lead < triggerThreshold && !isAtOrPastVerifiedTail(currentPositionMs, lead)) {
                startBufferingEpisode(
                    newState = PlaybackReadinessState.BUFFERING_MID_PLAYBACK,
                    targetMs = currentPositionMs,
                    nowEpochMs = nowEpochMs,
                )
                return snapshot()
            }
        }

        if (state == PlaybackReadinessState.SEEKING) {
            val reqLead = requiredInitialOrSeekLeadMs(targetPositionMs)
            state = when {
                lead < reqLead && !isAtOrPastVerifiedTail(targetPositionMs, lead) -> {
                    startBufferingEpisode(
                        newState = PlaybackReadinessState.BUFFERING_FOR_SEEK,
                        targetMs = targetPositionMs,
                        nowEpochMs = nowEpochMs,
                    )
                    state
                }
                userIntent == UserPlaybackIntent.PLAY -> {
                    emitAutoResumeDirective("Seek target verified and ready")
                    PlaybackReadinessState.READY_TO_AUTO_RESUME
                }
                else -> PlaybackReadinessState.PAUSED_BY_USER
            }
        }

        return evaluateCoverage(ledger, nowEpochMs)
    }

    /**
     * Re-evaluates readiness whenever the coverage ledger advances or a periodic tick occurs.
     * Emits an exact one-shot [AutoResumeDirective] the moment verified lead satisfies the
     * speed-scaled threshold AND [userIntent] is [UserPlaybackIntent.PLAY].
     */
    fun evaluateCoverage(
        ledger: TranscriptCoverageLedger,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PlaybackReadinessSnapshot {
        if (!requiresCoverageGate) return snapshot()

        val evalPos = if (isBufferingState(state) || state == PlaybackReadinessState.SEEKING) {
            targetPositionMs
        } else {
            currentPositionMs
        }
        val lead = ledger.continuousVerifiedLeadFrom(evalPos)
        lastContiguousLeadMs = lead

        if (isBufferingState(state)) {
            val required = requiredLeadForState(state, evalPos)
            if (lead >= required || isAtOrPastVerifiedTail(evalPos, lead)) {
                lastBufferingDurationMs = if (bufferingStartedAtEpochMs > 0L) {
                    (nowEpochMs - bufferingStartedAtEpochMs).coerceAtLeast(0L)
                } else {
                    0L
                }
                waitTimedOut = false
                failureReason = ""
                if (userIntent == UserPlaybackIntent.PLAY) {
                    emitAutoResumeDirective("Verified lead ready (${lead / 1000L}s)")
                    state = PlaybackReadinessState.READY_TO_AUTO_RESUME
                } else {
                    state = PlaybackReadinessState.READY_PAUSED
                }
                return snapshot()
            }

            // Detect failed interval at target or bounded wait timeout
            val elapsedWait = if (bufferingStartedAtEpochMs > 0L) {
                (nowEpochMs - bufferingStartedAtEpochMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val failedAtTarget = ledger.coverageStatusAt(evalPos) == CoverageStatus.FAILED
            if (failedAtTarget || elapsedWait >= waitTimeoutMs) {
                waitTimedOut = true
                failureReason = if (failedAtTarget) {
                    "Dialogue preparation failed for this section."
                } else {
                    "Dialogue preparation is taking longer than expected."
                }
                state = PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR
            }
        }

        return snapshot()
    }

    /**
     * Consumes the pending one-shot [AutoResumeDirective] once the UI/ViewModel dispatches
     * the actual play/seek command, transitioning cleanly into [PlaybackReadinessState.PLAYING].
     */
    fun consumeAutoResumeDirective(directiveId: Long): PlaybackReadinessSnapshot {
        val current = pendingAutoResume
        if (current != null && current.id == directiveId) {
            pendingAutoResume = null
            if (userIntent == UserPlaybackIntent.PLAY) {
                state = PlaybackReadinessState.PLAYING
            }
        }
        return snapshot()
    }

    /**
     * Explicit user choice ("Watch Now") during a buffering hold. Scoped ONLY to the current
     * buffering episode; never suppresses future seeks or later unverified stretches.
     */
    fun onUserWatchNowBypass(): PlaybackReadinessSnapshot {
        activeWatchNowBypass = WatchNowBypassScope(
            episodeId = bufferingEpisodeId,
            createdAtPositionMs = targetPositionMs,
            seekGeneration = seekGeneration,
        )
        waitTimedOut = false
        failureReason = ""
        pendingAutoResume = null
        userIntent = UserPlaybackIntent.PLAY
        state = PlaybackReadinessState.WATCHING_WITHOUT_FULL_COVERAGE
        return snapshot()
    }

    /**
     * Explicit user retry after a bounded buffering timeout or failure.
     */
    fun onUserRetryBuffering(nowEpochMs: Long = System.currentTimeMillis()): PlaybackReadinessSnapshot {
        waitTimedOut = false
        failureReason = ""
        activeWatchNowBypass = null
        startBufferingEpisode(
            newState = if (targetPositionMs <= 1_000L) {
                PlaybackReadinessState.BUFFERING_INITIAL_WINDOW
            } else {
                PlaybackReadinessState.BUFFERING_FOR_SEEK
            },
            targetMs = targetPositionMs,
            nowEpochMs = nowEpochMs,
        )
        return snapshot()
    }

    fun onMediaOrAnalysisError(reason: String): PlaybackReadinessSnapshot {
        failureReason = reason.ifBlank { "Unable to prepare dialogue for this section." }
        pendingAutoResume = null
        state = PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR
        return snapshot()
    }

    // -------------------------------------------------------------------------
    // Speed-scaled adaptive thresholds (Section 11)
    // -------------------------------------------------------------------------

    fun requiredInitialOrSeekLeadMs(positionMs: Long): Long =
        capToRemaining(scaleBySpeed(BASE_START_AND_RESUME_LEAD_MS), positionMs)

    fun midPlaybackBufferTriggerMs(positionMs: Long): Long =
        capToRemaining(scaleBySpeed(BASE_MID_PLAYBACK_TRIGGER_MS), positionMs)

    fun healthyLeadTargetMs(positionMs: Long): Long =
        capToRemaining(scaleBySpeed(BASE_HEALTHY_LEAD_MS), positionMs)

    fun comfortableLeadTargetMs(positionMs: Long): Long =
        capToRemaining(scaleBySpeed(BASE_COMFORTABLE_LEAD_MS), positionMs)

    private fun requiredLeadForState(s: PlaybackReadinessState, positionMs: Long): Long = when (s) {
        PlaybackReadinessState.BUFFERING_INITIAL_WINDOW,
        PlaybackReadinessState.BUFFERING_FOR_SEEK,
        PlaybackReadinessState.BUFFERING_MID_PLAYBACK -> requiredInitialOrSeekLeadMs(positionMs)
        else -> midPlaybackBufferTriggerMs(positionMs)
    }

    private fun scaleBySpeed(baseMs: Long): Long =
        (baseMs.toDouble() * playbackSpeed.coerceIn(0.5f, 3.0f)).roundToLong()

    private fun capToRemaining(desiredMs: Long, positionMs: Long): Long {
        if (knownDurationMs <= 0L) {
            // Unknown duration: require at least a bounded opening slice so buffering never hangs
            return desiredMs.coerceAtMost(UNKNOWN_DURATION_MAX_REQUIRED_LEAD_MS)
        }
        val remaining = (knownDurationMs - positionMs).coerceAtLeast(0L)
        if (remaining <= END_OF_MEDIA_TOLERANCE_MS) return 0L
        return minOf(desiredMs, (remaining - 500L).coerceAtLeast(MIN_SHORT_VIDEO_LEAD_MS.coerceAtMost(remaining)))
    }

    private fun isAtOrPastVerifiedTail(positionMs: Long, leadMs: Long): Boolean {
        if (knownDurationMs <= 0L) return false
        val remaining = (knownDurationMs - positionMs).coerceAtLeast(0L)
        return remaining <= END_OF_MEDIA_TOLERANCE_MS || leadMs >= (remaining - END_OF_MEDIA_TOLERANCE_MS).coerceAtLeast(0L)
    }

    private fun startBufferingEpisode(
        newState: PlaybackReadinessState,
        targetMs: Long,
        nowEpochMs: Long,
    ) {
        bufferingEpisodeId++
        bufferingStartedAtEpochMs = nowEpochMs
        targetPositionMs = targetMs.coerceAtLeast(0L)
        pendingAutoResume = null
        waitTimedOut = false
        failureReason = ""
        state = newState
    }

    private fun emitAutoResumeDirective(reason: String) {
        autoResumeSeq++
        autoResumeCount++
        pendingAutoResume = AutoResumeDirective(
            id = autoResumeSeq,
            targetPositionMs = targetPositionMs,
            reason = reason,
            seekGeneration = seekGeneration,
        )
    }

    private fun isBufferingState(s: PlaybackReadinessState): Boolean =
        s == PlaybackReadinessState.BUFFERING_INITIAL_WINDOW ||
            s == PlaybackReadinessState.BUFFERING_FOR_SEEK ||
            s == PlaybackReadinessState.BUFFERING_MID_PLAYBACK

    fun snapshot(): PlaybackReadinessSnapshot {
        val evalPos = if (isBufferingState(state) || state == PlaybackReadinessState.SEEKING) {
            targetPositionMs
        } else {
            currentPositionMs
        }
        val reqLead = requiredLeadForState(state, evalPos)
        val actions = if (state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR) {
            listOf(
                BufferingFailureAction.RETRY,
                BufferingFailureAction.WATCH_WITHOUT_SUBTITLES,
                BufferingFailureAction.ATTACH_SUBTITLES,
            )
        } else {
            emptyList()
        }
        val diag = PlaybackTimingDiagnostics(
            readinessState = state,
            userIntent = userIntent,
            playbackSpeed = playbackSpeed,
            currentPositionMs = currentPositionMs,
            targetPositionMs = targetPositionMs,
            knownDurationMs = knownDurationMs,
            contiguousVerifiedLeadMs = lastContiguousLeadMs,
            requiredInitialLeadMs = requiredInitialOrSeekLeadMs(evalPos),
            requiredSeekLeadMs = requiredInitialOrSeekLeadMs(evalPos),
            requiredResumeLeadMs = requiredInitialOrSeekLeadMs(evalPos),
            midPlaybackBufferThresholdMs = midPlaybackBufferTriggerMs(evalPos),
            healthyLeadTargetMs = healthyLeadTargetMs(evalPos),
            seekGeneration = seekGeneration,
            acknowledgedSeekGeneration = acknowledgedSeekGeneration,
            isSeekInFlight = seekGeneration > acknowledgedSeekGeneration,
            bufferingEpisodeCount = bufferingEpisodeId.toInt(),
            bufferingStartedAtEpochMs = bufferingStartedAtEpochMs,
            lastBufferingDurationMs = lastBufferingDurationMs,
            autoResumeCount = autoResumeCount,
            watchNowBypassActive = activeWatchNowBypass != null,
            waitTimedOut = waitTimedOut,
            statusDetail = failureReason,
        )
        return PlaybackReadinessSnapshot(
            state = state,
            userIntent = userIntent,
            targetPositionMs = targetPositionMs,
            contiguousVerifiedLeadMs = lastContiguousLeadMs,
            requiredLeadMs = reqLead,
            isBufferingHoldActive = isBufferingState(state),
            isWatchNowBypassActive = activeWatchNowBypass != null,
            waitTimedOut = waitTimedOut,
            failureReason = failureReason,
            availableRecoveryActions = actions,
            pendingAutoResume = pendingAutoResume,
            diagnostics = diag,
        )
    }

    companion object {
        const val BASE_MID_PLAYBACK_TRIGGER_MS = 2_000L
        const val BASE_START_AND_RESUME_LEAD_MS = 7_000L
        const val BASE_HEALTHY_LEAD_MS = 15_000L
        const val BASE_COMFORTABLE_LEAD_MS = 30_000L
        const val UNKNOWN_DURATION_MAX_REQUIRED_LEAD_MS = 7_000L
        const val MIN_SHORT_VIDEO_LEAD_MS = 1_500L
        const val END_OF_MEDIA_TOLERANCE_MS = 1_200L
        const val SEEK_ACK_TOLERANCE_MS = 1_800L
        const val DEFAULT_WAIT_TIMEOUT_MS = 24_000L
    }
}
