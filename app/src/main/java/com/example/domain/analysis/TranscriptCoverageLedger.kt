package com.example.domain.analysis

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
    val continuousReleasedUpToMs: Long = continuousVerifiedUpToMs,
    val releasedFraction: Float = verifiedFraction,
    val captionGapMs: Long = 0L,
    val unavailableEvidenceMs: Long = 0L,
    val releasedFallbackMs: Long = 0L,
    val captionGapCount: Int = 0,
    val unavailableEvidenceCount: Int = 0,
    val releasedFallbackCount: Int = 0,
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
    private var totalDurationMs: Long = initialDurationMs.coerceAtLeast(0L)
    private val intervals = mutableListOf<CoverageInterval>()

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
            intervals.clear()
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
                return
            }
            rebuildNormalizedLocked(intervals.toList())
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
            // Epistemic integrity guard (Operation Golden Video Section 3):
            // Never allow an empty/rejected window with dialogueCount == 0 to be committed as VERIFIED_DIALOGUE.
            val effectiveStatus = if (status == CoverageStatus.VERIFIED_DIALOGUE && dialogueCount <= 0) {
                CoverageStatus.FAILED
            } else {
                status
            }
            val effectiveReason = if (status == CoverageStatus.VERIFIED_DIALOGUE && dialogueCount <= 0) {
                failureReason.ifBlank { "EMPTY_VERIFIED_DIALOGUE_REJECTED" }
            } else {
                failureReason
            }
            val effectiveIncrementRetry = incrementRetry ||
                (status == CoverageStatus.VERIFIED_DIALOGUE && dialogueCount <= 0)
            val isVerified = effectiveStatus.isVerified
            val isSilence = effectiveStatus.isConfirmedSilence
            applyIntervalMutationLocked(s, e) { existing ->
                val protectAuthoritative = !forceOverrideHigherAuthority &&
                    sourcePriority(existing.source) > sourcePriority(source) &&
                    (existing.verified || (existing.source == SubtitleSourceKind.UPLOADED_FILE && !isVerified))
                if (protectAuthoritative) {
                    existing
                } else {
                    val nextRetries = if (effectiveIncrementRetry) existing.retryAttempts + 1 else existing.retryAttempts
                    existing.copy(
                        status = effectiveStatus,
                        source = source,
                        verified = isVerified,
                        retryAttempts = nextRetries,
                        lastFailureReason = if (isVerified) "" else effectiveReason.ifBlank { existing.lastFailureReason },
                        dialogueCount = if (isSilence) 0 else dialogueCount.coerceAtLeast(0),
                        silenceConfirmed = isSilence,
                        updatedAtEpochMs = nowEpochMs,
                    )
                }
            }
        }
    }

    /**
     * Marks an imported or embedded subtitle track (`UPLOADED_FILE` or `EMBEDDED_TRACK`) across
     * `[0, durationMs)`.
     *
     * Epistemic rule:
     * - Cue-covered spans are recorded as `VERIFIED_DIALOGUE` when [cuesIndependentlyVerified] is true
     *   (or `RELEASED_FALLBACK` for unverified automatic captions).
     * - Un-cued gaps between cues and the trailing region after the final cue are NEVER assumed to be
     *   `VERIFIED_SILENCE`; they are recorded as `CAPTION_GAP` (`verified = false`,
     *   `silenceConfirmed = false`) so playback stays ready via released-playability while the
     *   epistemic ledger honestly distinguishes missing captions from confirmed silence.
     */
    fun markAuthoritativeTrack(
        durationMs: Long,
        events: List<DialogueEvent>,
        source: SubtitleSourceKind,
        cuesIndependentlyVerified: Boolean = (source == SubtitleSourceKind.UPLOADED_FILE),
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        require(source == SubtitleSourceKind.UPLOADED_FILE || source == SubtitleSourceKind.EMBEDDED_TRACK) {
            "markAuthoritativeTrack requires UPLOADED_FILE or EMBEDDED_TRACK"
        }
        val maxCueEnd = events.maxOfOrNull { it.endTimeMs } ?: 0L
        val effectiveDur = maxOf(durationMs, maxCueEnd).coerceAtLeast(1_000L)
        val built = mutableListOf<CoverageInterval>()
        val realSorted = events
            .filter { it.endTimeMs > it.startTimeMs }
            .sortedBy { it.startTimeMs }

        val cueStatus = if (cuesIndependentlyVerified) {
            CoverageStatus.VERIFIED_DIALOGUE
        } else {
            CoverageStatus.RELEASED_FALLBACK
        }

        var cursor = 0L
        var i = 0
        while (i < realSorted.size) {
            val spanStart = realSorted[i].startTimeMs.coerceIn(0L, effectiveDur)
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
                        status = CoverageStatus.CAPTION_GAP,
                        source = source,
                        verified = false,
                        silenceConfirmed = false,
                        lastFailureReason = "No cue in caption track (not confirmed silence)",
                        updatedAtEpochMs = nowEpochMs,
                    )
                )
            }
            if (spanEnd > spanStart) {
                built.add(
                    CoverageInterval(
                        startMs = maxOf(cursor, spanStart),
                        endMs = spanEnd,
                        status = cueStatus,
                        source = source,
                        verified = cueStatus.isVerified,
                        dialogueCount = count,
                        silenceConfirmed = false,
                        lastFailureReason = if (cueStatus.isVerified) "" else "Caption track cues (unverified against audio)",
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
                    status = CoverageStatus.CAPTION_GAP,
                    source = source,
                    verified = false,
                    silenceConfirmed = false,
                    lastFailureReason = "Post-final-cue track tail (not confirmed silence)",
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

    fun bufferedRanges(): List<TimeWindowRange> = synchronized(lock) {
        val verified = intervals
            .filter { it.verified && it.endMs > it.startMs }
            .map { TimeWindowRange(it.startMs, it.endMs) }
        LookaheadWindowPlanner.coalesceBufferedRanges(verified, bridgeGapMs = 1L)
    }

    fun failedRanges(): List<TimeWindowRange> = synchronized(lock) {
        val failed = intervals
            .filter {
                (it.status == CoverageStatus.FAILED || it.status == CoverageStatus.PARTIAL_RECOVERY) &&
                    it.endMs > it.startMs
            }
            .map { TimeWindowRange(it.startMs, it.endMs) }
        LookaheadWindowPlanner.coalesceBufferedRanges(failed, bridgeGapMs = 1L)
    }

    fun furthestAnalyzedMs(): Long = synchronized(lock) {
        intervals
            .filter { it.status != CoverageStatus.PENDING && it.status != CoverageStatus.IN_PROGRESS }
            .maxOfOrNull { it.endMs }
            ?: 0L
    }

    fun continuousVerifiedUpToMs(): Long = synchronized(lock) {
        var cursor = 0L
        for (iv in intervals) {
            if (iv.startMs > cursor) break
            if (!iv.verified) break
            cursor = maxOf(cursor, iv.endMs)
        }
        cursor
    }

    fun releasedRanges(): List<TimeWindowRange> = synchronized(lock) {
        val playable = intervals
            .filter { it.status.isReleasedPlayable && it.endMs > it.startMs }
            .map { TimeWindowRange(it.startMs, it.endMs) }
        LookaheadWindowPlanner.coalesceBufferedRanges(playable, bridgeGapMs = 1L)
    }

    fun continuousReleasedUpToMs(): Long = synchronized(lock) {
        var cursor = 0L
        for (iv in intervals) {
            if (iv.startMs > cursor) break
            if (!iv.status.isReleasedPlayable) break
            cursor = maxOf(cursor, iv.endMs)
        }
        cursor
    }

    /**
     * Returns contiguous playable/released lead starting at [positionMs].
     * Kept strictly separate from [continuousVerifiedLeadFrom]: a deliberately released fallback
     * or caption-track gap may allow smooth playback without claiming `verified == true`.
     */
    fun continuousReleasedLeadFrom(positionMs: Long): Long = synchronized(lock) {
        if (totalDurationMs <= 0L || intervals.isEmpty()) return@synchronized 0L
        val start = positionMs.coerceIn(0L, totalDurationMs)
        if (start >= totalDurationMs) return@synchronized 0L
        var cursor = start
        for (iv in intervals) {
            if (iv.endMs <= cursor) continue
            if (iv.startMs > cursor) break
            if (!iv.status.isReleasedPlayable) break
            cursor = maxOf(cursor, iv.endMs)
        }
        (cursor - start).coerceAtLeast(0L)
    }

    /**
     * Returns the exact duration in milliseconds of contiguous verified coverage
     * (`VERIFIED_DIALOGUE` or `VERIFIED_SILENCE`) starting at [positionMs] and extending
     * forward without any unverified gap.
     */
    fun continuousVerifiedLeadFrom(positionMs: Long): Long = synchronized(lock) {
        if (totalDurationMs <= 0L || intervals.isEmpty()) return@synchronized 0L
        val start = positionMs.coerceIn(0L, totalDurationMs)
        if (start >= totalDurationMs) return@synchronized 0L
        var cursor = start
        for (iv in intervals) {
            if (iv.endMs <= cursor) continue
            if (iv.startMs > cursor) break
            if (!iv.verified) break
            cursor = maxOf(cursor, iv.endMs)
        }
        (cursor - start).coerceAtLeast(0L)
    }

    /**
     * Returns the epistemic [CoverageStatus] at [positionMs], or null if outside known duration.
     */
    fun coverageStatusAt(positionMs: Long): CoverageStatus? = synchronized(lock) {
        if (totalDurationMs <= 0L || intervals.isEmpty()) return@synchronized null
        val clamped = positionMs.coerceIn(0L, (totalDurationMs - 1L).coerceAtLeast(0L))
        intervals.firstOrNull { clamped in it.startMs until it.endMs }?.status
            ?: intervals.lastOrNull()?.status
    }

    /**
     * Returns true if [positionMs] falls inside a `FAILED` or `PARTIAL_RECOVERY` interval.
     */
    fun hasFailedIntervalAt(positionMs: Long): Boolean {
        val status = coverageStatusAt(positionMs)
        return status == CoverageStatus.FAILED || status == CoverageStatus.PARTIAL_RECOVERY
    }

    /**
     * Returns the first unverified interval within `[positionMs, positionMs + horizonMs]`, or null
     * if the entire horizon is contiguously verified.
     */
    fun firstUnverifiedGapFrom(positionMs: Long, horizonMs: Long): CoverageInterval? = synchronized(lock) {
        if (totalDurationMs <= 0L || intervals.isEmpty()) return@synchronized null
        val start = positionMs.coerceIn(0L, totalDurationMs)
        val end = (start + horizonMs.coerceAtLeast(0L)).coerceAtMost(totalDurationMs)
        intervals.firstOrNull { iv ->
            !iv.verified && iv.endMs > start && iv.startMs < end
        }
    }

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
        var captionGapMs = 0L
        var unavailableMs = 0L
        var fallbackMs = 0L
        var failedCount = 0
        var unresolvedCount = 0
        var verifiedCount = 0
        var captionGapCount = 0
        var unavailableCount = 0
        var fallbackCount = 0

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
                CoverageStatus.CAPTION_GAP -> {
                    captionGapMs += dur
                    captionGapCount++
                    unresolvedCount++
                }
                CoverageStatus.SUSPECTED_MISSING_DIALOGUE -> {
                    failedMs += dur
                    failedCount++
                    unresolvedCount++
                }
                CoverageStatus.UNAVAILABLE_EVIDENCE -> {
                    unavailableMs += dur
                    unavailableCount++
                    unresolvedCount++
                }
                CoverageStatus.RELEASED_FALLBACK -> {
                    fallbackMs += dur
                    fallbackCount++
                    unresolvedCount++
                }
            }
        }

        val totalVerified = verifiedDialogueMs + verifiedSilenceMs
        val totalReleased = totalVerified + captionGapMs + unavailableMs + fallbackMs
        val contVerified = continuousVerifiedUpToMs()
        val contReleased = continuousReleasedUpToMs()
        val frac = if (totalDurationMs > 0L) {
            (totalVerified.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val relFrac = if (totalDurationMs > 0L) {
            (totalReleased.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
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
            continuousReleasedUpToMs = contReleased,
            releasedFraction = relFrac,
            captionGapMs = captionGapMs,
            unavailableEvidenceMs = unavailableMs,
            releasedFallbackMs = fallbackMs,
            captionGapCount = captionGapCount,
            unavailableEvidenceCount = unavailableCount,
            releasedFallbackCount = fallbackCount,
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
    ): CoverageInterval? = synchronized(lock) {
        if (totalDurationMs <= 0L) return@synchronized null
        val safePlayhead = playheadMs.coerceIn(0L, totalDurationMs)
        val candidates = intervals.filter { iv ->
            !iv.verified &&
                iv.status != CoverageStatus.IN_PROGRESS &&
                iv.status != CoverageStatus.UNAVAILABLE_EVIDENCE &&
                iv.status != CoverageStatus.RELEASED_FALLBACK &&
                !(iv.status == CoverageStatus.CAPTION_GAP && iv.source == SubtitleSourceKind.UPLOADED_FILE) &&
                iv.endMs > iv.startMs &&
                (iv.status != CoverageStatus.FAILED || iv.retryAttempts < maxRetryAttempts)
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
        val sliceStart = if (safePlayhead in chosen.startMs until chosen.endMs &&
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
        CoverageStatus.SUSPECTED_MISSING_DIALOGUE -> 1
        CoverageStatus.LEGACY_UNVERIFIED -> 1
        CoverageStatus.CAPTION_GAP -> 2
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
    }

    companion object {
        fun sourcePriority(source: SubtitleSourceKind): Int = when (source) {
            SubtitleSourceKind.BUILT_IN_DEMO -> 0
            SubtitleSourceKind.AI_DRAFT -> 1
            SubtitleSourceKind.EMBEDDED_TRACK -> 2
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
                    ledger.markAuthoritativeTrack(
                        durationMs = effectiveDur,
                        events = realEvents,
                        source = cached.subtitleSource,
                        nowEpochMs = cached.cachedAtEpochMs,
                    )
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
