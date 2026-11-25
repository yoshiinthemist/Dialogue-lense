package com.example.domain.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.example.data.model.CachedVideoTranscript
import com.example.data.model.CoverageInterval
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.SubtitleSourceKind

enum class TranscriptionOutcome {
    DIALOGUE_DETECTED,
    VERIFIED_SILENCE,
    PARTIAL_SUCCESS,
    INVALID_OR_AMBIGUOUS_TIMESTAMPS,
    INVALID_AI_RESPONSE,
    EMPTY_UNCONFIRMED,
    NETWORK_FAILURE,
    TIMEOUT,
    QUOTA_EXHAUSTED,
    UNSUPPORTED_MEDIA,
    UNRESOLVED_FAILURE,
}

data class DuplicateDecisionRecord(
    val accepted: Boolean,
    val reason: String,
    val incomingTextPreview: String,
    val incomingStartMs: Long,
    val matchedStartMs: Long? = null,
    val speakerId: String? = null,
)

data class TranscriptDiagnostics(
    val requestedWindows: Int = 0,
    val completedWindows: Int = 0,
    val verifiedDialogueMs: Long = 0L,
    val verifiedSilenceMs: Long = 0L,
    val partialRecoveryMs: Long = 0L,
    val failedMs: Long = 0L,
    val pendingMs: Long = 0L,
    val legacyUnverifiedMs: Long = 0L,
    val verifiedDialogueCount: Int = 0,
    val verifiedSilenceCount: Int = 0,
    val failedCount: Int = 0,
    val pendingCount: Int = 0,
    val legacyUnverifiedCount: Int = 0,
    val acceptedEvents: Int = 0,
    val rejectedDuplicates: Int = 0,
    val invalidOrAmbiguousTimestampCount: Int = 0,
    val retryCount: Int = 0,
    val quotaFallbackTransitions: Int = 0,
    val activeSource: SubtitleSourceKind = SubtitleSourceKind.BUILT_IN_DEMO,
    val recentDuplicateDecisions: List<DuplicateDecisionRecord> = emptyList(),
) {
    fun summaryLine(): String = buildString {
        append(activeSource.name)
        append(" · ok=").append(verifiedDialogueCount)
        if (verifiedSilenceCount > 0) append(" silent=").append(verifiedSilenceCount)
        if (failedCount > 0) append(" failed=").append(failedCount)
        if (pendingCount > 0) append(" pending=").append(pendingCount)
        if (legacyUnverifiedCount > 0) append(" legacy=").append(legacyUnverifiedCount)
        if (rejectedDuplicates > 0) append(" dedup=").append(rejectedDuplicates)
        if (invalidOrAmbiguousTimestampCount > 0) append(" tsErr=").append(invalidOrAmbiguousTimestampCount)
        if (retryCount > 0) append(" retries=").append(retryCount)
        if (quotaFallbackTransitions > 0) append(" fallbacks=").append(quotaFallbackTransitions)
    }
}

data class CoverageSummary(
    val totalDurationMs: Long = 0L,
    val verifiedDialogueMs: Long = 0L,
    val verifiedSilenceMs: Long = 0L,
    val totalVerifiedMs: Long = 0L,
    val partialRecoveryMs: Long = 0L,
    val failedMs: Long = 0L,
    val pendingMs: Long = 0L,
    val legacyUnverifiedMs: Long = 0L,
    val furthestAnalyzedMs: Long = 0L,
    val continuousVerifiedUpToMs: Long = 0L,
    val verifiedFraction: Float = 0f,
    val isFullyVerified: Boolean = false,
    val unresolvedGapCount: Int = 0,
    val failedIntervalCount: Int = 0,
    val verifiedIntervalCount: Int = 0,
    val bufferedRanges: List<TimeWindowRange> = emptyList(),
    val failedRanges: List<TimeWindowRange> = emptyList(),
)

/**
 * Single source of truth for timeline coverage and transcript integrity.
 *
 * Strictly separates three distinct epistemic states for every millisecond [0, totalDurationMs):
 * 1. Verified Dialogue (`VERIFIED_DIALOGUE`, verified=true): transcribed/imported cues confirmed.
 * 2. Verified Silence (`VERIFIED_SILENCE`, verified=true): model or track explicitly confirmed no speech.
 * 3. Unverified Coverage (`PENDING`, `IN_PROGRESS`, `FAILED`, `PARTIAL_RECOVERY`, `LEGACY_UNVERIFIED`, verified=false).
 *
 * Unverified regions are never collapsed into continuous verified coverage tubes and never allow
 * the app to report full completion until every interval in [0, totalDurationMs) has `verified == true`.
 */
class TranscriptCoverageLedger(
    initialDurationMs: Long = 0L,
    initialIntervals: List<CoverageInterval> = emptyList(),
) {
    private val lock = Any()
    private val revisions = MutableStateFlow(0L)
    val workRevision: Long get() = revisions.value

    /** Sleep through a temporarily empty queue until a worker or repair releases its reservation. */
    suspend fun awaitWorkRelease(revision: Long): Boolean {
        val pending = synchronized(lock) {
            repairReservations.isNotEmpty() || intervals.any { it.status == CoverageStatus.IN_PROGRESS }
        }
        if (workRevision != revision) return true
        if (!pending) return false
        revisions.first { it != revision }
        return true
    }
    private val repairReservations = mutableMapOf<String, Pair<Long, Long>>()

    fun tryReserveRepair(key: String, startMs: Long, endMs: Long): Boolean = synchronized(lock) {
        if (endMs <= startMs || repairReservations.values.any { (s, e) -> s < endMs && e > startMs } ||
            intervals.any { it.status == CoverageStatus.IN_PROGRESS && it.startMs < endMs && it.endMs > startMs }) {
            return@synchronized false
        }
        repairReservations[key] = startMs to endMs
        true
    }

    fun releaseRepair(key: String) {
        synchronized(lock) { if (repairReservations.remove(key) != null) revisions.value++ }
    }

    fun revokeRepairReservations(): Boolean = synchronized(lock) {
        if (repairReservations.isEmpty()) return@synchronized false
        repairReservations.clear()
        revisions.value++
        true
    }

    fun clearRepairReservations() { revokeRepairReservations() }
    private var totalDurationMs: Long = initialDurationMs.coerceAtLeast(0L)
    private val intervals = mutableListOf<CoverageInterval>()
    private var verifiedRanges: List<TimeWindowRange> = emptyList()

    private var requestedWindows = 0
    private var completedWindows = 0
    private var acceptedEvents = 0
    private var rejectedDuplicates = 0
    private var invalidOrAmbiguousTimestampCount = 0
    private var retryCount = 0
    private var quotaFallbackTransitions = 0
    private var activeSource: SubtitleSourceKind = SubtitleSourceKind.BUILT_IN_DEMO
    private val duplicateDecisions = ArrayDeque<DuplicateDecisionRecord>(32)

    init {
        reset(totalDurationMs, initialIntervals)
    }

    fun reset(
        durationMs: Long,
        seedIntervals: List<CoverageInterval> = emptyList(),
        source: SubtitleSourceKind = activeSource,
    ) {
        synchronized(lock) {
            totalDurationMs = durationMs.coerceAtLeast(0L)
            activeSource = source
            revisions.value++
            intervals.clear()
            verifiedRanges = emptyList()
            requestedWindows = 0
            completedWindows = 0
            acceptedEvents = 0
            rejectedDuplicates = 0
            invalidOrAmbiguousTimestampCount = 0
            retryCount = 0
            quotaFallbackTransitions = 0
            duplicateDecisions.clear()

            if (totalDurationMs <= 0L) return

            if (seedIntervals.isEmpty()) {
                intervals.add(
                    CoverageInterval(
                        startMs = 0L,
                        endMs = totalDurationMs,
                        status = CoverageStatus.PENDING,
                        source = source,
                        verified = false,
                    )
                )
            } else {
                val sanitized = seedIntervals.map { iv ->
                    if (iv.status == CoverageStatus.IN_PROGRESS) {
                        iv.copy(status = CoverageStatus.PENDING, verified = false)
                    } else {
                        iv.copy(
                            verified = iv.verified &&
                                (iv.status == CoverageStatus.VERIFIED_DIALOGUE || iv.status == CoverageStatus.VERIFIED_SILENCE),
                            silenceConfirmed = iv.status == CoverageStatus.VERIFIED_SILENCE,
                        )
                    }
                }
                rebuildNormalizedLocked(sanitized)
            }
        }
    }

    fun updateDuration(newDurationMs: Long) {
        synchronized(lock) {
            val safeDur = newDurationMs.coerceAtLeast(0L)
            if (safeDur == totalDurationMs) return
            totalDurationMs = safeDur
            if (safeDur <= 0L) {
                intervals.clear()
                verifiedRanges = emptyList()
                return
            }
            rebuildNormalizedLocked(intervals.toList())
        }
    }

    /** The decoder can learn the runtime after file import or after the last timed subtitle.
     * A complete track covers its quiet tail. Partial and generated tracks still require evidence there.
     */
    fun reconcileMediaDuration(durationMs: Long, events: List<DialogueEvent>, source: SubtitleSourceKind) {
        if (durationMs <= 0L) return
        if (events.isNotEmpty() && (source == SubtitleSourceKind.UPLOADED_FILE || source == SubtitleSourceKind.EMBEDDED_TRACK)) {
            markAuthoritativeTrack(durationMs, events, source)
        } else {
            updateDuration(durationMs)
        }
    }

    fun updateActiveSource(source: SubtitleSourceKind) {
        synchronized(lock) {
            if (sourcePriority(source) >= sourcePriority(activeSource) || intervals.none { it.verified }) {
                activeSource = source
            }
        }
    }

    fun recordWindowRequested() {
        synchronized(lock) { requestedWindows++ }
    }

    fun recordWindowCompleted(retriesUsed: Int = 0) {
        synchronized(lock) {
            completedWindows++
            if (retriesUsed > 0) retryCount += retriesUsed
        }
    }

    fun recordRetry(count: Int = 1) {
        synchronized(lock) { retryCount += count.coerceAtLeast(0) }
    }

    fun recordInvalidOrAmbiguousTimestamp(count: Int = 1) {
        synchronized(lock) { invalidOrAmbiguousTimestampCount += count.coerceAtLeast(0) }
    }

    fun recordQuotaFallbackTransition() {
        synchronized(lock) { quotaFallbackTransitions++ }
    }

    fun recordDuplicateDecisions(records: List<DuplicateDecisionRecord>) {
        if (records.isEmpty()) return
        synchronized(lock) {
            for (rec in records) {
                if (rec.accepted) acceptedEvents++ else rejectedDuplicates++
                if (duplicateDecisions.size >= 30) duplicateDecisions.removeFirst()
                duplicateDecisions.addLast(rec)
            }
        }
    }

    fun recordEventCounts(accepted: Int, rejected: Int) {
        synchronized(lock) {
            acceptedEvents += accepted.coerceAtLeast(0)
            rejectedDuplicates += rejected.coerceAtLeast(0)
        }
    }

    fun markInProgress(
        startMs: Long,
        endMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val s = startMs.coerceAtLeast(0L)
            val e = endMs.coerceAtLeast(s)
            if (e <= s) return
            ensureDurationAtLeastLocked(e)
            applyIntervalMutationLocked(s, e) { existing ->
                if (existing.verified) {
                    existing
                } else {
                    existing.copy(
                        status = CoverageStatus.IN_PROGRESS,
                        verified = false,
                        updatedAtEpochMs = nowEpochMs,
                    )
                }
            }
        }
    }

    /** Release unfinished subranges while preserving any evidence, failures, and retry counts already committed. */
    fun releaseScoutWindow(startMs: Long, endMs: Long) = synchronized(lock) {
        if (intervals.any { it.status == CoverageStatus.IN_PROGRESS && it.startMs < endMs && it.endMs > startMs }) {
            applyIntervalMutationLocked(startMs, endMs) { span ->
                if (span.status == CoverageStatus.IN_PROGRESS) span.copy(status = CoverageStatus.PENDING) else span
            }
        }
    }

    /** Only newly verified evidence belongs to this repair. Later Scout evidence supersedes its token. */
    fun recordRepairEvidence(evidence: List<CoverageInterval>, token: String): List<CoverageInterval> = synchronized(lock) {
        val originals = mutableListOf<CoverageInterval>()
        for (span in evidence) {
            if (!span.verified || span.startMs < 0 || span.endMs <= span.startMs ||
                (span.status == CoverageStatus.VERIFIED_DIALOGUE && span.dialogueCount <= 0)) continue
            ensureDurationAtLeastLocked(span.endMs)
            if (sourcePriority(span.source) > sourcePriority(activeSource)) activeSource = span.source
            applyIntervalMutationLocked(span.startMs, span.endMs) { previous ->
                if (previous.verified || previous.status == CoverageStatus.IN_PROGRESS) previous else {
                    originals.add(previous)
                    span.copy(startMs = previous.startMs, endMs = previous.endMs, repairToken = token)
                }
            }
        }
        originals
    }

    fun undoRepairEvidence(originals: List<CoverageInterval>, token: String) {
        synchronized(lock) {
            for (original in originals) {
                applyIntervalMutationLocked(original.startMs, original.endMs) { current ->
                    if (current.repairToken == token) original.copy(startMs = current.startMs, endMs = current.endMs) else current
                }
            }
        }
    }

    fun recordOutcome(
        startMs: Long,
        endMs: Long,
        status: CoverageStatus,
        source: SubtitleSourceKind = activeSource,
        dialogueCount: Int = 0,
        failureReason: String = "",
        incrementRetry: Boolean = false,
        forceOverrideHigherAuthority: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val s = startMs.coerceAtLeast(0L)
            val e = endMs.coerceAtLeast(s)
            if (e <= s) return
            ensureDurationAtLeastLocked(e)
            if (sourcePriority(source) >= sourcePriority(activeSource)) {
                activeSource = source
            }
            val emptyDialogue = status == CoverageStatus.VERIFIED_DIALOGUE && dialogueCount <= 0
            val nextStatus = if (emptyDialogue) CoverageStatus.FAILED else status
            val reason = if (emptyDialogue) failureReason.ifBlank { "EMPTY_VERIFIED_DIALOGUE_REJECTED" } else failureReason
            val isVerified = nextStatus == CoverageStatus.VERIFIED_DIALOGUE || nextStatus == CoverageStatus.VERIFIED_SILENCE
            val isSilence = nextStatus == CoverageStatus.VERIFIED_SILENCE
            applyIntervalMutationLocked(s, e) { existing ->
                if (!forceOverrideHigherAuthority &&
                    existing.verified &&
                    (!isVerified || sourcePriority(existing.source) > sourcePriority(source))
                ) {
                    existing
                } else {
                    val nextRetries = if (incrementRetry || emptyDialogue) existing.retryAttempts + 1 else existing.retryAttempts
                    existing.copy(
                        status = nextStatus,
                        repairToken = null,
                        source = source,
                        verified = isVerified,
                        retryAttempts = nextRetries,
                        lastFailureReason = if (isVerified) "" else reason.ifBlank { existing.lastFailureReason },
                        dialogueCount = if (isSilence) 0 else dialogueCount.coerceAtLeast(0),
                        silenceConfirmed = isSilence,
                        updatedAtEpochMs = nowEpochMs,
                    )
                }
            }
        }
    }

    /**
     * Marks an authoritative subtitle track (`UPLOADED_FILE` or `EMBEDDED_TRACK`) across [0, durationMs)
     * using real cue regions as `VERIFIED_DIALOGUE` and remaining track regions as `VERIFIED_SILENCE`.
     */
    fun markAuthoritativeTrack(
        durationMs: Long,
        events: List<DialogueEvent>,
        source: SubtitleSourceKind,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        require(source == SubtitleSourceKind.UPLOADED_FILE || source == SubtitleSourceKind.EMBEDDED_TRACK) {
            "markAuthoritativeTrack requires UPLOADED_FILE or EMBEDDED_TRACK"
        }
        val maxCueEnd = events.maxOfOrNull { it.endTimeMs } ?: 0L
        val effectiveDur = durationMs.takeIf { it > 0L } ?: maxCueEnd.coerceAtLeast(0L)
        val built = mutableListOf<CoverageInterval>()
        val realSorted = events
            .filter { it.endTimeMs > it.startTimeMs && it.endTimeMs > 0L && it.startTimeMs < effectiveDur &&
                (it.sourceText.isNotBlank() || it.translatedText.isNotBlank()) }
            .sortedBy { it.startTimeMs }
        if (realSorted.isEmpty()) {
            // A missing or unparsable track supplies no evidence, including for silence.
            updateDuration(effectiveDur)
            return
        }

        var cursor = 0L
        var i = 0
        while (i < realSorted.size) {
            var spanStart = realSorted[i].startTimeMs.coerceIn(0L, effectiveDur)
            var spanEnd = realSorted[i].endTimeMs.coerceIn(spanStart, effectiveDur)
            var count = 1
            while (i + 1 < realSorted.size && realSorted[i + 1].startTimeMs <= spanEnd + 500L) {
                i++
                spanEnd = maxOf(spanEnd, realSorted[i].endTimeMs.coerceIn(spanStart, effectiveDur))
                count++
            }
            if (spanStart > cursor) {
                built.add(
                    CoverageInterval(
                        startMs = cursor,
                        endMs = spanStart,
                        status = CoverageStatus.VERIFIED_SILENCE,
                        source = source,
                        verified = true,
                        silenceConfirmed = true,
                        updatedAtEpochMs = nowEpochMs,
                    )
                )
            }
            if (spanEnd > spanStart) {
                built.add(
                    CoverageInterval(
                        startMs = maxOf(cursor, spanStart),
                        endMs = spanEnd,
                        status = CoverageStatus.VERIFIED_DIALOGUE,
                        source = source,
                        verified = true,
                        dialogueCount = count,
                        silenceConfirmed = false,
                        updatedAtEpochMs = nowEpochMs,
                    )
                )
                cursor = maxOf(cursor, spanEnd)
            }
            i++
        }
        if (cursor < effectiveDur) {
            built.add(
                CoverageInterval(
                    startMs = cursor,
                    endMs = effectiveDur,
                    status = CoverageStatus.VERIFIED_SILENCE,
                    source = source,
                    verified = true,
                    silenceConfirmed = true,
                    updatedAtEpochMs = nowEpochMs,
                )
            )
        }
        reset(effectiveDur, built, source)
        synchronized(lock) {
            acceptedEvents = realSorted.size
        }
    }

    fun intervalsSnapshot(): List<CoverageInterval> = synchronized(lock) { intervals.toList() }

    fun bufferedRanges(): List<TimeWindowRange> = synchronized(lock) { verifiedRanges }

    fun failedRanges(): List<TimeWindowRange> = synchronized(lock) {
        val failed = intervals
            .filter {
                (it.status == CoverageStatus.FAILED || it.status == CoverageStatus.PARTIAL_RECOVERY) &&
                    it.endMs > it.startMs
            }
            .map { TimeWindowRange(it.startMs, it.endMs) }
        LookaheadWindowPlanner.coalesceBufferedRanges(failed, bridgeGapMs = 0L)
    }

    fun furthestAnalyzedMs(): Long = synchronized(lock) {
        intervals
            .filter { it.status != CoverageStatus.PENDING && it.status != CoverageStatus.IN_PROGRESS }
            .maxOfOrNull { it.endMs }
            ?: 0L
    }

    /** Continuous verified speech or confirmed silence forward from the actual playhead. */
    fun verifiedLeadMs(positionMs: Long): Long = synchronized(lock) {
        val position = positionMs.coerceAtLeast(0L)
        val index = verifiedRanges.binarySearchBy(position) { it.startMs }
        val range = verifiedRanges.getOrNull(if (index >= 0) index else -index - 2)
        if (range == null) 0L else (range.endMs - position).coerceAtLeast(0L)
    }

    fun continuousVerifiedLeadFrom(positionMs: Long): Long = verifiedLeadMs(positionMs)

    fun coverageStatusAt(positionMs: Long): CoverageStatus? = synchronized(lock) {
        if (positionMs !in 0L until totalDurationMs) return@synchronized null
        val index = intervals.binarySearchBy(positionMs) { it.startMs }
        intervals.getOrNull(if (index >= 0) index else -index - 2)?.status
    }

    fun hasFailedIntervalAt(positionMs: Long): Boolean = when (coverageStatusAt(positionMs)) {
        CoverageStatus.FAILED, CoverageStatus.PARTIAL_RECOVERY -> true
        else -> false
    }

    fun firstUnverifiedGapFrom(positionMs: Long, horizonMs: Long): CoverageInterval? = synchronized(lock) {
        val start = positionMs.coerceIn(0L, totalDurationMs)
        val end = start + horizonMs.coerceIn(0L, totalDurationMs - start)
        intervals.firstOrNull { !it.verified && it.endMs > start && it.startMs < end }
    }

    fun continuousVerifiedUpToMs(): Long = verifiedLeadMs(0L)

    fun isFullyVerified(): Boolean = synchronized(lock) {
        totalDurationMs > 0L &&
            intervals.isNotEmpty() &&
            intervals.all { it.verified } &&
            continuousVerifiedUpToMs() >= totalDurationMs
    }

    fun summary(): CoverageSummary = synchronized(lock) {
        var verifiedDialogueMs = 0L
        var verifiedSilenceMs = 0L
        var partialMs = 0L
        var failedMs = 0L
        var pendingMs = 0L
        var legacyMs = 0L
        var failedCount = 0
        var unresolvedCount = 0
        var verifiedCount = 0

        for (iv in intervals) {
            val dur = (iv.endMs - iv.startMs).coerceAtLeast(0L)
            when (iv.status) {
                CoverageStatus.VERIFIED_DIALOGUE -> {
                    verifiedDialogueMs += dur
                    verifiedCount++
                }
                CoverageStatus.VERIFIED_SILENCE -> {
                    verifiedSilenceMs += dur
                    verifiedCount++
                }
                CoverageStatus.PARTIAL_RECOVERY -> {
                    partialMs += dur
                    unresolvedCount++
                }
                CoverageStatus.FAILED -> {
                    failedMs += dur
                    failedCount++
                    unresolvedCount++
                }
                CoverageStatus.PENDING, CoverageStatus.IN_PROGRESS -> {
                    pendingMs += dur
                    unresolvedCount++
                }
                CoverageStatus.LEGACY_UNVERIFIED -> {
                    legacyMs += dur
                    unresolvedCount++
                }
            }
        }

        val totalVerified = verifiedDialogueMs + verifiedSilenceMs
        val contVerified = continuousVerifiedUpToMs()
        val frac = if (totalDurationMs > 0L) {
            (totalVerified.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        CoverageSummary(
            totalDurationMs = totalDurationMs,
            verifiedDialogueMs = verifiedDialogueMs,
            verifiedSilenceMs = verifiedSilenceMs,
            totalVerifiedMs = totalVerified,
            partialRecoveryMs = partialMs,
            failedMs = failedMs,
            pendingMs = pendingMs,
            legacyUnverifiedMs = legacyMs,
            furthestAnalyzedMs = furthestAnalyzedMs(),
            continuousVerifiedUpToMs = contVerified,
            verifiedFraction = frac,
            isFullyVerified = isFullyVerified(),
            unresolvedGapCount = unresolvedCount,
            failedIntervalCount = failedCount,
            verifiedIntervalCount = verifiedCount,
            bufferedRanges = bufferedRanges(),
            failedRanges = failedRanges(),
        )
    }

    fun diagnostics(): TranscriptDiagnostics = synchronized(lock) {
        val sum = summary()
        TranscriptDiagnostics(
            requestedWindows = requestedWindows,
            completedWindows = completedWindows,
            verifiedDialogueMs = sum.verifiedDialogueMs,
            verifiedSilenceMs = sum.verifiedSilenceMs,
            partialRecoveryMs = sum.partialRecoveryMs,
            failedMs = sum.failedMs,
            pendingMs = sum.pendingMs,
            legacyUnverifiedMs = sum.legacyUnverifiedMs,
            verifiedDialogueCount = intervals.count { it.status == CoverageStatus.VERIFIED_DIALOGUE },
            verifiedSilenceCount = intervals.count { it.status == CoverageStatus.VERIFIED_SILENCE },
            failedCount = intervals.count { it.status == CoverageStatus.FAILED },
            pendingCount = intervals.count { it.status == CoverageStatus.PENDING || it.status == CoverageStatus.IN_PROGRESS },
            legacyUnverifiedCount = intervals.count { it.status == CoverageStatus.LEGACY_UNVERIFIED || it.status == CoverageStatus.PARTIAL_RECOVERY },
            acceptedEvents = acceptedEvents,
            rejectedDuplicates = rejectedDuplicates,
            invalidOrAmbiguousTimestampCount = invalidOrAmbiguousTimestampCount,
            retryCount = retryCount,
            quotaFallbackTransitions = quotaFallbackTransitions,
            activeSource = activeSource,
            recentDuplicateDecisions = duplicateDecisions.toList(),
        )
    }

    /**
     * Selects the highest-priority unverified interval to process or heal next relative to [playheadMs]:
     * 1. Interval currently containing [playheadMs]
     * 2. Intervals immediately ahead in [playheadMs, playheadMs + lookaheadHorizonMs]
     * 3. Remaining intervals ahead toward end of video
     * 4. Earlier gaps behind [playheadMs]
     *
     * Respects [maxRetryAttempts] for previously `FAILED` intervals so healing is always bounded.
     */
    fun nextPriorityInterval(
        playheadMs: Long,
        maxRetryAttempts: Int = 3,
        lookaheadHorizonMs: Long = 180_000L,
        openingWindowMs: Long = LookaheadWindowPlanner.OPENING_SCOUT_WINDOW_SEC * 1000L,
        rollingWindowMs: Long = LookaheadWindowPlanner.ROLLING_SCOUT_WINDOW_SEC * 1000L,
        startAtPlayhead: Boolean = false,
    ): CoverageInterval? = synchronized(lock) {
        if (totalDurationMs <= 0L) return@synchronized null
        val safePlayhead = playheadMs.coerceIn(0L, totalDurationMs)
        // Keep the remaining unreserved part of each gap available to Scout.
        val available = if (repairReservations.isEmpty()) intervals else intervals.flatMap { interval ->
            var slices = listOf(interval)
            for ((start, end) in repairReservations.values) {
                slices = slices.flatMap { slice ->
                    if (slice.endMs <= start || slice.startMs >= end) listOf(slice)
                    else listOfNotNull(
                        slice.copy(endMs = start).takeIf { slice.startMs < start },
                        slice.copy(startMs = end).takeIf { slice.endMs > end },
                    )
                }
            }
            slices
        }
        val candidates = available.filter { iv ->
            !iv.verified &&
                iv.status != CoverageStatus.IN_PROGRESS &&
                iv.endMs > iv.startMs &&
                ((iv.status != CoverageStatus.FAILED && iv.status != CoverageStatus.PARTIAL_RECOVERY) || iv.retryAttempts < maxRetryAttempts)
        }
        if (candidates.isEmpty()) return@synchronized null

        val chosen = candidates.firstOrNull { safePlayhead in it.startMs until it.endMs }
            ?: candidates
                .filter { it.startMs >= safePlayhead && it.startMs <= safePlayhead + lookaheadHorizonMs }
                .minWithOrNull(compareBy<CoverageInterval> { it.startMs }.thenBy { retryWeight(it) })
            ?: candidates
                .filter { it.startMs > safePlayhead }
                .minWithOrNull(compareBy<CoverageInterval> { it.startMs }.thenBy { retryWeight(it) })
            ?: candidates
                .filter { it.endMs <= safePlayhead || it.startMs < safePlayhead }
                .maxWithOrNull(compareBy<CoverageInterval> { it.startMs }.thenByDescending { -retryWeight(it) })
            ?: candidates.first()

        val maxChunkMs = if (chosen.startMs == 0L) openingWindowMs else rollingWindowMs
        val sliceStart = if (startAtPlayhead && safePlayhead in chosen.startMs until chosen.endMs) {
            safePlayhead
        } else if (safePlayhead in chosen.startMs until chosen.endMs &&
            (chosen.endMs - chosen.startMs) > maxChunkMs &&
            chosen.status != CoverageStatus.FAILED
        ) {
            // If playhead jumped far inside a huge unverified PENDING span, align around playhead!
            val offsetFromStart = safePlayhead - chosen.startMs
            val alignedStep = (offsetFromStart / maxChunkMs) * maxChunkMs
            (chosen.startMs + alignedStep).coerceIn(chosen.startMs, chosen.endMs)
        } else {
            chosen.startMs
        }
        val sliceEnd = minOf(chosen.endMs, sliceStart + maxChunkMs)
        chosen.copy(startMs = sliceStart, endMs = sliceEnd.coerceAtLeast(sliceStart))
    }

    private fun retryWeight(iv: CoverageInterval): Int = when (iv.status) {
        CoverageStatus.PENDING -> 0
        CoverageStatus.LEGACY_UNVERIFIED -> 1
        CoverageStatus.PARTIAL_RECOVERY -> 2
        CoverageStatus.FAILED -> 3 + iv.retryAttempts
        else -> 10
    }

    private fun ensureDurationAtLeastLocked(endMs: Long) {
        if (endMs > totalDurationMs) {
            val prevDur = totalDurationMs
            totalDurationMs = endMs
            if (prevDur < endMs) {
                intervals.add(
                    CoverageInterval(
                        startMs = prevDur,
                        endMs = endMs,
                        status = CoverageStatus.PENDING,
                        source = activeSource,
                        verified = false,
                    )
                )
                rebuildNormalizedLocked(intervals.toList())
            }
        }
    }

    private fun applyIntervalMutationLocked(
        targetStartMs: Long,
        targetEndMs: Long,
        transform: (CoverageInterval) -> CoverageInterval,
    ) {
        if (targetEndMs <= targetStartMs) return
        val updated = mutableListOf<CoverageInterval>()
        for (iv in intervals) {
            if (iv.endMs <= targetStartMs || iv.startMs >= targetEndMs) {
                updated.add(iv)
            } else {
                if (iv.startMs < targetStartMs) {
                    updated.add(iv.copy(endMs = targetStartMs))
                }
                val overlapStart = maxOf(iv.startMs, targetStartMs)
                val overlapEnd = minOf(iv.endMs, targetEndMs)
                if (overlapEnd > overlapStart) {
                    updated.add(
                        transform(
                            iv.copy(startMs = overlapStart, endMs = overlapEnd)
                        )
                    )
                }
                if (iv.endMs > targetEndMs) {
                    updated.add(iv.copy(startMs = targetEndMs))
                }
            }
        }
        rebuildNormalizedLocked(updated)
    }

    private fun rebuildNormalizedLocked(rawList: List<CoverageInterval>) {
        if (totalDurationMs <= 0L) {
            intervals.clear()
            verifiedRanges = emptyList()
            return
        }
        val clipped = rawList
            .mapNotNull { iv ->
                val s = iv.startMs.coerceIn(0L, totalDurationMs)
                val e = iv.endMs.coerceIn(0L, totalDurationMs)
                if (e > s) iv.copy(startMs = s, endMs = e) else null
            }
            .sortedWith(compareBy<CoverageInterval> { it.startMs }.thenBy { it.endMs })

        // Flatten overlaps cleanly (later/higher-priority clipped segment takes intersecting range)
        val flat = mutableListOf<CoverageInterval>()
        for (iv in clipped) {
            if (flat.isEmpty()) {
                flat.add(iv)
                continue
            }
            val last = flat.last()
            if (iv.startMs < last.endMs) {
                if (iv.startMs > last.startMs) {
                    flat[flat.lastIndex] = last.copy(endMs = iv.startMs)
                    flat.add(iv)
                } else {
                    if (iv.endMs < last.endMs) {
                        flat[flat.lastIndex] = iv
                        flat.add(last.copy(startMs = iv.endMs))
                    } else {
                        flat[flat.lastIndex] = iv
                    }
                }
            } else {
                flat.add(iv)
            }
        }

        val withGapsFilled = mutableListOf<CoverageInterval>()
        var cursor = 0L
        for (iv in flat) {
            if (iv.startMs > cursor) {
                withGapsFilled.add(
                    CoverageInterval(
                        startMs = cursor,
                        endMs = iv.startMs,
                        status = CoverageStatus.PENDING,
                        source = activeSource,
                        verified = false,
                    )
                )
            }
            withGapsFilled.add(iv)
            cursor = maxOf(cursor, iv.endMs)
        }
        if (cursor < totalDurationMs) {
            withGapsFilled.add(
                CoverageInterval(
                    startMs = cursor,
                    endMs = totalDurationMs,
                    status = CoverageStatus.PENDING,
                    source = activeSource,
                    verified = false,
                )
            )
        }

        // Coalesce strictly identical adjacent intervals
        val coalesced = mutableListOf<CoverageInterval>()
        for (iv in withGapsFilled) {
            val prev = coalesced.lastOrNull()
            if (prev != null &&
                prev.endMs == iv.startMs &&
                prev.status == iv.status &&
                prev.source == iv.source &&
                prev.verified == iv.verified &&
                prev.retryAttempts == iv.retryAttempts &&
                prev.lastFailureReason == iv.lastFailureReason &&
                prev.repairToken == iv.repairToken &&
                prev.silenceConfirmed == iv.silenceConfirmed
            ) {
                coalesced[coalesced.lastIndex] = prev.copy(
                    endMs = iv.endMs,
                    dialogueCount = prev.dialogueCount + iv.dialogueCount,
                    updatedAtEpochMs = maxOf(prev.updatedAtEpochMs, iv.updatedAtEpochMs),
                )
            } else {
                coalesced.add(iv)
            }
        }

        intervals.clear()
        intervals.addAll(coalesced)
        verifiedRanges = LookaheadWindowPlanner.coalesceBufferedRanges(
            coalesced.filter { it.verified }.map { TimeWindowRange(it.startMs, it.endMs) }, bridgeGapMs = 0L)
        revisions.value++
    }

    companion object {
        fun sourcePriority(source: SubtitleSourceKind): Int = when (source) {
            SubtitleSourceKind.BUILT_IN_DEMO -> 0
            SubtitleSourceKind.AI_DRAFT -> 1
            SubtitleSourceKind.EMBEDDED_TRACK, SubtitleSourceKind.EMBEDDED_PARTIAL -> 2
            SubtitleSourceKind.UPLOADED_FILE -> 3
        }

        /**
         * Restores coverage intervals from a cached transcript or safely migrates a legacy v1 cache.
         *
         * Legacy v1 caches stored only a single `scoutedUpToMs` watermark, which hid mid-video failures
         * and unanalyzed pockets. Migration preserves every saved dialogue event while marking cue-backed
         * regions as `PARTIAL_RECOVERY` and empty v1 intervals below `scoutedUpToMs` as `LEGACY_UNVERIFIED`
         * so missing sections are re-checked instead of assumed verified silence.
         */
        fun restoreOrMigrate(
            cached: CachedVideoTranscript,
            resolvedDurationMs: Long,
        ): List<CoverageInterval> {
            val maxCueEnd = cached.dialogueEvents.maxOfOrNull { it.endTimeMs } ?: 0L
            val effectiveDur = maxOf(resolvedDurationMs, cached.durationMs, maxCueEnd, 1_000L)

            if (cached.cacheVersion >= CachedVideoTranscript.CURRENT_CACHE_VERSION &&
                cached.coverageIntervals.isNotEmpty()
            ) {
                val ledger = TranscriptCoverageLedger(
                    initialDurationMs = effectiveDur,
                    initialIntervals = cached.coverageIntervals.map { iv ->
                        if (iv.status == CoverageStatus.IN_PROGRESS) {
                            iv.copy(status = CoverageStatus.PENDING, verified = false)
                        } else {
                            iv
                        }
                    },
                )
                return ledger.intervalsSnapshot()
            }

            // Legacy v1 migration: never treat scoutedUpToMs as proven verified coverage
            val legacyHorizon = cached.scoutedUpToMs.coerceIn(0L, effectiveDur)
            val realEvents = cached.dialogueEvents
                .filter { evt ->
                    (evt.sourceText.isNotBlank() || evt.translatedText.isNotBlank()) &&
                        !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                        !evt.sourceText.startsWith("Web Cinema ready")
                }
                .sortedBy { it.startTimeMs }

            if (cached.subtitleSource == SubtitleSourceKind.UPLOADED_FILE ||
                cached.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK
            ) {
                val ledger = TranscriptCoverageLedger()
                if (realEvents.isNotEmpty()) {
                    ledger.markAuthoritativeTrack(effectiveDur, realEvents, cached.subtitleSource, cached.cachedAtEpochMs)
                } else {
                    ledger.reset(
                        effectiveDur,
                        listOf(
                            CoverageInterval(
                                startMs = 0L,
                                endMs = effectiveDur,
                                status = CoverageStatus.LEGACY_UNVERIFIED,
                                source = cached.subtitleSource,
                                verified = false,
                                updatedAtEpochMs = cached.cachedAtEpochMs,
                            )
                        ),
                    )
                }
                return ledger.intervalsSnapshot()
            }

            // Group legacy AI cues into cue-backed PARTIAL_RECOVERY spans; uncovered regions inside old horizon -> LEGACY_UNVERIFIED
            val ledger = TranscriptCoverageLedger(initialDurationMs = effectiveDur)
            if (legacyHorizon > 0L) {
                ledger.recordOutcome(
                    startMs = 0L,
                    endMs = legacyHorizon,
                    status = CoverageStatus.LEGACY_UNVERIFIED,
                    source = cached.subtitleSource,
                    failureReason = "Migrated from legacy cache v${cached.cacheVersion}",
                    nowEpochMs = cached.cachedAtEpochMs,
                )
            }
            var i = 0
            while (i < realEvents.size) {
                var spanStart = (realEvents[i].startTimeMs - 500L).coerceIn(0L, effectiveDur)
                var spanEnd = (realEvents[i].endTimeMs + 500L).coerceIn(spanStart, effectiveDur)
                var count = 1
                while (i + 1 < realEvents.size && realEvents[i + 1].startTimeMs <= spanEnd + 2_500L) {
                    i++
                    spanEnd = maxOf(spanEnd, (realEvents[i].endTimeMs + 500L).coerceIn(spanStart, effectiveDur))
                    count++
                }
                if (spanEnd > spanStart) {
                    ledger.recordOutcome(
                        startMs = spanStart,
                        endMs = spanEnd,
                        status = CoverageStatus.PARTIAL_RECOVERY,
                        source = cached.subtitleSource,
                        dialogueCount = count,
                        failureReason = "Migrated v${cached.cacheVersion} cues awaiting full-window verification",
                        nowEpochMs = cached.cachedAtEpochMs,
                    )
                }
                i++
            }
            return ledger.intervalsSnapshot()
        }
    }
}
