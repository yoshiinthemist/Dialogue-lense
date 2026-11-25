package com.example.domain.analysis

import com.example.data.ai.AiCastSlotAssignment
import com.example.data.ai.AiTranscribedCue
import com.example.data.ai.AiYouTubeTranscriptionResult
import com.example.data.ai.ScoutHeartbeatService
import com.example.data.ai.TranslationAiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

data class FailedSliceDetail(
    val startSec: Int,
    val endSec: Int,
    val outcome: TranscriptionOutcome,
    val attempts: Int = 1,
    val reason: String = "",
)

sealed interface SegmentExecutionOutcome {
    data class Completed(
        val cues: List<AiTranscribedCue>,
        val speakerUpdates: List<AiCastSlotAssignment>,
        val summary: String,
        val resolvedTier: ScoutExecutionTier,
        val downgradedToLite: Boolean,
        val failedRanges: List<Pair<Int, Int>>,
        val usedBisection: Boolean,
        val verifiedDialogueRanges: List<Pair<Int, Int>> = emptyList(),
        val verifiedSilentRanges: List<Pair<Int, Int>> = emptyList(),
        val partialRanges: List<Pair<Int, Int>> = emptyList(),
        val failedDetails: List<FailedSliceDetail> = emptyList(),
        val discardedDuplicates: Int = 0,
        val invalidTimestampCount: Int = 0,
        val retryCount: Int = 0,
        val quotaFallbackTransitions: Int = 0,
    ) : SegmentExecutionOutcome

    data class QuotaExhausted(
        val error: Throwable?,
        val partialCues: List<AiTranscribedCue>,
        val speakerUpdates: List<AiCastSlotAssignment>,
        val summary: String,
    ) : SegmentExecutionOutcome
}

/**
 * Executes a YouTube video window with automatic non-quota resilience and strict epistemic outcome
 * accounting:
 * 1. Never conflates `INVALID_OR_AMBIGUOUS_TIMESTAMPS`, `INVALID_AI_RESPONSE`, or `EMPTY_UNCONFIRMED`
 *    with verified silence (`VERIFIED_SILENCE`).
 * 2. Retries transient drops briefly, sends timestamp-clarification retries on ambiguous offsets, and
 *    adaptively bisects (40s -> 20s -> 10s) on failures, malformed payloads, or partial outcomes.
 * 3. Cascades cleanly on quota exhaustion (BEST_AI -> LITE_AI -> Free CC trigger).
 * 4. Preserves valid partial cues (`PARTIAL_SUCCESS`) and isolates unverified spans for targeted
 *    background healing.
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
    ): SegmentExecutionOutcome = executeSegmentDetailed(
        startSec = startSec,
        endSec = endSec,
        initialTier = initialTier,
        onStatusUpdate = onStatusUpdate,
        onDowngradedToLite = onDowngradedToLite,
    ) { winStartSec, winEndSec, preferLite, _ ->
        transcribeWindow(winStartSec, winEndSec, preferLite)
    }

    suspend fun executeSegmentDetailed(
        startSec: Int,
        endSec: Int,
        initialTier: ScoutExecutionTier,
        onStatusUpdate: (String) -> Unit = {},
        onDowngradedToLite: () -> Unit = {},
        transcribeWindow: suspend (
            winStartSec: Int,
            winEndSec: Int,
            preferLite: Boolean,
            timestampClarificationRetry: Boolean,
        ) -> Result<AiYouTubeTranscriptionResult>,
    ): SegmentExecutionOutcome {
        val collectedCues = mutableListOf<AiTranscribedCue>()
        val collectedUpdates = mutableListOf<AiCastSlotAssignment>()
        val summaryParts = mutableListOf<String>()
        val verifiedDialogueRanges = mutableListOf<Pair<Int, Int>>()
        val verifiedSilentRanges = mutableListOf<Pair<Int, Int>>()
        val partialRanges = mutableListOf<Pair<Int, Int>>()
        val failedDetails = mutableListOf<FailedSliceDetail>()
        var activeTier = initialTier
        var downgradedToLite = initialTier == ScoutExecutionTier.LITE_AI
        var usedBisection = false
        var quotaStopError: Throwable? = null
        var hitQuotaStop = false
        var invalidTimestampCount = 0
        var totalRetries = 0
        var quotaTransitions = 0
        var discardedUpstreamDupes = 0

        fun absorbSuccessPayload(res: AiYouTubeTranscriptionResult) {
            collectedCues.addAll(res.cues)
            collectedUpdates.addAll(res.speakerUpdates)
            if (res.summary.isNotBlank()) summaryParts.add(res.summary)
            discardedUpstreamDupes += res.discardedDuplicateCount
        }

        fun classifyThrowable(err: Throwable?): TranscriptionOutcome {
            val msg = err?.message.orEmpty().lowercase()
            return when {
                err == null -> TranscriptionOutcome.UNRESOLVED_FAILURE
                TranslationAiService.isQuotaOrCreditExhaustedError(err) -> TranscriptionOutcome.QUOTA_EXHAUSTED
                err is TimeoutCancellationException || msg.contains("timeout") || msg.contains("deadline") ->
                    TranscriptionOutcome.TIMEOUT
                err is IOException || msg.contains("network") || msg.contains("connect") || msg.contains("resolve host") ->
                    TranscriptionOutcome.NETWORK_FAILURE
                else -> TranscriptionOutcome.UNRESOLVED_FAILURE
            }
        }

        suspend fun runSlice(sliceStart: Int, sliceEnd: Int, depth: Int) {
            if (hitQuotaStop || sliceEnd <= sliceStart) return
            val canBisect = (sliceEnd - sliceStart) >= 20 && depth < 2
            val primaryRetries = if (canBisect) 1 else 2
            var attemptsUsed = 0

            val firstAttempt = heartbeatService.executeWithBackoffRetry(
                modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                maxRetries = primaryRetries,
                onRetryScheduled = { attempt, maxRetries, delayMs, retryErr ->
                    totalRetries++
                    val delaySec = String.format(java.util.Locale.US, "%.1f", delayMs / 1000.0)
                    val prefix = if (TranslationAiService.isAppCheckTransientError(retryErr)) {
                        "Refreshing security token"
                    } else {
                        "${activeTier.displayLabel} reconnecting"
                    }
                    onStatusUpdate("$prefix ($attempt/$maxRetries in ${delaySec}s)...")
                },
            ) {
                attemptsUsed++
                transcribeWindow(sliceStart, sliceEnd, activeTier.preferLite, false)
            }

            var pendingFailureOutcome: TranscriptionOutcome? = null
            var pendingFailureReason = ""

            if (firstAttempt.isSuccess) {
                var res = firstAttempt.getOrThrow()
                if (res.outcome == TranscriptionOutcome.INVALID_OR_AMBIGUOUS_TIMESTAMPS) {
                    invalidTimestampCount++
                }

                // Ambiguous / malformed / unconfirmed-empty outcomes get a strict timestamp-clarification retry
                val needsClarificationRetry = res.outcome == TranscriptionOutcome.INVALID_OR_AMBIGUOUS_TIMESTAMPS ||
                    res.outcome == TranscriptionOutcome.INVALID_AI_RESPONSE ||
                    res.outcome == TranscriptionOutcome.EMPTY_UNCONFIRMED

                if (needsClarificationRetry && !canBisect) {
                    totalRetries++
                    attemptsUsed++
                    onStatusUpdate("Clarifying ${sliceStart}s–${sliceEnd}s timestamps (${activeTier.displayLabel})...")
                    val clarified = runCatching {
                        transcribeWindow(sliceStart, sliceEnd, activeTier.preferLite, true)
                    }.getOrElse { Result.failure(it) }
                    if (clarified.isSuccess) {
                        res = clarified.getOrThrow()
                    } else {
                        val cErr = clarified.exceptionOrNull()
                        if (cErr is CancellationException) throw cErr
                    }
                }

                when (res.outcome) {
                    TranscriptionOutcome.DIALOGUE_DETECTED -> {
                        absorbSuccessPayload(res)
                        verifiedDialogueRanges.add(sliceStart to sliceEnd)
                        return
                    }
                    TranscriptionOutcome.VERIFIED_SILENCE -> {
                        absorbSuccessPayload(res)
                        verifiedSilentRanges.add(sliceStart to sliceEnd)
                        return
                    }
                    TranscriptionOutcome.PARTIAL_SUCCESS -> {
                        absorbSuccessPayload(res)
                        if (!canBisect) {
                            partialRanges.add(sliceStart to sliceEnd)
                            failedDetails.add(
                                FailedSliceDetail(
                                    startSec = sliceStart,
                                    endSec = sliceEnd,
                                    outcome = TranscriptionOutcome.PARTIAL_SUCCESS,
                                    attempts = attemptsUsed,
                                    reason = res.diagnosticNote.ifBlank { "Partial cues salvaged; incomplete slice" },
                                )
                            )
                            return
                        }
                        // If bisection is available, split to heal invalid/missing sub-portion while retaining salvaged cues
                        pendingFailureOutcome = TranscriptionOutcome.PARTIAL_SUCCESS
                        pendingFailureReason = res.diagnosticNote
                    }
                    TranscriptionOutcome.INVALID_OR_AMBIGUOUS_TIMESTAMPS,
                    TranscriptionOutcome.INVALID_AI_RESPONSE,
                    TranscriptionOutcome.EMPTY_UNCONFIRMED -> {
                        // Never assume failure/ambiguity/unconfirmed-empty means silence
                        absorbSuccessPayload(res)
                        pendingFailureOutcome = res.outcome
                        pendingFailureReason = res.diagnosticNote.ifBlank { res.outcome.name }
                    }
                    else -> {
                        absorbSuccessPayload(res)
                        pendingFailureOutcome = res.outcome
                        pendingFailureReason = res.diagnosticNote
                    }
                }
            } else {
                val err = firstAttempt.exceptionOrNull()
                if (err is CancellationException) throw err

                // Quota exhaustion -> cascade stream permanently to Flash-Lite, then Free CC trigger
                if (TranslationAiService.isQuotaOrCreditExhaustedError(err)) {
                    if (activeTier == ScoutExecutionTier.BEST_AI) {
                        activeTier = LookaheadWindowPlanner.nextFallbackTierOnQuotaError(activeTier)
                        downgradedToLite = true
                        quotaTransitions++
                        onDowngradedToLite()
                        onStatusUpdate("Gemini 3.8 Flash credits depleted — switching to ${activeTier.displayLabel}...")
                        val liteRetry = heartbeatService.executeWithBackoffRetry(
                            modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                            maxRetries = 1,
                        ) {
                            attemptsUsed++
                            transcribeWindow(sliceStart, sliceEnd, true, false)
                        }
                        if (liteRetry.isSuccess) {
                            val res = liteRetry.getOrThrow()
                            when (res.outcome) {
                                TranscriptionOutcome.DIALOGUE_DETECTED -> {
                                    absorbSuccessPayload(res)
                                    verifiedDialogueRanges.add(sliceStart to sliceEnd)
                                    return
                                }
                                TranscriptionOutcome.VERIFIED_SILENCE -> {
                                    absorbSuccessPayload(res)
                                    verifiedSilentRanges.add(sliceStart to sliceEnd)
                                    return
                                }
                                TranscriptionOutcome.PARTIAL_SUCCESS -> {
                                    absorbSuccessPayload(res)
                                    if (!canBisect) {
                                        partialRanges.add(sliceStart to sliceEnd)
                                        failedDetails.add(
                                            FailedSliceDetail(sliceStart, sliceEnd, res.outcome, attemptsUsed, res.diagnosticNote)
                                        )
                                        return
                                    }
                                    pendingFailureOutcome = res.outcome
                                    pendingFailureReason = res.diagnosticNote
                                }
                                else -> {
                                    absorbSuccessPayload(res)
                                    pendingFailureOutcome = res.outcome
                                    pendingFailureReason = res.diagnosticNote
                                }
                            }
                        } else {
                            val liteErr = liteRetry.exceptionOrNull()
                            if (liteErr is CancellationException) throw liteErr
                            if (TranslationAiService.isQuotaOrCreditExhaustedError(liteErr)) {
                                hitQuotaStop = true
                                quotaStopError = liteErr
                                return
                            }
                            pendingFailureOutcome = classifyThrowable(liteErr)
                            pendingFailureReason = liteErr?.message?.take(120).orEmpty()
                            if (!canBisect) {
                                failedDetails.add(
                                    FailedSliceDetail(
                                        startSec = sliceStart,
                                        endSec = sliceEnd,
                                        outcome = pendingFailureOutcome,
                                        attempts = attemptsUsed,
                                        reason = pendingFailureReason,
                                    )
                                )
                                return
                            }
                        }
                    } else {
                        hitQuotaStop = true
                        quotaStopError = err
                        return
                    }
                } else {
                    pendingFailureOutcome = classifyThrowable(err)
                    pendingFailureReason = err?.message?.take(120).orEmpty()
                }
            }

            // Step 1: Bisect dense/ambiguous/faulting chunks (40s -> 20s -> 10s) on current model
            if (canBisect) {
                usedBisection = true
                val midSec = sliceStart + ((sliceEnd - sliceStart) / 2)
                onStatusUpdate("${activeTier.displayLabel} splitting slice (${sliceStart}s–${midSec}s, ${midSec}s–${sliceEnd}s)...")
                runSlice(sliceStart, midSec, depth + 1)
                if (!hitQuotaStop) {
                    runSlice(midSec, sliceEnd, depth + 1)
                }
                return
            }

            // Step 2: Leaf slice (10s..19s) still unresolved on BEST_AI -> cross-model rescue on Flash-Lite
            if (activeTier == ScoutExecutionTier.BEST_AI) {
                onStatusUpdate("Rescuing ${sliceStart}s–${sliceEnd}s slice with Flash-Lite...")
                val rescueAttempt = heartbeatService.executeWithBackoffRetry(
                    modelName = ScoutExecutionTier.LITE_AI.modelId ?: "gemini-3.5-flash-lite",
                    maxRetries = 1,
                ) {
                    attemptsUsed++
                    transcribeWindow(sliceStart, sliceEnd, true, true)
                }
                if (rescueAttempt.isSuccess) {
                    val res = rescueAttempt.getOrThrow()
                    when (res.outcome) {
                        TranscriptionOutcome.DIALOGUE_DETECTED -> {
                            absorbSuccessPayload(res)
                            verifiedDialogueRanges.add(sliceStart to sliceEnd)
                            return
                        }
                        TranscriptionOutcome.VERIFIED_SILENCE -> {
                            absorbSuccessPayload(res)
                            verifiedSilentRanges.add(sliceStart to sliceEnd)
                            return
                        }
                        TranscriptionOutcome.PARTIAL_SUCCESS -> {
                            absorbSuccessPayload(res)
                            partialRanges.add(sliceStart to sliceEnd)
                            failedDetails.add(
                                FailedSliceDetail(sliceStart, sliceEnd, res.outcome, attemptsUsed, res.diagnosticNote)
                            )
                            return
                        }
                        else -> {
                            absorbSuccessPayload(res)
                            pendingFailureOutcome = res.outcome
                            pendingFailureReason = res.diagnosticNote
                        }
                    }
                } else {
                    val rescueErr = rescueAttempt.exceptionOrNull()
                    if (rescueErr is CancellationException) throw rescueErr
                    if (TranslationAiService.isQuotaOrCreditExhaustedError(rescueErr)) {
                        hitQuotaStop = true
                        quotaStopError = rescueErr
                        return
                    }
                    pendingFailureOutcome = classifyThrowable(rescueErr)
                    pendingFailureReason = rescueErr?.message?.take(120).orEmpty()
                }
            }

            // Step 3: Isolate failed micro-slice as a recoverable gap instead of halting or claiming silence
            failedDetails.add(
                FailedSliceDetail(
                    startSec = sliceStart,
                    endSec = sliceEnd,
                    outcome = pendingFailureOutcome ?: TranscriptionOutcome.UNRESOLVED_FAILURE,
                    attempts = attemptsUsed,
                    reason = pendingFailureReason,
                )
            )
        }

        runSlice(startSec, endSec, depth = 0)

        val (dedupedCues, localDedupCount) = deduplicateAndClampCuesWithCount(
            cues = collectedCues,
            windowStartMs = startSec * 1000L,
            windowEndMs = endSec * 1000L,
        )
        val dedupedUpdates = collectedUpdates.distinctBy { "${it.slot}_${it.displayName}_${it.isVisuallyConfirmed}" }
        val mergedSummary = summaryParts.distinct().joinToString(" ").trim()
        val failedRanges = failedDetails.map { it.startSec to it.endSec }.distinct()

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
                verifiedDialogueRanges = verifiedDialogueRanges.distinct(),
                verifiedSilentRanges = verifiedSilentRanges.distinct(),
                partialRanges = partialRanges.distinct(),
                failedDetails = failedDetails,
                discardedDuplicates = discardedUpstreamDupes + localDedupCount,
                invalidTimestampCount = invalidTimestampCount,
                retryCount = totalRetries,
                quotaFallbackTransitions = quotaTransitions,
            )
        }
    }

    companion object {
        internal fun deduplicateAndClampCues(
            cues: List<AiTranscribedCue>,
            windowStartMs: Long,
            windowEndMs: Long,
        ): List<AiTranscribedCue> = deduplicateAndClampCuesWithCount(
            cues = cues,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
        ).first

        internal fun deduplicateAndClampCuesWithCount(
            cues: List<AiTranscribedCue>,
            windowStartMs: Long,
            windowEndMs: Long,
        ): Pair<List<AiTranscribedCue>, Int> {
            if (cues.isEmpty() || windowEndMs <= windowStartMs) return emptyList<AiTranscribedCue>() to 0
            val minAcceptableStart = (windowStartMs - 1_000L).coerceAtLeast(0L)
            val result = mutableListOf<AiTranscribedCue>()
            var discardedCount = 0
            val sorted = cues.sortedBy { it.startMs }
            for (raw in sorted) {
                if (raw.startMs >= windowEndMs || raw.startMs < minAcceptableStart) {
                    discardedCount++
                    continue
                }
                val clampedStart = raw.startMs.coerceIn(windowStartMs, (windowEndMs - 450L).coerceAtLeast(windowStartMs))
                val clampedEnd = raw.endMs
                    .coerceAtLeast((clampedStart + 450L).coerceAtMost(windowEndMs))
                    .coerceAtMost(windowEndMs)
                val cue = raw.copy(startMs = clampedStart, endMs = clampedEnd)
                val isSameUtteranceRepeat = result.any { prev ->
                    LookaheadWindowPlanner.areSpeakersCompatible(prev.suggestedSpeakerSlot, cue.suggestedSpeakerSlot) &&
                        LookaheadWindowPlanner.areTemporallyOverlappingEquivalentText(
                            prevStartMs = prev.startMs,
                            prevEndMs = prev.endMs,
                            prevText = prev.sourceText,
                            candStartMs = cue.startMs,
                            candEndMs = cue.endMs,
                            candText = cue.sourceText,
                        )
                }
                if (!isSameUtteranceRepeat) {
                    result.add(cue)
                } else {
                    discardedCount++
                }
            }
            return result to discardedCount
        }
    }
}
