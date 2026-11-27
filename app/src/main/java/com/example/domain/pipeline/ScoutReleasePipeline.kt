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

data class CueReviewRecord(
    val eventId: String,
    val stableCueKey: String,
    val status: DialogueReviewStatus,
    val isSourceVerified: Boolean,
    val isTranslationVerified: Boolean,
    val needsTranslation: Boolean = false,
    val note: String = "",
)

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
    val perCueReviews: List<CueReviewRecord> = emptyList(),
    val draftRevision: Long = 0L,
    val sourceRevision: Long = 0L,
    val referenceTrackId: String? = null,
    val isTranslationVerified: Boolean = true,
    val isApproximatePreviewOnly: Boolean = false,
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
        liveFacesPlayheadMs: Long? = null,
        measuredNormalizedSizes: Map<String, Pair<Float, Float>> = emptyMap(),
    ): IntervalReviewOutcome {
        val notes = mutableListOf<String>()
        val alignedRefs = referenceTrack?.cues
            ?.filter { cue ->
                cue.endMs >= draft.startMs - TIME_ALIGN_SLACK_MS &&
                    cue.startMs <= draft.endMs + TIME_ALIGN_SLACK_MS &&
                    cue.text.isNotBlank()
            }
            .orEmpty()

        // Only treat liveFaces as valid observations for this draft interval when they belong to
        // the same time region; NEVER evaluate a future scene using the viewer's current live faces!
        val intervalLiveFaces = if (liveFacesPlayheadMs != null &&
            (liveFacesPlayheadMs < draft.startMs - 3_000L || liveFacesPlayheadMs > draft.endMs + 3_000L)
        ) {
            notes.add("Future interval preview omitted current viewer faces")
            emptyList()
        } else {
            liveFaces
        }
        val isApproximatePreview = intervalLiveFaces.isEmpty() || measuredNormalizedSizes.isEmpty()

        val targetLangNorm = normalizeLanguageTag(draft.targetLanguage)
        val sourceLangNorm = normalizeLanguageTag(draft.sourceLanguage)
        val sampleRefText = alignedRefs.take(3).joinToString(" ") { it.text }
        val declaredRefLang = normalizeLanguageTag(referenceTrack?.languageCode.orEmpty())
        val inferredRefScriptLang = detectDominantScriptLanguage(sampleRefText)
        val effectiveRefLang = when {
            declaredRefLang.isNotBlank() && declaredRefLang != "auto" -> declaredRefLang
            inferredRefScriptLang.isNotBlank() -> inferredRefScriptLang
            else -> "auto"
        }

        // Language check applies identically to explicit sourceLanguage, Auto-Detect, AND empty-draft recovery
        val hasConcreteTarget = targetLangNorm.isNotBlank() && targetLangNorm != "auto"
        val refMatchesTarget = hasConcreteTarget && effectiveRefLang == targetLangNorm
        val refDiffersFromTarget = hasConcreteTarget &&
            effectiveRefLang.isNotBlank() &&
            effectiveRefLang != "auto" &&
            effectiveRefLang != targetLangNorm
        val isTranslationRequested = hasConcreteTarget && (
            (sourceLangNorm.isNotBlank() && sourceLangNorm != "auto" && sourceLangNorm != targetLangNorm) ||
                refDiffersFromTarget
            )
        val refMatchesSource = when {
            sourceLangNorm.isNotBlank() && sourceLangNorm != "auto" && effectiveRefLang != "auto" ->
                effectiveRefLang == sourceLangNorm
            sourceLangNorm == "auto" && effectiveRefLang != "auto" && !refMatchesTarget -> true
            else -> false
        }

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
                perCueReviews = emptyList(),
                draftRevision = draft.draftRevision,
                sourceRevision = draft.sourceRevision,
                referenceTrackId = draft.referenceTrackId,
                isTranslationVerified = false,
                isApproximatePreviewOnly = isApproximatePreview,
            )
        }

        // 2. Coverage check: empty draft vs confirmed silence vs caption dropout/gap
        if (draft.events.isEmpty()) {
            if (alignedRefs.isNotEmpty()) {
                // Draft missed dialogue present in reference captions -> recover with strict language guards!
                val missingTranslation = isTranslationRequested && !refMatchesTarget
                val refOriginIsHighTrust = referenceTrack?.originKind == CaptionOriginKind.AUTHORED ||
                    referenceTrack?.originKind == CaptionOriginKind.USER_IMPORTED
                if (missingTranslation) {
                    notes.add("Recovered ${alignedRefs.size} cue(s) in source language (${effectiveRefLang.uppercase()}); ${draft.targetLanguage} translation still required")
                } else {
                    notes.add("Recovered ${alignedRefs.size} cue(s) missed by Scout 1 from reference captions")
                }
                val recovered = alignedRefs.map { ref ->
                    val clampedStart = ref.startMs.coerceIn(draft.startMs, draft.endMs)
                    val clampedEnd = ref.endMs.coerceIn(
                        clampedStart + 350L,
                        maxOf(draft.endMs, clampedStart + 350L),
                    )
                    DialogueEvent(
                        id = "sc2_${ref.cueId}",
                        startTimeMs = clampedStart,
                        endTimeMs = clampedEnd,
                        sourceText = ref.text,
                        // NEVER copy foreign-language captions into translatedText when target translation is requested!
                        translatedText = if (missingTranslation) "" else ref.text,
                        speakerId = null,
                        speakerConfidence = 0.35f,
                        faceTrackId = null,
                        faceConfidence = 0f,
                        shotId = projectTemplate.shots.firstOrNull()?.id ?: "shot_1",
                        dialogueType = DialogueType.SPOKEN,
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        attributionSummary = "Unidentified speaker",
                        stableCueKey = ref.cueId,
                        needsTranslation = missingTranslation,
                    )
                }
                val cueReviews = recovered.map { evt ->
                    CueReviewRecord(
                        eventId = evt.id,
                        stableCueKey = evt.stableCueKey,
                        status = if (missingTranslation || !refOriginIsHighTrust) {
                            DialogueReviewStatus.FALLBACK_UNVERIFIED
                        } else {
                            DialogueReviewStatus.REPAIRED_FROM_REFERENCE
                        },
                        isSourceVerified = refOriginIsHighTrust,
                        isTranslationVerified = !missingTranslation && refOriginIsHighTrust,
                        needsTranslation = missingTranslation,
                        note = if (missingTranslation) "Source recovered; translation pending" else "Recovered from reference",
                    )
                }
                val (placedEvents, placementStatus, placementStable) = validatePresentation(
                    events = recovered,
                    layoutEngine = layoutEngine,
                    projectTemplate = projectTemplate,
                    liveFaces = intervalLiveFaces,
                    styleSettings = styleSettings,
                    displayMode = displayMode,
                    measuredNormalizedSizes = measuredNormalizedSizes,
                    notes = notes,
                )
                return IntervalReviewOutcome(
                    dialogueStatus = DialogueReviewStatus.REPAIRED_FROM_REFERENCE,
                    attributionStatus = AttributionReviewStatus.NEUTRAL_UNCERTAIN,
                    placementStatus = placementStatus,
                    dialogueConfidence = if (refOriginIsHighTrust) 0.90f else 0.72f,
                    attributionConfidence = 0.35f,
                    placementStable = placementStable,
                    isIndependentlyVerified = refOriginIsHighTrust && !missingTranslation,
                    needsModelEscalation = missingTranslation,
                    notes = notes,
                    repairedEvents = placedEvents,
                    perCueReviews = cueReviews,
                    draftRevision = draft.draftRevision,
                    sourceRevision = draft.sourceRevision,
                    referenceTrackId = draft.referenceTrackId,
                    isTranslationVerified = !missingTranslation && refOriginIsHighTrust,
                    isApproximatePreviewOnly = isApproximatePreview,
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
                    perCueReviews = emptyList(),
                    draftRevision = draft.draftRevision,
                    sourceRevision = draft.sourceRevision,
                    referenceTrackId = draft.referenceTrackId,
                    isTranslationVerified = true,
                    isApproximatePreviewOnly = isApproximatePreview,
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
                perCueReviews = emptyList(),
                draftRevision = draft.draftRevision,
                sourceRevision = draft.sourceRevision,
                referenceTrackId = draft.referenceTrackId,
                isTranslationVerified = false,
                isApproximatePreviewOnly = isApproximatePreview,
            )
        }

        // 3. Rolling-caption normalization & persistent identity preservation
        val deduplicated = normalizeRollingCaptionsPreservingIdentity(draft.events, notes)

        // 4. Per-cue, language-aware, semantic comparison against immutable reference captions
        val refOriginIsHighTrust = referenceTrack != null &&
            (referenceTrack.originKind == CaptionOriginKind.AUTHORED ||
                referenceTrack.originKind == CaptionOriginKind.USER_IMPORTED)
        var repairedDialogueCount = 0
        var verifiedCueCount = 0
        var unverifiedCueCount = 0
        var allTranslationsVerified = true
        var escalatedForAmbiguity = false
        val cueReviews = mutableListOf<CueReviewRecord>()

        val textReviewed = deduplicated.map { evt ->
            val stableKey = evt.stableCueKey.ifBlank { evt.id }
            val overlappingRefs = alignedRefs.filter { ref ->
                val overlapStart = maxOf(ref.startMs, evt.startTimeMs)
                val overlapEnd = minOf(ref.endMs, evt.endTimeMs)
                val directOverlap = overlapEnd > overlapStart
                val closeProximity = abs(ref.startMs - evt.startTimeMs) <= 800L
                directOverlap || closeProximity
            }
            val cueNeedsTranslation = isTranslationRequested &&
                evt.translatedText.isBlank() &&
                evt.sourceText.isNotBlank()

            if (overlappingRefs.isEmpty()) {
                // No overlapping reference cue for THIS cue -> keep this cue explicitly unverified unless backed by audio!
                if (draft.hasIndependentAudioOrFrameEvidence && !cueNeedsTranslation) {
                    verifiedCueCount++
                    cueReviews.add(
                        CueReviewRecord(
                            eventId = evt.id,
                            stableCueKey = stableKey,
                            status = DialogueReviewStatus.VERIFIED_MATCH,
                            isSourceVerified = true,
                            isTranslationVerified = !isTranslationRequested || evt.translatedText.isNotBlank(),
                            needsTranslation = cueNeedsTranslation,
                            note = "Backed by audio/media evidence",
                        )
                    )
                } else {
                    unverifiedCueCount++
                    if (isTranslationRequested && !refMatchesTarget) allTranslationsVerified = false
                    cueReviews.add(
                        CueReviewRecord(
                            eventId = evt.id,
                            stableCueKey = stableKey,
                            status = DialogueReviewStatus.FALLBACK_UNVERIFIED,
                            isSourceVerified = false,
                            isTranslationVerified = false,
                            needsTranslation = cueNeedsTranslation,
                            note = "No overlapping reference cue",
                        )
                    )
                }
                evt.copy(stableCueKey = stableKey, needsTranslation = cueNeedsTranslation)
            } else {
                val combinedRefText = overlappingRefs.joinToString(" ") { it.text.trim() }.trim()
                val cueRefLang = if (effectiveRefLang != "auto") effectiveRefLang else detectDominantScriptLanguage(combinedRefText)
                val cueRefMatchesTarget = hasConcreteTarget && cueRefLang == targetLangNorm
                val cueRefMatchesSource = when {
                    sourceLangNorm != "auto" && cueRefLang.isNotBlank() && cueRefLang != "auto" -> cueRefLang == sourceLangNorm
                    sourceLangNorm == "auto" && cueRefLang.isNotBlank() && cueRefLang != "auto" && !cueRefMatchesTarget -> true
                    else -> false
                }
                val cueRequiresTranslation = hasConcreteTarget && (
                    (sourceLangNorm != "auto" && sourceLangNorm != targetLangNorm) ||
                        (cueRefLang.isNotBlank() && cueRefLang != "auto" && cueRefLang != targetLangNorm)
                    )
                val canCompareAgainstTranslated = !cueRequiresTranslation || cueRefMatchesTarget
                val refIsUnrelatedLanguage = !canCompareAgainstTranslated && !cueRefMatchesSource

                if (refIsUnrelatedLanguage) {
                    // Unrelated-language reference MUST NEVER increment repairedDialogueCount or verify this cue!
                    unverifiedCueCount++
                    allTranslationsVerified = false
                    notes.add("Ignored unrelated-language reference ($cueRefLang) for ${evt.id}")
                    cueReviews.add(
                        CueReviewRecord(
                            eventId = evt.id,
                            stableCueKey = stableKey,
                            status = DialogueReviewStatus.FALLBACK_UNVERIFIED,
                            isSourceVerified = false,
                            isTranslationVerified = false,
                            needsTranslation = cueNeedsTranslation,
                            note = "Reference language ($cueRefLang) unrelated to source/target",
                        )
                    )
                    evt.copy(stableCueKey = stableKey, needsTranslation = cueNeedsTranslation)
                } else {
                    val compareTargetText = if (canCompareAgainstTranslated) {
                        evt.translatedText.ifBlank { evt.sourceText }
                    } else {
                        // Translation active and reference is in source language:
                        // compare ONLY against sourceText and NEVER overwrite translatedText!
                        evt.sourceText
                    }
                    val semantic = evaluateSemanticEquivalence(compareTargetText, combinedRefText)

                    when {
                        semantic.isEquivalent -> {
                            val translationChecked = canCompareAgainstTranslated
                            val sourceVerified = refOriginIsHighTrust || draft.hasIndependentAudioOrFrameEvidence
                            val cueVerified = sourceVerified && (!cueRequiresTranslation || translationChecked)
                            if (cueVerified) {
                                verifiedCueCount++
                            } else {
                                unverifiedCueCount++
                                if (cueRequiresTranslation && !translationChecked) {
                                    allTranslationsVerified = false
                                }
                            }
                            cueReviews.add(
                                CueReviewRecord(
                                    eventId = evt.id,
                                    stableCueKey = stableKey,
                                    status = if (cueVerified) DialogueReviewStatus.VERIFIED_MATCH else DialogueReviewStatus.FALLBACK_UNVERIFIED,
                                    isSourceVerified = sourceVerified,
                                    isTranslationVerified = translationChecked && sourceVerified,
                                    needsTranslation = cueNeedsTranslation,
                                    note = if (cueRequiresTranslation && !translationChecked) {
                                        "Source matched reference; translation unverified"
                                    } else {
                                        "Matched reference (${(semantic.score * 100).toInt()}%)"
                                    },
                                )
                            )
                            evt.copy(stableCueKey = stableKey, needsTranslation = cueNeedsTranslation)
                        }
                        // Authored/user-imported reference materially disagrees (including negation/number/entity flip)
                        refOriginIsHighTrust && (semantic.hasCriticalDivergence || semantic.score < EQUIVALENT_TEXT_THRESHOLD) -> {
                            if (canCompareAgainstTranslated) {
                                repairedDialogueCount++
                                verifiedCueCount++
                                notes.add("Repaired cue ${evt.id} using ${referenceTrack?.originKind?.label} reference (${semantic.reason})")
                                cueReviews.add(
                                    CueReviewRecord(
                                        eventId = evt.id,
                                        stableCueKey = stableKey,
                                        status = DialogueReviewStatus.REPAIRED_FROM_REFERENCE,
                                        isSourceVerified = true,
                                        isTranslationVerified = true,
                                        needsTranslation = false,
                                        note = "Repaired from ${referenceTrack?.originKind?.label} (${semantic.reason})",
                                    )
                                )
                                evt.copy(
                                    sourceText = combinedRefText,
                                    translatedText = combinedRefText,
                                    stableCueKey = stableKey,
                                    needsTranslation = false,
                                )
                            } else {
                                // Reference matches source language ONLY: repair sourceText, preserve existing target
                                // translation, and DO NOT claim translation is verified!
                                repairedDialogueCount++
                                unverifiedCueCount++
                                allTranslationsVerified = false
                                val stillMissingTrans = evt.translatedText.isBlank()
                                notes.add("Repaired sourceText for ${evt.id} (${semantic.reason}); ${draft.targetLanguage} translation remains unverified")
                                cueReviews.add(
                                    CueReviewRecord(
                                        eventId = evt.id,
                                        stableCueKey = stableKey,
                                        status = DialogueReviewStatus.REPAIRED_FROM_REFERENCE,
                                        isSourceVerified = true,
                                        isTranslationVerified = false,
                                        needsTranslation = stillMissingTrans,
                                        note = "Source repaired; translation unverified",
                                    )
                                )
                                evt.copy(
                                    sourceText = combinedRefText,
                                    stableCueKey = stableKey,
                                    needsTranslation = stillMissingTrans,
                                )
                            }
                        }
                        else -> {
                            unverifiedCueCount++
                            allTranslationsVerified = false
                            if ((semantic.hasCriticalDivergence || semantic.score < MATERIAL_MISMATCH_THRESHOLD) &&
                                draft.hasIndependentAudioOrFrameEvidence
                            ) {
                                escalatedForAmbiguity = true
                            }
                            cueReviews.add(
                                CueReviewRecord(
                                    eventId = evt.id,
                                    stableCueKey = stableKey,
                                    status = DialogueReviewStatus.FALLBACK_UNVERIFIED,
                                    isSourceVerified = false,
                                    isTranslationVerified = false,
                                    needsTranslation = cueNeedsTranslation,
                                    note = "Unverified divergence (${semantic.reason})",
                                )
                            )
                            evt.copy(stableCueKey = stableKey, needsTranslation = cueNeedsTranslation)
                        }
                    }
                }
            }
        }

        // Strict interval verification invariant:
        // ONE matching or repaired cue can NEVER verify an entire interval when other cues remain unverified!
        val totalCues = textReviewed.size
        val allCuesIndependentlyVerified = totalCues > 0 &&
            unverifiedCueCount == 0 &&
            verifiedCueCount == totalCues &&
            allTranslationsVerified

        val dialogueStatus: DialogueReviewStatus
        val dialogueConf: Float
        val independentlyVerified: Boolean
        when {
            allCuesIndependentlyVerified && repairedDialogueCount > 0 -> {
                dialogueStatus = DialogueReviewStatus.REPAIRED_FROM_REFERENCE
                dialogueConf = 0.90f
                independentlyVerified = true
            }
            allCuesIndependentlyVerified -> {
                dialogueStatus = DialogueReviewStatus.VERIFIED_MATCH
                dialogueConf = if (refOriginIsHighTrust) 0.94f else 0.85f
                independentlyVerified = true
            }
            repairedDialogueCount > 0 -> {
                dialogueStatus = DialogueReviewStatus.REPAIRED_FROM_REFERENCE
                dialogueConf = 0.76f
                independentlyVerified = false
                notes.add("Partially repaired ($repairedDialogueCount repaired, $unverifiedCueCount cue(s) remain unverified)")
            }
            verifiedCueCount > 0 -> {
                dialogueStatus = DialogueReviewStatus.FALLBACK_UNVERIFIED
                dialogueConf = 0.72f
                independentlyVerified = false
                notes.add("Partial verification ($verifiedCueCount/$totalCues cues verified; interval kept unverified)")
            }
            else -> {
                dialogueStatus = DialogueReviewStatus.FALLBACK_UNVERIFIED
                dialogueConf = 0.65f
                independentlyVerified = false
                notes.add("No complete independent verification; released as unverified fallback")
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
            liveFaces = intervalLiveFaces,
            styleSettings = styleSettings,
            displayMode = displayMode,
            measuredNormalizedSizes = measuredNormalizedSizes,
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
            perCueReviews = cueReviews,
            draftRevision = draft.draftRevision,
            sourceRevision = draft.sourceRevision,
            referenceTrackId = draft.referenceTrackId,
            isTranslationVerified = allTranslationsVerified && independentlyVerified,
            isApproximatePreviewOnly = isApproximatePreview,
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
        measuredNormalizedSizes: Map<String, Pair<Float, Float>> = emptyMap(),
        notes: MutableList<String>,
    ): Triple<List<DialogueEvent>, PlacementReviewStatus, Boolean> {
        if (events.isEmpty()) return Triple(events, PlacementReviewStatus.STABLE, true)
        val previewProject = projectTemplate.copy(dialogueEvents = events)
        var fixedCount = 0
        var allStable = true
        val checked = events.map { evt ->
            if (evt.lockedByUser) return@map evt
            val stability = layoutEngine.evaluateCuePlacementStability(
                project = previewProject,
                event = evt,
                styleSettings = styleSettings,
                liveFaces = liveFaces,
                fixedZoneTaillessMode = displayMode == CaptionDisplayMode.FIXED_ZONES,
                measuredNormalizedSizes = measuredNormalizedSizes,
            )
            if (!stability.isWithinBounds || stability.hasSevereFaceCollision || stability.maxDriftNorm > MAX_ALLOWED_PREVIEW_DRIFT) {
                fixedCount++
                notes.add("Stabilized placement for ${evt.id} to conservative neutral fallback (${stability.reason})")
                // Clear BOTH anchorPosition AND faceTrackId so BalloonLayoutEngine respects NEUTRAL_FALLBACK
                val fallbackEvt = evt.copy(
                    placementMode = PlacementMode.NEUTRAL_FALLBACK,
                    anchorPosition = null,
                    faceTrackId = null,
                    faceConfidence = 0f,
                )
                // Re-evaluate the repaired fallback before claiming placementStable!
                val recheck = layoutEngine.evaluateCuePlacementStability(
                    project = previewProject.copy(
                        dialogueEvents = previewProject.dialogueEvents.map { if (it.id == evt.id) fallbackEvt else it }
                    ),
                    event = fallbackEvt,
                    styleSettings = styleSettings,
                    liveFaces = liveFaces,
                    fixedZoneTaillessMode = displayMode == CaptionDisplayMode.FIXED_ZONES,
                    measuredNormalizedSizes = measuredNormalizedSizes,
                )
                if (!recheck.isWithinBounds || recheck.hasSevereFaceCollision || recheck.maxDriftNorm > MAX_ALLOWED_PREVIEW_DRIFT) {
                    allStable = false
                    notes.add("Fallback placement for ${evt.id} still constrained (${recheck.reason})")
                }
                fallbackEvt
            } else {
                evt
            }
        }
        val status = when {
            fixedCount == 0 -> PlacementReviewStatus.STABLE
            fixedCount == checked.size -> PlacementReviewStatus.FIXED_CONSERVATIVE
            else -> PlacementReviewStatus.REPAIRED
        }
        return Triple(checked, status, allStable)
    }

    data class SemanticEquivalenceResult(
        val isEquivalent: Boolean,
        val score: Float,
        val hasCriticalDivergence: Boolean,
        val reason: String,
    )

    companion object {
        private const val TIME_ALIGN_SLACK_MS = 1_500L
        private const val ROLLING_CAPTION_GAP_MS = 2_400L
        private const val EQUIVALENT_TEXT_THRESHOLD = 0.64f
        private const val MATERIAL_MISMATCH_THRESHOLD = 0.40f
        private const val ATTRIBUTION_CONFIRM_THRESHOLD = 0.55f
        private const val MAX_ALLOWED_PREVIEW_DRIFT = 0.16f

        private val NEGATION_WORDS = setOf(
            "not", "no", "never", "none", "neither", "nor", "without", "nobody", "nothing", "nowhere",
            "cannot", "cant", "wont", "dont", "doesnt", "didnt", "isnt", "arent", "wasnt", "werent",
            "havent", "hasnt", "hadnt", "wouldnt", "couldnt", "shouldnt", "mustnt",
            "nunca", "ninguno", "jamais", "nicht", "kein", "keine", "ない", "ません", "じゃない", "ではない",
        )

        private val NUMBER_WORD_MAP = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "ten" to "10", "eleven" to "11", "twelve" to "12", "twenty" to "20",
            "thirty" to "30", "forty" to "40", "fifty" to "50", "hundred" to "100",
            "thousand" to "1000", "million" to "1000000", "billion" to "1000000000",
        )

        fun evaluateSemanticEquivalence(draftText: String, refText: String): SemanticEquivalenceResult {
            val draftNeg = countNegations(draftText)
            val refNeg = countNegations(refText)
            if (draftNeg != refNeg) {
                return SemanticEquivalenceResult(
                    isEquivalent = false,
                    score = 0f,
                    hasCriticalDivergence = true,
                    reason = "negation polarity mismatch",
                )
            }
            val draftNums = extractQuantities(draftText)
            val refNums = extractQuantities(refText)
            if (draftNums != refNums && (draftNums.isNotEmpty() || refNums.isNotEmpty())) {
                return SemanticEquivalenceResult(
                    isEquivalent = false,
                    score = 0.2f,
                    hasCriticalDivergence = true,
                    reason = "numeric/quantity mismatch",
                )
            }
            val draftEntities = extractInternalProperEntities(draftText)
            val refEntities = extractInternalProperEntities(refText)
            if (draftEntities.isNotEmpty() && refEntities.isNotEmpty() && draftEntities.intersect(refEntities).isEmpty()) {
                return SemanticEquivalenceResult(
                    isEquivalent = false,
                    score = 0.25f,
                    hasCriticalDivergence = true,
                    reason = "entity/name mismatch",
                )
            }
            val draftTokens = tokenizeNormalized(draftText)
            val refTokens = tokenizeNormalized(refText)
            val f1 = tokenOverlapScore(draftTokens, refTokens)
            val equivalent = f1 >= EQUIVALENT_TEXT_THRESHOLD
            return SemanticEquivalenceResult(
                isEquivalent = equivalent,
                score = f1,
                hasCriticalDivergence = f1 < MATERIAL_MISMATCH_THRESHOLD,
                reason = if (equivalent) "token F1 ${(f1 * 100).toInt()}%" else "low similarity ${(f1 * 100).toInt()}%",
            )
        }

        private fun countNegations(text: String): Int {
            val lowered = text.lowercase()
                .replace("’", "'")
                .replace("n't", " nt")
                .replace(Regex("[\\p{Punct}“”‘’—–…♪♫]"), " ")
            val words = lowered.split(Regex("\\s+")).filter { it.isNotBlank() }
            var count = 0
            for (w in words) {
                if (w == "nt" || w in NEGATION_WORDS) count++
            }
            return count
        }

        private fun extractQuantities(text: String): Set<String> {
            val out = mutableSetOf<String>()
            Regex("\\d+(?:[.,]\\d+)?").findAll(text).forEach { out.add(it.value.replace(",", "")) }
            normalizeTextForCompare(text).split(' ').forEach { token ->
                NUMBER_WORD_MAP[token]?.let { out.add(it) }
            }
            return out
        }

        private fun extractInternalProperEntities(text: String): Set<String> {
            val rawTokens = text.trim().split(Regex("\\s+"))
            if (rawTokens.size <= 1) return emptySet()
            return rawTokens.drop(1)
                .map { it.trim { ch -> !ch.isLetter() } }
                .filter { tok -> tok.length >= 3 && tok[0].isUpperCase() && tok.drop(1).any { it.isLowerCase() } }
                .map { it.lowercase() }
                .toSet()
        }

        fun detectDominantScriptLanguage(text: String): String {
            if (text.isBlank()) return ""
            var jaKana = 0
            var koHangul = 0
            var cjkHan = 0
            var cyrillic = 0
            var arabic = 0
            var devanagari = 0
            for (ch in text) {
                when (ch.code) {
                    in 0x3040..0x30FF -> jaKana++
                    in 0xAC00..0xD7AF, in 0x1100..0x11FF -> koHangul++
                    in 0x4E00..0x9FFF -> cjkHan++
                    in 0x0400..0x04FF -> cyrillic++
                    in 0x0600..0x06FF -> arabic++
                    in 0x0900..0x097F -> devanagari++
                }
            }
            return when {
                jaKana >= 2 -> "ja"
                koHangul >= 2 -> "ko"
                cjkHan >= 2 -> "zh"
                cyrillic >= 3 -> "ru"
                arabic >= 3 -> "ar"
                devanagari >= 3 -> "hi"
                else -> ""
            }
        }

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

        /**
         * Balanced F1 token similarity (replaces asymmetric intersection/minSize which let
         * strict subsets like "I did send it" vs "I did not send it" score 1.0).
         */
        fun tokenOverlapScore(a: Set<String>, b: Set<String>): Float {
            if (a.isEmpty() && b.isEmpty()) return 1f
            if (a.isEmpty() || b.isEmpty()) return 0f
            val shared = a.intersect(b).size.toFloat()
            return ((2f * shared) / (a.size + b.size).toFloat()).coerceIn(0f, 1f)
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
    private val highestReleasedDraftRevByInterval = mutableMapOf<String, Long>()
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
        highestReleasedDraftRevByInterval.clear()
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
     * Rejects stale revisions relative to BOTH pending drafts and already-released revisions.
     */
    fun stageDraft(draft: DraftInterval): Boolean = synchronized(lock) {
        if (!isValidContextLocked(draft.mediaId, draft.sessionToken, draft.seekGeneration, draft.configFingerprint)) {
            staleCallbacksRejected++
            return@synchronized false
        }
        val existingPending = workingDrafts[draft.intervalId]
        if (existingPending != null && draft.draftRevision <= existingPending.draftRevision) {
            staleCallbacksRejected++
            return@synchronized false
        }
        val highestReleased = highestReleasedDraftRevByInterval[draft.intervalId] ?: 0L
        if (draft.draftRevision <= highestReleased) {
            staleCallbacksRejected++
            return@synchronized false
        }
        val currentRef = referenceTrack
        if (currentRef != null && draft.referenceTrackId == currentRef.trackId && draft.sourceRevision < currentRef.sourceRevision) {
            staleCallbacksRejected++
            return@synchronized false
        }
        workingDrafts[draft.intervalId] = draft
        true
    }

    fun pendingDraft(intervalId: String): DraftInterval? = synchronized(lock) {
        workingDrafts[intervalId]
    }

    fun pendingDraftsSnapshot(): List<DraftInterval> = synchronized(lock) {
        workingDrafts.values.sortedBy { it.startMs }
    }

    fun pendingDraftCount(): Int = synchronized(lock) {
        workingDrafts.size
    }

    fun hasPendingDraftsInWindow(startMs: Long, endMs: Long): Boolean = synchronized(lock) {
        workingDrafts.values.any { it.startMs < endMs && it.endMs > startMs }
    }

    /**
     * Atomically publishes a Scout 2 reviewed interval (text + timing + attribution + placement)
     * into the released playback timeline as one consistent revision.
     * Strictly requires an exact staged pending draft and rejects stale draft or evidence revisions
     * even after a newer revision has already been released.
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
        // Reject if a newer revision for this interval was already released
        val highestReleased = highestReleasedDraftRevByInterval[draft.intervalId] ?: 0L
        if (draft.draftRevision <= highestReleased) {
            staleCallbacksRejected++
            return@synchronized null
        }
        // Require the exact matching staged pending draft
        val currentDraft = workingDrafts[draft.intervalId]
        if (currentDraft == null ||
            currentDraft.draftRevision != draft.draftRevision ||
            currentDraft.sourceRevision != draft.sourceRevision ||
            currentDraft.referenceTrackId != draft.referenceTrackId
        ) {
            staleCallbacksRejected++
            return@synchronized null
        }
        // Verify outcome matches the exact draft revision reviewed
        if (outcome.draftRevision > 0L && outcome.draftRevision != draft.draftRevision) {
            staleCallbacksRejected++
            return@synchronized null
        }
        // Verify evidence revision matches the active reference track revision
        val currentRef = referenceTrack
        if (currentRef != null && draft.referenceTrackId == currentRef.trackId && draft.sourceRevision != currentRef.sourceRevision) {
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
        highestReleasedDraftRevByInterval[draft.intervalId] = draft.draftRevision
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
                        faceTrackId = null,
                        faceConfidence = 0f,
                        anchorPosition = null,
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        attributionSummary = "Unidentified speaker",
                        stableCueKey = stableKey,
                    )
                } else if (!evt.lockedByUser) {
                    evt.copy(
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        faceTrackId = null,
                        faceConfidence = 0f,
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
                draftRevision = draft.draftRevision,
                sourceRevision = draft.sourceRevision,
                referenceTrackId = draft.referenceTrackId,
            )
            releaseRevision++
            highestReleasedDraftRevByInterval[draft.intervalId] = maxOf(
                highestReleasedDraftRevByInterval[draft.intervalId] ?: 0L,
                draft.draftRevision,
            )
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
