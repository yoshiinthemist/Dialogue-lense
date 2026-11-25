package com.example.domain.pipeline

import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.CaptionOriginKind
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.PlacementMode
import com.example.data.model.ReferenceCaptionCue
import com.example.data.model.ReferenceCaptionTrack
import com.example.data.model.Speaker
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.layout.BalloonLayoutEngine
import kotlin.math.abs

enum class DialogueReviewStatus {
    VERIFIED_MATCH,
    REPAIRED_FROM_REFERENCE,
    CONFIRMED_SILENCE,
    FALLBACK_UNVERIFIED,
    GAP_UNRESOLVED,
    UNAVAILABLE_EVIDENCE,
}

enum class AttributionReviewStatus {
    CONFIRMED,
    NEUTRAL_UNCERTAIN,
    REPAIRED,
}

enum class PlacementReviewStatus {
    STABLE,
    FIXED_CONSERVATIVE,
    REPAIRED,
}

data class IntervalReviewOutcome(
    val dialogueStatus: DialogueReviewStatus,
    val attributionStatus: AttributionReviewStatus,
    val placementStatus: PlacementReviewStatus,
    val dialogueConfidence: Float,
    val attributionConfidence: Float,
    val placementStable: Boolean,
    val isIndependentlyVerified: Boolean,
    val needsModelEscalation: Boolean = false,
    val notes: List<String> = emptyList(),
    val repairedEvents: List<DialogueEvent> = emptyList(),
) {
    val coverageStatus: CoverageStatus
        get() = when (dialogueStatus) {
            DialogueReviewStatus.VERIFIED_MATCH,
            DialogueReviewStatus.REPAIRED_FROM_REFERENCE ->
                if (isIndependentlyVerified) CoverageStatus.VERIFIED_DIALOGUE else CoverageStatus.RELEASED_FALLBACK
            DialogueReviewStatus.CONFIRMED_SILENCE ->
                if (isIndependentlyVerified) CoverageStatus.VERIFIED_SILENCE else CoverageStatus.CAPTION_GAP
            DialogueReviewStatus.FALLBACK_UNVERIFIED -> CoverageStatus.RELEASED_FALLBACK
            DialogueReviewStatus.GAP_UNRESOLVED -> CoverageStatus.SUSPECTED_MISSING_DIALOGUE
            DialogueReviewStatus.UNAVAILABLE_EVIDENCE -> CoverageStatus.UNAVAILABLE_EVIDENCE
        }
}

data class DraftInterval(
    val intervalId: String,
    val mediaId: String,
    val sessionToken: Long,
    val seekGeneration: Long,
    val sourceRevision: Long,
    val draftRevision: Long,
    val configFingerprint: String,
    val startMs: Long,
    val endMs: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val events: List<DialogueEvent>,
    val speakers: List<Speaker>,
    val referenceTrackId: String? = null,
    val evidenceProvenance: String = "",
    val uncertaintyNotes: List<String> = emptyList(),
    val isConfirmedSilence: Boolean = false,
    val hasUnavailableEvidence: Boolean = false,
    val hasIndependentAudioOrFrameEvidence: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val deadlineEpochMs: Long = createdAtEpochMs + DEFAULT_REVIEW_DEADLINE_MS,
) {
    companion object {
        const val DEFAULT_REVIEW_DEADLINE_MS = 4_500L
    }
}

data class ReleasedInterval(
    val intervalId: String,
    val mediaId: String,
    val sourceRevision: Long,
    val draftRevision: Long,
    val releaseRevision: Long,
    val configFingerprint: String,
    val startMs: Long,
    val endMs: Long,
    val events: List<DialogueEvent>,
    val speakers: List<Speaker>,
    val reviewOutcome: IntervalReviewOutcome,
    val isPlayableFallback: Boolean,
    val releasedAtEpochMs: Long = System.currentTimeMillis(),
)

data class ReleasedPlaybackSnapshot(
    val mediaId: String = "",
    val sessionToken: Long = 0L,
    val seekGeneration: Long = 0L,
    val releaseRevision: Long = 0L,
    val layoutCacheRevision: Long = 0L,
    val configFingerprint: String = "",
    val events: List<DialogueEvent> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val releasedIntervals: List<ReleasedInterval> = emptyList(),
    val pendingDraftCount: Int = 0,
    val repairedIntervalCount: Int = 0,
    val fallbackReleaseCount: Int = 0,
    val staleCallbacksRejected: Int = 0,
    val visibleCorrectionCount: Int = 0,
    val lastReleaseSummary: String = "",
    val referenceTrackSummary: String = "No reference captions attached",
)

data class YouTubeCapabilityReport(
    val hasEmbeddedPlayer: Boolean = true,
    val hasProgrammaticCaptions: Boolean = false,
    val hasRawAudioAccess: Boolean = false,
    val hasFutureFrameAccess: Boolean = false,
    val captionProvenance: String = "Unavailable",
    val limitationNote: String = "YouTube IFrame player controls playback only; raw audio and future frames are not exposed locally.",
) {
    val canIndependentlyVerifyAudio: Boolean
        get() = hasRawAudioAccess || hasProgrammaticCaptions
}

/**
 * Scout 2: Independent deterministic reviewer and repair engine.
 *
 * Inspects a specific [DraftInterval] revision against:
 * - Immutable [ReferenceCaptionTrack] (aligned by time window and language without overwriting translations)
 * - Coverage integrity (empty interval is never assumed to be silence; real silence never invents dialogue)
 * - Speaker attribution confidence kept separate from dialogue confidence (visible face alone never proves speaker)
 * - Proposed visual presentation evaluated using the exact same [BalloonLayoutEngine] used during playback.
 *
 * Deterministic checks run first with zero cloud cost; `needsModelEscalation` is only raised for
 * genuine ambiguity when independent source evidence is available.
 */
class Scout2IndependentReviewer {

    fun reviewDraft(
        draft: DraftInterval,
        referenceTrack: ReferenceCaptionTrack?,
        layoutEngine: BalloonLayoutEngine,
        projectTemplate: DialogueLensProject,
        liveFaces: List<LiveFaceObservation> = emptyList(),
        styleSettings: BalloonStyleSettings = projectTemplate.styleSettings,
        displayMode: CaptionDisplayMode = CaptionDisplayMode.BALLOONS,
    ): IntervalReviewOutcome {
        val notes = mutableListOf<String>()
        val alignedRefs = referenceTrack?.cues
            ?.filter { cue ->
                cue.endMs >= draft.startMs - TIME_ALIGN_SLACK_MS &&
                    cue.startMs <= draft.endMs + TIME_ALIGN_SLACK_MS &&
                    cue.text.isNotBlank()
            }
            .orEmpty()

        // 1. Handle unavailable source evidence honestly without faking verification
        if (draft.hasUnavailableEvidence && draft.events.isEmpty() && alignedRefs.isEmpty()) {
            notes.add("Source evidence unavailable for interval")
            return IntervalReviewOutcome(
                dialogueStatus = DialogueReviewStatus.UNAVAILABLE_EVIDENCE,
                attributionStatus = AttributionReviewStatus.NEUTRAL_UNCERTAIN,
                placementStatus = PlacementReviewStatus.FIXED_CONSERVATIVE,
                dialogueConfidence = 0f,
                attributionConfidence = 0f,
                placementStable = true,
                isIndependentlyVerified = false,
                needsModelEscalation = false,
                notes = notes,
                repairedEvents = emptyList(),
            )
        }

        // 2. Coverage check: empty draft vs confirmed silence vs caption dropout/gap
        if (draft.events.isEmpty()) {
            if (alignedRefs.isNotEmpty()) {
                // Draft missed dialogue that is present in reference captions -> repair from reference!
                notes.add("Recovered ${alignedRefs.size} cue(s) missed by Scout 1 from reference captions")
                val recovered = alignedRefs.map { ref ->
                    DialogueEvent(
                        id = "sc2_${ref.cueId}",
                        startTimeMs = ref.startMs.coerceIn(draft.startMs, draft.endMs),
                        endTimeMs = ref.endMs.coerceIn(
                            ref.startMs.coerceIn(draft.startMs, draft.endMs) + 350L,
                            maxOf(draft.endMs, ref.startMs + 350L),
                        ),
                        sourceText = ref.text,
                        translatedText = ref.text,
                        speakerId = null,
                        speakerConfidence = 0.35f,
                        faceTrackId = null,
                        faceConfidence = 0f,
                        shotId = projectTemplate.shots.firstOrNull()?.id ?: "shot_1",
                        dialogueType = DialogueType.SPOKEN,
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        attributionSummary = "Unidentified speaker",
                        stableCueKey = ref.cueId,
                    )
                }
                val (placedEvents, placementStatus, placementStable) = validatePresentation(
                    events = recovered,
                    layoutEngine = layoutEngine,
                    projectTemplate = projectTemplate,
                    liveFaces = liveFaces,
                    styleSettings = styleSettings,
                    displayMode = displayMode,
                    notes = notes,
                )
                return IntervalReviewOutcome(
                    dialogueStatus = DialogueReviewStatus.REPAIRED_FROM_REFERENCE,
                    attributionStatus = AttributionReviewStatus.NEUTRAL_UNCERTAIN,
                    placementStatus = placementStatus,
                    dialogueConfidence = if (referenceTrack?.originKind == CaptionOriginKind.AUTOMATIC_ASR) 0.72f else 0.90f,
                    attributionConfidence = 0.35f,
                    placementStable = placementStable,
                    isIndependentlyVerified = referenceTrack?.originKind != CaptionOriginKind.AUTOMATIC_ASR,
                    needsModelEscalation = false,
                    notes = notes,
                    repairedEvents = placedEvents,
                )
            }
            if (draft.isConfirmedSilence && draft.hasIndependentAudioOrFrameEvidence) {
                notes.add("Silence confirmed by audio/media evidence; no dialogue invented")
                return IntervalReviewOutcome(
                    dialogueStatus = DialogueReviewStatus.CONFIRMED_SILENCE,
                    attributionStatus = AttributionReviewStatus.CONFIRMED,
                    placementStatus = PlacementReviewStatus.STABLE,
                    dialogueConfidence = 0.92f,
                    attributionConfidence = 1f,
                    placementStable = true,
                    isIndependentlyVerified = true,
                    needsModelEscalation = false,
                    notes = notes,
                    repairedEvents = emptyList(),
                )
            }
            // Empty interval without confirmed silence is NOT automatically silence!
            notes.add("Empty interval not confirmed as silence; held as unresolved gap")
            return IntervalReviewOutcome(
                dialogueStatus = DialogueReviewStatus.GAP_UNRESOLVED,
                attributionStatus = AttributionReviewStatus.NEUTRAL_UNCERTAIN,
                placementStatus = PlacementReviewStatus.STABLE,
                dialogueConfidence = 0f,
                attributionConfidence = 0f,
                placementStable = true,
                isIndependentlyVerified = false,
                needsModelEscalation = draft.hasIndependentAudioOrFrameEvidence,
                notes = notes,
                repairedEvents = emptyList(),
            )
        }

        // 3. Rolling-caption normalization & persistent identity preservation
        val deduplicated = normalizeRollingCaptionsPreservingIdentity(draft.events, notes)

        // 4. Language-aware text & timing comparison against original reference captions
        val targetLangNorm = normalizeLanguageTag(draft.targetLanguage)
        val sourceLangNorm = normalizeLanguageTag(draft.sourceLanguage)
        val refLangNorm = normalizeLanguageTag(referenceTrack?.languageCode.orEmpty())
        val isTranslationRequested = targetLangNorm.isNotBlank() &&
            sourceLangNorm.isNotBlank() &&
            targetLangNorm != "auto" &&
            sourceLangNorm != "auto" &&
            targetLangNorm != sourceLangNorm
        val refMatchesTarget = refLangNorm.isNotBlank() && targetLangNorm.isNotBlank() && refLangNorm == targetLangNorm
        val refMatchesSource = refLangNorm.isNotBlank() && sourceLangNorm.isNotBlank() && refLangNorm == sourceLangNorm

        var repairedDialogueCount = 0
        var matchedReferenceCount = 0
        var escalatedForAmbiguity = false
        val textReviewed = deduplicated.map { evt ->
            val stableKey = evt.stableCueKey.ifBlank { evt.id }
            val overlappingRefs = alignedRefs.filter { ref ->
                ref.endMs >= evt.startTimeMs - 900L && ref.startMs <= evt.endTimeMs + 900L
            }
            if (overlappingRefs.isEmpty()) {
                evt.copy(stableCueKey = stableKey)
            } else {
                val combinedRefText = overlappingRefs.joinToString(" ") { it.text.trim() }.trim()
                val refTokens = tokenizeNormalized(combinedRefText)
                val canCompareAgainstTranslated = !isTranslationRequested || refMatchesTarget
                val compareTargetText = if (canCompareAgainstTranslated) {
                    evt.translatedText.ifBlank { evt.sourceText }
                } else {
                    // Translation is active and reference is in source language:
                    // compare ONLY against sourceText and NEVER overwrite translatedText with source strings!
                    evt.sourceText
                }
                val draftTokens = tokenizeNormalized(compareTargetText)
                val overlapScore = tokenOverlapScore(draftTokens, refTokens)

                when {
                    overlapScore >= EQUIVALENT_TEXT_THRESHOLD -> {
                        matchedReferenceCount++
                        evt.copy(stableCueKey = stableKey)
                    }
                    // When reference is authored or user-imported and draft diverges materially
                    referenceTrack != null &&
                        (referenceTrack.originKind == CaptionOriginKind.AUTHORED ||
                            referenceTrack.originKind == CaptionOriginKind.USER_IMPORTED) &&
                        overlapScore < MATERIAL_MISMATCH_THRESHOLD -> {
                        repairedDialogueCount++
                        if (canCompareAgainstTranslated) {
                            notes.add("Repaired mismatched draft text for ${evt.id} using ${referenceTrack.originKind.label} reference")
                            evt.copy(
                                sourceText = combinedRefText,
                                translatedText = combinedRefText,
                                stableCueKey = stableKey,
                            )
                        } else if (refMatchesSource) {
                            notes.add("Repaired sourceText for ${evt.id} while preserving ${draft.targetLanguage} translation")
                            evt.copy(
                                sourceText = combinedRefText,
                                stableCueKey = stableKey,
                            )
                        } else {
                            // Different language reference — preserve requested display translation
                            evt.copy(stableCueKey = stableKey)
                        }
                    }
                    else -> {
                        if (overlapScore < MATERIAL_MISMATCH_THRESHOLD && draft.hasIndependentAudioOrFrameEvidence) {
                            escalatedForAmbiguity = true
                        }
                        evt.copy(stableCueKey = stableKey)
                    }
                }
            }
        }

        val dialogueStatus: DialogueReviewStatus
        val dialogueConf: Float
        val independentlyVerified: Boolean
        when {
            repairedDialogueCount > 0 -> {
                dialogueStatus = DialogueReviewStatus.REPAIRED_FROM_REFERENCE
                dialogueConf = 0.90f
                independentlyVerified = referenceTrack?.originKind != CaptionOriginKind.AUTOMATIC_ASR
            }
            matchedReferenceCount > 0 -> {
                dialogueStatus = DialogueReviewStatus.VERIFIED_MATCH
                dialogueConf = if (referenceTrack?.originKind == CaptionOriginKind.AUTOMATIC_ASR) 0.78f else 0.94f
                independentlyVerified = referenceTrack?.originKind != CaptionOriginKind.AUTOMATIC_ASR ||
                    draft.hasIndependentAudioOrFrameEvidence
            }
            draft.hasIndependentAudioOrFrameEvidence -> {
                dialogueStatus = DialogueReviewStatus.VERIFIED_MATCH
                dialogueConf = 0.85f
                independentlyVerified = true
            }
            else -> {
                // No independent reference or audio evidence -> NEVER falsely claim independently verified!
                dialogueStatus = DialogueReviewStatus.FALLBACK_UNVERIFIED
                dialogueConf = 0.65f
                independentlyVerified = false
                notes.add("No independent caption/audio corroboration; released as unverified fallback")
            }
        }

        // 5. Attribution review (strictly separate from text confidence; visible face alone never proves speaker!)
        var neutralizedAttributionCount = 0
        val attributionReviewed = textReviewed.map { evt ->
            if (evt.lockedByUser) return@map evt
            val isOffScreenOrNonSpoken = evt.dialogueType != DialogueType.SPOKEN
            val hasUncertainSpeaker = evt.speakerId.isNullOrBlank() ||
                evt.speakerConfidence < ATTRIBUTION_CONFIRM_THRESHOLD ||
                evt.attributionSummary.equals("Unidentified speaker", ignoreCase = true)

            if (!isOffScreenOrNonSpoken && hasUncertainSpeaker) {
                neutralizedAttributionCount++
                // Release dependable dialogue with neutral presentation rather than guessing from a visible listener face
                evt.copy(
                    speakerId = if (evt.speakerConfidence < 0.35f) null else evt.speakerId,
                    speakerConfidence = evt.speakerConfidence.coerceAtMost(0.45f),
                    faceTrackId = null,
                    faceConfidence = 0f,
                    anchorPosition = null,
                    placementMode = PlacementMode.NEUTRAL_FALLBACK,
                    attributionSummary = "Unidentified speaker",
                )
            } else if (isOffScreenOrNonSpoken) {
                // Off-screen speaker must never be attached to a visible listener face
                evt.copy(
                    faceTrackId = null,
                    faceConfidence = 0f,
                    anchorPosition = null,
                    placementMode = PlacementMode.EDGE_DOCKED,
                )
            } else {
                evt
            }
        }

        val attributionStatus = when {
            neutralizedAttributionCount == attributionReviewed.size && attributionReviewed.isNotEmpty() ->
                AttributionReviewStatus.NEUTRAL_UNCERTAIN
            neutralizedAttributionCount > 0 -> AttributionReviewStatus.REPAIRED
            else -> AttributionReviewStatus.CONFIRMED
        }
        val avgAttrConf = attributionReviewed
            .map { it.speakerConfidence }
            .average()
            .toFloat()
            .takeIf { !it.isNaN() }
            ?: 0f

        // 6. Presentation & layout review using the EXACT SAME BalloonLayoutEngine used during playback
        val (placementReviewed, placementStatus, placementStable) = validatePresentation(
            events = attributionReviewed,
            layoutEngine = layoutEngine,
            projectTemplate = projectTemplate,
            liveFaces = liveFaces,
            styleSettings = styleSettings,
            displayMode = displayMode,
            notes = notes,
        )

        return IntervalReviewOutcome(
            dialogueStatus = dialogueStatus,
            attributionStatus = attributionStatus,
            placementStatus = placementStatus,
            dialogueConfidence = dialogueConf,
            attributionConfidence = avgAttrConf,
            placementStable = placementStable,
            isIndependentlyVerified = independentlyVerified,
            needsModelEscalation = escalatedForAmbiguity,
            notes = notes,
            repairedEvents = placementReviewed,
        )
    }

    private fun normalizeRollingCaptionsPreservingIdentity(
        events: List<DialogueEvent>,
        notes: MutableList<String>,
    ): List<DialogueEvent> {
        if (events.size <= 1) return events.map { it.copy(stableCueKey = it.stableCueKey.ifBlank { it.id }) }
        val sorted = events.sortedBy { it.startTimeMs }
        val out = mutableListOf<DialogueEvent>()
        for (incoming in sorted) {
            val prev = out.lastOrNull()
            if (prev != null &&
                prev.dialogueType == incoming.dialogueType &&
                prev.speakerId == incoming.speakerId &&
                incoming.startTimeMs - prev.endTimeMs <= ROLLING_CAPTION_GAP_MS
            ) {
                val prevNorm = normalizeTextForCompare(prev.translatedText.ifBlank { prev.sourceText })
                val nextNorm = normalizeTextForCompare(incoming.translatedText.ifBlank { incoming.sourceText })
                if (prevNorm.isNotBlank() && nextNorm.isNotBlank()) {
                    if (nextNorm == prevNorm) {
                        // Exact rolling duplicate: extend previous cue smoothly preserving identity
                        out[out.lastIndex] = prev.copy(
                            endTimeMs = maxOf(prev.endTimeMs, incoming.endTimeMs),
                            stableCueKey = prev.stableCueKey.ifBlank { prev.id },
                        )
                        notes.add("Collapsed duplicate rolling caption preserving identity ${prev.id}")
                        continue
                    }
                    if (nextNorm.startsWith(prevNorm) && nextNorm.length > prevNorm.length + 2) {
                        // Progressive rolling growth: update in place preserving original id + stableCueKey!
                        out[out.lastIndex] = incoming.copy(
                            id = prev.id,
                            startTimeMs = prev.startTimeMs,
                            endTimeMs = maxOf(prev.endTimeMs, incoming.endTimeMs),
                            stableCueKey = prev.stableCueKey.ifBlank { prev.id },
                        )
                        notes.add("Merged progressive rolling caption preserving identity ${prev.id}")
                        continue
                    }
                }
            }
            out.add(incoming.copy(stableCueKey = incoming.stableCueKey.ifBlank { incoming.id }))
        }
        return out
    }

    private fun validatePresentation(
        events: List<DialogueEvent>,
        layoutEngine: BalloonLayoutEngine,
        projectTemplate: DialogueLensProject,
        liveFaces: List<LiveFaceObservation>,
        styleSettings: BalloonStyleSettings,
        displayMode: CaptionDisplayMode,
        notes: MutableList<String>,
    ): Triple<List<DialogueEvent>, PlacementReviewStatus, Boolean> {
        if (events.isEmpty()) return Triple(events, PlacementReviewStatus.STABLE, true)
        val previewProject = projectTemplate.copy(dialogueEvents = events)
        var fixedCount = 0
        val checked = events.map { evt ->
            if (evt.lockedByUser) return@map evt
            val stability = layoutEngine.evaluateCuePlacementStability(
                project = previewProject,
                event = evt,
                styleSettings = styleSettings,
                liveFaces = liveFaces,
                fixedZoneTaillessMode = displayMode == CaptionDisplayMode.FIXED_ZONES,
            )
            if (!stability.isWithinBounds || stability.hasSevereFaceCollision || stability.maxDriftNorm > MAX_ALLOWED_PREVIEW_DRIFT) {
                fixedCount++
                notes.add("Stabilized placement for ${evt.id} to conservative fixed position (${stability.reason})")
                evt.copy(
                    placementMode = PlacementMode.NEUTRAL_FALLBACK,
                    anchorPosition = null,
                )
            } else {
                evt
            }
        }
        val status = when {
            fixedCount == 0 -> PlacementReviewStatus.STABLE
            fixedCount == checked.size -> PlacementReviewStatus.FIXED_CONSERVATIVE
            else -> PlacementReviewStatus.REPAIRED
        }
        return Triple(checked, status, true)
    }

    companion object {
        private const val TIME_ALIGN_SLACK_MS = 1_500L
        private const val ROLLING_CAPTION_GAP_MS = 2_400L
        private const val EQUIVALENT_TEXT_THRESHOLD = 0.58f
        private const val MATERIAL_MISMATCH_THRESHOLD = 0.34f
        private const val ATTRIBUTION_CONFIRM_THRESHOLD = 0.55f
        private const val MAX_ALLOWED_PREVIEW_DRIFT = 0.16f

        fun normalizeLanguageTag(raw: String): String {
            val clean = raw.trim().lowercase()
            if (clean.isBlank() || clean.startsWith("auto")) return "auto"
            val base = clean.substringBefore('-').substringBefore('_')
            return when (base) {
                "english", "en" -> "en"
                "japanese", "ja", "jp" -> "ja"
                "korean", "ko", "kr" -> "ko"
                "spanish", "es" -> "es"
                "french", "fr" -> "fr"
                "german", "de" -> "de"
                "mandarin", "chinese", "zh" -> "zh"
                "italian", "it" -> "it"
                "portuguese", "pt" -> "pt"
                "hindi", "hi" -> "hi"
                "arabic", "ar" -> "ar"
                else -> base
            }
        }

        fun normalizeTextForCompare(text: String): String = text
            .lowercase()
            .replace(Regex("[\\p{Punct}“”‘’—–…♪♫]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun tokenizeNormalized(text: String): Set<String> = normalizeTextForCompare(text)
            .split(' ')
            .filter { it.length >= 2 }
            .toSet()

        fun tokenOverlapScore(a: Set<String>, b: Set<String>): Float {
            if (a.isEmpty() && b.isEmpty()) return 1f
            if (a.isEmpty() || b.isEmpty()) return 0f
            val shared = a.intersect(b).size.toFloat()
            val denom = minOf(a.size, b.size).coerceAtLeast(1).toFloat()
            return (shared / denom).coerceIn(0f, 1f)
        }
    }
}

/**
 * Real Release Gate separating Scout 1 working drafts from released playback snapshots.
 *
 * Guarantees:
 * - Scout 1 drafts never appear on screen until reviewed or explicitly degraded at deadline.
 * - Text, timing, attribution, and placement are published atomically as one consistent revision.
 * - Stale async callbacks (old mediaId, sessionToken, seekGeneration, or configFingerprint) are
 *   strictly rejected even if coroutine cancellation arrived late.
 * - Deadline fallbacks NEVER mark an unfinished or unverified draft as independently verified.
 * - Controlled visible corrections preserve cue identity and enforce anti-oscillation cooldowns.
 */
class ReleasedTimelineStore {

    private val lock = Any()
    private var activeMediaId: String = ""
    private var activeSessionToken: Long = 0L
    private var activeSeekGeneration: Long = 0L
    private var activeConfigFingerprint: String = ""
    private var releaseRevision: Long = 0L
    private var layoutCacheRevision: Long = 0L

    private val workingDrafts = linkedMapOf<String, DraftInterval>()
    private val releasedByIntervalId = linkedMapOf<String, ReleasedInterval>()
    private val releasedEventsById = linkedMapOf<String, DialogueEvent>()
    private val releasedSpeakersById = linkedMapOf<String, Speaker>()
    private var referenceTrack: ReferenceCaptionTrack? = null

    private var repairedIntervalCount: Int = 0
    private var fallbackReleaseCount: Int = 0
    private var staleCallbacksRejected: Int = 0
    private var visibleCorrectionCount: Int = 0
    private var lastReleaseSummary: String = ""
    private val lastVisibleCorrectionAtMs = mutableMapOf<String, Long>()

    fun resetSession(
        mediaId: String,
        sessionToken: Long,
        seekGeneration: Long = 0L,
        configFingerprint: String = "",
        initialEvents: List<DialogueEvent> = emptyList(),
        initialSpeakers: List<Speaker> = emptyList(),
    ): ReleasedPlaybackSnapshot = synchronized(lock) {
        activeMediaId = mediaId
        activeSessionToken = sessionToken
        activeSeekGeneration = seekGeneration
        activeConfigFingerprint = configFingerprint
        releaseRevision = 0L
        layoutCacheRevision = 0L
        workingDrafts.clear()
        releasedByIntervalId.clear()
        releasedEventsById.clear()
        releasedSpeakersById.clear()
        referenceTrack = null
        repairedIntervalCount = 0
        fallbackReleaseCount = 0
        staleCallbacksRejected = 0
        visibleCorrectionCount = 0
        lastVisibleCorrectionAtMs.clear()
        initialSpeakers.forEach { releasedSpeakersById[it.id] = it }
        initialEvents.forEach { evt ->
            val key = evt.stableCueKey.ifBlank { evt.id }
            releasedEventsById[evt.id] = evt.copy(stableCueKey = key)
        }
        lastReleaseSummary = if (initialEvents.isNotEmpty()) {
            "Initialized ${initialEvents.size} released cue(s)"
        } else {
            "Session initialized"
        }
        buildSnapshotLocked()
    }

    fun updateSeekOrConfigGeneration(
        seekGeneration: Long = this.activeSeekGeneration,
        configFingerprint: String = this.activeConfigFingerprint,
        invalidateLayoutOnly: Boolean = false,
    ): ReleasedPlaybackSnapshot = synchronized(lock) {
        activeSeekGeneration = seekGeneration
        activeConfigFingerprint = configFingerprint
        layoutCacheRevision++
        if (!invalidateLayoutOnly) {
            // Discard pending drafts from superseded config/seek while keeping released dialogue cache valid
            workingDrafts.entries.removeAll { (_, draft) ->
                draft.seekGeneration != activeSeekGeneration ||
                    (activeConfigFingerprint.isNotBlank() && draft.configFingerprint != activeConfigFingerprint)
            }
        }
        buildSnapshotLocked()
    }

    /**
     * Invalidates ONLY spatial layout cache revision (e.g., orientation, viewport, or font scale change)
     * without discarding cached dialogue or speaker attribution.
     */
    fun invalidateLayoutCacheOnly(): ReleasedPlaybackSnapshot = synchronized(lock) {
        layoutCacheRevision++
        buildSnapshotLocked()
    }

    /**
     * Stores or updates immutable reference captions separately from the visible released timeline.
     * Receiving native captions NEVER automatically replaces visible released dialogue events.
     */
    fun updateReferenceTrack(
        track: ReferenceCaptionTrack,
        sessionToken: Long = activeSessionToken,
    ): Boolean = synchronized(lock) {
        if (track.mediaId != activeMediaId || sessionToken != activeSessionToken) {
            staleCallbacksRejected++
            return@synchronized false
        }
        val current = referenceTrack
        // Prefer authored/user-imported over ASR, or newer revision of equal/higher quality
        val incomingRank = originRank(track.originKind)
        val currentRank = current?.let { originRank(it.originKind) } ?: -1
        if (current == null || incomingRank > currentRank || (incomingRank == currentRank && track.sourceRevision >= current.sourceRevision)) {
            referenceTrack = track
            return@synchronized true
        }
        false
    }

    fun currentReferenceTrack(): ReferenceCaptionTrack? = synchronized(lock) { referenceTrack }

    /**
     * Stages a Scout 1 draft without exposing it to playback yet.
     */
    fun stageDraft(draft: DraftInterval): Boolean = synchronized(lock) {
        if (!isValidContextLocked(draft.mediaId, draft.sessionToken, draft.seekGeneration, draft.configFingerprint)) {
            staleCallbacksRejected++
            return@synchronized false
        }
        val existing = workingDrafts[draft.intervalId]
        if (existing != null && draft.draftRevision < existing.draftRevision) {
            staleCallbacksRejected++
            return@synchronized false
        }
        workingDrafts[draft.intervalId] = draft
        true
    }

    fun pendingDraftsSnapshot(): List<DraftInterval> = synchronized(lock) {
        workingDrafts.values.sortedBy { it.startMs }
    }

    /**
     * Atomically publishes a Scout 2 reviewed interval (text + timing + attribution + placement)
     * into the released playback timeline as one consistent revision.
     */
    fun commitReviewedRelease(
        draft: DraftInterval,
        outcome: IntervalReviewOutcome,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ReleasedPlaybackSnapshot? = synchronized(lock) {
        if (!isValidContextLocked(draft.mediaId, draft.sessionToken, draft.seekGeneration, draft.configFingerprint)) {
            staleCallbacksRejected++
            return@synchronized null
        }
        val currentDraft = workingDrafts[draft.intervalId]
        if (currentDraft != null && draft.draftRevision < currentDraft.draftRevision) {
            staleCallbacksRejected++
            return@synchronized null
        }
        workingDrafts.remove(draft.intervalId)

        // If coverage is an unresolved gap with 0 cues, do NOT release a fake empty verified interval
        if (outcome.dialogueStatus == DialogueReviewStatus.GAP_UNRESOLVED && outcome.repairedEvents.isEmpty()) {
            lastReleaseSummary = "Held gap ${draft.startMs / 1000}s–${draft.endMs / 1000}s unresolved"
            return@synchronized buildSnapshotLocked()
        }

        releaseRevision++
        val isFallback = !outcome.isIndependentlyVerified
        if (outcome.dialogueStatus == DialogueReviewStatus.REPAIRED_FROM_REFERENCE ||
            outcome.attributionStatus == AttributionReviewStatus.REPAIRED ||
            outcome.placementStatus == PlacementReviewStatus.REPAIRED
        ) {
            repairedIntervalCount++
        }
        if (isFallback) fallbackReleaseCount++

        val released = ReleasedInterval(
            intervalId = draft.intervalId,
            mediaId = draft.mediaId,
            sourceRevision = draft.sourceRevision,
            draftRevision = draft.draftRevision,
            releaseRevision = releaseRevision,
            configFingerprint = draft.configFingerprint,
            startMs = draft.startMs,
            endMs = draft.endMs,
            events = outcome.repairedEvents,
            speakers = draft.speakers,
            reviewOutcome = outcome,
            isPlayableFallback = isFallback,
            releasedAtEpochMs = nowEpochMs,
        )
        releasedByIntervalId[draft.intervalId] = released
        mergeIntervalEventsAtomicallyLocked(draft.startMs, draft.endMs, outcome.repairedEvents, draft.speakers)
        lastReleaseSummary = "Released rev #$releaseRevision (${draft.startMs / 1000}s–${draft.endMs / 1000}s · ${outcome.dialogueStatus.name})"
        buildSnapshotLocked()
    }

    /**
     * Enforces per-interval repair deadlines: degrades overdue drafts predictably without EVER
     * marking an unfinished or unverified draft as `isIndependentlyVerified = true`.
     */
    fun applyDeadlineFallbackIfDue(
        nowEpochMs: Long = System.currentTimeMillis(),
        playheadMs: Long = 0L,
    ): List<ReleasedInterval> = synchronized(lock) {
        val newlyReleased = mutableListOf<ReleasedInterval>()
        val overdue = workingDrafts.values
            .filter { nowEpochMs >= it.deadlineEpochMs || (it.startMs <= playheadMs + 1_500L && it.endMs >= playheadMs) }
            .sortedBy { it.startMs }

        for (draft in overdue) {
            workingDrafts.remove(draft.intervalId)
            if (draft.events.isEmpty()) {
                // Missing text at deadline: NEVER invent text or mark verified!
                val status = if (draft.hasUnavailableEvidence) {
                    DialogueReviewStatus.UNAVAILABLE_EVIDENCE
                } else {
                    DialogueReviewStatus.GAP_UNRESOLVED
                }
                val outcome = IntervalReviewOutcome(
                    dialogueStatus = status,
                    attributionStatus = AttributionReviewStatus.NEUTRAL_UNCERTAIN,
                    placementStatus = PlacementReviewStatus.FIXED_CONSERVATIVE,
                    dialogueConfidence = 0f,
                    attributionConfidence = 0f,
                    placementStable = true,
                    isIndependentlyVerified = false,
                    notes = listOf("Deadline reached with no dialogue text; left explicitly unverified"),
                    repairedEvents = emptyList(),
                )
                if (status == DialogueReviewStatus.UNAVAILABLE_EVIDENCE) {
                    releaseRevision++
                    fallbackReleaseCount++
                    val rel = ReleasedInterval(
                        intervalId = draft.intervalId,
                        mediaId = draft.mediaId,
                        sourceRevision = draft.sourceRevision,
                        draftRevision = draft.draftRevision,
                        releaseRevision = releaseRevision,
                        configFingerprint = draft.configFingerprint,
                        startMs = draft.startMs,
                        endMs = draft.endMs,
                        events = emptyList(),
                        speakers = draft.speakers,
                        reviewOutcome = outcome,
                        isPlayableFallback = true,
                        releasedAtEpochMs = nowEpochMs,
                    )
                    releasedByIntervalId[draft.intervalId] = rel
                    newlyReleased.add(rel)
                }
                continue
            }

            // Reliable text with unfinished review -> release with neutral attribution & conservative fixed placement,
            // strictly marked isIndependentlyVerified = false!
            val safeFallbackEvents = draft.events.map { evt ->
                val stableKey = evt.stableCueKey.ifBlank { evt.id }
                val uncertainSpeaker = evt.speakerId.isNullOrBlank() || evt.speakerConfidence < 0.65f
                if (uncertainSpeaker && !evt.lockedByUser) {
                    evt.copy(
                        speakerId = null,
                        speakerConfidence = evt.speakerConfidence.coerceAtMost(0.45f),
                        anchorPosition = null,
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        attributionSummary = "Unidentified speaker",
                        stableCueKey = stableKey,
                    )
                } else if (!evt.lockedByUser) {
                    evt.copy(
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        anchorPosition = null,
                        stableCueKey = stableKey,
                    )
                } else {
                    evt.copy(stableCueKey = stableKey)
                }
            }
            val outcome = IntervalReviewOutcome(
                dialogueStatus = DialogueReviewStatus.FALLBACK_UNVERIFIED,
                attributionStatus = AttributionReviewStatus.NEUTRAL_UNCERTAIN,
                placementStatus = PlacementReviewStatus.FIXED_CONSERVATIVE,
                dialogueConfidence = 0.62f,
                attributionConfidence = 0.40f,
                placementStable = true,
                isIndependentlyVerified = false,
                notes = listOf("Released at review deadline as unverified fallback with conservative placement"),
                repairedEvents = safeFallbackEvents,
            )
            releaseRevision++
            fallbackReleaseCount++
            val rel = ReleasedInterval(
                intervalId = draft.intervalId,
                mediaId = draft.mediaId,
                sourceRevision = draft.sourceRevision,
                draftRevision = draft.draftRevision,
                releaseRevision = releaseRevision,
                configFingerprint = draft.configFingerprint,
                startMs = draft.startMs,
                endMs = draft.endMs,
                events = safeFallbackEvents,
                speakers = draft.speakers,
                reviewOutcome = outcome,
                isPlayableFallback = true,
                releasedAtEpochMs = nowEpochMs,
            )
            releasedByIntervalId[draft.intervalId] = rel
            mergeIntervalEventsAtomicallyLocked(draft.startMs, draft.endMs, safeFallbackEvents, draft.speakers)
            newlyReleased.add(rel)
            lastReleaseSummary = "Deadline fallback rev #$releaseRevision (${draft.startMs / 1000}s–${draft.endMs / 1000}s · unverified)"
        }
        newlyReleased
    }

    /**
     * Controlled atomic correction, neutralization, or removal of an already-visible cue that is
     * confirmed materially wrong. Preserves persistent cue identity (`id` & `stableCueKey`) when
     * updating and enforces a per-cue cooldown so visible cues cannot oscillate.
     */
    fun applyControlledVisibleCorrection(
        mediaId: String,
        sessionToken: Long,
        targetEventId: String,
        correctedText: String? = null,
        neutralizeAttribution: Boolean = false,
        removeHallucinatedCue: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ReleasedPlaybackSnapshot? = synchronized(lock) {
        if (mediaId != activeMediaId || sessionToken != activeSessionToken) {
            staleCallbacksRejected++
            return@synchronized null
        }
        val existing = releasedEventsById[targetEventId] ?: return@synchronized null
        if (existing.lockedByUser) return@synchronized null

        val lastCorr = lastVisibleCorrectionAtMs[targetEventId]
        if (lastCorr != null && nowEpochMs - lastCorr < VISIBLE_CORRECTION_COOLDOWN_MS) {
            // Anti-oscillation guard: suppress rapid repeated rewrites on the same visible cue
            return@synchronized null
        }
        lastVisibleCorrectionAtMs[targetEventId] = nowEpochMs
        releaseRevision++
        visibleCorrectionCount++

        if (removeHallucinatedCue) {
            releasedEventsById.remove(targetEventId)
            lastReleaseSummary = "Removed materially wrong cue $targetEventId (rev #$releaseRevision)"
        } else {
            val updated = existing.copy(
                sourceText = correctedText ?: existing.sourceText,
                translatedText = correctedText ?: existing.translatedText,
                speakerId = if (neutralizeAttribution) null else existing.speakerId,
                speakerConfidence = if (neutralizeAttribution) 0.35f else existing.speakerConfidence,
                faceTrackId = if (neutralizeAttribution) null else existing.faceTrackId,
                faceConfidence = if (neutralizeAttribution) 0f else existing.faceConfidence,
                anchorPosition = if (neutralizeAttribution) null else existing.anchorPosition,
                placementMode = if (neutralizeAttribution) PlacementMode.NEUTRAL_FALLBACK else existing.placementMode,
                attributionSummary = if (neutralizeAttribution) "Unidentified speaker" else existing.attributionSummary,
                stableCueKey = existing.stableCueKey.ifBlank { existing.id },
            )
            releasedEventsById[targetEventId] = updated
            lastReleaseSummary = "Controlled visible correction on $targetEventId (rev #$releaseRevision)"
        }
        buildSnapshotLocked()
    }

    fun snapshot(): ReleasedPlaybackSnapshot = synchronized(lock) {
        buildSnapshotLocked()
    }

    private fun mergeIntervalEventsAtomicallyLocked(
        startMs: Long,
        endMs: Long,
        incoming: List<DialogueEvent>,
        speakers: List<Speaker>,
    ) {
        speakers.forEach { releasedSpeakersById[it.id] = it }
        // Remove previous non-user-locked events strictly inside [startMs, endMs) unless preserved by id/stableCueKey
        val incomingIds = incoming.map { it.id }.toSet()
        val staleInWindow = releasedEventsById.values
            .filter { evt ->
                !evt.lockedByUser &&
                    evt.startTimeMs in startMs until endMs &&
                    evt.id !in incomingIds
            }
            .map { it.id }
        staleInWindow.forEach { releasedEventsById.remove(it) }

        for (evt in incoming) {
            val existingById = releasedEventsById[evt.id]
            if (existingById?.lockedByUser == true) continue
            val stableKey = evt.stableCueKey.ifBlank { existingById?.stableCueKey?.ifBlank { evt.id } ?: evt.id }
            releasedEventsById[evt.id] = evt.copy(stableCueKey = stableKey)
        }
    }

    private fun isValidContextLocked(
        mediaId: String,
        sessionToken: Long,
        seekGeneration: Long,
        configFingerprint: String,
    ): Boolean {
        if (mediaId != activeMediaId || sessionToken != activeSessionToken) return false
        if (seekGeneration != activeSeekGeneration) return false
        if (activeConfigFingerprint.isNotBlank() && configFingerprint.isNotBlank() && configFingerprint != activeConfigFingerprint) {
            return false
        }
        return true
    }

    private fun originRank(kind: CaptionOriginKind): Int = when (kind) {
        CaptionOriginKind.USER_IMPORTED -> 4
        CaptionOriginKind.AUTHORED -> 3
        CaptionOriginKind.AUTOMATIC_ASR -> 2
        CaptionOriginKind.UNKNOWN -> 1
    }

    private fun buildSnapshotLocked(): ReleasedPlaybackSnapshot {
        val ref = referenceTrack
        val refSummary = if (ref == null) {
            "No reference captions attached"
        } else {
            "${ref.originKind.label} · ${ref.languageCode.uppercase().ifBlank { "UND" }} · ${ref.cues.size} cues (rev ${ref.sourceRevision})"
        }
        return ReleasedPlaybackSnapshot(
            mediaId = activeMediaId,
            sessionToken = activeSessionToken,
            seekGeneration = activeSeekGeneration,
            releaseRevision = releaseRevision,
            layoutCacheRevision = layoutCacheRevision,
            configFingerprint = activeConfigFingerprint,
            events = releasedEventsById.values.sortedWith(compareBy<DialogueEvent> { it.startTimeMs }.thenBy { it.id }),
            speakers = releasedSpeakersById.values.toList(),
            releasedIntervals = releasedByIntervalId.values.sortedBy { it.startMs },
            pendingDraftCount = workingDrafts.size,
            repairedIntervalCount = repairedIntervalCount,
            fallbackReleaseCount = fallbackReleaseCount,
            staleCallbacksRejected = staleCallbacksRejected,
            visibleCorrectionCount = visibleCorrectionCount,
            lastReleaseSummary = lastReleaseSummary,
            referenceTrackSummary = refSummary,
        )
    }

    companion object {
        const val VISIBLE_CORRECTION_COOLDOWN_MS = 2_500L
    }
}
