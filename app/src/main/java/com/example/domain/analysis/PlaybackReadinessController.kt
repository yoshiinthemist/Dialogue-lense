package com.example.domain.analysis

import kotlin.math.abs
import kotlin.math.roundToLong

enum class PlaybackPhase {
    READY, USER_PAUSED, PREPARING, WAITING_FOR_SEEK, BUFFERING_MEDIA,
    BUFFERING_DIALOGUE, PLAYING, LIMITED, PROCESSING_UNAVAILABLE, ENDED,
}

enum class MediaTransport { UNAVAILABLE, PAUSED, BUFFERING, PLAYING, ENDED }

data class ViewerTransportSample(
    val positionMs: Long,
    val durationMs: Long,
    val transport: MediaTransport,
    val seekSequence: Int,
    val playSequence: Int,
)

data class ReadinessDecision(
    val phase: PlaybackPhase,
    val minimumLeadMs: Long,
    val playCommand: Boolean?,
)

/** Main-thread controller. A provider sample acknowledges transport, never dialogue coverage. */
class PlaybackReadinessController {
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

    fun setIntent(play: Boolean) {
        if (play && !wantsPlay) waitingForCoverage = true
        wantsPlay = play
        explicitPause = !play
        ended = false
        waitingSinceMs = null
    }

    fun seek(targetMs: Long, sequence: Int) {
        pendingTargetMs = targetMs
        generation = sequence
        waitingForCoverage = true
        bypassEndMs = null
        waitingSinceMs = null
        ended = false
    }

    fun acknowledge(sequence: Int, positionMs: Long, settled: Boolean): Boolean {
        if (sequence != generation) return false
        val target = pendingTargetMs ?: return true
        if (!settled || abs(target - positionMs) > 650L) return false
        pendingTargetMs = null
        return true
    }

    fun end() {
        setIntent(false)
        ended = true
        commandedPlaying = false
    }

    fun retry() { waitingSinceMs = null }

    /** Continue through this unresolved interval, reevaluating at the next verified boundary or in 15 viewing seconds. */
    fun continueWithAvailable(positionMs: Long, speed: Float, nextVerifiedMs: Long?) {
        if (!wantsPlay || pendingTargetMs != null) return
        val limit = positionMs + mediaMs(15_000L, speed)
        bypassEndMs = minOf(limit, nextVerifiedMs?.takeIf { it > positionMs } ?: limit)
        waitingSinceMs = null
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
        val remaining = durationMs?.let { (it - positionMs).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        val critical = minOf(remaining, mediaMs(2_000L, speed))
        val minimum = minOf(remaining, mediaMs(7_000L, speed))
        if (verifiedLeadMs >= minimum || !needsDialogue) waitingForCoverage = false
        else if (verifiedLeadMs < critical) waitingForCoverage = true
        if (!waitingForCoverage || positionMs >= (bypassEndMs ?: Long.MAX_VALUE)) bypassEndMs = null
        val bypassed = bypassEndMs != null
        val dialogueHold = needsDialogue && waitingForCoverage && !bypassed
        val seekHold = pendingTargetMs != null
        val transportReady = transport == MediaTransport.PAUSED || transport == MediaTransport.PLAYING
        val wait = seekHold || (wantsPlay && (dialogueHold || transport != MediaTransport.PLAYING))
        if (wait && waitingSinceMs == null) waitingSinceMs = nowMs
        if (!wait) waitingSinceMs = null
        val expired = waitingSinceMs?.let { nowMs - it >= 18_000L } ?: false
        val phase = when {
            ended -> PlaybackPhase.ENDED
            seekHold && expired -> PlaybackPhase.PROCESSING_UNAVAILABLE
            seekHold -> PlaybackPhase.WAITING_FOR_SEEK
            !wantsPlay -> if (explicitPause) PlaybackPhase.USER_PAUSED else PlaybackPhase.READY
            dialogueHold && (processingFailed || expired) -> PlaybackPhase.PROCESSING_UNAVAILABLE
            dialogueHold -> if (hasStarted) PlaybackPhase.BUFFERING_DIALOGUE else PlaybackPhase.PREPARING
            expired && transport != MediaTransport.PLAYING -> PlaybackPhase.PROCESSING_UNAVAILABLE
            transport != MediaTransport.PLAYING -> PlaybackPhase.BUFFERING_MEDIA
            bypassed -> PlaybackPhase.LIMITED
            else -> PlaybackPhase.PLAYING
        }
        val desired = wantsPlay && !seekHold && !dialogueHold && (transportReady || commandedPlaying)
        val command = if (commandedPlaying != desired) desired else null
        commandedPlaying = desired
        if (transport == MediaTransport.PLAYING && desired) hasStarted = true
        return ReadinessDecision(phase, minimum, command)
    }

    /** Native player transport controls can also express a user decision. */
    fun nativePlay() {
        setIntent(true)
        commandedPlaying = true
    }

    fun nativePause() {
        setIntent(false)
        commandedPlaying = false
    }

    private fun mediaMs(wallTimeMs: Long, speed: Float): Long =
        (wallTimeMs * speed.coerceIn(0.25f, 4f)).roundToLong()
}
