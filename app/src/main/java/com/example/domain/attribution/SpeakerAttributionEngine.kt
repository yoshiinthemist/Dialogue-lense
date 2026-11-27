package com.example.domain.attribution

import com.example.data.ai.AiTranslatedLine
import com.example.data.model.AudioVoiceFeatures
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.FaceTrack
import com.example.data.model.NormalizedPoint
import com.example.data.model.PlacementMode
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SpeakerColorOrder
import com.example.data.model.UserCorrection
import kotlin.math.abs
import kotlin.math.roundToInt

class SpeakerAttributionEngine {

    fun assignSpeakers(
        events: List<DialogueEvent>,
        shots: List<Shot>,
        faceTracks: List<FaceTrack>,
        existingSpeakers: List<Speaker>,
        turnHints: Map<String, AiTranslatedLine> = emptyMap(),
        rememberedCharacterColors: Map<String, Int> = emptyMap(),
        settledEventIds: Set<String> = emptySet(),
        sceneBoundaryMs: List<Long> = emptyList(),
        slotToSpeakerId: Map<String, String> = emptyMap(),
    ): Pair<List<Speaker>, List<DialogueEvent>> {
        val speakers = existingSpeakers.toMutableList()
        if (speakers.isEmpty()) {
            speakers.add(Speaker(id = "spk_a", defaultLabel = "Speaker A", accentIndex = 0))
            speakers.add(Speaker(id = "spk_b", defaultLabel = "Speaker B", accentIndex = 1))
        }
        val maxHintIdx = turnHints.values
            .mapNotNull { hint ->
                val ch = hint.suggestedSpeakerSlot.trim().uppercase().firstOrNull()
                if (ch != null && ch in 'A'..'H') (ch - 'A') else null
            }
            .maxOrNull() ?: 1
        while (speakers.size <= maxHintIdx && speakers.size < 8) {
            val idx = speakers.size
            val letter = ('A' + idx)
            speakers.add(Speaker(id = "spk_${letter.lowercaseChar()}", defaultLabel = "Speaker $letter", accentIndex = idx))
        }

        val sortedBoundaries = sceneBoundaryMs.filter { it > 0L }.distinct().sorted()
        val updatedEvents = mutableListOf<DialogueEvent>()
        var lastEvent: DialogueEvent? = null
        var activeSceneBucket: String? = null
        var sceneBucketIndex = 0

        // Per-scene spatial side memory: reset cleanly whenever a shot or chapter changeover occurs
        // so Scene 1 left/right seating NEVER forces misattributions in Scene 2.
        val sceneSpeakerSideX = mutableMapOf<String, Float>()

        // Track running acoustic cluster -> speakerId associations within this video session
        val clusterToSpeaker = mutableMapOf<String, String>()

        for (event in events.sortedBy { it.startTimeMs }) {
            val midMs = (event.startTimeMs + event.endTimeMs) / 2L
            val shot = shots.firstOrNull { midMs in it.startMs..it.endMs }
                ?: shots.minByOrNull { abs(((it.startMs + it.endMs) / 2L) - midMs) }
            val resolvedShotId = shot?.id ?: event.shotId
            val chapterBucket = sortedBoundaries.count { it <= event.startTimeMs }
            val sceneBucket = "$resolvedShotId#ch$chapterBucket"
            if (activeSceneBucket != null && activeSceneBucket != sceneBucket) {
                sceneSpeakerSideX.clear()
                sceneBucketIndex++
                // Across a chapter changeover, clear conversational carryover from the prior scene
                if (activeSceneBucket.substringAfter("#ch") != chapterBucket.toString()) {
                    lastEvent = null
                }
            }
            activeSceneBucket = sceneBucket

            val isSettledLine = event.id in settledEventIds && (
                event.speakerId != null ||
                    event.attributionSummary == "Unidentified speaker" ||
                    event.dialogueType != DialogueType.SPOKEN
                )
            if (event.lockedByUser || isSettledLine) {
                val keptSpeakerIdx = speakers.indexOfFirst { it.id == event.speakerId }
                if (keptSpeakerIdx >= 0) {
                    val updatedSpk = updateSpeakerVoiceCentroid(speakers[keptSpeakerIdx], event.audioFeatures)
                    speakers[keptSpeakerIdx] = updatedSpk
                    event.audioFeatures?.voiceClusterId?.let { cid -> clusterToSpeaker[cid] = updatedSpk.id }
                    val anchorX = event.anchorPosition?.x
                    if (anchorX != null && event.dialogueType == DialogueType.SPOKEN && event.speakerConfidence >= 0.70f) {
                        sceneSpeakerSideX[updatedSpk.id] = anchorX
                    }
                }
                updatedEvents.add(event)
                lastEvent = event
                continue
            }

            if (event.dialogueType == DialogueType.NARRATION ||
                event.dialogueType == DialogueType.VOICE_OVER ||
                event.dialogueType == DialogueType.SOUND_DESCRIPTION
            ) {
                updatedEvents.add(
                    event.copy(
                        faceTrackId = null,
                        faceConfidence = 0f,
                        placementMode = PlacementMode.EDGE_DOCKED,
                        attributionSummary = event.dialogueType.label,
                    )
                )
                continue
            }

            val shotId = resolvedShotId
            val shotTracks = faceTracks.filter { it.shotId == shotId }
            val aiHint = turnHints[event.id]

            if (shotTracks.isEmpty()) {
                val aiSlotRaw = aiHint?.suggestedSpeakerSlot?.trim()?.uppercase().orEmpty()
                val aiSlotChar = aiSlotRaw.firstOrNull()
                val aiExplicitSlotIndex = if (aiSlotRaw.length == 1 && aiSlotChar != null && aiSlotChar in 'A'..'L') {
                    aiSlotChar - 'A'
                } else {
                    -1
                }
                val hasScoutSpatialAnchor = event.anchorPosition != null &&
                    (faceTracks.isEmpty() && event.dialogueType == DialogueType.SPOKEN)

                // If the multimodal AI Scout watched the video and explicitly marked this line UNCERTAIN
                // (e.g. a brief B-roll clip speaker not in the cast glossary), do NOT force-assign it onto
                // an unrelated cast member — keep speakerId = null so it stays dotted gray until identified!
                if (faceTracks.isEmpty() && aiHint != null && aiSlotRaw == "UNCERTAIN" && event.speakerId == null) {
                    val placement = when {
                        event.dialogueType != DialogueType.SPOKEN -> PlacementMode.EDGE_DOCKED
                        hasScoutSpatialAnchor -> PlacementMode.AUTO
                        else -> PlacementMode.NEUTRAL_FALLBACK
                    }
                    val updatedEvt = event.copy(
                        shotId = shotId,
                        speakerId = null,
                        speakerConfidence = 0.48f,
                        faceTrackId = null,
                        faceConfidence = if (hasScoutSpatialAnchor) event.faceConfidence.coerceAtLeast(0.75f) else 0f,
                        dialogueType = event.dialogueType,
                        placementMode = placement,
                        contextCueReason = aiHint.contextReason.ifBlank { event.contextCueReason },
                        attributionSummary = "Unidentified speaker",
                    )
                    updatedEvents.add(updatedEvt)
                    lastEvent = updatedEvt
                    continue
                }

                // When the AI Scout directly observed who spoke on camera (slots A-L), ensure the slot exists
                // and trust the Scout's visual/audio slot instead of letting text heuristics override it.
                if (faceTracks.isEmpty() && aiExplicitSlotIndex in 0..11) {
                    val mappedSpeakerId = slotToSpeakerId[aiSlotRaw]
                    val directSpeaker = mappedSpeakerId?.let { id ->
                        speakers.firstOrNull { it.id == id }
                    } ?: run {
                        while (speakers.size <= aiExplicitSlotIndex) {
                            val nextIdx = speakers.size
                            val letter = ('A' + nextIdx)
                            speakers.add(
                                Speaker(
                                    id = "spk_${letter.lowercaseChar()}",
                                    defaultLabel = "Speaker $letter",
                                    accentIndex = nextIdx % SpeakerColorOrder.PALETTE_SIZE,
                                    isIdentityConfirmed = false,
                                )
                            )
                        }
                        speakers[aiExplicitSlotIndex]
                    }
                    val placement = when {
                        event.dialogueType != DialogueType.SPOKEN -> PlacementMode.EDGE_DOCKED
                        hasScoutSpatialAnchor -> PlacementMode.AUTO
                        else -> PlacementMode.NEUTRAL_FALLBACK
                    }
                    val updatedEvt = event.copy(
                        shotId = shotId,
                        speakerId = directSpeaker.id,
                        speakerConfidence = event.speakerConfidence.coerceIn(0.80f, 0.94f),
                        faceTrackId = null,
                        faceConfidence = if (hasScoutSpatialAnchor) event.faceConfidence.coerceAtLeast(0.78f) else 0f,
                        dialogueType = event.dialogueType,
                        placementMode = placement,
                        contextCueReason = aiHint?.contextReason.orEmpty().ifBlank { event.contextCueReason },
                        attributionSummary = aiHint?.contextReason.orEmpty().ifBlank { "AI Scout visual + voice match" },
                    )
                    updatedEvents.add(updatedEvt)
                    lastEvent = updatedEvt
                    continue
                }

                // Off-screen or local audio-only fallback: score speakers using audio voice signature + context
                val scoredOffScreen = speakers.map { spk ->
                    val audioScore = computeVoiceMatchScore(event.audioFeatures, spk, clusterToSpeaker)
                    val (ctxScore, ctxNote) = computeConversationalContextScore(
                        event = event,
                        prevEvent = lastEvent,
                        candidate = spk,
                        allSpeakers = speakers,
                        aiHint = aiHint,
                    )
                    val combined = if (event.audioFeatures != null && event.audioFeatures.pitchHz > 0f) {
                        (audioScore * 0.56f + ctxScore * 0.44f).coerceIn(0f, 0.94f)
                    } else {
                        (ctxScore * 0.85f).coerceIn(0f, 0.82f)
                    }
                    OffScreenCandidate(spk, combined, audioScore, ctxNote)
                }.sortedByDescending { it.score }

                val best = scoredOffScreen.firstOrNull()
                val second = scoredOffScreen.getOrNull(1)
                val margin = if (best != null && second != null) best.score - second.score else 0.25f
                val minConf = if (faceTracks.isEmpty()) 0.48f else 0.38f
                val offScreenConf = ((best?.score ?: 0.52f) * if (margin < 0.08f) 0.84f else 1f)
                    .coerceIn(minConf, 0.90f)
                val inferredType = if (faceTracks.isNotEmpty() && event.dialogueType == DialogueType.SPOKEN) {
                    DialogueType.OFF_SCREEN_BEHIND
                } else {
                    event.dialogueType
                }
                val hasSpatialAnchorForInferred = event.anchorPosition != null && inferredType == DialogueType.SPOKEN
                val placement = when {
                    inferredType != DialogueType.SPOKEN -> PlacementMode.EDGE_DOCKED
                    hasSpatialAnchorForInferred -> PlacementMode.AUTO
                    else -> PlacementMode.NEUTRAL_FALLBACK
                }
                val summary = buildSummary(
                    mouthScore = 0f,
                    audioFeatures = event.audioFeatures,
                    audioMatch = best?.audioScore ?: 0.5f,
                    contextNote = best?.ctxNote.orEmpty().ifBlank { event.contextCueReason },
                    offScreen = faceTracks.isNotEmpty(),
                )

                val updatedEvt = event.copy(
                    shotId = shotId,
                    speakerId = best?.speaker?.id,
                    speakerConfidence = offScreenConf,
                    faceTrackId = null,
                    faceConfidence = if (hasSpatialAnchorForInferred) event.faceConfidence.coerceAtLeast(0.75f) else 0f,
                    dialogueType = inferredType,
                    placementMode = placement,
                    contextCueReason = best?.ctxNote.orEmpty().ifBlank { event.contextCueReason },
                    attributionSummary = summary,
                )
                updatedEvents.add(updatedEvt)
                lastEvent = updatedEvt
                continue
            }

            // Visible faces in shot: fuse Mouth Activity + Audio Voice Match + Conversational Context + Spatial Continuity
            val scoredCandidates = shotTracks.map { track ->
                val windowSamples = track.samples.filter {
                    it.timeMs in (event.startTimeMs - 300L)..(event.endTimeMs + 300L)
                }.ifEmpty { track.samples }

                val mouthScore = if (windowSamples.isEmpty()) {
                    0.2f
                } else {
                    windowSamples.map { it.mouthActivity }.average().toFloat().coerceIn(0f, 1f)
                }

                val center = track.averageCenter()
                val mappedSpeaker = resolveSpeakerForTrack(
                    track = track,
                    speakers = speakers,
                    centerX = center.x,
                    allTracks = faceTracks,
                    sceneSpeakerSideX = sceneSpeakerSideX,
                    allowGlobalPreferredSideFallback = (sceneBucketIndex == 0 && sortedBoundaries.isEmpty()),
                )
                val voiceMatch = computeVoiceMatchScore(event.audioFeatures, mappedSpeaker, clusterToSpeaker)
                val (contextScore, contextNote) = computeConversationalContextScore(
                    event = event,
                    prevEvent = lastEvent,
                    candidate = mappedSpeaker,
                    allSpeakers = speakers,
                    aiHint = aiHint,
                )

                val scenePrefX = sceneSpeakerSideX[mappedSpeaker.id]
                    ?: if (sceneBucketIndex == 0 && sortedBoundaries.isEmpty()) mappedSpeaker.preferredScreenSideX else null
                val spatialContinuity = scenePrefX?.let { prefX ->
                    (1f - abs(prefX - center.x) * 1.4f).coerceIn(0f, 1f)
                } ?: 0.5f

                val hasUsableAudio = event.audioFeatures != null &&
                    (event.audioFeatures.pitchHz > 0f || event.audioFeatures.speechEnergy > 0.08f)

                val totalScore = if (hasUsableAudio) {
                    (
                        mouthScore * 0.36f +
                            voiceMatch * 0.28f +
                            contextScore * 0.24f +
                            spatialContinuity * 0.12f
                        ).coerceIn(0f, 0.98f)
                } else {
                    (
                        mouthScore * 0.44f +
                            contextScore * 0.36f +
                            spatialContinuity * 0.20f
                        ).coerceIn(0f, 0.98f)
                }

                CandidateScore(track, mappedSpeaker, mouthScore, voiceMatch, contextNote, totalScore)
            }.sortedByDescending { it.totalScore }

            val best = scoredCandidates.first()
            val second = scoredCandidates.getOrNull(1)
            val margin = if (second != null) (best.totalScore - second.totalScore) else 0.35f

            // Calibrate confidence conservatively when mouth activity & audio/context disagree or margin is thin
            val calibratedConfidence = when {
                best.mouthScore < 0.22f && margin < 0.12f -> (best.totalScore * 0.54f).coerceAtMost(0.42f)
                margin < 0.08f -> (best.totalScore * 0.72f).coerceAtMost(0.58f)
                else -> best.totalScore
            }

            // Update speaker's acoustic voice centroid and per-scene side memory when confidence is high
            if (calibratedConfidence >= 0.72f) {
                val spkIdx = speakers.indexOfFirst { it.id == best.speaker.id }
                if (spkIdx >= 0) {
                    speakers[spkIdx] = updateSpeakerVoiceCentroid(speakers[spkIdx], event.audioFeatures)
                }
                sceneSpeakerSideX[best.speaker.id] = best.track.averageCenter().x
                event.audioFeatures?.voiceClusterId?.let { cid ->
                    clusterToSpeaker[cid] = best.speaker.id
                }
            }

            val placementMode = when {
                calibratedConfidence < 0.45f -> PlacementMode.NEUTRAL_FALLBACK
                event.dialogueType != DialogueType.SPOKEN -> PlacementMode.EDGE_DOCKED
                else -> PlacementMode.AUTO
            }

            val summary = buildSummary(
                mouthScore = best.mouthScore,
                audioFeatures = event.audioFeatures,
                audioMatch = best.voiceMatch,
                contextNote = best.contextNote,
                offScreen = false,
            )

            val updatedEvt = event.copy(
                shotId = shotId,
                speakerId = best.speaker.id,
                speakerConfidence = calibratedConfidence,
                faceTrackId = best.track.id,
                faceConfidence = (best.mouthScore * 0.7f + 0.3f).coerceIn(0f, 0.99f),
                placementMode = placementMode,
                contextCueReason = best.contextNote,
                attributionSummary = summary,
            )
            updatedEvents.add(updatedEvt)
            lastEvent = updatedEvt
        }

        val (reconciledSpeakers, _) = reconcileCharacterColors(
            speakers = speakers,
            events = updatedEvents,
            rememberedColors = rememberedCharacterColors,
        )
        return reconciledSpeakers to updatedEvents
    }

    /**
     * Establishes a persistent outline color for each named main character when they first speak
     * (chronologically by first spoken event), reusing any previously remembered color from earlier
     * projects/sessions and avoiding collisions with co-occurring speakers.
     */
    fun reconcileCharacterColors(
        speakers: List<Speaker>,
        events: List<DialogueEvent>,
        rememberedColors: Map<String, Int>,
        paletteSize: Int = SpeakerColorOrder.PALETTE_SIZE,
    ): Pair<List<Speaker>, Map<String, Int>> {
        val safePaletteSize = paletteSize.coerceAtLeast(1)
        val updatedMemory = rememberedColors.toMutableMap()
        val firstSpokenMs = mutableMapOf<String, Long>()
        val manuallyAssignedSpeakerIds = mutableSetOf<String>()
        events.sortedBy { it.startTimeMs }.forEach { evt ->
            val spkId = evt.speakerId ?: return@forEach
            if (evt.lockedByUser) manuallyAssignedSpeakerIds.add(spkId)
            if (evt.dialogueType != DialogueType.SOUND_DESCRIPTION && spkId !in firstSpokenMs) {
                firstSpokenMs[spkId] = evt.startTimeMs
            }
        }

        fun isColorEligible(spk: Speaker): Boolean =
            spk.isConfirmedCharacter || spk.id in manuallyAssignedSpeakerIds

        val usedIndices = mutableSetOf<Int>()
        val resultMap = mutableMapOf<String, Speaker>()

        // Pass 1: honor already-remembered confirmed named characters & explicitly remembered locked colors first
        speakers.forEach { spk ->
            if (spk.isConfirmedCharacter && spk.hasDistinctCharacterName) {
                val key = normalizeCharacterKey(spk.customName)
                val remembered = updatedMemory[key]
                if (remembered != null) {
                    val rawSafe = ((remembered % safePaletteSize) + safePaletteSize) % safePaletteSize
                    val safeIdx = if (rawSafe !in usedIndices) {
                        rawSafe
                    } else {
                        (0 until safePaletteSize).firstOrNull { it !in usedIndices } ?: rawSafe
                    }
                    usedIndices.add(safeIdx)
                    resultMap[spk.id] = spk.copy(accentIndex = safeIdx, colorRemembered = true)
                } else if (spk.colorRemembered) {
                    val rawSafe = ((spk.accentIndex % safePaletteSize) + safePaletteSize) % safePaletteSize
                    val safeIdx = if (rawSafe !in usedIndices) {
                        rawSafe
                    } else {
                        (0 until safePaletteSize).firstOrNull { it !in usedIndices } ?: rawSafe
                    }
                    usedIndices.add(safeIdx)
                    updatedMemory[key] = safeIdx
                    resultMap[spk.id] = spk.copy(accentIndex = safeIdx, colorRemembered = true)
                }
            } else if (isColorEligible(spk) && spk.colorRemembered) {
                val rawSafe = ((spk.accentIndex % safePaletteSize) + safePaletteSize) % safePaletteSize
                val safeIdx = if (rawSafe !in usedIndices) {
                    rawSafe
                } else {
                    (0 until safePaletteSize).firstOrNull { it !in usedIndices } ?: rawSafe
                }
                usedIndices.add(safeIdx)
                resultMap[spk.id] = spk.copy(accentIndex = safeIdx, colorRemembered = true)
            }
        }

        // Pass 2: assign Primary -> Secondary -> Distinct unused colors in order of first spoken line
        val orderedRemaining = speakers
            .filter { it.id !in resultMap }
            .sortedWith(compareBy<Speaker> { firstSpokenMs[it.id] ?: Long.MAX_VALUE }.thenBy { it.id })

        orderedRemaining.forEach { spk ->
            if (isColorEligible(spk) && (spk.id in firstSpokenMs || spk.isLocked || spk.id in manuallyAssignedSpeakerIds)) {
                val chosen = (0 until safePaletteSize).firstOrNull { it !in usedIndices }
                    ?: (((spk.accentIndex % safePaletteSize) + safePaletteSize) % safePaletteSize)
                usedIndices.add(chosen)
                if (spk.hasDistinctCharacterName) {
                    updatedMemory[normalizeCharacterKey(spk.customName)] = chosen
                }
                resultMap[spk.id] = spk.copy(accentIndex = chosen, colorRemembered = true)
            } else {
                // Unknown / undiscovered speaker: keep provisional modulo index for turn alternation without reserving palette colors
                val safeIdx = ((spk.accentIndex % safePaletteSize) + safePaletteSize) % safePaletteSize
                resultMap[spk.id] = spk.copy(accentIndex = safeIdx, colorRemembered = false)
            }
        }

        return speakers.map { resultMap[it.id] ?: it } to updatedMemory
    }

    fun normalizeCharacterKey(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Applies a user correction to a single DialogueEvent, binds the learned FaceTrack,
     * spatial side, and acoustic voice profile to the chosen Speaker, and improves subsequent
     * unlocked events in the same project.
     */
    fun applyUserCorrection(
        project: DialogueLensProject,
        eventId: String,
        newSpeakerId: String?,
        manualAnchor: NormalizedPoint? = null,
    ): DialogueLensProject {
        val targetIndex = project.dialogueEvents.indexOfFirst { it.id == eventId }
        if (targetIndex == -1) return project

        val targetEvent = project.dialogueEvents[targetIndex]
        val correctedTrackId = targetEvent.faceTrackId
            ?: if (newSpeakerId != null) {
                project.faceTracks
                    .filter { it.shotId == targetEvent.shotId }
                    .firstOrNull { it.speakerId == newSpeakerId }
                    ?.id
            } else {
                null
            }

        val correctedTrack = project.faceTracks.firstOrNull { it.id == correctedTrackId }
        val trackCenterX = correctedTrack?.averageCenter()?.x

        val updatedSpeakers = project.speakers.map { spk ->
            if (spk.id == newSpeakerId) {
                val updatedTrackIds = if (correctedTrackId != null && correctedTrackId !in spk.learnedFaceTrackIds) {
                    spk.learnedFaceTrackIds + correctedTrackId
                } else {
                    spk.learnedFaceTrackIds
                }
                updateSpeakerVoiceCentroid(
                    spk.copy(
                        learnedFaceTrackIds = updatedTrackIds,
                        preferredScreenSideX = trackCenterX ?: spk.preferredScreenSideX,
                        isIdentityConfirmed = true,
                    ),
                    targetEvent.audioFeatures,
                )
            } else {
                spk
            }
        }

        val updatedFaceTracks = project.faceTracks.map { ft ->
            if (ft.id == correctedTrackId && newSpeakerId != null) {
                ft.copy(speakerId = newSpeakerId)
            } else {
                ft
            }
        }

        val correctedSpeakerPitch = updatedSpeakers.firstOrNull { it.id == newSpeakerId }?.voicePitchHz

        val updatedEvents = project.dialogueEvents.mapIndexed { idx, evt ->
            when {
                evt.id == eventId -> {
                    evt.copy(
                        speakerId = newSpeakerId,
                        speakerConfidence = 1.0f,
                        faceTrackId = correctedTrackId,
                        faceConfidence = if (correctedTrackId != null) 0.98f else evt.faceConfidence,
                        anchorPosition = manualAnchor ?: evt.anchorPosition,
                        placementMode = when {
                            manualAnchor != null -> PlacementMode.MANUAL_OVERRIDE
                            evt.dialogueType != DialogueType.SPOKEN -> PlacementMode.EDGE_DOCKED
                            else -> PlacementMode.AUTO
                        },
                        lockedByUser = true,
                        attributionSummary = "User verified",
                    )
                }
                // Active learning propagation: boost subsequent unlocked uncertain events sharing
                // POSITIVE matching speaker ID, face track ID, or acoustic voice signature.
                // Never propagate when two missing (null/blank) IDs compare equal!
                idx > targetIndex && !evt.lockedByUser && newSpeakerId != null &&
                    evt.speakerConfidence < 0.65f &&
                    (
                        (!targetEvent.speakerId.isNullOrBlank() && evt.speakerId == targetEvent.speakerId) ||
                            (!targetEvent.faceTrackId.isNullOrBlank() && evt.faceTrackId == targetEvent.faceTrackId) ||
                            isAcousticVoiceMatch(evt.audioFeatures, correctedSpeakerPitch, targetEvent.audioFeatures)
                        ) -> {
                    val matchingTrack = updatedFaceTracks.firstOrNull {
                        it.shotId == evt.shotId && it.speakerId == newSpeakerId
                    } ?: updatedFaceTracks.filter { it.shotId == evt.shotId }
                        .minByOrNull { ft ->
                            val prefX = updatedSpeakers.firstOrNull { it.id == newSpeakerId }?.preferredScreenSideX ?: 0.5f
                            abs(ft.averageCenter().x - prefX)
                        }
                    evt.copy(
                        speakerId = newSpeakerId,
                        speakerConfidence = 0.86f,
                        faceTrackId = matchingTrack?.id ?: evt.faceTrackId,
                        faceConfidence = if (matchingTrack != null) 0.88f else evt.faceConfidence,
                        placementMode = if (evt.dialogueType == DialogueType.SPOKEN) {
                            PlacementMode.AUTO
                        } else {
                            PlacementMode.EDGE_DOCKED
                        },
                        attributionSummary = "Refined from user correction",
                    )
                }
                else -> evt
            }
        }

        val correction = UserCorrection(
            id = "corr_${System.currentTimeMillis()}",
            eventId = eventId,
            previousSpeakerId = targetEvent.speakerId,
            newSpeakerId = newSpeakerId,
            manualAnchor = manualAnchor,
            timestampMs = System.currentTimeMillis(),
        )

        val (reconciledSpeakers, _) = reconcileCharacterColors(
            speakers = updatedSpeakers,
            events = updatedEvents,
            rememberedColors = emptyMap(),
        )

        return project.copy(
            speakers = reconciledSpeakers,
            faceTracks = updatedFaceTracks,
            dialogueEvents = updatedEvents,
            corrections = project.corrections + correction,
        )
    }

    private fun computeVoiceMatchScore(
        features: AudioVoiceFeatures?,
        speaker: Speaker,
        clusterToSpeaker: Map<String, String>,
    ): Float {
        if (features == null) return 0.5f
        val clusterMappedSpeakerId = features.voiceClusterId?.let { clusterToSpeaker[it] }
        val clusterBonus = when {
            clusterMappedSpeakerId == null -> 0f
            clusterMappedSpeakerId == speaker.id -> 0.25f
            else -> -0.25f
        }

        val refPitch = speaker.voicePitchHz
        val pitchScore = if (refPitch != null && refPitch > 0f && features.pitchHz > 0f) {
            // 0Hz diff -> 0.95, 70Hz+ diff -> 0.10
            (1f - (abs(refPitch - features.pitchHz) / 75f)).coerceIn(0.08f, 0.96f)
        } else {
            0.5f
        }

        val refBright = speaker.voiceBrightness
        val brightScore = if (refBright != null) {
            (1f - abs(refBright - features.spectralBrightness) * 1.6f).coerceIn(0.15f, 0.95f)
        } else {
            0.5f
        }

        val energyWeight = if (features.speechEnergy > 0.08f) 1f else 0.45f
        val raw = pitchScore * 0.72f + brightScore * 0.28f + clusterBonus
        return (0.5f + (raw - 0.5f) * energyWeight).coerceIn(0.05f, 0.98f)
    }

    private fun computeConversationalContextScore(
        event: DialogueEvent,
        prevEvent: DialogueEvent?,
        candidate: Speaker,
        allSpeakers: List<Speaker>,
        aiHint: AiTranslatedLine?,
    ): Pair<Float, String> {
        var score = 0.52f
        val reasons = mutableListOf<String>()

        val candidateSlotIndex = allSpeakers.indexOfFirst { it.id == candidate.id }
        val candidateSlotLetter = if (candidateSlotIndex >= 0) ('A' + candidateSlotIndex).toString() else null

        // 1. Gemini conversational context slot & turn hints (when available)
        if (aiHint != null) {
            if (aiHint.suggestedSpeakerSlot != "UNCERTAIN" && candidateSlotLetter != null) {
                if (aiHint.suggestedSpeakerSlot.equals(candidateSlotLetter, ignoreCase = true)) {
                    score += 0.34f
                    if (aiHint.contextReason.isNotBlank()) reasons.add(aiHint.contextReason)
                } else {
                    score -= 0.24f
                }
            }
            if (prevEvent?.speakerId != null) {
                when (aiHint.speakerTurnHint) {
                    "SAME_SPEAKER" -> if (candidate.id == prevEvent.speakerId) score += 0.18f else score -= 0.14f
                    "ALTERNATE_SPEAKER" -> if (candidate.id != prevEvent.speakerId) score += 0.18f else score -= 0.14f
                }
            }
        }

        // 2. Direct vocative name address exclusion:
        // If line calls another character by name ("Kenji, wait!"), the named person is the addressee!
        val combinedText = "${event.translatedText} ${event.sourceText}".lowercase()
        val candidateName = candidate.customName.trim().lowercase()
        val addressedCandidate = candidateName.length >= 2 && isAddressedByName(combinedText, candidateName)
        val addressedAnother = allSpeakers.any { other ->
            other.id != candidate.id &&
                other.customName.trim().length >= 2 &&
                isAddressedByName(combinedText, other.customName.trim().lowercase())
        }
        if (addressedCandidate && !addressedAnother) {
            score -= 0.34f
            reasons.add("Addresses ${candidate.displayName}")
        } else if (addressedAnother && !addressedCandidate) {
            score += 0.22f
            reasons.add("Speaks to partner")
        }

        // 3. Q&A turn-taking & clause continuation heuristics (works 100% offline too)
        if (prevEvent != null && prevEvent.speakerId != null) {
            val gapMs = (event.startTimeMs - prevEvent.endTimeMs).coerceAtLeast(0L)
            val prevText = "${prevEvent.translatedText} ${prevEvent.sourceText}".trim()
            val prevIsQuestion = prevText.endsWith("?") || prevText.endsWith("？") ||
                prevText.contains("?") || prevText.contains("？")
            val prevIsIncomplete = prevText.endsWith("...") || prevText.endsWith("…") ||
                prevText.endsWith("—") || prevText.endsWith(",")
            val currLower = event.translatedText.trim().lowercase()
            val startsWithContinuation = currLower.startsWith("and ") ||
                currLower.startsWith("but ") ||
                currLower.startsWith("so ") ||
                currLower.startsWith("because ")

            val hasSpeakerChangeChevron = event.sourceText.trimStart().startsWith(">>") ||
                event.translatedText.trimStart().startsWith(">>")

            when {
                hasSpeakerChangeChevron -> {
                    if (candidate.id != prevEvent.speakerId) {
                        score += 0.28f
                        reasons.add("Speaker turn marker (>>)")
                    } else {
                        score -= 0.20f
                    }
                }
                prevIsQuestion && gapMs < 4200L -> {
                    if (candidate.id != prevEvent.speakerId) {
                        score += 0.24f
                        reasons.add("Answers prior question")
                    } else {
                        score -= 0.16f
                    }
                }
                (prevIsIncomplete || startsWithContinuation) && gapMs < 1400L -> {
                    if (candidate.id == prevEvent.speakerId) {
                        score += 0.22f
                        reasons.add("Continues thought")
                    } else {
                        score -= 0.12f
                    }
                }
                gapMs in 200L..2600L -> {
                    // Natural back-and-forth conversational cadence mildly favors turn alternation
                    if (candidate.id != prevEvent.speakerId) score += 0.08f
                }
            }

            // Acoustic pitch jump vs consistency relative to previous utterance
            val currPitch = event.audioFeatures?.pitchHz ?: 0f
            val prevPitch = prevEvent.audioFeatures?.pitchHz ?: 0f
            if (currPitch > 0f && prevPitch > 0f) {
                val deltaHz = abs(currPitch - prevPitch)
                if (deltaHz < 24f && candidate.id == prevEvent.speakerId) {
                    score += 0.14f
                } else if (deltaHz > 48f && candidate.id != prevEvent.speakerId) {
                    score += 0.16f
                    reasons.add("Vocal pitch shift")
                }
            }
        }

        val note = reasons.distinct().take(2).joinToString(" · ")
        return score.coerceIn(0.05f, 0.98f) to note
    }

    private fun isAddressedByName(lowerText: String, lowerName: String): Boolean {
        val escaped = Regex.escape(lowerName)
        // Vocative patterns: "Kenji," / "Kenji!" / "Kenji?" / "Hey Kenji" / ", Kenji"
        val vocativeRegex = Regex("""(^|\b|,|hey\s+|oh\s+|wait\s+|—)$escaped(\s*[,!?…。—]|$)""")
        return vocativeRegex.containsMatchIn(lowerText)
    }

    private fun updateSpeakerVoiceCentroid(
        speaker: Speaker,
        audio: AudioVoiceFeatures?,
    ): Speaker {
        if (audio == null || audio.pitchHz <= 0f || audio.speechEnergy < 0.06f) return speaker
        val newPitch = speaker.voicePitchHz?.let { it * 0.65f + audio.pitchHz * 0.35f } ?: audio.pitchHz
        val newBright = speaker.voiceBrightness?.let { it * 0.65f + audio.spectralBrightness * 0.35f }
            ?: audio.spectralBrightness
        return speaker.copy(voicePitchHz = newPitch, voiceBrightness = newBright)
    }

    private fun isAcousticVoiceMatch(
        curr: AudioVoiceFeatures?,
        speakerPitchHz: Float?,
        refAudio: AudioVoiceFeatures?,
    ): Boolean {
        if (curr == null || curr.pitchHz <= 0f) return false
        val targetPitch = speakerPitchHz ?: refAudio?.pitchHz ?: return false
        if (targetPitch <= 0f) return false
        return abs(curr.pitchHz - targetPitch) <= 28f
    }

    private fun buildSummary(
        mouthScore: Float,
        audioFeatures: AudioVoiceFeatures?,
        audioMatch: Float,
        contextNote: String,
        offScreen: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        if (offScreen) {
            parts.add("Off-screen")
        } else if (mouthScore >= 0.55f) {
            parts.add("Lip sync ${(mouthScore * 100).roundToInt()}%")
        }
        if (audioFeatures != null && audioFeatures.pitchHz > 0f && audioMatch >= 0.62f) {
            parts.add("Voice ${audioFeatures.pitchHz.roundToInt()}Hz")
        }
        if (contextNote.isNotBlank()) {
            parts.add(contextNote)
        }
        return parts.joinToString(" · ").ifBlank { "Visual + dialogue continuity" }
    }

    private fun resolveSpeakerForTrack(
        track: FaceTrack,
        speakers: MutableList<Speaker>,
        centerX: Float,
        allTracks: List<FaceTrack>,
        sceneSpeakerSideX: Map<String, Float> = emptyMap(),
        allowGlobalPreferredSideFallback: Boolean = true,
    ): Speaker {
        track.speakerId?.let { id ->
            speakers.firstOrNull { it.id == id }?.let { return it }
        }
        speakers.firstOrNull { track.id in it.learnedFaceTrackIds }?.let { return it }

        val shotTrackCount = allTracks.count { it.shotId == track.shotId }
        val sortedByX = allTracks.filter { it.shotId == track.shotId }
            .sortedBy { it.averageCenter().x }
        val rank = sortedByX.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        while (speakers.size <= rank && speakers.size < 8) {
            val idx = speakers.size
            val letter = ('A' + idx)
            speakers.add(
                Speaker(
                    id = "spk_${letter.lowercaseChar()}",
                    defaultLabel = "Speaker $letter",
                    accentIndex = idx,
                    preferredScreenSideX = centerX,
                )
            )
        }

        if (shotTrackCount == 1 && speakers.size >= 2) {
            val sideAware = speakers.minByOrNull { spk ->
                val sideX = sceneSpeakerSideX[spk.id]
                    ?: if (allowGlobalPreferredSideFallback) spk.preferredScreenSideX else null
                sideX?.let { abs(it - centerX) } ?: 0.5f
            }
            if (sideAware != null) return sideAware
        }

        return speakers[rank.coerceIn(0, speakers.lastIndex)]
    }

    private data class CandidateScore(
        val track: FaceTrack,
        val speaker: Speaker,
        val mouthScore: Float,
        val voiceMatch: Float,
        val contextNote: String,
        val totalScore: Float,
    )

    private data class OffScreenCandidate(
        val speaker: Speaker,
        val score: Float,
        val audioScore: Float,
        val ctxNote: String,
    )
}
