package com.example.ui.state

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.domain.analysis.AudioAnalyzer
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.PlaybackClockAnchor
import com.example.domain.analysis.ResilientYouTubeSegmentExecutor
import com.example.domain.analysis.ScoutExecutionTier
import com.example.domain.analysis.ScoutSyncCommand
import com.example.domain.analysis.SegmentExecutionOutcome
import com.example.domain.analysis.SniffedWebMediaStream
import com.example.domain.analysis.TimeWindowRange
import com.example.domain.analysis.VideoAnalyzer
import com.example.domain.analysis.WebNavAction
import com.example.domain.analysis.WebNavCommand
import com.example.domain.analysis.WebNavigationGuard
import com.example.domain.analysis.WebNavigationState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.ai.AiCastDiscoveryResult
import com.example.data.ai.AiCastSlotAssignment
import com.example.data.ai.AiTranscribedCue
import com.example.data.ai.AiTranslatedLine
import com.example.data.ai.OnDeviceTranslatorService
import com.example.data.ai.ScoutHeartbeatService
import com.example.data.ai.ScoutHeartbeatSnapshot
import com.example.data.ai.ScoutHeartbeatStatus
import com.example.data.ai.TranslationAiService
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CachedVideoTranscript
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.GlossaryEntry
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.ProcessingQualityMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SpeakerColorOrder
import com.example.data.model.SpeakerLabelMode
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.project.DemoScenarioFactory
import com.example.data.project.ProjectStore
import com.example.data.subtitle.RawSubtitleCue
import com.example.data.subtitle.SubtitleParser
import com.example.data.subtitle.YouTubeCaptionFetcher
import com.example.domain.attribution.SpeakerAttributionEngine
import com.example.domain.export.VideoExporter
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.PlacedBalloon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "DialogueLensViewModel"

sealed interface AnalysisStatus {
    data object Ready : AnalysisStatus
    data class Processing(
        val phaseLabel: String,
        val progress: Float,
        val analyzedUpToMs: Long,
        val totalMs: Long,
    ) : AnalysisStatus
    data class Error(val message: String) : AnalysisStatus
}

sealed interface ScoutCaptionState {
    data object Idle : ScoutCaptionState
    data class FetchingCaptions(val message: String) : ScoutCaptionState
    data class AiTranscribing(
        val message: String,
        val completedWindows: Int = 0,
        val cuesSoFar: Int = 0,
    ) : ScoutCaptionState
    data class Ready(
        val sourceLabel: String,
        val cueCount: Int,
        val speakerCount: Int,
    ) : ScoutCaptionState
    data class Error(
        val message: String,
        val canRetryAi: Boolean = true,
        val isPausedMidVideo: Boolean = false,
        val isQuotaExhausted: Boolean = false,
    ) : ScoutCaptionState
}

data class SpeakerGlossaryToast(
    val id: Long,
    val speakerId: String,
    val isRename: Boolean,
    val title: String,
    val detail: String,
    val accentIndex: Int,
    val isUnknown: Boolean,
)

data class DialogueLensUiState(
    val project: DialogueLensProject = DemoScenarioFactory.createDemoProject(),
    val savedProjects: List<DialogueLensProject> = emptyList(),
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val currentTimeMs: Long = 600L,
    val displayMode: CaptionDisplayMode = CaptionDisplayMode.BALLOONS,
    val styleSettings: BalloonStyleSettings = BalloonStyleSettings(),
    val isEditMode: Boolean = false,
    val selectedEventId: String? = null,
    val placedBalloons: List<PlacedBalloon> = emptyList(),
    val analysisStatus: AnalysisStatus = AnalysisStatus.Ready,
    val scoutCaptionState: ScoutCaptionState = ScoutCaptionState.Idle,
    val youtubeNativeCcTrackCount: Int? = null,
    val youtubeNativeCcLang: String = "",
    val scoutDiagnosticDetail: String = "",
    val activeSpeakerToast: SpeakerGlossaryToast? = null,
    val statusBanner: String? = null,
    val isTranslatingAi: Boolean = false,
    val characterColorMemory: Map<String, Int> = emptyMap(),
    val streamPlayRequest: Pair<Int, Boolean>? = null,
    val youtubeSeekRequest: Pair<Int, Long>? = null,
    val dualInstanceEnabled: Boolean = true,
    val isCinemaIntroActive: Boolean = false,
    val introRemainingMs: Long = 0L,
    val introTotalDurationMs: Long = LookaheadWindowPlanner.INTRO_DURATION_MS,
    val requiredOpeningLeadWindows: Int = 1,
    val isOpeningSpeakersReady: Boolean = false,
    val isScoutCatchUpBuffering: Boolean = false,
    val scoutTimeMs: Long = 0L,
    val scoutedUpToMs: Long = 0L,
    val scoutStatusText: String = "",
    val scoutPlayRequest: Pair<Int, Boolean>? = null,
    val scoutSeekRequest: Pair<Int, Long>? = null,
    val showScoutMonitorPip: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val isFullscreenTheater: Boolean = false,
    val canUndo: Boolean = false,
    val exportProgress: Float? = null,
    val glossaryEntries: List<GlossaryEntry> = emptyList(),
    val showStartLauncher: Boolean = true,
    val hasPendingPlayIntro: Boolean = false,
    val quickPickerEventId: String? = null,
    val resumeAfterQuickPicker: Boolean = false,
    val quotaSaverMode: QuotaSaverMode = QuotaSaverMode.PRO_ACCURACY,
    val scoutHeartbeat: ScoutHeartbeatSnapshot = ScoutHeartbeatSnapshot(),
    val subtitlePrepState: SubtitlePrepState = SubtitlePrepState.Idle,
    val selectedPrepFormat: SubtitleParser.SubtitleExportFormat = SubtitleParser.SubtitleExportFormat.SRT,
    val liveFaceExclusionRects: List<NormalizedRect> = emptyList(),
    val bufferedWindowRanges: List<TimeWindowRange> = emptyList(),
    val scoutCompletedWindows: Int = 0,
    val scoutTotalWindows: Int = 0,
    val scoutFailedSliceCount: Int = 0,
    val viewerWebNav: WebNavigationState = WebNavigationState(),
    val scoutWebNav: WebNavigationState = WebNavigationState(),
    val viewerWebNavCommand: WebNavCommand? = null,
    val scoutWebNavCommand: WebNavCommand? = null,
    val showScoutBrowserModal: Boolean = false,
)

class DialogueLensViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val projectStore = ProjectStore(appContext)
    private val videoAnalyzer = VideoAnalyzer(appContext)
    private val audioAnalyzer = AudioAnalyzer(appContext)
    private val attributionEngine = SpeakerAttributionEngine()
    private val layoutEngine = BalloonLayoutEngine()
    private val translationAiService = TranslationAiService()
    private val scoutHeartbeatService = ScoutHeartbeatService(
        probeFlashLite = { translationAiService.pingFlashLiteHealth() },
    )
    private val onDeviceTranslator = OnDeviceTranslatorService()
    private val subtitlePreGenerator = SubtitlePreGenerator(
        projectStore = projectStore,
        videoAnalyzer = videoAnalyzer,
        audioAnalyzer = audioAnalyzer,
        attributionEngine = attributionEngine,
        translationAiService = translationAiService,
        scoutHeartbeatService = scoutHeartbeatService,
        onDeviceTranslator = onDeviceTranslator,
    )
    private val segmentExecutor = ResilientYouTubeSegmentExecutor(scoutHeartbeatService)
    private val videoExporter = VideoExporter(appContext)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext).build()

    private val _uiState = MutableStateFlow(DialogueLensUiState())
    val uiState: StateFlow<DialogueLensUiState> = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<DialogueLensProject>()
    private var tickerJob: Job? = null
    private var analysisJob: Job? = null
    private var introJob: Job? = null
    private var lookaheadJob: Job? = null
    private var aiTranslationJob: Job? = null
    private var speakerAnalysisJob: Job? = null
    private var castDiscoveryJob: Job? = null
    private var youtubeTranscriptJob: Job? = null
    private var subtitlePrepJob: Job? = null
    private var speakerToastJob: Job? = null
    private var nextSpeakerToastId = 0L
    private var lastCapturedWebTrackSignature: String? = null
    private var lastHandledShareText: String? = null
    private var nextStreamCommandSeq = 0
    private var nextScoutCommandSeq = 0
    private var lastScoutSeekSentAtMs = 0L
    private var lastScoutSeekTargetMs = -1L
    private val scoutCompletedWindows = mutableSetOf<Long>()
    private val scoutAudioSliceTimestamps = mutableSetOf<Long>()
    private val capturedYouTubeTimedTextKeys = mutableSetOf<String>()
    private val autoAnalyzedEventIds = mutableSetOf<String>()
    private val cachedAiTurnHints = mutableMapOf<String, AiTranslatedLine>()
    private val inMemoryTranslationCache = mutableMapOf<String, String>()
    private var quotaCircuitBreakerTripped = false
    private var downgradedToLiteForStream = false
    private var pendingWebTimedTextFallback: PendingWebTimedText? = null
    private var lastAutoAiTriggerElapsedMs = 0L
    private var discoveredWebMediaUrl: String? = null
    private var discoveredWebMediaHeaders: Map<String, String> = emptyMap()
    private var currentLiveFaces: List<LiveFaceObservation> = emptyList()
    private var catchUpBufferDismissedUntilMs = 0L
    private var nextViewerNavSeq = 0
    private var nextScoutNavSeq = 0
    private var lastAutoScoutSyncAtMs = 0L
    private val lookaheadMutex = Mutex()
    private val playbackClockAnchor = PlaybackClockAnchor()

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (_uiState.value.project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    playbackClockAnchor.updatePlaybackState(
                        positionMs = pos,
                        speed = exoPlayer.playbackParameters.speed,
                        playing = isPlaying,
                    )
                    _uiState.update { it.copy(isPlaying = isPlaying) }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (_uiState.value.project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
                    val snappedPos = newPosition.positionMs.coerceAtLeast(0L)
                    playbackClockAnchor.updatePlaybackState(
                        positionMs = snappedPos,
                        speed = exoPlayer.playbackParameters.speed,
                        playing = exoPlayer.isPlaying,
                    )
                    updateTimeAndLayout(snappedPos)
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (_uiState.value.project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
                    playbackClockAnchor.updatePlaybackState(
                        positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                        speed = playbackParameters.speed,
                        playing = exoPlayer.isPlaying,
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED &&
                    _uiState.value.project.videoSource.kind == VideoSourceKind.LOCAL_MP4
                ) {
                    playbackClockAnchor.updatePlaybackState(
                        positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                        speed = exoPlayer.playbackParameters.speed,
                        playing = false,
                    )
                    _uiState.update { it.copy(isPlaying = false) }
                }
            }
        })

        viewModelScope.launch {
            projectStore.styleSettingsFlow.collect { style ->
                _uiState.update { state ->
                    val updatedProject = state.project.copy(styleSettings = style)
                    state.copy(
                        styleSettings = style,
                        project = updatedProject,
                        placedBalloons = layoutEngine.computeLayout(updatedProject, state.currentTimeMs, style, currentLiveFaces),
                    )
                }
            }
        }

        // Always keep Balloons ON as the core DialogueLens default on startup/stream entry
        viewModelScope.launch {
            projectStore.displayModeFlow.collect { rawMode ->
                val mode = rawMode.normalizedPrimaryMode()
                if (mode != CaptionDisplayMode.OFF && !_uiState.value.showStartLauncher && !_uiState.value.hasPendingPlayIntro) {
                    _uiState.update { it.copy(displayMode = mode) }
                }
            }
        }

        viewModelScope.launch {
            projectStore.characterColorsFlow.collect { remembered ->
                _uiState.update { state ->
                    val (reconciledSpeakers, updatedMemory) = attributionEngine.reconcileCharacterColors(
                        speakers = state.project.speakers,
                        events = state.project.dialogueEvents,
                        rememberedColors = remembered,
                    )
                    val updatedProj = state.project.copy(speakers = reconciledSpeakers)
                    state.copy(
                        characterColorMemory = updatedMemory,
                        project = updatedProj,
                        placedBalloons = layoutEngine.computeLayout(updatedProj, state.currentTimeMs, state.styleSettings, currentLiveFaces),
                    )
                }
            }
        }

        viewModelScope.launch {
            projectStore.glossaryMemoryFlow.collect { savedGlossary ->
                _uiState.update { state ->
                    val merged = (state.project.glossary + savedGlossary).distinctBy {
                        it.term.trim().lowercase()
                    }
                    state.copy(
                        glossaryEntries = savedGlossary,
                        project = state.project.copy(glossary = merged),
                    )
                }
            }
        }

        viewModelScope.launch {
            projectStore.quotaSaverModeFlow.collect { mode ->
                scoutHeartbeatService.setZeroCloudPaused(mode == QuotaSaverMode.ZERO_CLOUD)
                _uiState.update { it.copy(quotaSaverMode = mode) }
            }
        }

        viewModelScope.launch {
            scoutHeartbeatService.snapshot.collect { snap ->
                _uiState.update { it.copy(scoutHeartbeat = snap) }
            }
        }

        scoutHeartbeatService.startPlaybackMonitor(
            scope = viewModelScope,
            shouldMonitor = {
                val s = _uiState.value
                s.isPlaying &&
                    !s.showStartLauncher &&
                    s.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD &&
                    !quotaCircuitBreakerTripped &&
                    (s.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT ||
                        s.project.videoSource.kind == VideoSourceKind.LOCAL_MP4 ||
                        s.project.videoSource.kind == VideoSourceKind.WEB_STREAM)
            },
            onConnectionRecovered = {
                val cur = _uiState.value
                val proj = cur.project
                when (proj.videoSource.kind) {
                    VideoSourceKind.YOUTUBE_COMPLIANT -> {
                        val vid = proj.videoSource.youtubeVideoId
                        val capErr = cur.scoutCaptionState as? ScoutCaptionState.Error
                        if (!vid.isNullOrBlank() &&
                            !LookaheadWindowPlanner.hasLoadedSubtitlesLocked(proj.subtitleSource, proj.dialogueEvents) &&
                            (youtubeTranscriptJob == null || youtubeTranscriptJob?.isActive != true) &&
                            (capErr != null && !capErr.isQuotaExhausted)
                        ) {
                            runYouTubeScoutTranscriptPipeline(
                                projectId = proj.id,
                                videoId = vid,
                                skipTierOneCaptions = true,
                                resumeFromHorizonSec = (cur.scoutedUpToMs / 1000L).toInt().coerceAtLeast(0),
                            )
                        }
                    }
                    VideoSourceKind.LOCAL_MP4 -> {
                        if (cur.dualInstanceEnabled && (lookaheadJob == null || lookaheadJob?.isActive != true)) {
                            val hasPreloaded = LookaheadWindowPlanner.hasRealSubtitles(proj.dialogueEvents)
                            startRollingMediaLookahead(
                                projectId = proj.id,
                                videoUri = Uri.parse(proj.videoSource.uriString),
                                httpUrl = null,
                                sourceLanguage = proj.sourceLanguage,
                                targetLanguage = proj.targetLanguage,
                                hasPreloadedSubtitles = hasPreloaded,
                            )
                        }
                    }
                    VideoSourceKind.WEB_STREAM -> {
                        val directUrl = discoveredWebMediaUrl
                        if (!directUrl.isNullOrBlank() && cur.dualInstanceEnabled &&
                            (lookaheadJob == null || lookaheadJob?.isActive != true)
                        ) {
                            startRollingMediaLookahead(
                                projectId = proj.id,
                                videoUri = null,
                                httpUrl = directUrl,
                                httpHeaders = discoveredWebMediaHeaders,
                                sourceLanguage = proj.sourceLanguage,
                                targetLanguage = proj.targetLanguage,
                                hasPreloadedSubtitles = LookaheadWindowPlanner.hasRealSubtitles(proj.dialogueEvents),
                            )
                        }
                    }
                    VideoSourceKind.DEMO_SCENARIO -> Unit
                }
            },
        )

        viewModelScope.launch {
            inMemoryTranslationCache.putAll(projectStore.loadTranslationCache())
            refreshSavedProjects()
            recomputeBalloons()
        }

        startPlaybackTicker()
    }

    fun setQuotaSaverMode(mode: QuotaSaverMode) {
        if (mode != QuotaSaverMode.ZERO_CLOUD) {
            quotaCircuitBreakerTripped = false
            downgradedToLiteForStream = false
            scoutHeartbeatService.setZeroCloudPaused(false)
            if (_uiState.value.scoutHeartbeat.status == ScoutHeartbeatStatus.QUOTA_EXHAUSTED ||
                _uiState.value.scoutHeartbeat.status == ScoutHeartbeatStatus.IDLE
            ) {
                scoutHeartbeatService.resetForNewStream(
                    if (mode == QuotaSaverMode.PRO_ACCURACY) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
                )
            }
        } else {
            scoutHeartbeatService.setZeroCloudPaused(true)
        }
        viewModelScope.launch {
            projectStore.saveQuotaSaverMode(mode)
        }
        _uiState.update {
            it.copy(
                quotaSaverMode = mode,
                statusBanner = "${mode.label}: ${mode.subtitle}",
            )
        }
        if (mode == QuotaSaverMode.ZERO_CLOUD) {
            youtubeTranscriptJob?.cancel()
            aiTranslationJob?.cancel()
            speakerAnalysisJob?.cancel()
            castDiscoveryJob?.cancel()
            val curProj = _uiState.value.project
            val vid = curProj.videoSource.youtubeVideoId
            val hasLoadedLocked = LookaheadWindowPlanner.hasLoadedSubtitlesLocked(curProj.subtitleSource, curProj.dialogueEvents)
            if (curProj.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT &&
                !vid.isNullOrBlank() &&
                !hasLoadedLocked
            ) {
                runYouTubeScoutTranscriptPipeline(
                    projectId = curProj.id,
                    videoId = vid,
                    skipTierOneCaptions = false,
                )
            } else {
                _uiState.update { cur ->
                    val proj = cur.project
                    val hasReal = LookaheadWindowPlanner.hasRealSubtitles(proj.dialogueEvents)
                    cur.copy(
                        isTranslatingAi = false,
                        scoutCaptionState = if (hasReal) {
                            ScoutCaptionState.Ready("Local / CC", proj.dialogueEvents.size, proj.speakers.size)
                        } else {
                            ScoutCaptionState.Error(
                                message = "Free CC Only active — use YouTube CC or Attach SRT",
                                canRetryAi = false,
                                isQuotaExhausted = false,
                            )
                        },
                    )
                }
            }
        }
    }

    fun switchToZeroCloudFallback() {
        setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)
    }

    private fun startPlaybackTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var lastTickNs = System.nanoTime()
            while (isActive) {
                val nowNs = System.nanoTime()
                val deltaMs = ((nowNs - lastTickNs) / 1_000_000L).coerceIn(1L, 120L)
                lastTickNs = nowNs

                val state = _uiState.value
                val kind = state.project.videoSource.kind
                when (kind) {
                    VideoSourceKind.LOCAL_MP4 -> {
                        val maxDur = maxOf(
                            state.project.videoSource.durationMs,
                            exoPlayer.duration.coerceAtLeast(0L),
                        ).coerceAtLeast(1000L)
                        val sampled = playbackClockAnchor.sampleLocalExoPosition(
                            exoPosMs = exoPlayer.currentPosition,
                            speed = exoPlayer.playbackParameters.speed,
                            playing = exoPlayer.isPlaying,
                            maxDurationMs = maxDur,
                            nowNs = nowNs,
                        )
                        if (sampled != state.currentTimeMs) {
                            updateTimeAndLayout(sampled)
                        }
                    }
                    VideoSourceKind.YOUTUBE_COMPLIANT,
                    VideoSourceKind.WEB_STREAM -> {
                        if (state.isPlaying &&
                            !state.isCinemaIntroActive &&
                            !state.isScoutCatchUpBuffering &&
                            !state.hasPendingPlayIntro
                        ) {
                            val maxDur = state.project.videoSource.durationMs.coerceAtLeast(1000L)
                            val sampled = playbackClockAnchor.sampleInterpolatedMs(
                                maxDurationMs = maxDur,
                                nowNs = nowNs,
                            )
                            if (kotlin.math.abs(sampled - state.currentTimeMs) >= 14L) {
                                updateTimeAndLayout(sampled)
                            }
                        }
                    }
                    VideoSourceKind.DEMO_SCENARIO -> {
                        if (state.isPlaying) {
                            val duration = state.project.videoSource.durationMs.coerceAtLeast(1000L)
                            val scaledDelta = (deltaMs * state.playbackSpeed).toLong().coerceAtLeast(1L)
                            val next = state.currentTimeMs + scaledDelta
                            if (next >= duration) {
                                updateTimeAndLayout(0L)
                            } else {
                                updateTimeAndLayout(next)
                            }
                        }
                    }
                }
                delay(16L)
            }
        }
    }

    private fun updateTimeAndLayout(timeMs: Long) {
        _uiState.update { state ->
            val balloons = layoutEngine.computeLayout(state.project, timeMs, state.styleSettings, currentLiveFaces)
            state.copy(currentTimeMs = timeMs, placedBalloons = balloons)
        }
        maybeTriggerRollingWindowEnhancement(timeMs)
    }

    private fun recomputeBalloons() {
        _uiState.update { state ->
            state.copy(
                placedBalloons = layoutEngine.computeLayout(
                    state.project,
                    state.currentTimeMs,
                    state.styleSettings,
                    currentLiveFaces,
                )
            )
        }
    }

    fun onLiveStageFacesDetected(timeMs: Long, faces: List<LiveFaceObservation>) {
        val state = _uiState.value
        if (state.showStartLauncher || state.isCinemaIntroActive || state.isEditMode) return
        currentLiveFaces = faces
        val lipSafe = state.styleSettings.lipReadingSafeZone
        val liveExclusions = faces.map { obs ->
            val raw = obs.boundingBox
            val expanded = raw.expand(0.042f, 0.036f)
            if (lipSafe) {
                expanded.copy(bottom = (raw.bottom + raw.height * 0.45f).coerceAtMost(1f))
            } else {
                expanded
            }
        }
        _uiState.update { cur ->
            if (cur.showStartLauncher || cur.isCinemaIntroActive || cur.isEditMode) {
                cur
            } else {
                cur.copy(
                    liveFaceExclusionRects = liveExclusions,
                    placedBalloons = layoutEngine.computeLayout(
                        project = cur.project,
                        currentMs = cur.currentTimeMs,
                        styleSettings = cur.styleSettings,
                        liveFaces = faces,
                    ),
                )
            }
        }
    }

    fun openStartLauncher() {
        pausePlayback()
        introJob?.cancel()
        _uiState.update { cur ->
            cur.copy(
                showStartLauncher = true,
                isCinemaIntroActive = false,
                introRemainingMs = 0L,
            )
        }
    }

    fun dismissScoutCatchUpBuffering() {
        val state = _uiState.value
        if (!state.isScoutCatchUpBuffering) return
        catchUpBufferDismissedUntilMs = state.currentTimeMs + 45_000L
        val kind = state.project.videoSource.kind
        when (kind) {
            VideoSourceKind.LOCAL_MP4 -> {
                _uiState.update { it.copy(isScoutCatchUpBuffering = false) }
                exoPlayer.play()
            }
            VideoSourceKind.YOUTUBE_COMPLIANT,
            VideoSourceKind.WEB_STREAM -> {
                val cmdSeq = ++nextStreamCommandSeq
                _uiState.update {
                    it.copy(
                        isScoutCatchUpBuffering = false,
                        isPlaying = true,
                        streamPlayRequest = cmdSeq to true,
                    )
                }
            }
            VideoSourceKind.DEMO_SCENARIO -> {
                _uiState.update { it.copy(isScoutCatchUpBuffering = false, isPlaying = true) }
            }
        }
    }

    fun togglePlayPause() {
        val state = _uiState.value
        if (state.quickPickerEventId != null) {
            _uiState.update { it.copy(quickPickerEventId = null, resumeAfterQuickPicker = false) }
        }
        if (state.isCinemaIntroActive && !state.isPlaying) {
            skipCinemaIntro()
            return
        }
        if (state.isScoutCatchUpBuffering) {
            dismissScoutCatchUpBuffering()
            return
        }
        val kind = state.project.videoSource.kind
        if (!state.isPlaying &&
            state.hasPendingPlayIntro &&
            state.dualInstanceEnabled &&
            kind != VideoSourceKind.DEMO_SCENARIO
        ) {
            val leadWins = LookaheadWindowPlanner.recommendedOpeningLeadWindows(state.project.videoSource.durationMs)
            val initStatus = when (kind) {
                VideoSourceKind.LOCAL_MP4 ->
                    "Smart Pre-Roll · Scout analyzing opening speech & faces..."
                VideoSourceKind.YOUTUBE_COMPLIANT -> if (leadWins > 1) {
                    "Smart Pre-Roll · Buffering $leadWins opening scenes for smoother playback..."
                } else {
                    "Smart Pre-Roll · Scouting opening 35s context & speakers..."
                }
                VideoSourceKind.WEB_STREAM ->
                    "Smart Pre-Roll · Scout scanning stream..."
                VideoSourceKind.DEMO_SCENARIO -> ""
            }
            _uiState.update { it.copy(hasPendingPlayIntro = false) }
            startSevenSecondIntro(
                projectId = state.project.id,
                initialStatus = initStatus,
                requireFullSevenSeconds = false,
                resetScoutProgress = false,
            )
            return
        }
        when (kind) {
            VideoSourceKind.LOCAL_MP4 -> {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            }
            VideoSourceKind.YOUTUBE_COMPLIANT,
            VideoSourceKind.WEB_STREAM -> {
                val nextPlaying = !state.isPlaying
                val cmdSeq = ++nextStreamCommandSeq
                _uiState.update { cur ->
                    cur.copy(isPlaying = nextPlaying, streamPlayRequest = cmdSeq to nextPlaying)
                }
            }
            VideoSourceKind.DEMO_SCENARIO -> {
                _uiState.update { it.copy(isPlaying = !it.isPlaying) }
            }
        }
    }

    fun pausePlayback() {
        val kind = _uiState.value.project.videoSource.kind
        if (kind == VideoSourceKind.LOCAL_MP4) {
            exoPlayer.pause()
        }
        val pauseSeq = if (kind == VideoSourceKind.YOUTUBE_COMPLIANT || kind == VideoSourceKind.WEB_STREAM) {
            ++nextStreamCommandSeq
        } else {
            null
        }
        _uiState.update { state ->
            state.copy(
                isPlaying = false,
                isScoutCatchUpBuffering = false,
                streamPlayRequest = pauseSeq?.let { it to false } ?: state.streamPlayRequest,
            )
        }
    }

    fun seekTo(timeMs: Long) {
        val cur = _uiState.value
        val duration = cur.project.videoSource.durationMs.coerceAtLeast(1000L)
        val clamped = timeMs.coerceIn(0L, duration)

        val significantJump = kotlin.math.abs(clamped - cur.currentTimeMs) > 2_500L
        if (significantJump) {
            aiTranslationJob?.cancel()
            speakerAnalysisJob?.cancel()
            lastAutoAiTriggerElapsedMs = 0L
            lastScoutSeekSentAtMs = 0L
            lastScoutSeekTargetMs = -1L
            catchUpBufferDismissedUntilMs = 0L
            if (cur.isTranslatingAi || cur.isScoutCatchUpBuffering) {
                _uiState.update { state ->
                    state.copy(
                        isTranslatingAi = false,
                        isScoutCatchUpBuffering = false,
                        analysisStatus = if (state.analysisStatus is AnalysisStatus.Processing) {
                            AnalysisStatus.Ready
                        } else {
                            state.analysisStatus
                        },
                    )
                }
            }
        }

        currentLiveFaces = emptyList()
        _uiState.update { it.copy(liveFaceExclusionRects = emptyList()) }
        when (_uiState.value.project.videoSource.kind) {
            VideoSourceKind.LOCAL_MP4 -> exoPlayer.seekTo(clamped)
            VideoSourceKind.YOUTUBE_COMPLIANT,
            VideoSourceKind.WEB_STREAM -> {
                val seekSeq = ++nextStreamCommandSeq
                _uiState.update { state ->
                    state.copy(youtubeSeekRequest = seekSeq to clamped)
                }
            }
            VideoSourceKind.DEMO_SCENARIO -> Unit
        }
        layoutEngine.clearSmoothing()
        playbackClockAnchor.reset(
            positionMs = clamped,
            speed = _uiState.value.playbackSpeed,
            playing = _uiState.value.isPlaying,
        )
        updateTimeAndLayout(clamped)
    }

    fun onYouTubePlaybackState(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        val safePos = positionMs.coerceAtLeast(0L)
        var shouldTriggerCatchUpPause = false
        _uiState.update { state ->
            if (state.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return@update state
            val updatedProj = if (durationMs > 1000L && durationMs != state.project.videoSource.durationMs) {
                state.project.copy(
                    videoSource = state.project.videoSource.copy(durationMs = durationMs),
                    shots = listOf(Shot("shot_1", 0L, durationMs, cutType = "EMBEDDED_YOUTUBE")),
                )
            } else {
                state.project
            }

            val isScoutActivelyBuffering = youtubeTranscriptJob?.isActive == true
            val canPauseForCatchUp = isPlaying &&
                !state.isCinemaIntroActive &&
                !state.hasPendingPlayIntro &&
                !state.isEditMode &&
                state.dualInstanceEnabled &&
                isScoutActivelyBuffering &&
                state.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD &&
                !quotaCircuitBreakerTripped &&
                safePos >= catchUpBufferDismissedUntilMs &&
                !LookaheadWindowPlanner.hasLoadedSubtitlesLocked(updatedProj.subtitleSource, updatedProj.dialogueEvents) &&
                LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                    playheadMs = safePos,
                    totalDurationMs = updatedProj.videoSource.durationMs,
                    bufferedRanges = state.bufferedWindowRanges,
                    events = updatedProj.dialogueEvents,
                    isScoutActive = true,
                )

            if (canPauseForCatchUp) {
                shouldTriggerCatchUpPause = true
                playbackClockAnchor.reset(safePos, state.playbackSpeed, playing = false)
                val pauseSeq = ++nextStreamCommandSeq
                state.copy(
                    project = updatedProj,
                    isPlaying = false,
                    isScoutCatchUpBuffering = true,
                    hasPendingPlayIntro = false,
                    currentTimeMs = safePos,
                    streamPlayRequest = pauseSeq to false,
                    placedBalloons = layoutEngine.computeLayout(updatedProj, safePos, state.styleSettings, currentLiveFaces),
                )
            } else {
                val clearedBuffering = if (state.isScoutCatchUpBuffering && !isScoutActivelyBuffering) {
                    false
                } else {
                    state.isScoutCatchUpBuffering
                }
                val resolvedPlaying = if (state.isScoutCatchUpBuffering && !isPlaying) false else isPlaying
                playbackClockAnchor.reconcileRemoteSample(
                    reportedPosMs = safePos,
                    speed = state.playbackSpeed,
                    playing = resolvedPlaying,
                )
                state.copy(
                    project = updatedProj,
                    isPlaying = resolvedPlaying,
                    isScoutCatchUpBuffering = clearedBuffering,
                    hasPendingPlayIntro = if (isPlaying) false else state.hasPendingPlayIntro,
                    currentTimeMs = safePos,
                    placedBalloons = layoutEngine.computeLayout(updatedProj, safePos, state.styleSettings, currentLiveFaces),
                )
            }
        }
        if (!shouldTriggerCatchUpPause) {
            maybeTriggerRollingWindowEnhancement(safePos)
        }
    }

    fun onYouTubeCaptionTrackStatus(trackCount: Int, activeLang: String) {
        _uiState.update { cur ->
            if (cur.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return@update cur
            cur.copy(
                youtubeNativeCcTrackCount = trackCount.coerceAtLeast(0),
                youtubeNativeCcLang = activeLang.trim(),
            )
        }
    }

    fun onYouTubeEmbedFallback(errorCode: Int) {
        _uiState.update { cur ->
            if (cur.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return@update cur
            val note = "Embed restricted ($errorCode) · Switched to YouTube mobile player"
            val nextDiag = if (cur.scoutDiagnosticDetail.isBlank()) {
                note
            } else if (cur.scoutDiagnosticDetail.contains("Switched to YouTube mobile player")) {
                cur.scoutDiagnosticDetail
            } else {
                "$note · ${cur.scoutDiagnosticDetail}"
            }
            cur.copy(
                scoutDiagnosticDetail = nextDiag,
                statusBanner = "Creator blocked iframe embed ($errorCode) — switched to YouTube mobile player",
            )
        }
    }

    fun dismissSpeakerToast() {
        speakerToastJob?.cancel()
        _uiState.update { it.copy(activeSpeakerToast = null) }
    }

    private fun emitSpeakerGlossaryToasts(before: List<Speaker>, after: List<Speaker>) {
        val beforeById = before.associateBy { it.id }
        val toastCandidate = after.firstNotNullOfOrNull { spk ->
            val prev = beforeById[spk.id]
            when {
                prev != null &&
                    !prev.isConfirmedCharacter &&
                    spk.isConfirmedCharacter -> {
                    SpeakerGlossaryToast(
                        id = ++nextSpeakerToastId,
                        speakerId = spk.id,
                        isRename = true,
                        title = "Speaker confirmed",
                        detail = "${prev.displayName} → ${spk.displayName}",
                        accentIndex = spk.accentIndex,
                        isUnknown = false,
                    )
                }
                prev != null &&
                    !prev.displayName.equals(spk.displayName, ignoreCase = true) &&
                    spk.isConfirmedCharacter -> {
                    SpeakerGlossaryToast(
                        id = ++nextSpeakerToastId,
                        speakerId = spk.id,
                        isRename = true,
                        title = "Speaker identified",
                        detail = "${prev.displayName} → ${spk.displayName}",
                        accentIndex = spk.accentIndex,
                        isUnknown = false,
                    )
                }
                prev == null && (spk.isConfirmedCharacter || spk.visualVoiceNotes.isNotBlank() || spk.isRolePlaceholder) -> {
                    SpeakerGlossaryToast(
                        id = ++nextSpeakerToastId,
                        speakerId = spk.id,
                        isRename = false,
                        title = if (spk.isConfirmedCharacter) "Speaker confirmed" else "New speaker spotted",
                        detail = if (spk.isConfirmedCharacter) {
                            spk.displayName
                        } else {
                            spk.visualVoiceNotes.ifBlank { spk.displayName }.take(40)
                        },
                        accentIndex = spk.accentIndex,
                        isUnknown = !spk.isConfirmedCharacter,
                    )
                }
                else -> null
            }
        } ?: return

        speakerToastJob?.cancel()
        _uiState.update { it.copy(activeSpeakerToast = toastCandidate) }
        speakerToastJob = viewModelScope.launch {
            delay(4_500L)
            _uiState.update { cur ->
                if (cur.activeSpeakerToast?.id == toastCandidate.id) {
                    cur.copy(activeSpeakerToast = null)
                } else {
                    cur
                }
            }
        }
    }

    fun onWebStreamPlaybackState(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        _uiState.update { state ->
            if (state.project.videoSource.kind != VideoSourceKind.WEB_STREAM) return@update state
            val updatedProj = if (durationMs > 1000L && durationMs != state.project.videoSource.durationMs) {
                state.project.copy(
                    videoSource = state.project.videoSource.copy(durationMs = durationMs),
                    shots = listOf(Shot("shot_1", 0L, durationMs, cutType = "WEB_STREAM")),
                )
            } else {
                state.project
            }
            val safePos = positionMs.coerceAtLeast(0L)
            playbackClockAnchor.reconcileRemoteSample(
                reportedPosMs = safePos,
                speed = state.playbackSpeed,
                playing = isPlaying,
            )
            state.copy(
                project = updatedProj,
                isPlaying = isPlaying,
                currentTimeMs = safePos,
                placedBalloons = layoutEngine.computeLayout(updatedProj, safePos, state.styleSettings, currentLiveFaces),
            )
        }
        maybeTriggerRollingWindowEnhancement(positionMs.coerceAtLeast(0L))
        maybeAutoSyncScoutFromViewer()
    }

    fun onWebStreamCapturedCaptions(rawJson: String) {
        val current = _uiState.value.project
        if (current.videoSource.kind != VideoSourceKind.WEB_STREAM) return
        if (current.subtitleSource == SubtitleSourceKind.UPLOADED_FILE) return
        if (rawJson.isBlank() || rawJson == lastCapturedWebTrackSignature) return

        val capturedEvents = parseCapturedWebTrackJson(rawJson, current.shots.firstOrNull()?.id ?: "shot_1")
        if (capturedEvents.isEmpty()) return

        lastCapturedWebTrackSignature = rawJson
        viewModelScope.launch {
            val (speakers, attributed) = attributionEngine.assignSpeakers(
                events = capturedEvents,
                shots = current.shots,
                faceTracks = current.faceTracks,
                existingSpeakers = current.speakers,
                rememberedCharacterColors = _uiState.value.characterColorMemory,
            )
            updateProject(
                current.copy(
                    subtitleSource = SubtitleSourceKind.EMBEDDED_TRACK,
                    speakers = speakers,
                    dialogueEvents = attributed,
                ),
                banner = "Captured ${attributed.size} web captions — overriding bottom track with balloons",
            )
            val needsTranslation = !current.sourceLanguage.equals(current.targetLanguage, ignoreCase = true)
            if (needsTranslation) {
                translateCurrentProjectWithAi(silentBackground = true)
            } else {
                analyzeSpeakersWithContextAndAudio(silentBackground = true)
            }
        }
    }

    fun toggleDualInstanceLookahead() {
        val nextEnabled = !_uiState.value.dualInstanceEnabled
        if (!nextEnabled) {
            introJob?.cancel()
            lookaheadJob?.cancel()
            _uiState.update {
                it.copy(
                    dualInstanceEnabled = false,
                    isCinemaIntroActive = false,
                    introRemainingMs = 0L,
                    isOpeningSpeakersReady = false,
                    scoutStatusText = "Single-instance mode",
                    statusBanner = "7s dual-instance lookahead paused",
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    dualInstanceEnabled = true,
                    statusBanner = "7s dual-instance lookahead enabled (Scout +7s ahead)",
                )
            }
            val proj = _uiState.value.project
            when (proj.videoSource.kind) {
                VideoSourceKind.LOCAL_MP4 -> {
                    startSevenSecondIntro(
                        projectId = proj.id,
                        initialStatus = "Scout decoding 7s ahead from local audio & faces...",
                        requireFullSevenSeconds = false,
                    )
                    startRollingMediaLookahead(
                        projectId = proj.id,
                        videoUri = Uri.parse(proj.videoSource.uriString),
                        httpUrl = null,
                        sourceLanguage = proj.sourceLanguage,
                        targetLanguage = proj.targetLanguage,
                        hasPreloadedSubtitles = LookaheadWindowPlanner.hasRealSubtitles(proj.dialogueEvents),
                    )
                }
                VideoSourceKind.YOUTUBE_COMPLIANT,
                VideoSourceKind.WEB_STREAM -> {
                    startWebScoutPlaybackFromStart(proj.id)
                }
                VideoSourceKind.DEMO_SCENARIO -> Unit
            }
        }
    }

    fun toggleScoutMonitorPip() {
        _uiState.update { it.copy(showScoutMonitorPip = !it.showScoutMonitorPip) }
    }

    fun skipCinemaIntro() {
        if (!_uiState.value.isCinemaIntroActive) return
        introJob?.cancel()
        finishIntroAndStartViewer()
    }

    fun onScoutYouTubePlaybackState(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        handleScoutWebPlaybackUpdate(VideoSourceKind.YOUTUBE_COMPLIANT, positionMs, durationMs, isPlaying)
    }

    fun onScoutWebStreamPlaybackState(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        handleScoutWebPlaybackUpdate(VideoSourceKind.WEB_STREAM, positionMs, durationMs, isPlaying)
    }

    private fun handleScoutWebPlaybackUpdate(
        expectedKind: VideoSourceKind,
        scoutPosMs: Long,
        durationMs: Long,
        isScoutPlaying: Boolean,
    ) {
        val state = _uiState.value
        if (!state.dualInstanceEnabled || state.project.videoSource.kind != expectedKind) return

        val effectiveDuration = maxOf(state.project.videoSource.durationMs, durationMs).coerceAtLeast(10_000L)
        val safeScoutPos = scoutPosMs.coerceAtLeast(0L)
        val syncCmd = LookaheadWindowPlanner.computeScoutSyncCommand(
            viewerPosMs = state.currentTimeMs,
            scoutPosMs = safeScoutPos,
            durationMs = effectiveDuration,
            isViewerPlaying = state.isPlaying,
            isScoutPlaying = isScoutPlaying,
            introRemainingMs = state.introRemainingMs,
        )

        var newPlayReq = state.scoutPlayRequest
        var newSeekReq = state.scoutSeekRequest
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        when (syncCmd) {
            is ScoutSyncCommand.PlayForward -> {
                if (nowElapsed - lastScoutSeekSentAtMs > 1_200L) {
                    newPlayReq = (++nextScoutCommandSeq) to true
                }
            }
            is ScoutSyncCommand.PauseWithLead -> {
                newPlayReq = (++nextScoutCommandSeq) to false
            }
            is ScoutSyncCommand.SeekAndPlay -> {
                val bigTargetJump = lastScoutSeekTargetMs < 0L ||
                    kotlin.math.abs(syncCmd.targetMs - lastScoutSeekTargetMs) > 10_000L
                val cooldownElapsed = nowElapsed - lastScoutSeekSentAtMs > 2_400L
                if (cooldownElapsed || bigTargetJump) {
                    lastScoutSeekSentAtMs = nowElapsed
                    lastScoutSeekTargetMs = syncCmd.targetMs
                    val seq = ++nextScoutCommandSeq
                    newSeekReq = seq to syncCmd.targetMs
                    if (state.isPlaying || state.introRemainingMs > 0L || state.hasPendingPlayIntro) {
                        newPlayReq = seq to true
                    }
                }
            }
            ScoutSyncCommand.Hold -> Unit
        }

        val leadSec = ((safeScoutPos - state.currentTimeMs).coerceAtLeast(0L) / 1000.0)
        _uiState.update { cur ->
            cur.copy(
                scoutTimeMs = safeScoutPos,
                scoutedUpToMs = maxOf(cur.scoutedUpToMs, safeScoutPos),
                scoutStatusText = when {
                    cur.isCinemaIntroActive && cur.isOpeningSpeakersReady ->
                        "Opening speakers ready — entering stream..."
                    cur.isCinemaIntroActive ->
                        "Scout ahead @ ${SubtitleParser.formatCompactTime(safeScoutPos)} — preparing opening captions..."
                    cur.hasPendingPlayIntro ->
                        "Scout gathering silently @ ${SubtitleParser.formatCompactTime(safeScoutPos)}..."
                    else ->
                        "Scout running +${String.format(java.util.Locale.US, "%.1f", leadSec)}s ahead"
                },
                scoutPlayRequest = newPlayReq,
                scoutSeekRequest = newSeekReq,
            )
        }
    }

    fun onScoutWebStreamDirectMediaUrl(mediaUrl: String) {
        val clean = mediaUrl.trim()
        if (clean.isEmpty()) return
        val curUrl = _uiState.value.viewerWebNav.currentUrl.ifBlank { _uiState.value.project.videoSource.uriString }
        onWebStreamSniffedMediaStream(
            SniffedWebMediaStream(
                url = clean,
                referer = curUrl,
                isHlsPlaylist = WebNavigationGuard.isHlsPlaylistUrl(clean),
            )
        )
    }

    fun onWebStreamSniffedMediaStream(stream: SniffedWebMediaStream) {
        val clean = stream.url.trim()
        val state = _uiState.value
        val proj = state.project
        if (!state.dualInstanceEnabled || proj.videoSource.kind != VideoSourceKind.WEB_STREAM) return
        if (clean.isEmpty() || !WebNavigationGuard.isLikelyPlayableMediaUrl(clean)) return

        val prevUrl = discoveredWebMediaUrl
        val prevIsHls = prevUrl?.let { WebNavigationGuard.isHlsPlaylistUrl(it) } == true
        val newIsHls = stream.isHlsPlaylist || WebNavigationGuard.isHlsPlaylistUrl(clean)
        val reqHeaders = stream.toRequestHeaders()
        val shouldUpgrade = prevUrl.isNullOrBlank() ||
            (!prevIsHls && newIsHls) ||
            (newIsHls && !clean.equals(prevUrl, ignoreCase = true) && clean.contains("master", ignoreCase = true)) ||
            (clean.equals(prevUrl, ignoreCase = true) && reqHeaders.isNotEmpty() && discoveredWebMediaHeaders.isEmpty())

        if (!shouldUpgrade && lookaheadJob?.isActive == true) return

        discoveredWebMediaUrl = clean
        if (reqHeaders.isNotEmpty()) {
            discoveredWebMediaHeaders = reqHeaders
        }
        if (!LookaheadWindowPlanner.hasLoadedSubtitlesLocked(proj.subtitleSource, proj.dialogueEvents)) {
            if (!LookaheadWindowPlanner.hasRealSubtitles(proj.dialogueEvents) &&
                !clean.equals(prevUrl, ignoreCase = true)
            ) {
                scoutCompletedWindows.clear()
            }
            startRollingMediaLookahead(
                projectId = proj.id,
                videoUri = null,
                httpUrl = clean,
                httpHeaders = discoveredWebMediaHeaders,
                sourceLanguage = proj.sourceLanguage,
                targetLanguage = proj.targetLanguage,
                hasPreloadedSubtitles = false,
            )
        }
    }

    fun onScoutWebStreamAudioSlice(startMs: Long, base64Audio: String, mimeType: String) {
        val state = _uiState.value
        val proj = state.project
        if (!state.dualInstanceEnabled || proj.videoSource.kind != VideoSourceKind.WEB_STREAM) return
        if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(proj.subtitleSource, proj.dialogueEvents)) {
            return
        }
        // Avoid duplicate cloud transcription when HTTP/HLS stream lookahead is already producing cues
        if (!discoveredWebMediaUrl.isNullOrBlank() &&
            lookaheadJob?.isActive == true &&
            LookaheadWindowPlanner.hasRealSubtitles(proj.dialogueEvents)
        ) {
            return
        }
        val bucketKey = (startMs / 5_000L) * 5_000L
        if (!scoutAudioSliceTimestamps.add(bucketKey)) return

        viewModelScope.launch {
            val audioBytes = runCatching { Base64.decode(base64Audio, Base64.DEFAULT) }.getOrNull()
                ?: return@launch
            if (audioBytes.size < 1800) return@launch

            val res = translationAiService.transcribeAndTranslateAudio(
                wavBytes = audioBytes,
                chunkStartMs = startMs,
                sourceLanguage = proj.sourceLanguage,
                targetLanguage = proj.targetLanguage,
                glossary = (proj.glossary + _uiState.value.glossaryEntries).distinctBy { it.term.trim().lowercase() },
                audioMimeType = mimeType.ifBlank { "audio/webm" },
            )
            val cues = res.getOrNull().orEmpty()
            if (cues.isEmpty()) return@launch

            val latestProj = _uiState.value.project
            if (latestProj.id != proj.id) return@launch

            val rawEvents = cues.map { c ->
                DialogueEvent(
                    id = SubtitleParser.newEventId(),
                    startTimeMs = c.startMs,
                    endTimeMs = c.endMs,
                    sourceText = c.sourceText,
                    translatedText = c.naturalTranslation,
                    literalText = c.literalTranslation,
                    romanization = c.romanization,
                    speakerId = null,
                    speakerConfidence = 0.56f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "shot_1",
                    dialogueType = c.dialogueType,
                    placementMode = if (c.dialogueType == DialogueType.SPOKEN) PlacementMode.NEUTRAL_FALLBACK else PlacementMode.EDGE_DOCKED,
                    contextCueReason = c.contextReason,
                    culturalNote = c.culturalNote,
                )
            }
            val paced = SubtitleParser.applyReadingPacingGuardrails(rawEvents)
            val merged = LookaheadWindowPlanner.mergeWindowDialogueEvents(
                existing = latestProj.dialogueEvents.filterNot { it.sourceText.startsWith("Web Cinema ready") },
                incoming = paced,
                windowStartMs = startMs,
                windowEndMs = startMs + LookaheadWindowPlanner.WINDOW_STEP_MS + 1_000L,
            )
            val (updatedSpeakers, attributed) = attributionEngine.assignSpeakers(
                events = merged,
                shots = latestProj.shots,
                faceTracks = latestProj.faceTracks,
                existingSpeakers = latestProj.speakers,
                rememberedCharacterColors = _uiState.value.characterColorMemory,
            )
            _uiState.update { cur ->
                cur.copy(
                    scoutedUpToMs = maxOf(cur.scoutedUpToMs, startMs + 7_000L),
                    scoutStatusText = "Scout heard live audio up to ${SubtitleParser.formatCompactTime(startMs + 7_000L)}",
                )
            }
            updateProject(
                latestProj.copy(
                    subtitleSource = SubtitleSourceKind.AI_DRAFT,
                    speakers = updatedSpeakers,
                    dialogueEvents = attributed,
                )
            )
        }
    }

    fun onViewerWebNavChanged(nav: WebNavigationState) {
        _uiState.update { cur -> cur.copy(viewerWebNav = nav) }
        maybeAutoSyncScoutFromViewer()
    }

    fun onScoutWebNavChanged(nav: WebNavigationState) {
        _uiState.update { cur -> cur.copy(scoutWebNav = nav) }
        maybeAutoSyncScoutFromViewer()
    }

    private fun maybeAutoSyncScoutFromViewer() {
        val state = _uiState.value
        if (!state.dualInstanceEnabled || state.project.videoSource.kind != VideoSourceKind.WEB_STREAM) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val shouldSync = WebNavigationGuard.shouldAutoSyncScoutFromViewer(
            viewerNav = state.viewerWebNav,
            scoutNav = state.scoutWebNav,
            scoutPosMs = state.scoutTimeMs,
            viewerPosMs = state.currentTimeMs,
            lastAutoSyncAtMs = lastAutoScoutSyncAtMs,
            nowMs = nowMs,
        )
        if (!shouldSync) return

        val targetUrl = state.viewerWebNav.currentUrl.ifBlank { state.project.videoSource.uriString }
        if (targetUrl.isBlank()) return
        lastAutoScoutSyncAtMs = nowMs
        _uiState.update { cur ->
            cur.copy(
                scoutWebNavCommand = WebNavCommand(
                    seq = ++nextScoutNavSeq,
                    action = WebNavAction.LOAD_URL,
                    targetUrl = targetUrl,
                ),
                scoutStatusText = "Scout synced to viewer video page...",
            )
        }
    }

    fun viewerWebGoBack() {
        _uiState.update { cur ->
            cur.copy(viewerWebNavCommand = WebNavCommand(++nextViewerNavSeq, WebNavAction.BACK))
        }
    }

    fun viewerWebGoForward() {
        _uiState.update { cur ->
            cur.copy(viewerWebNavCommand = WebNavCommand(++nextViewerNavSeq, WebNavAction.FORWARD))
        }
    }

    fun viewerWebRefresh() {
        _uiState.update { cur ->
            cur.copy(viewerWebNavCommand = WebNavCommand(++nextViewerNavSeq, WebNavAction.REFRESH))
        }
    }

    fun viewerWebResetSource() {
        _uiState.update { cur ->
            cur.copy(
                viewerWebNavCommand = WebNavCommand(++nextViewerNavSeq, WebNavAction.RESET_TO_SOURCE),
                viewerWebNav = cur.viewerWebNav.copy(lastBlockedUrl = null, isAdRedirectSuspected = false),
            )
        }
    }

    fun viewerWebAllowOnce() {
        val state = _uiState.value
        val blockedUrl = state.viewerWebNav.lastBlockedUrl ?: return
        val host = state.viewerWebNav.lastBlockedHost ?: WebNavigationGuard.extractHost(blockedUrl)
        _uiState.update { cur ->
            cur.copy(
                viewerWebNavCommand = WebNavCommand(
                    seq = ++nextViewerNavSeq,
                    action = WebNavAction.ALLOW_TEMP_HOST,
                    targetUrl = blockedUrl,
                    host = host,
                ),
                viewerWebNav = cur.viewerWebNav.copy(lastBlockedUrl = null),
                statusBanner = "Allowed redirect once to $host",
            )
        }
    }

    fun openScoutBrowserModal() {
        _uiState.update { it.copy(showScoutBrowserModal = true) }
    }

    fun closeScoutBrowserModal() {
        _uiState.update { it.copy(showScoutBrowserModal = false) }
    }

    fun scoutWebGoBack() {
        _uiState.update { cur ->
            cur.copy(scoutWebNavCommand = WebNavCommand(++nextScoutNavSeq, WebNavAction.BACK))
        }
    }

    fun scoutWebGoForward() {
        _uiState.update { cur ->
            cur.copy(scoutWebNavCommand = WebNavCommand(++nextScoutNavSeq, WebNavAction.FORWARD))
        }
    }

    fun scoutWebRefresh() {
        _uiState.update { cur ->
            cur.copy(scoutWebNavCommand = WebNavCommand(++nextScoutNavSeq, WebNavAction.REFRESH))
        }
    }

    fun scoutWebResetSource() {
        _uiState.update { cur ->
            cur.copy(
                scoutWebNavCommand = WebNavCommand(++nextScoutNavSeq, WebNavAction.RESET_TO_SOURCE),
                scoutWebNav = cur.scoutWebNav.copy(lastBlockedUrl = null, isAdRedirectSuspected = false),
            )
        }
    }

    fun scoutWebSyncFromViewer() {
        val state = _uiState.value
        val targetUrl = state.viewerWebNav.currentUrl.ifBlank { state.project.videoSource.uriString }
        lastAutoScoutSyncAtMs = android.os.SystemClock.elapsedRealtime()
        val targetLeadMs = LookaheadWindowPlanner.clampScoutTargetMs(
            viewerPosMs = state.currentTimeMs,
            durationMs = state.project.videoSource.durationMs,
        )
        val cmdSeq = ++nextScoutCommandSeq
        _uiState.update { cur ->
            cur.copy(
                scoutWebNavCommand = WebNavCommand(
                    seq = ++nextScoutNavSeq,
                    action = WebNavAction.LOAD_URL,
                    targetUrl = targetUrl,
                ),
                scoutWebNav = cur.scoutWebNav.copy(lastBlockedUrl = null, isAdRedirectSuspected = false),
                scoutSeekRequest = cmdSeq to targetLeadMs,
                scoutPlayRequest = cmdSeq to true,
                statusBanner = "Synced Scout browser to Viewer page",
            )
        }
    }

    fun scoutWebAllowOnce() {
        val state = _uiState.value
        val blockedUrl = state.scoutWebNav.lastBlockedUrl ?: return
        val host = state.scoutWebNav.lastBlockedHost ?: WebNavigationGuard.extractHost(blockedUrl)
        _uiState.update { cur ->
            cur.copy(
                scoutWebNavCommand = WebNavCommand(
                    seq = ++nextScoutNavSeq,
                    action = WebNavAction.ALLOW_TEMP_HOST,
                    targetUrl = blockedUrl,
                    host = host,
                ),
                scoutWebNav = cur.scoutWebNav.copy(lastBlockedUrl = null),
            )
        }
    }

    fun onYouTubeTimedTextCaptured(
        observedUrl: String,
        observedBody: String,
        cookies: String?,
        userAgent: String?,
    ) {
        val state = _uiState.value
        val proj = state.project
        if (proj.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return
        if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(proj.subtitleSource, proj.dialogueEvents)) {
            return
        }
        // While Best Model (Gemini 3.8 Flash) credits are active, stash passive WebView CC
        // so it only replaces AI if credits run dry or user switches to Free CC Only.
        if (!LookaheadWindowPlanner.shouldAllowPassiveWebTimedTextOverride(
                quotaSaverMode = state.quotaSaverMode,
                quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                subtitleSource = proj.subtitleSource,
                events = proj.dialogueEvents,
            )
        ) {
            pendingWebTimedTextFallback = PendingWebTimedText(
                observedUrl = observedUrl,
                observedBody = observedBody,
                cookies = cookies,
                userAgent = userAgent,
            )
            return
        }

        val vid = proj.videoSource.youtubeVideoId.orEmpty()
        val langKey = Regex("[?&]lang=([^&]+)").find(observedUrl)?.groupValues?.getOrNull(1) ?: "default"
        val key = "$vid:$langKey:${if (observedBody.isNotBlank()) "body" else "url"}"
        if (!capturedYouTubeTimedTextKeys.add(key)) return

        viewModelScope.launch {
            val cues = YouTubeCaptionFetcher.fetchTimedTextFromObservedUrl(
                observedUrl = observedUrl,
                cookies = cookies,
                observedBody = observedBody,
                userAgent = userAgent,
            )
            if (cues.isEmpty()) {
                capturedYouTubeTimedTextKeys.remove(key)
                return@launch
            }
            val latestState = _uiState.value
            val latest = latestState.project
            if (latest.id != proj.id ||
                !LookaheadWindowPlanner.shouldAllowPassiveWebTimedTextOverride(
                    quotaSaverMode = latestState.quotaSaverMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    subtitleSource = latest.subtitleSource,
                    events = latest.dialogueEvents,
                )
            ) {
                return@launch
            }
            // Free YouTube captions arrived in fallback/free mode — cancel active AI Scout immediately
            youtubeTranscriptJob?.cancel()
            applyFetchedYouTubeCues(
                baseProject = latest,
                cues = cues,
                fetchedTitle = null,
                fetchedDurationMs = null,
                fetchedLanguageCode = langKey.takeIf { it != "default" },
            )
        }
    }

    private fun applyFetchedYouTubeCues(
        baseProject: DialogueLensProject,
        cues: List<RawSubtitleCue>,
        fetchedTitle: String?,
        fetchedDurationMs: Long?,
        fetchedLanguageCode: String?,
        fetchedChannelName: String? = null,
        fetchedDescription: String? = null,
    ) {
        if (cues.isEmpty()) return
        val updatedTitle = fetchedTitle?.takeIf { it.isNotBlank() } ?: baseProject.title
        val updatedChannel = fetchedChannelName?.takeIf { it.isNotBlank() } ?: baseProject.videoSource.channelName
        val updatedDescription = fetchedDescription?.takeIf { it.isNotBlank() } ?: baseProject.videoSource.description
        val updatedDuration = maxOf(
            baseProject.videoSource.durationMs,
            fetchedDurationMs ?: 0L,
            cues.maxOfOrNull { it.endTimeMs } ?: 0L,
        ).coerceAtLeast(60_000L)

        val resolvedSourceLang = if (baseProject.sourceLanguage.equals("Auto-Detect", ignoreCase = true)) {
            isoToFriendlyLanguage(fetchedLanguageCode, baseProject.sourceLanguage)
        } else {
            baseProject.sourceLanguage
        }
        val rawEvents = SubtitleParser.applyReadingPacingGuardrails(
            cues.map { cue ->
                DialogueEvent(
                    id = SubtitleParser.newEventId(),
                    startTimeMs = cue.startTimeMs,
                    endTimeMs = cue.endTimeMs,
                    sourceText = cue.text,
                    translatedText = cue.text,
                    speakerId = null,
                    speakerConfidence = 0.48f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "shot_1",
                    dialogueType = cue.inferredType,
                    placementMode = if (cue.inferredType == DialogueType.SPOKEN) {
                        PlacementMode.NEUTRAL_FALLBACK
                    } else {
                        PlacementMode.EDGE_DOCKED
                    },
                )
            }
        )
        val updatedShots = listOf(Shot("shot_1", 0L, updatedDuration, cutType = "EMBEDDED_YOUTUBE"))
        val (updatedSpeakers, attributedEvents) = attributionEngine.assignSpeakers(
            events = rawEvents,
            shots = updatedShots,
            faceTracks = emptyList(),
            existingSpeakers = baseProject.speakers,
            rememberedCharacterColors = _uiState.value.characterColorMemory,
        )
        val enrichedProject = baseProject.copy(
            title = updatedTitle,
            sourceLanguage = resolvedSourceLang,
            subtitleSource = SubtitleSourceKind.EMBEDDED_TRACK,
            videoSource = baseProject.videoSource.copy(
                durationMs = updatedDuration,
                channelName = updatedChannel,
                description = updatedDescription,
            ),
            shots = updatedShots,
            speakers = updatedSpeakers,
            dialogueEvents = attributedEvents,
        )
        val captionLabel = fetchedLanguageCode?.uppercase() ?: resolvedSourceLang
        updateProject(
            enrichedProject,
            banner = "Captured ${attributedEvents.size} free YouTube captions ($captionLabel) — $0 credits",
        )
        _uiState.update {
            it.copy(
                isTranslatingAi = false,
                scoutedUpToMs = maxOf(it.scoutedUpToMs, updatedDuration),
                scoutStatusText = "${attributedEvents.size} CC bubbles ready ($captionLabel · $0)",
                scoutCaptionState = ScoutCaptionState.Ready(
                    sourceLabel = "CC · Free",
                    cueCount = attributedEvents.size,
                    speakerCount = updatedSpeakers.size,
                ),
            )
        }
        persistYouTubeVideoCache(enrichedProject, scoutedUpToMs = updatedDuration)
        val mode = _uiState.value.quotaSaverMode
        if (mode == QuotaSaverMode.PRO_ACCURACY) {
            runBackgroundCastDiscovery(enrichedProject.id)
        }
        val needsTranslation = !resolvedSourceLang.equals(baseProject.targetLanguage, ignoreCase = true) &&
            !resolvedSourceLang.equals("Auto-Detect", ignoreCase = true)
        if (needsTranslation) {
            translateCurrentProjectWithAi(silentBackground = true)
        } else if (mode == QuotaSaverMode.PRO_ACCURACY) {
            analyzeSpeakersWithContextAndAudio(silentBackground = true)
        }
    }

    private fun startSevenSecondIntro(
        projectId: String,
        initialStatus: String,
        requireFullSevenSeconds: Boolean = true,
        resetScoutProgress: Boolean = false,
    ) {
        introJob?.cancel()
        if (!_uiState.value.dualInstanceEnabled) return

        // Hold Viewer paused at 0s behind the duration-scaled Smart Pre-Roll slate
        val initialProj = _uiState.value.project
        val kind = initialProj.videoSource.kind
        val initialDurationMs = initialProj.videoSource.durationMs
        val initMaxIntroMs = LookaheadWindowPlanner.recommendedIntroMaxDurationMs(initialDurationMs)
        val initRequiredWins = LookaheadWindowPlanner.recommendedOpeningLeadWindows(initialDurationMs)

        if (kind == VideoSourceKind.LOCAL_MP4) {
            exoPlayer.pause()
            exoPlayer.seekTo(0L)
        }
        val pauseViewerSeq = if (kind == VideoSourceKind.YOUTUBE_COMPLIANT || kind == VideoSourceKind.WEB_STREAM) {
            ++nextStreamCommandSeq
        } else {
            null
        }

        _uiState.update { state ->
            state.copy(
                isCinemaIntroActive = true,
                introRemainingMs = initMaxIntroMs,
                introTotalDurationMs = initMaxIntroMs,
                requiredOpeningLeadWindows = initRequiredWins,
                isOpeningSpeakersReady = LookaheadWindowPlanner.hasRealSubtitles(state.project.dialogueEvents) &&
                    LookaheadWindowPlanner.isOpeningWindowReady(state.project, autoAnalyzedEventIds) &&
                    maxOf(state.scoutCompletedWindows, scoutCompletedWindows.size) >= initRequiredWins,
                currentTimeMs = 0L,
                isPlaying = false,
                scoutTimeMs = if (resetScoutProgress) 0L else state.scoutTimeMs,
                scoutedUpToMs = if (resetScoutProgress) 0L else state.scoutedUpToMs,
                scoutStatusText = initialStatus,
                streamPlayRequest = pauseViewerSeq?.let { it to false } ?: state.streamPlayRequest,
            )
        }

        introJob = viewModelScope.launch {
            var elapsed = 0L
            while (isActive) {
                delay(100L)
                val cur = _uiState.value
                if (cur.project.id != projectId) return@launch
                val curDurationMs = cur.project.videoSource.durationMs
                val activeMaxIntroMs = LookaheadWindowPlanner.recommendedIntroMaxDurationMs(curDurationMs)
                val requiredLeadWins = LookaheadWindowPlanner.recommendedOpeningLeadWindows(curDurationMs)
                if (elapsed >= activeMaxIntroMs) break
                elapsed = (elapsed + 100L).coerceAtMost(activeMaxIntroMs)
                val remaining = (activeMaxIntroMs - elapsed).coerceAtLeast(0L)

                val hasRealSubs = LookaheadWindowPlanner.hasRealSubtitles(cur.project.dialogueEvents)
                val openingWindow0Ready = LookaheadWindowPlanner.isOpeningWindowReady(
                    project = cur.project,
                    refinedEventIds = autoAnalyzedEventIds,
                ) || (scoutCompletedWindows.contains(0L) && hasRealSubs) ||
                    (cur.scoutCompletedWindows > 0 && hasRealSubs)
                val completedOpenCount = if (cur.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                    maxOf(cur.scoutCompletedWindows, scoutCompletedWindows.size)
                } else {
                    if (openingWindow0Ready) requiredLeadWins else 0
                }
                val allOpeningWinsReady = openingWindow0Ready &&
                    hasRealSubs &&
                    completedOpenCount >= requiredLeadWins

                val effectiveScoutPos = maxOf(cur.scoutTimeMs, cur.scoutedUpToMs)
                val readyStatus = if (allOpeningWinsReady) {
                    if (requireFullSevenSeconds) {
                        "Cast & bubbles ready — entering video..."
                    } else if (elapsed < LookaheadWindowPlanner.INTRO_MIN_BUFFER_MS) {
                        "Opening lead & cast ready — starting in a moment..."
                    } else {
                        "Opening cast & bubbles ready — entering video..."
                    }
                } else if (openingWindow0Ready && hasRealSubs && requiredLeadWins > 1) {
                    "Opening cast locked · buffering lead scene ${minOf(completedOpenCount + 1, requiredLeadWins)}/$requiredLeadWins..."
                } else {
                    cur.scoutStatusText
                }

                _uiState.update {
                    it.copy(
                        introRemainingMs = remaining,
                        introTotalDurationMs = activeMaxIntroMs,
                        requiredOpeningLeadWindows = requiredLeadWins,
                        isOpeningSpeakersReady = allOpeningWinsReady,
                        scoutStatusText = readyStatus,
                    )
                }

                if (!requireFullSevenSeconds &&
                    LookaheadWindowPlanner.shouldAutoReleaseAdaptiveIntro(
                        elapsedMs = elapsed,
                        scoutPosMs = effectiveScoutPos,
                        hasRealSubtitles = hasRealSubs,
                        openingWindowRefined = openingWindow0Ready,
                        durationMs = curDurationMs,
                        completedOpeningWindows = completedOpenCount,
                        requiredOpeningWindows = requiredLeadWins,
                    )
                ) {
                    break
                }
            }
            if (isActive && _uiState.value.project.id == projectId) {
                finishIntroAndStartViewer()
            }
        }
    }

    private fun finishIntroAndStartViewer() {
        val state = _uiState.value
        val kind = state.project.videoSource.kind
        playbackClockAnchor.reset(0L, state.playbackSpeed, playing = kind != VideoSourceKind.DEMO_SCENARIO)
        when (kind) {
            VideoSourceKind.LOCAL_MP4 -> {
                exoPlayer.seekTo(0L)
                exoPlayer.play()
            }
            VideoSourceKind.YOUTUBE_COMPLIANT,
            VideoSourceKind.WEB_STREAM -> {
                val playSeq = ++nextStreamCommandSeq
                val seekSeq = ++nextStreamCommandSeq
                _uiState.update { cur ->
                    cur.copy(
                        isCinemaIntroActive = false,
                        introRemainingMs = 0L,
                        isOpeningSpeakersReady = false,
                        isPlaying = true,
                        currentTimeMs = 0L,
                        youtubeSeekRequest = seekSeq to 0L,
                        streamPlayRequest = playSeq to true,
                    )
                }
                return
            }
            VideoSourceKind.DEMO_SCENARIO -> Unit
        }
        _uiState.update { cur ->
            cur.copy(
                isCinemaIntroActive = false,
                introRemainingMs = 0L,
                isOpeningSpeakersReady = false,
                currentTimeMs = 0L,
                isPlaying = kind == VideoSourceKind.LOCAL_MP4,
            )
        }
    }

    private fun startWebScoutPlaybackFromStart(projectId: String) {
        val proj = _uiState.value.project
        if (proj.id != projectId || !_uiState.value.dualInstanceEnabled) return
        val scoutSeq = ++nextScoutCommandSeq
        _uiState.update { cur ->
            cur.copy(
                scoutSeekRequest = scoutSeq to 0L,
                scoutPlayRequest = scoutSeq to true,
            )
        }
    }

    fun toggleMute() {
        val nextMuted = !_uiState.value.isMuted
        exoPlayer.volume = if (nextMuted) 0f else 1f
        _uiState.update { it.copy(isMuted = nextMuted) }
    }

    fun setDisplayMode(mode: CaptionDisplayMode) {
        val normalized = mode.normalizedPrimaryMode()
        _uiState.update { it.copy(displayMode = normalized) }
        viewModelScope.launch { projectStore.saveDisplayMode(normalized) }
    }

    fun setTranslationMode(mode: TranslationMode) {
        updateProject(_uiState.value.project.copy(translationMode = mode))
    }

    fun updateStyleSettings(settings: BalloonStyleSettings) {
        _uiState.update { state ->
            val proj = state.project.copy(styleSettings = settings)
            state.copy(
                styleSettings = settings,
                project = proj,
                placedBalloons = layoutEngine.computeLayout(proj, state.currentTimeMs, settings, currentLiveFaces),
            )
        }
        viewModelScope.launch { projectStore.saveStyleSettings(settings) }
    }

    fun cycleSpeakerLabelMode() {
        val currentStyle = _uiState.value.styleSettings
        val nextMode = when (currentStyle.labelMode) {
            SpeakerLabelMode.AUTO_FIRST_10 -> SpeakerLabelMode.ALWAYS
            SpeakerLabelMode.ALWAYS -> SpeakerLabelMode.OFF
            SpeakerLabelMode.OFF -> SpeakerLabelMode.AUTO_FIRST_10
        }
        updateStyleSettings(
            currentStyle.copy(
                labelMode = nextMode,
                showSpeakerLabels = nextMode != SpeakerLabelMode.OFF,
            )
        )
    }

    fun cyclePlaybackSpeed() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f)
        val currIdx = speeds.indexOfFirst { kotlin.math.abs(it - _uiState.value.playbackSpeed) < 0.05f }
        val nextSpeed = speeds[(currIdx + 1) % speeds.size]
        val cur = _uiState.value
        if (cur.project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
            exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
        } else {
            playbackClockAnchor.updatePlaybackState(cur.currentTimeMs, nextSpeed, cur.isPlaying)
        }
        _uiState.update { it.copy(playbackSpeed = nextSpeed) }
    }

    fun toggleFullscreenTheater() {
        _uiState.update { it.copy(isFullscreenTheater = !it.isFullscreenTheater) }
    }

    fun exitFullscreenTheater() {
        _uiState.update { it.copy(isFullscreenTheater = false) }
    }

    fun toggleEditMode() {
        _uiState.update { it.copy(isEditMode = !it.isEditMode, selectedEventId = null) }
    }

    fun selectEventForEditing(eventId: String?) {
        _uiState.update { it.copy(selectedEventId = eventId) }
    }

    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    private fun pushUndoSnapshot() {
        undoStack.addLast(_uiState.value.project)
        while (undoStack.size > 15) undoStack.removeFirst()
        _uiState.update { it.copy(canUndo = undoStack.isNotEmpty()) }
    }

    fun undoLastEdit() {
        val previous = if (undoStack.isNotEmpty()) undoStack.removeLast() else return
        layoutEngine.clearSmoothing()
        _uiState.update { state ->
            state.copy(
                project = previous,
                canUndo = undoStack.isNotEmpty(),
                placedBalloons = layoutEngine.computeLayout(previous, state.currentTimeMs, state.styleSettings, currentLiveFaces),
                statusBanner = "Undid last timeline edit",
            )
        }
        if (previous.id != DemoScenarioFactory.DEMO_PROJECT_ID) {
            viewModelScope.launch {
                projectStore.saveProject(previous)
                refreshSavedProjects()
            }
        }
    }

    fun shiftAllDialogueTimings(deltaMs: Long) {
        val current = _uiState.value.project
        if (current.dialogueEvents.isEmpty() || deltaMs == 0L) return
        pushUndoSnapshot()
        val shifted = current.dialogueEvents.map { evt ->
            val newStart = (evt.startTimeMs + deltaMs).coerceAtLeast(0L)
            val dur = (evt.endTimeMs - evt.startTimeMs).coerceAtLeast(350L)
            val newEnd = maxOf(newStart + 350L, (evt.endTimeMs + deltaMs).coerceAtLeast(newStart + dur))
            evt.copy(startTimeMs = newStart, endTimeMs = newEnd)
        }
        val newOffset = current.subtitleOffsetMs + deltaMs
        val sign = if (newOffset >= 0) "+" else ""
        updateProject(
            current.copy(dialogueEvents = shifted, subtitleOffsetMs = newOffset),
            banner = "Shifted subtitle sync (${sign}${newOffset / 1000f}s total)",
        )
    }

    fun addGlossaryEntry(term: String, translation: String, note: String = "") {
        val cleanTerm = term.trim()
        val cleanTrans = translation.trim()
        if (cleanTerm.isEmpty() || cleanTrans.isEmpty()) return
        val newEntry = GlossaryEntry(cleanTerm, cleanTrans, note.trim())
        val updatedGlobal = (_uiState.value.glossaryEntries.filterNot {
            it.term.equals(cleanTerm, ignoreCase = true)
        } + newEntry)
        val current = _uiState.value.project
        val updatedProjGlossary = (current.glossary.filterNot {
            it.term.equals(cleanTerm, ignoreCase = true)
        } + newEntry)
        _uiState.update { it.copy(glossaryEntries = updatedGlobal) }
        viewModelScope.launch { projectStore.saveGlossaryMemory(updatedGlobal) }
        updateProject(current.copy(glossary = updatedProjGlossary), banner = "Saved glossary rule: $cleanTerm → $cleanTrans")
    }

    fun removeGlossaryEntry(term: String) {
        val updatedGlobal = _uiState.value.glossaryEntries.filterNot { it.term.equals(term, ignoreCase = true) }
        val current = _uiState.value.project
        val updatedProjGlossary = current.glossary.filterNot { it.term.equals(term, ignoreCase = true) }
        _uiState.update { it.copy(glossaryEntries = updatedGlobal) }
        viewModelScope.launch { projectStore.saveGlossaryMemory(updatedGlobal) }
        updateProject(current.copy(glossary = updatedProjGlossary), banner = "Removed glossary term: $term")
    }

    fun assignSpeakerToEvent(eventId: String, speakerId: String?) {
        pushUndoSnapshot()
        val current = _uiState.value.project
        val beforeLowCount = current.dialogueEvents.count {
            !it.lockedByUser && it.speakerConfidence < 0.65f
        }
        val updated = attributionEngine.applyUserCorrection(
            project = current,
            eventId = eventId,
            newSpeakerId = speakerId,
        )
        val afterLowCount = updated.dialogueEvents.count {
            !it.lockedByUser && it.speakerConfidence < 0.65f
        }
        val improvedCount = (beforeLowCount - afterLowCount).coerceAtLeast(0)
        val speakerName = updated.speakers.firstOrNull { it.id == speakerId }?.displayName ?: "Unassigned"
        val banner = if (improvedCount > 0) {
            "Assigned to $speakerName · $improvedCount later uncertain line(s) refined"
        } else {
            "Locked speaker: $speakerName"
        }
        updateProject(updated, banner = banner)
    }

    fun openQuickSpeakerPicker(eventId: String) {
        val wasPlaying = _uiState.value.isPlaying
        if (wasPlaying) {
            pausePlayback()
        }
        _uiState.update {
            it.copy(
                selectedEventId = eventId,
                quickPickerEventId = eventId,
                resumeAfterQuickPicker = wasPlaying,
            )
        }
    }

    fun dismissQuickSpeakerPicker(resumePlayback: Boolean = true) {
        val shouldResume = resumePlayback && _uiState.value.resumeAfterQuickPicker && !_uiState.value.isPlaying
        _uiState.update {
            it.copy(
                quickPickerEventId = null,
                resumeAfterQuickPicker = false,
            )
        }
        if (shouldResume) {
            togglePlayPause()
        }
    }

    fun quickAssignSpeakerFromPicker(eventId: String, speakerId: String) {
        assignSpeakerToEvent(eventId, speakerId)
        dismissQuickSpeakerPicker(resumePlayback = true)
    }

    fun quickNameAndAssignSpeakerFromPicker(eventId: String, targetSpeakerId: String?, rawName: String) {
        val trimmed = rawName.trim()
        if (trimmed.length < 2) return
        val current = _uiState.value.project
        val resolvedSpeakerId = targetSpeakerId?.takeIf { id -> current.speakers.any { it.id == id } } ?: run {
            val nextIdx = current.speakers.size.coerceAtMost(11)
            val letter = ('A' + nextIdx)
            val newId = "spk_${letter.lowercaseChar()}"
            if (current.speakers.none { it.id == newId }) {
                val created = Speaker(
                    id = newId,
                    defaultLabel = "Speaker $letter",
                    customName = trimmed,
                    accentIndex = nextIdx % SpeakerColorOrder.PALETTE_SIZE,
                    isLocked = true,
                    isRolePlaceholder = false,
                    isIdentityConfirmed = true,
                    firstAppearedMs = _uiState.value.currentTimeMs,
                )
                updateProject(current.copy(speakers = current.speakers + created))
            }
            newId
        }
        assignSpeakerToEvent(eventId, resolvedSpeakerId)
        renameSpeaker(resolvedSpeakerId, trimmed)
        dismissQuickSpeakerPicker(resumePlayback = true)
    }

    fun quickMarkOffScreenFromPicker(eventId: String, side: DialogueType) {
        pushUndoSnapshot()
        val current = _uiState.value.project
        val updatedEvents = current.dialogueEvents.map { evt ->
            if (evt.id == eventId) {
                evt.copy(
                    dialogueType = side,
                    anchorPosition = null,
                    placementMode = PlacementMode.EDGE_DOCKED,
                    lockedByUser = true,
                )
            } else {
                evt
            }
        }
        updateProject(
            current.copy(dialogueEvents = updatedEvents),
            banner = "Marked as ${side.label}",
        )
        dismissQuickSpeakerPicker(resumePlayback = true)
    }

    fun assignActiveDialogueToFaceTrack(faceTrackId: String) {
        val state = _uiState.value
        val track = state.project.faceTracks.firstOrNull { it.id == faceTrackId } ?: return
        val targetEventId = state.selectedEventId
            ?: state.project.dialogueEvents.firstOrNull {
                state.currentTimeMs in it.startTimeMs..it.endTimeMs
            }?.id
            ?: return

        var workingProject = state.project
        var speakerId = track.speakerId
        if (speakerId == null) {
            // Match an existing speaker or create Speaker C..H up to palette limit
            val existingCount = workingProject.speakers.size
            val maxIdx = (SpeakerColorOrder.PALETTE_SIZE - 1).coerceAtMost(7)
            val newId = "spk_${('a' + existingCount.coerceAtMost(maxIdx))}"
            speakerId = workingProject.speakers.firstOrNull { it.id == newId }?.id ?: run {
                val created = Speaker(
                    id = newId,
                    defaultLabel = "Speaker ${'A' + existingCount.coerceAtMost(maxIdx)}",
                    accentIndex = existingCount % SpeakerColorOrder.PALETTE_SIZE,
                    preferredScreenSideX = track.averageCenter().x,
                )
                workingProject = workingProject.copy(speakers = workingProject.speakers + created)
                created.id
            }
        }
        val updated = attributionEngine.applyUserCorrection(
            project = workingProject.copy(
                dialogueEvents = workingProject.dialogueEvents.map {
                    if (it.id == targetEventId) it.copy(faceTrackId = faceTrackId) else it
                }
            ),
            eventId = targetEventId,
            newSpeakerId = speakerId,
        )
        val speakerName = updated.speakers.firstOrNull { it.id == speakerId }?.displayName ?: "Speaker"
        updateProject(updated, banner = "Tapped face linked to $speakerName")
    }

    fun overrideBalloonAnchor(eventId: String, normalizedCenter: NormalizedPoint) {
        pushUndoSnapshot()
        val current = _uiState.value.project
        val updated = attributionEngine.applyUserCorrection(
            project = current,
            eventId = eventId,
            newSpeakerId = current.dialogueEvents.firstOrNull { it.id == eventId }?.speakerId,
            manualAnchor = NormalizedPoint(
                x = normalizedCenter.x.coerceIn(0.10f, 0.90f),
                y = normalizedCenter.y.coerceIn(0.10f, 0.88f),
            ),
        )
        updateProject(updated, banner = "Saved custom balloon anchor")
    }

    fun resetBalloonAnchor(eventId: String) {
        pushUndoSnapshot()
        val current = _uiState.value.project
        val updated = current.copy(
            dialogueEvents = current.dialogueEvents.map { evt ->
                if (evt.id == eventId) {
                    evt.copy(
                        anchorPosition = null,
                        placementMode = if (evt.dialogueType == DialogueType.SPOKEN) {
                            PlacementMode.AUTO
                        } else {
                            PlacementMode.EDGE_DOCKED
                        },
                    )
                } else {
                    evt
                }
            }
        )
        updateProject(updated, banner = "Restored automatic balloon placement")
    }

    fun updateDialogueEvent(
        eventId: String,
        sourceText: String? = null,
        translatedText: String? = null,
        dialogueType: DialogueType? = null,
        startTimeMs: Long? = null,
        endTimeMs: Long? = null,
        lockedByUser: Boolean? = null,
    ) {
        pushUndoSnapshot()
        val current = _uiState.value.project
        val updatedEvents = current.dialogueEvents.map { evt ->
            if (evt.id != eventId) evt else {
                val newStart = (startTimeMs ?: evt.startTimeMs).coerceAtLeast(0L)
                val newEnd = (endTimeMs ?: evt.endTimeMs).coerceAtLeast(newStart + 300L)
                val newType = dialogueType ?: evt.dialogueType
                evt.copy(
                    sourceText = sourceText ?: evt.sourceText,
                    translatedText = translatedText ?: evt.translatedText,
                    dialogueType = newType,
                    startTimeMs = newStart,
                    endTimeMs = newEnd,
                    lockedByUser = lockedByUser ?: evt.lockedByUser,
                    placementMode = if (newType != DialogueType.SPOKEN && evt.placementMode == PlacementMode.AUTO) {
                        PlacementMode.EDGE_DOCKED
                    } else if (newType == DialogueType.SPOKEN && evt.placementMode == PlacementMode.EDGE_DOCKED) {
                        PlacementMode.AUTO
                    } else {
                        evt.placementMode
                    },
                )
            }
        }
        updateProject(current.copy(dialogueEvents = updatedEvents))
    }

    fun mergeWithNextEvent(eventId: String) {
        pushUndoSnapshot()
        val sorted = _uiState.value.project.dialogueEvents.sortedBy { it.startTimeMs }.toMutableList()
        val idx = sorted.indexOfFirst { it.id == eventId }
        if (idx == -1 || idx >= sorted.lastIndex) return
        val first = sorted[idx]
        val second = sorted[idx + 1]
        val merged = first.copy(
            endTimeMs = maxOf(first.endTimeMs, second.endTimeMs),
            sourceText = "${first.sourceText} ${second.sourceText}".trim(),
            translatedText = "${first.translatedText} ${second.translatedText}".trim(),
            literalText = listOf(first.literalText, second.literalText).filter { it.isNotBlank() }.joinToString(" "),
            lockedByUser = true,
        )
        sorted[idx] = merged
        sorted.removeAt(idx + 1)
        updateProject(_uiState.value.project.copy(dialogueEvents = sorted), banner = "Merged 2 dialogue events")
    }

    fun splitEvent(eventId: String) {
        pushUndoSnapshot()
        val sorted = _uiState.value.project.dialogueEvents.sortedBy { it.startTimeMs }.toMutableList()
        val idx = sorted.indexOfFirst { it.id == eventId }
        if (idx == -1) return
        val evt = sorted[idx]
        if (evt.endTimeMs - evt.startTimeMs < 800L) return
        val mid = (evt.startTimeMs + evt.endTimeMs) / 2L

        val (src1, src2) = splitStringCleanly(evt.sourceText)
        val (tr1, tr2) = splitStringCleanly(evt.translatedText)

        sorted[idx] = evt.copy(endTimeMs = mid, sourceText = src1, translatedText = tr1)
        sorted.add(
            idx + 1,
            evt.copy(
                id = SubtitleParser.newEventId(),
                startTimeMs = mid + 1L,
                sourceText = src2,
                translatedText = tr2,
            )
        )
        updateProject(_uiState.value.project.copy(dialogueEvents = sorted), banner = "Split dialogue event at midpoint")
    }

    fun renameSpeaker(speakerId: String, newName: String, chosenAccentIndex: Int? = null) {
        pushUndoSnapshot()
        val current = _uiState.value.project
        val memory = _uiState.value.characterColorMemory.toMutableMap()
        val trimmed = newName.trim()
        val preUpdated = current.copy(
            speakers = current.speakers.map { spk ->
                if (spk.id == speakerId) {
                    val paletteSize = SpeakerColorOrder.PALETTE_SIZE
                    val updatedIdx = chosenAccentIndex?.let { ((it % paletteSize) + paletteSize) % paletteSize } ?: spk.accentIndex
                    if (chosenAccentIndex != null && trimmed.length >= 2) {
                        memory[attributionEngine.normalizeCharacterKey(trimmed)] = updatedIdx
                    }
                    spk.copy(
                        customName = trimmed,
                        accentIndex = updatedIdx,
                        colorRemembered = chosenAccentIndex != null || spk.colorRemembered,
                        isLocked = true,
                        isRolePlaceholder = false,
                        isIdentityConfirmed = true,
                    )
                } else {
                    spk
                }
            },
            // Retroactively confirm all existing video cues belonging to this speaker
            dialogueEvents = current.dialogueEvents.map { evt ->
                if (evt.speakerId == speakerId && !evt.lockedByUser) {
                    val nextPlacement = when {
                        evt.dialogueType != DialogueType.SPOKEN -> PlacementMode.EDGE_DOCKED
                        evt.anchorPosition != null || evt.faceTrackId != null -> PlacementMode.AUTO
                        else -> evt.placementMode
                    }
                    evt.copy(
                        speakerConfidence = maxOf(evt.speakerConfidence, 0.92f),
                        placementMode = nextPlacement,
                    )
                } else {
                    evt
                }
            },
        )
        val (reconciledSpeakers, updatedMemory) = attributionEngine.reconcileCharacterColors(
            speakers = preUpdated.speakers,
            events = preUpdated.dialogueEvents,
            rememberedColors = memory,
        )
        persistCharacterColorMemory(updatedMemory)
        val renamed = reconciledSpeakers.firstOrNull { it.id == speakerId }
        val banner = if (renamed?.colorRemembered == true) {
            "Saved ${renamed.displayName} & updated all video bubbles"
        } else {
            "Speaker renamed"
        }
        updateProject(preUpdated.copy(speakers = reconciledSpeakers), banner = banner)
    }

    fun loadDemoScenario() {
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        introJob?.cancel()
        lookaheadJob?.cancel()
        scoutCompletedWindows.clear()
        scoutAudioSliceTimestamps.clear()
        autoAnalyzedEventIds.clear()
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        layoutEngine.clearSmoothing()
        playbackClockAnchor.reset(600L, _uiState.value.playbackSpeed, false)
        val demo = DemoScenarioFactory.createDemoProject().copy(styleSettings = _uiState.value.styleSettings)
        _uiState.update {
            it.copy(
                project = demo,
                showStartLauncher = false,
                hasPendingPlayIntro = false,
                displayMode = CaptionDisplayMode.BALLOONS,
                isPlaying = false,
                currentTimeMs = 600L,
                selectedEventId = null,
                streamPlayRequest = null,
                youtubeSeekRequest = null,
                isCinemaIntroActive = false,
                introRemainingMs = 0L,
                isOpeningSpeakersReady = false,
                scoutTimeMs = 0L,
                scoutedUpToMs = 0L,
                scoutStatusText = "",
                scoutPlayRequest = null,
                scoutSeekRequest = null,
                analysisStatus = AnalysisStatus.Ready,
                scoutCaptionState = ScoutCaptionState.Idle,
                liveFaceExclusionRects = emptyList(),
                bufferedWindowRanges = emptyList(),
                scoutCompletedWindows = 0,
                scoutTotalWindows = 0,
                scoutFailedSliceCount = 0,
                statusBanner = "Loaded interactive 2-speaker Demo Scenario",
            )
        }
        recomputeBalloons()
    }

    fun createProjectFromMedia(
        videoUri: Uri,
        videoTitle: String,
        subtitleUri: Uri?,
        subtitleFileName: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
        subtitleSource: SubtitleSourceKind,
        qualityMode: ProcessingQualityMode,
    ) {
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        introJob?.cancel()
        lookaheadJob?.cancel()
        downgradedToLiteForStream = false
        scoutHeartbeatService.resetForNewStream(
            if (_uiState.value.quotaSaverMode == QuotaSaverMode.PRO_ACCURACY) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
        )
        scoutHeartbeatService.setZeroCloudPaused(_uiState.value.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD)
        _uiState.update { it.copy(scoutCaptionState = ScoutCaptionState.Idle) }
        scoutCompletedWindows.clear()
        scoutAudioSliceTimestamps.clear()
        autoAnalyzedEventIds.clear()
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        _uiState.update {
            it.copy(
                liveFaceExclusionRects = emptyList(),
                bufferedWindowRanges = emptyList(),
                scoutCompletedWindows = 0,
                scoutTotalWindows = 0,
                scoutFailedSliceCount = 0,
            )
        }
        layoutEngine.clearSmoothing()
        playbackClockAnchor.reset(0L, _uiState.value.playbackSpeed, false)

        analysisJob = viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        analysisStatus = AnalysisStatus.Processing("Inspecting video & audio tracks...", 0.08f, 0L, 1L),
                        statusBanner = null,
                    )
                }

                val metadata = videoAnalyzer.extractMetadata(videoUri)
                val durationMs = metadata.durationMs.coerceAtLeast(10_000L)

                var resolvedSubtitleSource = subtitleSource
                val cues: List<RawSubtitleCue> = if (subtitleUri != null) {
                    val rawText = projectStore.readTextFromUri(subtitleUri).getOrElse { throw it }
                    SubtitleParser.parse(rawText, subtitleFileName)
                } else {
                    val embeddedCues = audioAnalyzer.extractEmbeddedSubtitles(videoUri)
                    if (embeddedCues.isNotEmpty()) {
                        resolvedSubtitleSource = SubtitleSourceKind.EMBEDDED_TRACK
                    }
                    embeddedCues
                }

                val speakers = listOf(
                    Speaker("spk_a", "Speaker A", accentIndex = 0),
                    Speaker("spk_b", "Speaker B", accentIndex = 1),
                )

                val initialEvents = if (cues.isNotEmpty()) {
                    SubtitleParser.applyReadingPacingGuardrails(
                        cues.map { cue ->
                            DialogueEvent(
                                id = SubtitleParser.newEventId(),
                                startTimeMs = cue.startTimeMs,
                                endTimeMs = cue.endTimeMs,
                                sourceText = cue.text,
                                translatedText = cue.text,
                                speakerId = null,
                                speakerConfidence = 0.35f,
                                faceTrackId = null,
                                faceConfidence = 0f,
                                shotId = "shot_1",
                                dialogueType = cue.inferredType,
                                placementMode = PlacementMode.NEUTRAL_FALLBACK,
                            )
                        }
                    )
                } else {
                    emptyList()
                }

                val newProject = DialogueLensProject(
                    id = "proj_${System.currentTimeMillis()}",
                    title = videoTitle.ifBlank { "Uploaded Video" },
                    createdAtEpochMs = System.currentTimeMillis(),
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    translationMode = translationMode,
                    subtitleSource = if (cues.isNotEmpty()) resolvedSubtitleSource else SubtitleSourceKind.AI_DRAFT,
                    qualityMode = qualityMode,
                    videoSource = VideoSource(
                        uriString = videoUri.toString(),
                        displayName = videoTitle,
                        kind = VideoSourceKind.LOCAL_MP4,
                        durationMs = durationMs,
                        width = metadata.width,
                        height = metadata.height,
                        audioTracks = metadata.audioTracks,
                        embeddedSubtitleTracks = metadata.subtitleTracks,
                    ),
                    shots = listOf(Shot("shot_1", 0L, durationMs)),
                    speakers = speakers,
                    faceTracks = emptyList(),
                    dialogueEvents = initialEvents,
                    styleSettings = _uiState.value.styleSettings,
                    glossary = _uiState.value.glossaryEntries,
                )

                // Prepare Media3 ExoPlayer immediately so playback is ready after first window
                exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
                exoPlayer.prepare()
                exoPlayer.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed)
                exoPlayer.volume = if (_uiState.value.isMuted) 0f else 1f

                undoStack.clear()
                _uiState.update {
                    it.copy(
                        project = newProject,
                        showStartLauncher = false,
                        hasPendingPlayIntro = true,
                        displayMode = CaptionDisplayMode.BALLOONS,
                        isCinemaIntroActive = false,
                        introRemainingMs = 0L,
                        canUndo = false,
                        currentTimeMs = 0L,
                        isPlaying = false,
                        scoutTimeMs = 0L,
                        scoutedUpToMs = 0L,
                        scoutStatusText = "Scout buffering opening 7s context in background...",
                        placedBalloons = layoutEngine.computeLayout(newProject, 0L, it.styleSettings, currentLiveFaces),
                        statusBanner = "Video loaded · Scout pre-buffering transcript & faces. Press Play for Smart Pre-Roll.",
                    )
                }

                // Start background Lookahead Scout immediately while paused, plus cast discovery
                if (_uiState.value.dualInstanceEnabled) {
                    startRollingMediaLookahead(
                        projectId = newProject.id,
                        videoUri = videoUri,
                        httpUrl = null,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        hasPreloadedSubtitles = cues.isNotEmpty(),
                    )
                    runBackgroundCastDiscovery(newProject.id)
                    viewModelScope.launch {
                        val waveformPeaks = audioAnalyzer.extractWaveformPeaks(videoUri, durationMs)
                        val cur = _uiState.value.project
                        if (waveformPeaks.isNotEmpty() && cur.id == newProject.id) {
                            updateProject(cur.copy(audioWaveformPeaks = waveformPeaks))
                        }
                    }
                    return@launch
                }

                // Extract audio waveform peaks for the visual scrubber bar
                val waveformPeaks = audioAnalyzer.extractWaveformPeaks(videoUri, durationMs)
                if (waveformPeaks.isNotEmpty()) {
                    updateProject(_uiState.value.project.copy(audioWaveformPeaks = waveformPeaks))
                }

                // Progressive window processing (45s look-ahead chunks)
                val windowSizeMs = 45_000L
                var windowStart = 0L
                val accumulatedShots = mutableListOf<Shot>()
                val accumulatedTracks = mutableListOf<com.example.data.model.FaceTrack>()

                while (windowStart < durationMs) {
                    val windowEnd = minOf(durationMs, windowStart + windowSizeMs)
                    val progress = (windowStart.toFloat() / durationMs.toFloat()).coerceIn(0.1f, 0.8f)
                    _uiState.update {
                        it.copy(
                            analysisStatus = AnalysisStatus.Processing(
                                phaseLabel = "Tracking faces, OCR & lighting (${windowStart / 1000}s–${windowEnd / 1000}s)...",
                                progress = progress,
                                analyzedUpToMs = windowStart,
                                totalMs = durationMs,
                            )
                        )
                    }

                    val windowResult = videoAnalyzer.analyzeWindow(
                        uri = videoUri,
                        windowStartMs = windowStart,
                        windowEndMs = windowEnd,
                        sampleIntervalMs = qualityMode.sampleIntervalMs,
                        existingShotCount = accumulatedShots.size,
                    )
                    accumulatedShots.addAll(windowResult.shots)
                    accumulatedTracks.addAll(windowResult.faceTracks)

                    val activeProj = _uiState.value.project
                    val (updatedSpeakers, attributedEvents) = attributionEngine.assignSpeakers(
                        events = activeProj.dialogueEvents,
                        shots = accumulatedShots,
                        faceTracks = accumulatedTracks,
                        existingSpeakers = activeProj.speakers,
                        rememberedCharacterColors = _uiState.value.characterColorMemory,
                    )
                    updateProject(
                        activeProj.copy(
                            shots = accumulatedShots.toList(),
                            faceTracks = accumulatedTracks.toList(),
                            speakers = updatedSpeakers,
                            dialogueEvents = attributedEvents,
                        )
                    )
                    windowStart = windowEnd
                }

                // Zero-file audio auto-transcription via Gemini if no subtitle file & no soft embedded track existed
                if (cues.isEmpty()) {
                    runAutoTranscribeAudioPipeline(videoUri, sourceLanguage, targetLanguage)
                    return@launch
                }

                // Extract acoustic voice signatures (pitch Hz, brightness, energy, shout/emphasis, voice clusters)
                var eventsWithAudio = _uiState.value.project.dialogueEvents
                if (eventsWithAudio.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            analysisStatus = AnalysisStatus.Processing(
                                phaseLabel = "Profiling vocal emphasis, pitch & turns...",
                                progress = 0.85f,
                                analyzedUpToMs = durationMs,
                                totalMs = durationMs,
                            )
                        )
                    }
                    val audioFeaturesMap = audioAnalyzer.extractVoiceFeatures(videoUri, eventsWithAudio)
                    if (audioFeaturesMap.isNotEmpty()) {
                        eventsWithAudio = eventsWithAudio.map { evt ->
                            evt.copy(audioFeatures = audioFeaturesMap[evt.id] ?: evt.audioFeatures)
                        }
                        val activeProj = _uiState.value.project
                        val (updatedSpeakers, attributedEvents) = attributionEngine.assignSpeakers(
                            events = eventsWithAudio,
                            shots = accumulatedShots,
                            faceTracks = accumulatedTracks,
                            existingSpeakers = activeProj.speakers,
                            rememberedCharacterColors = _uiState.value.characterColorMemory,
                        )
                        updateProject(
                            activeProj.copy(
                                speakers = updatedSpeakers,
                                dialogueEvents = attributedEvents,
                            )
                        )
                    }
                }

                // Translate cues & reason over conversational context via Firebase AI Logic if source != target
                if (!sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
                    translateCurrentProjectWithAi()
                } else {
                    analyzeSpeakersWithContextAndAudio()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Video project creation failed", e)
                _uiState.update {
                    it.copy(
                        analysisStatus = AnalysisStatus.Error(
                            e.localizedMessage ?: "Failed to analyze selected media."
                        )
                    )
                }
            }
        }
    }

    fun autoTranscribeCurrentVideoWithAi() {
        val current = _uiState.value.project
        when (current.videoSource.kind) {
            VideoSourceKind.LOCAL_MP4 -> {
                val uri = Uri.parse(current.videoSource.uriString)
                viewModelScope.launch {
                    runAutoTranscribeAudioPipeline(uri, current.sourceLanguage, current.targetLanguage)
                }
            }
            VideoSourceKind.YOUTUBE_COMPLIANT -> {
                retryYouTubeAiTranscription()
            }
            else -> {
                _uiState.update {
                    it.copy(statusBanner = "For general web streams, enable HTML5 captions or attach an SRT/VTT file.")
                }
            }
        }
    }

    fun retryYouTubeAiTranscription() {
        val current = _uiState.value.project
        val videoId = current.videoSource.youtubeVideoId
        if (current.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT || videoId.isNullOrBlank()) return
        downgradedToLiteForStream = false
        quotaCircuitBreakerTripped = false
        if (_uiState.value.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD) {
            setQuotaSaverMode(QuotaSaverMode.PRO_ACCURACY)
        }
        val currentHorizonSec = (_uiState.value.scoutedUpToMs / 1000L).toInt().coerceAtLeast(0)
        val hasExisting = LookaheadWindowPlanner.hasRealSubtitles(current.dialogueEvents)
        runYouTubeScoutTranscriptPipeline(
            projectId = current.id,
            videoId = videoId,
            skipTierOneCaptions = hasExisting,
            resumeFromHorizonSec = if (hasExisting) currentHorizonSec else 0,
        )
    }

    private suspend fun runAutoTranscribeAudioPipeline(
        videoUri: Uri,
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        if (_uiState.value.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped) {
            _uiState.update {
                it.copy(
                    isTranslatingAi = false,
                    analysisStatus = AnalysisStatus.Ready,
                    statusBanner = "Zero-Cloud active — attach an SRT/VTT or switch AI Mode to Quota-Saver.",
                )
            }
            return
        }
        val current = _uiState.value.project
        val durationMs = current.videoSource.durationMs.coerceAtLeast(1000L)
        try {
            _uiState.update {
                it.copy(
                    isTranslatingAi = true,
                    analysisStatus = AnalysisStatus.Processing(
                        phaseLabel = "Extracting audio chunks for AI speech transcription...",
                        progress = 0.84f,
                        analyzedUpToMs = durationMs,
                        totalMs = durationMs,
                    ),
                )
            }
            val chunks = audioAnalyzer.extractChunkedWavForAsr(videoUri)
            if (chunks.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isTranslatingAi = false,
                        analysisStatus = AnalysisStatus.Ready,
                        statusBanner = "No audible speech track found — attach an SRT/VTT or add lines manually.",
                    )
                }
                return
            }

            val mergedGlossary = (current.glossary + _uiState.value.glossaryEntries)
                .distinctBy { it.term.trim().lowercase() }

            val allAiCues = mutableListOf<com.example.data.ai.AiTranscribedCue>()
            val hintMap = mutableMapOf<String, AiTranslatedLine>()
            val generatedEvents = mutableListOf<DialogueEvent>()
            var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                quotaSaverMode = _uiState.value.quotaSaverMode,
                quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                downgradedToLite = downgradedToLiteForStream,
            )

            chunks.forEachIndexed { idx, chunk ->
                val chunkProg = 0.86f + (0.10f * (idx.toFloat() / chunks.size.coerceAtLeast(1)))
                _uiState.update {
                    it.copy(
                        analysisStatus = AnalysisStatus.Processing(
                            phaseLabel = "${activeTier.displayLabel} transcribing chunk ${idx + 1}/${chunks.size}...",
                            progress = chunkProg,
                            analyzedUpToMs = chunk.endMs,
                            totalMs = durationMs,
                        )
                    )
                }
                var chunkRes = translationAiService.transcribeAndTranslateAudio(
                    wavBytes = chunk.wavBytes,
                    chunkStartMs = chunk.startMs,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    glossary = mergedGlossary,
                    preferLiteModel = activeTier.preferLite,
                )
                if (chunkRes.isFailure &&
                    TranslationAiService.isQuotaOrCreditExhaustedError(chunkRes.exceptionOrNull()) &&
                    activeTier == ScoutExecutionTier.BEST_AI
                ) {
                    activeTier = ScoutExecutionTier.LITE_AI
                    downgradedToLiteForStream = true
                    chunkRes = translationAiService.transcribeAndTranslateAudio(
                        wavBytes = chunk.wavBytes,
                        chunkStartMs = chunk.startMs,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        glossary = mergedGlossary,
                        preferLiteModel = true,
                    )
                }
                if (chunkRes.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(chunkRes.exceptionOrNull())) {
                    quotaCircuitBreakerTripped = true
                }
                if (chunkRes.isSuccess) {
                    val cues = chunkRes.getOrThrow()
                    allAiCues.addAll(cues)
                    cues.forEach { c ->
                        val id = SubtitleParser.newEventId()
                        val midMs = (c.startMs + c.endMs) / 2L
                        val shotId = current.shots.firstOrNull { midMs in it.startMs..it.endMs }?.id ?: "shot_1"
                        generatedEvents.add(
                            DialogueEvent(
                                id = id,
                                startTimeMs = c.startMs,
                                endTimeMs = c.endMs,
                                sourceText = c.sourceText,
                                translatedText = c.naturalTranslation,
                                literalText = c.literalTranslation,
                                romanization = c.romanization,
                                speakerId = null,
                                speakerConfidence = 0.55f,
                                faceTrackId = null,
                                faceConfidence = 0f,
                                shotId = shotId,
                                dialogueType = c.dialogueType,
                                placementMode = PlacementMode.AUTO,
                                contextCueReason = c.contextReason,
                                culturalNote = c.culturalNote,
                            )
                        )
                        hintMap[id] = AiTranslatedLine(
                            id = id,
                            naturalTranslation = c.naturalTranslation,
                            literalTranslation = c.literalTranslation,
                            romanization = c.romanization,
                            dialogueTypeHint = c.dialogueType,
                            speakerTurnHint = "UNCERTAIN",
                            suggestedSpeakerSlot = c.suggestedSpeakerSlot,
                            contextReason = c.contextReason,
                            culturalNote = c.culturalNote,
                        )
                    }
                }
            }

            if (generatedEvents.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isTranslatingAi = false,
                        analysisStatus = AnalysisStatus.Ready,
                        statusBanner = "Auto-transcription produced 0 cues — attach SRT or add lines manually.",
                    )
                }
                return
            }

            val pacedEvents = SubtitleParser.applyReadingPacingGuardrails(generatedEvents)
            val audioFeaturesMap = audioAnalyzer.extractVoiceFeatures(videoUri, pacedEvents)
            val withAudio = pacedEvents.map { evt ->
                evt.copy(audioFeatures = audioFeaturesMap[evt.id] ?: evt.audioFeatures)
            }
            val latestProj = _uiState.value.project
            val (updatedSpeakers, attributed) = attributionEngine.assignSpeakers(
                events = withAudio,
                shots = latestProj.shots,
                faceTracks = latestProj.faceTracks,
                existingSpeakers = latestProj.speakers,
                turnHints = hintMap,
                rememberedCharacterColors = _uiState.value.characterColorMemory,
            )
            pushUndoSnapshot()
            updateProject(
                latestProj.copy(
                    subtitleSource = SubtitleSourceKind.AI_DRAFT,
                    speakers = updatedSpeakers,
                    dialogueEvents = attributed,
                ),
                banner = "Auto-transcribed ${attributed.size} dialogue cues directly from audio",
            )
            _uiState.update { it.copy(analysisStatus = AnalysisStatus.Ready, isTranslatingAi = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Auto transcription failed", e)
            _uiState.update {
                it.copy(
                    isTranslatingAi = false,
                    analysisStatus = AnalysisStatus.Error(
                        e.localizedMessage ?: "Audio transcription failed."
                    ),
                )
            }
        }
    }

    private fun maybeTriggerRollingWindowEnhancement(playheadMs: Long) {
        val state = _uiState.value
        val proj = state.project
        if (proj.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT &&
            proj.videoSource.kind != VideoSourceKind.WEB_STREAM
        ) {
            return
        }
        if (proj.subtitleSource != SubtitleSourceKind.EMBEDDED_TRACK &&
            proj.subtitleSource != SubtitleSourceKind.AI_DRAFT &&
            proj.subtitleSource != SubtitleSourceKind.UPLOADED_FILE
        ) {
            return
        }
        if (aiTranslationJob?.isActive == true || speakerAnalysisJob?.isActive == true) return
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (nowElapsed - lastAutoAiTriggerElapsedMs < 3_000L) return

        val lookaheadWindowEnd = playheadMs + 35_000L
        val hasPendingLookahead = proj.dialogueEvents.any { evt ->
            !evt.lockedByUser &&
                evt.endTimeMs >= (playheadMs - 2_000L) &&
                evt.startTimeMs <= lookaheadWindowEnd &&
                !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                !evt.sourceText.startsWith("Web Cinema ready") &&
                evt.id !in autoAnalyzedEventIds
        }
        if (!hasPendingLookahead) return

        lastAutoAiTriggerElapsedMs = nowElapsed
        val quotaMode = state.quotaSaverMode
        val needsTranslation = !proj.sourceLanguage.equals(proj.targetLanguage, ignoreCase = true) &&
            !proj.sourceLanguage.equals("Auto-Detect", ignoreCase = true)
        if (needsTranslation) {
            translateCurrentProjectWithAi(silentBackground = true)
        } else if (quotaMode == QuotaSaverMode.PRO_ACCURACY) {
            analyzeSpeakersWithContextAndAudio(silentBackground = true)
        } else {
            // Same-language captions in Quota-Saver / Zero-Cloud: mark window scanned without burning cloud quota
            proj.dialogueEvents
                .filter { it.endTimeMs >= (playheadMs - 2_000L) && it.startTimeMs <= lookaheadWindowEnd }
                .forEach { autoAnalyzedEventIds.add(it.id) }
        }
    }

    fun translateCurrentProjectWithAi(
        overrideTargetLanguage: String? = null,
        silentBackground: Boolean = false,
    ) {
        aiTranslationJob?.cancel()
        aiTranslationJob = viewModelScope.launch {
            val current = _uiState.value.project
            val targetLang = overrideTargetLanguage ?: current.targetLanguage
            val quotaMode = _uiState.value.quotaSaverMode
            val allRealEvents = current.dialogueEvents.filter { evt ->
                evt.sourceText.isNotBlank() &&
                    !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                    !evt.sourceText.startsWith("Web Cinema ready")
            }
            if (allRealEvents.isEmpty()) {
                if (!silentBackground) {
                    _uiState.update {
                        it.copy(
                            isTranslatingAi = false,
                            analysisStatus = AnalysisStatus.Ready,
                            statusBanner = "Load captions or add a line first before translating.",
                        )
                    }
                }
                return@launch
            }

            // Start right at playhead so first ~12 lines land rapidly, followed smoothly by +35s lookahead batches
            val sortedAll = allRealEvents.sortedBy { it.startTimeMs }
            val playheadMs = _uiState.value.currentTimeMs
            val centerIdx = sortedAll.indexOfFirst { it.endTimeMs >= playheadMs }.let { if (it >= 0) it else 0 }
            val startIdx = (centerIdx - 2).coerceAtLeast(0)
            val endIdx = (startIdx + 48).coerceAtMost(sortedAll.size)
            val translatable = sortedAll.subList(startIdx, endIdx)

            val isSameLanguage = current.sourceLanguage.equals(targetLang, ignoreCase = true) ||
                current.sourceLanguage.equals("Auto-Detect", ignoreCase = true)

            // Same-language watching in Lite-First / Zero-Cloud or when credits dry costs $0 cloud tokens!
            if (isSameLanguage && (quotaMode != QuotaSaverMode.PRO_ACCURACY || quotaCircuitBreakerTripped)) {
                translatable.forEach { autoAnalyzedEventIds.add(it.id) }
                if (!silentBackground) {
                    val (updatedSpeakers, attributed) = attributionEngine.assignSpeakers(
                        events = current.dialogueEvents,
                        shots = current.shots,
                        faceTracks = current.faceTracks,
                        existingSpeakers = current.speakers,
                        turnHints = cachedAiTurnHints,
                        rememberedCharacterColors = _uiState.value.characterColorMemory,
                    )
                    updateProject(
                        current.copy(targetLanguage = targetLang, speakers = updatedSpeakers, dialogueEvents = attributed),
                        banner = "Same-language cues active locally ($targetLang · $0 credits)",
                    )
                }
                return@launch
            }

            // Step 1: Check persistent line translation cache first ($0 cost)
            val cachedLineUpdates = mutableMapOf<String, String>()
            val uncachedEvents = mutableListOf<DialogueEvent>()
            translatable.forEach { evt ->
                val cacheKey = ProjectStore.buildLineTranslationCacheKey(evt.sourceText, current.sourceLanguage, targetLang)
                val cachedText = inMemoryTranslationCache[cacheKey]
                if (!cachedText.isNullOrBlank()) {
                    cachedLineUpdates[evt.id] = cachedText
                    autoAnalyzedEventIds.add(evt.id)
                } else {
                    uncachedEvents.add(evt)
                }
            }
            if (cachedLineUpdates.isNotEmpty()) {
                val latestProj = _uiState.value.project
                val patchedEvents = SubtitleParser.applyReadingPacingGuardrails(
                    latestProj.dialogueEvents.map { evt ->
                        val cachedTr = cachedLineUpdates[evt.id] ?: return@map evt
                        evt.copy(translatedText = cachedTr)
                    }
                )
                updateProject(
                    latestProj.copy(targetLanguage = targetLang, dialogueEvents = patchedEvents),
                    banner = if (uncachedEvents.isEmpty() && !silentBackground) {
                        "Restored ${cachedLineUpdates.size} cached translations ($0 credits)"
                    } else null,
                )
            }
            if (uncachedEvents.isEmpty()) {
                _uiState.update { it.copy(isTranslatingAi = false, analysisStatus = AnalysisStatus.Ready) }
                return@launch
            }

            // Step 2: Free offline Google ML Kit translation in Quota-Saver / Zero-Cloud mode!
            if (!isSameLanguage &&
                quotaMode != QuotaSaverMode.PRO_ACCURACY &&
                onDeviceTranslator.canTranslateOffline(current.sourceLanguage, targetLang)
            ) {
                if (!silentBackground) {
                    _uiState.update {
                        it.copy(
                            isTranslatingAi = true,
                            analysisStatus = AnalysisStatus.Processing(
                                phaseLabel = "Translating offline into $targetLang via ML Kit ($0)...",
                                progress = 0.35f,
                                analyzedUpToMs = current.videoSource.durationMs,
                                totalMs = current.videoSource.durationMs,
                            ),
                        )
                    }
                }
                val offlineRes = onDeviceTranslator.translateEventsOffline(
                    events = uncachedEvents,
                    sourceLanguage = current.sourceLanguage,
                    targetLanguage = targetLang,
                )
                val offlineMap = offlineRes.getOrNull().orEmpty()
                if (offlineMap.isNotEmpty()) {
                    uncachedEvents.forEach { evt ->
                        val tr = offlineMap[evt.id]
                        if (!tr.isNullOrBlank()) {
                            inMemoryTranslationCache[ProjectStore.buildLineTranslationCacheKey(evt.sourceText, current.sourceLanguage, targetLang)] = tr
                            autoAnalyzedEventIds.add(evt.id)
                        }
                    }
                    projectStore.saveTranslationCache(inMemoryTranslationCache)
                    val latestProj = _uiState.value.project
                    if (latestProj.id == current.id) {
                        val updatedEvents = SubtitleParser.applyReadingPacingGuardrails(
                            latestProj.dialogueEvents.map { evt ->
                                val tr = offlineMap[evt.id] ?: return@map evt
                                evt.copy(translatedText = tr)
                            }
                        )
                        val (updatedSpeakers, reAttributed) = attributionEngine.assignSpeakers(
                            events = updatedEvents,
                            shots = latestProj.shots,
                            faceTracks = latestProj.faceTracks,
                            existingSpeakers = latestProj.speakers,
                            turnHints = cachedAiTurnHints,
                            rememberedCharacterColors = _uiState.value.characterColorMemory,
                        )
                        if (!silentBackground) pushUndoSnapshot()
                        val updatedProj = latestProj.copy(
                            targetLanguage = targetLang,
                            speakers = updatedSpeakers,
                            dialogueEvents = reAttributed,
                        )
                        updateProject(
                            updatedProj,
                            banner = if (!silentBackground) {
                                "Translated ${offlineMap.size} lines offline into $targetLang via ML Kit ($0 credits)"
                            } else null,
                        )
                        persistYouTubeVideoCache(updatedProj, _uiState.value.scoutedUpToMs)
                    }
                    _uiState.update { it.copy(isTranslatingAi = false, analysisStatus = AnalysisStatus.Ready) }
                    return@launch
                } else if (quotaMode == QuotaSaverMode.ZERO_CLOUD) {
                    if (!silentBackground) {
                        _uiState.update {
                            it.copy(
                                isTranslatingAi = false,
                                analysisStatus = AnalysisStatus.Ready,
                                statusBanner = "Offline ML Kit language pack unavailable for ${current.sourceLanguage} → $targetLang.",
                            )
                        }
                    }
                    return@launch
                }
            }

            if (quotaMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped) {
                if (!silentBackground) {
                    _uiState.update {
                        it.copy(
                            isTranslatingAi = false,
                            analysisStatus = AnalysisStatus.Ready,
                            statusBanner = "Zero-Cloud mode active — keeping existing subtitles.",
                        )
                    }
                }
                return@launch
            }

            if (!silentBackground) {
                _uiState.update {
                    it.copy(
                        isTranslatingAi = true,
                        analysisStatus = AnalysisStatus.Processing(
                            phaseLabel = if (isSameLanguage) {
                                "Enhancing SDH cues & speakers (${uncachedEvents.size} lines)..."
                            } else {
                                "Translating ${uncachedEvents.size} lines into $targetLang..."
                            },
                            progress = 0.12f,
                            analyzedUpToMs = current.videoSource.durationMs,
                            totalMs = current.videoSource.durationMs,
                        ),
                    )
                }
            }

            try {
                val mergedGlossary = (current.glossary + _uiState.value.glossaryEntries)
                    .distinctBy { it.term.trim().lowercase() }

                val translatedById = mutableMapOf<String, AiTranslatedLine>()
                var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = quotaMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                var offset = 0
                var undoPushed = false
                while (offset < uncachedEvents.size) {
                    val chunkStartIdx = offset
                    val end = minOf(offset + 12, uncachedEvents.size)
                    val chunk = uncachedEvents.subList(chunkStartIdx, end)
                    val priorContext = if (chunkStartIdx > 0) {
                        uncachedEvents.subList(maxOf(0, chunkStartIdx - 3), chunkStartIdx).map { prev ->
                            val prevTr = translatedById[prev.id]?.naturalTranslation ?: prev.translatedText
                            prev.copy(translatedText = prevTr)
                        }
                    } else {
                        emptyList()
                    }
                    var useLite = activeTier != ScoutExecutionTier.BEST_AI
                    var result = scoutHeartbeatService.executeWithBackoffRetry(
                        modelName = if (useLite) "gemini-3.5-flash-lite" else "gemini-3.8-flash",
                        maxRetries = 2,
                        onWarmToken = {
                            com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L)
                        },
                    ) {
                        translationAiService.translateBatch(
                            events = chunk,
                            sourceLanguage = current.sourceLanguage,
                            targetLanguage = targetLang,
                            speakers = _uiState.value.project.speakers,
                            glossary = mergedGlossary,
                            priorContextLines = priorContext,
                            videoContext = buildVideoContextString(_uiState.value.project),
                            preferLiteModel = useLite,
                            onDiscoveredSpeakerUpdates = { updates ->
                                applyLiveSpeakerUpdates(current.id, updates)
                            },
                        )
                    }
                    if (result.isFailure &&
                        TranslationAiService.isQuotaOrCreditExhaustedError(result.exceptionOrNull()) &&
                        activeTier == ScoutExecutionTier.BEST_AI
                    ) {
                        activeTier = LookaheadWindowPlanner.nextFallbackTierOnQuotaError(activeTier)
                        downgradedToLiteForStream = true
                        useLite = true
                        if (!silentBackground) {
                            _uiState.update {
                                it.copy(statusBanner = "Best model credits depleted — switching to Flash-Lite...")
                            }
                        }
                        result = scoutHeartbeatService.executeWithBackoffRetry(
                            modelName = "gemini-3.5-flash-lite",
                            maxRetries = 1,
                            onWarmToken = {
                                com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 3_000L)
                            },
                        ) {
                            translationAiService.translateBatch(
                                events = chunk,
                                sourceLanguage = current.sourceLanguage,
                                targetLanguage = targetLang,
                                speakers = _uiState.value.project.speakers,
                                glossary = mergedGlossary,
                                priorContextLines = priorContext,
                                videoContext = buildVideoContextString(_uiState.value.project),
                                preferLiteModel = true,
                                onDiscoveredSpeakerUpdates = { updates ->
                                    applyLiveSpeakerUpdates(current.id, updates)
                                },
                            )
                        }
                    }
                    if (result.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(result.exceptionOrNull())) {
                        quotaCircuitBreakerTripped = true
                        val remaining = uncachedEvents.subList(chunkStartIdx, uncachedEvents.size)
                        if (!isSameLanguage &&
                            remaining.isNotEmpty() &&
                            onDeviceTranslator.canTranslateOffline(current.sourceLanguage, targetLang)
                        ) {
                            val offlineFallback = onDeviceTranslator.translateEventsOffline(
                                events = remaining,
                                sourceLanguage = current.sourceLanguage,
                                targetLanguage = targetLang,
                            ).getOrNull().orEmpty()
                            if (offlineFallback.isNotEmpty()) {
                                remaining.forEach { evt ->
                                    val tr = offlineFallback[evt.id]
                                    if (!tr.isNullOrBlank()) {
                                        inMemoryTranslationCache[ProjectStore.buildLineTranslationCacheKey(evt.sourceText, current.sourceLanguage, targetLang)] = tr
                                        autoAnalyzedEventIds.add(evt.id)
                                    }
                                }
                                projectStore.saveTranslationCache(inMemoryTranslationCache)
                                val latestProj = _uiState.value.project
                                if (latestProj.id == current.id) {
                                    val patchedEvents = SubtitleParser.applyReadingPacingGuardrails(
                                        latestProj.dialogueEvents.map { evt ->
                                            val tr = offlineFallback[evt.id] ?: return@map evt
                                            evt.copy(translatedText = tr)
                                        }
                                    )
                                    updateProject(
                                        latestProj.copy(targetLanguage = targetLang, dialogueEvents = patchedEvents),
                                        banner = if (!silentBackground) {
                                            "Cloud credits depleted — finished ${offlineFallback.size} lines via offline ML Kit ($0)"
                                        } else null,
                                    )
                                }
                                break
                            }
                        }
                        if (!silentBackground) {
                            _uiState.update {
                                it.copy(statusBanner = "Cloud credits depleted — keeping local captions & ML Kit mode.")
                            }
                        }
                        break
                    }
                    val lines = result.getOrNull().orEmpty()
                    val batchMap = mutableMapOf<String, AiTranslatedLine>()
                    lines.forEach { line ->
                        translatedById[line.id] = line
                        cachedAiTurnHints[line.id] = line
                        batchMap[line.id] = line
                    }
                    chunk.forEach { evt ->
                        autoAnalyzedEventIds.add(evt.id)
                        val trLine = batchMap[evt.id]
                        if (trLine != null && trLine.naturalTranslation.isNotBlank()) {
                            inMemoryTranslationCache[ProjectStore.buildLineTranslationCacheKey(evt.sourceText, current.sourceLanguage, targetLang)] =
                                trLine.naturalTranslation
                        }
                    }
                    offset = end

                    val latestProj = _uiState.value.project
                    if (latestProj.id != current.id) break

                    if (batchMap.isNotEmpty()) {
                        projectStore.saveTranslationCache(inMemoryTranslationCache)
                        val updatedEvents = SubtitleParser.applyReadingPacingGuardrails(
                            latestProj.dialogueEvents.map { evt ->
                                val aiLine = batchMap[evt.id] ?: return@map evt
                                evt.copy(
                                    translatedText = aiLine.naturalTranslation,
                                    literalText = aiLine.literalTranslation,
                                    romanization = aiLine.romanization,
                                    dialogueType = if (evt.lockedByUser) evt.dialogueType else aiLine.dialogueTypeHint,
                                    contextCueReason = aiLine.contextReason,
                                    culturalNote = aiLine.culturalNote.ifBlank { evt.culturalNote },
                                )
                            }
                        )
                        val (updatedSpeakers, reAttributed) = attributionEngine.assignSpeakers(
                            events = updatedEvents,
                            shots = latestProj.shots,
                            faceTracks = latestProj.faceTracks,
                            existingSpeakers = latestProj.speakers,
                            turnHints = cachedAiTurnHints,
                            rememberedCharacterColors = _uiState.value.characterColorMemory,
                        )
                        if (!silentBackground && !undoPushed) {
                            pushUndoSnapshot()
                            undoPushed = true
                        }
                        val isFinalChunk = offset >= uncachedEvents.size
                        val nextProj = latestProj.copy(
                            targetLanguage = targetLang,
                            speakers = updatedSpeakers,
                            dialogueEvents = reAttributed,
                            glossary = mergedGlossary,
                        )
                        updateProject(
                            nextProj,
                            banner = if (silentBackground || !isFinalChunk) {
                                null
                            } else if (isSameLanguage) {
                                "Enhanced ${translatedById.size} SDH cues & speakers ($targetLang)"
                            } else {
                                "Translated & cached ${translatedById.size} lines into $targetLang"
                            },
                        )
                        persistYouTubeVideoCache(nextProj, _uiState.value.scoutedUpToMs)
                    }

                    if (!silentBackground) {
                        val frac = 0.15f + 0.80f * (offset.toFloat() / uncachedEvents.size.coerceAtLeast(1))
                        _uiState.update { cur ->
                            cur.copy(
                                analysisStatus = AnalysisStatus.Processing(
                                    phaseLabel = if (isSameLanguage) {
                                        "Enhancing SDH cues ($offset/${uncachedEvents.size})..."
                                    } else {
                                        "Translating into $targetLang ($offset/${uncachedEvents.size})..."
                                    },
                                    progress = frac.coerceIn(0.15f, 0.95f),
                                    analyzedUpToMs = current.videoSource.durationMs,
                                    totalMs = current.videoSource.durationMs,
                                ),
                            )
                        }
                    }
                }

                if (translatedById.isEmpty() && !silentBackground && !quotaCircuitBreakerTripped) {
                    _uiState.update {
                        it.copy(
                            isTranslatingAi = false,
                            analysisStatus = AnalysisStatus.Ready,
                            statusBanner = "Translation timed out or unavailable; keeping original text.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI translation failed", e)
                if (!silentBackground) {
                    _uiState.update {
                        it.copy(statusBanner = e.localizedMessage ?: "Translation unavailable right now.")
                    }
                }
            } finally {
                if (!silentBackground) {
                    _uiState.update { cur ->
                        cur.copy(
                            isTranslatingAi = false,
                            analysisStatus = if (cur.analysisStatus is AnalysisStatus.Processing) AnalysisStatus.Ready else cur.analysisStatus,
                        )
                    }
                }
            }
        }
    }

    fun analyzeSpeakersWithContextAndAudio(silentBackground: Boolean = false) {
        speakerAnalysisJob?.cancel()
        speakerAnalysisJob = viewModelScope.launch {
            val current = _uiState.value.project
            val quotaMode = _uiState.value.quotaSaverMode
            val realEvents = current.dialogueEvents.filter { evt ->
                (evt.sourceText.isNotBlank() || evt.translatedText.isNotBlank()) &&
                    !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                    !evt.sourceText.startsWith("Web Cinema ready")
            }
            if (realEvents.isEmpty()) {
                if (!silentBackground) {
                    _uiState.update {
                        it.copy(
                            isTranslatingAi = false,
                            analysisStatus = AnalysisStatus.Ready,
                            statusBanner = "Load captions or add dialogue lines first before identifying speakers.",
                        )
                    }
                }
                return@launch
            }

            if (quotaMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped) {
                val (updatedSpeakers, reAttributed) = attributionEngine.assignSpeakers(
                    events = current.dialogueEvents,
                    shots = current.shots,
                    faceTracks = current.faceTracks,
                    existingSpeakers = current.speakers,
                    turnHints = cachedAiTurnHints,
                    rememberedCharacterColors = _uiState.value.characterColorMemory,
                )
                updateProject(
                    current.copy(speakers = updatedSpeakers, dialogueEvents = reAttributed),
                    banner = if (silentBackground) null else "Matched speakers on-device using voice & conversation turns ($0 credits)",
                )
                return@launch
            }

            // Start right around active playhead in small 15-line batches so colors sharpen live in ~1.5s
            val sortedReal = realEvents.sortedBy { it.startTimeMs }
            val playheadMs = _uiState.value.currentTimeMs
            val centerIdx = sortedReal.indexOfFirst { it.endTimeMs >= playheadMs }.let { if (it >= 0) it else 0 }
            val startIdx = (centerIdx - 2).coerceAtLeast(0)
            val endIdx = (startIdx + 36).coerceAtMost(sortedReal.size)
            val targetWindow = sortedReal.subList(startIdx, endIdx)

            if (!silentBackground) {
                _uiState.update {
                    it.copy(
                        isTranslatingAi = true,
                        analysisStatus = AnalysisStatus.Processing(
                            phaseLabel = "Matching speakers (${targetWindow.size} lines)...",
                            progress = 0.25f,
                            analyzedUpToMs = current.videoSource.durationMs,
                            totalMs = current.videoSource.durationMs,
                        ),
                    )
                }
            }

            val keyframes = if (current.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
                runCatching {
                    videoAnalyzer.extractKeyframesForShots(Uri.parse(current.videoSource.uriString), current.shots)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            try {
                var matchedCount = 0
                var undoPushed = false
                var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = quotaMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                val chunks = targetWindow.chunked(15)
                for ((idx, chunk) in chunks.withIndex()) {
                    var result = scoutHeartbeatService.executeWithBackoffRetry(
                        modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                        maxRetries = 2,
                        onWarmToken = {
                            com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L)
                        },
                    ) {
                        translationAiService.analyzeConversationSpeakers(
                            events = chunk,
                            speakers = _uiState.value.project.speakers,
                            shotKeyframes = if (idx == 0) keyframes else emptyList(),
                            videoContext = buildVideoContextString(_uiState.value.project),
                            preferLiteModel = activeTier.preferLite,
                            onDiscoveredSpeakerUpdates = { updates ->
                                applyLiveSpeakerUpdates(current.id, updates)
                            },
                        )
                    }
                    if (result.isFailure &&
                        TranslationAiService.isQuotaOrCreditExhaustedError(result.exceptionOrNull()) &&
                        activeTier == ScoutExecutionTier.BEST_AI
                    ) {
                        activeTier = ScoutExecutionTier.LITE_AI
                        downgradedToLiteForStream = true
                        result = scoutHeartbeatService.executeWithBackoffRetry(
                            modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                            maxRetries = 2,
                            onWarmToken = {
                                com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L)
                            },
                        ) {
                            translationAiService.analyzeConversationSpeakers(
                                events = chunk,
                                speakers = _uiState.value.project.speakers,
                                shotKeyframes = if (idx == 0) keyframes else emptyList(),
                                videoContext = buildVideoContextString(_uiState.value.project),
                                preferLiteModel = true,
                                onDiscoveredSpeakerUpdates = { updates ->
                                    applyLiveSpeakerUpdates(current.id, updates)
                                },
                            )
                        }
                    }
                    if (result.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(result.exceptionOrNull())) {
                        quotaCircuitBreakerTripped = true
                        break
                    }
                    if (result.isSuccess) {
                        val lines = result.getOrThrow()
                        matchedCount += lines.size
                        lines.forEach { line -> cachedAiTurnHints[line.id] = line }
                    }
                    chunk.forEach { autoAnalyzedEventIds.add(it.id) }

                    val latestProj = _uiState.value.project
                    if (latestProj.id != current.id) break

                    val (updatedSpeakers, reAttributed) = attributionEngine.assignSpeakers(
                        events = latestProj.dialogueEvents,
                        shots = latestProj.shots,
                        faceTracks = latestProj.faceTracks,
                        existingSpeakers = latestProj.speakers,
                        turnHints = cachedAiTurnHints,
                        rememberedCharacterColors = _uiState.value.characterColorMemory,
                    )
                    if (!silentBackground && !undoPushed) {
                        pushUndoSnapshot()
                        undoPushed = true
                    }
                    val isFinalChunk = idx == chunks.lastIndex
                    updateProject(
                        latestProj.copy(
                            speakers = updatedSpeakers,
                            dialogueEvents = reAttributed,
                        ),
                        banner = if (silentBackground || !isFinalChunk) {
                            null
                        } else if (matchedCount > 0) {
                            "Matched speakers across ${reAttributed.size} lines"
                        } else {
                            "Matched speakers locally using context & voice turns (${reAttributed.size} lines)"
                        },
                    )

                    if (!silentBackground) {
                        val frac = 0.35f + 0.55f * ((idx + 1).toFloat() / chunks.size.coerceAtLeast(1))
                        _uiState.update { cur ->
                            cur.copy(
                                analysisStatus = AnalysisStatus.Processing(
                                    phaseLabel = "Matching speakers (${idx + 1}/${chunks.size})...",
                                    progress = frac.coerceIn(0.35f, 0.92f),
                                    analyzedUpToMs = current.videoSource.durationMs,
                                    totalMs = current.videoSource.durationMs,
                                ),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cloud context analysis fallback to on-device audio + context fusion", e)
                targetWindow.forEach { autoAnalyzedEventIds.add(it.id) }
                val latestProj = _uiState.value.project
                val (updatedSpeakers, reAttributed) = attributionEngine.assignSpeakers(
                    events = latestProj.dialogueEvents,
                    shots = latestProj.shots,
                    faceTracks = latestProj.faceTracks,
                    existingSpeakers = latestProj.speakers,
                    turnHints = cachedAiTurnHints,
                    rememberedCharacterColors = _uiState.value.characterColorMemory,
                )
                updateProject(
                    latestProj.copy(
                        speakers = updatedSpeakers,
                        dialogueEvents = reAttributed,
                    ),
                    banner = if (silentBackground) null else "Matched speakers using on-device conversation & voice cues",
                )
            } finally {
                keyframes.forEach { (_, bmp) -> runCatching { if (!bmp.isRecycled) bmp.recycle() } }
                if (!silentBackground) {
                    _uiState.update { cur ->
                        cur.copy(
                            isTranslatingAi = false,
                            analysisStatus = if (cur.analysisStatus is AnalysisStatus.Processing) AnalysisStatus.Ready else cur.analysisStatus,
                        )
                    }
                }
            }
        }
    }

    fun attachSubtitleFileToCurrentProject(uri: Uri, fileName: String) {
        viewModelScope.launch {
            try {
                val raw = projectStore.readTextFromUri(uri).getOrThrow()
                val cues = SubtitleParser.parse(raw, fileName)
                if (cues.isEmpty()) {
                    _uiState.update { it.copy(statusBanner = "No valid SRT/VTT/ASS cues found in $fileName") }
                    return@launch
                }
                val current = _uiState.value.project
                val newEvents = SubtitleParser.applyReadingPacingGuardrails(
                    cues.map { cue ->
                        DialogueEvent(
                            id = SubtitleParser.newEventId(),
                            startTimeMs = cue.startTimeMs,
                            endTimeMs = cue.endTimeMs,
                            sourceText = cue.text,
                            translatedText = cue.text,
                            speakerId = null,
                            speakerConfidence = 0.38f,
                            faceTrackId = null,
                            faceConfidence = 0f,
                            shotId = current.shots.firstOrNull { cue.startTimeMs in it.startMs..it.endMs }?.id
                                ?: current.shots.firstOrNull()?.id ?: "shot_1",
                            dialogueType = cue.inferredType,
                            placementMode = PlacementMode.NEUTRAL_FALLBACK,
                        )
                    }
                )
                val (speakers, attributed) = attributionEngine.assignSpeakers(
                    events = newEvents,
                    shots = current.shots,
                    faceTracks = current.faceTracks,
                    existingSpeakers = current.speakers,
                    rememberedCharacterColors = _uiState.value.characterColorMemory,
                )
                pushUndoSnapshot()
                updateProject(
                    current.copy(
                        subtitleSource = SubtitleSourceKind.UPLOADED_FILE,
                        speakers = speakers,
                        dialogueEvents = attributed,
                    ),
                    banner = "Loaded ${cues.size} cues from $fileName",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed attaching subtitle file", e)
                _uiState.update {
                    it.copy(analysisStatus = AnalysisStatus.Error(e.localizedMessage ?: "Subtitle load failed."))
                }
            }
        }
    }

    fun exportProjectJsonToUri(uri: Uri) {
        viewModelScope.launch {
            val json = projectStore.serializeProject(_uiState.value.project)
            projectStore.writeTextToUri(uri, json)
                .onSuccess { _uiState.update { it.copy(statusBanner = "Exported .dlens.json project file") } }
                .onFailure { err ->
                    _uiState.update { it.copy(statusBanner = err.localizedMessage ?: "Export failed") }
                }
        }
    }

    fun exportSrtToUri(uri: Uri) {
        exportSubtitlesToUri(
            uri = uri,
            project = _uiState.value.project,
            format = SubtitleParser.SubtitleExportFormat.SRT,
            includeSpeakerPrefixes = true,
            translationMode = _uiState.value.project.translationMode,
        )
    }

    fun exportVttToUri(uri: Uri) {
        exportSubtitlesToUri(
            uri = uri,
            project = _uiState.value.project,
            format = SubtitleParser.SubtitleExportFormat.VTT,
            includeSpeakerPrefixes = true,
            translationMode = _uiState.value.project.translationMode,
        )
    }

    fun exportAssToUri(uri: Uri) {
        exportSubtitlesToUri(
            uri = uri,
            project = _uiState.value.project,
            format = SubtitleParser.SubtitleExportFormat.ASS,
            includeSpeakerPrefixes = true,
            translationMode = _uiState.value.project.translationMode,
        )
    }

    fun exportSubtitlesToUri(
        uri: Uri,
        project: DialogueLensProject = _uiState.value.project,
        format: SubtitleParser.SubtitleExportFormat,
        includeSpeakerPrefixes: Boolean = true,
        translationMode: TranslationMode = project.translationMode,
    ) {
        if (project.dialogueEvents.isEmpty()) {
            _uiState.update { it.copy(statusBanner = "No translated subtitles generated yet to export.") }
            return
        }
        viewModelScope.launch {
            val formatted = SubtitleParser.exportFormatted(
                project = project,
                format = format,
                includeSpeakerPrefixes = includeSpeakerPrefixes,
                translationMode = translationMode,
            )
            projectStore.writeTextToUri(uri, formatted)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            statusBanner = "Exported ${format.label} (${project.dialogueEvents.size} lines · ${translationMode.label}) to local storage",
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(statusBanner = err.localizedMessage ?: "${format.label} export failed")
                    }
                }
        }
    }

    fun saveSubtitlesToDownloads(
        project: DialogueLensProject = _uiState.value.project,
        format: SubtitleParser.SubtitleExportFormat,
        includeSpeakerPrefixes: Boolean = true,
        translationMode: TranslationMode = project.translationMode,
        onRequireSafPicker: (suggestedFileName: String, format: SubtitleParser.SubtitleExportFormat) -> Unit,
    ) {
        if (project.dialogueEvents.isEmpty()) {
            _uiState.update { it.copy(statusBanner = "No translated subtitles generated yet to save.") }
            return
        }
        val fileName = SubtitleParser.suggestedExportFilename(project.title, format)
        viewModelScope.launch {
            val formatted = SubtitleParser.exportFormatted(
                project = project,
                format = format,
                includeSpeakerPrefixes = includeSpeakerPrefixes,
                translationMode = translationMode,
            )
            projectStore.saveTextToDownloads(
                fileName = fileName,
                mimeType = format.mimeType,
                content = formatted,
            ).onSuccess { savedPath ->
                _uiState.update {
                    it.copy(
                        statusBanner = "Saved ${project.dialogueEvents.size} lines (${translationMode.label}) to $savedPath",
                    )
                }
            }.onFailure {
                onRequireSafPicker(fileName, format)
            }
        }
    }

    fun setSubtitlePrepFormat(format: SubtitleParser.SubtitleExportFormat) {
        _uiState.update { it.copy(selectedPrepFormat = format) }
    }

    fun clearSubtitlePrepState() {
        subtitlePrepJob?.cancel()
        _uiState.update { it.copy(subtitlePrepState = SubtitlePrepState.Idle) }
    }

    fun cancelSubtitlePreGeneration() {
        val currentPrep = _uiState.value.subtitlePrepState
        subtitlePrepJob?.cancel()
        if (currentPrep is SubtitlePrepState.Working && currentPrep.partialProject != null) {
            val partial = currentPrep.partialProject
            _uiState.update {
                it.copy(
                    subtitlePrepState = SubtitlePrepState.Ready(
                        generatedProject = partial,
                        sourceLabel = "${currentPrep.sourceLabel} (Stopped)",
                        cueCount = partial.dialogueEvents.size,
                        speakerCount = partial.speakers.size,
                        isPartial = true,
                        note = "Stopped early with ${partial.dialogueEvents.size} generated cues ready to download.",
                    )
                )
            }
        } else {
            _uiState.update { it.copy(subtitlePrepState = SubtitlePrepState.Idle) }
        }
    }

    fun generateSubtitlesForUrl(
        rawUrl: String,
        youtubeVideoId: String?,
        pastedSubtitleText: String,
        subtitleUri: Uri?,
        subtitleName: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
    ) {
        subtitlePrepJob?.cancel()
        subtitlePrepJob = viewModelScope.launch {
            val curState = _uiState.value
            val quotaMode = curState.quotaSaverMode
            if (pastedSubtitleText.isNotBlank() || subtitleUri != null) {
                val rawText = if (subtitleUri != null) {
                    projectStore.readTextFromUri(subtitleUri).getOrNull().orEmpty().ifBlank { pastedSubtitleText }
                } else {
                    pastedSubtitleText
                }
                subtitlePreGenerator.prepareFromTextOrCues(
                    title = youtubeVideoId?.let { "YouTube · $it" } ?: "Subtitle Track",
                    videoUrl = rawUrl.trim(),
                    videoKind = if (youtubeVideoId != null) VideoSourceKind.YOUTUBE_COMPLIANT else VideoSourceKind.WEB_STREAM,
                    youtubeVideoId = youtubeVideoId,
                    rawText = rawText,
                    fileName = subtitleName,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    translationMode = translationMode,
                    styleSettings = curState.styleSettings,
                    glossary = curState.glossaryEntries,
                    characterColorMemory = curState.characterColorMemory,
                    quotaSaverMode = quotaMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    sourceLabel = if (subtitleUri != null) "Attached File" else "Transcript Text",
                    onProgress = { prep -> _uiState.update { it.copy(subtitlePrepState = prep) } },
                )
                return@launch
            }

            if (!youtubeVideoId.isNullOrBlank()) {
                subtitlePreGenerator.prepareYouTubeSubtitles(
                    youtubeUrl = rawUrl,
                    videoId = youtubeVideoId,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    translationMode = translationMode,
                    styleSettings = curState.styleSettings,
                    glossary = curState.glossaryEntries,
                    characterColorMemory = curState.characterColorMemory,
                    quotaSaverMode = quotaMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    onQuotaTripped = { quotaCircuitBreakerTripped = true },
                    onProgress = { prep -> _uiState.update { it.copy(subtitlePrepState = prep) } },
                )
            } else {
                _uiState.update {
                    it.copy(
                        subtitlePrepState = SubtitlePrepState.Error(
                            "Direct subtitle pre-generation supports YouTube links, local video files, or pasted transcripts. Use 'Submit & Open Stream' to capture live web cinema tracks."
                        )
                    )
                }
            }
        }
    }

    fun generateSubtitlesForLocalVideo(
        videoUri: Uri,
        videoTitle: String,
        subtitleUri: Uri?,
        subtitleName: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
    ) {
        subtitlePrepJob?.cancel()
        subtitlePrepJob = viewModelScope.launch {
            val curState = _uiState.value
            val quotaMode = curState.quotaSaverMode
            if (subtitleUri != null) {
                val rawText = projectStore.readTextFromUri(subtitleUri).getOrNull().orEmpty()
                if (rawText.isNotBlank()) {
                    subtitlePreGenerator.prepareFromTextOrCues(
                        title = videoTitle.ifBlank { "Local Video" },
                        videoUrl = videoUri.toString(),
                        videoKind = VideoSourceKind.LOCAL_MP4,
                        youtubeVideoId = null,
                        rawText = rawText,
                        fileName = subtitleName,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        translationMode = translationMode,
                        styleSettings = curState.styleSettings,
                        glossary = curState.glossaryEntries,
                        characterColorMemory = curState.characterColorMemory,
                        quotaSaverMode = quotaMode,
                        quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                        sourceLabel = "Attached Subtitle",
                        onProgress = { prep -> _uiState.update { it.copy(subtitlePrepState = prep) } },
                    )
                    return@launch
                }
            }

            subtitlePreGenerator.prepareLocalVideoSubtitles(
                videoUri = videoUri,
                videoTitle = videoTitle,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translationMode = translationMode,
                styleSettings = curState.styleSettings,
                glossary = curState.glossaryEntries,
                characterColorMemory = curState.characterColorMemory,
                quotaSaverMode = quotaMode,
                quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                onQuotaTripped = { quotaCircuitBreakerTripped = true },
                onProgress = { prep -> _uiState.update { it.copy(subtitlePrepState = prep) } },
            )
        }
    }

    fun savePreparedSubtitlesToDownloads(
        onRequireSafPicker: (suggestedFileName: String, format: SubtitleParser.SubtitleExportFormat) -> Unit,
    ) {
        val cur = _uiState.value
        val ready = cur.subtitlePrepState as? SubtitlePrepState.Ready ?: return
        val format = cur.selectedPrepFormat
        val fileName = SubtitleParser.suggestedExportFilename(ready.generatedProject.title, format)
        viewModelScope.launch {
            val formatted = SubtitleParser.exportFormatted(
                project = ready.generatedProject,
                format = format,
                includeSpeakerPrefixes = true,
                translationMode = ready.generatedProject.translationMode,
            )
            projectStore.saveTextToDownloads(
                fileName = fileName,
                mimeType = format.mimeType,
                content = formatted,
            ).onSuccess { savedPath ->
                _uiState.update { state ->
                    val curReady = state.subtitlePrepState as? SubtitlePrepState.Ready
                    state.copy(
                        subtitlePrepState = curReady?.copy(
                            note = "Saved $fileName (${ready.cueCount} cues) to $savedPath.",
                        ) ?: state.subtitlePrepState,
                        statusBanner = "Saved ${format.label} (${ready.cueCount} cues) to $savedPath",
                    )
                }
            }.onFailure {
                onRequireSafPicker(fileName, format)
            }
        }
    }

    fun exportPreparedSubtitlesToUri(uri: Uri) {
        val cur = _uiState.value
        val ready = cur.subtitlePrepState as? SubtitlePrepState.Ready ?: return
        val format = cur.selectedPrepFormat
        viewModelScope.launch {
            val formatted = SubtitleParser.exportFormatted(
                project = ready.generatedProject,
                format = format,
                includeSpeakerPrefixes = true,
                translationMode = ready.generatedProject.translationMode,
            )
            projectStore.writeTextToUri(uri, formatted)
                .onSuccess {
                    _uiState.update { state ->
                        val curReady = state.subtitlePrepState as? SubtitlePrepState.Ready
                        state.copy(
                            subtitlePrepState = curReady?.copy(
                                note = "Downloaded ${format.label} (${ready.cueCount} cues) to your chosen folder.",
                            ) ?: state.subtitlePrepState,
                            statusBanner = "Downloaded ${format.label} subtitle file (${ready.cueCount} cues)",
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(statusBanner = err.localizedMessage ?: "Subtitle download failed")
                    }
                }
        }
    }

    fun launchPreparedProject() {
        val ready = _uiState.value.subtitlePrepState as? SubtitlePrepState.Ready ?: return
        val project = ready.generatedProject
        viewModelScope.launch {
            projectStore.saveProject(project)
            refreshSavedProjects()
        }
        loadProject(project)
        if (project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT &&
            !project.videoSource.youtubeVideoId.isNullOrBlank()
        ) {
            val horizonMs = project.dialogueEvents.maxOfOrNull { it.endTimeMs } ?: 0L
            val full = project.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK ||
                horizonMs >= project.videoSource.durationMs - 15_000L
            _uiState.update {
                it.copy(
                    scoutedUpToMs = horizonMs,
                    scoutStatusText = "Pre-generated ${project.dialogueEvents.size} bubbles ($0)",
                    scoutCaptionState = ScoutCaptionState.Ready(
                        sourceLabel = ready.sourceLabel,
                        cueCount = project.dialogueEvents.size,
                        speakerCount = project.speakers.size,
                    ),
                    statusBanner = "Loaded ${project.dialogueEvents.size} pre-generated bubbles!",
                )
            }
            if (!full) {
                runYouTubeScoutTranscriptPipeline(
                    projectId = project.id,
                    videoId = project.videoSource.youtubeVideoId,
                    skipTierOneCaptions = true,
                    resumeFromHorizonSec = (horizonMs / 1000L).toInt().coerceAtLeast(0),
                )
            }
        }
    }

    fun exportBurnedMp4ToUri(uri: Uri) {
        val current = _uiState.value.project
        if (current.videoSource.kind != VideoSourceKind.LOCAL_MP4) {
            _uiState.update {
                it.copy(statusBanner = "Burned MP4 export requires a local MP4 video. Export .ass or .srt for embedded streams.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(exportProgress = 0.02f, statusBanner = "Rendering burned-in balloon MP4...") }
            val result = videoExporter.exportBurnedMp4(current, uri) { p ->
                _uiState.update { it.copy(exportProgress = p) }
            }
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        exportProgress = null,
                        statusBanner = "Exported MP4 with burned-in DialogueLens balloons!",
                    )
                }
            }.onFailure { err ->
                Log.e(TAG, "Burned MP4 export failed", err)
                _uiState.update {
                    it.copy(
                        exportProgress = null,
                        statusBanner = "MP4 burn-in export failed: ${err.localizedMessage ?: "codec error"}",
                    )
                }
            }
        }
    }

    fun importProjectJsonFromUri(uri: Uri) {
        viewModelScope.launch {
            val text = projectStore.readTextFromUri(uri).getOrNull() ?: return@launch
            val imported = projectStore.deserializeProject(text)
            if (imported == null) {
                _uiState.update { it.copy(statusBanner = "Invalid DialogueLens project JSON file") }
                return@launch
            }
            loadProject(imported)
        }
    }

    fun loadProject(project: DialogueLensProject) {
        analysisJob?.cancel()
        introJob?.cancel()
        lookaheadJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        lastScoutSeekSentAtMs = 0L
        lastScoutSeekTargetMs = -1L
        scoutCompletedWindows.clear()
        scoutAudioSliceTimestamps.clear()
        capturedYouTubeTimedTextKeys.clear()
        autoAnalyzedEventIds.clear()
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        layoutEngine.clearSmoothing()
        playbackClockAnchor.reset(0L, _uiState.value.playbackSpeed, false)
        if (project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(project.videoSource.uriString)))
            exoPlayer.prepare()
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
        val hasRealSubs = LookaheadWindowPlanner.hasRealSubtitles(project.dialogueEvents)
        val cleanedProject = if (!hasRealSubs && project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
            project.copy(
                subtitleSource = SubtitleSourceKind.BUILT_IN_DEMO,
                dialogueEvents = emptyList(),
            )
        } else {
            project
        }
        _uiState.update {
            it.copy(
                project = cleanedProject,
                showStartLauncher = false,
                hasPendingPlayIntro = false,
                displayMode = CaptionDisplayMode.BALLOONS,
                isPlaying = false,
                currentTimeMs = 0L,
                selectedEventId = null,
                streamPlayRequest = null,
                youtubeSeekRequest = null,
                isCinemaIntroActive = false,
                introRemainingMs = 0L,
                isOpeningSpeakersReady = hasRealSubs,
                scoutTimeMs = 0L,
                scoutedUpToMs = 0L,
                scoutStatusText = "",
                scoutPlayRequest = null,
                scoutSeekRequest = null,
                analysisStatus = AnalysisStatus.Ready,
                scoutCaptionState = if (hasRealSubs) {
                    ScoutCaptionState.Ready("Saved", cleanedProject.dialogueEvents.size, cleanedProject.speakers.size)
                } else {
                    ScoutCaptionState.Idle
                },
                youtubeNativeCcTrackCount = null,
                youtubeNativeCcLang = "",
                scoutDiagnosticDetail = com.example.data.ai.AppCheckDiagnostics.summaryLine(),
                activeSpeakerToast = null,
                liveFaceExclusionRects = emptyList(),
                bufferedWindowRanges = emptyList(),
                scoutCompletedWindows = 0,
                scoutTotalWindows = 0,
                scoutFailedSliceCount = 0,
                statusBanner = "Opened project: ${cleanedProject.title}",
            )
        }
        recomputeBalloons()

        if (cleanedProject.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT &&
            !hasRealSubs &&
            !cleanedProject.videoSource.youtubeVideoId.isNullOrBlank()
        ) {
            runYouTubeScoutTranscriptPipeline(
                projectId = cleanedProject.id,
                videoId = cleanedProject.videoSource.youtubeVideoId,
                skipTierOneCaptions = false,
            )
        }
    }

    private fun startRollingMediaLookahead(
        projectId: String,
        videoUri: Uri?,
        httpUrl: String?,
        httpHeaders: Map<String, String> = emptyMap(),
        sourceLanguage: String,
        targetLanguage: String,
        hasPreloadedSubtitles: Boolean,
    ) {
        lookaheadJob?.cancel()
        lookaheadJob = viewModelScope.launch {
            val failedHttpWindowAttempts = mutableMapOf<Long, Int>()
            while (isActive) {
                val state = _uiState.value
                val proj = state.project
                if (proj.id != projectId || !state.dualInstanceEnabled) break

                val durationMs = proj.videoSource.durationMs.coerceAtLeast(10_000L)
                val nextWin = LookaheadWindowPlanner.nextPriorityWindow(
                    viewerPosMs = state.currentTimeMs,
                    durationMs = durationMs,
                    completedWindowStarts = scoutCompletedWindows,
                )
                if (nextWin == null) {
                    _uiState.update {
                        it.copy(
                            analysisStatus = AnalysisStatus.Ready,
                            scoutStatusText = "Scout complete — full transcript & speaker map buffered",
                        )
                    }
                    break
                }

                _uiState.update {
                    it.copy(
                        scoutStatusText = "Scout @ ${SubtitleParser.formatCompactTime(nextWin.startMs)}–${SubtitleParser.formatCompactTime(nextWin.endMs)} · Hearing & matching faces...",
                        analysisStatus = AnalysisStatus.Processing(
                            phaseLabel = "Scout +7s looking ahead (${SubtitleParser.formatCompactTime(nextWin.startMs)}–${SubtitleParser.formatCompactTime(nextWin.endMs)})...",
                            progress = (nextWin.endMs.toFloat() / durationMs.toFloat()).coerceIn(0.08f, 0.98f),
                            analyzedUpToMs = nextWin.endMs,
                            totalMs = durationMs,
                        ),
                    )
                }

                val existingShots = proj.shots.filterNot { it.cutType == "CONTINUOUS" && it.startMs == 0L && it.endMs == durationMs }
                val (visualResult, wavChunk) = coroutineScope {
                    val vDef = async {
                        if (videoUri != null) {
                            videoAnalyzer.analyzeWindow(
                                uri = videoUri,
                                windowStartMs = nextWin.startMs,
                                windowEndMs = nextWin.endMs,
                                sampleIntervalMs = proj.qualityMode.sampleIntervalMs,
                                existingShotCount = existingShots.size,
                            )
                        } else {
                            null
                        }
                    }
                    val aDef = async {
                        audioAnalyzer.extractWavWindowForAsr(
                            uri = videoUri,
                            httpUrl = httpUrl,
                            httpHeaders = httpHeaders,
                            windowStartMs = nextWin.startMs,
                            windowEndMs = nextWin.endMs,
                        )
                    }
                    vDef.await() to aDef.await()
                }

                val latestAfterDecode = _uiState.value.project
                if (latestAfterDecode.id != projectId) break

                val mergedShots = if (visualResult != null && visualResult.shots.isNotEmpty()) {
                    val base = latestAfterDecode.shots.filterNot {
                        it.id == "shot_1" && it.startMs == 0L && it.endMs == durationMs && latestAfterDecode.faceTracks.isEmpty()
                    }
                    (base + visualResult.shots).distinctBy { it.id }.sortedBy { it.startMs }
                } else {
                    latestAfterDecode.shots
                }
                val mergedFaces = if (visualResult != null && visualResult.faceTracks.isNotEmpty()) {
                    (latestAfterDecode.faceTracks + visualResult.faceTracks).distinctBy { it.id }
                } else {
                    latestAfterDecode.faceTracks
                }

                val existingInWindow = latestAfterDecode.dialogueEvents.filter { evt ->
                    evt.startTimeMs in nextWin.startMs until nextWin.endMs &&
                        !evt.sourceText.startsWith("Web Cinema ready")
                }

                val hintMap = mutableMapOf<String, AiTranslatedLine>()
                val mergedGlossary = (latestAfterDecode.glossary + _uiState.value.glossaryEntries)
                    .distinctBy { it.term.trim().lowercase() }

                val quotaMode = _uiState.value.quotaSaverMode
                var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = quotaMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                val allowCloudScout = quotaMode != QuotaSaverMode.ZERO_CLOUD && !quotaCircuitBreakerTripped
                val updatedEventsInWindow: List<DialogueEvent> = if (
                    allowCloudScout && (!hasPreloadedSubtitles || existingInWindow.isEmpty()) && wavChunk != null
                ) {
                    var asrRes = scoutHeartbeatService.executeWithBackoffRetry(
                        modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                        maxRetries = 3,
                        onWarmToken = {
                            com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L)
                        },
                        onRetryScheduled = { attempt, maxRetries, delayMs, _ ->
                            val delaySec = String.format(java.util.Locale.US, "%.1f", delayMs / 1000.0)
                            _uiState.update { cur ->
                                cur.copy(
                                    scoutStatusText = "${activeTier.displayLabel} dropout · Retry $attempt/$maxRetries in ${delaySec}s",
                                    scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · Media Scout retry $attempt/$maxRetries",
                                )
                            }
                        },
                    ) {
                        translationAiService.transcribeAndTranslateAudio(
                            wavBytes = wavChunk.wavBytes,
                            chunkStartMs = wavChunk.startMs,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                            glossary = mergedGlossary,
                            audioMimeType = "audio/wav",
                            preferLiteModel = activeTier.preferLite,
                        )
                    }
                    if (asrRes.isFailure &&
                        TranslationAiService.isQuotaOrCreditExhaustedError(asrRes.exceptionOrNull()) &&
                        activeTier == ScoutExecutionTier.BEST_AI
                    ) {
                        activeTier = ScoutExecutionTier.LITE_AI
                        downgradedToLiteForStream = true
                        asrRes = scoutHeartbeatService.executeWithBackoffRetry(
                            modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                            maxRetries = 2,
                            onWarmToken = {
                                com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L)
                            },
                        ) {
                            translationAiService.transcribeAndTranslateAudio(
                                wavBytes = wavChunk.wavBytes,
                                chunkStartMs = wavChunk.startMs,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage,
                                glossary = mergedGlossary,
                                audioMimeType = "audio/wav",
                                preferLiteModel = true,
                            )
                        }
                    }
                    if (asrRes.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(asrRes.exceptionOrNull())) {
                        quotaCircuitBreakerTripped = true
                    }
                    val aiCues = asrRes.getOrNull().orEmpty()
                    if (aiCues.isNotEmpty()) {
                        SubtitleParser.applyReadingPacingGuardrails(
                            aiCues.map { c ->
                                val id = SubtitleParser.newEventId()
                                val mid = (c.startMs + c.endMs) / 2L
                                val sId = mergedShots.firstOrNull { mid in it.startMs..it.endMs }?.id
                                    ?: mergedShots.firstOrNull()?.id ?: "shot_1"
                                hintMap[id] = AiTranslatedLine(
                                    id = id,
                                    naturalTranslation = c.naturalTranslation,
                                    literalTranslation = c.literalTranslation,
                                    romanization = c.romanization,
                                    dialogueTypeHint = c.dialogueType,
                                    speakerTurnHint = "UNCERTAIN",
                                    suggestedSpeakerSlot = c.suggestedSpeakerSlot,
                                    contextReason = c.contextReason,
                                    culturalNote = c.culturalNote,
                                )
                                DialogueEvent(
                                    id = id,
                                    startTimeMs = c.startMs,
                                    endTimeMs = c.endMs,
                                    sourceText = c.sourceText,
                                    translatedText = c.naturalTranslation,
                                    literalText = c.literalTranslation,
                                    romanization = c.romanization,
                                    speakerId = null,
                                    speakerConfidence = 0.56f,
                                    faceTrackId = null,
                                    faceConfidence = 0f,
                                    shotId = sId,
                                    dialogueType = c.dialogueType,
                                    placementMode = if (c.dialogueType == DialogueType.SPOKEN) PlacementMode.AUTO else PlacementMode.EDGE_DOCKED,
                                    contextCueReason = c.contextReason,
                                    culturalNote = c.culturalNote,
                                )
                            }
                        )
                    } else {
                        existingInWindow
                    }
                } else if (existingInWindow.isNotEmpty()) {
                    val audioFeat = audioAnalyzer.extractVoiceFeatures(
                        uri = videoUri,
                        events = existingInWindow,
                        httpUrl = httpUrl,
                        httpHeaders = httpHeaders,
                    )
                    val enrichedExisting = existingInWindow.map { evt ->
                        evt.copy(audioFeatures = audioFeat[evt.id] ?: evt.audioFeatures)
                    }
                    if (allowCloudScout && !sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
                        var trRes = scoutHeartbeatService.executeWithBackoffRetry(
                            modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                            maxRetries = 2,
                        ) {
                            translationAiService.translateBatch(
                                events = enrichedExisting,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage,
                                speakers = latestAfterDecode.speakers,
                                glossary = mergedGlossary,
                                preferLiteModel = activeTier.preferLite,
                            )
                        }
                        if (trRes.isFailure &&
                            TranslationAiService.isQuotaOrCreditExhaustedError(trRes.exceptionOrNull()) &&
                            activeTier == ScoutExecutionTier.BEST_AI
                        ) {
                            activeTier = ScoutExecutionTier.LITE_AI
                            downgradedToLiteForStream = true
                            trRes = scoutHeartbeatService.executeWithBackoffRetry(
                                modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                                maxRetries = 2,
                            ) {
                                translationAiService.translateBatch(
                                    events = enrichedExisting,
                                    sourceLanguage = sourceLanguage,
                                    targetLanguage = targetLanguage,
                                    speakers = latestAfterDecode.speakers,
                                    glossary = mergedGlossary,
                                    preferLiteModel = true,
                                )
                            }
                        }
                        if (trRes.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(trRes.exceptionOrNull())) {
                            quotaCircuitBreakerTripped = true
                        }
                        val trById = trRes.getOrNull().orEmpty().associateBy { it.id }
                        trById.forEach { (k, v) -> hintMap[k] = v }
                        SubtitleParser.applyReadingPacingGuardrails(
                            enrichedExisting.map { evt ->
                                val tr = trById[evt.id] ?: return@map evt
                                evt.copy(
                                    translatedText = tr.naturalTranslation,
                                    literalText = tr.literalTranslation,
                                    romanization = tr.romanization,
                                    dialogueType = if (evt.lockedByUser) evt.dialogueType else tr.dialogueTypeHint,
                                    contextCueReason = tr.contextReason,
                                    culturalNote = tr.culturalNote.ifBlank { evt.culturalNote },
                                )
                            }
                        )
                    } else {
                        enrichedExisting
                    }
                } else {
                    emptyList()
                }

                val latestBeforeCommit = _uiState.value.project
                if (latestBeforeCommit.id != projectId) break

                if (httpUrl != null && wavChunk == null && existingInWindow.isEmpty() && !hasPreloadedSubtitles) {
                    val attempts = (failedHttpWindowAttempts[nextWin.startMs] ?: 0) + 1
                    failedHttpWindowAttempts[nextWin.startMs] = attempts
                    if (attempts < 2) {
                        delay(550L)
                        continue
                    }
                }

                val mergedAllEvents = LookaheadWindowPlanner.mergeWindowDialogueEvents(
                    existing = latestBeforeCommit.dialogueEvents.filterNot { it.sourceText.startsWith("Web Cinema ready") },
                    incoming = updatedEventsInWindow,
                    windowStartMs = nextWin.startMs,
                    windowEndMs = nextWin.endMs,
                )

                val (updatedSpeakers, attributed) = attributionEngine.assignSpeakers(
                    events = mergedAllEvents,
                    shots = mergedShots,
                    faceTracks = mergedFaces,
                    existingSpeakers = latestBeforeCommit.speakers,
                    turnHints = hintMap,
                    rememberedCharacterColors = _uiState.value.characterColorMemory,
                )

                scoutCompletedWindows.add(nextWin.startMs)
                updatedEventsInWindow.forEach { autoAnalyzedEventIds.add(it.id) }
                val newUpTo = maxOf(_uiState.value.scoutedUpToMs, nextWin.endMs)
                val nextSubSource = if (updatedEventsInWindow.isNotEmpty() &&
                    latestBeforeCommit.subtitleSource == SubtitleSourceKind.BUILT_IN_DEMO
                ) {
                    SubtitleSourceKind.AI_DRAFT
                } else {
                    latestBeforeCommit.subtitleSource
                }
                val realCueCount = attributed.count { !it.sourceText.startsWith("Web Cinema ready") }
                _uiState.update { cur ->
                    cur.copy(
                        scoutedUpToMs = newUpTo,
                        isOpeningSpeakersReady = cur.isOpeningSpeakersReady || realCueCount > 0,
                        scoutStatusText = "Scout buffered up to ${SubtitleParser.formatCompactTime(newUpTo)} (+7s ahead)",
                        scoutCaptionState = if (realCueCount > 0) {
                            ScoutCaptionState.Ready("Stream AI", realCueCount, updatedSpeakers.size)
                        } else {
                            cur.scoutCaptionState
                        },
                    )
                }
                updateProject(
                    latestBeforeCommit.copy(
                        subtitleSource = nextSubSource,
                        shots = mergedShots,
                        faceTracks = mergedFaces,
                        speakers = updatedSpeakers,
                        dialogueEvents = attributed,
                    )
                )
                delay(80L)
            }
        }
    }

    fun deleteCurrentProject() {
        val currentId = _uiState.value.project.id
        viewModelScope.launch {
            projectStore.deleteProject(currentId)
            refreshSavedProjects()
            loadDemoScenario()
            _uiState.update {
                it.copy(statusBanner = "Project deleted locally. No biometric data retained.")
            }
        }
    }

    fun wipeAllLocalProjects() {
        viewModelScope.launch {
            projectStore.deleteAllProjects()
            refreshSavedProjects()
            loadDemoScenario()
            _uiState.update {
                it.copy(statusBanner = "All local projects deleted.")
            }
        }
    }

    fun addManualDialogueLine(text: String, translation: String, type: DialogueType) {
        pushUndoSnapshot()
        val current = _uiState.value.project
        val startMs = _uiState.value.currentTimeMs
        val endMs = (startMs + 3000L).coerceAtMost(current.videoSource.durationMs)
        val shotId = current.shots.firstOrNull { startMs in it.startMs..it.endMs }?.id
            ?: current.shots.firstOrNull()?.id ?: "shot_1"
        val evt = DialogueEvent(
            id = SubtitleParser.newEventId(),
            startTimeMs = startMs,
            endTimeMs = endMs,
            sourceText = text,
            translatedText = translation.ifBlank { text },
            speakerId = current.speakers.firstOrNull()?.id,
            speakerConfidence = 0.85f,
            faceTrackId = current.faceTracks.firstOrNull { it.shotId == shotId }?.id,
            faceConfidence = 0.85f,
            shotId = shotId,
            dialogueType = type,
            placementMode = if (type == DialogueType.SPOKEN) PlacementMode.AUTO else PlacementMode.EDGE_DOCKED,
            lockedByUser = true,
        )
        updateProject(
            current.copy(dialogueEvents = (current.dialogueEvents + evt).sortedBy { it.startTimeMs }),
            banner = "Added dialogue event at ${SubtitleParser.formatCompactTime(startMs)}",
        )
    }

    fun createYouTubeProject(
        youtubeUrl: String,
        youtubeVideoId: String,
        subtitleUri: Uri?,
        subtitleFileName: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
        pastedSubtitleText: String = "",
    ) {
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        introJob?.cancel()
        lookaheadJob?.cancel()
        downgradedToLiteForStream = false
        pendingWebTimedTextFallback = null
        scoutHeartbeatService.resetForNewStream(
            if (_uiState.value.quotaSaverMode == QuotaSaverMode.PRO_ACCURACY) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
        )
        scoutHeartbeatService.setZeroCloudPaused(_uiState.value.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD)
        lastScoutSeekSentAtMs = 0L
        lastScoutSeekTargetMs = -1L
        scoutCompletedWindows.clear()
        scoutAudioSliceTimestamps.clear()
        capturedYouTubeTimedTextKeys.clear()
        autoAnalyzedEventIds.clear()
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        layoutEngine.clearSmoothing()
        playbackClockAnchor.reset(0L, _uiState.value.playbackSpeed, false)

        viewModelScope.launch {
            val rawFileText = if (subtitleUri != null) {
                projectStore.readTextFromUri(subtitleUri).getOrNull().orEmpty()
            } else {
                ""
            }
            val cues: List<RawSubtitleCue> = when {
                rawFileText.isNotBlank() -> SubtitleParser.parse(rawFileText, subtitleFileName)
                pastedSubtitleText.isNotBlank() -> SubtitleParser.parseSmartTranscript(pastedSubtitleText)
                else -> emptyList()
            }
            val cachedTranscript = if (cues.isEmpty()) {
                projectStore.loadCachedTranscript(
                    ProjectStore.canonicalYouTubeCacheKey(youtubeVideoId, targetLanguage)
                )
            } else {
                null
            }
            val initialSpeakers = listOf(
                Speaker("spk_a", "Speaker A", accentIndex = 0),
                Speaker("spk_b", "Speaker B", accentIndex = 1),
            )
            val (speakers, events, resolvedDurationMs, resolvedSubSource, resolvedSourceLang, resolvedTitle, resolvedChannel, resolvedDesc, cachedHorizonMs) =
                if (cues.isNotEmpty()) {
                    val cueDur = (cues.maxOfOrNull { it.endTimeMs } ?: 180_000L).coerceAtLeast(60_000L)
                    val ytShots = listOf(Shot("shot_1", 0L, cueDur, cutType = "EMBEDDED_YOUTUBE"))
                    val rawEvents = SubtitleParser.applyReadingPacingGuardrails(
                        cues.map { cue ->
                            DialogueEvent(
                                id = SubtitleParser.newEventId(),
                                startTimeMs = cue.startTimeMs,
                                endTimeMs = cue.endTimeMs,
                                sourceText = cue.text,
                                translatedText = cue.text,
                                speakerId = null,
                                speakerConfidence = 0.48f,
                                faceTrackId = null,
                                faceConfidence = 0f,
                                shotId = "shot_1",
                                dialogueType = cue.inferredType,
                                placementMode = if (cue.inferredType == DialogueType.SPOKEN) {
                                    PlacementMode.NEUTRAL_FALLBACK
                                } else {
                                    PlacementMode.EDGE_DOCKED
                                },
                            )
                        }
                    )
                    val (spk, evts) = attributionEngine.assignSpeakers(
                        events = rawEvents,
                        shots = ytShots,
                        faceTracks = emptyList(),
                        existingSpeakers = initialSpeakers,
                        rememberedCharacterColors = _uiState.value.characterColorMemory,
                    )
                    CacheRestoreTuple(
                        speakers = spk,
                        events = evts,
                        durationMs = cueDur,
                        subSource = SubtitleSourceKind.UPLOADED_FILE,
                        sourceLanguage = sourceLanguage,
                        title = "YouTube · $youtubeVideoId",
                        channelName = "",
                        description = "",
                        horizonMs = cueDur,
                    )
                } else if (cachedTranscript != null && LookaheadWindowPlanner.hasRealSubtitles(cachedTranscript.dialogueEvents)) {
                    val dur = maxOf(cachedTranscript.durationMs, cachedTranscript.scoutedUpToMs, 60_000L)
                    CacheRestoreTuple(
                        speakers = cachedTranscript.speakers.ifEmpty { initialSpeakers },
                        events = cachedTranscript.dialogueEvents,
                        durationMs = dur,
                        subSource = cachedTranscript.subtitleSource,
                        sourceLanguage = cachedTranscript.sourceLanguage.ifBlank { sourceLanguage },
                        title = cachedTranscript.title.ifBlank { "YouTube · $youtubeVideoId" },
                        channelName = cachedTranscript.channelName,
                        description = cachedTranscript.description,
                        horizonMs = cachedTranscript.scoutedUpToMs.coerceAtLeast(
                            cachedTranscript.dialogueEvents.maxOfOrNull { it.endTimeMs } ?: 0L
                        ),
                    )
                } else {
                    CacheRestoreTuple(
                        speakers = initialSpeakers,
                        events = emptyList(),
                        durationMs = 180_000L,
                        subSource = SubtitleSourceKind.BUILT_IN_DEMO,
                        sourceLanguage = sourceLanguage,
                        title = "YouTube · $youtubeVideoId",
                        channelName = "",
                        description = "",
                        horizonMs = 0L,
                    )
                }

            val restoredFromCache = cues.isEmpty() && cachedTranscript != null && LookaheadWindowPlanner.hasRealSubtitles(cachedTranscript.dialogueEvents)
            val ytProject = DialogueLensProject(
                id = "yt_${youtubeVideoId}_${System.currentTimeMillis()}",
                title = resolvedTitle,
                createdAtEpochMs = System.currentTimeMillis(),
                sourceLanguage = resolvedSourceLang,
                targetLanguage = targetLanguage,
                translationMode = translationMode,
                subtitleSource = resolvedSubSource,
                qualityMode = ProcessingQualityMode.BALANCED,
                videoSource = VideoSource(
                    uriString = youtubeUrl.trim(),
                    displayName = "YouTube ($youtubeVideoId)",
                    kind = VideoSourceKind.YOUTUBE_COMPLIANT,
                    durationMs = resolvedDurationMs,
                    width = 1920,
                    height = 1080,
                    youtubeVideoId = youtubeVideoId,
                    channelName = resolvedChannel,
                    description = resolvedDesc,
                ),
                shots = if (cachedTranscript != null && cachedTranscript.shots.isNotEmpty()) {
                    cachedTranscript.shots
                } else {
                    listOf(Shot("shot_1", 0L, resolvedDurationMs, cutType = "EMBEDDED_YOUTUBE"))
                },
                speakers = speakers,
                faceTracks = emptyList(),
                dialogueEvents = events,
                styleSettings = _uiState.value.styleSettings,
                glossary = _uiState.value.glossaryEntries,
                castDiscoverySummary = cachedTranscript?.castDiscoverySummary.orEmpty(),
            )

            undoStack.clear()
            catchUpBufferDismissedUntilMs = 0L
            _uiState.update {
                    val initialTier = LookaheadWindowPlanner.initialExecutionTier(
                        quotaSaverMode = it.quotaSaverMode,
                        quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    )
                    val initialWorkingMsg = when (initialTier) {
                        ScoutExecutionTier.FREE_LOCAL -> if (quotaCircuitBreakerTripped) {
                            "Credits depleted — searching free YouTube CC..."
                        } else {
                            "Searching free YouTube CC..."
                        }
                        else -> "Smart Pre-Roll · ${initialTier.displayLabel} (0s–35s)..."
                    }
                    it.copy(
                        project = ytProject,
                        showStartLauncher = false,
                        hasPendingPlayIntro = true,
                        displayMode = CaptionDisplayMode.BALLOONS,
                        isCinemaIntroActive = false,
                        introRemainingMs = 0L,
                        introTotalDurationMs = LookaheadWindowPlanner.recommendedIntroMaxDurationMs(resolvedDurationMs),
                        requiredOpeningLeadWindows = LookaheadWindowPlanner.recommendedOpeningLeadWindows(resolvedDurationMs),
                        isScoutCatchUpBuffering = false,
                        canUndo = false,
                        isPlaying = false,
                        currentTimeMs = 0L,
                        scoutTimeMs = 0L,
                        scoutedUpToMs = cachedHorizonMs,
                        scoutStatusText = when {
                            cues.isNotEmpty() -> "Loaded ${events.size} bubbles · Ready"
                            restoredFromCache -> "Cached ${events.size} bubbles ($0)"
                            else -> initialWorkingMsg
                        },
                        selectedEventId = null,
                        isTranslatingAi = false,
                        streamPlayRequest = null,
                        youtubeSeekRequest = null,
                        analysisStatus = AnalysisStatus.Ready,
                        scoutCaptionState = when {
                            cues.isNotEmpty() -> ScoutCaptionState.Ready("Attached", events.size, speakers.size)
                            restoredFromCache -> ScoutCaptionState.Ready("Cached · $0", events.size, speakers.size)
                            initialTier == ScoutExecutionTier.FREE_LOCAL -> ScoutCaptionState.FetchingCaptions(initialWorkingMsg)
                            else -> ScoutCaptionState.AiTranscribing(initialWorkingMsg, completedWindows = 0, cuesSoFar = 0)
                        },
                        youtubeNativeCcTrackCount = null,
                        youtubeNativeCcLang = "",
                        scoutDiagnosticDetail = com.example.data.ai.AppCheckDiagnostics.summaryLine(),
                        activeSpeakerToast = null,
                        liveFaceExclusionRects = emptyList(),
                        bufferedWindowRanges = if (cachedHorizonMs > 0L) {
                            listOf(TimeWindowRange(0L, cachedHorizonMs))
                        } else {
                            emptyList()
                        },
                        scoutCompletedWindows = 0,
                        scoutTotalWindows = 0,
                        scoutFailedSliceCount = 0,
                        placedBalloons = layoutEngine.computeLayout(ytProject, 0L, it.styleSettings, currentLiveFaces),
                        statusBanner = when {
                            cues.isNotEmpty() -> "Attached ${events.size} subtitles — DialogueLens balloons active"
                            restoredFromCache -> "Restored ${events.size} cached bubbles ($0 credits)"
                            initialTier == ScoutExecutionTier.FREE_LOCAL -> "Stream ready · Searching free YouTube CC..."
                            else -> "Stream ready · ${initialTier.displayLabel} pre-buffering opening 35s..."
                        },
                    )
                }
            projectStore.saveProject(ytProject)
            refreshSavedProjects()

            if (cues.isNotEmpty()) {
                val mode = _uiState.value.quotaSaverMode
                if (mode == QuotaSaverMode.PRO_ACCURACY) {
                    runBackgroundCastDiscovery(ytProject.id)
                }
                if (!sourceLanguage.equals(targetLanguage, ignoreCase = true) &&
                    !sourceLanguage.equals("Auto-Detect", ignoreCase = true)
                ) {
                    translateCurrentProjectWithAi(silentBackground = true)
                } else if (mode == QuotaSaverMode.PRO_ACCURACY) {
                    analyzeSpeakersWithContextAndAudio(silentBackground = true)
                }
            } else if (restoredFromCache) {
                // If partial AI transcript was cached, keep playhead-gated tail ready only from cached horizon onward
                val fullyCached = LookaheadWindowPlanner.hasLoadedSubtitlesLocked(resolvedSubSource, events) ||
                    cachedHorizonMs >= resolvedDurationMs - 15_000L
                if (!fullyCached) {
                    runYouTubeScoutTranscriptPipeline(
                        projectId = ytProject.id,
                        videoId = youtubeVideoId,
                        skipTierOneCaptions = true,
                        resumeFromHorizonSec = (cachedHorizonMs / 1000L).toInt().coerceAtLeast(0),
                    )
                }
            } else {
                runYouTubeScoutTranscriptPipeline(
                    projectId = ytProject.id,
                    videoId = youtubeVideoId,
                    skipTierOneCaptions = LookaheadWindowPlanner.shouldSkipUpfrontFreeCaptionSearch(
                        quotaSaverMode = _uiState.value.quotaSaverMode,
                        quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    ),
                )
            }
        }
    }

    /**
     * Best-Model-First YouTube Scout transcript pipeline:
     * 1. When cloud credits are available, jumps straight into Gemini 3.8 Flash (with fast parallel
     *    metadata peek) so playback captions never stall waiting 10s on Free CC endpoints.
     * 2. If Gemini 3.8 Flash credits run dry, automatically cascades to Gemini 3.5 Flash-Lite.
     * 3. Only if cloud credits are completely exhausted (or Free CC Only is active) does it switch
     *    to the free caption search tier (Innertube CC + WebView /api/timedtext + offline ML Kit).
     */
    private fun runYouTubeScoutTranscriptPipeline(
        projectId: String,
        videoId: String,
        skipTierOneCaptions: Boolean = false,
        resumeFromHorizonSec: Int = 0,
    ) {
        youtubeTranscriptJob?.cancel()
        youtubeTranscriptJob = viewModelScope.launch {
            val initialState = _uiState.value
            val initialProj = initialState.project
            if (initialProj.id != projectId) return@launch

            val runFreeSearchFirst = !skipTierOneCaptions ||
                !LookaheadWindowPlanner.shouldSkipUpfrontFreeCaptionSearch(
                    quotaSaverMode = initialState.quotaSaverMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                )

            if (runFreeSearchFirst) {
                val appliedFree = executeFreeYouTubeCaptionFallback(
                    projectId = projectId,
                    videoId = videoId,
                    triggeredByCreditExhaustion = quotaCircuitBreakerTripped,
                )
                if (appliedFree) return@launch

                val postFreeState = _uiState.value
                val postFreeProj = postFreeState.project
                if (postFreeProj.id != projectId ||
                    LookaheadWindowPlanner.hasLoadedSubtitlesLocked(postFreeProj.subtitleSource, postFreeProj.dialogueEvents)
                ) {
                    return@launch
                }
                if (postFreeState.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped) {
                    val hasAny = LookaheadWindowPlanner.hasRealSubtitles(postFreeProj.dialogueEvents)
                    val msg = if (quotaCircuitBreakerTripped) {
                        "Credits depleted & no free YouTube CC exposed — attach SRT or inspect player CC"
                    } else {
                        "Free CC Only active & no YouTube CC exposed — tap Best Model or Attach SRT"
                    }
                    _uiState.update {
                        it.copy(
                            isTranslatingAi = false,
                            scoutStatusText = msg,
                            scoutCaptionState = if (hasAny) {
                                ScoutCaptionState.Ready("Local / Cached", postFreeProj.dialogueEvents.size, postFreeProj.speakers.size)
                            } else {
                                ScoutCaptionState.Error(
                                    message = msg,
                                    canRetryAi = false,
                                    isQuotaExhausted = quotaCircuitBreakerTripped,
                                )
                            },
                        )
                    }
                    return@launch
                }
            } else {
                // Non-blocking background metadata enrichment so Window 1 fires at 0ms delay
                viewModelScope.launch {
                    val fastMeta = YouTubeCaptionFetcher.fetchFastMetadataOnly(videoId)
                    val cur = _uiState.value.project
                    if (cur.id != projectId) return@launch
                    val updatedTitle = fastMeta.title?.takeIf { it.isNotBlank() } ?: cur.title
                    val updatedChannel = fastMeta.channelName?.takeIf { it.isNotBlank() } ?: cur.videoSource.channelName
                    val updatedDesc = fastMeta.description?.takeIf { it.isNotBlank() } ?: cur.videoSource.description
                    val updatedDur = maxOf(cur.videoSource.durationMs, fastMeta.durationMs ?: 0L).coerceAtLeast(60_000L)
                    if (updatedTitle != cur.title ||
                        updatedChannel != cur.videoSource.channelName ||
                        updatedDesc != cur.videoSource.description ||
                        updatedDur != cur.videoSource.durationMs
                    ) {
                        updateProject(
                            cur.copy(
                                title = updatedTitle,
                                videoSource = cur.videoSource.copy(
                                    durationMs = updatedDur,
                                    channelName = updatedChannel,
                                    description = updatedDesc,
                                ),
                                shots = listOf(Shot("shot_1", 0L, updatedDur, cutType = "EMBEDDED_YOUTUBE")),
                            )
                        )
                    }
                }
            }

            // Tier 1 (Default): Opening 35s Smart Pre-Roll Window + Aggressive Dual-Worker Whole-Video Lookahead
            val preTier2State = _uiState.value
            val preTier2Proj = preTier2State.project
            if (preTier2Proj.id != projectId ||
                LookaheadWindowPlanner.hasLoadedSubtitlesLocked(preTier2Proj.subtitleSource, preTier2Proj.dialogueEvents)
            ) {
                return@launch
            }

            val completedWindowStarts = mutableSetOf<Int>()
            val inFlightWindowStarts = mutableSetOf<Int>()
            val failedSliceQueue = mutableListOf<Pair<Int, Int>>()
            val inFlightRecoverySlices = mutableSetOf<Pair<Int, Int>>()
            val retriedFailedSlices = mutableSetOf<Pair<Int, Int>>()
            var failedMicroSliceCount = 0
            var totalCuesGenerated = preTier2Proj.dialogueEvents.count {
                !it.sourceText.startsWith("Embedded YouTube Player active") &&
                    !it.sourceText.startsWith("Web Cinema ready")
            }
            var castDiscoveryTriggered = false

            if (resumeFromHorizonSec > 0) {
                val initDurSec = (preTier2Proj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
                LookaheadWindowPlanner.buildSequentialPreGenWindows(initDurSec).forEach { (ws, we) ->
                    if (we <= resumeFromHorizonSec) {
                        completedWindowStarts.add(ws)
                        this@DialogueLensViewModel.scoutCompletedWindows.add(ws * 1000L)
                    }
                }
            }

            suspend fun applyWindowOutcome(
                startSec: Int,
                endSec: Int,
                outcome: SegmentExecutionOutcome.Completed,
                isRecoveryPass: Boolean = false,
            ) {
                val resolvedTier = outcome.resolvedTier
                if (outcome.downgradedToLite) {
                    downgradedToLiteForStream = true
                }
                lookaheadMutex.withLock {
                    if (!isRecoveryPass) {
                        inFlightWindowStarts.remove(startSec)
                        completedWindowStarts.add(startSec)
                        this@DialogueLensViewModel.scoutCompletedWindows.add(startSec * 1000L)
                        failedSliceQueue.addAll(outcome.failedRanges)
                        failedMicroSliceCount = failedSliceQueue.distinct().size
                    } else {
                        val key = startSec to endSec
                        inFlightRecoverySlices.remove(key)
                        failedSliceQueue.removeAll { it == key || (it.first >= startSec && it.second <= endSec) }
                        failedSliceQueue.addAll(outcome.failedRanges)
                        failedMicroSliceCount = failedSliceQueue.distinct().size
                    }

                    val liveProj = _uiState.value.project
                    if (liveProj.id != projectId ||
                        LookaheadWindowPlanner.hasLoadedSubtitlesLocked(liveProj.subtitleSource, liveProj.dialogueEvents)
                    ) {
                        return
                    }

                    var workingProj = liveProj
                    val beforeSpeakers = workingProj.speakers

                    // Reconcile this window's local A..L speaker slots against the live cast roster BEFORE
                    // merging assignments or storing cue turn hints so parallel workers never overwrite or mix slots.
                    val (reconciledCues, reconciledSpeakerUpdates, _) = reconcileWindowOutcomeSpeakersAndCues(
                        existingSpeakers = workingProj.speakers,
                        cues = outcome.cues,
                        speakerUpdates = outcome.speakerUpdates,
                    )

                    if (reconciledSpeakerUpdates.isNotEmpty() || outcome.summary.isNotBlank()) {
                        val (castMerged, updatedMem) = mergeCastSlotAssignments(
                            project = workingProj,
                            assignments = reconciledSpeakerUpdates,
                            rememberedColors = _uiState.value.characterColorMemory,
                            reconcileColors = attributionEngine::reconcileCharacterColors,
                            summary = outcome.summary,
                            discoveryTimestampMs = startSec * 1000L,
                        )
                        if (updatedMem != _uiState.value.characterColorMemory) {
                            persistCharacterColorMemory(updatedMem)
                        }
                        workingProj = castMerged
                    }

                    if (reconciledCues.isNotEmpty()) {
                        val windowEvents = mutableListOf<DialogueEvent>()
                        val hintMap = mutableMapOf<String, AiTranslatedLine>()
                        val validSlots = setOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")

                        reconciledCues.forEach { cue ->
                            val eventId = SubtitleParser.newEventId()
                            val isNonSpeaker = cue.dialogueType == DialogueType.SOUND_DESCRIPTION ||
                                cue.dialogueType == DialogueType.NARRATION
                            val isOffScreenOrVoiceOver = cue.dialogueType != DialogueType.SPOKEN
                            val spatialAnchor = if (!isOffScreenOrVoiceOver && cue.anchorX != null && cue.anchorY != null) {
                                NormalizedPoint(cue.anchorX, cue.anchorY)
                            } else {
                                null
                            }
                            val slotUpper = cue.suggestedSpeakerSlot.trim().uppercase()
                            windowEvents.add(
                                DialogueEvent(
                                    id = eventId,
                                    startTimeMs = cue.startMs,
                                    endTimeMs = cue.endMs,
                                    sourceText = cue.sourceText,
                                    translatedText = cue.naturalTranslation,
                                    literalText = cue.literalTranslation,
                                    romanization = cue.romanization,
                                    speakerId = null,
                                    speakerConfidence = if (slotUpper in validSlots) 0.84f else 0.42f,
                                    faceTrackId = null,
                                    faceConfidence = if (spatialAnchor != null) 0.78f else 0f,
                                    shotId = "shot_1",
                                    dialogueType = cue.dialogueType,
                                    anchorPosition = spatialAnchor,
                                    placementMode = when {
                                        isNonSpeaker || isOffScreenOrVoiceOver -> PlacementMode.EDGE_DOCKED
                                        spatialAnchor != null -> PlacementMode.AUTO
                                        else -> PlacementMode.NEUTRAL_FALLBACK
                                    },
                                    contextCueReason = cue.contextReason,
                                    culturalNote = cue.culturalNote,
                                    preferredDeadSpaceZone = cue.deadSpaceZone,
                                    avoidBoxes = cue.avoidBoxes,
                                )
                            )
                            hintMap[eventId] = AiTranslatedLine(
                                id = eventId,
                                naturalTranslation = cue.naturalTranslation,
                                literalTranslation = cue.literalTranslation,
                                romanization = cue.romanization,
                                dialogueTypeHint = cue.dialogueType,
                                speakerTurnHint = "UNCERTAIN",
                                suggestedSpeakerSlot = slotUpper.ifBlank { "UNCERTAIN" },
                                contextReason = cue.contextReason,
                                culturalNote = cue.culturalNote,
                            )
                        }

                        val wStartMs = startSec * 1000L
                        val wEndMs = (endSec * 1000L).coerceAtLeast(wStartMs + 1_000L)
                        val cleanExisting = workingProj.dialogueEvents.filterNot { evt ->
                            evt.sourceText.startsWith("Embedded YouTube Player active") ||
                                evt.sourceText.startsWith("Web Cinema ready")
                        }
                        val pacedWindow = SubtitleParser.applyReadingPacingGuardrails(windowEvents)
                            .filter { it.startTimeMs in wStartMs until wEndMs }
                            .map { evt ->
                                val clampedEnd = evt.endTimeMs.coerceIn(evt.startTimeMs + 350L, wEndMs)
                                evt.copy(endTimeMs = clampedEnd)
                            }
                        val antiEchoedWindow = LookaheadWindowPlanner.filterEchoedOpeningCues(
                            existing = cleanExisting,
                            incoming = pacedWindow,
                            windowStartMs = wStartMs,
                        )
                        val mergedAllEvents = LookaheadWindowPlanner.mergeWindowDialogueEvents(
                            existing = cleanExisting,
                            incoming = antiEchoedWindow,
                            windowStartMs = wStartMs,
                            windowEndMs = wEndMs,
                        )
                        cachedAiTurnHints.putAll(hintMap)
                        antiEchoedWindow.forEach { autoAnalyzedEventIds.add(it.id) }

                        val (attributedSpeakers, attributedEvents) = attributionEngine.assignSpeakers(
                            events = mergedAllEvents,
                            shots = workingProj.shots,
                            faceTracks = emptyList(),
                            existingSpeakers = workingProj.speakers,
                            turnHints = cachedAiTurnHints,
                            rememberedCharacterColors = _uiState.value.characterColorMemory,
                        )

                        val enrichedSpeakers = attributedSpeakers.map { spk ->
                            val spkEvents = attributedEvents.filter { it.speakerId == spk.id }
                            val earliest = spkEvents.minOfOrNull { it.startTimeMs }
                            val avgX = spkEvents
                                .filter { it.dialogueType == DialogueType.SPOKEN }
                                .mapNotNull { it.anchorPosition?.x }
                                .takeIf { it.isNotEmpty() }
                                ?.average()
                                ?.toFloat()
                            spk.copy(
                                firstAppearedMs = spk.firstAppearedMs ?: earliest,
                                preferredScreenSideX = avgX ?: spk.preferredScreenSideX,
                            )
                        }

                        val cutawayRoutedEvents = applyCutawayOffScreenSideMemory(
                            events = attributedEvents,
                            speakers = enrichedSpeakers,
                        )

                        val maxCueEndMs = cutawayRoutedEvents.maxOfOrNull { it.endTimeMs } ?: 0L
                        val expandedDurationMs = maxOf(workingProj.videoSource.durationMs, maxCueEndMs, endSec * 1000L)
                        workingProj = workingProj.copy(
                            subtitleSource = SubtitleSourceKind.AI_DRAFT,
                            videoSource = workingProj.videoSource.copy(durationMs = expandedDurationMs),
                            shots = listOf(Shot("shot_1", 0L, expandedDurationMs, cutType = "EMBEDDED_YOUTUBE")),
                            speakers = enrichedSpeakers,
                            dialogueEvents = cutawayRoutedEvents,
                        )
                    }

                    totalCuesGenerated = workingProj.dialogueEvents.count {
                        !it.sourceText.startsWith("Embedded YouTube Player active") &&
                            !it.sourceText.startsWith("Web Cinema ready")
                    }
                    val latestDurSec = (workingProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
                    val latestWindows = LookaheadWindowPlanner.buildSequentialPreGenWindows(latestDurSec)
                    val rawWindowRanges = latestWindows
                        .filter { it.first in completedWindowStarts }
                        .map { TimeWindowRange(it.first * 1000L, it.second * 1000L) }
                    val failedRangesMs = failedSliceQueue.distinct().map {
                        TimeWindowRange(it.first * 1000L, it.second * 1000L)
                    }
                    val bufferedRanges = LookaheadWindowPlanner.subtractFailedSlices(
                        completedRanges = rawWindowRanges,
                        failedSlices = failedRangesMs,
                    )
                    val nextHorizonMs = maxOf(_uiState.value.scoutedUpToMs, endSec * 1000L)
                    val completedCount = completedWindowStarts.size
                    val totalWinCount = latestWindows.size

                    updateProject(
                        workingProj,
                        banner = if (completedCount == 1) {
                            "${resolvedTier.displayLabel} heard ${workingProj.dialogueEvents.size} lines · ${workingProj.speakers.size} speakers"
                        } else {
                            null
                        },
                    )
                    persistYouTubeVideoCache(workingProj, nextHorizonMs)
                    emitSpeakerGlossaryToasts(beforeSpeakers, workingProj.speakers)

                    val diagNote = buildString {
                        append(com.example.data.ai.AppCheckDiagnostics.summaryLine())
                        append(" · $completedCount/$totalWinCount chunks")
                        if (outcome.usedBisection) append(" · Split-recovered")
                        if (failedMicroSliceCount > 0) append(" · $failedMicroSliceCount brief gap")
                    }
                    val resumeFromCatchUp = _uiState.value.isScoutCatchUpBuffering && !LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                        playheadMs = _uiState.value.currentTimeMs,
                        totalDurationMs = workingProj.videoSource.durationMs,
                        bufferedRanges = bufferedRanges,
                        events = workingProj.dialogueEvents,
                        isScoutActive = true,
                    )
                    val resumeSeq = if (resumeFromCatchUp) ++nextStreamCommandSeq else null

                    _uiState.update {
                        it.copy(
                            isOpeningSpeakersReady = it.isOpeningSpeakersReady || totalCuesGenerated > 0,
                            scoutedUpToMs = nextHorizonMs,
                            bufferedWindowRanges = bufferedRanges,
                            scoutCompletedWindows = completedCount,
                            scoutTotalWindows = totalWinCount,
                            scoutFailedSliceCount = failedMicroSliceCount,
                            scoutStatusText = "Scout $completedCount/$totalWinCount · ${workingProj.dialogueEvents.size} bubbles · ${workingProj.speakers.size} speakers",
                            scoutDiagnosticDetail = diagNote,
                            isScoutCatchUpBuffering = if (resumeFromCatchUp) false else it.isScoutCatchUpBuffering,
                            isPlaying = if (resumeFromCatchUp) true else it.isPlaying,
                            streamPlayRequest = resumeSeq?.let { seq -> seq to true } ?: it.streamPlayRequest,
                        )
                    }

                    if (!castDiscoveryTriggered &&
                        totalCuesGenerated > 0 &&
                        _uiState.value.quotaSaverMode == QuotaSaverMode.PRO_ACCURACY
                    ) {
                        castDiscoveryTriggered = true
                        runBackgroundCastDiscovery(projectId)
                    }
                }
            }

            // Warm up App Check token on cold start so reCAPTCHA Enterprise / Play Integrity finishes before Window 0
            com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = false, timeoutMs = 4_000L)

            val quotaTriggered = AtomicBoolean(false)
            var lastQuotaError: Throwable? = null

            // Step A: Opening 35s window runs immediately so Smart Pre-Roll settles fast with crisp context & cast
            val openingState = _uiState.value
            val openingProj = openingState.project
            val openingDurSec = (openingProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
            val openingWindows = LookaheadWindowPlanner.buildSequentialPreGenWindows(openingDurSec)
            _uiState.update { it.copy(scoutTotalWindows = openingWindows.size) }

            if (0 !in completedWindowStarts && openingProj.id == projectId) {
                val (openStart, openEnd) = openingWindows.first()
                val windowLabel = "${SubtitleParser.formatCompactTime(openStart * 1000L)}–${SubtitleParser.formatCompactTime(openEnd * 1000L)}"
                val initialTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = openingState.quotaSaverMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                val openMsg = "${initialTier.displayLabel} opening ($windowLabel)..."
                _uiState.update {
                    it.copy(
                        isTranslatingAi = true,
                        scoutStatusText = openMsg,
                        scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · ${initialTier.displayLabel} $windowLabel",
                        scoutCaptionState = ScoutCaptionState.AiTranscribing(openMsg, completedWindows = 0, cuesSoFar = totalCuesGenerated),
                    )
                }
                val mergedGlossary = (openingProj.glossary + openingState.glossaryEntries)
                    .distinctBy { it.term.trim().lowercase() }
                inFlightWindowStarts.add(openStart)
                val openOutcome = segmentExecutor.executeSegment(
                    startSec = openStart,
                    endSec = openEnd,
                    initialTier = initialTier,
                    onStatusUpdate = { statusNote ->
                        _uiState.update {
                            it.copy(
                                scoutStatusText = statusNote,
                                scoutCaptionState = ScoutCaptionState.AiTranscribing(
                                    message = statusNote,
                                    completedWindows = completedWindowStarts.size,
                                    cuesSoFar = totalCuesGenerated,
                                ),
                            )
                        }
                    },
                    onDowngradedToLite = { downgradedToLiteForStream = true },
                ) { sliceStart, sliceEnd, preferLite ->
                    val snapProj = _uiState.value.project
                    translationAiService.transcribeYouTubeVideoWindow(
                        videoId = videoId,
                        windowStartSec = sliceStart,
                        windowEndSec = sliceEnd,
                        totalDurationSec = (snapProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60),
                        sourceLanguage = snapProj.sourceLanguage,
                        targetLanguage = snapProj.targetLanguage,
                        videoContext = buildVideoContextString(snapProj),
                        knownSpeakers = snapProj.speakers,
                        glossary = mergedGlossary,
                        preferLiteModel = preferLite,
                    )
                }

                when (openOutcome) {
                    is SegmentExecutionOutcome.Completed -> applyWindowOutcome(openStart, openEnd, openOutcome)
                    is SegmentExecutionOutcome.QuotaExhausted -> {
                        inFlightWindowStarts.remove(openStart)
                        lastQuotaError = openOutcome.error
                        quotaCircuitBreakerTripped = true
                        quotaTriggered.set(true)
                    }
                }
            }

            // Step B: Aggressive Duration-Scaled Multi-Worker Lookahead across the entire video (2..4 parallel workers)
            // Secondary workers stagger briefly so opening speakers stabilize and nearby failed slices heal inline.
            if (!quotaTriggered.get() && !quotaCircuitBreakerTripped) {
                val scaledWorkers = LookaheadWindowPlanner.recommendedWorkerCount(_uiState.value.project.videoSource.durationMs)
                coroutineScope {
                    List(scaledWorkers) { workerIdx ->
                        launch {
                            if (workerIdx > 0) {
                                delay(workerIdx * 1_100L)
                            }
                            while (isActive && !quotaTriggered.get() && !quotaCircuitBreakerTripped) {
                                var nextWindow: Pair<Int, Int>? = null
                                var isRecoveryPick = false
                                val workerProj: DialogueLensProject
                                val workerState: DialogueLensUiState
                                lookaheadMutex.withLock {
                                    workerState = _uiState.value
                                    workerProj = workerState.project
                                    if (workerProj.id != projectId ||
                                        LookaheadWindowPlanner.hasLoadedSubtitlesLocked(workerProj.subtitleSource, workerProj.dialogueEvents) ||
                                        workerState.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD
                                    ) {
                                        return@launch
                                    }
                                    val durSec = (workerProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
                                    val allWins = LookaheadWindowPlanner.buildSequentialPreGenWindows(durSec)
                                    if (resumeFromHorizonSec > 0) {
                                        allWins.forEach { (ws, we) ->
                                            if (we <= resumeFromHorizonSec) completedWindowStarts.add(ws)
                                        }
                                    }
                                    val playheadSec = (workerState.currentTimeMs / 1000L).toInt().coerceIn(0, durSec)

                                    // First heal any nearby unretried failed micro-slice so upcoming gaps vanish before playhead arrives
                                    val nearbyGap = failedSliceQueue
                                        .distinct()
                                        .filter { it !in inFlightRecoverySlices && it !in retriedFailedSlices }
                                        .firstOrNull { (fs, fe) -> fe >= (playheadSec - 15) && fs <= (playheadSec + 180) }

                                    if (nearbyGap != null) {
                                        nextWindow = nearbyGap
                                        isRecoveryPick = true
                                        inFlightRecoverySlices.add(nearbyGap)
                                        retriedFailedSlices.add(nearbyGap)
                                    } else {
                                        val pendingWin = LookaheadWindowPlanner.buildLookaheadPriorityOrder(
                                            windows = allWins,
                                            completedStarts = completedWindowStarts,
                                            inFlightStarts = inFlightWindowStarts,
                                            playheadSec = playheadSec,
                                        ).firstOrNull()
                                        if (pendingWin != null) {
                                            nextWindow = pendingWin
                                            inFlightWindowStarts.add(pendingWin.first)
                                            if (allWins.size != workerState.scoutTotalWindows) {
                                                _uiState.update { it.copy(scoutTotalWindows = allWins.size) }
                                            }
                                        } else {
                                            val trailingGap = failedSliceQueue
                                                .distinct()
                                                .firstOrNull { it !in inFlightRecoverySlices && it !in retriedFailedSlices }
                                            if (trailingGap != null) {
                                                nextWindow = trailingGap
                                                isRecoveryPick = true
                                                inFlightRecoverySlices.add(trailingGap)
                                                retriedFailedSlices.add(trailingGap)
                                            }
                                        }
                                    }
                                }

                                val chosen = nextWindow ?: break
                                val (winStartSec, winEndSec) = chosen
                                val winLabel = "${SubtitleParser.formatCompactTime(winStartSec * 1000L)}–${SubtitleParser.formatCompactTime(winEndSec * 1000L)}"
                                val tier = LookaheadWindowPlanner.initialExecutionTier(
                                    quotaSaverMode = workerState.quotaSaverMode,
                                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                                    downgradedToLite = downgradedToLiteForStream,
                                )
                                val mergedGlossary = (workerProj.glossary + workerState.glossaryEntries)
                                    .distinctBy { it.term.trim().lowercase() }

                                if (workerIdx == 0) {
                                    val progressMsg = if (isRecoveryPick) {
                                        "Healing gap ($winLabel) · $totalCuesGenerated bubbles..."
                                    } else {
                                        "${tier.displayLabel} lookahead ($winLabel) · $totalCuesGenerated bubbles..."
                                    }
                                    _uiState.update {
                                        it.copy(
                                            isTranslatingAi = true,
                                            scoutStatusText = progressMsg,
                                            scoutCaptionState = ScoutCaptionState.AiTranscribing(
                                                message = progressMsg,
                                                completedWindows = completedWindowStarts.size,
                                                cuesSoFar = totalCuesGenerated,
                                            ),
                                        )
                                    }
                                }

                                val outcome = segmentExecutor.executeSegment(
                                    startSec = winStartSec,
                                    endSec = winEndSec,
                                    initialTier = tier,
                                    onStatusUpdate = { statusNote ->
                                        if (workerIdx == 0) {
                                            _uiState.update {
                                                it.copy(
                                                    scoutStatusText = statusNote,
                                                    scoutCaptionState = ScoutCaptionState.AiTranscribing(
                                                        message = statusNote,
                                                        completedWindows = completedWindowStarts.size,
                                                        cuesSoFar = totalCuesGenerated,
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                    onDowngradedToLite = { downgradedToLiteForStream = true },
                                ) { sliceStart, sliceEnd, preferLite ->
                                    val snapProj = _uiState.value.project
                                    val snapDurSec = (snapProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
                                    val priorNorms = snapProj.dialogueEvents
                                        .filter { it.startTimeMs < sliceStart * 1000L || it.startTimeMs >= sliceEnd * 1000L }
                                        .mapNotNull { evt ->
                                            evt.sourceText.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "").takeIf { it.length >= 10 }
                                        }
                                        .toSet()
                                    translationAiService.transcribeYouTubeVideoWindow(
                                        videoId = videoId,
                                        windowStartSec = sliceStart,
                                        windowEndSec = sliceEnd,
                                        totalDurationSec = snapDurSec,
                                        sourceLanguage = snapProj.sourceLanguage,
                                        targetLanguage = snapProj.targetLanguage,
                                        videoContext = buildVideoContextString(snapProj),
                                        knownSpeakers = snapProj.speakers,
                                        glossary = mergedGlossary,
                                        preferLiteModel = preferLite,
                                        priorWindowNormalizedTexts = priorNorms,
                                    )
                                }

                                when (outcome) {
                                    is SegmentExecutionOutcome.Completed -> {
                                        applyWindowOutcome(winStartSec, winEndSec, outcome, isRecoveryPass = isRecoveryPick)
                                    }
                                    is SegmentExecutionOutcome.QuotaExhausted -> {
                                        lookaheadMutex.withLock {
                                            if (isRecoveryPick) {
                                                inFlightRecoverySlices.remove(chosen)
                                            } else {
                                                inFlightWindowStarts.remove(winStartSec)
                                            }
                                        }
                                        if (quotaTriggered.compareAndSet(false, true)) {
                                            lastQuotaError = outcome.error
                                            quotaCircuitBreakerTripped = true
                                        }
                                        break
                                    }
                                }
                                delay(120L)
                            }
                        }
                    }.joinAll()
                }
            }

            // Step C: Brief Gap-Recovery pass for any transient micro-slice gaps collected during Step B
            val gapsToRecover = lookaheadMutex.withLock { failedSliceQueue.distinct().toList() }
            if (gapsToRecover.isNotEmpty() && !quotaTriggered.get() && !quotaCircuitBreakerTripped) {
                for ((gapStart, gapEnd) in gapsToRecover) {
                    if (!isActive || quotaTriggered.get() || quotaCircuitBreakerTripped) break
                    val gapState = _uiState.value
                    val gapProj = gapState.project
                    if (gapProj.id != projectId || gapState.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD) break
                    val gapTier = LookaheadWindowPlanner.initialExecutionTier(
                        quotaSaverMode = gapState.quotaSaverMode,
                        quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                        downgradedToLite = downgradedToLiteForStream,
                    )
                    val mergedGlossary = (gapProj.glossary + gapState.glossaryEntries)
                        .distinctBy { it.term.trim().lowercase() }
                    val recoveryOutcome = segmentExecutor.executeSegment(
                        startSec = gapStart,
                        endSec = gapEnd,
                        initialTier = gapTier,
                        onStatusUpdate = { note ->
                            _uiState.update { it.copy(scoutStatusText = "Healing brief gap (${gapStart}s–${gapEnd}s): $note") }
                        },
                        onDowngradedToLite = { downgradedToLiteForStream = true },
                    ) { sStart, sEnd, preferLite ->
                        val snapProj = _uiState.value.project
                        val snapDurSec = (snapProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
                        val priorNorms = snapProj.dialogueEvents
                            .filter { it.startTimeMs < sStart * 1000L || it.startTimeMs >= sEnd * 1000L }
                            .mapNotNull { evt ->
                                evt.sourceText.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "").takeIf { it.length >= 10 }
                            }
                            .toSet()
                        translationAiService.transcribeYouTubeVideoWindow(
                            videoId = videoId,
                            windowStartSec = sStart,
                            windowEndSec = sEnd,
                            totalDurationSec = snapDurSec,
                            sourceLanguage = snapProj.sourceLanguage,
                            targetLanguage = snapProj.targetLanguage,
                            videoContext = buildVideoContextString(snapProj),
                            knownSpeakers = snapProj.speakers,
                            glossary = mergedGlossary,
                            preferLiteModel = preferLite,
                            priorWindowNormalizedTexts = priorNorms,
                        )
                    }
                    when (recoveryOutcome) {
                        is SegmentExecutionOutcome.Completed -> {
                            applyWindowOutcome(gapStart, gapEnd, recoveryOutcome, isRecoveryPass = true)
                        }
                        is SegmentExecutionOutcome.QuotaExhausted -> {
                            lastQuotaError = recoveryOutcome.error
                            quotaCircuitBreakerTripped = true
                            quotaTriggered.set(true)
                            break
                        }
                    }
                }
            }

            val postLoopProj = _uiState.value.project
            if (postLoopProj.id != projectId) return@launch

            if (quotaTriggered.get() || quotaCircuitBreakerTripped) {
                val freeSuccess = executeFreeYouTubeCaptionFallback(
                    projectId = projectId,
                    videoId = videoId,
                    triggeredByCreditExhaustion = true,
                )
                if (freeSuccess) return@launch

                val latestAfterFallback = _uiState.value.project
                val hasPartial = LookaheadWindowPlanner.hasRealSubtitles(latestAfterFallback.dialogueEvents)
                val rawErr = lastQuotaError?.localizedMessage?.take(140).orEmpty()
                val msg = if (hasPartial) {
                    "Credits depleted (${latestAfterFallback.dialogueEvents.size} bubbles saved) · Attach SRT or enable CC"
                } else {
                    "Credits depleted & no free YouTube CC exposed — attach SRT or enable player CC"
                }
                _uiState.update {
                    it.copy(
                        isTranslatingAi = false,
                        scoutStatusText = msg,
                        scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · Quota Exhausted $rawErr",
                        scoutCaptionState = ScoutCaptionState.Error(
                            message = msg,
                            canRetryAi = false,
                            isPausedMidVideo = hasPartial,
                            isQuotaExhausted = true,
                        ),
                        statusBanner = msg,
                    )
                }
                return@launch
            }

            if (postLoopProj.dialogueEvents.isNotEmpty()) {
                val finalTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = _uiState.value.quotaSaverMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                val finalDurSec = (postLoopProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
                val finalWins = LookaheadWindowPlanner.buildSequentialPreGenWindows(finalDurSec)
                val rawFinalRanges = finalWins
                    .filter { it.first in completedWindowStarts }
                    .map { TimeWindowRange(it.first * 1000L, it.second * 1000L) }
                val finalFailedRangesMs = failedSliceQueue.distinct().map {
                    TimeWindowRange(it.first * 1000L, it.second * 1000L)
                }
                val finalRanges = LookaheadWindowPlanner.subtractFailedSlices(
                    completedRanges = rawFinalRanges,
                    failedSlices = finalFailedRangesMs,
                )
                val gapSummary = if (failedMicroSliceCount > 0) " (${failedMicroSliceCount} brief gap skipped)" else ""
                val readySummary = "Full Scout ready · ${postLoopProj.dialogueEvents.size} bubbles · ${postLoopProj.speakers.size} speakers$gapSummary"
                persistYouTubeVideoCache(postLoopProj, maxOf(_uiState.value.scoutedUpToMs, postLoopProj.videoSource.durationMs))
                _uiState.update {
                    it.copy(
                        isTranslatingAi = false,
                        isOpeningSpeakersReady = true,
                        bufferedWindowRanges = finalRanges,
                        scoutCompletedWindows = completedWindowStarts.size,
                        scoutTotalWindows = finalWins.size,
                        scoutFailedSliceCount = failedMicroSliceCount,
                        scoutStatusText = readySummary,
                        scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · Full ${completedWindowStarts.size}/${finalWins.size} chunks · ${postLoopProj.dialogueEvents.size} bubbles",
                        scoutCaptionState = ScoutCaptionState.Ready(
                            sourceLabel = "${finalTier.displayLabel} · Full",
                            cueCount = postLoopProj.dialogueEvents.size,
                            speakerCount = postLoopProj.speakers.size,
                        ),
                    )
                }
            } else {
                val emptyMsg = "No spoken dialogue detected — tap Retry AI or attach SRT"
                _uiState.update {
                    it.copy(
                        isTranslatingAi = false,
                        scoutStatusText = emptyMsg,
                        scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · 0 dialogue cues returned",
                        scoutCaptionState = ScoutCaptionState.Error(message = emptyMsg, canRetryAi = true),
                        statusBanner = emptyMsg,
                    )
                }
            }
        }
    }

    private suspend fun executeFreeYouTubeCaptionFallback(
        projectId: String,
        videoId: String,
        triggeredByCreditExhaustion: Boolean,
    ): Boolean {
        val statusMsg = if (triggeredByCreditExhaustion) {
            "Credits depleted — switching to free YouTube CC ($0)..."
        } else {
            "Searching free YouTube CC ($0)..."
        }
        _uiState.update {
            it.copy(
                isTranslatingAi = false,
                scoutStatusText = statusMsg,
                scoutCaptionState = ScoutCaptionState.FetchingCaptions(statusMsg),
                statusBanner = if (triggeredByCreditExhaustion) statusMsg else it.statusBanner,
            )
        }

        // First check if a passive WebView /api/timedtext payload was already captured during playback
        val stashedWeb = pendingWebTimedTextFallback
        if (stashedWeb != null) {
            val stashedCues = YouTubeCaptionFetcher.fetchTimedTextFromObservedUrl(
                observedUrl = stashedWeb.observedUrl,
                cookies = stashedWeb.cookies,
                observedBody = stashedWeb.observedBody,
                userAgent = stashedWeb.userAgent,
            )
            val afterStashed = _uiState.value.project
            if (stashedCues.isNotEmpty() && afterStashed.id == projectId) {
                pendingWebTimedTextFallback = null
                val langCode = Regex("[?&]lang=([^&]+)").find(stashedWeb.observedUrl)?.groupValues?.getOrNull(1)
                applyFetchedYouTubeCues(
                    baseProject = afterStashed,
                    cues = stashedCues,
                    fetchedTitle = null,
                    fetchedDurationMs = null,
                    fetchedLanguageCode = langCode,
                )
                return true
            }
        }

        val initialProj = _uiState.value.project
        if (initialProj.id != projectId) return false
        val fetched = YouTubeCaptionFetcher.fetchMetadataAndCaptions(
            videoId = videoId,
            preferredSourceLanguage = initialProj.sourceLanguage,
            preferredTargetLanguage = initialProj.targetLanguage,
        )
        val afterFetchProj = _uiState.value.project
        if (afterFetchProj.id != projectId ||
            LookaheadWindowPlanner.hasLoadedSubtitlesLocked(afterFetchProj.subtitleSource, afterFetchProj.dialogueEvents)
        ) {
            return true
        }
        if (fetched.cues.isNotEmpty()) {
            applyFetchedYouTubeCues(
                baseProject = afterFetchProj,
                cues = fetched.cues,
                fetchedTitle = fetched.title,
                fetchedDurationMs = fetched.durationMs,
                fetchedLanguageCode = fetched.languageCode,
                fetchedChannelName = fetched.channelName,
                fetchedDescription = fetched.description,
            )
            return true
        }

        val updatedDuration = maxOf(afterFetchProj.videoSource.durationMs, fetched.durationMs ?: 0L).coerceAtLeast(60_000L)
        updateProject(
            afterFetchProj.copy(
                title = fetched.title?.takeIf { it.isNotBlank() } ?: afterFetchProj.title,
                videoSource = afterFetchProj.videoSource.copy(
                    durationMs = updatedDuration,
                    channelName = fetched.channelName?.takeIf { it.isNotBlank() } ?: afterFetchProj.videoSource.channelName,
                    description = fetched.description?.takeIf { it.isNotBlank() } ?: afterFetchProj.videoSource.description,
                ),
                shots = listOf(Shot("shot_1", 0L, updatedDuration, cutType = "EMBEDDED_YOUTUBE")),
            )
        )
        return false
    }

    private fun isoToFriendlyLanguage(code: String?, fallback: String): String {
        val base = code?.trim()?.lowercase()?.substringBefore('-') ?: return fallback
        return when (base) {
            "en" -> "English"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "zh" -> "Mandarin"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "hi" -> "Hindi"
            "ar" -> "Arabic"
            else -> fallback
        }
    }

    fun createWebStreamProject(
        webUrl: String,
        subtitleUri: Uri?,
        subtitleFileName: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
        pastedSubtitleText: String = "",
    ) {
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        introJob?.cancel()
        lookaheadJob?.cancel()
        downgradedToLiteForStream = false
        pendingWebTimedTextFallback = null
        scoutHeartbeatService.resetForNewStream(
            if (_uiState.value.quotaSaverMode == QuotaSaverMode.PRO_ACCURACY) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
        )
        scoutHeartbeatService.setZeroCloudPaused(_uiState.value.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD)
        _uiState.update { it.copy(scoutCaptionState = ScoutCaptionState.Idle) }
        lastScoutSeekSentAtMs = 0L
        lastScoutSeekTargetMs = -1L
        scoutCompletedWindows.clear()
        scoutAudioSliceTimestamps.clear()
        autoAnalyzedEventIds.clear()
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        layoutEngine.clearSmoothing()
        playbackClockAnchor.reset(0L, _uiState.value.playbackSpeed, false)
        lastCapturedWebTrackSignature = null

        viewModelScope.launch {
            val rawFileText = if (subtitleUri != null) {
                projectStore.readTextFromUri(subtitleUri).getOrNull().orEmpty()
            } else {
                ""
            }
            val cues: List<RawSubtitleCue> = when {
                rawFileText.isNotBlank() -> SubtitleParser.parse(rawFileText, subtitleFileName)
                pastedSubtitleText.isNotBlank() -> SubtitleParser.parseSmartTranscript(pastedSubtitleText)
                else -> emptyList()
            }

            val hostLabel = runCatching { Uri.parse(webUrl).host?.removePrefix("www.") }.getOrNull()
                ?.takeIf { !it.isNullOrBlank() } ?: "Stream"
            val defaultDurationMs = (cues.maxOfOrNull { it.endTimeMs } ?: 180_000L).coerceAtLeast(60_000L)
            val initialSpeakers = listOf(
                Speaker("spk_a", "Speaker A", accentIndex = 0),
                Speaker("spk_b", "Speaker B", accentIndex = 1),
            )
            val webShots = listOf(Shot("shot_1", 0L, defaultDurationMs, cutType = "WEB_STREAM"))
            val (speakers, events) = if (cues.isNotEmpty()) {
                val rawEvents = SubtitleParser.applyReadingPacingGuardrails(
                    cues.map { cue ->
                        DialogueEvent(
                            id = SubtitleParser.newEventId(),
                            startTimeMs = cue.startTimeMs,
                            endTimeMs = cue.endTimeMs,
                            sourceText = cue.text,
                            translatedText = cue.text,
                            speakerId = null,
                            speakerConfidence = 0.48f,
                            faceTrackId = null,
                            faceConfidence = 0f,
                            shotId = "shot_1",
                            dialogueType = cue.inferredType,
                            placementMode = if (cue.inferredType == DialogueType.SPOKEN) {
                                PlacementMode.NEUTRAL_FALLBACK
                            } else {
                                PlacementMode.EDGE_DOCKED
                            },
                        )
                    }
                )
                attributionEngine.assignSpeakers(
                    events = rawEvents,
                    shots = webShots,
                    faceTracks = emptyList(),
                    existingSpeakers = initialSpeakers,
                    rememberedCharacterColors = _uiState.value.characterColorMemory,
                )
            } else {
                initialSpeakers to emptyList()
            }

            val webProject = DialogueLensProject(
                id = "web_${System.currentTimeMillis()}",
                title = "Web · $hostLabel",
                createdAtEpochMs = System.currentTimeMillis(),
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translationMode = translationMode,
                subtitleSource = if (cues.isNotEmpty()) SubtitleSourceKind.UPLOADED_FILE else SubtitleSourceKind.BUILT_IN_DEMO,
                qualityMode = ProcessingQualityMode.BALANCED,
                videoSource = VideoSource(
                    uriString = webUrl.trim(),
                    displayName = "Web ($hostLabel)",
                    kind = VideoSourceKind.WEB_STREAM,
                    durationMs = defaultDurationMs,
                    width = 1920,
                    height = 1080,
                ),
                shots = listOf(Shot("shot_1", 0L, defaultDurationMs, cutType = "WEB_STREAM")),
                speakers = speakers,
                faceTracks = emptyList(),
                dialogueEvents = events,
                styleSettings = _uiState.value.styleSettings,
                glossary = _uiState.value.glossaryEntries,
            )

            undoStack.clear()
            lastAutoScoutSyncAtMs = 0L
            _uiState.update {
                it.copy(
                    project = webProject,
                    showStartLauncher = false,
                    hasPendingPlayIntro = true,
                    displayMode = CaptionDisplayMode.BALLOONS,
                    isCinemaIntroActive = false,
                    introRemainingMs = 0L,
                    canUndo = false,
                    isPlaying = false,
                    currentTimeMs = 0L,
                    scoutTimeMs = 0L,
                    scoutedUpToMs = 0L,
                    scoutStatusText = "Scout gathering stream captions, audio & cast...",
                    selectedEventId = null,
                    isTranslatingAi = false,
                    streamPlayRequest = null,
                    youtubeSeekRequest = null,
                    analysisStatus = AnalysisStatus.Ready,
                    liveFaceExclusionRects = emptyList(),
                    bufferedWindowRanges = emptyList(),
                    scoutCompletedWindows = 0,
                    scoutTotalWindows = 0,
                    scoutFailedSliceCount = 0,
                    viewerWebNav = WebNavigationState(
                        currentUrl = webUrl.trim(),
                        currentHost = WebNavigationGuard.extractHost(webUrl),
                    ),
                    scoutWebNav = WebNavigationState(
                        currentUrl = webUrl.trim(),
                        currentHost = WebNavigationGuard.extractHost(webUrl),
                    ),
                    viewerWebNavCommand = null,
                    scoutWebNavCommand = null,
                    showScoutBrowserModal = false,
                    placedBalloons = layoutEngine.computeLayout(webProject, 0L, it.styleSettings, currentLiveFaces),
                    statusBanner = "Opened in Web Cinema ($hostLabel) · Scout gathering in background",
                )
            }
            projectStore.saveProject(webProject)
            refreshSavedProjects()

            if (_uiState.value.dualInstanceEnabled) {
                startWebScoutPlaybackFromStart(webProject.id)
            }

            if (cues.isNotEmpty()) {
                runBackgroundCastDiscovery(webProject.id)
                if (!sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
                    translateCurrentProjectWithAi(silentBackground = true)
                } else {
                    analyzeSpeakersWithContextAndAudio(silentBackground = true)
                }
            }
        }
    }

    fun onWebStreamPageMetadata(title: String, author: String, description: String) {
        val current = _uiState.value.project
        if (current.videoSource.kind != VideoSourceKind.WEB_STREAM) return
        val cleanTitle = title.trim()
        val cleanAuthor = author.trim()
        val cleanDesc = description.trim()
        val updatedTitle = if (current.title.startsWith("Web · ") && cleanTitle.isNotBlank()) {
            cleanTitle.take(80)
        } else {
            current.title
        }
        val nextChannel = cleanAuthor.ifBlank { current.videoSource.channelName }
        val nextDesc = cleanDesc.ifBlank { current.videoSource.description }
        if (updatedTitle == current.title &&
            nextChannel == current.videoSource.channelName &&
            nextDesc == current.videoSource.description
        ) {
            return
        }
        val updated = current.copy(
            title = updatedTitle,
            videoSource = current.videoSource.copy(
                channelName = nextChannel,
                description = nextDesc,
            ),
        )
        updateProject(updated)
        if (updated.castDiscoverySummary.isBlank()) {
            runBackgroundCastDiscovery(updated.id)
        }
    }

    private fun runBackgroundCastDiscovery(projectId: String) {
        castDiscoveryJob?.cancel()
        castDiscoveryJob = viewModelScope.launch {
            val proj = _uiState.value.project
            if (proj.id != projectId) return@launch
            if (_uiState.value.quotaSaverMode != QuotaSaverMode.PRO_ACCURACY || quotaCircuitBreakerTripped) {
                return@launch
            }
            // Never run blind text-only search cast guessing on YouTube videos:
            // the multimodal YouTube AI Scout watches the actual video and strictly requires
            // visual confirmation before assigning any real person's name to a speaker slot.
            if (proj.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                return@launch
            }
            val keyframes = if (proj.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
                runCatching {
                    videoAnalyzer.extractKeyframesForShots(Uri.parse(proj.videoSource.uriString), proj.shots.take(3))
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            try {
                val realSample = proj.dialogueEvents.filter { evt ->
                    evt.sourceText.isNotBlank() &&
                        !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                        !evt.sourceText.startsWith("Web Cinema ready")
                }
                val result = translationAiService.discoverCastAndRoles(
                    title = proj.title,
                    channelName = proj.videoSource.channelName,
                    description = proj.videoSource.description,
                    transcriptSample = realSample,
                    speakers = proj.speakers,
                    shotKeyframes = keyframes,
                ).getOrNull() ?: return@launch

                applyCastDiscoveryResult(projectId, result)
            } finally {
                keyframes.forEach { (_, bmp) -> runCatching { if (!bmp.isRecycled) bmp.recycle() } }
            }
        }
    }

    private fun applyCastDiscoveryResult(projectId: String, discovery: AiCastDiscoveryResult) {
        val current = _uiState.value.project
        if (current.id != projectId) return
        val beforeSpeakers = current.speakers
        val (mergedProj, updatedMem) = mergeCastSlotAssignments(
            project = current,
            assignments = discovery.assignments,
            rememberedColors = _uiState.value.characterColorMemory,
            reconcileColors = attributionEngine::reconcileCharacterColors,
            summary = discovery.summary,
            groundingQueries = discovery.groundingQueries,
            groundingSources = discovery.groundingSources,
            discoveryTimestampMs = _uiState.value.currentTimeMs,
        )
        if (updatedMem != _uiState.value.characterColorMemory) {
            persistCharacterColorMemory(updatedMem)
        }
        updateProject(mergedProj)
        emitSpeakerGlossaryToasts(beforeSpeakers, mergedProj.speakers)
    }

    private fun applyLiveSpeakerUpdates(projectId: String, updates: List<AiCastSlotAssignment>) {
        if (updates.isEmpty()) return
        val current = _uiState.value.project
        if (current.id != projectId) return
        val beforeSpeakers = current.speakers
        val (mergedProj, updatedMem) = mergeCastSlotAssignments(
            project = current,
            assignments = updates,
            rememberedColors = _uiState.value.characterColorMemory,
            reconcileColors = attributionEngine::reconcileCharacterColors,
            discoveryTimestampMs = _uiState.value.currentTimeMs,
        )
        if (mergedProj.speakers != current.speakers) {
            if (updatedMem != _uiState.value.characterColorMemory) {
                persistCharacterColorMemory(updatedMem)
            }
            updateProject(mergedProj)
            emitSpeakerGlossaryToasts(beforeSpeakers, mergedProj.speakers)
        }
    }

    private fun buildVideoContextString(project: DialogueLensProject): String = buildString {
        if (project.title.isNotBlank()) append("Title: ${project.title}")
        if (project.videoSource.channelName.isNotBlank()) append(" | Channel/Creator: ${project.videoSource.channelName}")
        if (project.videoSource.description.isNotBlank()) append(" | Description: ${project.videoSource.description.take(360)}")
        if (project.castDiscoverySummary.isNotBlank()) append(" | Cast Summary: ${project.castDiscoverySummary}")
    }

    fun handleSharedIncomingText(rawText: String) {
        val clean = rawText.trim()
        if (clean.isEmpty() || clean == lastHandledShareText) return
        lastHandledShareText = clean

        val sharedUrl = extractFirstHttpUrl(clean)
        if (sharedUrl != null) {
            val ytId = com.example.ui.setup.extractYouTubeId(sharedUrl)
            if (ytId != null) {
                createYouTubeProject(
                    youtubeUrl = sharedUrl,
                    youtubeVideoId = ytId,
                    subtitleUri = null,
                    subtitleFileName = "",
                    sourceLanguage = "Auto-Detect",
                    targetLanguage = _uiState.value.project.targetLanguage.ifBlank { "English" },
                    translationMode = _uiState.value.project.translationMode,
                    pastedSubtitleText = "",
                )
            } else {
                createWebStreamProject(
                    webUrl = sharedUrl,
                    subtitleUri = null,
                    subtitleFileName = "",
                    sourceLanguage = "Auto-Detect",
                    targetLanguage = _uiState.value.project.targetLanguage.ifBlank { "English" },
                    translationMode = _uiState.value.project.translationMode,
                    pastedSubtitleText = "",
                )
            }
            return
        }

        // Plain transcript text shared from notes/browser -> parse onto current project
        val cues = SubtitleParser.parseSmartTranscript(
            content = clean,
            fallbackTotalMs = _uiState.value.project.videoSource.durationMs,
        )
        if (cues.isNotEmpty()) {
            val current = _uiState.value.project
            val events = SubtitleParser.applyReadingPacingGuardrails(
                cues.map { cue ->
                    DialogueEvent(
                        id = SubtitleParser.newEventId(),
                        startTimeMs = cue.startTimeMs,
                        endTimeMs = cue.endTimeMs,
                        sourceText = cue.text,
                        translatedText = cue.text,
                        speakerId = null,
                        speakerConfidence = 0.42f,
                        faceTrackId = null,
                        faceConfidence = 0f,
                        shotId = current.shots.firstOrNull()?.id ?: "shot_1",
                        dialogueType = cue.inferredType,
                        placementMode = PlacementMode.NEUTRAL_FALLBACK,
                    )
                }
            )
            pushUndoSnapshot()
            updateProject(
                current.copy(
                    subtitleSource = SubtitleSourceKind.UPLOADED_FILE,
                    dialogueEvents = events,
                ),
                banner = "Imported ${events.size} cues from shared text",
            )
        }
    }

    private fun persistCharacterColorMemory(updatedMemory: Map<String, Int>) {
        _uiState.update { it.copy(characterColorMemory = updatedMemory) }
        viewModelScope.launch {
            projectStore.saveCharacterColors(updatedMemory)
        }
    }

    private fun persistYouTubeVideoCache(project: DialogueLensProject, scoutedUpToMs: Long) {
        val vid = project.videoSource.youtubeVideoId
        if (project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT ||
            vid.isNullOrBlank() ||
            !LookaheadWindowPlanner.hasRealSubtitles(project.dialogueEvents)
        ) {
            return
        }
        val key = ProjectStore.canonicalYouTubeCacheKey(vid, project.targetLanguage)
        viewModelScope.launch {
            projectStore.saveCachedTranscript(
                CachedVideoTranscript(
                    canonicalKey = key,
                    title = project.title,
                    channelName = project.videoSource.channelName,
                    description = project.videoSource.description,
                    durationMs = project.videoSource.durationMs,
                    sourceLanguage = project.sourceLanguage,
                    targetLanguage = project.targetLanguage,
                    subtitleSource = project.subtitleSource,
                    speakers = project.speakers,
                    shots = project.shots,
                    dialogueEvents = project.dialogueEvents,
                    scoutedUpToMs = scoutedUpToMs,
                    castDiscoverySummary = project.castDiscoverySummary,
                )
            )
        }
    }

    private fun updateProject(project: DialogueLensProject, banner: String? = null) {
        val (reconciledSpeakers, updatedMemory) = attributionEngine.reconcileCharacterColors(
            speakers = project.speakers,
            events = project.dialogueEvents,
            rememberedColors = _uiState.value.characterColorMemory,
        )
        val normalizedProject = if (reconciledSpeakers != project.speakers) {
            project.copy(speakers = reconciledSpeakers)
        } else {
            project
        }
        if (updatedMemory != _uiState.value.characterColorMemory) {
            persistCharacterColorMemory(updatedMemory)
        }
        _uiState.update { state ->
            state.copy(
                project = normalizedProject,
                placedBalloons = layoutEngine.computeLayout(normalizedProject, state.currentTimeMs, state.styleSettings, currentLiveFaces),
                statusBanner = banner ?: state.statusBanner,
            )
        }
        if (normalizedProject.id != DemoScenarioFactory.DEMO_PROJECT_ID) {
            viewModelScope.launch {
                projectStore.saveProject(normalizedProject)
                refreshSavedProjects()
            }
        }
    }

    private suspend fun refreshSavedProjects() {
        val saved = projectStore.listSavedProjects()
        _uiState.update { it.copy(savedProjects = saved) }
    }

    private fun splitStringCleanly(text: String): Pair<String, String> {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size <= 1) return text to text
        val half = words.size / 2
        return words.take(half).joinToString(" ") to words.drop(half).joinToString(" ")
    }

    override fun onCleared() {
        super.onCleared()
        scoutHeartbeatService.stopMonitoring()
        tickerJob?.cancel()
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        speakerToastJob?.cancel()
        introJob?.cancel()
        lookaheadJob?.cancel()
        exoPlayer.release()
    }
}

private data class CacheRestoreTuple(
    val speakers: List<Speaker>,
    val events: List<DialogueEvent>,
    val durationMs: Long,
    val subSource: SubtitleSourceKind,
    val sourceLanguage: String,
    val title: String,
    val channelName: String,
    val description: String,
    val horizonMs: Long,
)

private data class PendingWebTimedText(
    val observedUrl: String,
    val observedBody: String,
    val cookies: String?,
    val userAgent: String?,
)

/**
 * Reconciles a chunk's local A..L speaker slot labels against the live project roster BEFORE merging
 * cast updates or creating turnHints so concurrent workers or window-local slot renumbering never map
 * cues or names onto the wrong person's slot.
 */
internal fun reconcileWindowOutcomeSpeakersAndCues(
    existingSpeakers: List<Speaker>,
    cues: List<AiTranscribedCue>,
    speakerUpdates: List<AiCastSlotAssignment>,
): Triple<List<AiTranscribedCue>, List<AiCastSlotAssignment>, Map<String, String>> {
    val validSlots = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")
    if (speakerUpdates.isEmpty()) {
        return Triple(cues, speakerUpdates, emptyMap())
    }

    fun normalizeNameForMatch(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    fun isGenericSpeakerName(raw: String): Boolean {
        val t = raw.trim().lowercase()
        return t.isBlank() ||
            t.matches(Regex("^(speaker|host|guest|panelist|narrator|person)\\s*[a-z0-9]*$"))
    }

    fun namesMatch(incomingRaw: String, existing: Speaker): Boolean {
        if (isGenericSpeakerName(incomingRaw)) return false
        val inNorm = normalizeNameForMatch(incomingRaw)
        if (inNorm.length < 3) return false
        val candidates = (listOf(existing.displayName) + existing.previousNames)
            .filter { !isGenericSpeakerName(it) }
            .map { normalizeNameForMatch(it) }
        return candidates.any { exNorm ->
            exNorm == inNorm ||
                (exNorm.length >= 5 && inNorm.length >= 5 && (exNorm.contains(inNorm) || inNorm.contains(exNorm)))
        }
    }

    fun slotIsOccupied(spk: Speaker?): Boolean {
        if (spk == null) return false
        return spk.isLocked ||
            spk.isConfirmedCharacter ||
            spk.hasDistinctCharacterName ||
            spk.visualVoiceNotes.isNotBlank()
    }

    val existingBySlot = validSlots.associateWith { slot ->
        val idx = slot.first() - 'A'
        existingSpeakers.getOrNull(idx)
    }

    val slotRemap = mutableMapOf<String, String>()
    val claimedTargetSlots = mutableSetOf<String>()
    val pendingAssignments = mutableListOf<AiCastSlotAssignment>()

    // Pass 1: Lock exact or near-exact character name matches onto their established project slot
    for (assignment in speakerUpdates) {
        val localSlot = assignment.slot.trim().uppercase()
        if (localSlot !in validSlots) continue

        // Prefer matching existing slot at same letter first if names match, else scan roster
        val sameSlotExisting = existingBySlot[localSlot]
        val matchedSlot = if (sameSlotExisting != null && namesMatch(assignment.displayName, sameSlotExisting)) {
            localSlot
        } else {
            validSlots.firstOrNull { candidateSlot ->
                candidateSlot !in claimedTargetSlots &&
                    existingBySlot[candidateSlot]?.let { namesMatch(assignment.displayName, it) } == true
            }
        }

        if (matchedSlot != null && matchedSlot !in claimedTargetSlots) {
            slotRemap[localSlot] = matchedSlot
            claimedTargetSlots.add(matchedSlot)
        } else {
            pendingAssignments.add(assignment)
        }
    }

    // Pass 2: Place remaining new/unnamed assignments, relocating away from occupied non-matching slots
    for (assignment in pendingAssignments) {
        val localSlot = assignment.slot.trim().uppercase()
        if (localSlot in slotRemap) continue

        val existingAtLocal = existingBySlot[localSlot]
        val localHasDifferentDistinctSpeaker = existingAtLocal != null && (
            existingAtLocal.isLocked ||
                existingAtLocal.isConfirmedCharacter ||
                existingAtLocal.hasDistinctCharacterName ||
                (existingAtLocal.visualVoiceNotes.isNotBlank() &&
                    assignment.visualVoiceNotes.isNotBlank() &&
                    !existingAtLocal.visualVoiceNotes.equals(assignment.visualVoiceNotes.trim(), ignoreCase = true))
            )

        val targetSlot = if (localSlot !in claimedTargetSlots && !localHasDifferentDistinctSpeaker) {
            localSlot
        } else {
            validSlots.firstOrNull { cand ->
                cand !in claimedTargetSlots && !slotIsOccupied(existingBySlot[cand])
            } ?: validSlots.firstOrNull { cand ->
                cand !in claimedTargetSlots && existingBySlot[cand]?.isLocked != true
            } ?: localSlot
        }
        slotRemap[localSlot] = targetSlot
        claimedTargetSlots.add(targetSlot)
    }

    val remappedUpdates = speakerUpdates
        .mapNotNull { assignment ->
            val localSlot = assignment.slot.trim().uppercase()
            val mapped = slotRemap[localSlot] ?: localSlot
            if (mapped !in validSlots) null else assignment.copy(slot = mapped)
        }
        .groupBy { it.slot }
        .values
        .map { group ->
            group.maxByOrNull { item ->
                (if (item.isVisuallyConfirmed) 4 else 0) +
                    (if (!item.isRolePlaceholder) 2 else 0) +
                    if (item.displayName.isNotBlank()) 1 else 0
            } ?: group.first()
        }

    val remappedCues = cues.map { cue ->
        val rawSlot = cue.suggestedSpeakerSlot.trim().uppercase()
        val mapped = slotRemap[rawSlot] ?: rawSlot
        if (mapped == cue.suggestedSpeakerSlot) cue else cue.copy(suggestedSpeakerSlot = mapped)
    }

    return Triple(remappedCues, remappedUpdates, slotRemap.toMap())
}

internal fun mergeCastSlotAssignments(
    project: DialogueLensProject,
    assignments: List<AiCastSlotAssignment>,
    rememberedColors: Map<String, Int>,
    reconcileColors: (List<Speaker>, List<DialogueEvent>, Map<String, Int>) -> Pair<List<Speaker>, Map<String, Int>> =
        { spks, _, mem -> spks to mem },
    summary: String = "",
    groundingQueries: List<String> = emptyList(),
    groundingSources: List<String> = emptyList(),
    discoveryTimestampMs: Long? = null,
): Pair<DialogueLensProject, Map<String, Int>> {
    val workingSpeakers = project.speakers.toMutableList()
    val newlyConfirmedSpeakerNames = mutableMapOf<String, String>()

    assignments.forEach { assignment ->
        val slotChar = assignment.slot.trim().uppercase().firstOrNull()
        val slotIdx = if (slotChar != null && slotChar in 'A'..'L') (slotChar - 'A') else -1
        if (slotIdx !in 0..11) return@forEach
        if (assignment.displayName.isBlank() && assignment.visualVoiceNotes.isBlank()) return@forEach

        while (workingSpeakers.size <= slotIdx) {
            val nextIdx = workingSpeakers.size
            val letter = ('A' + nextIdx)
            workingSpeakers.add(
                Speaker(
                    id = "spk_${letter.lowercaseChar()}",
                    defaultLabel = "Speaker $letter",
                    accentIndex = nextIdx % SpeakerColorOrder.PALETTE_SIZE,
                    firstAppearedMs = discoveryTimestampMs,
                    isIdentityConfirmed = false,
                )
            )
        }

        val existing = workingSpeakers[slotIdx]
        if (existing.isLocked) return@forEach

        val normalizedRaw = assignment.displayName.trim().replace(Regex("\\s+"), " ")
        val cleanedNewName = if (normalizedRaw.length <= 26) {
            normalizedRaw
        } else {
            val slice = normalizedRaw.take(26)
            val spaceIdx = slice.lastIndexOf(' ')
            if (spaceIdx >= 8) slice.substring(0, spaceIdx).trimEnd(',', '-', ':', '/') else slice.trim()
        }
        val mergedNotes = buildString {
            val incomingNotes = assignment.visualVoiceNotes.trim()
            if (incomingNotes.isNotBlank()) {
                append(incomingNotes)
            } else if (!assignment.isVisuallyConfirmed &&
                cleanedNewName.isNotBlank() &&
                !cleanedNewName.matches(Regex("^Speaker\\s+[A-Z0-9]+$", RegexOption.IGNORE_CASE))
            ) {
                append(cleanedNewName)
            } else if (existing.visualVoiceNotes.isNotBlank()) {
                append(existing.visualVoiceNotes)
            }
        }
        val mergedReason = assignment.rationale.trim().ifBlank { existing.discoveryReason }
        val mergedFirstSeen = existing.firstAppearedMs ?: discoveryTimestampMs

        if (!assignment.isVisuallyConfirmed) {
            // Strict visual confirmation gate: keep unconfirmed speakers dotted gray (no premature name)
            // while storing rich visual/voice clues so later Scout windows can recognize and confirm them!
            if (!existing.isConfirmedCharacter) {
                workingSpeakers[slotIdx] = existing.copy(
                    isIdentityConfirmed = false,
                    isRolePlaceholder = true,
                    firstAppearedMs = mergedFirstSeen,
                    visualVoiceNotes = mergedNotes.ifBlank { existing.visualVoiceNotes },
                    discoveryReason = mergedReason,
                )
            } else if (mergedNotes != existing.visualVoiceNotes ||
                mergedReason != existing.discoveryReason ||
                mergedFirstSeen != existing.firstAppearedMs
            ) {
                workingSpeakers[slotIdx] = existing.copy(
                    firstAppearedMs = mergedFirstSeen,
                    visualVoiceNotes = mergedNotes.ifBlank { existing.visualVoiceNotes },
                    discoveryReason = mergedReason,
                )
            }
            return@forEach
        }

        if (cleanedNewName.isBlank()) return@forEach

        val shouldApplyName = !existing.isConfirmedCharacter ||
            !existing.hasDistinctCharacterName ||
            (existing.isRolePlaceholder && !assignment.isRolePlaceholder)

        if (shouldApplyName && (!existing.displayName.equals(cleanedNewName, ignoreCase = true) || !existing.isIdentityConfirmed)) {
            val priorHistory = if (existing.hasDistinctCharacterName &&
                existing.displayName.isNotBlank() &&
                !existing.displayName.equals(cleanedNewName, ignoreCase = true)
            ) {
                (existing.previousNames + existing.displayName).distinct().takeLast(4)
            } else {
                existing.previousNames
            }
            workingSpeakers[slotIdx] = existing.copy(
                customName = cleanedNewName,
                isRolePlaceholder = assignment.isRolePlaceholder,
                isIdentityConfirmed = true,
                firstAppearedMs = mergedFirstSeen,
                visualVoiceNotes = mergedNotes.ifBlank { existing.visualVoiceNotes },
                previousNames = priorHistory,
                discoveryReason = mergedReason,
            )
            newlyConfirmedSpeakerNames[existing.id] = cleanedNewName
        } else if (mergedNotes != existing.visualVoiceNotes ||
            mergedReason != existing.discoveryReason ||
            mergedFirstSeen != existing.firstAppearedMs
        ) {
            workingSpeakers[slotIdx] = existing.copy(
                firstAppearedMs = mergedFirstSeen,
                visualVoiceNotes = mergedNotes.ifBlank { existing.visualVoiceNotes },
                discoveryReason = mergedReason,
            )
        }
    }

    // Retroactively update ALL existing user video cues for every speaker that just became visually confirmed
    val retroactivelyUpdatedEvents = if (newlyConfirmedSpeakerNames.isEmpty()) {
        project.dialogueEvents
    } else {
        project.dialogueEvents.map { evt ->
            val confirmedName = evt.speakerId?.let { newlyConfirmedSpeakerNames[it] }
            if (confirmedName != null && !evt.lockedByUser) {
                val upgradedPlacement = when {
                    evt.dialogueType != DialogueType.SPOKEN -> PlacementMode.EDGE_DOCKED
                    evt.anchorPosition != null || evt.faceTrackId != null -> PlacementMode.AUTO
                    else -> evt.placementMode
                }
                val nextSummary = if (evt.attributionSummary.contains(confirmedName, ignoreCase = true)) {
                    evt.attributionSummary
                } else {
                    "Confirmed: $confirmedName"
                }
                evt.copy(
                    speakerConfidence = maxOf(evt.speakerConfidence, 0.90f),
                    placementMode = upgradedPlacement,
                    attributionSummary = nextSummary,
                )
            } else {
                evt
            }
        }
    }

    val (reconciledSpeakers, updatedMemory) = reconcileColors(
        workingSpeakers,
        retroactivelyUpdatedEvents,
        rememberedColors,
    )
    val mergedSummary = summary.ifBlank { project.castDiscoverySummary }
    val mergedQueries = (project.groundingQueries + groundingQueries).distinct()
    val mergedSources = (project.groundingSources + groundingSources).distinct().take(6)

    return project.copy(
        speakers = reconciledSpeakers,
        dialogueEvents = retroactivelyUpdatedEvents,
        castDiscoverySummary = mergedSummary,
        groundingQueries = mergedQueries,
        groundingSources = mergedSources,
    ) to updatedMemory
}

/**
 * Maps generic off-screen cutaway cues (OFF_SCREEN_BEHIND) onto a speaker's remembered left or right
 * studio edge whenever their preferredScreenSideX is established, and clears stale on-screen anchors
 * on off-screen cues so bubbles never attach a false pointer tail to a silent reaction shot.
 */
internal fun applyCutawayOffScreenSideMemory(
    events: List<DialogueEvent>,
    speakers: List<Speaker>,
): List<DialogueEvent> {
    if (events.isEmpty()) return events
    val speakerById = speakers.associateBy { it.id }
    val spokenAvgSideBySpeakerId = events
        .filter { it.dialogueType == DialogueType.SPOKEN && it.speakerId != null && it.anchorPosition != null }
        .groupBy { it.speakerId!! }
        .mapValues { (_, list) -> list.mapNotNull { it.anchorPosition?.x }.average().toFloat() }

    return events.map { evt ->
        val isOffScreen = evt.dialogueType == DialogueType.OFF_SCREEN_LEFT ||
            evt.dialogueType == DialogueType.OFF_SCREEN_RIGHT ||
            evt.dialogueType == DialogueType.OFF_SCREEN_BEHIND
        if (!isOffScreen) return@map evt

        val resolvedType = if (evt.dialogueType == DialogueType.OFF_SCREEN_BEHIND && evt.speakerId != null) {
            val sideX = speakerById[evt.speakerId]?.preferredScreenSideX ?: spokenAvgSideBySpeakerId[evt.speakerId]
            when {
                sideX != null && sideX < 0.45f -> DialogueType.OFF_SCREEN_LEFT
                sideX != null && sideX > 0.55f -> DialogueType.OFF_SCREEN_RIGHT
                else -> evt.dialogueType
            }
        } else {
            evt.dialogueType
        }
        val nextPlacement = if (evt.placementMode == PlacementMode.MANUAL_OVERRIDE) {
            evt.placementMode
        } else {
            PlacementMode.EDGE_DOCKED
        }
        if (resolvedType != evt.dialogueType || evt.anchorPosition != null || evt.placementMode != nextPlacement) {
            evt.copy(
                dialogueType = resolvedType,
                anchorPosition = if (nextPlacement == PlacementMode.MANUAL_OVERRIDE) evt.anchorPosition else null,
                placementMode = nextPlacement,
            )
        } else {
            evt
        }
    }
}

internal fun extractFirstHttpUrl(rawText: String): String? {
    val match = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE).find(rawText)
    return match?.value?.trimEnd('.', ',', ';', ')', ']')
}

private data class CapturedWebTrackCuePayload(
    val startMs: Long = 0L,
    val endMs: Long = 2000L,
    val text: String = "",
)

private val webTrackMoshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
private val webTrackListType = Types.newParameterizedType(List::class.java, CapturedWebTrackCuePayload::class.java)
private val webTrackAdapter = webTrackMoshi.adapter<List<CapturedWebTrackCuePayload>>(webTrackListType)

internal fun parseCapturedWebTrackJson(rawJson: String, shotId: String = "shot_1"): List<DialogueEvent> {
    val parsed = runCatching {
        webTrackAdapter.fromJson(rawJson)
    }.getOrNull() ?: return emptyList()

    val events = parsed.mapNotNull { item ->
        val text = item.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        val startMs = item.startMs.coerceAtLeast(0L)
        val endMs = item.endMs.coerceAtLeast(startMs + 350L)
        val inferredType = if (
            text.startsWith("[") || text.startsWith("(") || text.contains("♪") || text.contains("♫")
        ) {
            DialogueType.SOUND_DESCRIPTION
        } else {
            DialogueType.SPOKEN
        }
        DialogueEvent(
            id = SubtitleParser.newEventId(),
            startTimeMs = startMs,
            endTimeMs = endMs,
            sourceText = text,
            translatedText = text,
            speakerId = null,
            speakerConfidence = 0.45f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = shotId,
            dialogueType = inferredType,
            placementMode = if (inferredType == DialogueType.SPOKEN) {
                PlacementMode.NEUTRAL_FALLBACK
            } else {
                PlacementMode.EDGE_DOCKED
            },
        )
    }
    return SubtitleParser.applyReadingPacingGuardrails(events)
}
