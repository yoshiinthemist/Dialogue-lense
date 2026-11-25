package com.example.domain.analysis

import com.example.data.model.ChapterSource
import com.example.data.model.ConfidenceTier
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.Speaker
import com.example.data.model.VideoChapter
import com.example.data.model.isBubbleSpeakerUnknown
import kotlin.math.abs
import kotlin.math.hypot

enum class RenderPresentationMode {
    DIRECTIONAL_BUBBLE,
    SPEAKER_TAILLESS_BUBBLE,
    OFF_SCREEN_SPEAKER,
    NEUTRAL_CAPTION,
}

data class AttributionConfidenceAssessment(
    val identityConfidence: Float,
    val locationConfidence: Float,
    val presentationMode: RenderPresentationMode,
    val reason: String,
)

/**
 * Layer A — Video Memory.
 * Persistent video-wide speaker knowledge that survives camera cuts, chapter transitions,
 * temporary off-screen absences, and localized Heal Beacon repairs.
 */
data class VideoMemory(
    val videoId: String,
    val confirmedSpeakersById: Map<String, Speaker> = emptyMap(),
    val normalizedNameToSpeakerId: Map<String, String> = emptyMap(),
    val userLockedEventIds: Set<String> = emptySet(),
    val speakerPreferredSideX: Map<String, Float> = emptyMap(),
)

/**
 * Layer B — Chapter Memory.
 * Section-level context anchored to YouTube chapters or inferred editorial segment boundaries.
 * Transitions trigger contextual revalidation without discarding Layer A speaker memory.
 */
data class ChapterMemory(
    val activeChapter: VideoChapter? = null,
    val chapterEpoch: Int = 0,
    val activeParticipantIds: Set<String> = emptySet(),
    val recentSpeakerSequence: List<String> = emptyList(),
)

/**
 * Layer C — Scene Memory.
 * Short-lived visual environment for the current camera shot. Immediately invalidated whenever
 * a camera cut, chapter boundary, seek jump, or empty/B-roll transition occurs.
 */
data class SceneMemory(
    val sceneEpoch: Int = 0,
    val activeShotId: String = "",
    val lastUpdatedPlaybackMs: Long = 0L,
    val lastVisualCutMs: Long = -1L,
    val hasLiveVisionSignal: Boolean = false,
    val visibleFaces: List<LiveFaceObservation> = emptyList(),
    val liveFrameObservedEmpty: Boolean = false,
    val coordinatesValidated: Boolean = false,
    val isReactionOrCutaway: Boolean = false,
)

data class ContextualSnapshot(
    val videoMemory: VideoMemory,
    val chapterMemory: ChapterMemory,
    val sceneMemory: SceneMemory,
)

/**
 * Implements DialogueLens's 3-layer Hierarchical Contextual Awareness model:
 * - Layer A (Video Memory): persistent speaker identities, colors, aliases, and user locks.
 * - Layer B (Chapter Memory): chapter / editorial segment context and active participants.
 * - Layer C (Scene Memory): short-lived visual framing and verified face positions that reset
 *   immediately on visual camera cuts, chapter boundaries, and seeks so directional tails never
 *   point toward stale coordinates or empty space.
 */
class HierarchicalContextEngine {

    @Volatile
    private var videoMemory = VideoMemory(videoId = "")

    @Volatile
    private var chapterMemory = ChapterMemory()

    @Volatile
    private var sceneMemory = SceneMemory()

    fun currentSnapshot(): ContextualSnapshot = ContextualSnapshot(
        videoMemory = videoMemory,
        chapterMemory = chapterMemory,
        sceneMemory = sceneMemory,
    )

    fun resetForProject(project: DialogueLensProject) {
        val speakerMap = project.speakers.associateBy { it.id }
        val nameLookup = project.speakers.associate { normalizeSpeakerKey(it.displayName) to it.id }
        val lockedIds = project.dialogueEvents.filter { it.lockedByUser }.map { it.id }.toSet()
        val preferredSides = project.speakers
            .mapNotNull { sp -> sp.preferredScreenSideX?.let { sp.id to it } }
            .toMap()

        videoMemory = VideoMemory(
            videoId = project.id,
            confirmedSpeakersById = speakerMap,
            normalizedNameToSpeakerId = nameLookup,
            userLockedEventIds = lockedIds,
            speakerPreferredSideX = preferredSides,
        )
        chapterMemory = ChapterMemory()
        sceneMemory = SceneMemory(
            sceneEpoch = 1,
            activeShotId = project.shots.firstOrNull()?.id.orEmpty(),
            coordinatesValidated = false,
        )
    }

    fun syncProjectMemory(project: DialogueLensProject) {
        if (videoMemory.videoId != project.id) {
            resetForProject(project)
            return
        }
        val mergedSpeakers = videoMemory.confirmedSpeakersById.toMutableMap()
        project.speakers.forEach { sp -> mergedSpeakers[sp.id] = sp }
        val mergedNames = mergedSpeakers.values.associate { normalizeSpeakerKey(it.displayName) to it.id }
        val mergedLocks = videoMemory.userLockedEventIds +
            project.dialogueEvents.filter { it.lockedByUser }.map { it.id }
        val mergedSides = videoMemory.speakerPreferredSideX.toMutableMap()
        project.speakers.forEach { sp ->
            sp.preferredScreenSideX?.let { mergedSides[sp.id] = it }
        }
        videoMemory = videoMemory.copy(
            confirmedSpeakersById = mergedSpeakers,
            normalizedNameToSpeakerId = mergedNames,
            userLockedEventIds = mergedLocks,
            speakerPreferredSideX = mergedSides,
        )
    }

    /**
     * Evaluates playback position against chapters and shots. Detects seeks, shot switches,
     * and chapter boundaries, invalidating Layer C coordinates while retaining Layer A speakers.
     */
    fun onPlaybackPosition(
        project: DialogueLensProject,
        playbackMs: Long,
    ): ContextualSnapshot {
        syncProjectMemory(project)

        val prevScene = sceneMemory
        val jumpedSeek = prevScene.lastUpdatedPlaybackMs > 0L &&
            abs(playbackMs - prevScene.lastUpdatedPlaybackMs) > SEEK_INVALIDATION_THRESHOLD_MS

        val activeShotId = project.shots.firstOrNull { playbackMs in it.startMs..it.endMs }?.id
            ?: prevScene.activeShotId
        val shotChanged = activeShotId.isNotEmpty() &&
            prevScene.activeShotId.isNotEmpty() &&
            activeShotId != prevScene.activeShotId

        val activeChapter = project.chapters.firstOrNull { playbackMs in it.startMs..it.endMs }
        val chapterChanged = activeChapter?.id != chapterMemory.activeChapter?.id

        if (chapterChanged) {
            val chapterParticipants = if (activeChapter != null) {
                project.dialogueEvents
                    .filter { it.startTimeMs in activeChapter.startMs..activeChapter.endMs }
                    .mapNotNull { it.speakerId }
                    .toSet()
            } else {
                emptySet()
            }
            chapterMemory = chapterMemory.copy(
                activeChapter = activeChapter,
                chapterEpoch = chapterMemory.chapterEpoch + 1,
                activeParticipantIds = chapterParticipants,
                recentSpeakerSequence = emptyList(),
            )
        }

        if (jumpedSeek || shotChanged || chapterChanged) {
            sceneMemory = prevScene.copy(
                sceneEpoch = prevScene.sceneEpoch + 1,
                activeShotId = activeShotId,
                lastUpdatedPlaybackMs = playbackMs,
                lastVisualCutMs = playbackMs,
                visibleFaces = emptyList(),
                liveFrameObservedEmpty = false,
                coordinatesValidated = false,
                isReactionOrCutaway = false,
            )
        } else {
            sceneMemory = prevScene.copy(
                activeShotId = activeShotId,
                lastUpdatedPlaybackMs = playbackMs,
            )
        }

        return currentSnapshot()
    }

    /**
     * Processes a live vision snapshot (from PixelCopy + ML Kit).
     * When a camera cut is detected, immediately bumps [SceneMemory.sceneEpoch] and invalidates
     * stale coordinates until faces are freshly re-verified in the new shot.
     */
    fun onLiveFrameSnapshot(
        snapshot: LiveStageFrameSnapshot,
        playbackMs: Long,
    ): ContextualSnapshot {
        val prev = sceneMemory
        val nextEpoch = if (snapshot.visualCutDetected) prev.sceneEpoch + 1 else prev.sceneEpoch
        val isCutFrame = snapshot.visualCutDetected
        val hasFaces = snapshot.faces.isNotEmpty()
        val coordinatesNowValid = hasFaces && (!isCutFrame || snapshot.cutDistance < 0.35f)

        sceneMemory = prev.copy(
            sceneEpoch = nextEpoch,
            lastUpdatedPlaybackMs = playbackMs,
            lastVisualCutMs = if (isCutFrame) playbackMs else prev.lastVisualCutMs,
            hasLiveVisionSignal = snapshot.hasLiveVisionSignal,
            visibleFaces = snapshot.faces,
            liveFrameObservedEmpty = snapshot.hasLiveVisionSignal && !hasFaces,
            coordinatesValidated = coordinatesNowValid,
            isReactionOrCutaway = false,
        )
        return currentSnapshot()
    }

    /**
     * Explicitly invalidates Layer C positional memory (e.g., on user seek or layout resize)
     * without touching Layer A speaker identities.
     */
    fun invalidateSceneCoordinates(playbackMs: Long) {
        sceneMemory = sceneMemory.copy(
            sceneEpoch = sceneMemory.sceneEpoch + 1,
            lastUpdatedPlaybackMs = playbackMs,
            lastVisualCutMs = playbackMs,
            visibleFaces = emptyList(),
            liveFrameObservedEmpty = false,
            coordinatesValidated = false,
        )
    }

    companion object {
        private const val SEEK_INVALIDATION_THRESHOLD_MS = 2_200L

        fun normalizeSpeakerKey(name: String): String =
            name.trim().lowercase().replace(Regex("\\s+"), " ")

        /**
         * Separates speaker identity confidence from speaker location confidence so DialogueLens
         * never points a directional tail at an unverified or stale coordinate.
         */
        fun assessConfidence(
            event: DialogueEvent,
            speaker: Speaker?,
            hasVerifiedFaceMatch: Boolean,
            sceneMemory: SceneMemory,
            isOffScreen: Boolean,
        ): AttributionConfidenceAssessment {
            val identityUnknown = isBubbleSpeakerUnknown(event, speaker)
            val identityConf = when {
                event.lockedByUser && speaker != null -> 1.0f
                identityUnknown -> (event.speakerConfidence * 0.5f).coerceAtMost(0.45f)
                else -> event.speakerConfidence.coerceIn(0f, 1f)
            }

            if (isOffScreen ||
                event.dialogueType == DialogueType.OFF_SCREEN_LEFT ||
                event.dialogueType == DialogueType.OFF_SCREEN_RIGHT ||
                event.dialogueType == DialogueType.OFF_SCREEN_BEHIND
            ) {
                return AttributionConfidenceAssessment(
                    identityConfidence = identityConf,
                    locationConfidence = 0f,
                    presentationMode = if (identityUnknown) {
                        RenderPresentationMode.NEUTRAL_CAPTION
                    } else {
                        RenderPresentationMode.OFF_SCREEN_SPEAKER
                    },
                    reason = "Speaker is off-screen",
                )
            }

            if (identityUnknown) {
                return AttributionConfidenceAssessment(
                    identityConfidence = identityConf,
                    locationConfidence = 0f,
                    presentationMode = RenderPresentationMode.NEUTRAL_CAPTION,
                    reason = "Speaker identity uncertain",
                )
            }

            // Compute spatial/location confidence independently from identity confidence
            val locationConf = when {
                hasVerifiedFaceMatch -> maxOf(event.faceConfidence, 0.82f).coerceIn(0f, 1f)
                sceneMemory.liveFrameObservedEmpty -> 0.0f // Live vision confirms 0 faces in frame (B-roll / title card)
                sceneMemory.hasLiveVisionSignal && !sceneMemory.coordinatesValidated -> 0.15f
                event.anchorPosition != null &&
                    !sceneMemory.hasLiveVisionSignal &&
                    event.confidenceTier != ConfidenceTier.LOW -> {
                    // Only trust precomputed static anchor when live vision is unavailable (e.g. unit tests / offline render)
                    event.faceConfidence.coerceAtLeast(0.55f)
                }
                else -> 0.10f
            }

            val mode = if (locationConf >= 0.52f) {
                RenderPresentationMode.DIRECTIONAL_BUBBLE
            } else {
                RenderPresentationMode.SPEAKER_TAILLESS_BUBBLE
            }

            return AttributionConfidenceAssessment(
                identityConfidence = identityConf,
                locationConfidence = locationConf,
                presentationMode = mode,
                reason = if (mode == RenderPresentationMode.DIRECTIONAL_BUBBLE) {
                    "Verified speaker position"
                } else {
                    "Speaker known, on-screen position unverified"
                },
            )
        }

        /**
         * Fallback editorial chapter inference when explicit YouTube chapters are unavailable.
         * Uses sustained silence gaps (>= 9s) or major shot/topic transitions to create clean,
         * non-intrusive editorial segments so Layer B Chapter Memory and Chapter Heal always work.
         */
        fun inferEditorialChapters(
            events: List<DialogueEvent>,
            durationMs: Long,
            existingChapters: List<VideoChapter> = emptyList(),
        ): List<VideoChapter> {
            if (existingChapters.isNotEmpty()) return existingChapters
            val effectiveDuration = maxOf(durationMs, events.maxOfOrNull { it.endTimeMs } ?: 0L)
            if (effectiveDuration < 45_000L || events.size < 4) return emptyList()

            val sorted = events.sortedBy { it.startTimeMs }
            val splitPoints = mutableListOf(0L)
            val minSegmentMs = 40_000L
            val silenceGapThresholdMs = 9_000L

            for (i in 1 until sorted.size) {
                val prevEnd = sorted[i - 1].endTimeMs
                val currStart = sorted[i].startTimeMs
                val gap = currStart - prevEnd
                if (gap >= silenceGapThresholdMs && (currStart - splitPoints.last()) >= minSegmentMs) {
                    splitPoints.add(currStart)
                }
            }

            // If continuous talk video > 4 min with no long silence, create gentle 3-minute editorial sections
            if (splitPoints.size == 1 && effectiveDuration >= 240_000L) {
                val step = 180_000L
                var t = step
                while (t < effectiveDuration - 45_000L && splitPoints.size < 12) {
                    // Snap to nearest dialogue start within ±15s
                    val snap = sorted.minByOrNull { abs(it.startTimeMs - t) }
                        ?.takeIf { abs(it.startTimeMs - t) <= 15_000L }
                        ?.startTimeMs ?: t
                    if (snap > splitPoints.last() + minSegmentMs) {
                        splitPoints.add(snap)
                    }
                    t += step
                }
            }

            if (splitPoints.size < 2) return emptyList()

            return splitPoints.mapIndexed { idx, startMs ->
                val endMs = if (idx < splitPoints.lastIndex) splitPoints[idx + 1] else effectiveDuration
                VideoChapter(
                    id = "inferred_chapter_${idx + 1}",
                    title = "Part ${idx + 1}",
                    startMs = startMs,
                    endMs = endMs.coerceAtLeast(startMs + 1_000L),
                    source = ChapterSource.INFERRED_EDITORIAL,
                )
            }
        }

        fun isDistanceWithinRestrainedTailReach(
            bubbleCenter: NormalizedPoint,
            tailTarget: NormalizedPoint,
            hasVerifiedFace: Boolean,
        ): Boolean {
            val dist = hypot(
                (bubbleCenter.x - tailTarget.x).toDouble(),
                (bubbleCenter.y - tailTarget.y).toDouble(),
            ).toFloat()
            val maxReach = if (hasVerifiedFace) 0.72f else 0.56f
            return dist <= maxReach
        }
    }
}
