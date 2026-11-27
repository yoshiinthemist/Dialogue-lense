package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.data.ai.AppCheckDiagnostics
import com.example.data.ai.ScoutHeartbeatStatus
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealScope
import com.example.data.model.QuotaSaverMode
import com.example.data.model.SpeakerLabelMode
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSourceKind
import com.example.data.model.YouTubeNativeCcState
import com.example.data.model.isBubbleSpeakerUnknown
import com.example.data.model.isNonSpeakerSpecificEvent
import com.example.data.subtitle.SubtitleParser
import com.example.ui.editor.InspectorTab
import com.example.ui.editor.TimelineSubtitleEditor
import com.example.ui.player.HealBeaconDetailSheet
import com.example.ui.player.HealThisMomentPill
import com.example.ui.player.PlayerControlsBar
import com.example.ui.player.ReadinessStatusPill
import com.example.ui.player.ScoutBrowserInspectorModal
import com.example.ui.player.VideoPlayerStage
import com.example.ui.player.WebBrowserControlStrip
import com.example.ui.player.WebStreamSessionHolder
import com.example.ui.settings.BalloonStyleSheet
import com.example.ui.setup.FirstTimeOnboardingOverlay
import com.example.ui.setup.MediaImportSheet
import com.example.ui.setup.ProjectAndPrivacySheet
import com.example.ui.setup.StartLauncherScreen
import com.example.ui.setup.SubtitleExportSheet
import com.example.ui.state.AppDestination
import com.example.ui.state.DialogueLensUiState
import com.example.ui.state.DialogueLensViewModel
import com.example.ui.state.ScoutCaptionState
import com.example.ui.state.SubtitlePrepState
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.ConfidenceHighColor
import com.example.ui.theme.LensAmber
import com.example.ui.theme.LensMint
import com.example.ui.theme.LensSky
import com.example.ui.theme.NonSpeakerBubbleWhite
import com.example.ui.theme.SpeakerAccentPalette
import com.example.ui.theme.UnknownSpeakerGray
import kotlinx.coroutines.delay

private data class PendingSubtitleExport(
    val project: DialogueLensProject,
    val format: SubtitleParser.SubtitleExportFormat,
    val includeSpeakerPrefixes: Boolean,
    val translationMode: TranslationMode,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogueLensScreen(
    viewModel: DialogueLensViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showImportSheet by remember { mutableStateOf(false) }
    var showPrivacySheet by remember { mutableStateOf(false) }
    var showStyleSheet by remember { mutableStateOf(false) }
    var showToolsDrawer by remember { mutableStateOf(false) }
    var subtitleExportProject by remember { mutableStateOf<DialogueLensProject?>(null) }
    var pendingSafExport by remember { mutableStateOf<PendingSubtitleExport?>(null) }
    var inspectorTabRequest by remember { mutableStateOf(InspectorTab.TIMELINE) }
    var expandScoutDiagInDrawer by remember { mutableStateOf(false) }

    val scoutSessionHolder = remember(state.project.id, state.project.videoSource.uriString) {
        WebStreamSessionHolder()
    }
    DisposableEffect(scoutSessionHolder) {
        onDispose {
            scoutSessionHolder.dispose()
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportProjectJsonToUri(uri)
    }

    val exportSrtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        val pending = pendingSafExport
        pendingSafExport = null
        if (uri != null) {
            if (pending != null) {
                viewModel.exportSubtitlesToUri(
                    uri = uri,
                    project = pending.project,
                    format = pending.format,
                    includeSpeakerPrefixes = pending.includeSpeakerPrefixes,
                    translationMode = pending.translationMode,
                )
            } else {
                viewModel.exportSrtToUri(uri)
            }
        }
    }

    val exportVttLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/vtt")
    ) { uri ->
        val pending = pendingSafExport
        pendingSafExport = null
        if (uri != null) {
            if (pending != null) {
                viewModel.exportSubtitlesToUri(
                    uri = uri,
                    project = pending.project,
                    format = pending.format,
                    includeSpeakerPrefixes = pending.includeSpeakerPrefixes,
                    translationMode = pending.translationMode,
                )
            } else {
                viewModel.exportVttToUri(uri)
            }
        }
    }

    val exportAssLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val pending = pendingSafExport
        pendingSafExport = null
        if (uri != null) {
            if (pending != null) {
                viewModel.exportSubtitlesToUri(
                    uri = uri,
                    project = pending.project,
                    format = pending.format,
                    includeSpeakerPrefixes = pending.includeSpeakerPrefixes,
                    translationMode = pending.translationMode,
                )
            } else {
                viewModel.exportAssToUri(uri)
            }
        }
    }

    val exportMp4Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        if (uri != null) viewModel.exportBurnedMp4ToUri(uri)
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importProjectJsonFromUri(uri)
    }

    val exportPrepSrtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) viewModel.exportPreparedSubtitlesToUri(uri)
    }

    val exportPrepVttLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/vtt")
    ) { uri ->
        if (uri != null) viewModel.exportPreparedSubtitlesToUri(uri)
    }

    val exportPrepAssLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) viewModel.exportPreparedSubtitlesToUri(uri)
    }

    fun launchSafSubtitleExport(
        project: DialogueLensProject,
        format: SubtitleParser.SubtitleExportFormat,
        includeSpeakerPrefixes: Boolean,
        translationMode: TranslationMode,
    ) {
        val filename = SubtitleParser.suggestedExportFilename(project.title, format)
        pendingSafExport = PendingSubtitleExport(
            project = project,
            format = format,
            includeSpeakerPrefixes = includeSpeakerPrefixes,
            translationMode = translationMode,
        )
        when (format) {
            SubtitleParser.SubtitleExportFormat.SRT -> exportSrtLauncher.launch(filename)
            SubtitleParser.SubtitleExportFormat.VTT -> exportVttLauncher.launch(filename)
            SubtitleParser.SubtitleExportFormat.ASS -> exportAssLauncher.launch(filename)
        }
    }

    subtitleExportProject?.let { targetProject ->
        SubtitleExportSheet(
            project = targetProject,
            onDismiss = { subtitleExportProject = null },
            onSaveToDownloads = { fmt, includeSpeakers, mode ->
                viewModel.saveSubtitlesToDownloads(
                    project = targetProject,
                    format = fmt,
                    includeSpeakerPrefixes = includeSpeakers,
                    translationMode = mode,
                    onRequireSafPicker = { _, fallbackFmt ->
                        launchSafSubtitleExport(targetProject, fallbackFmt, includeSpeakers, mode)
                    },
                )
            },
            onSaveAsDocument = { fmt, includeSpeakers, mode ->
                launchSafSubtitleExport(targetProject, fmt, includeSpeakers, mode)
            },
        )
    }

    if (state.showStartLauncher && state.activeDestination == AppDestination.LIBRARY) {
        Box(modifier = Modifier.fillMaxSize()) {
            StartLauncherScreen(
                savedProjects = state.savedProjects,
                onStartLocalProject = viewModel::createProjectFromMedia,
                onStartYouTubeProject = viewModel::createYouTubeProject,
                onStartWebStreamProject = viewModel::createWebStreamProject,
                onStartDirectMediaUrl = viewModel::createDirectMediaUrlProject,
                onOpenSavedProject = viewModel::loadProject,
                onLoadDemoScenario = viewModel::loadDemoScenario,
                subtitlePrepState = state.subtitlePrepState,
                selectedPrepFormat = state.selectedPrepFormat,
                onSelectPrepFormat = viewModel::setSubtitlePrepFormat,
                onGenerateSubtitlesFromUrl = viewModel::generateSubtitlesForUrl,
                onGenerateSubtitlesFromLocal = viewModel::generateSubtitlesForLocalVideo,
                onCancelSubtitlePrep = viewModel::cancelSubtitlePreGeneration,
                onClearSubtitlePrep = viewModel::clearSubtitlePrepState,
                onDownloadPreparedSubtitles = {
                    val ready = state.subtitlePrepState as? SubtitlePrepState.Ready ?: return@StartLauncherScreen
                    val fmt = state.selectedPrepFormat
                    val filename = SubtitleParser.suggestedExportFilename(ready.generatedProject.title, fmt)
                    when (fmt) {
                        SubtitleParser.SubtitleExportFormat.SRT -> exportPrepSrtLauncher.launch(filename)
                        SubtitleParser.SubtitleExportFormat.VTT -> exportPrepVttLauncher.launch(filename)
                        SubtitleParser.SubtitleExportFormat.ASS -> exportPrepAssLauncher.launch(filename)
                    }
                },
                onLaunchPreparedProject = viewModel::launchPreparedProject,
                onExportSavedProjectSubtitles = { proj -> subtitleExportProject = proj },
            )
            if (!state.hasCompletedOnboarding) {
                FirstTimeOnboardingOverlay(
                    onComplete = viewModel::completeOnboarding,
                    onTryDemoScene = {
                        viewModel.completeOnboarding()
                        viewModel.loadDemoScenario()
                    },
                )
            }
        }
        return
    }

    if (state.showScoutBrowserModal && state.project.videoSource.kind == VideoSourceKind.WEB_STREAM) {
        ScoutBrowserInspectorModal(
            webUrl = state.project.videoSource.uriString,
            playbackSpeed = state.playbackSpeed,
            scoutPlayRequest = state.scoutPlayRequest,
            scoutSeekRequest = state.scoutSeekRequest,
            scoutNavCommand = state.scoutWebNavCommand,
            scoutNav = state.scoutWebNav,
            scoutTimeMs = state.scoutTimeMs,
            scoutSessionHolder = scoutSessionHolder,
            onScoutPlaybackState = viewModel::onScoutWebStreamPlaybackState,
            onCapturedCaptions = viewModel::onWebStreamCapturedCaptions,
            onScoutDirectMediaUrl = viewModel::onScoutWebStreamDirectMediaUrl,
            onScoutSniffedMediaStream = viewModel::onWebStreamSniffedMediaStream,
            onScoutAudioSlice = viewModel::onScoutWebStreamAudioSlice,
            onScoutNavChanged = viewModel::onScoutWebNavChanged,
            onScoutBack = viewModel::scoutWebGoBack,
            onScoutForward = viewModel::scoutWebGoForward,
            onScoutRefresh = viewModel::scoutWebRefresh,
            onScoutSyncFromViewer = viewModel::scoutWebSyncFromViewer,
            onScoutResetSource = viewModel::scoutWebResetSource,
            onScoutAllowOnce = viewModel::scoutWebAllowOnce,
            onDismiss = viewModel::closeScoutBrowserModal,
        )
    }

    if (state.isFullscreenTheater) {
        var fullscreenControlsVisible by remember { mutableStateOf(true) }
        LaunchedEffect(fullscreenControlsVisible, state.isPlaying, state.quickPickerEventId) {
            if (fullscreenControlsVisible && state.isPlaying && state.quickPickerEventId == null) {
                delay(3_500L)
                fullscreenControlsVisible = false
            }
        }
        BackHandler {
            if (state.quickPickerEventId != null) {
                viewModel.dismissQuickSpeakerPicker(resumePlayback = true)
            } else {
                viewModel.exitFullscreenTheater()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    fullscreenControlsVisible = !fullscreenControlsVisible
                }
                .testTag("theater_fullscreen_container"),
            contentAlignment = Alignment.Center,
        ) {
            VideoPlayerStage(
                project = state.project,
                exoPlayer = viewModel.exoPlayer,
                currentTimeMs = state.currentTimeMs,
                isPlaying = state.isPlaying,
                isMuted = state.isMuted,
                playbackSpeed = state.playbackSpeed,
                streamPlayRequest = state.streamPlayRequest,
                youtubeSeekRequest = state.youtubeSeekRequest,
                dualInstanceEnabled = state.dualInstanceEnabled,
                hasPendingPlayIntro = state.hasPendingPlayIntro,
                isCinemaIntroActive = state.isCinemaIntroActive,
                introRemainingMs = state.introRemainingMs,
                introTotalDurationMs = state.introTotalDurationMs,
                requiredOpeningWindows = state.requiredOpeningLeadWindows,
                isOpeningSpeakersReady = state.isOpeningSpeakersReady,
                isScoutCatchUpBuffering = state.isScoutCatchUpBuffering,
                onDismissCatchUpBuffering = viewModel::dismissScoutCatchUpBuffering,
                scoutTimeMs = state.scoutTimeMs,
                scoutedUpToMs = state.scoutedUpToMs,
                scoutStatusText = state.scoutStatusText,
                scoutCaptionState = state.scoutCaptionState,
                activeSpeakerToast = state.activeSpeakerToast,
                onDismissSpeakerToast = viewModel::dismissSpeakerToast,
                onOpenCastGlossary = {
                    viewModel.exitFullscreenTheater()
                    inspectorTabRequest = InspectorTab.CAST
                    viewModel.selectDestination(AppDestination.STUDIO)
                },
                scoutPlayRequest = state.scoutPlayRequest,
                scoutSeekRequest = state.scoutSeekRequest,
                showScoutMonitorPip = state.showScoutMonitorPip,
                onToggleScoutMonitorPip = viewModel::toggleScoutMonitorPip,
                onSkipIntro = viewModel::skipCinemaIntro,
                onRetryYouTubeAiTranscript = viewModel::retryYouTubeAiTranscription,
                onOpenAttachCaptions = {
                    viewModel.exitFullscreenTheater()
                    showImportSheet = true
                },
                onSwitchToZeroCloudMode = viewModel::switchToZeroCloudFallback,
                displayMode = state.displayMode,
                placedBalloons = state.placedBalloons,
                liveFaceExclusionRects = state.liveFaceExclusionRects,
                scoutCompletedWindows = state.scoutCompletedWindows,
                scoutTotalWindows = state.scoutTotalWindows,
                onLiveFacesDetected = viewModel::onLiveStageFacesDetected,
                onLiveFrameSnapshot = viewModel::onLiveStageFrameSnapshot,
                isEditMode = state.isEditMode,
                selectedEventId = state.selectedEventId,
                onTogglePlayPause = {
                    fullscreenControlsVisible = true
                    viewModel.togglePlayPause()
                },
                onYouTubePlaybackState = viewModel::onYouTubePlaybackState,
                onYouTubeCaptionTrackStatus = viewModel::onYouTubeCaptionTrackStatus,
                onYouTubeEmbedFallback = viewModel::onYouTubeEmbedFallback,
                onYouTubeTimedTextCaptured = viewModel::onYouTubeTimedTextCaptured,
                onWebStreamPlaybackState = viewModel::onWebStreamPlaybackState,
                onWebStreamCapturedCaptions = viewModel::onWebStreamCapturedCaptions,
                onWebStreamPageMetadata = viewModel::onWebStreamPageMetadata,
                viewerWebNavCommand = state.viewerWebNavCommand,
                onViewerWebNavChanged = viewModel::onViewerWebNavChanged,
                scoutWebNavCommand = state.scoutWebNavCommand,
                onScoutWebNavChanged = viewModel::onScoutWebNavChanged,
                scoutSessionHolder = scoutSessionHolder,
                showScoutBrowserModal = state.showScoutBrowserModal,
                onScoutYouTubePlaybackState = viewModel::onScoutYouTubePlaybackState,
                onScoutWebStreamPlaybackState = viewModel::onScoutWebStreamPlaybackState,
                onScoutWebStreamDirectMediaUrl = viewModel::onScoutWebStreamDirectMediaUrl,
                onWebStreamSniffedMediaStream = viewModel::onWebStreamSniffedMediaStream,
                onScoutWebStreamAudioSlice = viewModel::onScoutWebStreamAudioSlice,
                onTapBalloon = { eventId -> viewModel.openQuickSpeakerPicker(eventId) },
                onTapFaceTrack = viewModel::assignActiveDialogueToFaceTrack,
                onDragBalloonAnchor = viewModel::overrideBalloonAnchor,
                quickPickerEventId = state.quickPickerEventId,
                onQuickAssignSpeaker = { eventId, spkId -> viewModel.quickAssignSpeakerFromPicker(eventId, spkId, false) },
                onQuickAssignSpeakerScoped = viewModel::quickAssignSpeakerFromPicker,
                onQuickNameSpeaker = viewModel::quickNameAndAssignSpeakerFromPicker,
                onQuickMarkOffScreen = viewModel::quickMarkOffScreenFromPicker,
                onQuickToggleMoveMode = {
                    if (!state.isEditMode) viewModel.toggleEditMode()
                },
                onQuickEditDialogue = { eventId ->
                    viewModel.dismissQuickSpeakerPicker(resumePlayback = false)
                    viewModel.exitFullscreenTheater()
                    viewModel.selectEventForEditing(eventId)
                    inspectorTabRequest = InspectorTab.TIMELINE
                    viewModel.selectDestination(AppDestination.STUDIO)
                },
                onQuickHealMoment = {
                    viewModel.dismissQuickSpeakerPicker(resumePlayback = false)
                    viewModel.markHealBeaconAtCurrentTime()
                },
                onDismissQuickPicker = { viewModel.dismissQuickSpeakerPicker(resumePlayback = true) },
                seekGeneration = state.seekGeneration,
                onMeasuredBubbleSize = viewModel::onMeasuredBubbleSize,
                onRenderedBalloonFrame = viewModel::onRenderedBalloonFrame,
                onBalloonEntryStarted = viewModel::onBalloonEntryStarted,
                onViewportOrFontScaleChanged = viewModel::onViewportOrFontScaleChanged,
                measurementEpoch = state.measurementEpoch,
                readinessSnapshot = state.readinessSnapshot,
                onRetryBuffering = viewModel::retryBufferingReadiness,
                modifier = Modifier.fillMaxSize(),
            )

            // Auto-fading Fullscreen Top Controls
            AnimatedVisibility(
                visible = fullscreenControlsVisible || !state.isPlaying,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.project.videoSource.kind == VideoSourceKind.WEB_STREAM) {
                        IconButton(
                            onClick = viewModel::viewerWebGoBack,
                            enabled = state.viewerWebNav.canGoBack,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(CinemaObsidian.copy(alpha = 0.72f))
                                .testTag("theater_web_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Viewer back",
                                tint = if (state.viewerWebNav.canGoBack) Color.White else Color.White.copy(alpha = 0.35f),
                            )
                        }
                        IconButton(
                            onClick = viewModel::viewerWebRefresh,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(CinemaObsidian.copy(alpha = 0.72f))
                                .testTag("theater_web_refresh_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Viewer refresh",
                                tint = Color.White,
                            )
                        }
                    }
                    IconButton(
                        onClick = viewModel::togglePlayPause,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CinemaObsidian.copy(alpha = 0.72f)),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = viewModel::exitFullscreenTheater,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CinemaObsidian.copy(alpha = 0.72f))
                            .testTag("exit_fullscreen_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FullscreenExit,
                            contentDescription = "Exit theater fullscreen",
                            tint = Color.White,
                        )
                    }
                }
            }

            // Auto-fading Fullscreen Bottom Strip: Display Mode Switch + Contextual Heal Beacon when paused
            AnimatedVisibility(
                visible = (fullscreenControlsVisible || !state.isPlaying) && state.quickPickerEventId == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = CinemaObsidian.copy(alpha = 0.82f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val activeMode = state.displayMode.normalizedPrimaryMode()
                            listOf(
                                Triple(CaptionDisplayMode.BALLOONS, Icons.Filled.ChatBubble, "Speech bubbles"),
                                Triple(CaptionDisplayMode.FIXED_ZONES, Icons.Filled.ViewAgenda, "Fixed zones (no tails)"),
                                Triple(CaptionDisplayMode.CLASSIC, Icons.Filled.Subtitles, "Subtitles"),
                                Triple(CaptionDisplayMode.OFF, Icons.Filled.SubtitlesOff, "Dialogue off"),
                            ).forEach { (mode, icon, label) ->
                                val selected = activeMode == mode
                                IconButton(
                                    onClick = { viewModel.setDisplayMode(mode) },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) LensSky else Color.Transparent)
                                        .testTag("theater_mode_${mode.name.lowercase()}"),
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = label,
                                        tint = if (selected) CinemaObsidian else Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (!state.isPlaying && state.readinessSnapshot.isIntentionallyPausedByUser) {
                        HealThisMomentPill(
                            onClick = { viewModel.markHealBeaconAtCurrentTime() },
                            isCompact = true,
                        )
                    }
                }
            }
        }
        return
    }

    if (state.activeDestination != AppDestination.WATCH) {
        BackHandler {
            viewModel.selectDestination(AppDestination.WATCH)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaObsidian),
    ) {
        val isExpandedLayout = maxWidth >= 820.dp

        val navDestinations = listOf(
            Triple(AppDestination.WATCH, "Watch", Icons.Filled.PlayCircle),
            Triple(AppDestination.LIBRARY, "Library", Icons.Filled.VideoLibrary),
            Triple(AppDestination.STUDIO, "Studio", Icons.Filled.Tune),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            if (isExpandedLayout) {
                NavigationRail(
                    containerColor = CinemaSurface,
                    modifier = Modifier
                        .fillMaxHeight()
                        .testTag("adaptive_navigation_rail"),
                ) {
                    Spacer(Modifier.size(12.dp))
                    navDestinations.forEach { (dest, label, icon) ->
                        val selected = state.activeDestination == dest
                        NavigationRailItem(
                            selected = selected,
                            onClick = { viewModel.selectDestination(dest) },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = CinemaObsidian,
                                selectedTextColor = LensSky,
                                indicatorColor = LensSky,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.testTag("nav_rail_${dest.name.lowercase()}"),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = false,
                        onClick = { showStyleSheet = true },
                        icon = { Icon(Icons.Filled.Palette, contentDescription = "Appearance & Accessibility") },
                        label = { Text("Style", fontSize = 11.sp) },
                        modifier = Modifier.testTag("nav_rail_style"),
                    )
                    Spacer(Modifier.size(12.dp))
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                contentWindowInsets = WindowInsets.safeDrawing,
                containerColor = CinemaObsidian,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = CinemaObsidian,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        title = {
                            Text(
                                text = when (state.activeDestination) {
                                    AppDestination.LIBRARY -> "Library"
                                    AppDestination.STUDIO -> "Studio · ${state.project.title}"
                                    AppDestination.WATCH -> state.project.title
                                },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = {
                            if (state.activeDestination == AppDestination.WATCH &&
                                state.project.videoSource.kind != VideoSourceKind.DEMO_SCENARIO
                            ) {
                                ReadinessStatusPill(
                                    readiness = state.humanReadiness,
                                    onClick = {
                                        inspectorTabRequest = InspectorTab.DIAGNOSTICS
                                        viewModel.selectDestination(AppDestination.STUDIO)
                                    },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }

                            IconButton(
                                onClick = { showStyleSheet = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_appearance_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Palette,
                                    contentDescription = "Appearance and accessibility settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            IconButton(
                                onClick = { showImportSheet = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_media_import_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AddCircleOutline,
                                    contentDescription = "Add video or captions",
                                    tint = LensSky,
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!isExpandedLayout) {
                        NavigationBar(
                            containerColor = CinemaSurface,
                            tonalElevation = 0.dp,
                            modifier = Modifier.testTag("compact_navigation_bar"),
                        ) {
                            navDestinations.forEach { (dest, label, icon) ->
                                val selected = state.activeDestination == dest
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.selectDestination(dest) },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label, fontSize = 11.5.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = CinemaObsidian,
                                        selectedTextColor = LensSky,
                                        indicatorColor = LensSky,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    modifier = Modifier.testTag("nav_bar_${dest.name.lowercase()}"),
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    when (state.activeDestination) {
                        AppDestination.LIBRARY -> {
                            StartLauncherScreen(
                                savedProjects = state.savedProjects,
                                onStartLocalProject = viewModel::createProjectFromMedia,
                                onStartYouTubeProject = viewModel::createYouTubeProject,
                                onStartWebStreamProject = viewModel::createWebStreamProject,
                                onStartDirectMediaUrl = viewModel::createDirectMediaUrlProject,
                                onOpenSavedProject = viewModel::loadProject,
                                onLoadDemoScenario = viewModel::loadDemoScenario,
                                subtitlePrepState = state.subtitlePrepState,
                                selectedPrepFormat = state.selectedPrepFormat,
                                onSelectPrepFormat = viewModel::setSubtitlePrepFormat,
                                onGenerateSubtitlesFromUrl = viewModel::generateSubtitlesForUrl,
                                onGenerateSubtitlesFromLocal = viewModel::generateSubtitlesForLocalVideo,
                                onCancelSubtitlePrep = viewModel::cancelSubtitlePreGeneration,
                                onClearSubtitlePrep = viewModel::clearSubtitlePrepState,
                                onDownloadPreparedSubtitles = {
                                    val ready = state.subtitlePrepState as? SubtitlePrepState.Ready ?: return@StartLauncherScreen
                                    val fmt = state.selectedPrepFormat
                                    val filename = SubtitleParser.suggestedExportFilename(ready.generatedProject.title, fmt)
                                    when (fmt) {
                                        SubtitleParser.SubtitleExportFormat.SRT -> exportPrepSrtLauncher.launch(filename)
                                        SubtitleParser.SubtitleExportFormat.VTT -> exportPrepVttLauncher.launch(filename)
                                        SubtitleParser.SubtitleExportFormat.ASS -> exportPrepAssLauncher.launch(filename)
                                    }
                                },
                                onLaunchPreparedProject = viewModel::launchPreparedProject,
                                onExportSavedProjectSubtitles = { proj -> subtitleExportProject = proj },
                                embeddedInNavigation = true,
                            )
                        }

                        AppDestination.STUDIO -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                ToolsQuickSettingsHeader(
                                    state = state,
                                    expandScoutDiag = expandScoutDiagInDrawer,
                                    onToggleExpandScoutDiag = { expandScoutDiagInDrawer = !expandScoutDiagInDrawer },
                                    onToggleEditMode = viewModel::toggleEditMode,
                                    onCyclePlaybackSpeed = viewModel::cyclePlaybackSpeed,
                                    onCycleTranslationMode = {
                                        val next = when (state.project.translationMode) {
                                            TranslationMode.NATURAL -> TranslationMode.LITERAL
                                            TranslationMode.LITERAL -> TranslationMode.LEARNING
                                            TranslationMode.LEARNING -> TranslationMode.NATURAL
                                        }
                                        viewModel.setTranslationMode(next)
                                    },
                                    onCycleSpeakerLabels = viewModel::cycleSpeakerLabelMode,
                                    onToggleBubbleStyle = {
                                        val nextStyle = when (state.styleSettings.visualStyle) {
                                            BubbleVisualStyle.CINEMA_SUBTLE -> BubbleVisualStyle.MANGA_INK
                                            BubbleVisualStyle.MANGA_INK -> BubbleVisualStyle.STREAMING_CLEAN
                                            BubbleVisualStyle.STREAMING_CLEAN -> BubbleVisualStyle.READING_COMFORT
                                            BubbleVisualStyle.READING_COMFORT -> BubbleVisualStyle.CINEMA_SUBTLE
                                        }
                                        viewModel.updateStyleSettings(state.styleSettings.copy(visualStyle = nextStyle))
                                    },
                                    onOpenStyleSheet = { showStyleSheet = true },
                                    onOpenProjectAndPrivacy = { showPrivacySheet = true },
                                    onOpenSubtitleExport = { subtitleExportProject = state.project },
                                    onRetryAi = viewModel::retryYouTubeAiTranscription,
                                    onOpenAttachSrt = { showImportSheet = true },
                                    onSelectQuotaMode = viewModel::setQuotaSaverMode,
                                    onSwitchToZeroCloud = viewModel::switchToZeroCloudFallback,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                TimelineSubtitleEditor(
                                    project = state.project,
                                    currentTimeMs = state.currentTimeMs,
                                    selectedEventId = state.selectedEventId,
                                    isTranslatingAi = state.isTranslatingAi,
                                    canUndo = state.canUndo,
                                    glossaryEntries = state.glossaryEntries,
                                    initialTab = inspectorTabRequest,
                                    onSelectEvent = viewModel::selectEventForEditing,
                                    onSeekTo = { ms ->
                                        viewModel.seekTo(ms)
                                        viewModel.selectDestination(AppDestination.WATCH)
                                    },
                                    onAssignSpeaker = viewModel::assignSpeakerToEvent,
                                    onUpdateEvent = viewModel::updateDialogueEvent,
                                    onMergeWithNext = viewModel::mergeWithNextEvent,
                                    onSplitEvent = viewModel::splitEvent,
                                    onResetAnchor = viewModel::resetBalloonAnchor,
                                    onRenameSpeaker = viewModel::renameSpeaker,
                                    onTranslateWithAi = { viewModel.translateCurrentProjectWithAi() },
                                    onAutoTranscribeAudio = viewModel::autoTranscribeCurrentVideoWithAi,
                                    onAnalyzeSpeakers = { viewModel.analyzeSpeakersWithContextAndAudio() },
                                    onAddManualLine = viewModel::addManualDialogueLine,
                                    onUndo = viewModel::undoLastEdit,
                                    onShiftTiming = viewModel::shiftAllDialogueTimings,
                                    onAddGlossaryEntry = viewModel::addGlossaryEntry,
                                    onRemoveGlossaryEntry = viewModel::removeGlossaryEntry,
                                    onExportSubtitles = { subtitleExportProject = state.project },
                                    onMergeSpeakers = viewModel::mergeSpeakers,
                                    onMarkHealBeaconHere = { scope -> viewModel.markHealBeaconAtCurrentTime(scope = scope) },
                                    onRetryHealBeacon = viewModel::retryHealBeacon,
                                    onUndoHealBeacon = viewModel::undoHealBeacon,
                                    onDismissHealBeacon = viewModel::dismissHealBeacon,
                                    onChapterHealAt = { ts ->
                                        viewModel.seekTo(ts)
                                        viewModel.markHealBeaconAtCurrentTime(scope = HealScope.CHAPTER)
                                    },
                                    mediaCapabilities = state.mediaCapabilities,
                                    transcriptDiagnostics = state.transcriptDiagnostics,
                                    quotaSaverMode = state.quotaSaverMode,
                                    onSelectQuotaMode = viewModel::setQuotaSaverMode,
                                    onRetryAiTranscription = viewModel::retryYouTubeAiTranscription,
                                    onAttachSubtitleFile = { showImportSheet = true },
                                    visualStabilitySnapshot = state.visualStabilitySnapshot,
                                    readinessSnapshot = state.readinessSnapshot,
                                    sentinelSnapshot = state.sentinelSnapshot,
                                    releaseSnapshot = state.releaseSnapshot,
                                    youtubeCapabilities = state.youtubeCapabilities,
                                    onInspectChapterNow = viewModel::inspectChapterNow,
                                    onResetStabilityDiagnostics = viewModel::resetVisualStabilityDiagnostics,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                            }
                        }

                        AppDestination.WATCH -> {
                            if (isExpandedLayout) {
                                // Tablet / Foldable Canonical Layout: Video + Controls Left, Calm Inspector/Dialogue Right
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1.45f)
                                            .fillMaxHeight(),
                                    ) {
                                        StatusBannerStrip(
                                            banner = state.statusBanner,
                                            onDismiss = viewModel::clearStatusBanner,
                                        )

                                        VideoPlayerStage(
                                            project = state.project,
                                            exoPlayer = viewModel.exoPlayer,
                                            currentTimeMs = state.currentTimeMs,
                                            isPlaying = state.isPlaying,
                                            isMuted = state.isMuted,
                                            playbackSpeed = state.playbackSpeed,
                                            streamPlayRequest = state.streamPlayRequest,
                                            youtubeSeekRequest = state.youtubeSeekRequest,
                                            dualInstanceEnabled = state.dualInstanceEnabled,
                                            hasPendingPlayIntro = state.hasPendingPlayIntro,
                                            isCinemaIntroActive = state.isCinemaIntroActive,
                                            introRemainingMs = state.introRemainingMs,
                                            introTotalDurationMs = state.introTotalDurationMs,
                                            requiredOpeningWindows = state.requiredOpeningLeadWindows,
                                            isOpeningSpeakersReady = state.isOpeningSpeakersReady,
                                            isScoutCatchUpBuffering = state.isScoutCatchUpBuffering,
                                            onDismissCatchUpBuffering = viewModel::dismissScoutCatchUpBuffering,
                                            scoutTimeMs = state.scoutTimeMs,
                                            scoutedUpToMs = state.scoutedUpToMs,
                                            scoutStatusText = state.scoutStatusText,
                                            scoutCaptionState = state.scoutCaptionState,
                                            activeSpeakerToast = state.activeSpeakerToast,
                                            onDismissSpeakerToast = viewModel::dismissSpeakerToast,
                                            onOpenCastGlossary = { inspectorTabRequest = InspectorTab.CAST },
                                            scoutPlayRequest = state.scoutPlayRequest,
                                            scoutSeekRequest = state.scoutSeekRequest,
                                            showScoutMonitorPip = state.showScoutMonitorPip,
                                            onToggleScoutMonitorPip = viewModel::toggleScoutMonitorPip,
                                            onSkipIntro = viewModel::skipCinemaIntro,
                                            onRetryYouTubeAiTranscript = viewModel::retryYouTubeAiTranscription,
                                            onOpenAttachCaptions = { showImportSheet = true },
                                            onSwitchToZeroCloudMode = viewModel::switchToZeroCloudFallback,
                                            displayMode = state.displayMode,
                                            placedBalloons = state.placedBalloons,
                                            liveFaceExclusionRects = state.liveFaceExclusionRects,
                                            scoutCompletedWindows = state.scoutCompletedWindows,
                                            scoutTotalWindows = state.scoutTotalWindows,
                                            onLiveFacesDetected = viewModel::onLiveStageFacesDetected,
                                            onLiveFrameSnapshot = viewModel::onLiveStageFrameSnapshot,
                                            isEditMode = state.isEditMode,
                                            selectedEventId = state.selectedEventId,
                                            onTogglePlayPause = viewModel::togglePlayPause,
                                            onYouTubePlaybackState = viewModel::onYouTubePlaybackState,
                                            onYouTubeCaptionTrackStatus = viewModel::onYouTubeCaptionTrackStatus,
                                            onYouTubeEmbedFallback = viewModel::onYouTubeEmbedFallback,
                                            onYouTubeTimedTextCaptured = viewModel::onYouTubeTimedTextCaptured,
                                            onWebStreamPlaybackState = viewModel::onWebStreamPlaybackState,
                                            onWebStreamCapturedCaptions = viewModel::onWebStreamCapturedCaptions,
                                            onWebStreamPageMetadata = viewModel::onWebStreamPageMetadata,
                                            viewerWebNavCommand = state.viewerWebNavCommand,
                                            onViewerWebNavChanged = viewModel::onViewerWebNavChanged,
                                            scoutWebNavCommand = state.scoutWebNavCommand,
                                            onScoutWebNavChanged = viewModel::onScoutWebNavChanged,
                                            scoutSessionHolder = scoutSessionHolder,
                                            showScoutBrowserModal = state.showScoutBrowserModal,
                                            onScoutYouTubePlaybackState = viewModel::onScoutYouTubePlaybackState,
                                            onScoutWebStreamPlaybackState = viewModel::onScoutWebStreamPlaybackState,
                                            onScoutWebStreamDirectMediaUrl = viewModel::onScoutWebStreamDirectMediaUrl,
                                            onWebStreamSniffedMediaStream = viewModel::onWebStreamSniffedMediaStream,
                                            onScoutWebStreamAudioSlice = viewModel::onScoutWebStreamAudioSlice,
                                            onTapBalloon = { eventId -> viewModel.openQuickSpeakerPicker(eventId) },
                                            onTapFaceTrack = viewModel::assignActiveDialogueToFaceTrack,
                                            onDragBalloonAnchor = viewModel::overrideBalloonAnchor,
                                            quickPickerEventId = state.quickPickerEventId,
                                            onQuickAssignSpeaker = { eventId, spkId -> viewModel.quickAssignSpeakerFromPicker(eventId, spkId, false) },
                                            onQuickAssignSpeakerScoped = viewModel::quickAssignSpeakerFromPicker,
                                            onQuickNameSpeaker = viewModel::quickNameAndAssignSpeakerFromPicker,
                                            onQuickMarkOffScreen = viewModel::quickMarkOffScreenFromPicker,
                                            onQuickToggleMoveMode = {
                                                if (!state.isEditMode) viewModel.toggleEditMode()
                                            },
                                            onQuickEditDialogue = { eventId ->
                                                viewModel.dismissQuickSpeakerPicker(resumePlayback = false)
                                                viewModel.selectEventForEditing(eventId)
                                                inspectorTabRequest = InspectorTab.TIMELINE
                                            },
                                            onQuickHealMoment = {
                                                viewModel.dismissQuickSpeakerPicker(resumePlayback = false)
                                                viewModel.markHealBeaconAtCurrentTime()
                                            },
                                            onDismissQuickPicker = { viewModel.dismissQuickSpeakerPicker(resumePlayback = true) },
                                            seekGeneration = state.seekGeneration,
                                            onMeasuredBubbleSize = viewModel::onMeasuredBubbleSize,
                                            onRenderedBalloonFrame = viewModel::onRenderedBalloonFrame,
                                            onBalloonEntryStarted = viewModel::onBalloonEntryStarted,
                                            onViewportOrFontScaleChanged = viewModel::onViewportOrFontScaleChanged,
                                            measurementEpoch = state.measurementEpoch,
                                            readinessSnapshot = state.readinessSnapshot,
                                            onRetryBuffering = viewModel::retryBufferingReadiness,
                                            modifier = Modifier.weight(1f),
                                        )

                                        PlayerControlsBar(
                                            project = state.project,
                                            currentTimeMs = state.currentTimeMs,
                                            isPlaying = state.isPlaying,
                                            isIntentionallyPausedByUser = state.readinessSnapshot.isIntentionallyPausedByUser,
                                            willAutoResumeWhenReady = state.readinessSnapshot.willAutoResumeWhenReady,
                                            isMuted = state.isMuted,
                                            playbackSpeed = state.playbackSpeed,
                                            isFullscreenTheater = state.isFullscreenTheater,
                                            displayMode = state.displayMode,
                                            styleSettings = state.styleSettings,
                                            isEditMode = state.isEditMode,
                                            dualInstanceEnabled = state.dualInstanceEnabled,
                                            scoutedUpToMs = state.scoutedUpToMs,
                                            bufferedRanges = state.bufferedWindowRanges,
                                            failedRanges = state.coverageSummary.failedRanges,
                                            analysisStatus = state.analysisStatus,
                                            activeChapterTitle = state.activeChapter?.title,
                                            onTogglePlayPause = viewModel::togglePlayPause,
                                            onSeekTo = viewModel::seekTo,
                                            onScrubberPreviewStart = viewModel::onScrubberPreviewStart,
                                            onScrubberPreviewEnd = viewModel::onScrubberPreviewEnd,
                                            onToggleMute = viewModel::toggleMute,
                                            onCyclePlaybackSpeed = viewModel::cyclePlaybackSpeed,
                                            onToggleFullscreenTheater = viewModel::toggleFullscreenTheater,
                                            onToggleDualInstance = viewModel::toggleDualInstanceLookahead,
                                            onSelectDisplayMode = viewModel::setDisplayMode,
                                            onToggleEditMode = viewModel::toggleEditMode,
                                            onOpenStyleSheet = { showStyleSheet = true },
                                            onOpenCastDrawer = { inspectorTabRequest = InspectorTab.CAST },
                                            onOpenToolsDrawer = {
                                                expandScoutDiagInDrawer = false
                                                showToolsDrawer = true
                                            },
                                            onHealThisMoment = { viewModel.markHealBeaconAtCurrentTime() },
                                            onInspectHealBeacon = viewModel::inspectHealBeacon,
                                            showDrawerTriggers = false,
                                        )

                                        if (state.project.videoSource.kind == VideoSourceKind.WEB_STREAM) {
                                            WebBrowserControlStrip(
                                                seedUrl = state.project.videoSource.uriString,
                                                viewerNav = state.viewerWebNav,
                                                scoutNav = state.scoutWebNav,
                                                scoutTimeMs = state.scoutTimeMs,
                                                dualInstanceEnabled = state.dualInstanceEnabled,
                                                onViewerBack = viewModel::viewerWebGoBack,
                                                onViewerForward = viewModel::viewerWebGoForward,
                                                onViewerRefresh = viewModel::viewerWebRefresh,
                                                onViewerResetSource = viewModel::viewerWebResetSource,
                                                onViewerAllowOnce = viewModel::viewerWebAllowOnce,
                                                onOpenScoutModal = viewModel::openScoutBrowserModal,
                                            )
                                        }
                                    }

                                    TimelineSubtitleEditor(
                                        project = state.project,
                                        currentTimeMs = state.currentTimeMs,
                                        selectedEventId = state.selectedEventId,
                                        isTranslatingAi = state.isTranslatingAi,
                                        canUndo = state.canUndo,
                                        glossaryEntries = state.glossaryEntries,
                                        initialTab = inspectorTabRequest,
                                        onSelectEvent = viewModel::selectEventForEditing,
                                        onSeekTo = viewModel::seekTo,
                                        onAssignSpeaker = viewModel::assignSpeakerToEvent,
                                        onUpdateEvent = viewModel::updateDialogueEvent,
                                        onMergeWithNext = viewModel::mergeWithNextEvent,
                                        onSplitEvent = viewModel::splitEvent,
                                        onResetAnchor = viewModel::resetBalloonAnchor,
                                        onRenameSpeaker = viewModel::renameSpeaker,
                                        onTranslateWithAi = { viewModel.translateCurrentProjectWithAi() },
                                        onAutoTranscribeAudio = viewModel::autoTranscribeCurrentVideoWithAi,
                                        onAnalyzeSpeakers = { viewModel.analyzeSpeakersWithContextAndAudio() },
                                        onAddManualLine = viewModel::addManualDialogueLine,
                                        onUndo = viewModel::undoLastEdit,
                                        onShiftTiming = viewModel::shiftAllDialogueTimings,
                                        onAddGlossaryEntry = viewModel::addGlossaryEntry,
                                        onRemoveGlossaryEntry = viewModel::removeGlossaryEntry,
                                        onExportSubtitles = { subtitleExportProject = state.project },
                                        onMergeSpeakers = viewModel::mergeSpeakers,
                                        onMarkHealBeaconHere = { scope -> viewModel.markHealBeaconAtCurrentTime(scope = scope) },
                                        onRetryHealBeacon = viewModel::retryHealBeacon,
                                        onUndoHealBeacon = viewModel::undoHealBeacon,
                                        onDismissHealBeacon = viewModel::dismissHealBeacon,
                                        onChapterHealAt = { ts ->
                                            viewModel.seekTo(ts)
                                            viewModel.markHealBeaconAtCurrentTime(scope = HealScope.CHAPTER)
                                        },
                                        mediaCapabilities = state.mediaCapabilities,
                                        transcriptDiagnostics = state.transcriptDiagnostics,
                                        quotaSaverMode = state.quotaSaverMode,
                                        onSelectQuotaMode = viewModel::setQuotaSaverMode,
                                        onRetryAiTranscription = viewModel::retryYouTubeAiTranscription,
                                        onAttachSubtitleFile = { showImportSheet = true },
                                        visualStabilitySnapshot = state.visualStabilitySnapshot,
                                        readinessSnapshot = state.readinessSnapshot,
                                        sentinelSnapshot = state.sentinelSnapshot,
                                        releaseSnapshot = state.releaseSnapshot,
                                        youtubeCapabilities = state.youtubeCapabilities,
                                        onInspectChapterNow = viewModel::inspectChapterNow,
                                        onResetStabilityDiagnostics = viewModel::resetVisualStabilityDiagnostics,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                    )
                                }
                            } else {
                                // Mobile / Compact Theater-First Layout: Dominant Video + Serene Controls + Collapsible Dialogue Dock
                                Column(modifier = Modifier.fillMaxSize()) {
                                    StatusBannerStrip(
                                        banner = state.statusBanner,
                                        onDismiss = viewModel::clearStatusBanner,
                                    )

                                    VideoPlayerStage(
                                        project = state.project,
                                        exoPlayer = viewModel.exoPlayer,
                                        currentTimeMs = state.currentTimeMs,
                                        isPlaying = state.isPlaying,
                                        isMuted = state.isMuted,
                                        playbackSpeed = state.playbackSpeed,
                                        streamPlayRequest = state.streamPlayRequest,
                                        youtubeSeekRequest = state.youtubeSeekRequest,
                                        dualInstanceEnabled = state.dualInstanceEnabled,
                                        hasPendingPlayIntro = state.hasPendingPlayIntro,
                                        isCinemaIntroActive = state.isCinemaIntroActive,
                                        introRemainingMs = state.introRemainingMs,
                                        introTotalDurationMs = state.introTotalDurationMs,
                                        requiredOpeningWindows = state.requiredOpeningLeadWindows,
                                        isOpeningSpeakersReady = state.isOpeningSpeakersReady,
                                        isScoutCatchUpBuffering = state.isScoutCatchUpBuffering,
                                        onDismissCatchUpBuffering = viewModel::dismissScoutCatchUpBuffering,
                                        scoutTimeMs = state.scoutTimeMs,
                                        scoutedUpToMs = state.scoutedUpToMs,
                                        scoutStatusText = state.scoutStatusText,
                                        scoutCaptionState = state.scoutCaptionState,
                                        activeSpeakerToast = state.activeSpeakerToast,
                                        onDismissSpeakerToast = viewModel::dismissSpeakerToast,
                                        onOpenCastGlossary = {
                                            inspectorTabRequest = InspectorTab.CAST
                                            viewModel.selectDestination(AppDestination.STUDIO)
                                        },
                                        scoutPlayRequest = state.scoutPlayRequest,
                                        scoutSeekRequest = state.scoutSeekRequest,
                                        showScoutMonitorPip = state.showScoutMonitorPip,
                                        onToggleScoutMonitorPip = viewModel::toggleScoutMonitorPip,
                                        onSkipIntro = viewModel::skipCinemaIntro,
                                        onRetryYouTubeAiTranscript = viewModel::retryYouTubeAiTranscription,
                                        onOpenAttachCaptions = { showImportSheet = true },
                                        onSwitchToZeroCloudMode = viewModel::switchToZeroCloudFallback,
                                        displayMode = state.displayMode,
                                        placedBalloons = state.placedBalloons,
                                        liveFaceExclusionRects = state.liveFaceExclusionRects,
                                        scoutCompletedWindows = state.scoutCompletedWindows,
                                        scoutTotalWindows = state.scoutTotalWindows,
                                        onLiveFacesDetected = viewModel::onLiveStageFacesDetected,
                                        onLiveFrameSnapshot = viewModel::onLiveStageFrameSnapshot,
                                        isEditMode = state.isEditMode,
                                        selectedEventId = state.selectedEventId,
                                        onTogglePlayPause = viewModel::togglePlayPause,
                                        onYouTubePlaybackState = viewModel::onYouTubePlaybackState,
                                        onYouTubeCaptionTrackStatus = viewModel::onYouTubeCaptionTrackStatus,
                                        onYouTubeEmbedFallback = viewModel::onYouTubeEmbedFallback,
                                        onYouTubeTimedTextCaptured = viewModel::onYouTubeTimedTextCaptured,
                                        onWebStreamPlaybackState = viewModel::onWebStreamPlaybackState,
                                        onWebStreamCapturedCaptions = viewModel::onWebStreamCapturedCaptions,
                                        onWebStreamPageMetadata = viewModel::onWebStreamPageMetadata,
                                        viewerWebNavCommand = state.viewerWebNavCommand,
                                        onViewerWebNavChanged = viewModel::onViewerWebNavChanged,
                                        scoutWebNavCommand = state.scoutWebNavCommand,
                                        onScoutWebNavChanged = viewModel::onScoutWebNavChanged,
                                        scoutSessionHolder = scoutSessionHolder,
                                        showScoutBrowserModal = state.showScoutBrowserModal,
                                        onScoutYouTubePlaybackState = viewModel::onScoutYouTubePlaybackState,
                                        onScoutWebStreamPlaybackState = viewModel::onScoutWebStreamPlaybackState,
                                        onScoutWebStreamDirectMediaUrl = viewModel::onScoutWebStreamDirectMediaUrl,
                                        onWebStreamSniffedMediaStream = viewModel::onWebStreamSniffedMediaStream,
                                        onScoutWebStreamAudioSlice = viewModel::onScoutWebStreamAudioSlice,
                                        onTapBalloon = { eventId -> viewModel.openQuickSpeakerPicker(eventId) },
                                        onTapFaceTrack = viewModel::assignActiveDialogueToFaceTrack,
                                        onDragBalloonAnchor = viewModel::overrideBalloonAnchor,
                                        quickPickerEventId = state.quickPickerEventId,
                                        onQuickAssignSpeaker = { eventId, spkId -> viewModel.quickAssignSpeakerFromPicker(eventId, spkId, false) },
                                        onQuickAssignSpeakerScoped = viewModel::quickAssignSpeakerFromPicker,
                                        onQuickNameSpeaker = viewModel::quickNameAndAssignSpeakerFromPicker,
                                        onQuickMarkOffScreen = viewModel::quickMarkOffScreenFromPicker,
                                        onQuickToggleMoveMode = {
                                            if (!state.isEditMode) viewModel.toggleEditMode()
                                        },
                                        onQuickEditDialogue = { eventId ->
                                            viewModel.dismissQuickSpeakerPicker(resumePlayback = false)
                                            viewModel.selectEventForEditing(eventId)
                                            inspectorTabRequest = InspectorTab.TIMELINE
                                            viewModel.selectDestination(AppDestination.STUDIO)
                                        },
                                        onQuickHealMoment = {
                                            viewModel.dismissQuickSpeakerPicker(resumePlayback = false)
                                            viewModel.markHealBeaconAtCurrentTime()
                                        },
                                        onDismissQuickPicker = { viewModel.dismissQuickSpeakerPicker(resumePlayback = true) },
                                        seekGeneration = state.seekGeneration,
                                        onMeasuredBubbleSize = viewModel::onMeasuredBubbleSize,
                                        onRenderedBalloonFrame = viewModel::onRenderedBalloonFrame,
                                        onBalloonEntryStarted = viewModel::onBalloonEntryStarted,
                                        onViewportOrFontScaleChanged = viewModel::onViewportOrFontScaleChanged,
                                        measurementEpoch = state.measurementEpoch,
                                        readinessSnapshot = state.readinessSnapshot,
                                        onRetryBuffering = viewModel::retryBufferingReadiness,
                                    )

                                    PlayerControlsBar(
                                        project = state.project,
                                        currentTimeMs = state.currentTimeMs,
                                        isPlaying = state.isPlaying,
                                        isIntentionallyPausedByUser = state.readinessSnapshot.isIntentionallyPausedByUser,
                                        willAutoResumeWhenReady = state.readinessSnapshot.willAutoResumeWhenReady,
                                        isMuted = state.isMuted,
                                        playbackSpeed = state.playbackSpeed,
                                        isFullscreenTheater = state.isFullscreenTheater,
                                        displayMode = state.displayMode,
                                        styleSettings = state.styleSettings,
                                        isEditMode = state.isEditMode,
                                        dualInstanceEnabled = state.dualInstanceEnabled,
                                        scoutedUpToMs = state.scoutedUpToMs,
                                        bufferedRanges = state.bufferedWindowRanges,
                                        failedRanges = state.coverageSummary.failedRanges,
                                        analysisStatus = state.analysisStatus,
                                        activeChapterTitle = state.activeChapter?.title,
                                        onTogglePlayPause = viewModel::togglePlayPause,
                                        onSeekTo = viewModel::seekTo,
                                        onScrubberPreviewStart = viewModel::onScrubberPreviewStart,
                                        onScrubberPreviewEnd = viewModel::onScrubberPreviewEnd,
                                        onToggleMute = viewModel::toggleMute,
                                        onCyclePlaybackSpeed = viewModel::cyclePlaybackSpeed,
                                        onToggleFullscreenTheater = viewModel::toggleFullscreenTheater,
                                        onToggleDualInstance = viewModel::toggleDualInstanceLookahead,
                                        onSelectDisplayMode = viewModel::setDisplayMode,
                                        onToggleEditMode = viewModel::toggleEditMode,
                                        onOpenStyleSheet = { showStyleSheet = true },
                                        onOpenCastDrawer = {
                                            inspectorTabRequest = InspectorTab.CAST
                                            viewModel.selectDestination(AppDestination.STUDIO)
                                        },
                                        onOpenToolsDrawer = {
                                            inspectorTabRequest = InspectorTab.TIMELINE
                                            viewModel.selectDestination(AppDestination.STUDIO)
                                        },
                                        onHealThisMoment = { viewModel.markHealBeaconAtCurrentTime() },
                                        onInspectHealBeacon = viewModel::inspectHealBeacon,
                                    )

                                    if (state.project.videoSource.kind == VideoSourceKind.WEB_STREAM) {
                                        WebBrowserControlStrip(
                                            seedUrl = state.project.videoSource.uriString,
                                            viewerNav = state.viewerWebNav,
                                            scoutNav = state.scoutWebNav,
                                            scoutTimeMs = state.scoutTimeMs,
                                            dualInstanceEnabled = state.dualInstanceEnabled,
                                            onViewerBack = viewModel::viewerWebGoBack,
                                            onViewerForward = viewModel::viewerWebGoForward,
                                            onViewerRefresh = viewModel::viewerWebRefresh,
                                            onViewerResetSource = viewModel::viewerWebResetSource,
                                            onViewerAllowOnce = viewModel::viewerWebAllowOnce,
                                            onOpenScoutModal = viewModel::openScoutBrowserModal,
                                        )
                                    }

                                    // Collapsible Ambient Now-Speaking Dock (calm breathing room below controls)
                                    AmbientNowSpeakingDock(
                                        state = state,
                                        isExpanded = state.isDialoguePanelExpanded,
                                        onToggleExpanded = viewModel::toggleDialoguePanelExpanded,
                                        onTapActiveCue = { eventId -> viewModel.openQuickSpeakerPicker(eventId) },
                                        onOpenCastTab = {
                                            inspectorTabRequest = InspectorTab.CAST
                                            viewModel.selectDestination(AppDestination.STUDIO)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Unified Slide-Up Tools & Inspector Drawer (holds Style, Speed, Translation Mode, Names, Edit Mode, Scout, Lines, Cast, Export)
    if (showToolsDrawer) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showToolsDrawer = false },
            sheetState = sheetState,
            containerColor = CinemaObsidian,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .navigationBarsPadding(),
            ) {
                ToolsQuickSettingsHeader(
                    state = state,
                    expandScoutDiag = expandScoutDiagInDrawer,
                    onToggleExpandScoutDiag = { expandScoutDiagInDrawer = !expandScoutDiagInDrawer },
                    onToggleEditMode = viewModel::toggleEditMode,
                    onCyclePlaybackSpeed = viewModel::cyclePlaybackSpeed,
                    onCycleTranslationMode = {
                        val next = when (state.project.translationMode) {
                            TranslationMode.NATURAL -> TranslationMode.LITERAL
                            TranslationMode.LITERAL -> TranslationMode.LEARNING
                            TranslationMode.LEARNING -> TranslationMode.NATURAL
                        }
                        viewModel.setTranslationMode(next)
                    },
                    onCycleSpeakerLabels = viewModel::cycleSpeakerLabelMode,
                    onToggleBubbleStyle = {
                        val nextStyle = when (state.styleSettings.visualStyle) {
                            BubbleVisualStyle.CINEMA_SUBTLE -> BubbleVisualStyle.MANGA_INK
                            BubbleVisualStyle.MANGA_INK -> BubbleVisualStyle.STREAMING_CLEAN
                            BubbleVisualStyle.STREAMING_CLEAN -> BubbleVisualStyle.READING_COMFORT
                            BubbleVisualStyle.READING_COMFORT -> BubbleVisualStyle.CINEMA_SUBTLE
                        }
                        viewModel.updateStyleSettings(state.styleSettings.copy(visualStyle = nextStyle))
                    },
                    onOpenStyleSheet = {
                        showToolsDrawer = false
                        showStyleSheet = true
                    },
                    onOpenProjectAndPrivacy = {
                        showToolsDrawer = false
                        showPrivacySheet = true
                    },
                    onOpenSubtitleExport = {
                        showToolsDrawer = false
                        subtitleExportProject = state.project
                    },
                    onRetryAi = viewModel::retryYouTubeAiTranscription,
                    onOpenAttachSrt = {
                        showToolsDrawer = false
                        showImportSheet = true
                    },
                    onSelectQuotaMode = viewModel::setQuotaSaverMode,
                    onSwitchToZeroCloud = viewModel::switchToZeroCloudFallback,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                TimelineSubtitleEditor(
                    project = state.project,
                    currentTimeMs = state.currentTimeMs,
                    selectedEventId = state.selectedEventId,
                    isTranslatingAi = state.isTranslatingAi,
                    canUndo = state.canUndo,
                    glossaryEntries = state.glossaryEntries,
                    initialTab = inspectorTabRequest,
                    onSelectEvent = viewModel::selectEventForEditing,
                    onSeekTo = viewModel::seekTo,
                    onAssignSpeaker = viewModel::assignSpeakerToEvent,
                    onUpdateEvent = viewModel::updateDialogueEvent,
                    onMergeWithNext = viewModel::mergeWithNextEvent,
                    onSplitEvent = viewModel::splitEvent,
                    onResetAnchor = viewModel::resetBalloonAnchor,
                    onRenameSpeaker = viewModel::renameSpeaker,
                    onTranslateWithAi = { viewModel.translateCurrentProjectWithAi() },
                    onAutoTranscribeAudio = viewModel::autoTranscribeCurrentVideoWithAi,
                    onAnalyzeSpeakers = { viewModel.analyzeSpeakersWithContextAndAudio() },
                    onAddManualLine = viewModel::addManualDialogueLine,
                    onUndo = viewModel::undoLastEdit,
                    onShiftTiming = viewModel::shiftAllDialogueTimings,
                    onAddGlossaryEntry = viewModel::addGlossaryEntry,
                    onRemoveGlossaryEntry = viewModel::removeGlossaryEntry,
                    onExportSubtitles = {
                        showToolsDrawer = false
                        subtitleExportProject = state.project
                    },
                    onMergeSpeakers = viewModel::mergeSpeakers,
                    onMarkHealBeaconHere = { scope -> viewModel.markHealBeaconAtCurrentTime(scope = scope) },
                    onRetryHealBeacon = viewModel::retryHealBeacon,
                    onUndoHealBeacon = viewModel::undoHealBeacon,
                    onDismissHealBeacon = viewModel::dismissHealBeacon,
                    onChapterHealAt = { ts ->
                        viewModel.seekTo(ts)
                        viewModel.markHealBeaconAtCurrentTime(scope = HealScope.CHAPTER)
                    },
                    mediaCapabilities = state.mediaCapabilities,
                    transcriptDiagnostics = state.transcriptDiagnostics,
                    quotaSaverMode = state.quotaSaverMode,
                    onSelectQuotaMode = viewModel::setQuotaSaverMode,
                    onRetryAiTranscription = viewModel::retryYouTubeAiTranscription,
                    onAttachSubtitleFile = {
                        showToolsDrawer = false
                        showImportSheet = true
                    },
                    visualStabilitySnapshot = state.visualStabilitySnapshot,
                    readinessSnapshot = state.readinessSnapshot,
                    sentinelSnapshot = state.sentinelSnapshot,
                    releaseSnapshot = state.releaseSnapshot,
                    youtubeCapabilities = state.youtubeCapabilities,
                    onInspectChapterNow = viewModel::inspectChapterNow,
                    onResetStabilityDiagnostics = viewModel::resetVisualStabilityDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }

    if (showImportSheet) {
        MediaImportSheet(
            onDismiss = { showImportSheet = false },
            onStartLocalProject = viewModel::createProjectFromMedia,
            onStartYouTubeProject = viewModel::createYouTubeProject,
            onStartWebStreamProject = viewModel::createWebStreamProject,
            onAttachSubtitleOnly = viewModel::attachSubtitleFileToCurrentProject,
            onLoadDemoScenario = viewModel::loadDemoScenario,
        )
    }

    if (showPrivacySheet) {
        ProjectAndPrivacySheet(
            currentProject = state.project,
            savedProjects = state.savedProjects,
            exportProgress = state.exportProgress,
            onDismiss = { showPrivacySheet = false },
            onExportProjectJson = {
                val safeName = state.project.title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
                exportJsonLauncher.launch("${safeName}.dlens.json")
            },
            onExportSrt = {
                launchSafSubtitleExport(
                    project = state.project,
                    format = SubtitleParser.SubtitleExportFormat.SRT,
                    includeSpeakerPrefixes = true,
                    translationMode = state.project.translationMode,
                )
            },
            onExportVtt = {
                launchSafSubtitleExport(
                    project = state.project,
                    format = SubtitleParser.SubtitleExportFormat.VTT,
                    includeSpeakerPrefixes = true,
                    translationMode = state.project.translationMode,
                )
            },
            onExportAss = {
                launchSafSubtitleExport(
                    project = state.project,
                    format = SubtitleParser.SubtitleExportFormat.ASS,
                    includeSpeakerPrefixes = true,
                    translationMode = state.project.translationMode,
                )
            },
            onOpenSubtitleExportOptions = {
                subtitleExportProject = state.project
            },
            onExportBurnedMp4 = {
                val safeName = state.project.title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
                exportMp4Launcher.launch("${safeName}_dialoguelens.mp4")
            },
            onImportProjectJson = {
                importJsonLauncher.launch(arrayOf("application/json", "*/*"))
            },
            onOpenSavedProject = viewModel::loadProject,
            onDeleteCurrentProject = viewModel::deleteCurrentProject,
            onWipeAllProjects = viewModel::wipeAllLocalProjects,
        )
    }

    if (showStyleSheet) {
        BalloonStyleSheet(
            settings = state.styleSettings,
            onUpdate = viewModel::updateStyleSettings,
            onDismiss = { showStyleSheet = false },
        )
    }

    val inspectedBeacon = state.selectedHealBeaconId?.let { id ->
        state.project.healBeacons.firstOrNull { it.id == id }
    }
    if (inspectedBeacon != null) {
        HealBeaconDetailSheet(
            beacon = inspectedBeacon,
            hasChapters = state.project.chapters.isNotEmpty(),
            onDismissSheet = { viewModel.inspectHealBeacon(null) },
            onJumpToMoment = viewModel::seekTo,
            onRetry = viewModel::retryHealBeacon,
            onUndo = viewModel::undoHealBeacon,
            onRemoveBeacon = viewModel::dismissHealBeacon,
            onUpdateProblemHint = viewModel::updateHealBeaconProblemHint,
        )
    }
}

@Composable
private fun QuietScoutTopPill(
    state: DialogueLensUiState,
    onClick: () -> Unit,
) {
    val cap = state.scoutCaptionState
    val hb = state.scoutHeartbeat
    val isReconnecting = hb.status == ScoutHeartbeatStatus.RECONNECTING || hb.status == ScoutHeartbeatStatus.DEGRADED
    val isError = cap is ScoutCaptionState.Error || hb.status == ScoutHeartbeatStatus.QUOTA_EXHAUSTED
    val isWorking = cap is ScoutCaptionState.FetchingCaptions || cap is ScoutCaptionState.AiTranscribing || isReconnecting
    val isReady = cap is ScoutCaptionState.Ready || state.project.dialogueEvents.isNotEmpty()
    val isPartial = cap is ScoutCaptionState.Ready && cap.sourceLabel.contains("Partial", ignoreCase = true)
    val accent = when {
        isError -> LensAmber
        isReconnecting -> LensAmber
        isPartial -> LensAmber
        isWorking -> LensSky
        isReady -> LensMint
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val verifiedPct = (state.coverageSummary.verifiedFraction * 100).toInt()
    val label = when {
        cap is ScoutCaptionState.Error && cap.isQuotaExhausted -> "Credits"
        hb.status == ScoutHeartbeatStatus.RECONNECTING -> "Retry ${hb.retryAttempt}/${hb.maxRetries}"
        cap is ScoutCaptionState.Error && cap.isPausedMidVideo -> "Resume"
        isError -> "Retry"
        isWorking && state.scoutTotalWindows > 0 ->
            "Scout ${state.scoutCompletedWindows}/${state.scoutTotalWindows}"
        isWorking -> "Scout"
        cap is ScoutCaptionState.Ready && cap.sourceLabel.contains("CC", ignoreCase = true) -> "CC Free"
        cap is ScoutCaptionState.Ready && isPartial ->
            "Partial ${verifiedPct}% · ${state.project.dialogueEvents.size}"
        cap is ScoutCaptionState.Ready && cap.sourceLabel.contains("Cached", ignoreCase = true) -> "Cached"
        cap is ScoutCaptionState.Ready && cap.sourceLabel.contains("Silent", ignoreCase = true) -> "Silent · 0"
        cap is ScoutCaptionState.Ready && cap.sourceLabel.contains("Full", ignoreCase = true) ->
            "Full · ${state.project.dialogueEvents.size}"
        else -> "${state.project.dialogueEvents.size}"
    }

    Surface(
        color = CinemaElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = if (isError) 0.72f else 0.35f)),
        onClick = onClick,
        modifier = Modifier
            .padding(end = 4.dp)
            .testTag("quiet_scout_top_pill"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.6.dp,
                    color = accent,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AmbientNowSpeakingDock(
    state: DialogueLensUiState,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTapActiveCue: (String) -> Unit,
    onOpenCastTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeCue = remember(state.project.dialogueEvents, state.currentTimeMs) {
        state.project.dialogueEvents.firstOrNull {
            state.currentTimeMs in it.startTimeMs..it.endTimeMs
        }
    }
    val activeSpeaker = remember(activeCue, state.project.speakers) {
        state.project.speakers.firstOrNull { it.id == activeCue?.speakerId }
    }
    val activeAccent = when {
        activeCue != null && isNonSpeakerSpecificEvent(activeCue) -> NonSpeakerBubbleWhite
        activeCue != null && isBubbleSpeakerUnknown(activeCue, activeSpeaker) -> UnknownSpeakerGray
        activeSpeaker != null -> SpeakerAccentPalette[activeSpeaker.accentIndex % SpeakerAccentPalette.size]
        else -> LensSky
    }

    Column(
        modifier = modifier
            .background(CinemaObsidian)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("ambient_now_speaking_dock"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.activeChapter?.title ?: "Live Dialogue",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("toggle_dialogue_panel_button"),
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse dialogue panel" else "Expand dialogue panel",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            if (activeCue != null) {
                Surface(
                    color = CinemaSurface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.35f)),
                    onClick = { onTapActiveCue(activeCue.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(activeAccent)
                            )
                            Text(
                                text = if (isNonSpeakerSpecificEvent(activeCue)) {
                                    activeCue.dialogueType.label
                                } else if (isBubbleSpeakerUnknown(activeCue, activeSpeaker)) {
                                    "Unconfirmed · Tap to name"
                                } else {
                                    activeSpeaker?.displayName ?: "Speaker"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = activeAccent,
                            )
                        }
                        Text(
                            text = activeCue.activeText(state.project.translationMode).ifBlank { activeCue.sourceText },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.project.speakers.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            state.project.speakers.take(8).forEach { spk ->
                                val accent = if (spk.isConfirmedCharacter) {
                                    SpeakerAccentPalette[spk.accentIndex % SpeakerAccentPalette.size]
                                } else {
                                    UnknownSpeakerGray
                                }
                                Surface(
                                    color = CinemaSurface,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
                                    onClick = onOpenCastTab,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(accent)
                                        )
                                        Text(
                                            text = spk.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = "Tap any speech bubble to confirm or rename a speaker. Pause to place a Heal Beacon.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolsQuickSettingsHeader(
    state: DialogueLensUiState,
    expandScoutDiag: Boolean,
    onToggleExpandScoutDiag: () -> Unit,
    onToggleEditMode: () -> Unit,
    onCyclePlaybackSpeed: () -> Unit,
    onCycleTranslationMode: () -> Unit,
    onCycleSpeakerLabels: () -> Unit,
    onToggleBubbleStyle: () -> Unit,
    onOpenStyleSheet: () -> Unit,
    onOpenProjectAndPrivacy: () -> Unit,
    onOpenSubtitleExport: () -> Unit,
    onRetryAi: () -> Unit,
    onOpenAttachSrt: () -> Unit,
    onSelectQuotaMode: (QuotaSaverMode) -> Unit,
    onSwitchToZeroCloud: () -> Unit,
) {
    val labelActive = state.styleSettings.labelMode != SpeakerLabelMode.OFF
    val namesChipLabel = when (state.styleSettings.labelMode) {
        SpeakerLabelMode.AUTO_FIRST_10 -> "Names: 1st 10"
        SpeakerLabelMode.ALWAYS -> "Names: All"
        SpeakerLabelMode.OFF -> "Names: Off"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Quick playback, reading & style controls row inside the Tools drawer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.isEditMode,
                onClick = onToggleEditMode,
                label = { Text("Edit / Drag", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = LensSky,
                    selectedLabelColor = CinemaObsidian,
                    selectedLeadingIconColor = CinemaObsidian,
                ),
                modifier = Modifier.testTag("edit_mode_toggle"),
            )

            FilterChip(
                selected = state.playbackSpeed != 1.0f,
                onClick = onCyclePlaybackSpeed,
                label = { Text("${state.playbackSpeed}x", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(15.dp))
                },
                modifier = Modifier.testTag("playback_speed_chip"),
            )

            FilterChip(
                selected = state.project.translationMode == TranslationMode.LEARNING,
                onClick = onCycleTranslationMode,
                label = { Text("Mode: ${state.project.translationMode.label}", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(15.dp))
                },
                modifier = Modifier.testTag("translation_mode_chip"),
            )

            FilterChip(
                selected = labelActive,
                onClick = onCycleSpeakerLabels,
                label = { Text(namesChipLabel, fontSize = 12.sp) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (labelActive) ConfidenceHighColor else Color.Gray)
                    )
                },
                modifier = Modifier.testTag("speaker_labels_chip"),
            )

            FilterChip(
                selected = state.styleSettings.visualStyle != BubbleVisualStyle.STREAMING_CLEAN,
                onClick = onToggleBubbleStyle,
                label = { Text(state.styleSettings.visualStyle.label, fontSize = 12.sp) },
                modifier = Modifier.testTag("bubble_style_preset_chip"),
            )

            FilterChip(
                selected = false,
                onClick = onOpenStyleSheet,
                label = { Text("Appearance", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(15.dp))
                },
                modifier = Modifier.testTag("balloon_style_button"),
            )

            FilterChip(
                selected = false,
                onClick = onOpenSubtitleExport,
                enabled = state.project.dialogueEvents.isNotEmpty(),
                label = { Text("Export .srt/.vtt/.ass", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                },
                modifier = Modifier.testTag("export_subtitles_chip"),
            )

            FilterChip(
                selected = false,
                onClick = onOpenProjectAndPrivacy,
                label = { Text("Export & Privacy", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.PrivacyTip, contentDescription = null, modifier = Modifier.size(15.dp))
                },
                modifier = Modifier.testTag("privacy_export_button"),
            )
        }

        // AI & Quota Mode selector row (Zero-Cloud / Quota-Saver / Pro Scout)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AI & Quota Mode:",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuotaSaverMode.entries.forEach { mode ->
                val selected = state.quotaSaverMode == mode
                FilterChip(
                    selected = selected,
                    onClick = { onSelectQuotaMode(mode) },
                    label = { Text(mode.label, fontSize = 11.5.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (mode == QuotaSaverMode.ZERO_CLOUD) LensMint else LensSky,
                        selectedLabelColor = CinemaObsidian,
                    ),
                    modifier = Modifier.testTag("quota_mode_${mode.name.lowercase()}"),
                )
            }
        }

        // Compact Scout Status & Diagnostics card inside the Tools drawer (with version badge & Retry/SRT actions)
        if (state.project.videoSource.kind != VideoSourceKind.DEMO_SCENARIO) {
            val cap = state.scoutCaptionState
            val isError = cap is ScoutCaptionState.Error
            val isWorking = cap is ScoutCaptionState.FetchingCaptions || cap is ScoutCaptionState.AiTranscribing
            val isReady = cap is ScoutCaptionState.Ready
            val accent = when {
                isError -> LensAmber
                isReady -> LensMint
                else -> LensSky
            }
            val headline = when (cap) {
                is ScoutCaptionState.FetchingCaptions -> cap.message
                is ScoutCaptionState.AiTranscribing -> cap.message
                is ScoutCaptionState.Ready -> "Scout ready · ${cap.cueCount} bubbles · ${cap.speakerCount} speakers"
                is ScoutCaptionState.Error -> cap.message
                ScoutCaptionState.Idle -> state.scoutStatusText.ifBlank {
                    "Scout ready · ${state.project.dialogueEvents.size} bubbles"
                }
            }

            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = if (isError) 0.65f else 0.30f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_scout_telemetry_strip"),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = accent,
                            )
                        } else {
                            Icon(
                                imageVector = when {
                                    isError -> Icons.Filled.WarningAmber
                                    isReady -> Icons.Filled.CheckCircle
                                    else -> Icons.Filled.AutoAwesome
                                },
                                contentDescription = "Scout status",
                                tint = accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (expandScoutDiag || isError) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            color = CinemaSurface,
                            shape = RoundedCornerShape(8.dp),
                            onClick = onToggleExpandScoutDiag,
                            modifier = Modifier.testTag("scout_diagnostics_toggle"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    imageVector = if (expandScoutDiag) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = "Toggle Scout diagnostics",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    if (expandScoutDiag || isError) {
                        val ccSummary = if (state.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) {
                            "Native track: local/web"
                        } else {
                            val langSuffix = state.youtubeNativeCcLang.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                            when (state.youtubeNativeCcState) {
                                YouTubeNativeCcState.LOADING -> "YouTube CC: loading..."
                                YouTubeNativeCcState.OFF -> {
                                    val countNote = state.youtubeNativeCcTrackCount?.let { "$it track(s)" } ?: "inactive"
                                    "YouTube CC: off ($countNote)$langSuffix"
                                }
                                YouTubeNativeCcState.UNAVAILABLE_OR_BLOCKED ->
                                    if (state.youtubeNativeCcProbeBlocked) {
                                        "YouTube CC: probe blocked / inaccessible"
                                    } else {
                                        "YouTube CC: 0 embeddable tracks (AI Scout listening directly)"
                                    }
                                YouTubeNativeCcState.ENABLED_INACCESSIBLE ->
                                    "YouTube CC: enabled (${state.youtubeNativeCcTrackCount ?: 1} track)$langSuffix · cues protected/inaccessible"
                                YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE ->
                                    "YouTube CC: enabled$langSuffix · standby (no current cue)"
                                YouTubeNativeCcState.ENABLED_ACTIVE_CUES ->
                                    "YouTube CC: active$langSuffix (${state.youtubeNativeCcTrackCount ?: 1} track(s))"
                            }
                        }
                        val diagLine = state.scoutDiagnosticDetail.ifBlank { AppCheckDiagnostics.summaryLine() }
                        val hbBadge = state.scoutHeartbeat.summaryBadge()
                        val coverageDiag = state.transcriptDiagnostics.summaryLine()
                        Text(
                            text = "$ccSummary · $hbBadge · Coverage: $coverageDiag · App Check: $diagLine",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("scout_diagnostics_detail_text"),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                                OutlinedButton(
                                    onClick = onRetryAi,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("telemetry_retry_ai_button"),
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = when {
                                            cap is ScoutCaptionState.Error && cap.isPausedMidVideo -> "Resume Scout"
                                            state.quotaSaverMode == QuotaSaverMode.PRO_ACCURACY -> "Retry AI"
                                            else -> "Retry Lite"
                                        },
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            if (state.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD) {
                                OutlinedButton(
                                    onClick = onSwitchToZeroCloud,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("telemetry_zero_cloud_button"),
                                ) {
                                    Text("Free CC / Local", fontSize = 11.sp)
                                }
                            }
                            OutlinedButton(
                                onClick = onOpenAttachSrt,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("telemetry_attach_srt_button"),
                            ) {
                                Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Attach SRT", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBannerStrip(
    banner: String?,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible = banner != null) {
        if (banner != null) {
            Surface(
                color = LensMint.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, LensMint.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.labelMedium,
                        color = LensMint,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss notification",
                            tint = LensMint,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
