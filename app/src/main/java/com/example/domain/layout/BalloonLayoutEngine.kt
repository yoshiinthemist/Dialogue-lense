package com.example.domain.layout

import com.example.data.model.BalloonStyleSettings
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.ConfidenceTier
import com.example.data.model.DeadSpaceZone
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.Speaker
import com.example.data.model.isBubbleSpeakerUnknown
import com.example.domain.analysis.HierarchicalContextEngine
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.LiveStageFaceTracker
import com.example.domain.analysis.SubtitleTimelineMatcher
import kotlin.math.abs
import kotlin.math.hypot

enum class TailDirection {
    NONE,
    DOWN_LEFT,
    DOWN_RIGHT,
    LEFT,
    RIGHT,
    UP_LEFT,
    UP_RIGHT,
}

data class PlacedBalloon(
    val event: DialogueEvent,
    val speaker: Speaker?,
    val rect: NormalizedRect,
    val tailTarget: NormalizedPoint?,
    val tailDirection: TailDirection,
    val placementMode: PlacementMode,
    val isOffScreenEdge: Boolean,
    val edgeSide: DialogueType?,
    val isAmbiguousFallback: Boolean,
    val emphasisScore: Float = 0f,
    val isShouted: Boolean = false,
    val sceneLuminance: Float = 0.25f,
    val speakerLineOrdinal: Int = 0,
    val isUnknownSpeaker: Boolean = false,
    val lowestDensityEdge: ScreenEdge? = null,
    val heatmapDensity: Float = 0f,
    val identityConfidence: Float = 0f,
    val locationConfidence: Float = 0f,
)

class BalloonLayoutEngine {

    val stabilityDiagnostics: VisualStabilityDiagnostics = VisualStabilityDiagnostics()

    private data class CommittedBodyState(
        val sceneKey: String,
        val center: NormalizedPoint,
        val previousCenter: NormalizedPoint? = null,
        val lastRelocationMs: Long = 0L,
        val lastScore: Float = Float.MAX_VALUE,
        val missingFaceStreak: Int = 0,
        val lastTailTarget: NormalizedPoint? = null,
    )

    private val smoothedPositions = mutableMapOf<String, NormalizedPoint>()
    private val smoothedTailTargets = mutableMapOf<String, NormalizedPoint>()
    private val lastShotIds = mutableMapOf<String, String>()
    private val committedBodies = mutableMapOf<String, CommittedBodyState>()
    private val previousFaceBoxesByShot = mutableMapOf<String, Map<String, NormalizedRect>>()
    private val recentLiveFacesByShot = mutableMapOf<String, Pair<Long, List<Pair<String, com.example.data.model.FaceSample>>>>()
    private val lastTailWasLiveTracked = mutableMapOf<String, Boolean>()
    private val timelineMatcher = SubtitleTimelineMatcher()

    private var cachedEventsRef: List<DialogueEvent>? = null
    private var cachedOrdinals: Map<String, Int> = emptyMap()
    private var lastActiveEventIds: Set<String> = emptySet()
    private var lastGlobalSceneKey: String = ""

    fun clearSmoothing() {
        smoothedPositions.clear()
        smoothedTailTargets.clear()
        lastShotIds.clear()
        committedBodies.clear()
        previousFaceBoxesByShot.clear()
        recentLiveFacesByShot.clear()
        lastTailWasLiveTracked.clear()
        lastActiveEventIds = emptySet()
        timelineMatcher.reset()
    }

    /**
     * Flushes stale per-shot face tracking caches on visual cuts without wiping active
     * timeline matcher state or forcing mid-sentence balloon flashes.
     */
    fun onVisualCutBoundary() {
        previousFaceBoxesByShot.clear()
        recentLiveFacesByShot.clear()
    }

    /**
     * Selectively invalidates layout state only for [eventIds] modified by a localized repair,
     * leaving unrelated active speech bubbles completely undisturbed on screen.
     */
    fun invalidateEventsOnly(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        for (id in eventIds) {
            smoothedPositions.remove(id)
            smoothedTailTargets.remove(id)
            lastShotIds.remove(id)
            committedBodies.remove(id)
            lastTailWasLiveTracked.remove(id)
        }
        cachedEventsRef = null
    }

    fun computeLayout(
        project: DialogueLensProject,
        currentMs: Long,
        styleSettings: BalloonStyleSettings = project.styleSettings,
        liveFaces: List<LiveFaceObservation> = emptyList(),
        sceneEpoch: Int = 0,
        hasLiveVisionSignal: Boolean = false,
        liveFrameObservedEmpty: Boolean = false,
        activeChapterId: String? = null,
        measuredNormalizedSizes: Map<String, Pair<Float, Float>> = emptyMap(),
        fixedZoneTaillessMode: Boolean = false,
    ): List<PlacedBalloon> {
        stabilityDiagnostics.recordLayoutEvaluation()
        val activeEvents = timelineMatcher.matchActiveEvents(project.dialogueEvents, currentMs)
            // Strict temporal validation: never allow stale dialogue to linger past its end boundary
            .filter { evt -> currentMs <= evt.endTimeMs + 160L }

        val currentActiveIds = activeEvents.map { it.id }.toSet()
        if (currentActiveIds != lastActiveEventIds) {
            val activated = (currentActiveIds - lastActiveEventIds).size
            val deactivated = (lastActiveEventIds - currentActiveIds).size
            if (activated > 0 || deactivated > 0) {
                stabilityDiagnostics.recordBubbleActivationDelta(activated, deactivated)
            }
            // Clean committed state for expired cues so memory stays bounded
            for (expiredId in (lastActiveEventIds - currentActiveIds)) {
                committedBodies.remove(expiredId)
            }
            lastActiveEventIds = currentActiveIds
        }

        if (activeEvents.isEmpty()) return emptyList()

        val activeShot = project.shots.firstOrNull { currentMs in it.startMs..it.endMs }
        val shotId = activeShot?.id ?: activeEvents.first().shotId
        val effectiveSceneKey = if (sceneEpoch > 0 || !activeChapterId.isNullOrBlank()) {
            "$shotId#ep$sceneEpoch#${activeChapterId.orEmpty()}"
        } else {
            shotId
        }
        if (lastGlobalSceneKey.isNotEmpty() && lastGlobalSceneKey != effectiveSceneKey) {
            stabilityDiagnostics.recordSceneEpochChange()
        }
        lastGlobalSceneKey = effectiveSceneKey
        val shotLuminance = activeShot?.sceneLuminance ?: 0.25f

        // Fuse precomputed face tracks with real-time 6fps ML Kit live viewport observations
        val precomputedSamples = project.faceTracks
            .filter { it.shotId == shotId }
            .mapNotNull { track ->
                track.sampleAt(currentMs)?.let { track.id to it }
            }
        val rawLiveSamples = liveFaces.mapIndexed { idx, obs ->
            val trackId = "live_${obs.trackingId ?: idx}"
            trackId to com.example.data.model.FaceSample(
                timeMs = currentMs,
                boundingBox = obs.boundingBox,
                mouthActivity = 0.72f,
                headYawDeg = obs.headYawDeg,
                mouthPoint = obs.mouthPoint,
            )
        }
        val liveConvertedSamples = if (rawLiveSamples.isNotEmpty()) {
            recentLiveFacesByShot[effectiveSceneKey] = currentMs to rawLiveSamples
            rawLiveSamples
        } else if (liveFrameObservedEmpty) {
            // Live vision confirmed an empty / B-roll / title-card frame: do NOT recycle stale faces!
            recentLiveFacesByShot.remove(effectiveSceneKey)
            emptyList()
        } else {
            val cached = recentLiveFacesByShot[effectiveSceneKey]
            if (cached != null && abs(currentMs - cached.first) <= 650L) {
                cached.second
            } else {
                emptyList()
            }
        }
        val faceSamples = if (precomputedSamples.isNotEmpty()) {
            // Enhance existing MP4 face track boxes/mouths whenever a matching live ML Kit frame exists
            precomputedSamples.map { (tid, sample) ->
                val liveMatch = liveConvertedSamples.minByOrNull { (_, live) ->
                    hypot(
                        (live.boundingBox.centerX - sample.boundingBox.centerX).toDouble(),
                        (live.boundingBox.centerY - sample.boundingBox.centerY).toDouble(),
                    )
                }?.second
                if (liveMatch != null && hypot(
                        (liveMatch.boundingBox.centerX - sample.boundingBox.centerX).toDouble(),
                        (liveMatch.boundingBox.centerY - sample.boundingBox.centerY).toDouble(),
                    ) <= 0.16
                ) {
                    tid to liveMatch
                } else {
                    tid to sample
                }
            }
        } else {
            liveConvertedSamples
        }
        val faceBoxes = faceSamples.map { it.first to it.second.boundingBox }
        val faceExclusionZones = faceBoxes.map { it.second.expand(0.045f, 0.038f) }
        val bodyExclusionZones = faceBoxes.map { deriveSpeakerSilhouetteZone(it.second) }
        val lipCorridorZones = if (styleSettings.lipReadingSafeZone) {
            faceBoxes.map { deriveLipReadingCorridorZone(it.second) }
        } else {
            emptyList()
        }

        val shotFocalZones = activeShot?.focalExclusionZones.orEmpty()
        val allSceneObstacles = project.textExclusionZones + shotFocalZones
        val occupiedBalloonRects = mutableListOf<NormalizedRect>()
        val results = mutableListOf<PlacedBalloon>()
        var offScreenCount = 0

        // Detect speaker movement from BOTH cross-frame cache AND recent ML Kit FaceTrack trajectory (currentMs - 400ms)
        val priorFaceMap = previousFaceBoxesByShot[effectiveSceneKey].orEmpty()
        val historyFallbackMap = if (priorFaceMap.isEmpty() && currentMs >= 200L) {
            project.faceTracks
                .filter { it.shotId == shotId }
                .mapNotNull { track ->
                    track.sampleAt((currentMs - 400L).coerceAtLeast(0L))?.let { track.id to it.boundingBox }
                }
                .toMap()
        } else {
            emptyMap()
        }
        val effectivePrevFaces = if (priorFaceMap.isNotEmpty()) priorFaceMap else historyFallbackMap
        val (mlKitMoved, mlKitDelta) = DeadSpaceHeatmapAnalyzer.detectSpeakerMovement(
            currentFaces = faceBoxes,
            previousFacesByKey = effectivePrevFaces,
        )
        if (faceBoxes.isNotEmpty()) {
            previousFaceBoxesByShot[effectiveSceneKey] = faceBoxes.toMap()
        } else if (liveFrameObservedEmpty) {
            previousFaceBoxesByShot.remove(effectiveSceneKey)
        }
        // Compute base ML Kit occupancy heatmap for continuous edge dead-space evaluation
        val baseHeatmap = DeadSpaceHeatmapAnalyzer.computeHeatmap(
            faceZones = faceExclusionZones,
            lipZones = lipCorridorZones,
            bodyZones = bodyExclusionZones,
            obstacleZones = allSceneObstacles,
            occupiedBubbles = emptyList(),
            speakerMoved = mlKitMoved,
            maxSpeakerDelta = mlKitDelta,
        )

        // Cached per-project speaker ordinals (eliminates O(N log N) sorting on every 16ms frame)
        val ordinalByEventId = if (cachedEventsRef === project.dialogueEvents) {
            cachedOrdinals
        } else {
            val countsBySpeaker = mutableMapOf<String, Int>()
            val computed = HashMap<String, Int>(project.dialogueEvents.size)
            project.dialogueEvents.sortedBy { it.startTimeMs }.forEach { evt ->
                val spkId = evt.speakerId
                if (spkId != null && evt.dialogueType != DialogueType.SOUND_DESCRIPTION) {
                    val nextCount = (countsBySpeaker[spkId] ?: 0) + 1
                    countsBySpeaker[spkId] = nextCount
                    computed[evt.id] = nextCount
                }
            }
            cachedEventsRef = project.dialogueEvents
            cachedOrdinals = computed
            computed
        }

        // Place all dialogue / sketch bubbles BEFORE SFX notes so SFX checks every active bubble
        val orderedEvents = activeEvents.sortedBy {
            if (it.dialogueType == DialogueType.SOUND_DESCRIPTION) 1 else 0
        }

        for (event in orderedEvents) {
            val speaker = project.speakers.firstOrNull { it.id == event.speakerId }
            val lineOrdinal = ordinalByEventId[event.id] ?: 0
            val text = event.activeText(project.translationMode)

            // Prefer actual measured normalized bubble dimensions when reported by Compose,
            // falling back to text-based normalized size estimation on initial placement
            val measuredPair = measuredNormalizedSizes[event.id]
            val estWidth = (measuredPair?.first?.takeIf { it in 0.06f..0.88f }
                ?: estimateNormalizedWidth(text, styleSettings.fontScale, styleSettings.maxLines))
            val estHeight = (measuredPair?.second?.takeIf { it in 0.04f..0.48f }
                ?: estimateNormalizedHeight(text, styleSettings.fontScale, styleSettings.maxLines))

            // 1. User manual drag override takes priority (clamped inside safe screen margins)
            if (event.placementMode == PlacementMode.MANUAL_OVERRIDE && event.anchorPosition != null) {
                val rect = centeredRect(
                    cx = event.anchorPosition.x,
                    cy = event.anchorPosition.y,
                    w = estWidth,
                    h = estHeight,
                )
                val liveMatchedManual = if (event.faceTrackId != null) {
                    faceSamples.firstOrNull { it.first == event.faceTrackId }
                } else if (liveConvertedSamples.isNotEmpty()) {
                    matchLiveFaceForSpokenEvent(
                        event = event,
                        speaker = speaker,
                        liveSamples = liveConvertedSamples,
                    )
                } else {
                    null
                }
                val rawFaceTarget = liveMatchedManual?.second?.let { computeMouthPoint(it) }
                val faceTarget = applyTailTargetSmoothing(
                    eventId = event.id,
                    shotId = effectiveSceneKey,
                    target = rawFaceTarget,
                    reducedMotion = styleSettings.reducedMotion,
                    hasLiveFaceTracking = liveFaces.isNotEmpty(),
                )
                val resolvedTailTarget = if (fixedZoneTaillessMode) null else faceTarget
                occupiedBalloonRects.add(rect)
                val emphasis = resolveEmphasis(event)
                results.add(
                    PlacedBalloon(
                        event = event,
                        speaker = speaker,
                        rect = rect,
                        tailTarget = resolvedTailTarget,
                        tailDirection = computeTailDirection(rect, resolvedTailTarget),
                        placementMode = PlacementMode.MANUAL_OVERRIDE,
                        isOffScreenEdge = false,
                        edgeSide = null,
                        isAmbiguousFallback = false,
                        emphasisScore = emphasis.first,
                        isShouted = emphasis.second,
                        sceneLuminance = shotLuminance,
                        speakerLineOrdinal = lineOrdinal,
                        isUnknownSpeaker = isBubbleSpeakerUnknown(event, speaker),
                        identityConfidence = if (event.lockedByUser) 1f else event.speakerConfidence,
                        locationConfidence = if (resolvedTailTarget != null) 0.9f else 0.7f,
                    )
                )
                continue
            }

            // 1a. Tailless Fixed-Zone Subtitle Mode: deterministic calm screen lanes with almost no movement and NO speech tail
            if (fixedZoneTaillessMode && event.dialogueType == DialogueType.SPOKEN) {
                val isUnknown = isBubbleSpeakerUnknown(event, speaker)
                val fixedW = estWidth.coerceIn(0.28f, 0.50f)
                val fixedH = estHeight.coerceIn(0.10f, 0.24f)
                val existingFixed = committedBodies[event.id]
                val fixedRect = if (existingFixed != null) {
                    val lockedRect = centeredRect(existingFixed.center.x, existingFixed.center.y, fixedW, fixedH)
                    val severelyObstructed = faceExclusionZones.any { it.intersectionArea(lockedRect) > 0.012f } ||
                        occupiedBalloonRects.any { it.intersectionArea(lockedRect) > 0.004f }
                    if (!severelyObstructed) {
                        lockedRect
                    } else {
                        placeFixedZoneTaillessRect(
                            w = fixedW,
                            h = fixedH,
                            speaker = speaker,
                            anchorX = event.anchorPosition?.x,
                            occupied = occupiedBalloonRects + allSceneObstacles,
                            faceExclusions = faceExclusionZones,
                            lipCorridorZones = lipCorridorZones,
                            hasMultipleSpeakers = project.speakers.size >= 2,
                        )
                    }
                } else {
                    placeFixedZoneTaillessRect(
                        w = fixedW,
                        h = fixedH,
                        speaker = speaker,
                        anchorX = event.anchorPosition?.x,
                        occupied = occupiedBalloonRects + allSceneObstacles,
                        faceExclusions = faceExclusionZones,
                        lipCorridorZones = lipCorridorZones,
                        hasMultipleSpeakers = project.speakers.size >= 2,
                    )
                }
                committedBodies[event.id] = CommittedBodyState(
                    sceneKey = effectiveSceneKey,
                    center = NormalizedPoint(fixedRect.centerX, fixedRect.centerY),
                    previousCenter = existingFixed?.center,
                    lastRelocationMs = existingFixed?.lastRelocationMs ?: currentMs,
                    lastScore = 0f,
                    missingFaceStreak = 0,
                    lastTailTarget = null,
                )
                occupiedBalloonRects.add(fixedRect)
                val emphasis = resolveEmphasis(event)
                results.add(
                    PlacedBalloon(
                        event = event,
                        speaker = speaker,
                        rect = fixedRect,
                        tailTarget = null,
                        tailDirection = TailDirection.NONE,
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        isOffScreenEdge = false,
                        edgeSide = null,
                        isAmbiguousFallback = isUnknown,
                        emphasisScore = emphasis.first,
                        isShouted = emphasis.second,
                        sceneLuminance = shotLuminance,
                        speakerLineOrdinal = lineOrdinal,
                        isUnknownSpeaker = isUnknown,
                        identityConfidence = if (isUnknown) 0.3f else event.speakerConfidence,
                        locationConfidence = 0f,
                    )
                )
                continue
            }

            // 1b. Accessibility Stable Positioning or Reading Comfort mode: calm, steady, tailless reading zone preserving speaker identity & color
            val useStableCalmMode = styleSettings.stablePositioning ||
                styleSettings.visualStyle == BubbleVisualStyle.READING_COMFORT
            if (useStableCalmMode && event.dialogueType == DialogueType.SPOKEN) {
                val isUnknown = isBubbleSpeakerUnknown(event, speaker)
                val stableRect = placeNeutralFallbackRect(
                    w = estWidth,
                    h = estHeight,
                    occupied = occupiedBalloonRects + allSceneObstacles,
                    faceExclusions = faceExclusionZones,
                    lipCorridorZones = lipCorridorZones,
                    speakerAccentIndex = speaker?.accentIndex,
                    hasMultipleSpeakers = project.speakers.size >= 2,
                )
                occupiedBalloonRects.add(stableRect)
                val emphasis = resolveEmphasis(event)
                results.add(
                    PlacedBalloon(
                        event = event,
                        speaker = speaker,
                        rect = stableRect,
                        tailTarget = null,
                        tailDirection = TailDirection.NONE,
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        isOffScreenEdge = false,
                        edgeSide = null,
                        isAmbiguousFallback = isUnknown,
                        emphasisScore = emphasis.first,
                        isShouted = emphasis.second,
                        sceneLuminance = shotLuminance,
                        speakerLineOrdinal = lineOrdinal,
                        isUnknownSpeaker = isUnknown,
                        identityConfidence = if (isUnknown) 0.3f else event.speakerConfidence,
                        locationConfidence = 0f,
                    )
                )
                continue
            }

            // 2. Narration / Voice-over / SFX / Internal Monologue / Off-Screen treatments (strictly bubble- & face-safe!)
            if (event.dialogueType != DialogueType.SPOKEN) {
                // Cutaway side-memory safety net: route generic OFF_SCREEN_BEHIND to speaker's remembered studio side
                val resolvedEdgeType = if (event.dialogueType == DialogueType.OFF_SCREEN_BEHIND) {
                    val sideX = speaker?.preferredScreenSideX
                    when {
                        sideX != null && sideX < 0.45f -> DialogueType.OFF_SCREEN_LEFT
                        sideX != null && sideX > 0.55f -> DialogueType.OFF_SCREEN_RIGHT
                        else -> event.dialogueType
                    }
                } else {
                    event.dialogueType
                }
                val isOffScreen = resolvedEdgeType == DialogueType.OFF_SCREEN_LEFT ||
                    resolvedEdgeType == DialogueType.OFF_SCREEN_RIGHT ||
                    resolvedEdgeType == DialogueType.OFF_SCREEN_BEHIND
                val stackSlot = if (isOffScreen) offScreenCount++ else 0
                val edgeRect = if (resolvedEdgeType == DialogueType.SOUND_DESCRIPTION) {
                    val sfxW = estWidth.coerceIn(0.22f, 0.44f)
                    val sfxH = (estHeight * 0.78f).coerceIn(0.065f, 0.14f)
                    val sameShotPrevCenter = smoothedPositions[event.id]?.takeIf { lastShotIds[event.id] == effectiveSceneKey }
                    val targetSfxRect = placeSfxSafeRect(
                        w = sfxW,
                        h = sfxH,
                        occupiedBubbles = occupiedBalloonRects,
                        faceExclusions = faceExclusionZones,
                        lipCorridorZones = lipCorridorZones,
                        bodyExclusions = bodyExclusionZones,
                        focalObstacles = allSceneObstacles,
                        previousCenter = sameShotPrevCenter,
                    )
                    val smoothedSfxCenter = applyTemporalSmoothing(
                        eventId = event.id,
                        shotId = effectiveSceneKey,
                        target = NormalizedPoint(targetSfxRect.centerX, targetSfxRect.centerY),
                        reducedMotion = styleSettings.reducedMotion,
                        movementSensitivity = styleSettings.movementSensitivity,
                    )
                    centeredRect(smoothedSfxCenter.x, smoothedSfxCenter.y, sfxW, sfxH)
                } else {
                    placeSpecialDialogueRect(
                        type = resolvedEdgeType,
                        w = estWidth,
                        h = estHeight,
                        occupied = occupiedBalloonRects + allSceneObstacles,
                        faceExclusions = faceExclusionZones,
                        lipCorridorZones = lipCorridorZones,
                        stackIndex = stackSlot,
                    )
                }
                occupiedBalloonRects.add(edgeRect)
                val emphasis = resolveEmphasis(event)
                results.add(
                    PlacedBalloon(
                        event = event,
                        speaker = speaker,
                        rect = edgeRect,
                        tailTarget = null,
                        tailDirection = TailDirection.NONE,
                        placementMode = PlacementMode.EDGE_DOCKED,
                        isOffScreenEdge = isOffScreen,
                        edgeSide = resolvedEdgeType,
                        isAmbiguousFallback = false,
                        emphasisScore = emphasis.first,
                        isShouted = emphasis.second,
                        sceneLuminance = shotLuminance,
                        speakerLineOrdinal = lineOrdinal,
                        isUnknownSpeaker = isBubbleSpeakerUnknown(event, speaker),
                    )
                )
                continue
            }

            // 3. Spatial positioning via face track / live ML Kit mouth match / Scout-reported speaker coordinates
            val matchedPair = when {
                event.faceTrackId != null -> faceSamples.firstOrNull { it.first == event.faceTrackId }
                liveConvertedSamples.isNotEmpty() && !isBubbleSpeakerUnknown(event, speaker) ->
                    matchLiveFaceForSpokenEvent(
                        event = event,
                        speaker = speaker,
                        liveSamples = liveConvertedSamples,
                    )
                else -> null
            }
            val matchedSample = matchedPair?.second
            val trackSample = matchedSample?.boundingBox
            val scoutAnchor = event.anchorPosition
            val isUnknown = isBubbleSpeakerUnknown(event, speaker)

            // Cutaway safeguard for YouTube live ML Kit: if ML Kit clearly sees 1-2 faces in frame and NONE are near
            // the speaker's expected horizontal column, dock off-screen / reaction-safe rather than mispointing onto a silent listener!
            val reactionCutawaySide = if (
                matchedSample == null &&
                liveConvertedSamples.size in 1..2 &&
                !isUnknown &&
                event.faceTrackId == null
            ) {
                val expectedX = speaker?.preferredScreenSideX ?: scoutAnchor?.x
                if (expectedX != null) {
                    val onlyDisjointFaces = liveConvertedSamples.all { (_, sample) ->
                        abs(sample.boundingBox.centerX - expectedX) > 0.26f
                    }
                    if (onlyDisjointFaces) {
                        when {
                            expectedX < 0.44f -> DialogueType.OFF_SCREEN_LEFT
                            expectedX > 0.56f -> DialogueType.OFF_SCREEN_RIGHT
                            else -> DialogueType.OFF_SCREEN_BEHIND
                        }
                    } else null
                } else null
            } else null

            if (reactionCutawaySide != null) {
                val edgeRect = placeSpecialDialogueRect(
                    type = reactionCutawaySide,
                    w = estWidth,
                    h = estHeight,
                    occupied = occupiedBalloonRects + allSceneObstacles,
                    faceExclusions = faceExclusionZones,
                    lipCorridorZones = lipCorridorZones,
                    stackIndex = offScreenCount++,
                )
                occupiedBalloonRects.add(edgeRect)
                val emphasis = resolveEmphasis(event)
                results.add(
                    PlacedBalloon(
                        event = event,
                        speaker = speaker,
                        rect = edgeRect,
                        tailTarget = null,
                        tailDirection = TailDirection.NONE,
                        placementMode = PlacementMode.EDGE_DOCKED,
                        isOffScreenEdge = true,
                        edgeSide = reactionCutawaySide,
                        isAmbiguousFallback = false,
                        emphasisScore = emphasis.first,
                        isShouted = emphasis.second,
                        sceneLuminance = shotLuminance,
                        speakerLineOrdinal = lineOrdinal,
                        isUnknownSpeaker = isUnknown,
                    )
                )
                continue
            }

            // Core Rendering Rule (1.0): Never point a directional tail at empty space / B-roll / unverified post-cut frames!
            // If live vision confirms 0 visible faces (e.g., B-roll, title card, chapter bumper, empty studio),
            // render a speaker-colored, tailless bubble when speaker is known, or neutral caption when unknown.
            val liveVisionInvalidatesStaticAnchor = hasLiveVisionSignal &&
                trackSample == null &&
                (liveFrameObservedEmpty || liveConvertedSamples.isEmpty())

            if (trackSample == null && scoutAnchor != null && event.confidenceTier != ConfidenceTier.LOW && !liveVisionInvalidatesStaticAnchor) {
                val targetX = scoutAnchor.x.coerceIn(0.12f, 0.88f)
                val targetY = scoutAnchor.y.coerceIn(0.14f, 0.72f)
                // Synthesize a generous full Head/Forehead/Mouth box around the Scout's reported mouth/face anchor
                // so speech bubbles NEVER clip eyes, forehead, hair, or chin, even if Frame 0 hasn't resolved ML Kit yet.
                val synthFaceBox = NormalizedRect(
                    left = (targetX - 0.145f).coerceAtLeast(0.01f),
                    top = (targetY - 0.26f).coerceAtLeast(0.01f),
                    right = (targetX + 0.145f).coerceAtMost(0.99f),
                    bottom = (targetY + 0.10f).coerceAtMost(0.95f),
                )
                val upperPortraitHeadGuard = if (faceExclusionZones.isEmpty() && targetY <= 0.58f) {
                    NormalizedRect(
                        left = (targetX - 0.135f).coerceAtLeast(0.02f),
                        top = 0.05f,
                        right = (targetX + 0.135f).coerceAtMost(0.98f),
                        bottom = 0.40f,
                    )
                } else {
                    null
                }
                val bystanderAvoidBoxes = event.avoidBoxes.map { it.expand(0.02f, 0.02f) }
                val scoutEstWidth = estWidth.coerceIn(0.26f, 0.48f)
                val scoutEstHeight = estHeight.coerceIn(0.12f, 0.24f)
                val halfW = scoutEstWidth * 0.5f
                val halfH = scoutEstHeight * 0.5f
                val allObstacles = allSceneObstacles + occupiedBalloonRects

                // Build a 12-zone negative-space ("dead space") candidate field across the safe frame:
                // upper/side open background pockets, speaker-adjacent air, and clean lower desk space
                val belowY = (synthFaceBox.bottom + 0.055f + halfH).coerceIn(0.46f + halfH, 0.90f - halfH)
                val belowX = when {
                    targetX < 0.36f -> (targetX + 0.05f).coerceIn(0.05f + halfW, 0.62f - halfW)
                    targetX > 0.64f -> (targetX - 0.05f).coerceIn(0.38f + halfW, 0.95f - halfW)
                    else -> targetX.coerceIn(0.05f + halfW, 0.95f - halfW)
                }
                val rightSideX = (synthFaceBox.right + 0.04f + halfW).coerceAtMost(0.95f - halfW)
                val leftSideX = (synthFaceBox.left - 0.04f - halfW).coerceAtLeast(0.05f + halfW)
                val upperSideY = (targetY - 0.08f).coerceIn(0.055f + halfH, 0.44f)
                val sideY = (targetY + 0.01f).coerceIn(0.055f + halfH, 0.78f - halfH)
                val aboveY = synthFaceBox.top - 0.04f - halfH
                val preferredZoneCenter = deadSpaceAnchorCenter(event.preferredDeadSpaceZone, halfW, halfH)
                val sameShotPrev = smoothedPositions[event.id]?.takeIf { lastShotIds[event.id] == effectiveSceneKey }
                val scoutSynthList = listOf(synthFaceBox)
                val scoutHeatmap = DeadSpaceHeatmapAnalyzer.computeHeatmap(
                    faceZones = if (faceExclusionZones.isNotEmpty()) faceExclusionZones + scoutSynthList else scoutSynthList,
                    lipZones = lipCorridorZones,
                    bodyZones = bodyExclusionZones,
                    obstacleZones = allObstacles + bystanderAvoidBoxes,
                    occupiedBubbles = occupiedBalloonRects,
                    speakerMoved = mlKitMoved,
                    maxSpeakerDelta = mlKitDelta,
                )

                val scoutCandidates = buildList {
                    if (sameShotPrev != null) add(sameShotPrev)
                    if (preferredZoneCenter != null) add(preferredZoneCenter)
                    // Quiet upper & side negative-space pockets (preferred background dead space)
                    add(NormalizedPoint(0.055f + halfW, 0.055f + halfH)) // TOP_LEFT
                    add(NormalizedPoint(0.50f, 0.055f + halfH))          // TOP_CENTER
                    add(NormalizedPoint(0.945f - halfW, 0.055f + halfH)) // TOP_RIGHT
                    add(NormalizedPoint(0.055f + halfW, upperSideY))     // UPPER_LEFT
                    add(NormalizedPoint(0.945f - halfW, upperSideY))     // UPPER_RIGHT
                    add(NormalizedPoint(0.055f + halfW, 0.44f))          // MID_LEFT
                    add(NormalizedPoint(0.945f - halfW, 0.44f))          // MID_RIGHT
                    // Speaker-adjacent open air
                    if (targetX <= 0.56f) {
                        add(NormalizedPoint(rightSideX, upperSideY))
                        add(NormalizedPoint(rightSideX, sideY))
                    }
                    if (targetX >= 0.44f) {
                        add(NormalizedPoint(leftSideX, upperSideY))
                        add(NormalizedPoint(leftSideX, sideY))
                    }
                    if (aboveY >= 0.035f + halfH) {
                        add(NormalizedPoint(targetX.coerceIn(0.055f + halfW, 0.945f - halfW), aboveY))
                    }
                    // Lower torso / desk fallback zones
                    add(NormalizedPoint(belowX, belowY))
                    add(NormalizedPoint(0.055f + halfW, 0.88f - halfH))  // BOTTOM_LEFT
                    add(NormalizedPoint(0.945f - halfW, 0.88f - halfH))  // BOTTOM_RIGHT
                    add(NormalizedPoint(targetX.coerceIn(0.055f + halfW, 0.945f - halfW), (0.89f - halfH).coerceAtLeast(belowY)))
                    // Full 2D screen-wide roaming grid + lowest-density edge coordinate buffers
                    addAll(
                        DeadSpaceHeatmapAnalyzer.buildPriorityEdgeCandidates(
                            heatmap = scoutHeatmap,
                            halfW = halfW,
                            halfH = halfH,
                        )
                    )
                    val roamXs = listOf(0.14f, 0.28f, 0.40f, 0.50f, 0.60f, 0.72f, 0.86f)
                    val roamYs = listOf(0.10f, 0.24f, 0.40f, 0.60f, 0.80f)
                    for (gx in roamXs) {
                        for (gy in roamYs) {
                            add(
                                NormalizedPoint(
                                    x = gx.coerceIn(0.05f + halfW, 0.95f - halfW),
                                    y = gy.coerceIn(0.04f + halfH, 0.90f - halfH),
                                )
                            )
                        }
                    }
                }

                val mouthRef = NormalizedPoint(targetX, targetY.coerceIn(0.14f, 0.78f))
                fun scoreScoutCandidate(cand: NormalizedPoint): Float {
                    val r = centeredRect(cand.x, cand.y, scoutEstWidth, scoutEstHeight)
                    var pen = 0f
                    val faceOverlap = r.intersectionArea(synthFaceBox)
                    if (faceOverlap > 0f) {
                        pen += faceOverlap * 9000f + 280f
                    }
                    if (upperPortraitHeadGuard != null) {
                        val headColOv = r.intersectionArea(upperPortraitHeadGuard)
                        if (headColOv > 0f) pen += headColOv * 5500f + 220f
                    }
                    for (avoid in bystanderAvoidBoxes) {
                        val avOv = r.intersectionArea(avoid)
                        if (avOv > 0f) pen += avOv * 8500f + 260f
                    }
                    for (obs in allObstacles) {
                        val ov = r.intersectionArea(obs)
                        if (ov > 0f) pen += ov * 2400f + 120f
                    }
                    val candCenterPt = NormalizedPoint(r.centerX, r.centerY)
                    for (avoid in bystanderAvoidBoxes) {
                        if (segmentIntersectsRect(candCenterPt, mouthRef, avoid)) {
                            pen += 320f
                        }
                    }
                    if (r.left < 0.045f) pen += (0.045f - r.left) * 80f
                    if (r.right > 0.955f) pen += (r.right - 0.955f) * 80f
                    if (r.top < 0.03f) pen += (0.03f - r.top) * 80f

                    if (preferredZoneCenter != null) {
                        val distToPref = hypot(
                            (r.centerX - preferredZoneCenter.x).toDouble(),
                            (r.centerY - preferredZoneCenter.y).toDouble(),
                        ).toFloat()
                        if (distToPref < 0.14f) pen -= 18f * (1f - distToPref / 0.14f)
                    }
                    if (r.centerY < synthFaceBox.bottom) {
                        pen -= 0.4f
                    }
                    if (abs(targetX - 0.50f) >= 0.14f && (r.centerX - 0.50f) * (targetX - 0.50f) < 0f) {
                        pen += 1.8f
                    }

                    val horizDist = abs(r.centerX - targetX)
                    val vertDist = abs(r.centerY - targetY)
                    val euclid = hypot(horizDist.toDouble(), vertDist.toDouble()).toFloat()
                    pen += horizDist * 6.0f + vertDist * 4.2f
                    if (euclid > 0.60f) {
                        pen += (euclid - 0.60f) * 14f
                    }

                    val thermalDensity = scoutHeatmap.sampleRectDensity(r)
                    pen += thermalDensity * 120f
                    // Gentle continuous open-space preference (never binary edge-yank on minor head nods)
                    pen += scoutHeatmap.distanceToLowestDensityEdge(r) * 3.0f

                    if (sameShotPrev != null &&
                        hypot((cand.x - sameShotPrev.x).toDouble(), (cand.y - sameShotPrev.y).toDouble()) < 0.008
                    ) {
                        pen -= 18f
                    }
                    return pen
                }

                val bestScoutCenter = scoutCandidates.minByOrNull { scoreScoutCandidate(it) } ?: scoutCandidates.first()
                val bestScoutScore = scoreScoutCandidate(bestScoutCenter)
                val committedCenter = commitBodyPosition(
                    eventId = event.id,
                    sceneKey = effectiveSceneKey,
                    candidateCenter = bestScoutCenter,
                    candidateScore = bestScoutScore,
                    scoreCommitted = { prevPt -> scoreScoutCandidate(prevPt) },
                    isCriticallyInvalid = { prevPt ->
                        val r = centeredRect(prevPt.x, prevPt.y, scoutEstWidth, scoutEstHeight)
                        r.intersectionArea(synthFaceBox) > 0.002f ||
                            bystanderAvoidBoxes.any { r.intersectionArea(it) > 0.002f } ||
                            occupiedBalloonRects.any { r.intersectionArea(it) > 0.002f }
                    },
                    currentMs = currentMs,
                    styleSettings = styleSettings,
                    forceResponsiveTracking = false,
                )
                val finalRect = centeredRect(committedCenter.x, committedCenter.y, scoutEstWidth, scoutEstHeight)
                val rawMouthY = targetY.coerceIn(0.14f, 0.78f)
                val rawTailCandidate = NormalizedPoint(targetX, rawMouthY)
                val withinTailReach = HierarchicalContextEngine.isDistanceWithinRestrainedTailReach(
                    bubbleCenter = NormalizedPoint(finalRect.centerX, finalRect.centerY),
                    tailTarget = rawTailCandidate,
                    hasVerifiedFace = false,
                )
                val resolvedTailRaw = applyTailVisibilityHysteresis(
                    eventId = event.id,
                    sceneKey = effectiveSceneKey,
                    rawTailTarget = if (isUnknown || !withinTailReach) null else rawTailCandidate,
                    confirmedEmptyFrame = liveFrameObservedEmpty,
                )
                val tailPt = if (resolvedTailRaw == null) {
                    null
                } else {
                    applyTailTargetSmoothing(
                        eventId = event.id,
                        shotId = effectiveSceneKey,
                        target = resolvedTailRaw,
                        reducedMotion = styleSettings.reducedMotion,
                        hasLiveFaceTracking = liveFaces.isNotEmpty(),
                    )
                }
                occupiedBalloonRects.add(finalRect)
                val emphasis = resolveEmphasis(event)
                results.add(
                    PlacedBalloon(
                        event = event,
                        speaker = speaker,
                        rect = finalRect,
                        tailTarget = tailPt,
                        tailDirection = computeTailDirection(finalRect, tailPt),
                        placementMode = PlacementMode.AUTO,
                        isOffScreenEdge = false,
                        edgeSide = null,
                        isAmbiguousFallback = false,
                        emphasisScore = emphasis.first,
                        isShouted = emphasis.second,
                        sceneLuminance = shotLuminance,
                        speakerLineOrdinal = lineOrdinal,
                        isUnknownSpeaker = isUnknown,
                        lowestDensityEdge = scoutHeatmap.lowestDensityEdge,
                        heatmapDensity = scoutHeatmap.sampleRectDensity(finalRect),
                        identityConfidence = if (isUnknown) 0.35f else event.speakerConfidence,
                        locationConfidence = if (tailPt != null) event.faceConfidence.coerceAtLeast(0.55f) else 0.25f,
                    )
                )
                continue
            }

            if (event.confidenceTier == ConfidenceTier.LOW || trackSample == null) {
                val neutralRect = placeNeutralFallbackRect(
                    w = estWidth,
                    h = estHeight,
                    occupied = occupiedBalloonRects + allSceneObstacles,
                    faceExclusions = faceExclusionZones,
                    lipCorridorZones = lipCorridorZones,
                    speakerAccentIndex = speaker?.accentIndex,
                    hasMultipleSpeakers = project.speakers.size >= 2,
                )
                occupiedBalloonRects.add(neutralRect)
                val emphasis = resolveEmphasis(event)
                results.add(
                    PlacedBalloon(
                        event = event,
                        speaker = speaker,
                        rect = neutralRect,
                        tailTarget = null,
                        tailDirection = TailDirection.NONE,
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        isOffScreenEdge = false,
                        edgeSide = null,
                        isAmbiguousFallback = event.confidenceTier == ConfidenceTier.LOW || isUnknown,
                        emphasisScore = emphasis.first,
                        isShouted = emphasis.second,
                        sceneLuminance = shotLuminance,
                        speakerLineOrdinal = lineOrdinal,
                        isUnknownSpeaker = isUnknown,
                        identityConfidence = if (isUnknown) 0.3f else event.speakerConfidence,
                        locationConfidence = 0f,
                    )
                )
                continue
            }

            // 4. Spatial candidate scoring around the speaker's face (protecting face + upper-body silhouette + shot focal props + dead space)
            val allObstacles = allSceneObstacles + occupiedBalloonRects + event.avoidBoxes
            val matchedTrackKey = matchedPair.first
            val bystanderFaces = faceSamples
                .filter { it.first != (matchedTrackKey ?: event.faceTrackId) }
                .map { it.second.boundingBox.expand(0.015f, 0.015f) } + event.avoidBoxes
            val mouthRefPoint = computeMouthPoint(matchedSample)
            val preferredDeadCenter = deadSpaceAnchorCenter(event.preferredDeadSpaceZone, estWidth * 0.5f, estHeight * 0.5f)

            // Continuous dead-space heatmap evaluation using live ML Kit faces + occupied bubbles
            val liveHeatmap = if (occupiedBalloonRects.isEmpty() && event.avoidBoxes.isEmpty()) {
                baseHeatmap
            } else {
                DeadSpaceHeatmapAnalyzer.computeHeatmap(
                    faceZones = faceExclusionZones,
                    lipZones = lipCorridorZones,
                    bodyZones = bodyExclusionZones,
                    obstacleZones = allSceneObstacles + event.avoidBoxes,
                    occupiedBubbles = occupiedBalloonRects,
                    speakerMoved = mlKitMoved,
                    maxSpeakerDelta = mlKitDelta,
                )
            }
            val rawCandidates = buildList {
                if (preferredDeadCenter != null) add(preferredDeadCenter)
                if (liveHeatmap.speakerMoved) {
                    addAll(
                        DeadSpaceHeatmapAnalyzer.buildPriorityEdgeCandidates(
                            heatmap = liveHeatmap,
                            halfW = estWidth * 0.5f,
                            halfH = estHeight * 0.5f,
                        )
                    )
                }
                addAll(
                    generateCandidateCenters(
                        face = trackSample,
                        headYawDeg = matchedSample.headYawDeg,
                        isMultiSpeakerShot = faceBoxes.size >= 2,
                        w = estWidth,
                        h = estHeight,
                    )
                )
                if (!liveHeatmap.speakerMoved) {
                    addAll(
                        DeadSpaceHeatmapAnalyzer.buildPriorityEdgeCandidates(
                            heatmap = liveHeatmap,
                            halfW = estWidth * 0.5f,
                            halfH = estHeight * 0.5f,
                        )
                    )
                }
            }
            // Always keep the prior committed anchor inside the same shot in the candidate pool so
            // minor face jitter does not cause the bubble to abandon a still-clear position.
            val sameShotPrevious = committedBodies[event.id]
                ?.takeIf { it.sceneKey == effectiveSceneKey }
                ?.center
                ?: smoothedPositions[event.id]?.takeIf { lastShotIds[event.id] == effectiveSceneKey }
            val candidates = if (sameShotPrevious != null) {
                listOf(sameShotPrevious) + rawCandidates
            } else {
                rawCandidates
            }

            fun scoreLiveCandidate(candidate: NormalizedPoint): Float {
                val candRect = centeredRect(candidate.x, candidate.y, estWidth, estHeight)
                val candHeat = liveHeatmap.sampleRectDensity(candRect)
                val stabilityBonus = if (sameShotPrevious != null &&
                    hypot((candidate.x - sameShotPrevious.x).toDouble(), (candidate.y - sameShotPrevious.y).toDouble()) < 0.008
                ) {
                    -22f
                } else {
                    0f
                }
                val deadSpaceBonus = if (preferredDeadCenter != null) {
                    val d = hypot((candRect.centerX - preferredDeadCenter.x).toDouble(), (candRect.centerY - preferredDeadCenter.y).toDouble()).toFloat()
                    if (d < 0.14f) -10f * (1f - d / 0.14f) else 0f
                } else {
                    0f
                }
                // Gentle continuous dead-space edge preference (eliminates violent edge-yanks on head nods)
                val edgeMovementBias = liveHeatmap.distanceToLowestDensityEdge(candRect) * 3.5f
                val candPt = NormalizedPoint(candRect.centerX, candRect.centerY)
                val tailCrossPenalty = bystanderFaces.sumOf { bf ->
                    if (segmentIntersectsRect(candPt, mouthRefPoint, bf)) 220.0 else 0.0
                }.toFloat()
                return scoreCandidate(
                    rect = candRect,
                    targetFace = trackSample,
                    headYawDeg = matchedSample.headYawDeg,
                    isMultiSpeakerShot = faceBoxes.size >= 2,
                    lipReadingSafeZone = styleSettings.lipReadingSafeZone,
                    faceExclusions = faceExclusionZones,
                    bodyExclusions = bodyExclusionZones,
                    lipCorridorZones = lipCorridorZones,
                    otherObstacles = allObstacles,
                ) + candHeat * 105f + stabilityBonus + deadSpaceBonus + edgeMovementBias + tailCrossPenalty
            }

            val bestCenter = candidates.minByOrNull { scoreLiveCandidate(it) }
                ?: NormalizedPoint(trackSample.centerX, (trackSample.top - estHeight * 0.7f).coerceAtLeast(0.11f))
            val bestScore = scoreLiveCandidate(bestCenter)

            // 5. Authoritative hysteresis, gentle-drift tracking, A-B-A bounce blocker & temporal smoothing
            val committedCenter = commitBodyPosition(
                eventId = event.id,
                sceneKey = effectiveSceneKey,
                candidateCenter = bestCenter,
                candidateScore = bestScore,
                scoreCommitted = { prevPt -> scoreLiveCandidate(prevPt) },
                isCriticallyInvalid = { prevPt ->
                    val r = centeredRect(prevPt.x, prevPt.y, estWidth, estHeight)
                    faceExclusionZones.any { r.intersectionArea(it) > 0.002f } ||
                        lipCorridorZones.any { r.intersectionArea(it) > 0.002f } ||
                        occupiedBalloonRects.any { r.intersectionArea(it) > 0.002f }
                },
                currentMs = currentMs,
                styleSettings = styleSettings,
                forceResponsiveTracking = false,
            )

            val finalRect = centeredRect(committedCenter.x, committedCenter.y, estWidth, estHeight)
            val rawMouthPt = computeMouthPoint(matchedSample)
            val resolvedMouthPt = applyTailVisibilityHysteresis(
                eventId = event.id,
                sceneKey = effectiveSceneKey,
                rawTailTarget = rawMouthPt,
                confirmedEmptyFrame = liveFrameObservedEmpty,
            )
            val mouthTarget = applyTailTargetSmoothing(
                eventId = event.id,
                shotId = effectiveSceneKey,
                target = resolvedMouthPt,
                reducedMotion = styleSettings.reducedMotion,
                hasLiveFaceTracking = liveFaces.isNotEmpty(),
            )
            occupiedBalloonRects.add(finalRect)

            val emphasis = resolveEmphasis(event)
            results.add(
                PlacedBalloon(
                    event = event,
                    speaker = speaker,
                    rect = finalRect,
                    tailTarget = mouthTarget,
                    tailDirection = computeTailDirection(finalRect, mouthTarget),
                    placementMode = PlacementMode.AUTO,
                    isOffScreenEdge = false,
                    edgeSide = null,
                    isAmbiguousFallback = false,
                    emphasisScore = emphasis.first,
                    isShouted = emphasis.second,
                    sceneLuminance = shotLuminance,
                    speakerLineOrdinal = lineOrdinal,
                    isUnknownSpeaker = isBubbleSpeakerUnknown(event, speaker),
                    lowestDensityEdge = liveHeatmap.lowestDensityEdge,
                    heatmapDensity = liveHeatmap.sampleRectDensity(finalRect),
                    identityConfidence = if (isUnknown) 0.35f else event.speakerConfidence,
                    locationConfidence = maxOf(event.faceConfidence, 0.85f),
                )
            )
        }

        return results.sortedBy { it.event.startTimeMs }
    }

    private fun deriveSpeakerSilhouetteZone(face: NormalizedRect): NormalizedRect {
        return NormalizedRect(
            left = (face.left - face.width * 0.42f).coerceIn(0f, 1f),
            top = (face.bottom - 0.01f).coerceIn(0f, 1f),
            right = (face.right + face.width * 0.42f).coerceIn(0f, 1f),
            bottom = (face.bottom + face.height * 1.45f).coerceAtMost(1f),
        )
    }

    private fun deriveLipReadingCorridorZone(face: NormalizedRect): NormalizedRect {
        return NormalizedRect(
            left = (face.left - 0.055f).coerceIn(0f, 1f),
            top = (face.top + face.height * 0.45f).coerceIn(0f, 1f),
            right = (face.right + 0.055f).coerceIn(0f, 1f),
            bottom = (face.bottom + face.height * 0.65f).coerceAtMost(1f),
        )
    }

    private fun generateCandidateCenters(
        face: NormalizedRect,
        headYawDeg: Float,
        isMultiSpeakerShot: Boolean,
        w: Float,
        h: Float,
    ): List<NormalizedPoint> {
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val gapX = 0.055f
        val gapY = 0.045f

        val clearLeftX = face.left - halfW - gapX
        val clearRightX = face.right + halfW + gapX
        val clearTopY = (face.top - halfH - gapY).coerceAtLeast(0.025f + halfH)

        val facingRight = headYawDeg > 8f
        val facingLeft = headYawDeg < -8f

        val list = mutableListOf(
            // Cleanly above head on the speaker's side (classic manga top-arc reading flow)
            NormalizedPoint(face.centerX, clearTopY),
            NormalizedPoint(face.centerX - 0.03f, clearTopY),
            NormalizedPoint(face.centerX + 0.03f, clearTopY),
            NormalizedPoint(face.centerX - 0.07f, clearTopY),
            NormalizedPoint(face.centerX + 0.07f, clearTopY),
            // Upper-outer manga panel corners (quietest negative space in 2-shots)
            NormalizedPoint(0.05f + halfW, 0.03f + halfH),
            NormalizedPoint(0.95f - halfW, 0.03f + halfH),
            // Cleanly beside head in open side air (ideal for single close-ups)
            NormalizedPoint(clearRightX, (face.top + halfH * 0.35f).coerceAtLeast(0.07f + halfH)),
            NormalizedPoint(clearRightX + 0.10f, (face.top + halfH * 0.35f).coerceAtLeast(0.07f + halfH)),
            NormalizedPoint(clearLeftX, (face.top + halfH * 0.35f).coerceAtLeast(0.07f + halfH)),
            NormalizedPoint(clearLeftX - 0.10f, (face.top + halfH * 0.35f).coerceAtLeast(0.07f + halfH)),
            // Eye/jaw-level open negative space beside speaker
            NormalizedPoint(clearRightX + 0.02f, face.centerY - 0.04f),
            NormalizedPoint(clearRightX + 0.03f, face.centerY),
            NormalizedPoint(clearLeftX - 0.02f, face.centerY - 0.04f),
            NormalizedPoint(clearLeftX - 0.03f, face.centerY),
        )

        // In single-speaker close-ups, bias slightly toward the facing side air;
        // in 2-shots, prefer staying above the speaker's own outer half rather than crossing the center focal area.
        if (!isMultiSpeakerShot) {
            if (facingLeft) {
                list.add(0, NormalizedPoint(clearLeftX, (face.top - halfH * 0.2f).coerceAtLeast(0.06f + halfH)))
            } else if (facingRight) {
                list.add(0, NormalizedPoint(clearRightX, (face.top - halfH * 0.2f).coerceAtLeast(0.06f + halfH)))
            }
        }
        // Screen-wide 2D clear-space grid so bubbles freely roam away from crowded faces/props
        for (gx in listOf(0.14f, 0.32f, 0.50f, 0.68f, 0.86f)) {
            for (gy in listOf(0.11f, 0.28f, 0.50f, 0.78f)) {
                list.add(
                    NormalizedPoint(
                        x = gx.coerceIn(0.05f + halfW, 0.95f - halfW),
                        y = gy.coerceIn(0.03f + halfH, 0.90f - halfH),
                    )
                )
            }
        }
        return list
    }

    private fun scoreCandidate(
        rect: NormalizedRect,
        targetFace: NormalizedRect,
        headYawDeg: Float,
        isMultiSpeakerShot: Boolean,
        lipReadingSafeZone: Boolean,
        faceExclusions: List<NormalizedRect>,
        bodyExclusions: List<NormalizedRect>,
        lipCorridorZones: List<NormalizedRect>,
        otherObstacles: List<NormalizedRect>,
    ): Float {
        var penalty = 0f

        // Strict protection: never cover any speaker's head/eyes/face
        for (faceZone in faceExclusions) {
            val overlap = rect.intersectionArea(faceZone)
            if (overlap > 0f) penalty += overlap * 2400f + 120f
        }

        // Lip-reading Safe Zone Lock: strictly forbid covering cheekbones, lips, jawline, or neck corridor
        if (lipReadingSafeZone) {
            for (lipZone in lipCorridorZones) {
                val overlap = rect.intersectionArea(lipZone)
                if (overlap > 0f) penalty += overlap * 3200f + 160f
            }
        }

        // Strong protection: avoid covering speakers' shoulders/torso silhouette
        for (bodyZone in bodyExclusions) {
            val overlap = rect.intersectionArea(bodyZone)
            if (overlap > 0f) penalty += overlap * 900f + 42f
        }

        // Avoid overlapping focal props, scene text, or other balloons
        for (obstacle in otherObstacles) {
            val overlap = rect.intersectionArea(obstacle)
            if (overlap > 0f) penalty += overlap * 680f + 24f
        }

        // Safe margins [0.045..0.955] x [0.025..0.91]
        if (rect.left < 0.045f) penalty += (0.045f - rect.left) * 90f
        if (rect.right > 0.955f) penalty += (rect.right - 0.955f) * 90f
        if (rect.top < 0.025f) penalty += (0.025f - rect.top) * 90f
        if (rect.bottom > 0.91f) penalty += (rect.bottom - 0.91f) * 90f

        // Gentle distance tie-breaker so zero face/lip/body overlap always wins over staying close
        val dist = hypot(
            (rect.centerX - targetFace.centerX).toDouble(),
            (rect.centerY - targetFace.centerY).toDouble(),
        ).toFloat()
        penalty += dist * 3.6f

        // Subtle directional flow: in single shots reward facing direction;
        // in 2-shots reward staying cleanly above the speaker's own horizontal column.
        if (isMultiSpeakerShot) {
            penalty += abs(rect.centerX - targetFace.centerX) * 2.2f
        } else {
            if (headYawDeg > 10f && rect.centerX > targetFace.centerX) penalty -= 0.45f
            if (headYawDeg < -10f && rect.centerX < targetFace.centerX) penalty -= 0.45f
        }

        // Strongly prefer above-head / beside-head over chin/chest below face (locked down when Lip-Reading Safe Zone is active)
        val inBelowChinColumn = rect.centerY > targetFace.bottom - 0.02f &&
            rect.right > targetFace.left - 0.06f &&
            rect.left < targetFace.right + 0.06f
        if (lipReadingSafeZone && inBelowChinColumn) {
            penalty += 120f
        } else if (rect.centerY > targetFace.bottom - 0.02f) {
            penalty += if (lipReadingSafeZone) 24.0f else 5.0f
        }

        return penalty
    }

    /**
     * Authoritative 2.0 body-position stability gate:
     * - Snaps cleanly across real scene cuts / chapter changes without sliding across shots.
     * - Within the same shot, preserves committed position unless a critical invalidation
     *   (face/lip/sibling overlap) occurs OR the candidate beats the committed position by
     *   at least [RELOCATION_SCORE_MARGIN], exceeds the dead-zone, passes the relocation
     *   cooldown, and is not an A-B-A bounce within [ABA_GUARD_WINDOW_MS].
     */
    private fun commitBodyPosition(
        eventId: String,
        sceneKey: String,
        candidateCenter: NormalizedPoint,
        candidateScore: Float,
        scoreCommitted: (NormalizedPoint) -> Float,
        isCriticallyInvalid: (NormalizedPoint) -> Boolean,
        currentMs: Long,
        styleSettings: BalloonStyleSettings,
        forceResponsiveTracking: Boolean,
    ): NormalizedPoint {
        val existing = committedBodies[eventId]
        if (existing == null || existing.sceneKey != sceneKey) {
            val prevPoint = existing?.center
            if (prevPoint != null) {
                val dist = hypot(
                    (candidateCenter.x - prevPoint.x).toDouble(),
                    (candidateCenter.y - prevPoint.y).toDouble(),
                ).toFloat()
                if (dist > 0.01f) {
                    stabilityDiagnostics.recordRelocation(
                        eventId = eventId,
                        displacementNormalized = dist,
                        reason = "scene_cut_snap",
                        playbackMs = currentMs,
                    )
                }
            }
            committedBodies[eventId] = CommittedBodyState(
                sceneKey = sceneKey,
                center = candidateCenter,
                previousCenter = null,
                lastRelocationMs = currentMs,
                lastScore = candidateScore,
                missingFaceStreak = 0,
                lastTailTarget = null,
            )
            smoothedPositions[eventId] = candidateCenter
            lastShotIds[eventId] = sceneKey
            return candidateCenter
        }

        val displacement = hypot(
            (candidateCenter.x - existing.center.x).toDouble(),
            (candidateCenter.y - existing.center.y).toDouble(),
        ).toFloat()

        val criticalInvalid = isCriticallyInvalid(existing.center)
        val sens = styleSettings.movementSensitivity.coerceIn(0f, 1f)
        val deadzone = when {
            styleSettings.reducedMotion -> 0.055f
            forceResponsiveTracking -> (0.022f - sens * 0.010f).coerceAtLeast(0.012f)
            else -> (0.048f - sens * 0.022f).coerceIn(0.024f, 0.050f)
        }

        // 1. Dead-zone: ignore minor sub-threshold drift when current position is valid
        if (!criticalInvalid && displacement < deadzone) {
            return existing.center
        }

        val elapsedSinceRelocation = abs(currentMs - existing.lastRelocationMs)
        val cooldownMs = if (styleSettings.reducedMotion) 1_000L else 650L
        val requiredMargin = if (styleSettings.reducedMotion) 32f else 24f

        // 2. Gentle-drift vs major corner-relocation hysteresis:
        //    - Small-to-moderate continuous speaker motion (< 0.16f) glides gently alongside the speaker
        //      once beyond the dead-zone, while still honoring the A-B-A bounce guard.
        //    - Large cross-frame relocations (>= 0.16f) require clear score improvement AND cooldown.
        val isGentleLocalFollow = !styleSettings.reducedMotion && displacement in deadzone..0.16f
        if (!criticalInvalid) {
            val committedScore = scoreCommitted(existing.center)
            val improvement = committedScore - candidateScore
            if (!isGentleLocalFollow) {
                if (improvement < requiredMargin && !forceResponsiveTracking) {
                    return existing.center
                }
                if (elapsedSinceRelocation < cooldownMs && !forceResponsiveTracking) {
                    return existing.center
                }
            } else if (improvement < 3.5f) {
                return existing.center
            }
            // 4. A-B-A bounce blocker: never jump back to the previous anchor A inside 1.4s
            val prevAnchor = existing.previousCenter
            if (prevAnchor != null && elapsedSinceRelocation < ABA_GUARD_WINDOW_MS) {
                val distToPrevAnchor = hypot(
                    (candidateCenter.x - prevAnchor.x).toDouble(),
                    (candidateCenter.y - prevAnchor.y).toDouble(),
                ).toFloat()
                if (distToPrevAnchor < 0.065f) {
                    stabilityDiagnostics.recordOscillationPrevented(
                        eventId = eventId,
                        playbackMs = currentMs,
                        reason = "aba_bounce_blocked",
                    )
                    return existing.center
                }
            }
        }

        val smoothedRaw = applyTemporalSmoothing(
            eventId = eventId,
            shotId = sceneKey,
            target = candidateCenter,
            reducedMotion = styleSettings.reducedMotion,
            forceResponsiveEdgeTracking = criticalInvalid,
            movementSensitivity = styleSettings.movementSensitivity,
        )
        // Velocity-clamped gentle drift: cap single-frame displacement unless critically dodging a face
        val rawStepDist = hypot(
            (smoothedRaw.x - existing.center.x).toDouble(),
            (smoothedRaw.y - existing.center.y).toDouble(),
        ).toFloat()
        val maxStep = if (criticalInvalid || styleSettings.reducedMotion) 0.18f else 0.036f
        val smoothed = if (rawStepDist > maxStep && rawStepDist > 1e-4f) {
            val ratio = maxStep / rawStepDist
            val clamped = NormalizedPoint(
                x = existing.center.x + (smoothedRaw.x - existing.center.x) * ratio,
                y = existing.center.y + (smoothedRaw.y - existing.center.y) * ratio,
            )
            smoothedPositions[eventId] = clamped
            clamped
        } else {
            smoothedRaw
        }
        val actualMove = hypot(
            (smoothed.x - existing.center.x).toDouble(),
            (smoothed.y - existing.center.y).toDouble(),
        ).toFloat()

        if (actualMove >= 0.008f) {
            val prevAnchor = existing.previousCenter
            if (prevAnchor != null && elapsedSinceRelocation < ABA_GUARD_WINDOW_MS) {
                val distBackToA = hypot(
                    (smoothed.x - prevAnchor.x).toDouble(),
                    (smoothed.y - prevAnchor.y).toDouble(),
                ).toFloat()
                if (distBackToA < 0.055f) {
                    stabilityDiagnostics.recordOscillationDetected(
                        eventId = eventId,
                        displacementNormalized = actualMove,
                        playbackMs = currentMs,
                    )
                }
            }
            stabilityDiagnostics.recordRelocation(
                eventId = eventId,
                displacementNormalized = actualMove,
                reason = if (criticalInvalid) "critical_face_or_overlap_avoidance" else if (isGentleLocalFollow) "gentle_face_drift" else "clear_margin_improvement",
                playbackMs = currentMs,
            )
            committedBodies[eventId] = existing.copy(
                center = smoothed,
                previousCenter = if (actualMove >= 0.03f) existing.center else existing.previousCenter,
                lastRelocationMs = if (actualMove >= 0.03f) currentMs else existing.lastRelocationMs,
                lastScore = candidateScore,
            )
        }
        return smoothed
    }

    /**
     * Suppresses single-frame ML Kit face dropouts so directional tails do not flicker on/off
     * during transient misses, while still dropping tails immediately on confirmed empty B-roll
     * or real scene cuts.
     */
    private fun applyTailVisibilityHysteresis(
        eventId: String,
        sceneKey: String,
        rawTailTarget: NormalizedPoint?,
        confirmedEmptyFrame: Boolean,
    ): NormalizedPoint? {
        val state = committedBodies[eventId]
        if (state == null || state.sceneKey != sceneKey) {
            return rawTailTarget
        }
        if (rawTailTarget != null) {
            if (state.lastTailTarget == null && state.missingFaceStreak > 0) {
                stabilityDiagnostics.recordTailToggle()
            }
            committedBodies[eventId] = state.copy(
                missingFaceStreak = 0,
                lastTailTarget = rawTailTarget,
            )
            return rawTailTarget
        }
        if (confirmedEmptyFrame) {
            if (state.lastTailTarget != null) {
                stabilityDiagnostics.recordTailToggle()
            }
            committedBodies[eventId] = state.copy(
                missingFaceStreak = LiveStageFaceTracker.CONFIRMED_EMPTY_FRAME_STREAK,
                lastTailTarget = null,
            )
            return null
        }
        val nextStreak = state.missingFaceStreak + 1
        if (state.lastTailTarget != null && nextStreak < LiveStageFaceTracker.CONFIRMED_EMPTY_FRAME_STREAK) {
            stabilityDiagnostics.recordTrackingDropout()
            committedBodies[eventId] = state.copy(missingFaceStreak = nextStreak)
            return state.lastTailTarget
        }
        if (state.lastTailTarget != null) {
            stabilityDiagnostics.recordTailToggle()
        }
        committedBodies[eventId] = state.copy(
            missingFaceStreak = nextStreak,
            lastTailTarget = null,
        )
        return null
    }

    private fun applyTemporalSmoothing(
        eventId: String,
        shotId: String,
        target: NormalizedPoint,
        reducedMotion: Boolean,
        forceResponsiveEdgeTracking: Boolean = false,
        movementSensitivity: Float = 0.5f,
    ): NormalizedPoint {
        val previousShot = lastShotIds[eventId]
        val previousPoint = smoothedPositions[eventId]
        lastShotIds[eventId] = shotId

        // Never animate/interpolate across a hard shot cut or chapter transition!
        if (reducedMotion || previousPoint == null || previousShot != shotId) {
            smoothedPositions[eventId] = target
            return target
        }

        val sens = movementSensitivity.coerceIn(0f, 1f)
        val deltaDist = hypot(
            (target.x - previousPoint.x).toDouble(),
            (target.y - previousPoint.y).toDouble(),
        ).toFloat()
        val baseDeadzone = 0.024f - (sens * 0.018f)
        val deadzone = if (forceResponsiveEdgeTracking) (baseDeadzone * 0.35f).coerceAtLeast(0.003f) else baseDeadzone
        if (deltaDist < deadzone) {
            return previousPoint
        }

        val baseAlpha = 0.14f + (sens * 0.24f)
        val alpha = if (forceResponsiveEdgeTracking) (baseAlpha + 0.24f).coerceAtMost(0.68f) else baseAlpha
        val smoothed = NormalizedPoint(
            x = previousPoint.x + (target.x - previousPoint.x) * alpha,
            y = previousPoint.y + (target.y - previousPoint.y) * alpha,
        )
        smoothedPositions[eventId] = smoothed
        return smoothed
    }

    companion object {
        private const val ABA_GUARD_WINDOW_MS = 1_400L
    }

    private fun applyTailTargetSmoothing(
        eventId: String,
        shotId: String,
        target: NormalizedPoint?,
        reducedMotion: Boolean,
        hasLiveFaceTracking: Boolean = false,
    ): NormalizedPoint? {
        if (target == null) return null
        val prev = smoothedTailTargets[eventId]
        val sameShot = lastShotIds[eventId] == shotId
        val upgradedToLive = hasLiveFaceTracking && lastTailWasLiveTracked[eventId] != true
        lastTailWasLiveTracked[eventId] = hasLiveFaceTracking
        if (reducedMotion || prev == null || !sameShot || upgradedToLive) {
            smoothedTailTargets[eventId] = target
            return target
        }
        val delta = hypot((target.x - prev.x).toDouble(), (target.y - prev.y).toDouble()).toFloat()
        // Zero-deadzone continuous tail tracking: only ignore sub-pixel rounding noise (< 0.15%)
        if (delta < 0.0015f) return prev
        val alpha = if (hasLiveFaceTracking) 0.62f else 0.42f
        val damped = NormalizedPoint(
            x = prev.x + (target.x - prev.x) * alpha,
            y = prev.y + (target.y - prev.y) * alpha,
        )
        smoothedTailTargets[eventId] = damped
        return damped
    }

    private fun resolveEmphasis(event: DialogueEvent): Pair<Float, Boolean> {
        val audio = event.audioFeatures ?: return 0f to false
        val score = audio.emphasisScore.coerceIn(0f, 1f)
        val shouted = audio.isShouted || score >= 0.66f
        return score to shouted
    }

    private fun placeSpecialDialogueRect(
        type: DialogueType,
        w: Float,
        h: Float,
        occupied: List<NormalizedRect>,
        faceExclusions: List<NormalizedRect>,
        lipCorridorZones: List<NormalizedRect>,
        stackIndex: Int,
    ): NormalizedRect {
        val base = when (type) {
            DialogueType.OFF_SCREEN_LEFT -> centeredRect(0.038f + w * 0.5f, 0.22f + stackIndex * 0.15f, w, h)
            DialogueType.OFF_SCREEN_RIGHT -> centeredRect(0.962f - w * 0.5f, 0.22f + stackIndex * 0.15f, w, h)
            DialogueType.OFF_SCREEN_BEHIND -> centeredRect(0.50f, 0.13f, w, h)
            DialogueType.NARRATION, DialogueType.VOICE_OVER -> centeredRect(0.50f, 0.12f, w.coerceAtLeast(0.42f), h)
            DialogueType.INTERNAL_MONOLOGUE -> centeredRect(0.50f, 0.18f, w, h)
            DialogueType.SOUND_DESCRIPTION -> centeredRect(0.50f, 0.87f, w.coerceAtMost(0.46f), h * 0.82f)
            DialogueType.SPOKEN -> centeredRect(0.50f, 0.84f, w, h)
        }
        return nudgeOutOfOccupied(base, occupied, faceExclusions, lipCorridorZones)
    }

    private fun placeSfxSafeRect(
        w: Float,
        h: Float,
        occupiedBubbles: List<NormalizedRect>,
        faceExclusions: List<NormalizedRect>,
        lipCorridorZones: List<NormalizedRect>,
        bodyExclusions: List<NormalizedRect>,
        focalObstacles: List<NormalizedRect>,
        previousCenter: NormalizedPoint?,
    ): NormalizedRect {
        val halfW = w * 0.5f
        val leftX = 0.055f + halfW
        val rightX = 0.945f - halfW
        val paddedBubbles = occupiedBubbles.map { it.expand(0.015f, 0.015f) }

        val candidates = buildList {
            if (previousCenter != null) {
                add(centeredRect(previousCenter.x, previousCenter.y, w, h))
            }
            // Default bottom cinema rest position + bottom-edge corners
            add(centeredRect(0.50f, 0.87f, w, h))
            add(centeredRect(leftX, 0.87f, w, h))
            add(centeredRect(rightX, 0.87f, w, h))
            add(centeredRect(0.26f, 0.87f, w, h))
            add(centeredRect(0.74f, 0.87f, w, h))
            add(centeredRect(0.50f, 0.74f, w, h))
            // Top perimeter & corners
            add(centeredRect(0.50f, 0.08f, w, h))
            add(centeredRect(leftX, 0.08f, w, h))
            add(centeredRect(rightX, 0.08f, w, h))
            add(centeredRect(0.28f, 0.08f, w, h))
            add(centeredRect(0.72f, 0.08f, w, h))
            add(centeredRect(0.50f, 0.19f, w, h))
            // Side vertical gutters
            add(centeredRect(leftX, 0.48f, w, h))
            add(centeredRect(rightX, 0.48f, w, h))
            add(centeredRect(leftX, 0.28f, w, h))
            add(centeredRect(rightX, 0.28f, w, h))
            add(centeredRect(leftX, 0.68f, w, h))
            add(centeredRect(rightX, 0.68f, w, h))
            // Exhaustive interior fallback grid for densely crowded multi-speaker shots
            for (gx in listOf(0.18f, 0.34f, 0.50f, 0.66f, 0.82f)) {
                for (gy in listOf(0.22f, 0.40f, 0.58f, 0.76f)) {
                    add(centeredRect(gx, gy, w, h))
                }
            }
        }

        return candidates.minByOrNull { cand ->
            val faceOverlap = faceExclusions.sumOf { it.intersectionArea(cand).toDouble() }.toFloat()
            val bubbleOverlap = paddedBubbles.sumOf { it.intersectionArea(cand).toDouble() }.toFloat()
            val lipOverlap = lipCorridorZones.sumOf { it.intersectionArea(cand).toDouble() }.toFloat()
            val bodyOverlap = bodyExclusions.sumOf { it.intersectionArea(cand).toDouble() }.toFloat()
            val focalOverlap = focalObstacles.sumOf { it.intersectionArea(cand).toDouble() }.toFloat()
            val isPrevious = previousCenter != null &&
                hypot((cand.centerX - previousCenter.x).toDouble(), (cand.centerY - previousCenter.y).toDouble()) < 0.005
            val clearOfCritical = faceOverlap == 0f && bubbleOverlap == 0f && lipOverlap == 0f
            val stabilityBonus = if (isPrevious && clearOfCritical) -4.0f else 0f
            val distFromBottom = hypot((cand.centerX - 0.50f).toDouble(), (cand.centerY - 0.87f).toDouble()).toFloat()

            (faceOverlap * 5500f + if (faceOverlap > 0f) 240f else 0f) +
                (bubbleOverlap * 5000f + if (bubbleOverlap > 0f) 220f else 0f) +
                (lipOverlap * 4200f + if (lipOverlap > 0f) 180f else 0f) +
                (bodyOverlap * 750f + if (bodyOverlap > 0f) 22f else 0f) +
                (focalOverlap * 550f + if (focalOverlap > 0f) 16f else 0f) +
                distFromBottom * 2.5f +
                stabilityBonus
        } ?: centeredRect(0.50f, 0.87f, w, h)
    }

    private fun placeFixedZoneTaillessRect(
        w: Float,
        h: Float,
        speaker: Speaker?,
        anchorX: Float?,
        occupied: List<NormalizedRect>,
        faceExclusions: List<NormalizedRect>,
        lipCorridorZones: List<NormalizedRect>,
        hasMultipleSpeakers: Boolean,
    ): NormalizedRect {
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val leftLaneX = (0.06f + halfW).coerceIn(0.20f, 0.34f)
        val rightLaneX = (0.94f - halfW).coerceIn(0.66f, 0.80f)
        val lowerY = (0.86f - halfH).coerceIn(0.70f, 0.83f)
        val upperY = (0.08f + halfH).coerceIn(0.11f, 0.22f)

        val preferredX = when {
            anchorX != null && anchorX < 0.44f -> leftLaneX
            anchorX != null && anchorX > 0.56f -> rightLaneX
            speaker?.preferredScreenSideX != null && speaker.preferredScreenSideX < 0.44f -> leftLaneX
            speaker?.preferredScreenSideX != null && speaker.preferredScreenSideX > 0.56f -> rightLaneX
            hasMultipleSpeakers && speaker != null -> if (speaker.accentIndex % 2 == 0) leftLaneX else rightLaneX
            else -> 0.50f
        }
        val fixedZoneCandidates = listOf(
            centeredRect(preferredX, lowerY, w, h),
            centeredRect(preferredX, upperY, w, h),
            centeredRect(0.50f, lowerY, w, h),
            centeredRect(0.50f, upperY, w, h),
            centeredRect(if (preferredX <= 0.50f) rightLaneX else leftLaneX, lowerY, w, h),
            centeredRect(if (preferredX <= 0.50f) rightLaneX else leftLaneX, upperY, w, h),
        )
        return fixedZoneCandidates.minByOrNull { cand ->
            val faceOv = faceExclusions.sumOf { it.intersectionArea(cand).toDouble() }.toFloat()
            val lipOv = lipCorridorZones.sumOf { it.intersectionArea(cand).toDouble() }.toFloat()
            val occOv = occupied.sumOf { it.intersectionArea(cand).toDouble() }.toFloat()
            val lanePref = abs(cand.centerX - preferredX) * 4f + if (cand.centerY < 0.50f) 1.5f else 0f
            (faceOv * 5000f + if (faceOv > 0f) 180f else 0f) +
                (lipOv * 4200f + if (lipOv > 0f) 150f else 0f) +
                (occOv * 3000f + if (occOv > 0f) 120f else 0f) +
                lanePref
        } ?: centeredRect(preferredX, lowerY, w, h)
    }

    private fun placeNeutralFallbackRect(
        w: Float,
        h: Float,
        occupied: List<NormalizedRect>,
        faceExclusions: List<NormalizedRect>,
        lipCorridorZones: List<NormalizedRect>,
        speakerAccentIndex: Int? = null,
        hasMultipleSpeakers: Boolean = false,
    ): NormalizedRect {
        val balloonW = w.coerceAtLeast(if (hasMultipleSpeakers) 0.30f else 0.42f)
        val cx = when {
            speakerAccentIndex == null || !hasMultipleSpeakers -> 0.50f
            speakerAccentIndex % 2 == 0 -> 0.34f
            else -> 0.66f
        }
        val cy = if (speakerAccentIndex != null && hasMultipleSpeakers) 0.77f else 0.82f
        val base = centeredRect(cx, cy, balloonW, h)
        return nudgeOutOfOccupied(base, occupied, faceExclusions, lipCorridorZones)
    }

    private fun nudgeOutOfOccupied(
        candidate: NormalizedRect,
        occupied: List<NormalizedRect>,
        faceExclusions: List<NormalizedRect> = emptyList(),
        lipCorridorZones: List<NormalizedRect> = emptyList(),
    ): NormalizedRect {
        if (occupied.none { it.intersects(candidate) } &&
            faceExclusions.none { it.intersects(candidate) } &&
            lipCorridorZones.none { it.intersects(candidate) }
        ) {
            return candidate
        }
        val stepY = candidate.height + 0.03f
        val inwardSign = if (candidate.centerX >= 0.5f) -1f else 1f
        val cw = candidate.width
        val ch = candidate.height
        val probes = listOf(
            candidate,
            centeredRect(candidate.centerX, candidate.centerY - stepY, cw, ch),
            centeredRect(candidate.centerX, candidate.centerY + stepY, cw, ch),
            centeredRect(candidate.centerX + inwardSign * 0.06f, candidate.centerY, cw, ch),
            centeredRect(candidate.centerX + inwardSign * 0.075f, candidate.centerY, cw, ch),
            centeredRect(candidate.centerX + inwardSign * 0.075f, candidate.centerY + ch * 0.65f, cw, ch),
            centeredRect(candidate.centerX + inwardSign * 0.075f, candidate.centerY - ch * 0.65f, cw, ch),
            centeredRect(candidate.centerX + inwardSign * 0.11f, candidate.centerY, cw, ch),
            centeredRect(candidate.centerX + inwardSign * 0.11f, candidate.centerY + ch * 0.75f, cw, ch),
            centeredRect(candidate.centerX + inwardSign * 0.16f, candidate.centerY, cw, ch),
            centeredRect(candidate.centerX - inwardSign * 0.06f, candidate.centerY, cw, ch),
            centeredRect(candidate.centerX, 0.06f + ch * 0.5f, cw, ch),
            centeredRect(candidate.centerX + inwardSign * 0.11f, 0.06f + ch * 0.5f, cw, ch),
            centeredRect(candidate.centerX, 0.88f - ch * 0.5f, cw, ch),
            centeredRect(0.06f + cw * 0.5f, 0.08f + ch * 0.5f, cw, ch),
            centeredRect(0.94f - cw * 0.5f, 0.08f + ch * 0.5f, cw, ch),
            centeredRect(0.06f + cw * 0.5f, 0.86f - ch * 0.5f, cw, ch),
            centeredRect(0.94f - cw * 0.5f, 0.86f - ch * 0.5f, cw, ch),
        )
        return probes.minByOrNull { probe ->
            val faceOverlap = faceExclusions.sumOf { it.intersectionArea(probe).toDouble() }.toFloat()
            val lipOverlap = lipCorridorZones.sumOf { it.intersectionArea(probe).toDouble() }.toFloat()
            val obstacleOverlap = occupied.sumOf { it.intersectionArea(probe).toDouble() }.toFloat()
            val shiftDist = hypot(
                (probe.centerX - candidate.centerX).toDouble(),
                (probe.centerY - candidate.centerY).toDouble(),
            ).toFloat()
            val facePen = faceOverlap * 4000f + if (faceOverlap > 0f) 60f else 0f
            val lipPen = lipOverlap * 2800f + if (lipOverlap > 0f) 40f else 0f
            val obsPen = obstacleOverlap * 1000f + if (obstacleOverlap > 0f) 12f else 0f
            facePen + lipPen + obsPen + shiftDist
        } ?: candidate
    }

    private fun centeredRect(cx: Float, cy: Float, w: Float, h: Float): NormalizedRect {
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val clampedCx = cx.coerceIn(0.045f + halfW, 0.955f - halfW)
        val clampedCy = cy.coerceIn(0.025f + halfH, 0.91f - halfH)
        return NormalizedRect(
            left = clampedCx - halfW,
            top = clampedCy - halfH,
            right = clampedCx + halfW,
            bottom = clampedCy + halfH,
        )
    }

    private fun computeMouthPoint(sample: com.example.data.model.FaceSample): NormalizedPoint {
        sample.mouthPoint?.let { return it }
        val box = sample.boundingBox
        val yawShift = (sample.headYawDeg / 90f).coerceIn(-0.28f, 0.28f) * box.width
        return NormalizedPoint(
            x = (box.centerX + yawShift).coerceIn(box.left + 0.01f, box.right - 0.01f),
            y = box.top + box.height * 0.70f,
        )
    }

    private fun matchLiveFaceForSpokenEvent(
        event: DialogueEvent,
        speaker: Speaker?,
        liveSamples: List<Pair<String, com.example.data.model.FaceSample>>,
    ): Pair<String, com.example.data.model.FaceSample>? {
        if (liveSamples.isEmpty()) return null
        val anchor = event.anchorPosition
        val refX = anchor?.x ?: speaker?.preferredScreenSideX ?: return null
        val refY = anchor?.y

        return liveSamples
            .mapNotNull { entry ->
                val sample = entry.second
                val faceX = sample.mouthPoint?.x ?: sample.boundingBox.centerX
                val faceY = sample.mouthPoint?.y ?: (sample.boundingBox.top + sample.boundingBox.height * 0.70f)
                val dx = abs(faceX - refX)
                val dy = if (refY != null) abs(faceY - refY) else 0f
                // Prioritize horizontal column matching so a chest-biased Scout anchorY never vetoes
                // an obvious ML Kit head/mouth directly above it, while still preventing cross-speaker tail steals.
                val maxDx = if (liveSamples.size == 1) 0.28f else 0.20f
                val maxDy = if (liveSamples.size == 1) 0.45f else 0.36f
                val score = dx * 1.45f + dy * 0.35f
                if (dx <= maxDx && dy <= maxDy) entry to score else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun estimateNormalizedWidth(text: String, fontScale: Float, maxLines: Int = 2): Float {
        val lines = ((text.length / 26) + 1).coerceIn(1, maxLines.coerceIn(1, 3))
        val effectiveLineChars = ((text.length + lines - 1) / lines).coerceIn(4, 34)
        val base = 0.11f + (effectiveLineChars / 34f) * 0.16f
        return (base * (0.85f + fontScale * 0.15f)).coerceIn(0.11f, 0.30f)
    }

    private fun estimateNormalizedHeight(text: String, fontScale: Float, maxLines: Int): Float {
        val lines = ((text.length / 26) + 1).coerceIn(1, maxLines.coerceIn(1, 4))
        return (0.08f + lines * 0.040f * fontScale).coerceIn(0.09f, 0.26f)
    }

    private fun computeTailDirection(rect: NormalizedRect, target: NormalizedPoint?): TailDirection {
        if (target == null) return TailDirection.NONE
        val dx = target.x - rect.centerX
        val dy = target.y - rect.centerY
        return when {
            abs(dy) >= abs(dx) * 0.7f && dy > 0f && dx < 0f -> TailDirection.DOWN_LEFT
            abs(dy) >= abs(dx) * 0.7f && dy > 0f -> TailDirection.DOWN_RIGHT
            abs(dy) >= abs(dx) * 0.7f && dy < 0f && dx < 0f -> TailDirection.UP_LEFT
            abs(dy) >= abs(dx) * 0.7f && dy < 0f -> TailDirection.UP_RIGHT
            dx < 0f -> TailDirection.LEFT
            else -> TailDirection.RIGHT
        }
    }

    private fun deadSpaceAnchorCenter(
        zone: DeadSpaceZone,
        halfW: Float,
        halfH: Float,
    ): NormalizedPoint? = when (zone) {
        DeadSpaceZone.AUTO -> null
        DeadSpaceZone.TOP_LEFT -> NormalizedPoint(0.055f + halfW, 0.055f + halfH)
        DeadSpaceZone.TOP_CENTER -> NormalizedPoint(0.50f, 0.055f + halfH)
        DeadSpaceZone.TOP_RIGHT -> NormalizedPoint(0.945f - halfW, 0.055f + halfH)
        DeadSpaceZone.MID_LEFT -> NormalizedPoint(0.055f + halfW, 0.44f)
        DeadSpaceZone.MID_RIGHT -> NormalizedPoint(0.945f - halfW, 0.44f)
        DeadSpaceZone.BOTTOM_LEFT -> NormalizedPoint(0.055f + halfW, 0.88f - halfH)
        DeadSpaceZone.BOTTOM_CENTER -> NormalizedPoint(0.50f, 0.88f - halfH)
        DeadSpaceZone.BOTTOM_RIGHT -> NormalizedPoint(0.945f - halfW, 0.88f - halfH)
    }
}

/**
 * Liang-Barsky exact line-segment / rectangle intersection test used to verify that a comic tail
 * stretching from a dead-space bubble to the active speaker's mouth never slices across a bystander's face.
 */
internal fun segmentIntersectsRect(
    p1: NormalizedPoint,
    p2: NormalizedPoint,
    rect: NormalizedRect,
): Boolean {
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y
    var t0 = 0f
    var t1 = 1f
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(
        p1.x - rect.left,
        rect.right - p1.x,
        p1.y - rect.top,
        rect.bottom - p1.y,
    )
    for (i in 0 until 4) {
        if (abs(p[i]) < 1e-6f) {
            if (q[i] < 0f) return false
        } else {
            val t = q[i] / p[i]
            if (p[i] < 0f) {
                if (t > t1) return false
                if (t > t0) t0 = t
            } else {
                if (t < t0) return false
                if (t < t1) t1 = t
            }
        }
    }
    return t0 <= t1
}
