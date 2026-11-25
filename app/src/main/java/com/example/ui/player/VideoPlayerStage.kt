package com.example.ui.player

import com.example.domain.analysis.MediaTransport
import com.example.domain.analysis.ViewerTransportSample

import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import android.view.SurfaceView
import androidx.compose.ui.unit.IntSize
import com.example.domain.layout.MeasuredBalloonLayout
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import com.example.R
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.VideoSourceKind
import com.example.data.model.isBubbleSpeakerUnknown
import com.example.data.project.DemoScenarioFactory
import com.example.data.subtitle.SubtitleParser
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.LiveStageFaceTracker
import com.example.domain.analysis.LiveStageFrameSnapshot
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.SniffedWebMediaStream
import com.example.domain.analysis.WebNavCommand
import com.example.domain.analysis.WebNavigationState
import com.example.domain.layout.PlacedBalloon
import com.example.ui.state.ScoutCaptionState
import com.example.ui.state.SpeakerGlossaryToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.LensAmber
import com.example.ui.theme.LensMint
import com.example.ui.theme.LensSky
import com.example.ui.theme.SpeakerAccentPalette
import com.example.ui.theme.UnknownSpeakerGray
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val MangaPagePaper = Color(0xFFF6F1E7)
private val MangaCellInk = Color(0xFF0B0F17)

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerStage(
    project: DialogueLensProject,
    exoPlayer: ExoPlayer,
    currentTimeMs: Long,
    isPlaying: Boolean,
    isMuted: Boolean,
    playbackSpeed: Float = 1.0f,
    streamPlayRequest: Pair<Int, Boolean>? = null,
    youtubeSeekRequest: Pair<Int, Long>?,
    dualInstanceEnabled: Boolean = true,
    hasPendingPlayIntro: Boolean = false,
    isCinemaIntroActive: Boolean = false,
    introRemainingMs: Long = 0L,
    introTotalDurationMs: Long = LookaheadWindowPlanner.INTRO_DURATION_MS,
    requiredOpeningWindows: Int = 1,
    isOpeningSpeakersReady: Boolean = false,
    isScoutCatchUpBuffering: Boolean = false,
    onDismissCatchUpBuffering: () -> Unit = {},
    scoutTimeMs: Long = 0L,
    scoutedUpToMs: Long = 0L,
    scoutStatusText: String = "",
    scoutCaptionState: ScoutCaptionState = ScoutCaptionState.Idle,
    activeSpeakerToast: SpeakerGlossaryToast? = null,
    onDismissSpeakerToast: () -> Unit = {},
    onOpenCastGlossary: () -> Unit = {},
    scoutPlayRequest: Pair<Int, Boolean>? = null,
    scoutSeekRequest: Pair<Int, Long>? = null,
    showScoutMonitorPip: Boolean = true,
    onToggleScoutMonitorPip: () -> Unit = {},
    onSkipIntro: () -> Unit = {},
    onRetryYouTubeAiTranscript: () -> Unit = {},
    onOpenAttachCaptions: () -> Unit = {},
    onSwitchToZeroCloudMode: () -> Unit = {},
    displayMode: CaptionDisplayMode,
    placedBalloons: List<PlacedBalloon>,
    scoutCompletedWindows: Int = 0,
    scoutTotalWindows: Int = 0,
    onLiveFacesDetected: (Long, List<LiveFaceObservation>) -> Unit = { _, _ -> },
    isEditMode: Boolean,
    selectedEventId: String?,
    onTogglePlayPause: () -> Unit,
    onStageTap: () -> Unit = onTogglePlayPause,
    onViewerTransport: ((ViewerTransportSample) -> Unit)? = null,
    onYouTubePlaybackState: (Long, Long, Boolean) -> Unit,
    onYouTubeCaptionTrackStatus: (Int, String) -> Unit = { _, _ -> },
    onYouTubeEmbedFallback: (Int) -> Unit = {},
    onYouTubeTimedTextCaptured: (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
    onWebStreamPlaybackState: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
    onWebStreamCapturedCaptions: (String) -> Unit = {},
    onWebStreamPageMetadata: (String, String, String) -> Unit = { _, _, _ -> },
    viewerWebNavCommand: WebNavCommand? = null,
    onViewerWebNavChanged: (WebNavigationState) -> Unit = {},
    scoutWebNavCommand: WebNavCommand? = null,
    onScoutWebNavChanged: (WebNavigationState) -> Unit = {},
    scoutSessionHolder: WebStreamSessionHolder? = null,
    showScoutBrowserModal: Boolean = false,
    onScoutYouTubePlaybackState: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
    onScoutWebStreamPlaybackState: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
    onScoutWebStreamDirectMediaUrl: (String) -> Unit = {},
    onWebStreamSniffedMediaStream: (SniffedWebMediaStream) -> Unit = {},
    onScoutWebStreamAudioSlice: (Long, Long, String, String) -> Unit = { _, _, _, _ -> },
    onTapBalloon: (String) -> Unit,
    onTapFaceTrack: (String) -> Unit,
    onDragBalloonAnchor: (String, NormalizedPoint) -> Unit,
    quickPickerEventId: String? = null,
    onQuickAssignSpeaker: (String, String) -> Unit = { _, _ -> },
    onQuickAssignSpeakerScoped: ((String, String, Boolean) -> Unit)? = null,
    onQuickNameSpeaker: (String, String?, String) -> Unit = { _, _, _ -> },
    onQuickMarkOffScreen: (String, DialogueType) -> Unit = { _, _ -> },
    onQuickToggleMoveMode: () -> Unit = {},
    onQuickEditDialogue: ((String) -> Unit)? = null,
    onQuickHealMoment: (() -> Unit)? = null,
    onLiveFrameSnapshot: ((Long, LiveStageFrameSnapshot) -> Unit)? = null,
    playbackGeneration: Int = 0,
    onDismissQuickPicker: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val videoAspect = (project.videoSource.width.toFloat() /
        project.videoSource.height.coerceAtLeast(1).toFloat()).coerceIn(0.6f, 2.4f)

    val isMangaCell = project.styleSettings.visualStyle == BubbleVisualStyle.MANGA_INK &&
        !project.styleSettings.highContrastMode
    val isCinemaSubtle = project.styleSettings.visualStyle == BubbleVisualStyle.CINEMA_SUBTLE &&
        !project.styleSettings.highContrastMode

    val activeShotIndex = project.shots.indexOfLast { currentTimeMs in it.startMs..it.endMs }
        .let { if (it >= 0) it else 0 }
    val activeShot = project.shots.getOrNull(activeShotIndex)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isMangaCell) Color(0xFF0A0D13) else Color.Black)
            .padding(if (isMangaCell) 5.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val hostView = LocalView.current
        val liveFaceTracker = remember(project.id) { LiveStageFaceTracker() }
        DisposableEffect(liveFaceTracker) { onDispose { liveFaceTracker.close() } }
        var localSurface by remember(project.id) { mutableStateOf<SurfaceView?>(null) }
        var captureMasks by remember(project.id) { mutableStateOf(emptyList<NormalizedRect>()) }
        var stageBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
        val latestTimeMs by rememberUpdatedState(currentTimeMs)
        val latestOnLiveFaces by rememberUpdatedState(onLiveFacesDetected)
        val latestOnLiveSnapshot by rememberUpdatedState(onLiveFrameSnapshot)

        LaunchedEffect(project.id, playbackGeneration, isPlaying, displayMode, isCinemaIntroActive, isEditMode, project.videoSource.kind) {
            liveFaceTracker.resetSceneHistory()
            if (!isPlaying ||
                displayMode == CaptionDisplayMode.OFF ||
                isCinemaIntroActive ||
                isEditMode ||
                project.videoSource.kind == VideoSourceKind.DEMO_SCENARIO
            ) {
                return@LaunchedEffect
            }
            while (isActive) {
                if (stageBoundsInWindow.width >= 48f && stageBoundsInWindow.height >= 48f) {
                    val sampleTime = latestTimeMs
                    val snapshot = liveFaceTracker.captureFrameSnapshot(
                        hostView = hostView,
                        stageBoundsInWindow = stageBoundsInWindow,
                        videoTimeMs = sampleTime,
                        videoSurface = localSurface,
                        overlayBounds = captureMasks,
                    )
                    if (snapshot != null) {
                        val snapHandler = latestOnLiveSnapshot
                        if (snapHandler != null) {
                            snapHandler(sampleTime, snapshot.copy(projectId = project.id, playbackGeneration = playbackGeneration))
                        } else {
                            latestOnLiveFaces(sampleTime, snapshot.faces)
                        }
                    }
                }
                delay(135L)
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(videoAspect)
                .onGloballyPositioned { coords ->
                    stageBoundsInWindow = coords.boundsInWindow()
                }
                .then(
                    when {
                        isMangaCell -> Modifier
                            .border(2.5.dp, MangaPagePaper, RoundedCornerShape(2.dp))
                            .padding(2.dp)
                            .border(2.dp, MangaCellInk, RoundedCornerShape(1.dp))
                        isCinemaSubtle -> Modifier
                            .border(0.75.dp, Color.White.copy(alpha = 0.14f))
                        else -> Modifier
                    }
                )
                .then(
                    if (!isEditMode &&
                        project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT &&
                        project.videoSource.kind != VideoSourceKind.WEB_STREAM
                    ) {
                        Modifier.clickable(onClickLabel = "Show or hide controls", onClick = onStageTap)
                    } else {
                        Modifier
                    }
                )
                .testTag("video_player_stage"),
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }

            // 1. Video Layer (Media3 ExoPlayer for MP4, official embedded YouTube IFrame, or Demo Film Stage)
            when (project.videoSource.kind) {
                VideoSourceKind.LOCAL_MP4 -> {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                                localSurface = videoSurfaceView as? SurfaceView
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        update = { view ->
                            if (view.player !== exoPlayer) {
                                view.player = exoPlayer
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                VideoSourceKind.YOUTUBE_COMPLIANT -> {
                    val hasRealSubtitles = project.dialogueEvents.any { evt ->
                        (evt.sourceText.isNotBlank() || evt.translatedText.isNotBlank()) &&
                            !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                            !evt.sourceText.startsWith("Web Cinema ready")
                    }
                    val ytLangCode = when (project.targetLanguage.trim().lowercase()) {
                        "japanese" -> "ja"
                        "korean" -> "ko"
                        "spanish" -> "es"
                        "french" -> "fr"
                        "german" -> "de"
                        "mandarin", "chinese" -> "zh"
                        "italian" -> "it"
                        "portuguese" -> "pt"
                        "hindi" -> "hi"
                        else -> "en"
                    }
                    YouTubeEmbeddedPlayer(
                        videoId = project.videoSource.youtubeVideoId.orEmpty(),
                        isMuted = isMuted,
                        playbackSpeed = playbackSpeed,
                        playRequest = streamPlayRequest,
                        seekRequest = youtubeSeekRequest,
                        onPlaybackState = onYouTubePlaybackState,
                        onViewerTransport = onViewerTransport,
                        // Softly hide YouTube's default track ONLY when DialogueLens bubbles are loaded AND turned ON;
                        // when bubbles are absent or OFF, leave YouTube's native CC completely unblocked.
                        suppressNativeCaptions = hasRealSubtitles && displayMode != CaptionDisplayMode.OFF,
                        preferredCaptionLang = ytLangCode,
                        onCaptionTrackStatus = onYouTubeCaptionTrackStatus,
                        onEmbedFallback = onYouTubeEmbedFallback,
                        onCapturedTimedTextBody = onYouTubeTimedTextCaptured,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                VideoSourceKind.WEB_STREAM -> {
                    WebStreamPlayer(
                        webUrl = project.videoSource.uriString,
                        isMuted = isMuted,
                        playbackSpeed = playbackSpeed,
                        playRequest = streamPlayRequest,
                        seekRequest = youtubeSeekRequest,
                        onPlaybackState = onWebStreamPlaybackState,
                        onViewerTransport = onViewerTransport,
                        onCapturedCaptions = onWebStreamCapturedCaptions,
                        isolateVideoInViewport = true,
                        onDirectMediaUrl = onScoutWebStreamDirectMediaUrl,
                        onSniffedMediaStream = onWebStreamSniffedMediaStream,
                        onPageMetadata = onWebStreamPageMetadata,
                        navCommand = viewerWebNavCommand,
                        onNavigationStateChange = onViewerWebNavChanged,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                VideoSourceKind.DEMO_SCENARIO -> {
                    val sceneRes = if (activeShot?.demoSceneKey == DemoScenarioFactory.SCENE_CLOSEUP_A) {
                        R.drawable.img_demo_closeup_a_1795100345743
                    } else {
                        R.drawable.img_demo_two_shot_1795100344716
                    }
                    Crossfade(
                        targetState = sceneRes,
                        animationSpec = tween(durationMillis = if (project.styleSettings.reducedMotion) 0 else 110),
                        label = "demo_shot_cut",
                    ) { resId ->
                        Image(
                            painter = painterResource(id = resId),
                            contentDescription = "Demo film scene",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Subtle corner focus lines in manga close-up cells (strictly outer corners so faces remain 100% clear)
            if (isMangaCell && activeShot?.cutType?.contains("CLOSEUP", ignoreCase = true) == true) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width * 0.45f
                    val cy = size.height * 0.45f
                    val lineCount = 26
                    for (i in 0 until lineCount) {
                        val angle = (i.toDouble() / lineCount) * Math.PI * 2.0
                        val outerR = size.width.coerceAtLeast(size.height) * 0.85f
                        val innerR = outerR * (0.64f + (i % 3) * 0.05f)
                        drawLine(
                            color = MangaCellInk.copy(alpha = 0.22f),
                            start = Offset(
                                x = cx + (cos(angle) * innerR).toFloat(),
                                y = cy + (sin(angle) * innerR).toFloat(),
                            ),
                            end = Offset(
                                x = cx + (cos(angle) * outerR).toFloat(),
                                y = cy + (sin(angle) * outerR).toFloat(),
                            ),
                            strokeWidth = if (i % 2 == 0) 2.2f else 1.2f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }

            // Deaf-First Cinema: Off-Screen Directional Edge Glow (peripheral cue tinted in off-screen speaker's hue)
            if (project.styleSettings.offScreenEdgeGlow && !project.styleSettings.highContrastMode) {
                val offScreenCues = placedBalloons.filter { it.isOffScreenEdge }
                val leftHue = offScreenCues.firstOrNull { it.edgeSide == DialogueType.OFF_SCREEN_LEFT }?.let { b ->
                    if (!b.isUnknownSpeaker && b.speaker != null && !isBubbleSpeakerUnknown(b.event, b.speaker)) {
                        SpeakerAccentPalette[b.speaker.accentIndex % SpeakerAccentPalette.size]
                    } else {
                        UnknownSpeakerGray
                    }
                }
                val rightHue = offScreenCues.firstOrNull { it.edgeSide == DialogueType.OFF_SCREEN_RIGHT }?.let { b ->
                    if (!b.isUnknownSpeaker && b.speaker != null && !isBubbleSpeakerUnknown(b.event, b.speaker)) {
                        SpeakerAccentPalette[b.speaker.accentIndex % SpeakerAccentPalette.size]
                    } else {
                        UnknownSpeakerGray
                    }
                }
                val behindHue = offScreenCues.firstOrNull { it.edgeSide == DialogueType.OFF_SCREEN_BEHIND }?.let { b ->
                    if (!b.isUnknownSpeaker && b.speaker != null && !isBubbleSpeakerUnknown(b.event, b.speaker)) {
                        SpeakerAccentPalette[b.speaker.accentIndex % SpeakerAccentPalette.size]
                    } else {
                        UnknownSpeakerGray
                    }
                }
                val glowSpec = if (project.styleSettings.reducedMotion) snap<Float>() else tween<Float>(220)
                val leftAlpha by animateFloatAsState(if (leftHue != null) 1f else 0f, animationSpec = glowSpec, label = "offscreen_left_glow")
                val rightAlpha by animateFloatAsState(if (rightHue != null) 1f else 0f, animationSpec = glowSpec, label = "offscreen_right_glow")
                val behindAlpha by animateFloatAsState(if (behindHue != null) 1f else 0f, animationSpec = glowSpec, label = "offscreen_behind_glow")

                if (leftAlpha > 0.01f || rightAlpha > 0.01f || behindAlpha > 0.01f) {
                    Canvas(modifier = Modifier.fillMaxSize().testTag("offscreen_edge_glow_layer")) {
                        val sideWidth = 56.dp.toPx()
                        val topHeight = 38.dp.toPx()
                        val edgeBarPx = 3.5.dp.toPx()
                        if (leftHue != null && leftAlpha > 0.01f) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        leftHue.copy(alpha = 0.48f * leftAlpha),
                                        leftHue.copy(alpha = 0.18f * leftAlpha),
                                        Color.Transparent,
                                    ),
                                    startX = 0f,
                                    endX = sideWidth,
                                ),
                                topLeft = Offset.Zero,
                                size = Size(sideWidth, size.height),
                            )
                            drawRoundRect(
                                color = leftHue.copy(alpha = 0.90f * leftAlpha),
                                topLeft = Offset(0f, size.height * 0.08f),
                                size = Size(edgeBarPx, size.height * 0.84f),
                                cornerRadius = CornerRadius(edgeBarPx, edgeBarPx),
                            )
                        }
                        if (rightHue != null && rightAlpha > 0.01f) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        rightHue.copy(alpha = 0.18f * rightAlpha),
                                        rightHue.copy(alpha = 0.48f * rightAlpha),
                                    ),
                                    startX = size.width - sideWidth,
                                    endX = size.width,
                                ),
                                topLeft = Offset(size.width - sideWidth, 0f),
                                size = Size(sideWidth, size.height),
                            )
                            drawRoundRect(
                                color = rightHue.copy(alpha = 0.90f * rightAlpha),
                                topLeft = Offset(size.width - edgeBarPx, size.height * 0.08f),
                                size = Size(edgeBarPx, size.height * 0.84f),
                                cornerRadius = CornerRadius(edgeBarPx, edgeBarPx),
                            )
                        }
                        if (behindHue != null && behindAlpha > 0.01f) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        behindHue.copy(alpha = 0.38f * behindAlpha),
                                        behindHue.copy(alpha = 0.12f * behindAlpha),
                                        Color.Transparent,
                                    ),
                                    startY = 0f,
                                    endY = topHeight,
                                ),
                                topLeft = Offset.Zero,
                                size = Size(size.width, topHeight),
                            )
                            drawRoundRect(
                                color = behindHue.copy(alpha = 0.85f * behindAlpha),
                                topLeft = Offset(size.width * 0.14f, 0f),
                                size = Size(size.width * 0.72f, edgeBarPx),
                                cornerRadius = CornerRadius(edgeBarPx, edgeBarPx),
                            )
                        }
                    }
                }
            }

            // Deaf-First Cinema: Subtle Beat-Synced Music / Tension Mood Border (hairline outer glow on frame edge)
            if (project.styleSettings.ambientMoodBorder && !project.styleSettings.highContrastMode) {
                val activeSfx = placedBalloons.firstOrNull { it.event.dialogueType == DialogueType.SOUND_DESCRIPTION }
                val activeShout = placedBalloons.firstOrNull { it.isShouted }
                val moodTarget: Pair<Color, Float> = when {
                    activeSfx != null -> {
                        val desc = activeSfx.event.activeText(project.translationMode).lowercase()
                        val isTense = desc.contains("thunder") || desc.contains("slam") || desc.contains("crash") ||
                            desc.contains("explosion") || desc.contains("alarm") || desc.contains("siren") ||
                            desc.contains("bang") || desc.contains("gun") || desc.contains("scream") ||
                            desc.contains("roar") || desc.contains("heartbeat")
                        val isMelodic = desc.contains("music") || desc.contains("piano") || desc.contains("strings") ||
                            desc.contains("violin") || desc.contains("guitar") || desc.contains("rain") ||
                            desc.contains("wind") || desc.contains("hum") || desc.contains("song") ||
                            desc.contains("melody") || desc.contains("♪") || desc.contains("sigh") ||
                            desc.contains("chime")
                        when {
                            isTense -> Color(0xFFFB7185) to 0.44f
                            isMelodic -> Color(0xFF38BDF8) to 0.34f
                            else -> Color(0xFF818CF8) to 0.30f
                        }
                    }
                    activeShout != null -> {
                        val spkHue = if (!activeShout.isUnknownSpeaker && activeShout.speaker != null && !isBubbleSpeakerUnknown(activeShout.event, activeShout.speaker)) {
                            SpeakerAccentPalette[activeShout.speaker.accentIndex % SpeakerAccentPalette.size]
                        } else {
                            LensAmber
                        }
                        spkHue to 0.34f
                    }
                    else -> Color.Transparent to 0f
                }

                val peaks = project.audioWaveformPeaks
                val peakMod = if (peaks.isNotEmpty() && !project.styleSettings.reducedMotion && moodTarget.second > 0f) {
                    val progress = (currentTimeMs.toFloat() / project.videoSource.durationMs.coerceAtLeast(1L).toFloat())
                        .coerceIn(0f, 1f)
                    val idx = ((progress * (peaks.size - 1)).roundToInt()).coerceIn(0, peaks.lastIndex)
                    (0.68f + 0.52f * peaks[idx].coerceIn(0f, 1f))
                } else {
                    0.88f
                }

                val borderAlphaSpec = if (project.styleSettings.reducedMotion) snap<Float>() else tween<Float>(180)
                val borderColorSpec = if (project.styleSettings.reducedMotion) snap<Color>() else tween<Color>(220)
                val smoothAlpha by animateFloatAsState(
                    targetValue = (moodTarget.second * peakMod).coerceIn(0f, 0.62f),
                    animationSpec = borderAlphaSpec,
                    label = "ambient_mood_alpha",
                )
                val smoothColor by animateColorAsState(
                    targetValue = if (moodTarget.second > 0f) moodTarget.first else Color.Transparent,
                    animationSpec = borderColorSpec,
                    label = "ambient_mood_color",
                )

                if (smoothAlpha > 0.01f) {
                    Canvas(modifier = Modifier.fillMaxSize().testTag("ambient_mood_border_layer")) {
                        val insetPx = 1.dp.toPx()
                        val borderSize = Size(
                            width = (size.width - insetPx * 2f).coerceAtLeast(1f),
                            height = (size.height - insetPx * 2f).coerceAtLeast(1f),
                        )
                        drawRoundRect(
                            color = smoothColor.copy(alpha = smoothAlpha * 0.28f),
                            topLeft = Offset(insetPx, insetPx),
                            size = borderSize,
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(width = 5.dp.toPx()),
                        )
                        drawRoundRect(
                            color = smoothColor.copy(alpha = smoothAlpha),
                            topLeft = Offset(insetPx, insetPx),
                            size = borderSize,
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }

            // Top Cell Header Stamp & Edit Badge (shown only in Edit Mode so story playback stays pure)
            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.36f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (activeShot != null) {
                            if (isMangaCell) {
                                Surface(
                                    color = MangaPagePaper.copy(alpha = 0.92f),
                                    shape = RoundedCornerShape(3.dp),
                                    border = BorderStroke(1.2.dp, MangaCellInk),
                                ) {
                                    Text(
                                        text = "PANEL ${(activeShotIndex + 1).toString().padStart(2, '0')}/${project.shots.size.toString().padStart(2, '0')} · ${activeShot.cutType.replace('_', ' ')}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.4.sp,
                                        ),
                                        color = MangaCellInk,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            } else {
                                Surface(
                                    color = CinemaObsidian.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = "${activeShot.id.uppercase()} · ${activeShot.cutType.replace('_', ' ')}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = Color.White.copy(alpha = 0.86f),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = CinemaObsidian,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "EDIT MODE · Tap face or drag balloon",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = CinemaObsidian,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Edit Mode Interactive Face Bounding Boxes
            if (isEditMode) {
                val activeShotId = activeShot?.id
                val visibleTracks = project.faceTracks.filter { it.shotId == activeShotId }
                visibleTracks.forEach { track ->
                    val sample = track.sampleAt(currentTimeMs) ?: return@forEach
                    val speaker = project.speakers.firstOrNull { it.id == track.speakerId }
                    val accent = if (speaker != null && speaker.isConfirmedCharacter) {
                        SpeakerAccentPalette[speaker.accentIndex % SpeakerAccentPalette.size]
                    } else {
                        UnknownSpeakerGray
                    }

                    val boxLeftPx = sample.boundingBox.left * widthPx
                    val boxTopPx = sample.boundingBox.top * heightPx
                    val boxWidthDp = with(density) { (sample.boundingBox.width * widthPx).toDp() }
                    val boxHeightDp = with(density) { (sample.boundingBox.height * heightPx).toDp() }

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(boxLeftPx.roundToInt(), boxTopPx.roundToInt()) }
                            .size(width = boxWidthDp, height = boxHeightDp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(BorderStroke(2.dp, accent), RoundedCornerShape(8.dp))
                            .background(accent.copy(alpha = 0.12f))
                            .clickable { onTapFaceTrack(track.id) }
                            .testTag("face_box_${track.id}"),
                    ) {
                        Surface(
                            color = accent,
                            shape = RoundedCornerShape(bottomEnd = 6.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Face,
                                    contentDescription = "Assign to ${speaker?.displayName ?: "person"}",
                                    tint = CinemaObsidian,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = speaker?.displayName ?: "Tap to assign",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                    color = CinemaObsidian,
                                )
                            }
                        }
                    }
                }
            }

            // 3. Independent Decoupled Spatial Balloon Overlay Layer & Obstacle-Aware Spatial SFX Notes
            val activeFaceExclusions = remember(placedBalloons) {
                val observed = placedBalloons.firstOrNull()?.faceExclusions.orEmpty()
                val authored = placedBalloons.flatMap { it.event.avoidBoxes }
                val anchored = if (observed.isEmpty()) placedBalloons.mapNotNull { balloon ->
                    val pt = balloon.event.anchorPosition
                    if (balloon.tailTarget != null && pt != null &&
                        balloon.event.placementMode != com.example.data.model.PlacementMode.MANUAL_OVERRIDE) {
                        NormalizedRect(pt.x, pt.y, pt.x, pt.y).expand(0.14f, 0.14f)
                    } else null
                } else emptyList()
                observed + authored + anchored
            }
            val coordinator = remember(project.id, playbackGeneration, widthPx, heightPx) { MeasuredBalloonLayout() }
            val sizes = remember(project.id) { mutableStateMapOf<String, IntSize>() }
            val visibleBodies = remember(placedBalloons, displayMode) {
                placedBalloons.filter {
                    displayMode == CaptionDisplayMode.BALLOONS ||
                        (displayMode == CaptionDisplayMode.BOTH && it.event.dialogueType != DialogueType.SOUND_DESCRIPTION)
                }
            }
            val measured = sizes.toMap()
            val safeWidth = widthPx.coerceAtLeast(1f)
            val safeHeight = heightPx.coerceAtLeast(1f)
            val obstacles = activeFaceExclusions + project.textExclusionZones + activeShot?.focalExclusionZones.orEmpty()
            val positions = remember(coordinator, visibleBodies, measured, obstacles, density) {
                coordinator.place(
                    proposals = visibleBodies,
                    sizes = measured.mapValues { NormalizedPoint(it.value.width / safeWidth, it.value.height / safeHeight) },
                    obstacles = obstacles,
                    marginX = with(density) { 10.dp.toPx() } / safeWidth,
                    marginY = with(density) { 8.dp.toPx() } / safeHeight,
                )
            }
            val visibleIds = remember(visibleBodies) { visibleBodies.mapTo(mutableSetOf()) { it.event.id } }
            LaunchedEffect(visibleIds) { sizes.keys.toList().filterNot(visibleIds::contains).forEach { sizes.remove(it) } }
            SideEffect {
                val classic = displayMode == CaptionDisplayMode.CLASSIC || displayMode == CaptionDisplayMode.BOTH
                captureMasks = positions.values.toList() + if (classic && placedBalloons.isNotEmpty())
                    listOf(NormalizedRect(0f, 0.72f, 1f, 1f)) else emptyList()
            }
            visibleBodies.forEach { placed ->
                val id = placed.event.id
                key(project.id, id) {
                    if (placed.event.dialogueType == DialogueType.SOUND_DESCRIPTION) {
                        SpatialSfxNoteView(
                            placed = placed,
                            translationMode = project.translationMode,
                            style = project.styleSettings,
                            containerWidthPx = widthPx,
                            containerHeightPx = heightPx,
                            committedRect = positions[id],
                            onMeasure = { sizes[id] = it },
                            onTap = { onTapBalloon(id) },
                        )
                    } else {
                        SpeechBalloonView(
                            placed = placed,
                            translationMode = project.translationMode,
                            style = project.styleSettings,
                            isEditMode = isEditMode,
                            isSelected = id == selectedEventId,
                            containerWidthPx = widthPx,
                            containerHeightPx = heightPx,
                            committedRect = positions[id],
                            onMeasure = { sizes[id] = it },
                            onClick = { onTapBalloon(id) },
                            onDragCommit = { onDragBalloonAnchor(id, it) },
                        )
                    }
                }
            }
            if (displayMode == CaptionDisplayMode.CLASSIC || displayMode == CaptionDisplayMode.BOTH) {
                ClassicSubtitleBar(
                    placedBalloons = placedBalloons,
                    translationMode = project.translationMode,
                    style = project.styleSettings,
                    onTapEvent = onTapBalloon,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // 5. Real Lookahead Scout Status & Web Stream Scout Instance
            if (dualInstanceEnabled && project.videoSource.kind != VideoSourceKind.DEMO_SCENARIO) {
                val showPip = showScoutMonitorPip && !isEditMode
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Keep a real active WebView Scout instance mounted for HTML5 Web Cinema streams
                    // (when the interactive Scout Browser Modal is open, that modal attaches the same sessionHolder)
                    if (project.videoSource.kind == VideoSourceKind.WEB_STREAM && !showScoutBrowserModal) {
                        val pipW = if (showPip) 104.dp else 2.dp
                        val pipH = if (showPip) 58.dp else 2.dp
                        Box(
                            modifier = Modifier
                                .size(width = pipW, height = pipH)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (showPip) 1.dp else 0.dp,
                                    color = LensSky.copy(alpha = if (showPip) 0.55f else 0f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .background(Color.Black),
                        ) {
                            WebStreamPlayer(
                                webUrl = project.videoSource.uriString,
                                isMuted = true,
                                playbackSpeed = playbackSpeed,
                                playRequest = scoutPlayRequest,
                                seekRequest = scoutSeekRequest,
                                onPlaybackState = onScoutWebStreamPlaybackState,
                                onCapturedCaptions = onWebStreamCapturedCaptions,
                                isScoutInstance = true,
                                isolateVideoInViewport = true,
                                onDirectMediaUrl = onScoutWebStreamDirectMediaUrl,
                                onSniffedMediaStream = onWebStreamSniffedMediaStream,
                                onCapturedAudioSlice = onScoutWebStreamAudioSlice,
                                navCommand = scoutWebNavCommand,
                                onNavigationStateChange = onScoutWebNavChanged,
                                sessionHolder = scoutSessionHolder,
                                autoDisposeOnLeave = false,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // 5b. Live Running Speaker Glossary Toast (when Scout spots a new speaker or identifies/renames one)
            if (activeSpeakerToast != null && !isEditMode && !isCinemaIntroActive) {
                val toastAccent = if (activeSpeakerToast.isUnknown) {
                    UnknownSpeakerGray
                } else {
                    SpeakerAccentPalette[activeSpeakerToast.accentIndex % SpeakerAccentPalette.size]
                }
                Surface(
                    color = CinemaObsidian.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.2.dp, toastAccent.copy(alpha = 0.85f)),
                    onClick = {
                        onDismissSpeakerToast()
                        onOpenCastGlossary()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .testTag("live_speaker_glossary_toast"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(toastAccent)
                        )
                        Column {
                            Text(
                                text = activeSpeakerToast.title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = toastAccent,
                            )
                            Text(
                                text = activeSpeakerToast.detail,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = Color.White,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 7. Floating Contextual Quick Speaker Picker (tap any bubble to confirm, reassign, rename, or mark off-screen)
            val pickerEvent = remember(quickPickerEventId, project.dialogueEvents) {
                quickPickerEventId?.let { id -> project.dialogueEvents.firstOrNull { it.id == id } }
            }
            if (pickerEvent != null && !isCinemaIntroActive) {
                val tappedPlaced = placedBalloons.firstOrNull { it.event.id == pickerEvent.id }
                val bubbleInUpperHalf = (tappedPlaced?.rect?.centerY ?: 0.5f) < 0.48f
                FloatingQuickSpeakerPicker(
                    event = pickerEvent,
                    speakers = project.speakers,
                    anchorBottom = bubbleInUpperHalf,
                    onAssignSpeaker = { spkId, applyAll ->
                        val scoped = onQuickAssignSpeakerScoped
                        if (scoped != null) {
                            scoped(pickerEvent.id, spkId, applyAll)
                        } else {
                            onQuickAssignSpeaker(pickerEvent.id, spkId)
                        }
                    },
                    onNameSpeaker = { targetSpkId, name -> onQuickNameSpeaker(pickerEvent.id, targetSpkId, name) },
                    onMarkOffScreen = { side -> onQuickMarkOffScreen(pickerEvent.id, side) },
                    onToggleMoveMode = {
                        onDismissQuickPicker()
                        onQuickToggleMoveMode()
                    },
                    onEditDialogue = onQuickEditDialogue?.let { handler ->
                        {
                            onDismissQuickPicker()
                            handler(pickerEvent.id)
                        }
                    },
                    onHealMoment = onQuickHealMoment?.let { handler ->
                        {
                            onDismissQuickPicker()
                            handler()
                        }
                    },
                    onDismiss = onDismissQuickPicker,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FloatingQuickSpeakerPicker(
    event: com.example.data.model.DialogueEvent,
    speakers: List<com.example.data.model.Speaker>,
    anchorBottom: Boolean,
    onAssignSpeaker: (String, Boolean) -> Unit,
    onNameSpeaker: (String?, String) -> Unit,
    onMarkOffScreen: (DialogueType) -> Unit,
    onToggleMoveMode: () -> Unit,
    onEditDialogue: (() -> Unit)? = null,
    onHealMoment: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSpeaker = speakers.firstOrNull { it.id == event.speakerId }
    val isCurrentUnknown = isBubbleSpeakerUnknown(event, currentSpeaker)
    var showNameField by remember(event.id) { mutableStateOf(isCurrentUnknown) }
    // Default strictly to single-line reassignment so uncertain global edits never happen silently
    var applyToAllLines by remember(event.id) { mutableStateOf(false) }
    var draftName by remember(event.id) {
        mutableStateOf(
            if (currentSpeaker != null && currentSpeaker.isConfirmedCharacter) currentSpeaker.displayName else ""
        )
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onDismiss)
            .testTag("floating_quick_speaker_picker_scrim"),
    ) {
        Surface(
            color = CinemaObsidian.copy(alpha = 0.96f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.2.dp, LensSky.copy(alpha = 0.62f)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .align(if (anchorBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .testTag("floating_quick_speaker_picker"),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (isCurrentUnknown) {
                            "Who said this?"
                        } else {
                            "Speaker · ${currentSpeaker?.displayName ?: "Unassigned"}"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isCurrentUnknown) LensAmber else LensSky,
                    )
                    Text(
                        text = "\"${event.translatedText.ifBlank { event.sourceText }}\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("quick_picker_close_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close speaker picker and resume",
                            tint = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Explicit scope toggle: This line only (default safe) vs All lines by current speaker
                if (currentSpeaker != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = !applyToAllLines,
                            onClick = { applyToAllLines = false },
                            label = { Text("This line only", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LensSky.copy(alpha = 0.22f),
                                selectedLabelColor = Color.White,
                            ),
                            modifier = Modifier.testTag("quick_picker_scope_single"),
                        )
                        FilterChip(
                            selected = applyToAllLines,
                            onClick = { applyToAllLines = true },
                            label = {
                                Text(
                                    "All lines by ${currentSpeaker.displayName.take(14)}",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LensAmber.copy(alpha = 0.24f),
                                selectedLabelColor = Color.White,
                            ),
                            modifier = Modifier.testTag("quick_picker_scope_all"),
                        )
                    }
                }

                // 1-tap Cast chips row (initials avatar + color so identity isn't color-only)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    speakers.forEach { spk ->
                        val confirmed = spk.isConfirmedCharacter
                        val chipAccent = if (confirmed) {
                            SpeakerAccentPalette[spk.accentIndex % SpeakerAccentPalette.size]
                        } else {
                            UnknownSpeakerGray
                        }
                        val initials = spk.displayName
                            .split(" ")
                            .filter { it.isNotBlank() }
                            .take(2)
                            .joinToString("") { it.first().uppercaseChar().toString() }
                            .ifBlank { "?" }
                        val isSelected = event.speakerId == spk.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { onAssignSpeaker(spk.id, applyToAllLines) },
                            label = {
                                Text(
                                    text = spk.displayName,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                )
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(chipAccent),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = initials,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CinemaObsidian,
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipAccent.copy(alpha = 0.28f),
                                selectedLabelColor = Color.White,
                            ),
                            modifier = Modifier.testTag("quick_picker_chip_${spk.id}"),
                        )
                    }

                    FilterChip(
                        selected = showNameField,
                        onClick = { showNameField = !showNameField },
                        label = { Text(if (isCurrentUnknown) "Name" else "+ Name", fontSize = 11.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        modifier = Modifier.testTag("quick_picker_name_toggle"),
                    )
                }

                if (showNameField) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it.take(26) },
                            placeholder = {
                                Text("Type speaker's real name...", fontSize = 12.sp)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LensSky,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                                cursorColor = LensSky,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_picker_name_input"),
                        )
                        Button(
                            onClick = { onNameSpeaker(event.speakerId, draftName) },
                            enabled = draftName.trim().length >= 2,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LensSky,
                                contentColor = CinemaObsidian,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("quick_picker_confirm_name_button"),
                        ) {
                            Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Contextual secondary actions: Off-screen, Edit dialogue, Move, Heal this moment
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                        onClick = { onMarkOffScreen(DialogueType.OFF_SCREEN_LEFT) },
                        modifier = Modifier.testTag("quick_picker_offscreen_left"),
                    ) {
                        Text(
                            text = "◀ Off Left",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                        onClick = { onMarkOffScreen(DialogueType.OFF_SCREEN_RIGHT) },
                        modifier = Modifier.testTag("quick_picker_offscreen_right"),
                    ) {
                        Text(
                            text = "Off Right ▶",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                    if (onEditDialogue != null) {
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            onClick = onEditDialogue,
                            modifier = Modifier.testTag("quick_picker_edit_dialogue"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit dialogue in Studio",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "Edit",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = Color.White.copy(alpha = 0.9f),
                                )
                            }
                        }
                    }
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                        onClick = onToggleMoveMode,
                        modifier = Modifier.testTag("quick_picker_move_bubble"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.OpenWith,
                                contentDescription = "Move bubble position",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = "Move",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                    if (onHealMoment != null) {
                        Surface(
                            color = LensSky.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, LensSky.copy(alpha = 0.55f)),
                            onClick = onHealMoment,
                            modifier = Modifier.testTag("quick_picker_heal_moment"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoFixHigh,
                                    contentDescription = "Heal this moment",
                                    tint = LensSky,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "Heal",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
