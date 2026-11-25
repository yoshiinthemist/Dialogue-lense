package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.ui.setup.MediaLibrary
import com.example.ui.setup.WatchHome
import kotlinx.coroutines.launch
import com.example.ui.setup.documentName
import com.example.ui.setup.TARGET_LANGUAGES
import com.example.ui.player.WatchControls
import com.example.ui.player.PlaybackNotice
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
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealScope
import com.example.data.model.QuotaSaverMode
import com.example.data.model.SpeakerLabelMode
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSourceKind
import com.example.data.subtitle.SubtitleParser
import com.example.ui.editor.InspectorTab
import com.example.ui.editor.TimelineSubtitleEditor
import com.example.ui.player.HealBeaconDetailSheet
import com.example.ui.player.ScoutBrowserInspectorModal
import com.example.ui.player.VideoPlayerStage
import com.example.ui.player.WebBrowserControlStrip
import com.example.ui.player.WebStreamSessionHolder
import com.example.ui.settings.withVisualPreset
import com.example.ui.settings.BalloonStyleSheet
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

    var showPrivacySheet by remember { mutableStateOf(false) }
    var showStyleSheet by remember { mutableStateOf(false) }
    var showAdvancedImport by rememberSaveable { mutableStateOf(false) }
    var showTour by rememberSaveable { mutableStateOf(false) }
    var showIntake by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var browserControls by rememberSaveable(state.project.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var studioSettings by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by rememberSaveable(state.project.id) { mutableStateOf(true) }
    val playbackVisible = state.activeDestination == AppDestination.WATCH && !state.showStartLauncher && !showIntake && !showAdvancedImport
    val fullscreen = state.isFullscreenTheater && playbackVisible
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val appScope = androidx.compose.runtime.rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(fullscreen, view) {
        val window = (context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (fullscreen) bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    val attachCaptions = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) appScope.launch {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.documentName(uri, "subtitles.srt") }
            viewModel.attachSubtitleFileToCurrentProject(uri, name)
        }
    }
    fun attach() = attachCaptions.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream", "*/*"))
    LaunchedEffect(state.project.id) {
        showIntake = false
        showAdvancedImport = false
    }
    LaunchedEffect(state.statusBanner) {
        state.statusBanner?.let {
            snackbar.showSnackbar(it)
            viewModel.clearStatusBanner()
        }
    }
    LaunchedEffect(state.project.id, state.playbackPhase) {
        if (state.playbackPhase == com.example.domain.analysis.PlaybackPhase.USER_PAUSED ||
            state.playbackPhase == com.example.domain.analysis.PlaybackPhase.PROCESSING_UNAVAILABLE) controlsVisible = true
    }
    LaunchedEffect(playbackVisible, state.isPlaying, controlsVisible, menuOpen, state.quickPickerEventId, showStyleSheet, showLanguage) {
        if (playbackVisible && state.isPlaying && controlsVisible && !menuOpen && state.quickPickerEventId == null && !showStyleSheet && !showLanguage) {
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(4500L, containsIcons = true, containsControls = true) ?: 4500L
            delay(timeout)
            controlsVisible = false
        }
    }
    var subtitleExportProject by remember { mutableStateOf<DialogueLensProject?>(null) }
    var pendingSafExport by remember { mutableStateOf<PendingSubtitleExport?>(null) }
    var inspectorTabRequest by rememberSaveable { mutableStateOf(InspectorTab.OVERVIEW) }
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

    val homeVisible = state.showStartLauncher || showIntake
    val savedScreens = rememberSaveableStateHolder()
    BackHandler(fullscreen || showAdvancedImport || showIntake || state.activeDestination != AppDestination.WATCH) {
        when {
            showAdvancedImport -> showAdvancedImport = false
            fullscreen -> viewModel.exitFullscreenTheater()
            showIntake -> showIntake = false
            else -> viewModel.selectDestination(AppDestination.WATCH)
        }
    }
    fun navigate(destination: AppDestination) {
        showAdvancedImport = false
        showIntake = false
        viewModel.selectDestination(destination)
    }
    val tabs = listOf(
        Triple(AppDestination.WATCH, "Watch", Icons.Filled.PlayCircle),
        Triple(AppDestination.LIBRARY, "Library", Icons.Filled.VideoLibrary),
        Triple(AppDestination.STUDIO, "Studio", Icons.Filled.Tune),
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(CinemaObsidian)) {
        val compactLandscape = maxWidth > maxHeight && maxHeight < 500.dp
        val wide = maxWidth >= 760.dp && !compactLandscape
        val useRail = wide || compactLandscape
        Row(Modifier.fillMaxSize()) {
            if (useRail && !fullscreen) {
                NavigationRail(containerColor = CinemaSurface, modifier = Modifier.testTag("adaptive_navigation_rail")) {
                    tabs.forEach { (tab, label, icon) ->
                        NavigationRailItem(state.activeDestination == tab, { navigate(tab) },
                            icon = { Icon(icon, null) }, label = { Text(label) },
                            modifier = Modifier.testTag("nav_rail_${tab.name.lowercase()}"))
                    }
                }
            }
            Scaffold(modifier = Modifier.weight(1f), contentWindowInsets = WindowInsets.safeDrawing,
                containerColor = CinemaObsidian, snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!useRail && !fullscreen) NavigationBar(containerColor = CinemaSurface, modifier = Modifier.testTag("compact_navigation_bar")) {
                        tabs.forEach { (tab, label, icon) ->
                            NavigationBarItem(state.activeDestination == tab, { navigate(tab) },
                                icon = { Icon(icon, null) }, label = { Text(label) },
                                modifier = Modifier.testTag("nav_bar_${tab.name.lowercase()}"))
                        }
                    }
                }) { insets ->
                Row(Modifier.fillMaxSize().padding(insets)) {
                    // Keep provider views at a single composition site through tab changes and resizing.
                    val studioPreview = wide && !showAdvancedImport && state.activeDestination == AppDestination.STUDIO && !state.showStartLauncher
                    val stageVisible = playbackVisible || studioPreview
                    if (!state.showStartLauncher) {
                        Column(if (stageVisible) Modifier.weight(if (studioPreview) 1.15f else 1f).fillMaxHeight()
                            else Modifier.size(0.dp).clearAndSetSemantics {}) {
                            val nativeOverlay = (compactLandscape || fullscreen) && playbackVisible &&
                                state.project.videoSource.kind in setOf(VideoSourceKind.LOCAL_MP4, VideoSourceKind.DEMO_SCENARIO) &&
                                !state.isEditMode && state.quickPickerEventId == null
                            val transportControls: @Composable () -> Unit = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val showControls = controlsVisible || studioPreview
                                    if (playbackVisible) PlaybackNotice(state, viewModel, ::attach)
                                    if (showControls) {
                                        WatchControls(state, viewModel, onAppearance = { showStyleSheet = true },
                                            onLanguage = { showLanguage = true }, onAttach = ::attach, onBrowser = { browserControls = !browserControls },
                                            onCast = { inspectorTabRequest = InspectorTab.CAST; navigate(AppDestination.STUDIO) },
                                            onOpenVideo = { viewModel.pausePlayback(); viewModel.exitFullscreenTheater(); showIntake = true },
                                            onMenuVisible = { menuOpen = it }, compact = compactLandscape, onHide = { controlsVisible = false })
                                    } else {
                                        TextButton(onClick = { controlsVisible = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                            Text("Show controls")
                                        }
                                    }
                                    if (browserControls && state.project.videoSource.kind == VideoSourceKind.WEB_STREAM) {
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
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                                val stageProjectId = state.project.id
                                val stageSession = state.playbackSessionId
                                androidx.compose.runtime.key(stageProjectId, stageSession) {
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
                                        isCinemaIntroActive = state.isCinemaIntroActive,
                                        activeSpeakerToast = if (state.activeDestination == AppDestination.STUDIO) state.activeSpeakerToast else null,
                                        onDismissSpeakerToast = viewModel::dismissSpeakerToast,
                                        onOpenCastGlossary = {
                                            viewModel.exitFullscreenTheater()
                                            inspectorTabRequest = InspectorTab.CAST
                                            viewModel.selectDestination(AppDestination.STUDIO)
                                        },
                                        scoutPlayRequest = state.scoutPlayRequest,
                                        scoutSeekRequest = state.scoutSeekRequest,
                                        showScoutMonitorPip = state.activeDestination == AppDestination.STUDIO && state.showScoutMonitorPip,
                                        displayMode = state.displayMode,
                                        placedBalloons = state.placedBalloons,
                                        onLiveFacesDetected = viewModel::onLiveStageFacesDetected,
                                        playbackGeneration = state.playbackGeneration,
                                        onBodyRelocations = viewModel::recordBodyRelocations,
                                        onBalloonMeasurements = viewModel::onBalloonMeasurements,
                                        onLiveFrameSnapshot = viewModel::onLiveStageFrameSnapshot,
                                        isEditMode = state.isEditMode,
                                        selectedEventId = state.selectedEventId,
                                        onStageTap = { controlsVisible = !controlsVisible },
                                        onViewerTransport = { sample -> viewModel.onViewerTransport(stageProjectId, stageSession, sample) },
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
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                if (nativeOverlay) {
                                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        transportControls()
                                    }
                                }
                            }
                            if (stageVisible && !nativeOverlay) transportControls()

                        }
                    }
                    if (!playbackVisible) Box(Modifier.weight(1f).fillMaxHeight().background(CinemaObsidian)) {
                        when {
                            showAdvancedImport -> Column {
                                TextButton(onClick = { showAdvancedImport = false }) { Text("Back") }
                                StartLauncherScreen(
                                    savedProjects = state.savedProjects,
                                    onStartLocalProject = viewModel::createProjectFromMedia,
                                    onStartYouTubeProject = viewModel::createYouTubeProject,
                                    onStartWebStreamProject = viewModel::createWebStreamProject,
                                    onStartDirectMediaUrl = viewModel::createDirectMediaUrlProject,
                                    onOpenSavedProject = { showAdvancedImport = false; showIntake = false; viewModel.loadProject(it) },
                                    onLoadDemoScenario = { showAdvancedImport = false; showIntake = false; viewModel.loadDemoScenario() },
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
                            state.activeDestination == AppDestination.LIBRARY -> savedScreens.SaveableStateProvider("library") {
                                MediaLibrary(state.savedProjects, onOpen = viewModel::loadProject,
                                    onInspect = { viewModel.loadProject(it); navigate(AppDestination.STUDIO) },
                                    onExport = { subtitleExportProject = it }, onRemove = viewModel::removeSavedProject,
                                    onImport = { importJsonLauncher.launch(arrayOf("application/json", "*/*")) })
                            }
                            state.activeDestination == AppDestination.STUDIO && state.showStartLauncher -> Column(
                                Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Studio", style = MaterialTheme.typography.headlineMedium)
                                Text("Open a video to edit dialogue, review marked moments, or export subtitles.")
                                Button(onClick = { navigate(AppDestination.WATCH) }) { Text("Choose a video") }
                                TextButton(onClick = { showAdvancedImport = true }) { Text("Prepare a subtitle file") }
                                TextButton(onClick = { importJsonLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import a project") }
                            }
                            state.activeDestination == AppDestination.STUDIO -> savedScreens.SaveableStateProvider("studio_${state.project.id}") {
                                Column(Modifier.fillMaxSize()) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Studio", style = MaterialTheme.typography.titleMedium)
                                            Text(state.project.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = { showStyleSheet = true }) { Icon(Icons.Filled.Palette, "Appearance") }
                                        IconButton(onClick = { showPrivacySheet = true }) { Icon(Icons.Filled.Download, "Project and export options") }
                                        IconButton(onClick = { studioSettings = !studioSettings }) { Icon(Icons.Filled.Tune, "Studio settings") }
                                    }
                                    if (studioSettings) {
                                        ToolsQuickSettingsHeader(
                                            state = state,
                                            expandScoutDiag = expandScoutDiagInDrawer,
                                            visualDiagnostics = viewModel::visualDiagnostics,
                                            onToggleExpandScoutDiag = { expandScoutDiagInDrawer = !expandScoutDiagInDrawer },
                                            onToggleEditMode = {
                                                if (!wide && !state.isEditMode) navigate(AppDestination.WATCH)
                                                viewModel.toggleEditMode()
                                            },
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
                                                viewModel.updateStyleSettings(state.styleSettings.withVisualPreset(nextStyle))
                                            },
                                            onOpenStyleSheet = { showStyleSheet = true },
                                            onOpenProjectAndPrivacy = { showPrivacySheet = true },
                                            onOpenSubtitleExport = { subtitleExportProject = state.project },
                                            onRetryAi = viewModel::retryYouTubeAiTranscription,
                                            onOpenAttachSrt = ::attach,
                                            onSelectQuotaMode = viewModel::setQuotaSaverMode,
                                            onSwitchToZeroCloud = viewModel::switchToZeroCloudFallback,
                                        )
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
                                        onSeekTo = { ms ->
                                            viewModel.seekTo(ms)
                                            if (!wide) viewModel.selectDestination(AppDestination.WATCH)
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
                                            viewModel.markHealBeaconAt(ts, scope = HealScope.CHAPTER)
                                        },
                                        mediaCapabilities = state.mediaCapabilities,
                                        transcriptDiagnostics = state.transcriptDiagnostics,
                                        quotaSaverMode = state.quotaSaverMode,
                                        onSelectQuotaMode = viewModel::setQuotaSaverMode,
                                        onRetryAiTranscription = viewModel::retryYouTubeAiTranscription,
                                        onAttachSubtitleFile = ::attach,
                                        visualStabilitySnapshot = state.visualStabilitySnapshot,
                                        readinessSnapshot = state.readinessSnapshot,
                                        sentinelSnapshot = state.sentinelSnapshot,
                                        onInspectChapterNow = viewModel::requestChapterInspection,
                                        onResetStabilityDiagnostics = viewModel::resetVisualStabilityDiagnostics,
                                        verifiedLeadMs = state.verifiedLeadMs,
                                        playbackDurationMs = state.playbackDurationMs,
                                        layoutPasses = viewModel.spatialLayoutPasses,
                                        coverage = state.coverageSummary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    )
                                }
                            }
                            homeVisible -> savedScreens.SaveableStateProvider("watchHome") {
                                Column {
                                    val status = state.analysisStatus
                                    if (status is com.example.ui.state.AnalysisStatus.Error) Text(status.message, modifier = Modifier.padding(16.dp))
                                    if (status is com.example.ui.state.AnalysisStatus.Processing) Text(status.phaseLabel, modifier = Modifier.padding(16.dp))
                                    WatchHome(state.savedProjects, viewModel, hasCompletedOnboarding = state.hasCompletedOnboarding,
                                        cloudDialogueConfigured = state.cloudDialogueConfigured, onQuickTour = { showTour = true }, onSelectExisting = { showIntake = false }, onAdvancedImport = { showAdvancedImport = true },
                                        onLibrary = { navigate(AppDestination.LIBRARY) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showTour) {
        fun finishTour() { showTour = false; viewModel.completeOnboarding() }
        androidx.compose.ui.window.Dialog(onDismissRequest = ::finishTour,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            com.example.ui.setup.FirstTimeOnboardingOverlay(onComplete = ::finishTour, onTryDemoScene = {
                finishTour()
                showIntake = false
                viewModel.loadDemoScenario()
            })
        }
    }
    if (showLanguage) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { showLanguage = false }, title = { Text("Subtitle language") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                TARGET_LANGUAGES.forEach { language ->
                    TextButton(onClick = {
                        showLanguage = false
                        context.getSharedPreferences("dialoguelens_intake", android.content.Context.MODE_PRIVATE).edit().putString("target_language", language).apply()
                        viewModel.translateCurrentProjectWithAi(overrideTargetLanguage = language)
                    }) { Text(language) }
                }
            } }, confirmButton = { TextButton(onClick = { showLanguage = false }) { Text("Close") } })
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
private fun ToolsQuickSettingsHeader(
    state: DialogueLensUiState,
    expandScoutDiag: Boolean,
    visualDiagnostics: () -> String,
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
                    enabled = state.cloudDialogueConfigured || mode == QuotaSaverMode.ZERO_CLOUD,
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
                        val ccSummary = when (val ccCount = state.youtubeNativeCcTrackCount) {
                            null -> if (state.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) "YouTube CC: checking..." else "Native track: local/web"
                            0 -> "YouTube CC: 0 embeddable tracks (AI Scout listening directly)"
                            else -> "YouTube CC: $ccCount track(s) ${state.youtubeNativeCcLang.ifBlank { "" }}".trim()
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
                        Text(
                            text = visualDiagnostics(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
