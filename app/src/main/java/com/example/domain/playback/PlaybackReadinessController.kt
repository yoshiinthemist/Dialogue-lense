package com.example.domain.playback

import com.example.domain.analysis.MediaTransport
import com.example.domain.analysis.PlaybackPhase
import com.example.domain.analysis.ReadinessDecision
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
        get() = state == PlaybackReadinessState.PAUSED_BY_USER

    /**
     * True when playback is holding for verified dialogue coverage and should promise automatic
     * resumption ONLY if the user's intent is [UserPlaybackIntent.PLAY].
     */
    val willAutoResumeWhenReady: Boolean
        get() = isBufferingHoldActive && userIntent == UserPlaybackIntent.PLAY && !waitTimedOut
}

/** Main-thread coordinator shared by all media adapters and Studio diagnostics. */
class PlaybackReadinessController(
    private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
) {
    var wantsPlay = false
        private set
    var pendingTargetMs: Long? = null
        private set
    var generation = 0
        private set
    private var explicitPause = false
    private var commandedPlaying = false
    private var waitingForCoverage = true
    private var hasStarted = false
    private var ended = false
    private var bypassEndMs: Long? = null
    private var waitingSinceMs: Long? = null
    private var lastPosition = 0L
    private var duration: Long? = null
    private var speed = 1f
    private var needsDialogue = true
    private var lead = 0L
    private var transport = MediaTransport.PAUSED
    private var lastNow = 0L
    private var waitTimedOut = false
    private var failureReason = ""
    private var state = PlaybackReadinessState.IDLE
    private var acknowledgedGeneration = 0
    private var episodeStarted: Long? = null
    private var episodeCount = 0
    private var previousWaitDuration = 0L
    private var resumeSeq = 0L
    private var resumesDispatched = 0
    private var directive: AutoResumeDirective? = null

    fun resetForMedia(
        initialPositionMs: Long,
        durationMs: Long,
        requiresCoverageGate: Boolean,
        initialIntent: UserPlaybackIntent = UserPlaybackIntent.PAUSE,
        playbackSpeed: Float = 1f,
    ): PlaybackReadinessSnapshot {
        wantsPlay = initialIntent == UserPlaybackIntent.PLAY
        pendingTargetMs = null
        generation = 0
        acknowledgedGeneration = 0
        explicitPause = false
        commandedPlaying = false
        waitingForCoverage = true
        hasStarted = false
        ended = false
        bypassEndMs = null
        waitingSinceMs = null
        lastPosition = initialPositionMs.coerceAtLeast(0L)
        duration = durationMs.takeIf { it > 0 }
        speed = playbackSpeed.coerceIn(0.25f, 4f)
        needsDialogue = requiresCoverageGate
        lead = 0
        transport = MediaTransport.PAUSED
        lastNow = 0
        episodeStarted = null
        episodeCount = 0
        previousWaitDuration = 0
        directive = null
        resumesDispatched = 0
        failureReason = ""
        waitTimedOut = false
        state = if (needsDialogue) PlaybackReadinessState.LOADING_MEDIA else PlaybackReadinessState.READY_PAUSED
        return snapshot()
    }

    fun setIntent(play: Boolean) {
        if (play && !wantsPlay) waitingForCoverage = true
        wantsPlay = play
        explicitPause = !play
        ended = false
        waitingSinceMs = null
        if (!play) {
            directive = null
            episodeStarted = null
            bypassEndMs = null
        }
    }

    fun seek(targetMs: Long, sequence: Int) {
        pendingTargetMs = targetMs
        generation = sequence
        waitingForCoverage = true
        bypassEndMs = null
        waitingSinceMs = null
        episodeStarted = null
        directive = null
        ended = false
    }

    fun acknowledge(sequence: Int, positionMs: Long, settled: Boolean): Boolean {
        if (sequence != generation) return false
        val target = pendingTargetMs ?: return true
        if (!settled || abs(target - positionMs) > SEEK_ACK_TOLERANCE_MS) return false
        acknowledgedGeneration = sequence
        pendingTargetMs = null
        return true
    }

    fun suspendPlayback() {
        if (wantsPlay) {
            setIntent(false)
            explicitPause = false
        }
    }

    fun end() {
        setIntent(false)
        ended = true
        commandedPlaying = false
    }

    fun retry() {
        waitingSinceMs = null
        waitTimedOut = false
        failureReason = ""
    }

    fun continueWithAvailable(positionMs: Long, speed: Float, nextVerifiedMs: Long?) {
        if (!wantsPlay || pendingTargetMs != null) return
        val limit = positionMs + mediaMs(BASE_HEALTHY_LEAD_MS, speed)
        bypassEndMs = minOf(limit, nextVerifiedMs?.takeIf { it > positionMs } ?: limit)
        waitingSinceMs = null
        episodeStarted = null
        directive = null
    }

    fun evaluate(
        positionMs: Long,
        durationMs: Long?,
        speed: Float,
        verifiedLeadMs: Long,
        needsDialogue: Boolean,
        processingFailed: Boolean,
        transport: MediaTransport,
        nowMs: Long,
    ): ReadinessDecision {
        lastPosition = positionMs
        duration = durationMs?.takeIf { it > 0L }
        this.speed = speed.coerceIn(0.25f, 4f)
        this.needsDialogue = needsDialogue
        this.transport = transport
        lastNow = nowMs
        lead = verifiedLeadMs
        val target = pendingTargetMs ?: positionMs
        val minimum = requiredInitialOrSeekLeadMs(target)
        val critical = midPlaybackBufferTriggerMs(target)
        if (verifiedLeadMs >= minimum || !needsDialogue) waitingForCoverage = false
        else if (verifiedLeadMs < critical) waitingForCoverage = true
        if (!waitingForCoverage || target >= (bypassEndMs ?: Long.MAX_VALUE)) bypassEndMs = null
        val bypassed = bypassEndMs != null
        val dialogueHold = needsDialogue && waitingForCoverage && !bypassed
        val seekHold = pendingTargetMs != null
        val transportReady = transport == MediaTransport.PAUSED || transport == MediaTransport.PLAYING
        val wait = seekHold || (wantsPlay && (dialogueHold || transport != MediaTransport.PLAYING))
        if (wait && waitingSinceMs == null) waitingSinceMs = nowMs
        if (!wait) waitingSinceMs = null
        waitTimedOut = waitingSinceMs?.let { nowMs - it >= waitTimeoutMs } ?: false
        val phase = when {
            ended -> PlaybackPhase.ENDED
            seekHold && waitTimedOut -> PlaybackPhase.PROCESSING_UNAVAILABLE
            seekHold -> PlaybackPhase.WAITING_FOR_SEEK
            !wantsPlay -> if (explicitPause) PlaybackPhase.USER_PAUSED else PlaybackPhase.READY
            dialogueHold && (processingFailed || waitTimedOut) -> PlaybackPhase.PROCESSING_UNAVAILABLE
            dialogueHold -> if (hasStarted) PlaybackPhase.BUFFERING_DIALOGUE else PlaybackPhase.PREPARING
            waitTimedOut && transport != MediaTransport.PLAYING -> PlaybackPhase.PROCESSING_UNAVAILABLE
            transport != MediaTransport.PLAYING -> PlaybackPhase.BUFFERING_MEDIA
            bypassed -> PlaybackPhase.LIMITED
            else -> PlaybackPhase.PLAYING
        }
        if (dialogueHold && (wantsPlay || seekHold)) {
            directive = null
            if (episodeStarted == null) {
                episodeStarted = nowMs
                episodeCount++
            }
        } else if (episodeStarted != null) {
            previousWaitDuration = (nowMs - episodeStarted!!).coerceAtLeast(0L)
            episodeStarted = null
            if (wantsPlay && !bypassed) directive = AutoResumeDirective(++resumeSeq, target,
                "Dialogue ready at the requested position", generation.toLong())
        }

        state = when {
            phase == PlaybackPhase.PROCESSING_UNAVAILABLE -> PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR
            directive != null -> PlaybackReadinessState.READY_TO_AUTO_RESUME
            seekHold -> if (dialogueHold) PlaybackReadinessState.BUFFERING_FOR_SEEK else PlaybackReadinessState.SEEKING
            phase == PlaybackPhase.USER_PAUSED -> PlaybackReadinessState.PAUSED_BY_USER
            !wantsPlay -> PlaybackReadinessState.READY_PAUSED
            bypassed -> PlaybackReadinessState.WATCHING_WITHOUT_FULL_COVERAGE
            phase == PlaybackPhase.PREPARING -> PlaybackReadinessState.BUFFERING_INITIAL_WINDOW
            phase == PlaybackPhase.BUFFERING_DIALOGUE -> PlaybackReadinessState.BUFFERING_MID_PLAYBACK
            phase == PlaybackPhase.BUFFERING_MEDIA -> PlaybackReadinessState.LOADING_MEDIA
            else -> PlaybackReadinessState.PLAYING
        }
        failureReason = when {
            state != PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR -> ""
            processingFailed -> "Dialogue or media preparation failed for this section."
            seekHold -> "The player hasn't confirmed the requested position."
            else -> "Preparation is taking longer than expected."
        }
        val desired = wantsPlay && !seekHold && !dialogueHold && (transportReady || commandedPlaying)
        val command = if (commandedPlaying != desired) desired else null
        commandedPlaying = desired
        if (transport == MediaTransport.PLAYING && desired) hasStarted = true
        return ReadinessDecision(phase, minimum, command)
    }

    fun nativePlay() {
        setIntent(true)
        commandedPlaying = true
    }

    fun nativePause() {
        setIntent(false)
        commandedPlaying = false
    }

    fun setPlaybackSpeed(speed: Float): PlaybackReadinessSnapshot {
        this.speed = speed.coerceIn(0.25f, 4f)
        return snapshot()
    }

    fun updateDuration(durationMs: Long) {
        if (durationMs > 0L) duration = durationMs
    }

    fun onUserPlayRequested(positionMs: Long, ledger: TranscriptCoverageLedger,
                            nowEpochMs: Long = System.currentTimeMillis()): PlaybackReadinessSnapshot {
        setIntent(true)
        lastPosition = positionMs.coerceAtLeast(0L)
        return evaluateCoverage(ledger, nowEpochMs)
    }

    fun onUserPauseRequested(positionMs: Long = lastPosition): PlaybackReadinessSnapshot {
        setIntent(false)
        lastPosition = positionMs.coerceAtLeast(0L)
        refresh(lastNow)
        return snapshot()
    }

    fun onUserSeekRequested(requestedTargetMs: Long, ledger: TranscriptCoverageLedger,
                            nowEpochMs: Long = System.currentTimeMillis()): PlaybackReadinessSnapshot {
        val target = requestedTargetMs.coerceIn(0L, duration ?: Long.MAX_VALUE)
        seek(target, generation + 1)
        return evaluateCoverage(ledger, nowEpochMs)
    }

    fun onPlayerPositionSample(reportedPositionMs: Long, playerIsPlaying: Boolean, ledger: TranscriptCoverageLedger,
                               nowEpochMs: Long = System.currentTimeMillis()): PlaybackReadinessSnapshot {
        val pos = reportedPositionMs.coerceAtLeast(0L)
        if (acknowledge(generation, pos, settled = true)) {
            lastPosition = pos
            transport = if (playerIsPlaying) MediaTransport.PLAYING else MediaTransport.PAUSED
        }
        return evaluateCoverage(ledger, nowEpochMs)
    }

    fun evaluateCoverage(ledger: TranscriptCoverageLedger,
                         nowEpochMs: Long = System.currentTimeMillis()): PlaybackReadinessSnapshot {
        val target = pendingTargetMs ?: lastPosition
        lead = ledger.continuousVerifiedLeadFrom(target)
        refresh(nowEpochMs, failed = ledger.hasFailedIntervalAt(target))
        return snapshot()
    }

    private fun refresh(now: Long, failed: Boolean = false) {
        evaluate(lastPosition, duration, speed, lead, needsDialogue, failed, transport, now)
    }

    /** Dispatched commands are separate from provider confirmation that media time is advancing. */
    fun confirmResumeDispatched() {
        val pending = directive ?: return
        if (!wantsPlay || pendingTargetMs != null || pending.seekGeneration != generation.toLong() ||
            !commandedPlaying || transport !in listOf(MediaTransport.PAUSED, MediaTransport.PLAYING)) return
        directive = null
        resumesDispatched++
        state = PlaybackReadinessState.PLAYING
    }

    fun consumeAutoResumeDirective(directiveId: Long): PlaybackReadinessSnapshot {
        if (directive?.id == directiveId) confirmResumeDispatched()
        return snapshot()
    }

    fun onUserWatchNowBypass(): PlaybackReadinessSnapshot {
        if (!wantsPlay) setIntent(true)
        continueWithAvailable(lastPosition, speed, null)
        refresh(lastNow)
        return snapshot()
    }

    fun onUserRetryBuffering(nowEpochMs: Long = System.currentTimeMillis()): PlaybackReadinessSnapshot {
        retry()
        refresh(nowEpochMs)
        return snapshot()
    }

    fun onMediaOrAnalysisError(reason: String): PlaybackReadinessSnapshot {
        directive = null
        state = PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR
        failureReason = reason.ifBlank { "Unable to prepare this section." }
        return snapshot()
    }

    fun requiredInitialOrSeekLeadMs(positionMs: Long): Long = threshold(BASE_START_AND_RESUME_LEAD_MS, positionMs)
    fun midPlaybackBufferTriggerMs(positionMs: Long): Long = threshold(BASE_MID_PLAYBACK_TRIGGER_MS, positionMs)
    fun healthyLeadTargetMs(positionMs: Long): Long = threshold(BASE_HEALTHY_LEAD_MS, positionMs)
    fun comfortableLeadTargetMs(positionMs: Long): Long = threshold(BASE_COMFORTABLE_LEAD_MS, positionMs)

    private fun threshold(base: Long, position: Long): Long =
        minOf(mediaMs(base, speed), duration?.let { (it - position).coerceAtLeast(0L) } ?: Long.MAX_VALUE)

    private fun mediaMs(wallTimeMs: Long, speed: Float): Long =
        (wallTimeMs * speed.coerceIn(0.25f, 4f)).roundToLong()

    fun snapshot(): PlaybackReadinessSnapshot {
        val target = pendingTargetMs ?: lastPosition
        val intent = if (wantsPlay) UserPlaybackIntent.PLAY else UserPlaybackIntent.PAUSE
        val minimum = requiredInitialOrSeekLeadMs(target)
        val diagnostics = PlaybackTimingDiagnostics(
            readinessState = state, userIntent = intent, playbackSpeed = speed, currentPositionMs = lastPosition,
            targetPositionMs = target, knownDurationMs = duration ?: 0L, contiguousVerifiedLeadMs = lead,
            requiredInitialLeadMs = minimum, requiredSeekLeadMs = minimum, requiredResumeLeadMs = minimum,
            midPlaybackBufferThresholdMs = midPlaybackBufferTriggerMs(target), healthyLeadTargetMs = healthyLeadTargetMs(target),
            seekGeneration = generation.toLong(), acknowledgedSeekGeneration = acknowledgedGeneration.toLong(),
            isSeekInFlight = pendingTargetMs != null, bufferingEpisodeCount = episodeCount,
            bufferingStartedAtEpochMs = episodeStarted ?: 0L, lastBufferingDurationMs = previousWaitDuration,
            autoResumeCount = resumesDispatched, watchNowBypassActive = bypassEndMs != null,
            waitTimedOut = waitTimedOut, statusDetail = failureReason,
        )
        return PlaybackReadinessSnapshot(
            state = state, userIntent = intent, targetPositionMs = target, contiguousVerifiedLeadMs = lead,
            requiredLeadMs = minimum, isBufferingHoldActive = episodeStarted != null && failureReason.isEmpty(),
            isWatchNowBypassActive = bypassEndMs != null, waitTimedOut = waitTimedOut, failureReason = failureReason,
            availableRecoveryActions = if (state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR) {
                BufferingFailureAction.entries
            } else emptyList(),
            pendingAutoResume = directive, diagnostics = diagnostics,
        )
    }

    companion object {
        const val BASE_MID_PLAYBACK_TRIGGER_MS = 2_000L
        const val BASE_START_AND_RESUME_LEAD_MS = 7_000L
        const val BASE_HEALTHY_LEAD_MS = 15_000L
        const val BASE_COMFORTABLE_LEAD_MS = 30_000L
        const val SEEK_ACK_TOLERANCE_MS = 650L
        const val DEFAULT_WAIT_TIMEOUT_MS = 18_000L
    }
}
