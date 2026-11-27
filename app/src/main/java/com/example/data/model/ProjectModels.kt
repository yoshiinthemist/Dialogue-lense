package com.example.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun expand(marginX: Float, marginY: Float): NormalizedRect = NormalizedRect(
        left = (left - marginX).coerceIn(0f, 1f),
        top = (top - marginY).coerceIn(0f, 1f),
        right = (right + marginX).coerceIn(0f, 1f),
        bottom = (bottom + marginY).coerceIn(0f, 1f),
    )

    fun intersects(other: NormalizedRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun intersectionArea(other: NormalizedRect): Float {
        val ix = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val iy = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        return ix * iy
    }
}

@JsonClass(generateAdapter = true)
data class NormalizedPoint(
    val x: Float,
    val y: Float,
)

enum class VideoSourceKind {
    DEMO_SCENARIO,
    LOCAL_MP4,
    YOUTUBE_COMPLIANT,
    WEB_STREAM,
}

enum class SubtitleSourceKind {
    BUILT_IN_DEMO,
    UPLOADED_FILE,
    EMBEDDED_TRACK,
    AI_DRAFT,
}

enum class TranslationMode(val label: String) {
    NATURAL("Natural"),
    LITERAL("Literal"),
    LEARNING("Learning"),
}

enum class CaptionDisplayMode(val label: String) {
    OFF("No captions"),
    CLASSIC("Classic captions"),
    BALLOONS("Bubble captions"),
    BUBBLES_WITH_TAILS("Bubbles with tails"),
    @Deprecated("Legacy mode migrated to BALLOONS")
    FIXED_ZONES("Bubble captions"),
    @Deprecated("Legacy mode migrated to BUBBLES_WITH_TAILS or BALLOONS")
    BOTH("Bubbles with tails");

    val shouldShowBubbles: Boolean
        get() = when (normalizedPrimaryMode()) {
            BALLOONS, BUBBLES_WITH_TAILS -> true
            OFF, CLASSIC -> false
            else -> false
        }

    val shouldShowClassic: Boolean
        get() = normalizedPrimaryMode() == CLASSIC

    val shouldShowTails: Boolean
        get() = normalizedPrimaryMode() == BUBBLES_WITH_TAILS

    fun normalizedPrimaryMode(previousShowTail: Boolean = false): CaptionDisplayMode = when (this) {
        OFF -> OFF
        CLASSIC -> CLASSIC
        BALLOONS -> BALLOONS
        BUBBLES_WITH_TAILS -> BUBBLES_WITH_TAILS
        FIXED_ZONES -> BALLOONS
        BOTH -> if (previousShowTail) BUBBLES_WITH_TAILS else BALLOONS
    }

    companion object {
        val primaryModes: List<CaptionDisplayMode> = listOf(OFF, CLASSIC, BALLOONS, BUBBLES_WITH_TAILS)

        fun migrateLegacy(rawMode: CaptionDisplayMode, previousShowTail: Boolean): CaptionDisplayMode =
            when (rawMode) {
                OFF -> OFF
                CLASSIC -> CLASSIC
                FIXED_ZONES -> BALLOONS
                BOTH, BALLOONS -> if (previousShowTail) BUBBLES_WITH_TAILS else BALLOONS
                BUBBLES_WITH_TAILS -> BUBBLES_WITH_TAILS
            }
    }
}

enum class ProcessingQualityMode(val label: String, val sampleIntervalMs: Long) {
    FAST("Fast", 1100L),
    BALANCED("Balanced", 650L),
    HIGH_ACCURACY("High Accuracy", 350L),
}

enum class QuotaSaverMode(val label: String, val subtitle: String) {
    PRO_ACCURACY("Best Model", "Gemini 3.8 Flash first; Lite & Free CC if credits dry"),
    QUOTA_SAVER("Lite First", "Flash-Lite + cache first; Free CC if credits dry"),
    ZERO_CLOUD("Free CC Only", "100% free CC, SRT & offline ML Kit"),
}

enum class DialogueType(val label: String) {
    SPOKEN("Spoken"),
    OFF_SCREEN_LEFT("Off-Screen Left"),
    OFF_SCREEN_RIGHT("Off-Screen Right"),
    OFF_SCREEN_BEHIND("Off-Screen"),
    VOICE_OVER("Voice-Over"),
    NARRATION("Narration"),
    INTERNAL_MONOLOGUE("Monologue"),
    SOUND_DESCRIPTION("Sound / SFX"),
}

enum class PlacementMode {
    AUTO,
    EDGE_DOCKED,
    NEUTRAL_FALLBACK,
    MANUAL_OVERRIDE,
}

enum class DeadSpaceZone {
    AUTO,
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    MID_LEFT,
    MID_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
}

enum class ConfidenceTier(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Uncertain"),
}

enum class BubbleVisualStyle(val label: String) {
    CINEMA_SUBTLE("Cinema"),
    MANGA_INK("Manga"),
    STREAMING_CLEAN("Minimal"),
    READING_COMFORT("Reading Comfort"),
}

enum class CaptionFontFamily(val label: String) {
    DEFAULT_SANS("System Sans"),
    SERIF("Classic Serif"),
    MONOSPACE("Monospace"),
    READABLE_ROUNDED("Readable Rounded"),
}

enum class SpeakerLabelMode(val label: String) {
    AUTO_FIRST_10("1st 10"),
    ALWAYS("Always"),
    OFF("Off"),
}

fun confidenceTierFor(score: Float): ConfidenceTier = when {
    score >= 0.75f -> ConfidenceTier.HIGH
    score >= 0.45f -> ConfidenceTier.MEDIUM
    else -> ConfidenceTier.LOW
}

@JsonClass(generateAdapter = true)
data class MediaTrackInfo(
    val index: Int,
    val mimeType: String,
    val language: String,
    val label: String,
)

@JsonClass(generateAdapter = true)
data class VideoSource(
    val uriString: String,
    val displayName: String,
    val kind: VideoSourceKind,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val audioTracks: List<MediaTrackInfo> = emptyList(),
    val embeddedSubtitleTracks: List<MediaTrackInfo> = emptyList(),
    val youtubeVideoId: String? = null,
    val channelName: String = "",
    val description: String = "",
)

@JsonClass(generateAdapter = true)
data class Shot(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val cutType: String = "HARD_CUT",
    val demoSceneKey: String? = null,
    val focalExclusionZones: List<NormalizedRect> = emptyList(),
    val sceneLuminance: Float = 0.25f,
)

@JsonClass(generateAdapter = true)
data class FaceSample(
    val timeMs: Long,
    val boundingBox: NormalizedRect,
    val mouthActivity: Float,
    val headYawDeg: Float = 0f,
    val mouthPoint: NormalizedPoint? = null,
)

@JsonClass(generateAdapter = true)
data class FaceTrack(
    val id: String,
    val shotId: String,
    val speakerId: String?,
    val trackingTag: Int = 0,
    val samples: List<FaceSample>,
) {
    fun sampleAt(timeMs: Long): FaceSample? {
        if (samples.isEmpty()) return null
        if (samples.size == 1) return samples.first()
        val exact = samples.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }
        val before = samples.lastOrNull { it.timeMs <= timeMs }
        val after = samples.firstOrNull { it.timeMs >= timeMs }
        if (before == null || after == null || before.timeMs == after.timeMs) {
            return exact
        }
        val t = ((timeMs - before.timeMs).toFloat() / (after.timeMs - before.timeMs)).coerceIn(0f, 1f)
        val interpolatedMouth = when {
            before.mouthPoint != null && after.mouthPoint != null -> NormalizedPoint(
                x = lerp(before.mouthPoint.x, after.mouthPoint.x, t),
                y = lerp(before.mouthPoint.y, after.mouthPoint.y, t),
            )
            else -> before.mouthPoint ?: after.mouthPoint
        }
        return FaceSample(
            timeMs = timeMs,
            boundingBox = NormalizedRect(
                left = lerp(before.boundingBox.left, after.boundingBox.left, t),
                top = lerp(before.boundingBox.top, after.boundingBox.top, t),
                right = lerp(before.boundingBox.right, after.boundingBox.right, t),
                bottom = lerp(before.boundingBox.bottom, after.boundingBox.bottom, t),
            ),
            mouthActivity = lerp(before.mouthActivity, after.mouthActivity, t),
            headYawDeg = lerp(before.headYawDeg, after.headYawDeg, t),
            mouthPoint = interpolatedMouth,
        )
    }

    fun averageCenter(): NormalizedPoint {
        if (samples.isEmpty()) return NormalizedPoint(0.5f, 0.5f)
        return NormalizedPoint(
            x = samples.map { it.boundingBox.centerX }.average().toFloat(),
            y = samples.map { it.boundingBox.centerY }.average().toFloat(),
        )
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

@JsonClass(generateAdapter = true)
data class AudioVoiceFeatures(
    val pitchHz: Float = 0f,
    val spectralBrightness: Float = 0.5f,
    val speechEnergy: Float = 0f,
    val voiceClusterId: String? = null,
    val peakAmplitude: Float = 0f,
    val emphasisScore: Float = 0f,
    val isShouted: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class Speaker(
    val id: String,
    val defaultLabel: String,
    val customName: String = "",
    val accentIndex: Int = 0,
    val colorRemembered: Boolean = false,
    val isLocked: Boolean = false,
    val learnedFaceTrackIds: List<String> = emptyList(),
    val preferredScreenSideX: Float? = null,
    val voicePitchHz: Float? = null,
    val voiceBrightness: Float? = null,
    val isRolePlaceholder: Boolean = false,
    val firstAppearedMs: Long? = null,
    val visualVoiceNotes: String = "",
    val previousNames: List<String> = emptyList(),
    val discoveryReason: String = "",
    val isIdentityConfirmed: Boolean = true,
    val nameRevealedAtMs: Long? = null,
    val isUserNamed: Boolean = false,
    val revealProvenance: String = "",
) {
    val displayName: String
        get() = customName.ifBlank { defaultLabel }

    fun visibleDisplayNameAt(atTimeMs: Long): String {
        if (customName.isBlank()) return defaultLabel
        if (isLocked || isUserNamed) return customName
        val revealAt = nameRevealedAtMs ?: return customName
        return if (atTimeMs >= revealAt) customName else defaultLabel
    }

    fun hasVisibleCharacterNameAt(atTimeMs: Long): Boolean {
        val resolved = visibleDisplayNameAt(atTimeMs).trim()
        if (resolved.length < 2) return false
        if (resolved.matches(Regex("^Speaker\\s+[A-Z0-9]+$", RegexOption.IGNORE_CASE))) return false
        if (resolved.matches(Regex("^(Unknown(\\s+Speaker)?|Unidentified)$", RegexOption.IGNORE_CASE))) return false
        return true
    }

    val hasDistinctCharacterName: Boolean
        get() {
            val trimmed = customName.trim()
            if (trimmed.length < 2) return false
            if (trimmed.matches(Regex("^Speaker\\s+[A-Z0-9]+$", RegexOption.IGNORE_CASE))) return false
            if (trimmed.matches(Regex("^(Unknown(\\s+Speaker)?|Unidentified)$", RegexOption.IGNORE_CASE))) return false
            return true
        }

    val isConfirmedCharacter: Boolean
        get() = isLocked || (isIdentityConfirmed && hasDistinctCharacterName)
}

@JsonClass(generateAdapter = true)
data class DialogueEvent(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sourceText: String,
    val translatedText: String,
    val literalText: String = "",
    val romanization: String = "",
    val speakerId: String?,
    val speakerConfidence: Float,
    val faceTrackId: String?,
    val faceConfidence: Float,
    val shotId: String,
    val dialogueType: DialogueType = DialogueType.SPOKEN,
    val anchorPosition: NormalizedPoint? = null,
    val placementMode: PlacementMode = PlacementMode.AUTO,
    val lockedByUser: Boolean = false,
    val audioFeatures: AudioVoiceFeatures? = null,
    val contextCueReason: String = "",
    val attributionSummary: String = "",
    val culturalNote: String = "",
    val preferredDeadSpaceZone: DeadSpaceZone = DeadSpaceZone.AUTO,
    val avoidBoxes: List<NormalizedRect> = emptyList(),
    val stableCueKey: String = "",
    val needsTranslation: Boolean = false,
    val parentUtteranceKey: String = "",
    val sourceSpanStartMs: Long? = null,
    val sourceSpanEndMs: Long? = null,
    val sourceTextRevision: Int = 1,
    val translationSourceRevision: Int = 0,
) {
    val canonicalIdentityKey: String
        get() = stableCueKey.ifBlank { id }

    val confidenceTier: ConfidenceTier
        get() = confidenceTierFor(speakerConfidence)

    val charactersPerSecond: Float
        get() {
            val durSec = ((endTimeMs - startTimeMs).coerceAtLeast(300L)) / 1000f
            return translatedText.ifBlank { sourceText }.length / durSec
        }

    fun activeText(mode: TranslationMode): String = when (mode) {
        TranslationMode.NATURAL -> translatedText.ifBlank { sourceText }
        TranslationMode.LITERAL -> literalText.ifBlank { translatedText.ifBlank { sourceText } }
        TranslationMode.LEARNING -> translatedText.ifBlank { sourceText }
    }
}

@JsonClass(generateAdapter = true)
data class GlossaryEntry(
    val term: String,
    val translation: String,
    val note: String = "",
)

@JsonClass(generateAdapter = true)
data class UserCorrection(
    val id: String,
    val eventId: String,
    val previousSpeakerId: String?,
    val newSpeakerId: String?,
    val manualAnchor: NormalizedPoint? = null,
    val timestampMs: Long,
)

enum class ChapterSource {
    YOUTUBE_DESCRIPTION,
    YOUTUBE_METADATA,
    INFERRED_EDITORIAL,
}

@JsonClass(generateAdapter = true)
data class VideoChapter(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val source: ChapterSource = ChapterSource.YOUTUBE_DESCRIPTION,
)

enum class HealBeaconStatus {
    MARKED,
    HEALING,
    HEALED,
    NEEDS_ATTENTION,
    SUPERSEDED,
    CANCELLED,
    UNDONE,
}

enum class HealScope {
    ADAPTIVE,
    QUICK,
    SCENE,
    CHAPTER,
}

enum class HealProblemHint(val label: String) {
    NONE("Auto-diagnose"),
    MISSING_DIALOGUE("Missing dialogue"),
    WRONG_SPEAKER("Wrong speaker"),
    INCORRECT_TIMING("Incorrect timing"),
    UNSTABLE_BUBBLE("Unstable bubble"),
    WRONG_POSITIONING("Wrong positioning"),
    OTHER("Something else"),
}

@JsonClass(generateAdapter = true)
data class HealBeacon(
    val id: String,
    val timestampMs: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val status: HealBeaconStatus = HealBeaconStatus.MARKED,
    val scope: HealScope = HealScope.ADAPTIVE,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val statusNote: String = "",
    val attemptCount: Int = 0,
    val preHealSnapshotEvents: List<DialogueEvent> = emptyList(),
    val replacedEventIds: List<String> = emptyList(),
    val autoTriggered: Boolean = false,
    val problemHint: HealProblemHint = HealProblemHint.NONE,
    val repairRevision: Int = 0,
    val changeSummary: String = "",
    val patrolChapterId: String? = null,
    val beforeSampleText: String = "",
    val afterSampleText: String = "",
    val generationToken: Long = 0L,
    val coordinatorJobId: String = "",
)

@JsonClass(generateAdapter = true)
data class BalloonStyleSettings(
    val visualStyle: BubbleVisualStyle = BubbleVisualStyle.CINEMA_SUBTLE,
    val fontScale: Float = 1.0f,
    val balloonOpacity: Float = 0.86f,
    val highContrastMode: Boolean = false,
    val showTail: Boolean = true,
    val speakerSpecificColors: Boolean = true,
    val maxLines: Int = 2,
    val reducedMotion: Boolean = false,
    val stablePositioning: Boolean = false,
    val movementSensitivity: Float = 0.5f,
    val showSpeakerLabels: Boolean = true,
    val labelMode: SpeakerLabelMode = SpeakerLabelMode.AUTO_FIRST_10,
    val showConfidenceIndicators: Boolean = true,
    val tategakiVerticalJapanese: Boolean = false,
    val lipReadingSafeZone: Boolean = true,
    val offScreenEdgeGlow: Boolean = true,
    val ambientMoodBorder: Boolean = true,
    val fontFamily: CaptionFontFamily = CaptionFontFamily.DEFAULT_SANS,
    val customForegroundColorHex: Long? = null,
    val customBackgroundColorHex: Long? = null,
)

object CaptionAccessibilityContrast {
    const val MIN_TEXT_CONTRAST_RATIO: Float = 4.5f

    fun compositeOverBackdrop(
        surfaceArgb: Long,
        surfaceAlpha: Float,
        backdropArgb: Long = 0xFF101828L,
    ): Long {
        val a = surfaceAlpha.coerceIn(0f, 1f)
        val sr = ((surfaceArgb shr 16) and 0xFF).toFloat()
        val sg = ((surfaceArgb shr 8) and 0xFF).toFloat()
        val sb = (surfaceArgb and 0xFF).toFloat()
        val br = ((backdropArgb shr 16) and 0xFF).toFloat()
        val bg = ((backdropArgb shr 8) and 0xFF).toFloat()
        val bb = (backdropArgb and 0xFF).toFloat()
        val r = (sr * a + br * (1f - a)).toInt().coerceIn(0, 255)
        val g = (sg * a + bg * (1f - a)).toInt().coerceIn(0, 255)
        val b = (sb * a + bb * (1f - a)).toInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    fun relativeLuminance(argb: Long): Float {
        fun linearize(channel: Long): Double {
            val srgb = channel.toDouble() / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        val r = linearize((argb shr 16) and 0xFF)
        val g = linearize((argb shr 8) and 0xFF)
        val b = linearize(argb and 0xFF)
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
    }

    fun contrastRatio(fgArgb: Long, bgArgb: Long): Float {
        val l1 = relativeLuminance(fgArgb)
        val l2 = relativeLuminance(bgArgb)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return ((lighter + 0.05f) / (darker + 0.05f))
    }

    fun meetsMinimumTextContrast(
        fgArgb: Long,
        bgContainerArgb: Long,
        bgAlpha: Float,
        backdropArgb: Long = 0xFF808080L,
    ): Boolean {
        val composited = compositeOverBackdrop(bgContainerArgb, bgAlpha, backdropArgb)
        return contrastRatio(fgArgb, composited) >= MIN_TEXT_CONTRAST_RATIO
    }
}

@JsonClass(generateAdapter = true)
data class PersistedSchedulingState(
    val acknowledgedViewerPosMs: Long = 0L,
    val horizonMs: Long = 180_000L,
    val schedulingGeneration: Long = 0L,
    val isViewerPaused: Boolean = false,
    val lastIdleAnchorMs: Long = -1L,
    val lastIdleBoundaryEndMs: Long = -1L,
    val schedulerOutcome: String = "ACTIVE",
    val savedAtEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class DialogueLensProject(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translationMode: TranslationMode = TranslationMode.NATURAL,
    val subtitleSource: SubtitleSourceKind = SubtitleSourceKind.BUILT_IN_DEMO,
    val qualityMode: ProcessingQualityMode = ProcessingQualityMode.BALANCED,
    val videoSource: VideoSource,
    val shots: List<Shot>,
    val speakers: List<Speaker>,
    val faceTracks: List<FaceTrack>,
    val dialogueEvents: List<DialogueEvent>,
    val textExclusionZones: List<NormalizedRect> = emptyList(),
    val corrections: List<UserCorrection> = emptyList(),
    val styleSettings: BalloonStyleSettings = BalloonStyleSettings(),
    val glossary: List<GlossaryEntry> = emptyList(),
    val audioWaveformPeaks: List<Float> = emptyList(),
    val subtitleOffsetMs: Long = 0L,
    val groundingQueries: List<String> = emptyList(),
    val groundingSources: List<String> = emptyList(),
    val castDiscoverySummary: String = "",
    val chapters: List<VideoChapter> = emptyList(),
    val healBeacons: List<HealBeacon> = emptyList(),
    val lastPlaybackPositionMs: Long = 0L,
    val lastWatchedAtEpochMs: Long = 0L,
    val coveragePercent: Int = 0,
    val schedulingState: PersistedSchedulingState? = null,
)

/**
 * Canonical speaker color assignment order:
 * 1. Primary colors first (Red, Blue, Yellow)
 * 2. Secondary colors next (Green, Orange, Purple)
 * 3. Distinct unused colors (Cyan, Magenta, Lime, Teal)
 * White is strictly reserved for non-speaker-specific bubbles; Gray (dotted) is used for unknown speakers.
 */
object SpeakerColorOrder {
    val PALETTE_HEX_COLORS: List<Long> = listOf(
        0xFFEF4444L, // 0 — Primary Red
        0xFF3B82F6L, // 1 — Primary Blue
        0xFFFACC15L, // 2 — Primary Yellow
        0xFF22C55EL, // 3 — Secondary Green
        0xFFF97316L, // 4 — Secondary Orange
        0xFFA855F7L, // 5 — Secondary Purple
        0xFF06B6D4L, // 6 — Distinct Cyan
        0xFFD946EFL, // 7 — Distinct Magenta
        0xFF84CC16L, // 8 — Distinct Lime
        0xFF14B8A6L, // 9 — Distinct Teal
    )

    val PALETTE_SIZE: Int get() = PALETTE_HEX_COLORS.size

    const val UNKNOWN_SPEAKER_GRAY_HEX: Long = 0xFF94A3B8L
    const val NON_SPEAKER_WHITE_HEX: Long = 0xFFFFFFFFL
}

fun isNonSpeakerSpecificEvent(event: DialogueEvent): Boolean =
    event.dialogueType == DialogueType.NARRATION ||
        event.dialogueType == DialogueType.VOICE_OVER ||
        event.dialogueType == DialogueType.SOUND_DESCRIPTION

fun isBubbleSpeakerUnknown(event: DialogueEvent, speaker: Speaker?): Boolean {
    if (isNonSpeakerSpecificEvent(event)) return false
    if (event.lockedByUser && event.speakerId != null && speaker != null) return false
    if (speaker == null) return true
    if (event.confidenceTier == ConfidenceTier.LOW) return true
    return !speaker.isConfirmedCharacter
}

fun isBubbleNonSpeakerSpecific(event: DialogueEvent, style: BalloonStyleSettings): Boolean {
    if (isNonSpeakerSpecificEvent(event)) return true
    return !style.speakerSpecificColors
}

enum class CaptionOriginKind(val label: String) {
    AUTHORED("Authored"),
    AUTOMATIC_ASR("Automatic ASR"),
    USER_IMPORTED("User Imported"),
    UNKNOWN("Unknown Origin"),
}

enum class YouTubeNativeCcState(val label: String) {
    OFF("Off"),
    LOADING("Checking"),
    UNAVAILABLE_OR_BLOCKED("Unavailable"),
    ENABLED_INACCESSIBLE("Protected"),
    ENABLED_NO_CURRENT_CUE("Standby"),
    ENABLED_ACTIVE_CUES("Active"),
}

@JsonClass(generateAdapter = true)
data class ReferenceCaptionCue(
    val cueId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val languageCode: String = "",
)

@JsonClass(generateAdapter = true)
data class ReferenceCaptionTrack(
    val mediaId: String,
    val trackId: String,
    val languageCode: String,
    val translationSourceLang: String? = null,
    val originKind: CaptionOriginKind = CaptionOriginKind.UNKNOWN,
    val isAutoTranslated: Boolean = false,
    val sourceRevision: Long = 1L,
    val fetchedAtEpochMs: Long = System.currentTimeMillis(),
    val provenanceLabel: String = "",
    val cues: List<ReferenceCaptionCue> = emptyList(),
)

enum class CoverageStatus {
    VERIFIED_DIALOGUE,
    VERIFIED_SILENCE,
    PARTIAL_RECOVERY,
    PENDING,
    IN_PROGRESS,
    FAILED,
    LEGACY_UNVERIFIED,
    CAPTION_GAP,
    SUSPECTED_MISSING_DIALOGUE,
    UNAVAILABLE_EVIDENCE,
    RELEASED_FALLBACK;

    val isVerified: Boolean
        get() = this == VERIFIED_DIALOGUE || this == VERIFIED_SILENCE

    val isReleasedPlayable: Boolean
        get() = this == VERIFIED_DIALOGUE ||
            this == VERIFIED_SILENCE ||
            this == CAPTION_GAP ||
            this == UNAVAILABLE_EVIDENCE ||
            this == RELEASED_FALLBACK

    val isConfirmedSilence: Boolean
        get() = this == VERIFIED_SILENCE
}

@JsonClass(generateAdapter = true)
data class CoverageInterval(
    val startMs: Long,
    val endMs: Long,
    val status: CoverageStatus,
    val source: SubtitleSourceKind = SubtitleSourceKind.AI_DRAFT,
    val verified: Boolean = status.isVerified,
    val retryAttempts: Int = 0,
    val lastFailureReason: String = "",
    val dialogueCount: Int = 0,
    val silenceConfirmed: Boolean = status.isConfirmedSilence,
    val updatedAtEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class CachedVideoTranscript(
    val canonicalKey: String,
    val title: String = "",
    val channelName: String = "",
    val description: String = "",
    val durationMs: Long = 0L,
    val sourceLanguage: String = "Auto-Detect",
    val targetLanguage: String = "English",
    val subtitleSource: SubtitleSourceKind = SubtitleSourceKind.AI_DRAFT,
    val speakers: List<Speaker> = emptyList(),
    val shots: List<Shot> = emptyList(),
    val dialogueEvents: List<DialogueEvent> = emptyList(),
    val scoutedUpToMs: Long = 0L,
    val castDiscoverySummary: String = "",
    val cachedAtEpochMs: Long = System.currentTimeMillis(),
    val cacheVersion: Int = 1,
    val coverageIntervals: List<CoverageInterval> = emptyList(),
) {
    companion object {
        const val CURRENT_CACHE_VERSION = 2
    }
}
enum class ScoutCaptureHealthState(val label: String) {
    WAITING_FOR_MEDIA("Waiting for media"),
    UNSUPPORTED_CAPTURE("Capture unsupported"),
    RECORDER_STARTED("Recorder active"),
    USABLE_DECODED_INPUT("Decoded speech input usable"),
    SOURCE_INACCESSIBLE("Source protected or inaccessible"),
    CONFIRMED_SILENCE("Confirmed silence"),
    DECODE_FAILED("Audio decode failed"),
    FAILED_EXHAUSTED("Capture recovery exhausted"),
}

@JsonClass(generateAdapter = true)
data class ScoutCaptureDiagnostics(
    val healthState: ScoutCaptureHealthState = ScoutCaptureHealthState.WAITING_FOR_MEDIA,
    val selectedElementId: String = "",
    val documentGeneration: Long = 0L,
    val recorderId: String = "",
    val outputMuted: Boolean = true,
    val outputVolumeZero: Boolean = true,
    val trackReadyState: String = "",
    val trackMuted: Boolean = false,
    val trackEnabled: Boolean = false,
    val lastMimeType: String = "",
    val lastSliceStartMs: Long = -1L,
    val lastSliceEndMs: Long = -1L,
    val sampleRateHz: Int = 0,
    val decodedSampleCount: Int = 0,
    val rmsEnergy: Float = 0f,
    val peakAmplitude: Float = 0f,
    val discontinuityCount: Int = 0,
    val droppedGapCount: Int = 0,
    val diagnosticDetail: String = "",
)

enum class YouTubePlayerErrorKind(val label: String) {
    CLIENT_IDENTITY_153("Client identity rejected (153)"),
    EMBEDDING_DENIED_101_150("Embedding disallowed (101/150)"),
    INVALID_PARAM_2("Invalid request (2)"),
    HTML5_ERROR_5("HTML5 playback error (5)"),
    VIDEO_NOT_FOUND_100("Video unavailable (100)"),
    UNKNOWN("Player error"),
}

fun classifyYouTubeErrorCode(errorCode: Int): YouTubePlayerErrorKind = when (errorCode) {
    153 -> YouTubePlayerErrorKind.CLIENT_IDENTITY_153
    101, 150 -> YouTubePlayerErrorKind.EMBEDDING_DENIED_101_150
    2 -> YouTubePlayerErrorKind.INVALID_PARAM_2
    5 -> YouTubePlayerErrorKind.HTML5_ERROR_5
    100 -> YouTubePlayerErrorKind.VIDEO_NOT_FOUND_100
    else -> YouTubePlayerErrorKind.UNKNOWN
}

