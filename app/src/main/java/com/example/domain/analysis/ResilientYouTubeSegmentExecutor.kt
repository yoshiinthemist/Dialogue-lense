package com.example.domain.analysis

import com.example.data.ai.AiCastSlotAssignment
import com.example.data.ai.AiTranscribedCue
import com.example.data.ai.AiYouTubeTranscriptionResult
import com.example.data.ai.ScoutHeartbeatService
import com.example.data.ai.TranslationAiService
import kotlinx.coroutines.CancellationException

sealed interface SegmentExecutionOutcome {
    data class Completed(
        val cues: List<AiTranscribedCue>,
        val speakerUpdates: List<AiCastSlotAssignment>,
        val summary: String,
        val resolvedTier: ScoutExecutionTier,
        val downgradedToLite: Boolean,
        val failedRanges: List<Pair<Int, Int>>,
        val usedBisection: Boolean,
    ) : SegmentExecutionOutcome

    data class QuotaExhausted(
        val error: Throwable?,
        val partialCues: List<AiTranscribedCue>,
        val speakerUpdates: List<AiCastSlotAssignment>,
        val summary: String,
    ) : SegmentExecutionOutcome
}

/**
 * Executes a YouTube video window with automatic non-quota resilience so a transient server fault
 * (such as "Something unexpected happened." or deadline exhaustion on dense multi-speaker shots)
 * never stalls live Scout or .srt generation mid-video:
 * 1. Retries transient drops briefly on the active model.
 * 2. On any quota exhaustion, cascades immediately BEST_AI -> LITE_AI -> Free CC trigger.
 * 3. On non-quota failures/timeouts, adaptively bisects (40s -> 20s -> 10s) on the current model
 *    first, then falls back to Flash-Lite on stubborn micro-slices, and finally logs any still-failing
 *    micro-slice as an isolated gap while returning cleanly so Scout continues across the full video.
 */
class ResilientYouTubeSegmentExecutor(
    private val heartbeatService: ScoutHeartbeatService,
) {
    suspend fun executeSegment(
        startSec: Int,
        endSec: Int,
        initialTier: ScoutExecutionTier,
        onStatusUpdate: (String) -> Unit = {},
        onDowngradedToLite: () -> Unit = {},
        transcribeWindow: suspend (winStartSec: Int, winEndSec: Int, preferLite: Boolean) -> Result<AiYouTubeTranscriptionResult>,
    ): SegmentExecutionOutcome {
        val collectedCues = mutableListOf<AiTranscribedCue>()
        val collectedUpdates = mutableListOf<AiCastSlotAssignment>()
        val summaryParts = mutableListOf<String>()
        val failedRanges = mutableListOf<Pair<Int, Int>>()
        var activeTier = initialTier
        var downgradedToLite = initialTier == ScoutExecutionTier.LITE_AI
        var usedBisection = false
        var quotaStopError: Throwable? = null
        var hitQuotaStop = false

        suspend fun runSlice(sliceStart: Int, sliceEnd: Int, depth: Int) {
            if (hitQuotaStop || sliceEnd <= sliceStart) return
            val canBisect = (sliceEnd - sliceStart) >= 20 && depth < 2
            val primaryRetries = if (canBisect) 1 else 2

            val firstAttempt = heartbeatService.executeWithBackoffRetry(
                modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                maxRetries = primaryRetries,
                onRetryScheduled = { attempt, maxRetries, delayMs, retryErr ->
                    val delaySec = String.format(java.util.Locale.US, "%.1f", delayMs / 1000.0)
                    val prefix = if (TranslationAiService.isAppCheckTransientError(retryErr)) {
                        "Refreshing security token"
                    } else {
                        "${activeTier.displayLabel} reconnecting"
                    }
                    onStatusUpdate("$prefix ($attempt/$maxRetries in ${delaySec}s)...")
                },
            ) {
                transcribeWindow(sliceStart, sliceEnd, activeTier.preferLite)
            }

            if (firstAttempt.isSuccess) {
                val res = firstAttempt.getOrThrow()
                collectedCues.addAll(res.cues)
                collectedUpdates.addAll(res.speakerUpdates)
                if (res.summary.isNotBlank()) summaryParts.add(res.summary)
                return
            }

            val err = firstAttempt.exceptionOrNull()
            if (err is CancellationException) throw err

            // Quota exhaustion -> cascade stream permanently to Flash-Lite, then Free CC if still dry
            if (TranslationAiService.isQuotaOrCreditExhaustedError(err)) {
                if (activeTier == ScoutExecutionTier.BEST_AI) {
                    activeTier = LookaheadWindowPlanner.nextFallbackTierOnQuotaError(activeTier)
                    downgradedToLite = true
                    onDowngradedToLite()
                    onStatusUpdate("Gemini 3.8 Flash credits depleted — switching to ${activeTier.displayLabel}...")
                    val liteRetry = heartbeatService.executeWithBackoffRetry(
                        modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                        maxRetries = 1,
                    ) {
                        transcribeWindow(sliceStart, sliceEnd, true)
                    }
                    if (liteRetry.isSuccess) {
                        val res = liteRetry.getOrThrow()
                        collectedCues.addAll(res.cues)
                        collectedUpdates.addAll(res.speakerUpdates)
                        if (res.summary.isNotBlank()) summaryParts.add(res.summary)
                        return
                    }
                    val liteErr = liteRetry.exceptionOrNull()
                    if (liteErr is CancellationException) throw liteErr
                    if (TranslationAiService.isQuotaOrCreditExhaustedError(liteErr)) {
                        hitQuotaStop = true
                        quotaStopError = liteErr
                        return
                    }
                    // If Flash-Lite hit a non-quota error on a bisect-able slice, let bisection below recover it!
                    if (!canBisect) {
                        failedRanges.add(sliceStart to sliceEnd)
                        return
                    }
                } else {
                    hitQuotaStop = true
                    quotaStopError = err
                    return
                }
            }

            // Non-quota failure ("Something unexpected happened.", timeout, HTTP 500, etc.):
            // Step 1: Bisect dense chunks (40s -> 20s -> 10s) on current model before degrading quality
            if (canBisect) {
                usedBisection = true
                val midSec = sliceStart + ((sliceEnd - sliceStart) / 2)
                onStatusUpdate("${activeTier.displayLabel} splitting dense clip (${sliceStart}s–${midSec}s, ${midSec}s–${sliceEnd}s)...")
                runSlice(sliceStart, midSec, depth + 1)
                if (!hitQuotaStop) {
                    runSlice(midSec, sliceEnd, depth + 1)
                }
                return
            }

            // Step 2: Leaf slice (10s..19s) still failed on BEST_AI -> cross-model rescue on Flash-Lite
            if (activeTier == ScoutExecutionTier.BEST_AI) {
                onStatusUpdate("Rescuing ${sliceStart}s–${sliceEnd}s slice with Flash-Lite...")
                val rescueAttempt = heartbeatService.executeWithBackoffRetry(
                    modelName = ScoutExecutionTier.LITE_AI.modelId ?: "gemini-3.5-flash-lite",
                    maxRetries = 1,
                ) {
                    transcribeWindow(sliceStart, sliceEnd, true)
                }
                if (rescueAttempt.isSuccess) {
                    val res = rescueAttempt.getOrThrow()
                    collectedCues.addAll(res.cues)
                    collectedUpdates.addAll(res.speakerUpdates)
                    if (res.summary.isNotBlank()) summaryParts.add(res.summary)
                    return
                }
                val rescueErr = rescueAttempt.exceptionOrNull()
                if (rescueErr is CancellationException) throw rescueErr
                if (TranslationAiService.isQuotaOrCreditExhaustedError(rescueErr)) {
                    hitQuotaStop = true
                    quotaStopError = rescueErr
                    return
                }
            }

            // Step 3: Isolate failed micro-slice as a recoverable gap instead of halting the stream
            failedRanges.add(sliceStart to sliceEnd)
        }

        runSlice(startSec, endSec, depth = 0)

        val dedupedCues = deduplicateAndClampCues(
            cues = collectedCues,
            windowStartMs = startSec * 1000L,
            windowEndMs = endSec * 1000L,
        )
        val dedupedUpdates = collectedUpdates.distinctBy { "${it.slot}_${it.displayName}_${it.isVisuallyConfirmed}" }
        val mergedSummary = summaryParts.distinct().joinToString(" ").trim()

        return if (hitQuotaStop) {
            SegmentExecutionOutcome.QuotaExhausted(
                error = quotaStopError,
                partialCues = dedupedCues,
                speakerUpdates = dedupedUpdates,
                summary = mergedSummary,
            )
        } else {
            SegmentExecutionOutcome.Completed(
                cues = dedupedCues,
                speakerUpdates = dedupedUpdates,
                summary = mergedSummary,
                resolvedTier = activeTier,
                downgradedToLite = downgradedToLite,
                failedRanges = failedRanges,
                usedBisection = usedBisection,
            )
        }
    }

    companion object {
        internal fun deduplicateAndClampCues(
            cues: List<AiTranscribedCue>,
            windowStartMs: Long,
            windowEndMs: Long,
        ): List<AiTranscribedCue> {
            if (cues.isEmpty() || windowEndMs <= windowStartMs) return emptyList()
            val minAcceptableStart = (windowStartMs - 1_000L).coerceAtLeast(0L)
            val result = mutableListOf<AiTranscribedCue>()
            val sorted = cues.sortedBy { it.startMs }
            for (raw in sorted) {
                if (raw.startMs >= windowEndMs || raw.startMs < minAcceptableStart) continue
                val clampedStart = raw.startMs.coerceIn(windowStartMs, (windowEndMs - 450L).coerceAtLeast(windowStartMs))
                val clampedEnd = raw.endMs
                    .coerceAtLeast((clampedStart + 450L).coerceAtMost(windowEndMs))
                    .coerceAtMost(windowEndMs)
                val cue = raw.copy(startMs = clampedStart, endMs = clampedEnd)
                val isRepeat = result.any { prev ->
                    LookaheadWindowPlanner.isEquivalentOrOverlappingCueText(
                        prevText = prev.sourceText,
                        candText = cue.sourceText,
                        deltaMs = kotlin.math.abs(prev.startMs - cue.startMs),
                    )
                }
                if (!isRepeat) {
                    result.add(cue)
                }
            }
            return result
        }
    }
}
