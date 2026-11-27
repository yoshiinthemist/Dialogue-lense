package com.example.ui.state

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.domain.analysis.AudioAnalyzer
import com.example.domain.analysis.AutoSelfHealDetector
import com.example.domain.analysis.CoverageSummary
import com.example.domain.analysis.HealBeaconEngine
import com.example.domain.analysis.HierarchicalContextEngine
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.LiveStageFrameSnapshot
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.PlaybackClockAnchor
import com.example.domain.analysis.ResilientYouTubeSegmentExecutor
import com.example.domain.analysis.ScoutExecutionTier
import com.example.domain.analysis.ScoutSyncCommand
import com.example.domain.analysis.SegmentExecutionOutcome
import com.example.domain.analysis.SelfHealSignalKind
import com.example.domain.analysis.SniffedWebMediaStream
import com.example.domain.analysis.TimeWindowRange
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.analysis.TranscriptDiagnostics
import com.example.domain.analysis.TranscriptionOutcome
import com.example.domain.analysis.VideoAnalyzer
import com.example.domain.analysis.WebNavAction
import com.example.domain.analysis.WebNavCommand
import com.example.domain.analysis.WebNavigationGuard
import com.example.domain.analysis.WebNavigationState
import com.example.domain.media.MediaCapabilities
import com.example.domain.media.MediaSourceAdapterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
import com.example.data.model.CoverageInterval
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.GlossaryEntry
import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealProblemHint
import com.example.data.model.HealScope
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.ProcessingQualityMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.ScoutCaptureDiagnostics
import com.example.data.model.ScoutCaptureHealthState
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SpeakerColorOrder
import com.example.data.model.SpeakerLabelMode
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.model.VideoChapter
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.model.YouTubeNativeCcState
import com.example.data.model.YouTubePlayerErrorKind
import com.example.data.model.classifyYouTubeErrorCode
import com.example.data.project.DemoScenarioFactory
import com.example.data.project.ProjectStore
import com.example.data.subtitle.RawSubtitleCue
import com.example.data.subtitle.SubtitleParser
import com.example.data.subtitle.YouTubeCaptionFetcher
import com.example.domain.attribution.SpeakerAttributionEngine
import com.example.domain.export.VideoExporter
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.PlacedBalloon
import com.example.domain.layout.VisualStabilityDiagnosticsSnapshot
import com.example.domain.pipeline.DraftInterval
import com.example.domain.pipeline.IntervalReviewOutcome
import com.example.domain.pipeline.ReleasedPlaybackSnapshot
import com.example.domain.pipeline.ReleasedTimelineStore
import com.example.domain.pipeline.Scout2IndependentReviewer
import com.example.domain.pipeline.YouTubeCapabilityReport
import com.example.domain.playback.PlaybackReadinessController
import com.example.domain.playback.PlaybackReadinessSnapshot
import com.example.domain.playback.PlaybackReadinessState
import com.example.domain.playback.UserPlaybackIntent
import com.example.domain.sentinel.BoundarySubmissionDecision
import com.example.domain.sentinel.RollingBoundaryPolicy
import com.example.domain.sentinel.SchedulerOutcomeState
import com.example.domain.sentinel.SentinelInspectorSnapshot
import com.example.domain.sentinel.SentinelPriorityTier
import com.example.domain.sentinel.SentinelTaskKind
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

enum class AppDestination(val label: String) {
    WATCH("Watch"),
    LIBRARY("Library"),
    STUDIO("Studio"),
}

data class DialogueLensUiState(
    val project: DialogueLensProject = DemoScenarioFactory.createDemoProject(),
    val savedProjects: List<DialogueLensProject> = emptyList(),
    val activeDestination: AppDestination = AppDestination.LIBRARY,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val currentTimeMs: Long = 600L,
    val displayMode: CaptionDisplayMode = CaptionDisplayMode.BUBBLES_WITH_TAILS,
    val styleSettings: BalloonStyleSettings = BalloonStyleSettings(),
    val isEditMode: Boolean = false,
    val selectedEventId: String? = null,
    val placedBalloons: List<PlacedBalloon> = emptyList(),
    val analysisStatus: AnalysisStatus = AnalysisStatus.Ready,
    val scoutCaptionState: ScoutCaptionState = ScoutCaptionState.Idle,
    val youtubeNativeCcTrackCount: Int? = null,
    val youtubeNativeCcLang: String = "",
    val youtubeNativeCcState: YouTubeNativeCcState = YouTubeNativeCcState.LOADING,
    val youtubeNativeCcTrackEnabled: Boolean = false,
    val youtubeNativeCcProbeBlocked: Boolean = false,
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
    val showScoutMonitorPip: Boolean = false,
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
    val coverageSummary: CoverageSummary = CoverageSummary(),
    val transcriptDiagnostics: TranscriptDiagnostics = TranscriptDiagnostics(),
    val viewerWebNav: WebNavigationState = WebNavigationState(),
    val scoutWebNav: WebNavigationState = WebNavigationState(),
    val viewerWebNavCommand: WebNavCommand? = null,
    val scoutWebNavCommand: WebNavCommand? = null,
    val showScoutBrowserModal: Boolean = false,
    val selectedHealBeaconId: String? = null,
    val isDialoguePanelExpanded: Boolean = true,
    val activeChapter: VideoChapter? = null,
    val humanReadiness: HumanReadableReadiness = HumanReadableReadiness(
        label = "Ready",
        detail = "Choose a video to begin.",
        tone = ReadinessTone.READY,
    ),
    val mediaCapabilities: MediaCapabilities = MediaSourceAdapterRegistry.default.capabilitiesFor(
        DemoScenarioFactory.createDemoProject().videoSource,
    ),
    val readinessSnapshot: PlaybackReadinessSnapshot = PlaybackReadinessSnapshot(),
    val visualStabilitySnapshot: VisualStabilityDiagnosticsSnapshot = VisualStabilityDiagnosticsSnapshot(),
    val sentinelSnapshot: SentinelInspectorSnapshot = SentinelInspectorSnapshot(),
    val releaseSnapshot: ReleasedPlaybackSnapshot = ReleasedPlaybackSnapshot(),
    val youtubeCapabilities: YouTubeCapabilityReport = YouTubeCapabilityReport(),
    val scoutCaptureDiagnostics: ScoutCaptureDiagnostics = ScoutCaptureDiagnostics(),
    val youtubePlayerErrorKind: YouTubePlayerErrorKind? = null,
    val hasCompletedOnboarding: Boolean = true,
    val seekGeneration: Long = 0L,
    val measurementEpoch: Long = 0L,
)

class DialogueLensViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val projectStore = ProjectStore(appContext)
    private val videoAnalyzer = VideoAnalyzer(appContext)
    private val audioAnalyzer = AudioAnalyzer(appContext)
    private val attributionEngine = SpeakerAttributionEngine()
    private val layoutEngine = BalloonLayoutEngine()
    private val contextEngine = HierarchicalContextEngine()
    private val autoSelfHealDetector = AutoSelfHealDetector()
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
    private val healBeaconMutex = Mutex()
    private var healBeaconJob: Job? = null
    private var lastProgressPersistedAtMs = 0L
    private var lastAutoSelfHealCheckAtMs = 0L

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
    private var statusBannerJob: Job? = null
    private var seekAckTimeoutJob: Job? = null
    private var nextSpeakerToastId = 0L
    private var lastCapturedWebTrackSignature: String? = null
    private var lastHandledShareText: String? = null
    private var nextStreamCommandSeq = 0
    private var nextScoutCommandSeq = 0
    private var lastScoutSeekSentAtMs = 0L
    private var lastScoutSeekTargetMs = -1L
    private val scoutCompletedExtents = mutableListOf<TimeWindowRange>()
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
    private var nextViewerNavSeq = 0
    private var nextScoutNavSeq = 0
    private var lastAutoScoutSyncAtMs = 0L
    private val lookaheadMutex = Mutex()
    private val playbackClockAnchor = PlaybackClockAnchor()
    private val activeSessionToken = AtomicLong(0L)
    private val coverageLedger = TranscriptCoverageLedger()
    private val readinessController = PlaybackReadinessController()
    private val sentinelCoordinator = SentinelWorkforceCoordinator()
    private val schedulerWakeEpoch = MutableStateFlow(0L)
    private val releaseStore = ReleasedTimelineStore()
    private val scout2Reviewer = Scout2IndependentReviewer()
    private val measuredBubbleSizes = mutableMapOf<String, Pair<Float, Float>>()
    private var lastPatrolSweepAtMs = 0L
    private var changeoverPreListenJob: Job? = null
    private var lastChangeoverPreListenSweepAtMs = 0L
    private var lastDeadlineCheckAtMs = 0L
    private val settledAttributedEventIds = mutableSetOf<String>()
    private var lastSceneSideResetKey: String = ""
    private var lastDetectorFrameId: Long = -1L

    private data class CapturedAudioQueueItem(
        val bucketKey: Long,
        val startMs: Long,
        val endMs: Long,
        val base64Audio: String,
        val mimeType: String,
        val discontinuity: Boolean,
        val requestCtx: WorkRequestContext,
    )

    private val capturedAudioQueueLock = Any()
    private val pendingCapturedAudioQueue = ArrayDeque<CapturedAudioQueueItem>()
    private val inFlightCapturedAudioBuckets = mutableSetOf<Long>()
    private var capturedAudioConsumerJob: Job? = null

    private fun clearCapturedAudioQueueSync() {
        capturedAudioConsumerJob?.cancel()
        capturedAudioConsumerJob = null
        synchronized(capturedAudioQueueLock) {
            pendingCapturedAudioQueue.clear()
            inFlightCapturedAudioBuckets.clear()
        }
    }

    private fun buildSlotToSpeakerIdMap(speakers: List<Speaker>): Map<String, String> =
        speakers.mapIndexedNotNull { idx, spk ->
            if (idx in 0..11) (('A' + idx).toString()) to spk.id else null
        }.toMap()

    private fun showStatusBanner(message: String?, autoClearMs: Long = 3_500L) {
        statusBannerJob?.cancel()
        _uiState.update { it.copy(statusBanner = message) }
        if (!message.isNullOrBlank() && autoClearMs > 0L) {
            statusBannerJob = viewModelScope.launch {
                delay(autoClearMs)
                _uiState.update { cur ->
                    if (cur.statusBanner == message) cur.copy(statusBanner = null) else cur
                }
            }
        }
    }

    internal fun configureRollingBoundaryPolicyForTest(policy: RollingBoundaryPolicy) {
        sentinelCoordinator.configurePolicy(policy)
    }

    internal fun currentSchedulerSnapshotForTest() = sentinelCoordinator.schedulerSnapshot()

    private fun wakeScheduler(reason: String) {
        sentinelCoordinator.wakeForReason(reason)
        schedulerWakeEpoch.update { it + 1L }
    }

    private suspend fun awaitSchedulerWake(observedEpoch: Long) {
        schedulerWakeEpoch.first { it > observedEpoch }
    }

    private fun recordCompletedWindowExtent(startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        scoutCompletedExtents.add(TimeWindowRange(startMs, endMs))
    }

    private fun hasCompletedWindowAt(positionMs: Long): Boolean =
        scoutCompletedExtents.any { positionMs in it.startMs until it.endMs }

    private val sourceEditRevision = AtomicLong(0L)

    /**
     * Immutable request context captured at dispatch time so late completions from prior
     * sessions, seeks, source edits, or language/quota configurations can never corrupt current playback.
     */
    internal data class WorkRequestContext(
        val mediaId: String,
        val sessionToken: Long,
        val seekGeneration: Long,
        val configFingerprint: String,
        val sourceEditRevision: Long = 0L,
    )

    internal fun captureWorkRequestContext(
        project: DialogueLensProject = _uiState.value.project,
        mode: QuotaSaverMode = _uiState.value.quotaSaverMode,
    ): WorkRequestContext = WorkRequestContext(
        mediaId = project.id,
        sessionToken = activeSessionToken.get(),
        seekGeneration = _uiState.value.seekGeneration,
        configFingerprint = currentConfigFingerprint(project, mode),
        sourceEditRevision = sourceEditRevision.get(),
    )

    internal fun isRequestContextCurrent(ctx: WorkRequestContext): Boolean {
        val state = _uiState.value
        if (activeSessionToken.get() != ctx.sessionToken) return false
        if (state.project.id != ctx.mediaId) return false
        if (state.seekGeneration != ctx.seekGeneration) return false
        if (sourceEditRevision.get() != ctx.sourceEditRevision) return false
        if (currentConfigFingerprint(state.project, state.quotaSaverMode) != ctx.configFingerprint) return false
        return true
    }

    internal fun isSameResolvedLanguage(
        sourceLanguage: String,
        targetLanguage: String,
        sampleTexts: List<String> = emptyList(),
    ): Boolean {
        val tgtNorm = Scout2IndependentReviewer.normalizeLanguageTag(targetLanguage)
        if (tgtNorm.isBlank() || tgtNorm == "auto") return true
        val srcNorm = Scout2IndependentReviewer.normalizeLanguageTag(sourceLanguage)
        if (srcNorm.isNotBlank() && srcNorm != "auto") {
            if (srcNorm != tgtNorm) return false
            val hasForeignScript = sampleTexts.any { txt ->
                val scriptLang = Scout2IndependentReviewer.detectDominantScriptLanguage(txt)
                scriptLang.isNotBlank() && scriptLang != tgtNorm
            }
            return !hasForeignScript
        }
        // Auto-Detect is unresolved — only consider matching when non-empty samples uniquely resolve
        // to a distinct script matching targetLanguage and contain no foreign-script segments.
        val nonBlank = sampleTexts.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return false
        val detectedScripts = nonBlank.mapNotNull { txt ->
            Scout2IndependentReviewer.detectDominantScriptLanguage(txt).takeIf { it.isNotBlank() }
        }
        if (detectedScripts.isNotEmpty()) {
            return detectedScripts.size == nonBlank.size && detectedScripts.all { it == tgtNorm }
        }
        return false
    }

    private fun canInvokeCloudAi(
        ctx: WorkRequestContext,
        sliceStartMs: Long = 0L,
        sliceEndMs: Long = 0L,
        enforceBoundary: Boolean = true,
        requireUnloadedTracks: Boolean = false,
    ): Boolean {
        if (!isRequestContextCurrent(ctx)) return false
        val state = _uiState.value
        if (state.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD) return false
        if (quotaCircuitBreakerTripped) return false
        if (requireUnloadedTracks &&
            LookaheadWindowPlanner.hasLoadedSubtitlesLocked(state.project.subtitleSource, state.project.dialogueEvents)
        ) {
            return false
        }
        if (enforceBoundary) {
            if (sentinelCoordinator.isScrubberPreviewActive()) return false
            if (sentinelCoordinator.schedulerSnapshot().pausedDeadlineReached) return false
            val boundaryEndMs = sentinelCoordinator.currentBoundaryEndMs()
            if (sliceStartMs >= boundaryEndMs || sliceEndMs > boundaryEndMs) return false
        }
        return true
    }

    internal fun attachReferenceTrackForTest(track: com.example.data.model.ReferenceCaptionTrack) {
        releaseStore.updateReferenceTrack(track, sessionToken = activeSessionToken.get())
    }

    internal fun coverageStatusAtForTest(timeMs: Long): CoverageStatus? =
        coverageLedger.coverageStatusAt(timeMs)

    internal fun currentLiveFacesForTest(): List<LiveFaceObservation> =
        currentLiveFaces.toList()

    internal fun currentDiscoveredWebMediaForTest(): Pair<String?, Map<String, String>> =
        discoveredWebMediaUrl to discoveredWebMediaHeaders.toMap()

    private fun currentConfigFingerprint(
        project: DialogueLensProject = _uiState.value.project,
        mode: QuotaSaverMode = _uiState.value.quotaSaverMode,
    ): String = "${project.sourceLanguage}|${project.targetLanguage}|${project.translationMode.name}|${mode.name}"

    private fun nextSessionToken(): Long = activeSessionToken.incrementAndGet()

    private fun isCurrentSession(sessionToken: Long, projectId: String? = null): Boolean {
        if (activeSessionToken.get() != sessionToken) return false
        return projectId == null || _uiState.value.project.id == projectId
    }

    private var nextDraftRevision = AtomicLong(0L)

    /**
     * Authoritative event merge for a reviewed/released interval that ALWAYS preserves
     * user-locked (`lockedByUser`) edits inside [startMs, endMs).
     */
    internal fun mergeReleasedIntervalPreservingLocks(
        existingEvents: List<DialogueEvent>,
        releasedIntervalEvents: List<DialogueEvent>,
        startMs: Long,
        endMs: Long,
    ): List<DialogueEvent> {
        val outside = existingEvents.filterNot { it.startTimeMs in startMs until endMs }
        val lockedInside = existingEvents.filter { it.startTimeMs in startMs until endMs && it.lockedByUser }
        if (lockedInside.isEmpty()) {
            return (outside + releasedIntervalEvents).sortedBy { it.startTimeMs }
        }
        val filteredIncoming = releasedIntervalEvents.filter { incoming ->
            lockedInside.none { locked ->
                val sameIdentity = locked.id == incoming.id ||
                    locked.canonicalIdentityKey == incoming.canonicalIdentityKey
                val overlapsTime = incoming.startTimeMs < locked.endTimeMs && incoming.endTimeMs > locked.startTimeMs
                val sameOrUnknownSpeaker = locked.speakerId == null ||
                    incoming.speakerId == null ||
                    locked.speakerId == incoming.speakerId
                sameIdentity || (overlapsTime && sameOrUnknownSpeaker)
            }
        }
        return (outside + lockedInside + filteredIncoming).sortedBy { it.startTimeMs }
    }

    /**
     * Authoritative publication helper so reviewed/recovered/fallback dialogue reaches playback,
     * coverage ledger, releaseSnapshot, and persistence identically.
     */
    internal fun publishReleasedEventsToProject(
        startMs: Long,
        endMs: Long,
        releasedEvents: List<DialogueEvent>,
        coverageStatus: CoverageStatus,
        source: SubtitleSourceKind,
        speakers: List<Speaker>? = null,
        failureReason: String = "",
        banner: String? = null,
        persistYouTubeCacheSessionToken: Long? = null,
    ) {
        val liveProj = _uiState.value.project
        val mergedEvents = mergeReleasedIntervalPreservingLocks(
            existingEvents = liveProj.dialogueEvents.filterNot { evt ->
                evt.sourceText.startsWith("Embedded YouTube Player active") ||
                    evt.sourceText.startsWith("Web Cinema ready")
            },
            releasedIntervalEvents = releasedEvents,
            startMs = startMs,
            endMs = endMs,
        )
        val effectiveStatus = if (releasedEvents.isNotEmpty() && coverageStatus == CoverageStatus.VERIFIED_SILENCE) {
            CoverageStatus.RELEASED_FALLBACK
        } else {
            coverageStatus
        }
        val maxCueEndMs = mergedEvents.maxOfOrNull { it.endTimeMs } ?: 0L
        val expandedDurationMs = maxOf(liveProj.videoSource.durationMs, maxCueEndMs, endMs)
        coverageLedger.updateDuration(expandedDurationMs)
        coverageLedger.recordOutcome(
            startMs = startMs,
            endMs = endMs,
            status = effectiveStatus,
            source = source,
            dialogueCount = releasedEvents.size,
            failureReason = failureReason,
        )
        val updatedProj = liveProj.copy(
            subtitleSource = if (liveProj.subtitleSource == SubtitleSourceKind.BUILT_IN_DEMO && releasedEvents.isNotEmpty()) {
                source
            } else {
                liveProj.subtitleSource
            },
            videoSource = liveProj.videoSource.copy(durationMs = expandedDurationMs),
            speakers = speakers ?: liveProj.speakers,
            dialogueEvents = mergedEvents,
        )
        updateProject(updatedProj, banner = banner)
        _uiState.update { it.copy(releaseSnapshot = releaseStore.snapshot()) }
        syncCoverageUiState()
        if (persistYouTubeCacheSessionToken != null && updatedProj.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
            persistYouTubeVideoCache(updatedProj, coverageLedger.continuousVerifiedUpToMs(), persistYouTubeCacheSessionToken)
        }
    }

    /**
     * Central Two-Scout Release Gate:
     * Scout 1 stages an interval draft -> Scout 2 claims the matching non-suspended required review task ->
     * Scout 2 independently evaluates coverage, reference captions, attribution, and layout stability
     * via the same [BalloonLayoutEngine] -> ReleasedTimelineStore atomically publishes the reviewed
     * interval (or rejects stale session/seek/config/revision writes).
     */
    internal fun stageAndReviewIntervalThroughReleaseGate(
        project: DialogueLensProject,
        intervalId: String,
        startMs: Long,
        endMs: Long,
        events: List<DialogueEvent>,
        speakers: List<Speaker>,
        sessionToken: Long,
        seekGeneration: Long,
        isConfirmedSilence: Boolean = false,
        hasUnavailableEvidence: Boolean = false,
        hasIndependentAudioOrFrameEvidence: Boolean = false,
        elapsedProcessingMs: Long = 0L,
        expectedConfigFingerprint: String? = null,
    ): Pair<IntervalReviewOutcome?, ReleasedPlaybackSnapshot> {
        if (elapsedProcessingMs > 0L) {
            readinessController.recordProcessingLatencyMs(elapsedProcessingMs)
        }
        val state = _uiState.value
        val configFp = expectedConfigFingerprint ?: currentConfigFingerprint(project, state.quotaSaverMode)
        val nowMs = System.currentTimeMillis()
        val draftRev = nextDraftRevision.incrementAndGet()
        val refTrack = releaseStore.currentReferenceTrack()
        val draft = DraftInterval(
            intervalId = intervalId,
            mediaId = project.id,
            sessionToken = sessionToken,
            seekGeneration = seekGeneration,
            sourceRevision = refTrack?.sourceRevision ?: 1L,
            draftRevision = draftRev,
            configFingerprint = configFp,
            startMs = startMs,
            endMs = endMs,
            sourceLanguage = project.sourceLanguage,
            targetLanguage = project.targetLanguage,
            events = events,
            speakers = speakers,
            referenceTrackId = refTrack?.trackId,
            evidenceProvenance = project.subtitleSource.name,
            isConfirmedSilence = isConfirmedSilence,
            hasUnavailableEvidence = hasUnavailableEvidence,
            hasIndependentAudioOrFrameEvidence = hasIndependentAudioOrFrameEvidence,
            createdAtEpochMs = nowMs,
        )
        if (!releaseStore.stageDraft(draft)) {
            return null to releaseStore.snapshot()
        }
        sentinelCoordinator.updatePendingReviewCount(releaseStore.pendingDraftCount())

        // Dependent Stage Scheduler: Scout 2 review runs at CRITICAL priority and is NEVER suspended by low lead
        sentinelCoordinator.enqueueScout2Review(
            intervalId = intervalId,
            startMs = startMs,
            endMs = endMs,
            playheadMs = sentinelCoordinator.currentAcknowledgedViewerPosMs(),
            nowEpochMs = nowMs,
            draftRevision = draft.draftRevision,
            schedulingGeneration = seekGeneration,
        )
        val claimedReview = sentinelCoordinator.claimRequiredReviewWork(
            workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
            playheadMs = sentinelCoordinator.currentAcknowledgedViewerPosMs(),
            nowEpochMs = nowMs,
            targetIntervalId = intervalId,
        )
        val expectedCtx = WorkRequestContext(
            mediaId = project.id,
            sessionToken = sessionToken,
            seekGeneration = seekGeneration,
            configFingerprint = configFp,
        )
        if (claimedReview == null ||
            !isRequestContextCurrent(expectedCtx) ||
            claimedReview.first.draftRevision != draft.draftRevision
        ) {
            if (claimedReview != null) {
                sentinelCoordinator.markWorkerIdle(
                    workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
                    reservationId = claimedReview.second.reservationId,
                    repairCompleted = false,
                )
            }
            wakeScheduler("review_claim_deferred")
            return null to releaseStore.snapshot()
        }
        val stagedDraft = releaseStore.pendingDraft(claimedReview.first.beaconId ?: intervalId)
        if (stagedDraft == null || stagedDraft.draftRevision != draft.draftRevision) {
            sentinelCoordinator.markWorkerIdle(
                workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
                reservationId = claimedReview.second.reservationId,
                repairCompleted = false,
            )
            wakeScheduler("review_draft_missing")
            return null to releaseStore.snapshot()
        }
        val outcome = try {
            scout2Reviewer.reviewDraft(
                draft = stagedDraft,
                referenceTrack = refTrack,
                layoutEngine = layoutEngine,
                projectTemplate = project,
                liveFaces = currentLiveFaces,
                liveFacesPlayheadMs = state.currentTimeMs,
                measuredNormalizedSizes = measuredBubbleSizes.toMap(),
                styleSettings = state.styleSettings,
                displayMode = state.displayMode,
            )
        } finally {
            sentinelCoordinator.markWorkerIdle(
                workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
                reservationId = claimedReview.second.reservationId,
                repairCompleted = true,
            )
        }
        val releasedSnap = releaseStore.commitReviewedRelease(
            draft = stagedDraft,
            outcome = outcome,
            nowEpochMs = System.currentTimeMillis(),
        )
        sentinelCoordinator.updatePendingReviewCount(releaseStore.pendingDraftCount())
        return if (releasedSnap != null) outcome to releasedSnap else null to releaseStore.snapshot()
    }

    private fun executePendingAutoResumeIfNeeded(snapshot: PlaybackReadinessSnapshot): PlaybackReadinessSnapshot {
        val directive = snapshot.pendingAutoResume ?: return snapshot
        val cur = _uiState.value
        val kind = cur.project.videoSource.kind
        val targetMs = directive.targetPositionMs.coerceAtLeast(0L)
        val consumed = readinessController.consumeAutoResumeDirective(directive.id)
        when (kind) {
            VideoSourceKind.LOCAL_MP4 -> {
                if (kotlin.math.abs(exoPlayer.currentPosition - targetMs) > 1_200L) {
                    exoPlayer.seekTo(targetMs)
                }
                exoPlayer.play()
            }
            VideoSourceKind.YOUTUBE_COMPLIANT,
            VideoSourceKind.WEB_STREAM -> {
                val playSeq = ++nextStreamCommandSeq
                val seekSeq = if (kotlin.math.abs(cur.currentTimeMs - targetMs) > 1_200L) {
                    ++nextStreamCommandSeq
                } else {
                    null
                }
                playbackClockAnchor.reset(targetMs, cur.playbackSpeed, playing = true)
                _uiState.update { s ->
                    s.copy(
                        isPlaying = true,
                        isCinemaIntroActive = false,
                        isScoutCatchUpBuffering = false,
                        currentTimeMs = targetMs,
                        youtubeSeekRequest = seekSeq?.let { it to targetMs } ?: s.youtubeSeekRequest,
                        streamPlayRequest = playSeq to true,
                        readinessSnapshot = consumed,
                    )
                }
            }
            VideoSourceKind.DEMO_SCENARIO -> {
                _uiState.update { it.copy(isPlaying = true, readinessSnapshot = consumed) }
            }
        }
        return consumed
    }

    private fun syncCoverageUiState(
        totalWindowHint: Int? = null,
        completedWindowHint: Int? = null,
    ) {
        val summary = coverageLedger.summary()
        val diagnostics = coverageLedger.diagnostics()
        val evaluatedReadiness = executePendingAutoResumeIfNeeded(
            readinessController.evaluateCoverage(coverageLedger),
        )
        sentinelCoordinator.updatePendingReviewCount(releaseStore.pendingDraftCount())
        sentinelCoordinator.updateRuntimeConditions(
            quotaMode = _uiState.value.quotaSaverMode,
            isPlaybackBuffering = evaluatedReadiness.isBufferingHoldActive || _uiState.value.isCinemaIntroActive,
            contiguousPlayheadLeadMs = evaluatedReadiness.contiguousVerifiedLeadMs,
        )
        sentinelCoordinator.evaluateSchedulerOutcome(
            ledger = coverageLedger,
            quotaMode = _uiState.value.quotaSaverMode,
            quotaExhausted = quotaCircuitBreakerTripped,
            hasPendingReviews = releaseStore.pendingDraftCount() > 0,
            hasEligibleQualityWork = false,
        )
        val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
        val releaseSnap = releaseStore.snapshot()
        _uiState.update { cur ->
            val durSec = (cur.project.videoSource.durationMs / 1000L).toInt().coerceAtLeast(1)
            val fallbackTotal = totalWindowHint ?: if (cur.scoutTotalWindows > 0) {
                cur.scoutTotalWindows
            } else {
                LookaheadWindowPlanner.buildSequentialPreGenWindows(durSec.coerceAtLeast(60)).size
            }
            val computedCompleted = completedWindowHint ?: (summary.releasedFraction * fallbackTotal).toInt()
                .coerceIn(0, fallbackTotal)
            cur.copy(
                scoutedUpToMs = maxOf(summary.continuousVerifiedUpToMs, summary.continuousReleasedUpToMs),
                bufferedWindowRanges = summary.bufferedRanges,
                scoutCompletedWindows = if (summary.isFullyVerified) fallbackTotal else computedCompleted.coerceAtMost((fallbackTotal - 1).coerceAtLeast(0)),
                scoutTotalWindows = fallbackTotal,
                scoutFailedSliceCount = summary.failedIntervalCount,
                coverageSummary = summary,
                transcriptDiagnostics = diagnostics,
                readinessSnapshot = evaluatedReadiness,
                sentinelSnapshot = sentinelSnap,
                releaseSnapshot = releaseSnap,
                humanReadiness = HumanReadableStatus.resolve(
                    project = cur.project,
                    scoutState = cur.scoutCaptionState,
                    coverageSummary = summary,
                    readinessSnapshot = evaluatedReadiness,
                    releaseSnapshot = releaseSnap,
                    sentinelSnapshot = sentinelSnap,
                ),
            )
        }
    }

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (_uiState.value.project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val rSnap = readinessController.snapshot()
                    if (isPlaying && (rSnap.isBufferingHoldActive || rSnap.userIntent == UserPlaybackIntent.PAUSE)) {
                        exoPlayer.pause()
                        playbackClockAnchor.updatePlaybackState(
                            positionMs = pos,
                            speed = exoPlayer.playbackParameters.speed,
                            playing = false,
                        )
                        _uiState.update { it.copy(isPlaying = false, readinessSnapshot = rSnap) }
                        return
                    }
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
                    val (balloons, activeChapter) = computeContextAwareLayout(
                        project = updatedProject,
                        timeMs = state.currentTimeMs,
                        styleSettings = style,
                        liveFaces = currentLiveFaces,
                        displayMode = state.displayMode,
                    )
                    state.copy(
                        styleSettings = style,
                        project = updatedProject,
                        placedBalloons = balloons,
                        activeChapter = activeChapter,
                    )
                }
            }
        }

        // Always keep Balloons ON as the core DialogueLens default on startup/stream entry
        viewModelScope.launch {
            projectStore.displayModeFlow.collect { rawMode ->
                val mode = rawMode.normalizedPrimaryMode()
                if (mode != CaptionDisplayMode.OFF && !_uiState.value.showStartLauncher && !_uiState.value.hasPendingPlayIntro) {
                    _uiState.update { state ->
                        val (balloons, activeChapter) = computeContextAwareLayout(
                            project = state.project,
                            timeMs = state.currentTimeMs,
                            styleSettings = state.styleSettings,
                            liveFaces = currentLiveFaces,
                            displayMode = mode,
                        )
                        state.copy(
                            displayMode = mode,
                            placedBalloons = balloons,
                            activeChapter = activeChapter,
                        )
                    }
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
                    val (balloons, activeChapter) = computeContextAwareLayout(
                        project = updatedProj,
                        timeMs = state.currentTimeMs,
                        styleSettings = state.styleSettings,
                        liveFaces = currentLiveFaces,
                        displayMode = state.displayMode,
                    )
                    state.copy(
                        characterColorMemory = updatedMemory,
                        project = updatedProj,
                        placedBalloons = balloons,
                        activeChapter = activeChapter,
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
                sentinelCoordinator.updateRuntimeConditions(quotaMode = mode)
                _uiState.update {
                    it.copy(
                        quotaSaverMode = mode,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    )
                }
            }
        }

        viewModelScope.launch {
            projectStore.hasCompletedOnboardingFlow.collect { completed ->
                _uiState.update { it.copy(hasCompletedOnboarding = completed) }
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
                                resumeFromHorizonSec = 0,
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
        releaseStore.updateSeekOrConfigGeneration(
            configFingerprint = currentConfigFingerprint(_uiState.value.project, mode),
        )
        if (mode != QuotaSaverMode.ZERO_CLOUD) {
            wakeScheduler("quota_mode_${mode.name}")
        }
        if (mode == QuotaSaverMode.ZERO_CLOUD) {
            youtubeTranscriptJob?.cancel()
            changeoverPreListenJob?.cancel()
            lookaheadJob?.cancel()
            healBeaconJob?.cancel()
            clearCapturedAudioQueueSync()
            aiTranslationJob?.cancel()
            speakerAnalysisJob?.cancel()
            castDiscoveryJob?.cancel()
            sentinelCoordinator.cancelQueuedCloudWork()
            sentinelCoordinator.cancelOrSupersedeStaleHealTasks(_uiState.value.seekGeneration)
            sentinelCoordinator.updateRuntimeConditions(
                quotaMode = QuotaSaverMode.ZERO_CLOUD,
                isPlaybackBuffering = _uiState.value.readinessSnapshot.isBufferingHoldActive,
                contiguousPlayheadLeadMs = coverageLedger.continuousVerifiedLeadFrom(sentinelCoordinator.currentAcknowledgedViewerPosMs()),
            )
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
                            !state.hasPendingPlayIntro &&
                            !state.readinessSnapshot.diagnostics.isSeekInFlight
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

    fun onMeasuredBubbleSize(eventId: String, normW: Float, normH: Float) {
        if (normW !in 0.05f..0.95f || normH !in 0.03f..0.75f) return
        val prev = measuredBubbleSizes[eventId]
        if (prev != null &&
            kotlin.math.abs(prev.first - normW) < 0.015f &&
            kotlin.math.abs(prev.second - normH) < 0.015f
        ) {
            return
        }
        if (measuredBubbleSizes.size > 160) {
            measuredBubbleSizes.clear()
        }
        measuredBubbleSizes[eventId] = normW to normH
    }

    fun completeOnboarding() {
        _uiState.update { it.copy(hasCompletedOnboarding = true) }
        viewModelScope.launch {
            projectStore.saveOnboardingCompleted(true)
        }
    }

    fun onRenderedBalloonFrame(
        cueKey: String,
        leftPx: Float,
        topPx: Float,
        widthPx: Float,
        heightPx: Float,
        stageW: Float,
        stageH: Float,
    ) {
        if (stageW <= 0f || stageH <= 0f) return
        layoutEngine.stabilityDiagnostics.recordRenderedFrame(
            cueKey = cueKey,
            renderedLeftPx = leftPx,
            renderedTopPx = topPx,
            widthPx = widthPx,
            heightPx = heightPx,
            containerWidthPx = stageW,
            containerHeightPx = stageH,
        )
    }

    fun onBalloonEntryStarted(cueKey: String) {
        layoutEngine.stabilityDiagnostics.recordEntryAnimationStarted(cueKey)
    }

    /**
     * Viewport resize, orientation change, or font-scale change invalidates ONLY cached layout
     * coordinates and measured bubble dimensions — never discarding dialogue, speakers, or reference tracks.
     */
    fun onViewportOrFontScaleChanged(widthPx: Int, heightPx: Int, fontScale: Float) {
        if (widthPx <= 0 || heightPx <= 0 || fontScale <= 0f) return
        measuredBubbleSizes.clear()
        layoutEngine.invalidateLayoutCacheForViewportOrStyleChange()
        releaseStore.invalidateLayoutCacheOnly()
        _uiState.update {
            it.copy(
                measurementEpoch = it.measurementEpoch + 1L,
                releaseSnapshot = releaseStore.snapshot(),
            )
        }
        recomputeBalloons()
    }

    fun resetVisualStabilityDiagnostics() {
        layoutEngine.stabilityDiagnostics.reset()
        _uiState.update {
            it.copy(visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot())
        }
    }

    fun retryBufferingReadiness() {
        val snap = readinessController.onUserRetryBuffering()
        _uiState.update { cur ->
            cur.copy(
                readinessSnapshot = snap,
                humanReadiness = HumanReadableStatus.resolve(
                    project = cur.project,
                    scoutState = cur.scoutCaptionState,
                    coverageSummary = cur.coverageSummary,
                    readinessSnapshot = snap,
                    releaseSnapshot = cur.releaseSnapshot,
                    sentinelSnapshot = cur.sentinelSnapshot,
                ),
            )
        }
        if (_uiState.value.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
            retryYouTubeAiTranscription()
        }
    }

    fun inspectChapterNow(chapterId: String) {
        val cur = _uiState.value
        val record = sentinelCoordinator.requestManualChapterInspection(cur.project, chapterId) ?: return
        val check = sentinelCoordinator.runLocalChapterBoundaryCheck(
            project = cur.project,
            chapterId = chapterId,
            ledger = coverageLedger,
        )
        if (check.hasDefects && cur.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD) {
            sentinelCoordinator.markChapterRepairInProgress(chapterId)
            val (updatedProj, _) = HealBeaconEngine.placeOrMergeBeacon(
                project = cur.project,
                timestampMs = record.boundaryMs,
                scope = HealScope.QUICK,
                autoTriggered = true,
                patrolChapterId = chapterId,
            )
            updateProject(updatedProj)
            enqueueHealBeaconProcessing()
        }
        _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
    }

    private fun maybeRunProactiveChapterPatrol(timeMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastPatrolSweepAtMs < 6_000L) return
        lastPatrolSweepAtMs = now
        val state = _uiState.value
        if (state.showStartLauncher || state.project.chapters.isEmpty()) return
        if (sentinelCoordinator.isScrubberPreviewActive()) return
        val sched = sentinelCoordinator.schedulerSnapshot()
        if (sched.isTemporarilyIdle && sched.outcomeState != SchedulerOutcomeState.ACTIVE) return
        val anchorMs = sentinelCoordinator.currentAcknowledgedViewerPosMs()
        val leadMs = coverageLedger.continuousVerifiedLeadFrom(anchorMs)
        sentinelCoordinator.updateRuntimeConditions(
            quotaMode = state.quotaSaverMode,
            isPlaybackBuffering = state.readinessSnapshot.isBufferingHoldActive || state.isCinemaIntroActive,
            contiguousPlayheadLeadMs = leadMs,
        )
        if (sentinelCoordinator.isPatrolSuspendedForPlayback()) {
            _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
            return
        }
        // Only reveal/queue chapter patrol tasks that now fall inside the active 3-minute boundary
        sentinelCoordinator.planDefaultChapterPatrol(state.project)
        val claimed = sentinelCoordinator.claimNextWork(SentinelWorkforceCoordinator.WORKER_1_ID, now)
        if (claimed != null) {
            val (item, res) = claimed
            try {
                if (item.kind == SentinelTaskKind.CHAPTER_BOUNDARY_PATROL && item.chapterId != null) {
                    val result = sentinelCoordinator.runLocalChapterBoundaryCheck(
                        project = state.project,
                        chapterId = item.chapterId,
                        ledger = coverageLedger,
                        nowEpochMs = now,
                    )
                    if (result.hasDefects && state.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD) {
                        val decision = sentinelCoordinator.clipOrDeferToBoundary(
                            startMs = result.windowStartMs,
                            endMs = result.windowEndMs,
                            roundingUpToSec = true,
                        )
                        if (decision is BoundarySubmissionDecision.Allowed) {
                            sentinelCoordinator.markChapterRepairInProgress(item.chapterId, now)
                            val (updatedProj, _) = HealBeaconEngine.placeOrMergeBeacon(
                                project = state.project,
                                timestampMs = result.boundaryMs,
                                scope = HealScope.QUICK,
                                autoTriggered = true,
                                patrolChapterId = item.chapterId,
                                nowEpochMs = now,
                            )
                            updateProject(updatedProj)
                            enqueueHealBeaconProcessing()
                        }
                    }
                }
            } finally {
                sentinelCoordinator.markWorkerIdle(SentinelWorkforceCoordinator.WORKER_1_ID, res.reservationId)
            }
        }
        _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
    }

    private fun maybeDispatchChangeoverPreListenScout(timeMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastChangeoverPreListenSweepAtMs < 3_000L) return
        lastChangeoverPreListenSweepAtMs = now

        val state = _uiState.value
        if (state.showStartLauncher || state.isCinemaIntroActive) return
        if (!state.dualInstanceEnabled) return
        if (state.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped) return
        if (sentinelCoordinator.isScrubberPreviewActive()) return
        val sched = sentinelCoordinator.schedulerSnapshot()
        if (sched.isTemporarilyIdle && sched.outcomeState != SchedulerOutcomeState.ACTIVE) return
        if (sched.pausedDeadlineReached) return
        val proj = state.project
        val ytId = proj.videoSource.youtubeVideoId
        if (proj.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT || ytId.isNullOrBlank()) return
        if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(proj.subtitleSource, proj.dialogueEvents)) return
        if (changeoverPreListenJob?.isActive == true) return

        val anchorMs = sentinelCoordinator.currentAcknowledgedViewerPosMs()
        val leadMs = coverageLedger.continuousVerifiedLeadFrom(anchorMs)
        sentinelCoordinator.updateRuntimeConditions(
            quotaMode = state.quotaSaverMode,
            isPlaybackBuffering = state.readinessSnapshot.isBufferingHoldActive || state.isCinemaIntroActive,
            contiguousPlayheadLeadMs = leadMs,
        )
        sentinelCoordinator.planUpcomingChangeoverPreListens(
            project = proj,
            playheadMs = anchorMs,
            nowEpochMs = now,
        )
        val claimed = sentinelCoordinator.claimChangeoverPreListenWork(
            workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
            nowEpochMs = now,
        )
        if (claimed == null) {
            _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
            return
        }

        val (item, reservation) = claimed
        val boundaryDecision = sentinelCoordinator.clipOrDeferToBoundary(
            startMs = item.startMs,
            endMs = item.endMs,
            overlapMs = 0L,
            roundingUpToSec = true,
        )
        if (boundaryDecision !is BoundarySubmissionDecision.Allowed) {
            sentinelCoordinator.markWorkerIdle(SentinelWorkforceCoordinator.WORKER_2_ID, reservation.reservationId)
            _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
            return
        }
        val requestCtx = captureWorkRequestContext(proj, state.quotaSaverMode)
        val sessionToken = requestCtx.sessionToken
        val projectId = requestCtx.mediaId
        _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }

        changeoverPreListenJob = viewModelScope.launch {
            var didRepair = false
            try {
                val startSec = (boundaryDecision.startMs / 1000L).toInt().coerceAtLeast(0)
                val maxEndSec = (sentinelCoordinator.currentBoundaryEndMs() / 1000L).toInt()
                val endSec = ((boundaryDecision.endMs + 999L) / 1000L).toInt()
                    .coerceAtMost(maxEndSec)
                if (endSec <= startSec) return@launch
                val initialTier = if (_uiState.value.quotaSaverMode == QuotaSaverMode.QUOTA_SAVER || downgradedToLiteForStream) {
                    ScoutExecutionTier.LITE_AI
                } else {
                    ScoutExecutionTier.BEST_AI
                }
                val mergedGlossary = (_uiState.value.project.glossary + _uiState.value.glossaryEntries)
                    .distinctBy { it.term.trim().lowercase() }

                val seamExecStartMs = System.currentTimeMillis()
                val segOutcome = segmentExecutor.executeSegmentDetailed(
                    startSec = startSec,
                    endSec = endSec,
                    initialTier = initialTier,
                    onStatusUpdate = {},
                    onDowngradedToLite = { downgradedToLiteForStream = true },
                    canInvokeProvider = { sliceStartSec, sliceEndSec, _ ->
                        canInvokeCloudAi(
                            ctx = requestCtx,
                            sliceStartMs = sliceStartSec * 1000L,
                            sliceEndMs = sliceEndSec * 1000L,
                            enforceBoundary = true,
                            requireUnloadedTracks = true,
                        )
                    },
                ) { sliceStart, sliceEnd, preferLite, clarificationRetry ->
                    val snapProj = _uiState.value.project
                    translationAiService.transcribeYouTubeVideoWindow(
                        videoId = ytId,
                        windowStartSec = sliceStart,
                        windowEndSec = sliceEnd,
                        totalDurationSec = (snapProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60),
                        sourceLanguage = snapProj.sourceLanguage,
                        targetLanguage = snapProj.targetLanguage,
                        videoContext = buildVideoContextString(snapProj),
                        knownSpeakers = snapProj.speakers,
                        glossary = mergedGlossary,
                        preferLiteModel = preferLite,
                        timestampClarificationRetry = clarificationRetry,
                    )
                }

                if (!isRequestContextCurrent(requestCtx)) return@launch
                when (segOutcome) {
                    is SegmentExecutionOutcome.QuotaExhausted -> {
                        sentinelCoordinator.markChangeoverPreListenFailed(item.id)
                        sentinelCoordinator.recordQuotaError()
                    }
                    is SegmentExecutionOutcome.Completed -> {
                        lookaheadMutex.withLock {
                            if (!isRequestContextCurrent(requestCtx)) return@withLock
                            val liveProj = _uiState.value.project
                            if (liveProj.id != projectId ||
                                LookaheadWindowPlanner.hasLoadedSubtitlesLocked(liveProj.subtitleSource, liveProj.dialogueEvents)
                            ) {
                                return@withLock
                            }

                            var workingProj = liveProj
                            val (reconciledCues, reconciledUpdates, _) = reconcileWindowOutcomeSpeakersAndCues(
                                existingSpeakers = workingProj.speakers,
                                cues = segOutcome.cues,
                                speakerUpdates = segOutcome.speakerUpdates,
                            )
                            if (reconciledUpdates.isNotEmpty() || segOutcome.summary.isNotBlank()) {
                                val (castMerged, updatedMem) = mergeCastSlotAssignments(
                                    project = workingProj,
                                    assignments = reconciledUpdates,
                                    rememberedColors = _uiState.value.characterColorMemory,
                                    reconcileColors = attributionEngine::reconcileCharacterColors,
                                    summary = segOutcome.summary,
                                    discoveryTimestampMs = item.startMs,
                                )
                                if (updatedMem != _uiState.value.characterColorMemory) {
                                    persistCharacterColorMemory(updatedMem)
                                }
                                workingProj = castMerged
                            }

                            val seamStartMs = startSec * 1000L
                            val seamEndMs = (endSec * 1000L).coerceAtLeast(seamStartMs + 1_000L)
                            if (reconciledCues.isNotEmpty()) {
                                val validSlots = setOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")
                                val hintMap = mutableMapOf<String, AiTranslatedLine>()
                                val incomingEvents = reconciledCues.map { cue ->
                                    val eventId = SubtitleParser.newEventId()
                                    val isOffOrNonSpoken = cue.dialogueType != DialogueType.SPOKEN
                                    val spatialAnchor = if (!isOffOrNonSpoken && cue.anchorX != null && cue.anchorY != null) {
                                        NormalizedPoint(cue.anchorX, cue.anchorY)
                                    } else {
                                        null
                                    }
                                    val slotUpper = cue.suggestedSpeakerSlot.trim().uppercase()
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
                                    DialogueEvent(
                                        id = eventId,
                                        startTimeMs = cue.startMs,
                                        endTimeMs = cue.endMs,
                                        sourceText = cue.sourceText,
                                        translatedText = cue.naturalTranslation,
                                        literalText = cue.literalTranslation,
                                        romanization = cue.romanization,
                                        speakerId = null,
                                        speakerConfidence = if (slotUpper in validSlots) 0.86f else 0.44f,
                                        faceTrackId = null,
                                        faceConfidence = if (spatialAnchor != null) 0.78f else 0f,
                                        shotId = workingProj.shots.firstOrNull { cue.startMs in it.startMs..it.endMs }?.id ?: "shot_1",
                                        dialogueType = cue.dialogueType,
                                        anchorPosition = spatialAnchor,
                                        placementMode = when {
                                            isOffOrNonSpoken -> PlacementMode.EDGE_DOCKED
                                            spatialAnchor != null -> PlacementMode.AUTO
                                            else -> PlacementMode.NEUTRAL_FALLBACK
                                        },
                                        contextCueReason = cue.contextReason,
                                        culturalNote = cue.culturalNote,
                                        preferredDeadSpaceZone = cue.deadSpaceZone,
                                        avoidBoxes = cue.avoidBoxes,
                                    )
                                }

                                val pacedSeam = SubtitleParser.applyReadingPacingGuardrails(incomingEvents)
                                    .filter { it.startTimeMs in seamStartMs until seamEndMs }
                                    .map { evt ->
                                        val safeMinEnd = minOf(evt.startTimeMs + 350L, seamEndMs)
                                        evt.copy(endTimeMs = evt.endTimeMs.coerceIn(safeMinEnd, maxOf(safeMinEnd, seamEndMs)))
                                    }
                                val priorExisting = workingProj.dialogueEvents.filter { it.startTimeMs < seamStartMs }
                                val antiEchoedSeam = LookaheadWindowPlanner.filterEchoedOpeningCues(
                                    existing = priorExisting,
                                    incoming = pacedSeam,
                                    windowStartMs = seamStartMs,
                                )
                                if (antiEchoedSeam.isNotEmpty()) {
                                    // Unfreeze ONLY cues strictly inside this changeover seam slice; keep all other cues frozen
                                    val replacedIds = workingProj.dialogueEvents
                                        .filter { it.startTimeMs in seamStartMs until seamEndMs }
                                        .map { it.id }
                                        .toSet()
                                    settledAttributedEventIds.removeAll(replacedIds)

                                    val mergeReport = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
                                        existing = workingProj.dialogueEvents,
                                        incoming = antiEchoedSeam,
                                        windowStartMs = seamStartMs,
                                        windowEndMs = seamEndMs,
                                    )
                                    cachedAiTurnHints.putAll(hintMap)
                                    antiEchoedSeam.forEach { autoAnalyzedEventIds.add(it.id) }

                                    val sceneBoundaries = workingProj.chapters
                                        .map { it.startMs }
                                        .filter { it > 0L }
                                        .sorted()
                                    val slotMap = buildSlotToSpeakerIdMap(workingProj.speakers)
                                    val (attributedSpeakers, attributedEvents) = attributionEngine.assignSpeakers(
                                        events = mergeReport.events,
                                        shots = workingProj.shots,
                                        faceTracks = emptyList(),
                                        existingSpeakers = workingProj.speakers,
                                        turnHints = cachedAiTurnHints,
                                        rememberedCharacterColors = _uiState.value.characterColorMemory,
                                        settledEventIds = settledAttributedEventIds,
                                        sceneBoundaryMs = sceneBoundaries,
                                        slotToSpeakerId = slotMap,
                                    )
                                    val finalEvents = applyCutawayOffScreenSideMemory(
                                        events = attributedEvents,
                                        speakers = attributedSpeakers,
                                        sceneBoundaryMs = sceneBoundaries,
                                    )
                                    finalEvents.forEach { evt ->
                                        if (evt.speakerId != null || evt.attributionSummary == "Unidentified speaker") {
                                            settledAttributedEventIds.add(evt.id)
                                        }
                                    }
                                    val seamEvents = finalEvents.filter { it.startTimeMs in seamStartMs until seamEndMs }
                                    val (reviewOutcome, releaseSnap) = stageAndReviewIntervalThroughReleaseGate(
                                        project = workingProj.copy(speakers = attributedSpeakers, dialogueEvents = finalEvents),
                                        intervalId = "seam_${seamStartMs}_${seamEndMs}",
                                        startMs = seamStartMs,
                                        endMs = seamEndMs,
                                        events = seamEvents,
                                        speakers = attributedSpeakers,
                                        sessionToken = requestCtx.sessionToken,
                                        seekGeneration = requestCtx.seekGeneration,
                                        hasIndependentAudioOrFrameEvidence = false,
                                        elapsedProcessingMs = (System.currentTimeMillis() - seamExecStartMs).coerceAtLeast(1L),
                                        expectedConfigFingerprint = requestCtx.configFingerprint,
                                    )
                                    if (reviewOutcome != null) {
                                        val mergedReviewed = mergeReleasedIntervalPreservingLocks(
                                            existingEvents = finalEvents,
                                            releasedIntervalEvents = reviewOutcome.repairedEvents,
                                            startMs = seamStartMs,
                                            endMs = seamEndMs,
                                        )
                                        val changedIds = replacedIds + reviewOutcome.repairedEvents.map { it.id }
                                        layoutEngine.invalidateEventsOnly(changedIds)

                                        val updatedProj = workingProj.copy(
                                            subtitleSource = SubtitleSourceKind.AI_DRAFT,
                                            speakers = attributedSpeakers,
                                            dialogueEvents = mergedReviewed,
                                        )
                                        coverageLedger.recordOutcome(
                                            startMs = seamStartMs,
                                            endMs = minOf(updatedProj.videoSource.durationMs.coerceAtLeast(seamEndMs), seamEndMs),
                                            status = reviewOutcome.coverageStatus,
                                            source = SubtitleSourceKind.AI_DRAFT,
                                            dialogueCount = reviewOutcome.repairedEvents.size,
                                        )
                                        updateProject(updatedProj)
                                        _uiState.update { it.copy(releaseSnapshot = releaseSnap) }
                                        persistYouTubeVideoCache(
                                            updatedProj,
                                            coverageLedger.continuousVerifiedUpToMs(),
                                            sessionToken,
                                        )
                                        didRepair = true
                                    }
                                }
                            } else if (segOutcome.verifiedSilentRanges.isNotEmpty() && segOutcome.failedRanges.isEmpty()) {
                                val (reviewedSilence, silenceSnap) = stageAndReviewIntervalThroughReleaseGate(
                                    project = workingProj,
                                    intervalId = "seam_${seamStartMs}_${seamEndMs}",
                                    startMs = seamStartMs,
                                    endMs = seamEndMs,
                                    events = emptyList(),
                                    speakers = workingProj.speakers,
                                    sessionToken = requestCtx.sessionToken,
                                    seekGeneration = requestCtx.seekGeneration,
                                    isConfirmedSilence = true,
                                    hasIndependentAudioOrFrameEvidence = false,
                                    elapsedProcessingMs = (System.currentTimeMillis() - seamExecStartMs).coerceAtLeast(1L),
                                    expectedConfigFingerprint = requestCtx.configFingerprint,
                                )
                                if (reviewedSilence != null) {
                                    if (reviewedSilence.repairedEvents.isNotEmpty()) {
                                        val effectiveStatus = if (reviewedSilence.coverageStatus == CoverageStatus.VERIFIED_SILENCE) {
                                            CoverageStatus.RELEASED_FALLBACK
                                        } else {
                                            reviewedSilence.coverageStatus
                                        }
                                        publishReleasedEventsToProject(
                                            startMs = seamStartMs,
                                            endMs = minOf(workingProj.videoSource.durationMs.coerceAtLeast(seamEndMs), seamEndMs),
                                            releasedEvents = reviewedSilence.repairedEvents,
                                            coverageStatus = effectiveStatus,
                                            source = SubtitleSourceKind.AI_DRAFT,
                                            persistYouTubeCacheSessionToken = sessionToken,
                                        )
                                        didRepair = true
                                    } else {
                                        val silenceStatus = if (reviewedSilence.coverageStatus == CoverageStatus.SUSPECTED_MISSING_DIALOGUE) {
                                            CoverageStatus.SUSPECTED_MISSING_DIALOGUE
                                        } else {
                                            CoverageStatus.VERIFIED_SILENCE
                                        }
                                        coverageLedger.recordOutcome(
                                            startMs = seamStartMs,
                                            endMs = minOf(workingProj.videoSource.durationMs.coerceAtLeast(seamEndMs), seamEndMs),
                                            status = silenceStatus,
                                            source = SubtitleSourceKind.AI_DRAFT,
                                            dialogueCount = 0,
                                        )
                                        _uiState.update { it.copy(releaseSnapshot = silenceSnap) }
                                    }
                                }
                            }

                            sentinelCoordinator.markChangeoverPreListenCompleted(item.id)
                            item.chapterId?.let { chId ->
                                sentinelCoordinator.markChapterRepairVerified(
                                    chId,
                                    if (didRepair) "Pre-listened & stabilized changeover ahead of playback" else "Pre-listened clean",
                                )
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                sentinelCoordinator.markChangeoverPreListenFailed(item.id)
                throw ce
            } catch (_: Throwable) {
                sentinelCoordinator.markChangeoverPreListenFailed(item.id)
            } finally {
                sentinelCoordinator.markWorkerIdle(
                    workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
                    reservationId = reservation.reservationId,
                    repairCompleted = didRepair,
                )
                _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
            }
        }
    }

    private fun computeContextAwareLayout(
        project: DialogueLensProject,
        timeMs: Long,
        styleSettings: BalloonStyleSettings,
        liveFaces: List<LiveFaceObservation> = currentLiveFaces,
        displayMode: CaptionDisplayMode = _uiState.value.displayMode,
    ): Pair<List<PlacedBalloon>, VideoChapter?> {
        val primaryMode = displayMode.normalizedPrimaryMode()
        val ctx = contextEngine.onPlaybackPosition(project, timeMs)
        val balloons = layoutEngine.computeLayout(
            project = project,
            currentMs = timeMs,
            styleSettings = styleSettings,
            liveFaces = liveFaces,
            sceneEpoch = ctx.sceneMemory.sceneEpoch,
            hasLiveVisionSignal = ctx.sceneMemory.hasLiveVisionSignal,
            liveFrameObservedEmpty = ctx.sceneMemory.liveFrameObservedEmpty,
            activeChapterId = ctx.chapterMemory.activeChapter?.id,
            measuredNormalizedSizes = measuredBubbleSizes,
            fixedZoneTaillessMode = !primaryMode.shouldShowTails,
            displayMode = primaryMode,
            detectorFrameId = lastDetectorFrameId,
        )
        return balloons to ctx.chapterMemory.activeChapter
    }

    private fun updateVisualPresentationOnly(targetMs: Long) {
        _uiState.update { state ->
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = state.project,
                timeMs = targetMs,
                styleSettings = state.styleSettings,
                liveFaces = currentLiveFaces,
                displayMode = state.displayMode,
            )
            state.copy(
                placedBalloons = balloons,
                activeChapter = activeChapter,
                visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
            )
        }
    }

    private fun updateTimeAndLayout(timeMs: Long) {
        var latestBalloons: List<PlacedBalloon> = emptyList()
        val preState = _uiState.value
        val readinessSnap = executePendingAutoResumeIfNeeded(
            readinessController.onPlayerPositionSample(
                reportedPositionMs = timeMs,
                playerIsPlaying = preState.isPlaying,
                ledger = coverageLedger,
            )
        )
        val hasPendingSeekAck = readinessSnap.diagnostics.isSeekInFlight
        if (!hasPendingSeekAck) {
            val woke = sentinelCoordinator.updateAcknowledgedViewerPosition(
                positionMs = timeMs,
                durationMs = preState.project.videoSource.durationMs,
                isPlaying = preState.isPlaying,
                playbackSpeed = preState.playbackSpeed,
                seekGeneration = preState.seekGeneration,
            )
            if (woke) {
                wakeScheduler("playback_advance")
            }
        }
        val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
        if (preState.project.videoSource.kind == VideoSourceKind.LOCAL_MP4 &&
            (readinessSnap.isBufferingHoldActive || readinessSnap.state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR) &&
            exoPlayer.isPlaying
        ) {
            exoPlayer.pause()
            playbackClockAnchor.reset(timeMs, preState.playbackSpeed, playing = false)
        }
        val resolvedPlaying = if (readinessSnap.isBufferingHoldActive ||
            readinessSnap.state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR ||
            readinessSnap.userIntent == UserPlaybackIntent.PAUSE
        ) {
            false
        } else {
            preState.isPlaying
        }
        _uiState.update { state ->
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = state.project,
                timeMs = timeMs,
                styleSettings = state.styleSettings,
                liveFaces = currentLiveFaces,
                displayMode = state.displayMode,
            )
            latestBalloons = balloons
            val readiness = HumanReadableStatus.resolve(
                project = state.project,
                scoutState = state.scoutCaptionState,
                coverageSummary = state.coverageSummary,
                readinessSnapshot = readinessSnap,
                releaseSnapshot = state.releaseSnapshot,
                sentinelSnapshot = sentinelSnap,
            )
            val refCues = releaseStore.currentReferenceTrack()?.cues.orEmpty()
            val nextCcState = if (
                state.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT &&
                (state.youtubeNativeCcState == YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE ||
                    state.youtubeNativeCcState == YouTubeNativeCcState.ENABLED_ACTIVE_CUES) &&
                (refCues.isNotEmpty() || state.project.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK)
            ) {
                val hasCueNow = refCues.any { timeMs in (it.startMs - 400L)..(it.endMs + 400L) } ||
                    (state.project.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK &&
                        state.project.dialogueEvents.any {
                            it.sourceText.isNotBlank() &&
                                !it.sourceText.startsWith("Embedded YouTube Player active") &&
                                timeMs in (it.startTimeMs - 400L)..(it.endTimeMs + 400L)
                        })
                if (hasCueNow) YouTubeNativeCcState.ENABLED_ACTIVE_CUES else YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE
            } else {
                state.youtubeNativeCcState
            }
            state.copy(
                currentTimeMs = timeMs,
                isPlaying = resolvedPlaying,
                isScoutCatchUpBuffering = readinessSnap.isBufferingHoldActive,
                placedBalloons = balloons,
                activeChapter = activeChapter,
                youtubeNativeCcState = nextCcState,
                humanReadiness = readiness,
                readinessSnapshot = readinessSnap,
                sentinelSnapshot = sentinelSnap,
                visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
            )
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDeadlineCheckAtMs >= 1_500L) {
            lastDeadlineCheckAtMs = nowMs
            val fallbacks = releaseStore.applyDeadlineFallbackIfDue(
                nowEpochMs = nowMs,
                playheadMs = timeMs,
            )
            if (fallbacks.isNotEmpty()) {
                val curProj = _uiState.value.project
                val canPublishFallback = !LookaheadWindowPlanner.hasLoadedSubtitlesLocked(
                    curProj.subtitleSource,
                    curProj.dialogueEvents,
                )
                fallbacks.forEach { fb ->
                    if (canPublishFallback && fb.events.isNotEmpty()) {
                        publishReleasedEventsToProject(
                            startMs = fb.startMs,
                            endMs = fb.endMs,
                            releasedEvents = fb.events,
                            coverageStatus = CoverageStatus.RELEASED_FALLBACK,
                            source = curProj.subtitleSource,
                            failureReason = "DEADLINE_FALLBACK_UNVERIFIED",
                        )
                    } else {
                        coverageLedger.recordOutcome(
                            startMs = fb.startMs,
                            endMs = fb.endMs,
                            status = CoverageStatus.RELEASED_FALLBACK,
                            source = curProj.subtitleSource,
                            dialogueCount = fb.events.size,
                            failureReason = "DEADLINE_FALLBACK_UNVERIFIED",
                        )
                    }
                }
                sentinelCoordinator.updatePendingReviewCount(releaseStore.pendingDraftCount())
                _uiState.update { it.copy(releaseSnapshot = releaseStore.snapshot()) }
                syncCoverageUiState()
            }
        }
        maybeTriggerRollingWindowEnhancement(timeMs)
        maybePersistPlaybackProgress(timeMs)
        maybeRunAutomaticSelfHealCheck(timeMs, latestBalloons)
        maybeRunProactiveChapterPatrol(timeMs)
        maybeDispatchChangeoverPreListenScout(timeMs)
    }

    private fun recomputeBalloons() {
        _uiState.update { state ->
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = state.project,
                timeMs = state.currentTimeMs,
                styleSettings = state.styleSettings,
                liveFaces = currentLiveFaces,
                displayMode = state.displayMode,
            )
            state.copy(
                placedBalloons = balloons,
                activeChapter = activeChapter,
                visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
                humanReadiness = HumanReadableStatus.resolve(
                    project = state.project,
                    scoutState = state.scoutCaptionState,
                    coverageSummary = state.coverageSummary,
                    readinessSnapshot = state.readinessSnapshot,
                    releaseSnapshot = state.releaseSnapshot,
                    sentinelSnapshot = state.sentinelSnapshot,
                ),
            )
        }
    }

    fun onLiveStageFrameSnapshot(timeMs: Long, snapshot: LiveStageFrameSnapshot) {
        val state = _uiState.value
        if (state.showStartLauncher || state.isCinemaIntroActive || state.isEditMode) return
        // Reject delayed frame-analysis callbacks from a prior project or prior seek generation
        if (snapshot.projectId.isNotBlank() && snapshot.projectId != state.project.id) return
        if (snapshot.seekGeneration > 0L && snapshot.seekGeneration != state.seekGeneration) return
        lastDetectorFrameId = if (snapshot.observationSeq > 0L) snapshot.observationSeq else lastDetectorFrameId + 1L

        val ctx = contextEngine.onLiveFrameSnapshot(snapshot, timeMs)
        // Only confirmed visual cuts / sceneEpoch transitions reset spatial side memory;
        // chapter markers alone NEVER reset speaker identities or spatial side memory.
        val sceneResetKey = "scene:${ctx.sceneMemory.sceneEpoch}"
        val didCrossSceneBoundary = snapshot.visualCutDetected ||
            (lastSceneSideResetKey.isNotEmpty() && lastSceneSideResetKey != sceneResetKey)
        if (snapshot.visualCutDetected) {
            layoutEngine.onVisualCutBoundary()
        }
        lastSceneSideResetKey = sceneResetKey
        val faces = snapshot.faces
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
            } else if ((snapshot.projectId.isNotBlank() && snapshot.projectId != cur.project.id) ||
                (snapshot.seekGeneration > 0L && snapshot.seekGeneration != cur.seekGeneration)
            ) {
                cur
            } else {
                // Reset spatial side memory per scene so seating in Scene 1 never pulls Scene 2 cues
                val sceneProject = if (didCrossSceneBoundary && cur.project.speakers.any { it.preferredScreenSideX != null }) {
                    cur.project.copy(
                        speakers = cur.project.speakers.map { it.copy(preferredScreenSideX = null) }
                    )
                } else {
                    cur.project
                }
                cur.copy(
                    project = sceneProject,
                    liveFaceExclusionRects = liveExclusions,
                    placedBalloons = layoutEngine.computeLayout(
                        project = sceneProject,
                        currentMs = cur.currentTimeMs,
                        styleSettings = cur.styleSettings,
                        liveFaces = faces,
                        sceneEpoch = ctx.sceneMemory.sceneEpoch,
                        hasLiveVisionSignal = ctx.sceneMemory.hasLiveVisionSignal,
                        liveFrameObservedEmpty = ctx.sceneMemory.liveFrameObservedEmpty,
                        activeChapterId = ctx.chapterMemory.activeChapter?.id,
                        measuredNormalizedSizes = measuredBubbleSizes,
                        fixedZoneTaillessMode = !cur.displayMode.normalizedPrimaryMode().shouldShowTails,
                        displayMode = cur.displayMode.normalizedPrimaryMode(),
                        detectorFrameId = lastDetectorFrameId,
                    ),
                    visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
                )
            }
        }
    }

    fun onLiveStageFacesDetected(timeMs: Long, faces: List<LiveFaceObservation>) {
        onLiveStageFrameSnapshot(
            timeMs = timeMs,
            snapshot = LiveStageFrameSnapshot(
                faces = faces,
                visualCutDetected = false,
                cutDistance = 0f,
                frameLumaAverage = 0.5f,
                capturedAtMs = System.currentTimeMillis(),
                hasLiveVisionSignal = true,
            ),
        )
    }

    fun selectDestination(destination: AppDestination) {
        when (destination) {
            AppDestination.LIBRARY -> {
                openStartLauncher()
            }
            AppDestination.WATCH -> {
                _uiState.update { cur ->
                    cur.copy(
                        activeDestination = AppDestination.WATCH,
                        showStartLauncher = false,
                        isEditMode = false,
                    )
                }
            }
            AppDestination.STUDIO -> {
                _uiState.update { cur ->
                    cur.copy(
                        activeDestination = AppDestination.STUDIO,
                        showStartLauncher = false,
                        isFullscreenTheater = false,
                    )
                }
            }
        }
    }

    fun toggleDialoguePanelExpanded() {
        _uiState.update { it.copy(isDialoguePanelExpanded = !it.isDialoguePanelExpanded) }
    }

    fun openStartLauncher() {
        persistPlaybackProgressNow()
        pausePlayback()
        introJob?.cancel()
        _uiState.update { cur ->
            cur.copy(
                activeDestination = AppDestination.LIBRARY,
                showStartLauncher = true,
                isFullscreenTheater = false,
                isCinemaIntroActive = false,
                introRemainingMs = 0L,
            )
        }
    }

    fun dismissScoutCatchUpBuffering() {
        val state = _uiState.value
        val bypassSnap = readinessController.onUserWatchNowBypass()
        sentinelCoordinator.onViewerPauseChanged(
            paused = false,
            acknowledgedPositionMs = state.currentTimeMs,
            durationMs = state.project.videoSource.durationMs,
        )
        if (!state.isScoutCatchUpBuffering && !state.readinessSnapshot.isBufferingHoldActive) {
            _uiState.update { it.copy(readinessSnapshot = bypassSnap) }
            return
        }
        val kind = state.project.videoSource.kind
        when (kind) {
            VideoSourceKind.LOCAL_MP4 -> {
                _uiState.update {
                    it.copy(
                        isScoutCatchUpBuffering = false,
                        isPlaying = true,
                        readinessSnapshot = bypassSnap,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    )
                }
                playbackClockAnchor.reset(state.currentTimeMs, state.playbackSpeed, playing = true)
                exoPlayer.play()
            }
            VideoSourceKind.YOUTUBE_COMPLIANT,
            VideoSourceKind.WEB_STREAM -> {
                val cmdSeq = ++nextStreamCommandSeq
                playbackClockAnchor.reset(state.currentTimeMs, state.playbackSpeed, playing = true)
                _uiState.update {
                    it.copy(
                        isScoutCatchUpBuffering = false,
                        isPlaying = true,
                        streamPlayRequest = cmdSeq to true,
                        readinessSnapshot = bypassSnap,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    )
                }
            }
            VideoSourceKind.DEMO_SCENARIO -> {
                _uiState.update {
                    it.copy(
                        isScoutCatchUpBuffering = false,
                        isPlaying = true,
                        readinessSnapshot = bypassSnap,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    )
                }
            }
        }
    }

    fun togglePlayPause() {
        val state = _uiState.value
        if (state.quickPickerEventId != null) {
            _uiState.update { it.copy(quickPickerEventId = null, resumeAfterQuickPicker = false) }
        }
        if (state.isCinemaIntroActive || state.isScoutCatchUpBuffering || state.readinessSnapshot.isBufferingHoldActive) {
            if (state.readinessSnapshot.userIntent == UserPlaybackIntent.PLAY) {
                introJob?.cancel()
                pausePlayback()
                _uiState.update {
                    it.copy(
                        isCinemaIntroActive = false,
                        introRemainingMs = 0L,
                        isOpeningSpeakersReady = false,
                    )
                }
            } else {
                val playSnap = executePendingAutoResumeIfNeeded(
                    readinessController.onUserPlayRequested(state.currentTimeMs, coverageLedger)
                )
                sentinelCoordinator.onViewerPauseChanged(
                    paused = false,
                    acknowledgedPositionMs = state.currentTimeMs,
                    durationMs = state.project.videoSource.durationMs,
                )
                wakeScheduler("user_play_during_hold")
                _uiState.update {
                    it.copy(
                        isScoutCatchUpBuffering = playSnap.isBufferingHoldActive,
                        readinessSnapshot = playSnap,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    )
                }
            }
            return
        }
        val kind = state.project.videoSource.kind
        if (!state.isPlaying &&
            state.hasPendingPlayIntro &&
            state.dualInstanceEnabled &&
            kind != VideoSourceKind.DEMO_SCENARIO
        ) {
            val playSnap = readinessController.onUserPlayRequested(state.currentTimeMs, coverageLedger)
            sentinelCoordinator.onViewerPauseChanged(
                paused = false,
                acknowledgedPositionMs = state.currentTimeMs,
                durationMs = state.project.videoSource.durationMs,
            )
            val leadWins = LookaheadWindowPlanner.recommendedOpeningLeadWindows(state.project.videoSource.durationMs)
            val initStatus = when (kind) {
                VideoSourceKind.LOCAL_MP4 ->
                    "Preparing opening speech & speakers..."
                VideoSourceKind.YOUTUBE_COMPLIANT -> if (leadWins > 1) {
                    "Preparing opening scenes for smoother playback..."
                } else {
                    "Preparing opening dialogue & speakers..."
                }
                VideoSourceKind.WEB_STREAM ->
                    "Preparing stream dialogue..."
                VideoSourceKind.DEMO_SCENARIO -> ""
            }
            _uiState.update {
                it.copy(
                    hasPendingPlayIntro = false,
                    readinessSnapshot = playSnap,
                    sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                )
            }
            startSevenSecondIntro(
                projectId = state.project.id,
                initialStatus = initStatus,
                requireFullSevenSeconds = false,
                resetScoutProgress = false,
            )
            return
        }
        val nextPlaying = !state.isPlaying
        val nextReadiness = if (nextPlaying) {
            readinessController.onUserPlayRequested(state.currentTimeMs, coverageLedger)
        } else {
            readinessController.onUserPauseRequested(state.currentTimeMs)
        }
        sentinelCoordinator.onViewerPauseChanged(
            paused = !nextPlaying,
            acknowledgedPositionMs = state.currentTimeMs,
            durationMs = state.project.videoSource.durationMs,
        )
        val allowImmediateMediaPlay = nextPlaying &&
            !nextReadiness.isBufferingHoldActive &&
            nextReadiness.state != PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR
        when (kind) {
            VideoSourceKind.LOCAL_MP4 -> {
                if (allowImmediateMediaPlay) exoPlayer.play() else exoPlayer.pause()
                playbackClockAnchor.reset(state.currentTimeMs, state.playbackSpeed, playing = allowImmediateMediaPlay)
                _uiState.update {
                    it.copy(
                        isPlaying = allowImmediateMediaPlay,
                        isScoutCatchUpBuffering = nextReadiness.isBufferingHoldActive,
                        readinessSnapshot = nextReadiness,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    )
                }
            }
            VideoSourceKind.YOUTUBE_COMPLIANT,
            VideoSourceKind.WEB_STREAM -> {
                val cmdSeq = ++nextStreamCommandSeq
                playbackClockAnchor.reset(state.currentTimeMs, state.playbackSpeed, playing = allowImmediateMediaPlay)
                _uiState.update { cur ->
                    cur.copy(
                        isPlaying = allowImmediateMediaPlay,
                        isScoutCatchUpBuffering = nextReadiness.isBufferingHoldActive,
                        streamPlayRequest = cmdSeq to allowImmediateMediaPlay,
                        readinessSnapshot = nextReadiness,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    )
                }
            }
            VideoSourceKind.DEMO_SCENARIO -> {
                _uiState.update {
                    it.copy(
                        isPlaying = nextPlaying,
                        readinessSnapshot = nextReadiness,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    )
                }
            }
        }
    }

    fun pausePlayback() {
        val cur = _uiState.value
        val kind = cur.project.videoSource.kind
        val pausedSnap = readinessController.onUserPauseRequested(cur.currentTimeMs)
        sentinelCoordinator.onViewerPauseChanged(
            paused = true,
            acknowledgedPositionMs = cur.currentTimeMs,
            durationMs = cur.project.videoSource.durationMs,
        )
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
                readinessSnapshot = pausedSnap,
                sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
            )
        }
    }

    /**
     * Scrubber drag preview start: freezes the rolling 3-minute boundary at the last acknowledged
     * playback position so preview dragging NEVER moves the boundary or dispatches analysis.
     */
    fun onScrubberPreviewStart() {
        sentinelCoordinator.onScrubberPreviewStateChanged(true)
        _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
    }

    /**
     * Scrubber drag preview end/cancel: releases the preview freeze and wakes waiting workers.
     * Committed seeks flow through [seekTo] only upon commit.
     */
    fun onScrubberPreviewEnd(committedMs: Long = -1L) {
        sentinelCoordinator.onScrubberPreviewStateChanged(false)
        wakeScheduler("scrubber_preview_ended")
        _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
    }

    fun seekTo(timeMs: Long) {
        val cur = _uiState.value
        val duration = cur.project.videoSource.durationMs.coerceAtLeast(1000L)
        val clamped = timeMs.coerceIn(0L, duration)
        val seekSnap = readinessController.onUserSeekRequested(clamped, coverageLedger)
        val nextSeekGen = seekSnap.diagnostics.seekGeneration
        sentinelCoordinator.onCommittedSeek(
            targetPositionMs = clamped,
            durationMs = duration,
            isPlaying = seekSnap.userIntent == UserPlaybackIntent.PLAY,
            externalSeekGeneration = nextSeekGen,
            anchorBoundaryImmediately = cur.project.videoSource.kind == VideoSourceKind.DEMO_SCENARIO,
        )

        val significantJump = kotlin.math.abs(clamped - cur.currentTimeMs) > 2_500L
        changeoverPreListenJob?.cancel()
        seekAckTimeoutJob?.cancel()
        if (significantJump) {
            aiTranslationJob?.cancel()
            speakerAnalysisJob?.cancel()
            lastAutoAiTriggerElapsedMs = 0L
            lastScoutSeekSentAtMs = 0L
            lastScoutSeekTargetMs = -1L
            scoutAudioSliceTimestamps.clear()
            pendingCapturedAudioQueue.clear()
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

        // Supersede older queued heal beacons & cancel stale coordinator heal tasks on committed seek
        val supersededProj = HealBeaconEngine.supersedeOrCancelStaleBeacons(
            project = _uiState.value.project,
            activeGenerationToken = nextSeekGen,
            cancelActiveHealing = false,
        )
        sentinelCoordinator.cancelOrSupersedeStaleHealTasks(nextSeekGen)

        currentLiveFaces = emptyList()
        lastDetectorFrameId = -1L
        contextEngine.invalidateSceneCoordinates(clamped)
        releaseStore.updateSeekOrConfigGeneration(seekGeneration = nextSeekGen)
        sentinelCoordinator.updatePendingReviewCount(releaseStore.pendingDraftCount())
        wakeScheduler("committed_seek_$nextSeekGen")
        val holdAfterSeek = seekSnap.isBufferingHoldActive ||
            seekSnap.state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR
        val pauseSeq = if (holdAfterSeek &&
            (cur.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT ||
                cur.project.videoSource.kind == VideoSourceKind.WEB_STREAM)
        ) {
            ++nextStreamCommandSeq
        } else {
            null
        }
        if (holdAfterSeek && cur.project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
            exoPlayer.pause()
        }
        _uiState.update {
            it.copy(
                project = supersededProj,
                liveFaceExclusionRects = emptyList(),
                seekGeneration = nextSeekGen,
                isPlaying = if (holdAfterSeek) false else it.isPlaying,
                isScoutCatchUpBuffering = seekSnap.isBufferingHoldActive,
                streamPlayRequest = pauseSeq?.let { seq -> seq to false } ?: it.streamPlayRequest,
                readinessSnapshot = seekSnap,
                releaseSnapshot = releaseStore.snapshot(),
                sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
            )
        }
        val kind = _uiState.value.project.videoSource.kind
        when (kind) {
            VideoSourceKind.LOCAL_MP4 -> {
                exoPlayer.seekTo(clamped)
                readinessController.onSeekDispatched(nextSeekGen)
            }
            VideoSourceKind.YOUTUBE_COMPLIANT,
            VideoSourceKind.WEB_STREAM -> {
                val seekSeq = ++nextStreamCommandSeq
                _uiState.update { state ->
                    state.copy(youtubeSeekRequest = seekSeq to clamped)
                }
                readinessController.onSeekDispatched(nextSeekGen)
            }
            VideoSourceKind.DEMO_SCENARIO -> Unit
        }
        layoutEngine.clearSmoothing()
        if (kind == VideoSourceKind.DEMO_SCENARIO) {
            playbackClockAnchor.reset(
                positionMs = clamped,
                speed = _uiState.value.playbackSpeed,
                playing = if (holdAfterSeek) false else _uiState.value.isPlaying,
            )
            updateTimeAndLayout(clamped)
        } else {
            // Do NOT self-acknowledge async seeks before the player actually arrives;
            // keep playbackClockAnchor & currentTimeMs on observed position with isSeekInFlight = true.
            playbackClockAnchor.reset(
                positionMs = _uiState.value.currentTimeMs,
                speed = _uiState.value.playbackSpeed,
                playing = false,
            )
            updateVisualPresentationOnly(clamped)
            seekAckTimeoutJob = viewModelScope.launch {
                delay(PlaybackReadinessController.SEEK_ACK_TIMEOUT_MS + 250L)
                val currentSnap = readinessController.snapshot()
                if (currentSnap.diagnostics.seekGeneration == nextSeekGen &&
                    currentSnap.diagnostics.isSeekInFlight
                ) {
                    val timedOutSnap = readinessController.onSeekAckTimedOut(coverageLedger)
                    val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
                    _uiState.update { s ->
                        s.copy(
                            isPlaying = false,
                            isScoutCatchUpBuffering = false,
                            readinessSnapshot = timedOutSnap,
                            sentinelSnapshot = sentinelSnap,
                            humanReadiness = HumanReadableStatus.resolve(
                                project = s.project,
                                scoutState = s.scoutCaptionState,
                                coverageSummary = s.coverageSummary,
                                readinessSnapshot = timedOutSnap,
                                releaseSnapshot = s.releaseSnapshot,
                                sentinelSnapshot = sentinelSnap,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun onYouTubePlaybackState(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        val safePos = positionMs.coerceAtLeast(0L)
        val preState = _uiState.value
        if (preState.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return

        val wasSeekInFlight = preState.readinessSnapshot.diagnostics.isSeekInFlight
        val readinessSnap = executePendingAutoResumeIfNeeded(
            readinessController.onPlayerPositionSample(
                reportedPositionMs = safePos,
                playerIsPlaying = isPlaying,
                ledger = coverageLedger,
            ),
        )
        val shouldForceStreamPause = readinessSnap.isBufferingHoldActive ||
            readinessSnap.state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR ||
            readinessSnap.userIntent == UserPlaybackIntent.PAUSE

        // Reject stale pre-seek observations before updating clock/layout/coverage/scheduler (A04)
        if (wasSeekInFlight && readinessSnap.diagnostics.isSeekInFlight) {
            val pauseSeq = if (shouldForceStreamPause && isPlaying) ++nextStreamCommandSeq else null
            val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
            _uiState.update { cur ->
                cur.copy(
                    isPlaying = if (shouldForceStreamPause) false else cur.isPlaying,
                    isScoutCatchUpBuffering = readinessSnap.isBufferingHoldActive,
                    streamPlayRequest = pauseSeq?.let { it to false } ?: cur.streamPlayRequest,
                    readinessSnapshot = readinessSnap,
                    sentinelSnapshot = sentinelSnap,
                    humanReadiness = HumanReadableStatus.resolve(
                        project = cur.project,
                        scoutState = cur.scoutCaptionState,
                        coverageSummary = cur.coverageSummary,
                        readinessSnapshot = readinessSnap,
                        releaseSnapshot = cur.releaseSnapshot,
                        sentinelSnapshot = sentinelSnap,
                    ),
                )
            }
            return
        }
        if (wasSeekInFlight && !readinessSnap.diagnostics.isSeekInFlight) {
            seekAckTimeoutJob?.cancel()
        }

        _uiState.update { state ->
            if (state.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return@update state
            val updatedProj = if (durationMs > 1000L && durationMs != state.project.videoSource.durationMs) {
                coverageLedger.updateDuration(durationMs)
                state.project.copy(
                    videoSource = state.project.videoSource.copy(durationMs = durationMs),
                    shots = listOf(Shot("shot_1", 0L, durationMs, cutType = "EMBEDDED_YOUTUBE")),
                )
            } else {
                state.project
            }
            playbackClockAnchor.reconcileRemoteSample(
                reportedPosMs = safePos,
                speed = state.playbackSpeed,
                playing = !shouldForceStreamPause && isPlaying,
            )
            val presentationMs = playbackClockAnchor.sampleInterpolatedMs(
                maxDurationMs = updatedProj.videoSource.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
            )
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = updatedProj,
                timeMs = presentationMs,
                styleSettings = state.styleSettings,
                liveFaces = currentLiveFaces,
                displayMode = state.displayMode,
            )
            val refCues = releaseStore.currentReferenceTrack()?.cues.orEmpty()
            val nextCcState = if (
                (state.youtubeNativeCcState == YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE ||
                    state.youtubeNativeCcState == YouTubeNativeCcState.ENABLED_ACTIVE_CUES) &&
                (refCues.isNotEmpty() || updatedProj.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK)
            ) {
                val hasCueNow = refCues.any { presentationMs in (it.startMs - 400L)..(it.endMs + 400L) } ||
                    (updatedProj.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK &&
                        updatedProj.dialogueEvents.any {
                            it.sourceText.isNotBlank() &&
                                !it.sourceText.startsWith("Embedded YouTube Player active") &&
                                presentationMs in (it.startTimeMs - 400L)..(it.endTimeMs + 400L)
                        })
                if (hasCueNow) YouTubeNativeCcState.ENABLED_ACTIVE_CUES else YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE
            } else {
                state.youtubeNativeCcState
            }
            state.copy(
                project = updatedProj,
                isPlaying = !shouldForceStreamPause && isPlaying,
                hasPendingPlayIntro = if (!shouldForceStreamPause && isPlaying) false else state.hasPendingPlayIntro,
                currentTimeMs = presentationMs,
                placedBalloons = balloons,
                activeChapter = activeChapter,
                youtubeNativeCcState = nextCcState,
            )
        }
        val latestState = _uiState.value
        val pauseSeq = if (shouldForceStreamPause && isPlaying) ++nextStreamCommandSeq else null
        if (shouldForceStreamPause) {
            playbackClockAnchor.reset(latestState.currentTimeMs, latestState.playbackSpeed, playing = false)
        }
        val woke = sentinelCoordinator.updateAcknowledgedViewerPosition(
            positionMs = latestState.currentTimeMs,
            durationMs = latestState.project.videoSource.durationMs,
            isPlaying = !shouldForceStreamPause && isPlaying,
            playbackSpeed = latestState.playbackSpeed,
            seekGeneration = latestState.seekGeneration,
            seekAcknowledged = wasSeekInFlight && !readinessSnap.diagnostics.isSeekInFlight,
        )
        if (woke) {
            wakeScheduler("youtube_playback_advance")
        }
        val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
        _uiState.update { cur ->
            cur.copy(
                isPlaying = if (shouldForceStreamPause) false else cur.isPlaying,
                isScoutCatchUpBuffering = readinessSnap.isBufferingHoldActive,
                streamPlayRequest = pauseSeq?.let { it to false } ?: cur.streamPlayRequest,
                readinessSnapshot = readinessSnap,
                sentinelSnapshot = sentinelSnap,
                humanReadiness = HumanReadableStatus.resolve(
                    project = cur.project,
                    scoutState = cur.scoutCaptionState,
                    coverageSummary = cur.coverageSummary,
                    readinessSnapshot = readinessSnap,
                    releaseSnapshot = cur.releaseSnapshot,
                    sentinelSnapshot = sentinelSnap,
                ),
            )
        }
        maybeTriggerRollingWindowEnhancement(latestState.currentTimeMs)
        maybeRunProactiveChapterPatrol(latestState.currentTimeMs)
        maybeDispatchChangeoverPreListenScout(latestState.currentTimeMs)
    }

    private fun resolveYouTubeNativeCcState(
        trackCount: Int?,
        trackEnabled: Boolean,
        activeCuePresent: Boolean,
        probeBlocked: Boolean,
        playheadMs: Long,
        project: DialogueLensProject,
        hasProgrammaticCaptions: Boolean,
    ): YouTubeNativeCcState {
        if (project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) {
            return YouTubeNativeCcState.OFF
        }
        val refCues = releaseStore.currentReferenceTrack()?.cues.orEmpty()
        val hasCapturedCues = hasProgrammaticCaptions || refCues.isNotEmpty()
        val refCueAtPlayhead = refCues.any { playheadMs in (it.startMs - 400L)..(it.endMs + 400L) }
        val timelineCueAtPlayhead = project.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK &&
            project.dialogueEvents.any {
                it.sourceText.isNotBlank() &&
                    !it.sourceText.startsWith("Embedded YouTube Player active") &&
                    playheadMs in (it.startTimeMs - 400L)..(it.endTimeMs + 400L)
            }

        if (trackCount == null) {
            return when {
                hasCapturedCues -> if (activeCuePresent || refCueAtPlayhead || timelineCueAtPlayhead) {
                    YouTubeNativeCcState.ENABLED_ACTIVE_CUES
                } else {
                    YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE
                }
                probeBlocked -> YouTubeNativeCcState.UNAVAILABLE_OR_BLOCKED
                else -> YouTubeNativeCcState.LOADING
            }
        }
        if (trackCount <= 0 && !trackEnabled && !hasCapturedCues) {
            return YouTubeNativeCcState.UNAVAILABLE_OR_BLOCKED
        }
        if (!trackEnabled && trackCount > 0) {
            return YouTubeNativeCcState.OFF
        }
        if (probeBlocked && !activeCuePresent && !hasCapturedCues) {
            return YouTubeNativeCcState.ENABLED_INACCESSIBLE
        }
        return if (activeCuePresent || refCueAtPlayhead || timelineCueAtPlayhead) {
            YouTubeNativeCcState.ENABLED_ACTIVE_CUES
        } else {
            YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE
        }
    }

    fun onYouTubeCaptionTrackStatus(
        trackCount: Int,
        activeLang: String,
        trackEnabled: Boolean = trackCount > 0 && activeLang.isNotBlank(),
        activeCuePresent: Boolean = false,
        probeBlocked: Boolean = false,
    ) {
        _uiState.update { cur ->
            if (cur.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return@update cur
            val safeCount = trackCount.coerceAtLeast(0)
            val cleanLang = activeLang.trim()
            val ccState = resolveYouTubeNativeCcState(
                trackCount = safeCount,
                trackEnabled = trackEnabled,
                activeCuePresent = activeCuePresent,
                probeBlocked = probeBlocked,
                playheadMs = cur.currentTimeMs,
                project = cur.project,
                hasProgrammaticCaptions = cur.youtubeCapabilities.hasProgrammaticCaptions,
            )
            val hasStoredRef = releaseStore.currentReferenceTrack() != null
            val updatedCaps = when (ccState) {
                YouTubeNativeCcState.ENABLED_ACTIVE_CUES,
                YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE -> cur.youtubeCapabilities.copy(
                    hasProgrammaticCaptions = cur.youtubeCapabilities.hasProgrammaticCaptions || hasStoredRef,
                    captionProvenance = when {
                        hasStoredRef -> cur.youtubeCapabilities.captionProvenance
                        cleanLang.isNotBlank() -> "YouTube CC ($cleanLang · ${ccState.label})"
                        else -> "YouTube CC (${ccState.label})"
                    },
                )
                YouTubeNativeCcState.ENABLED_INACCESSIBLE -> cur.youtubeCapabilities.copy(
                    captionProvenance = if (hasStoredRef) {
                        cur.youtubeCapabilities.captionProvenance
                    } else {
                        "CC enabled (${cleanLang.ifBlank { "und" }}), cue text blocked by player"
                    },
                )
                YouTubeNativeCcState.UNAVAILABLE_OR_BLOCKED -> cur.youtubeCapabilities.copy(
                    captionProvenance = if (hasStoredRef) cur.youtubeCapabilities.captionProvenance else "Unavailable / Blocked",
                )
                YouTubeNativeCcState.OFF -> cur.youtubeCapabilities.copy(
                    captionProvenance = if (hasStoredRef) cur.youtubeCapabilities.captionProvenance else "CC Off ($safeCount track(s) available)",
                )
                YouTubeNativeCcState.LOADING -> cur.youtubeCapabilities
            }
            cur.copy(
                youtubeNativeCcTrackCount = safeCount,
                youtubeNativeCcLang = cleanLang,
                youtubeNativeCcState = ccState,
                youtubeNativeCcTrackEnabled = trackEnabled,
                youtubeNativeCcProbeBlocked = probeBlocked,
                youtubeCapabilities = updatedCaps,
            )
        }
    }

    fun onYouTubeEmbedFallback(errorCode: Int) {
        val errorKind = classifyYouTubeErrorCode(errorCode)
        _uiState.update { cur ->
            if (cur.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return@update cur
            val note = "${errorKind.label} · Switched to YouTube mobile player"
            val nextDiag = if (cur.scoutDiagnosticDetail.isBlank()) {
                note
            } else if (cur.scoutDiagnosticDetail.contains("Switched to YouTube mobile player")) {
                cur.scoutDiagnosticDetail
            } else {
                "$note · ${cur.scoutDiagnosticDetail}"
            }
            cur.copy(
                youtubePlayerErrorKind = errorKind,
                scoutDiagnosticDetail = nextDiag,
            )
        }
        showStatusBanner("${errorKind.label} — switched to YouTube mobile player")
    }

    fun onYouTubePlayerError(errorCode: Int) {
        val errorKind = classifyYouTubeErrorCode(errorCode)
        val cur = _uiState.value
        if (cur.project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT) return
        val diagMsg = "YouTube ${errorKind.label} (code $errorCode)"
        if (errorCode == 101 || errorCode == 150 || errorCode == 152 || errorCode == 153) {
            onYouTubeEmbedFallback(errorCode)
            return
        }
        val errReadiness = readinessController.onMediaOrAnalysisError(
            reason = "${errorKind.label}. Retry or check network/source availability.",
            isMediaHardwareError = true,
        )
        _uiState.update { state ->
            state.copy(
                isPlaying = false,
                isScoutCatchUpBuffering = false,
                youtubePlayerErrorKind = errorKind,
                scoutDiagnosticDetail = diagMsg,
                readinessSnapshot = errReadiness,
                humanReadiness = HumanReadableStatus.resolve(
                    project = state.project,
                    scoutState = state.scoutCaptionState,
                    coverageSummary = state.coverageSummary,
                    readinessSnapshot = errReadiness,
                    releaseSnapshot = state.releaseSnapshot,
                    sentinelSnapshot = state.sentinelSnapshot,
                ),
            )
        }
    }

    fun onScoutCaptureDiagnosticsUpdated(diagnostics: ScoutCaptureDiagnostics) {
        _uiState.update { cur ->
            if (cur.scoutCaptureDiagnostics == diagnostics) cur else cur.copy(scoutCaptureDiagnostics = diagnostics)
        }
    }

    fun dismissSpeakerToast() {
        speakerToastJob?.cancel()
        _uiState.update { it.copy(activeSpeakerToast = null) }
    }

    private fun emitSpeakerGlossaryToasts(before: List<Speaker>, after: List<Speaker>) {
        val viewerTimeMs = _uiState.value.currentTimeMs
        val beforeById = before.associateBy { it.id }
        val toastCandidate = after.firstNotNullOfOrNull { spk ->
            val prev = beforeById[spk.id]
            val revealedNow = spk.hasVisibleCharacterNameAt(viewerTimeMs)
            val prevRevealed = prev?.hasVisibleCharacterNameAt(viewerTimeMs) == true
            val visibleNameNow = spk.visibleDisplayNameAt(viewerTimeMs)
            val prevVisibleName = prev?.visibleDisplayNameAt(viewerTimeMs) ?: spk.defaultLabel
            when {
                prev != null &&
                    !prevRevealed &&
                    revealedNow -> {
                    SpeakerGlossaryToast(
                        id = ++nextSpeakerToastId,
                        speakerId = spk.id,
                        isRename = true,
                        title = "Speaker confirmed",
                        detail = "$prevVisibleName → $visibleNameNow",
                        accentIndex = spk.accentIndex,
                        isUnknown = false,
                    )
                }
                prev != null &&
                    !prevVisibleName.equals(visibleNameNow, ignoreCase = true) &&
                    revealedNow -> {
                    SpeakerGlossaryToast(
                        id = ++nextSpeakerToastId,
                        speakerId = spk.id,
                        isRename = true,
                        title = "Speaker identified",
                        detail = "$prevVisibleName → $visibleNameNow",
                        accentIndex = spk.accentIndex,
                        isUnknown = false,
                    )
                }
                prev == null && (revealedNow || spk.visualVoiceNotes.isNotBlank() || spk.isRolePlaceholder) -> {
                    SpeakerGlossaryToast(
                        id = ++nextSpeakerToastId,
                        speakerId = spk.id,
                        isRename = false,
                        title = if (revealedNow) "Speaker confirmed" else "New speaker spotted",
                        detail = if (revealedNow) {
                            visibleNameNow
                        } else {
                            spk.visualVoiceNotes.ifBlank { visibleNameNow }.take(40)
                        },
                        accentIndex = spk.accentIndex,
                        isUnknown = !revealedNow,
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
        val safePos = positionMs.coerceAtLeast(0L)
        val preState = _uiState.value
        if (preState.project.videoSource.kind != VideoSourceKind.WEB_STREAM) return

        val wasSeekInFlight = preState.readinessSnapshot.diagnostics.isSeekInFlight
        val readinessSnap = executePendingAutoResumeIfNeeded(
            readinessController.onPlayerPositionSample(
                reportedPositionMs = safePos,
                playerIsPlaying = isPlaying,
                ledger = coverageLedger,
            )
        )
        val shouldForceStreamPause = readinessSnap.isBufferingHoldActive ||
            readinessSnap.state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR ||
            readinessSnap.userIntent == UserPlaybackIntent.PAUSE

        if (wasSeekInFlight && readinessSnap.diagnostics.isSeekInFlight) {
            val pauseSeq = if (shouldForceStreamPause && isPlaying) ++nextStreamCommandSeq else null
            val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
            _uiState.update { cur ->
                cur.copy(
                    isPlaying = if (shouldForceStreamPause) false else cur.isPlaying,
                    isScoutCatchUpBuffering = readinessSnap.isBufferingHoldActive,
                    streamPlayRequest = pauseSeq?.let { it to false } ?: cur.streamPlayRequest,
                    readinessSnapshot = readinessSnap,
                    sentinelSnapshot = sentinelSnap,
                    humanReadiness = HumanReadableStatus.resolve(
                        project = cur.project,
                        scoutState = cur.scoutCaptionState,
                        coverageSummary = cur.coverageSummary,
                        readinessSnapshot = readinessSnap,
                        releaseSnapshot = cur.releaseSnapshot,
                        sentinelSnapshot = sentinelSnap,
                    ),
                )
            }
            return
        }
        if (wasSeekInFlight && !readinessSnap.diagnostics.isSeekInFlight) {
            seekAckTimeoutJob?.cancel()
        }

        _uiState.update { state ->
            if (state.project.videoSource.kind != VideoSourceKind.WEB_STREAM) return@update state
            val updatedProj = if (durationMs > 1000L && durationMs != state.project.videoSource.durationMs) {
                coverageLedger.updateDuration(durationMs)
                state.project.copy(
                    videoSource = state.project.videoSource.copy(durationMs = durationMs),
                    shots = listOf(Shot("shot_1", 0L, durationMs, cutType = "WEB_STREAM")),
                )
            } else {
                state.project
            }
            playbackClockAnchor.reconcileRemoteSample(
                reportedPosMs = safePos,
                speed = state.playbackSpeed,
                playing = !shouldForceStreamPause && isPlaying,
            )
            val presentationMs = playbackClockAnchor.sampleInterpolatedMs(
                maxDurationMs = updatedProj.videoSource.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
            )
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = updatedProj,
                timeMs = presentationMs,
                styleSettings = state.styleSettings,
                liveFaces = currentLiveFaces,
                displayMode = state.displayMode,
            )
            state.copy(
                project = updatedProj,
                isPlaying = !shouldForceStreamPause && isPlaying,
                currentTimeMs = presentationMs,
                placedBalloons = balloons,
                activeChapter = activeChapter,
            )
        }
        val snap = _uiState.value
        val durMs = snap.project.videoSource.durationMs.coerceAtLeast(durationMs)
        val pauseSeq = if (shouldForceStreamPause && isPlaying) ++nextStreamCommandSeq else null
        if (shouldForceStreamPause) {
            playbackClockAnchor.reset(snap.currentTimeMs, snap.playbackSpeed, playing = false)
        }
        if (!sentinelCoordinator.isScrubberPreviewActive()) {
            val wokeFromIdle = sentinelCoordinator.updateAcknowledgedViewerPosition(
                positionMs = snap.currentTimeMs,
                durationMs = durMs,
                isPlaying = !shouldForceStreamPause && isPlaying,
                playbackSpeed = snap.playbackSpeed,
                seekGeneration = snap.seekGeneration,
                seekAcknowledged = wasSeekInFlight && !readinessSnap.diagnostics.isSeekInFlight,
            )
            if (wokeFromIdle) {
                wakeScheduler("web_playback_advance")
            }
        }
        val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
        _uiState.update { cur ->
            cur.copy(
                isPlaying = if (shouldForceStreamPause) false else cur.isPlaying,
                isScoutCatchUpBuffering = readinessSnap.isBufferingHoldActive,
                streamPlayRequest = pauseSeq?.let { it to false } ?: cur.streamPlayRequest,
                readinessSnapshot = readinessSnap,
                sentinelSnapshot = sentinelSnap,
                humanReadiness = HumanReadableStatus.resolve(
                    project = cur.project,
                    scoutState = cur.scoutCaptionState,
                    coverageSummary = cur.coverageSummary,
                    readinessSnapshot = readinessSnap,
                    releaseSnapshot = cur.releaseSnapshot,
                    sentinelSnapshot = sentinelSnap,
                ),
            )
        }
        maybeTriggerRollingWindowEnhancement(snap.currentTimeMs)
        maybeAutoSyncScoutFromViewer()
        maybeRunProactiveChapterPatrol(snap.currentTimeMs)
    }

    fun onWebStreamCapturedCaptions(rawJson: String) {
        val current = _uiState.value.project
        if (current.videoSource.kind != VideoSourceKind.WEB_STREAM) return
        if (current.subtitleSource == SubtitleSourceKind.UPLOADED_FILE) return
        if (rawJson.isBlank() || rawJson == lastCapturedWebTrackSignature) return

        val capturedEvents = parseCapturedWebTrackJson(
            rawJson = rawJson,
            shotId = current.shots.firstOrNull()?.id ?: "shot_1",
            sourceLanguage = current.sourceLanguage,
            targetLanguage = current.targetLanguage,
        )
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
            val needsTranslation = !isSameResolvedLanguage(
                sourceLanguage = current.sourceLanguage,
                targetLanguage = current.targetLanguage,
                sampleTexts = attributed.map { it.sourceText },
            )
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
        if (!_uiState.value.isCinemaIntroActive) {
            if (_uiState.value.isScoutCatchUpBuffering || _uiState.value.readinessSnapshot.isBufferingHoldActive) {
                dismissScoutCatchUpBuffering()
            } else {
                val bypassSnap = readinessController.onUserWatchNowBypass()
                _uiState.update { it.copy(readinessSnapshot = bypassSnap) }
            }
            return
        }
        val bypassSnap = readinessController.onUserWatchNowBypass()
        _uiState.update { it.copy(readinessSnapshot = bypassSnap) }
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
        val sameUrlRicherHeaders = clean.equals(prevUrl, ignoreCase = true) &&
            reqHeaders.isNotEmpty() &&
            reqHeaders != discoveredWebMediaHeaders &&
            (reqHeaders.size > discoveredWebMediaHeaders.size ||
                !discoveredWebMediaHeaders.entries.containsAll(reqHeaders.entries))
        val shouldUpgrade = prevUrl.isNullOrBlank() ||
            (!prevIsHls && newIsHls) ||
            (newIsHls && !clean.equals(prevUrl, ignoreCase = true) && clean.contains("master", ignoreCase = true)) ||
            sameUrlRicherHeaders

        if (!shouldUpgrade && lookaheadJob?.isActive == true) return

        discoveredWebMediaUrl = clean
        if (reqHeaders.isNotEmpty()) {
            discoveredWebMediaHeaders = reqHeaders
        }
        if (!LookaheadWindowPlanner.hasLoadedSubtitlesLocked(proj.subtitleSource, proj.dialogueEvents)) {
            if (!LookaheadWindowPlanner.hasRealSubtitles(proj.dialogueEvents) &&
                !clean.equals(prevUrl, ignoreCase = true)
            ) {
                scoutCompletedExtents.clear()
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

    fun onScoutWebStreamAudioSlice(
        startMs: Long,
        base64Audio: String,
        mimeType: String,
        endMs: Long = startMs + LookaheadWindowPlanner.WINDOW_STEP_MS,
        discontinuity: Boolean = false,
    ) {
        val state = _uiState.value
        val proj = state.project
        if (!state.dualInstanceEnabled || proj.videoSource.kind != VideoSourceKind.WEB_STREAM) return
        if (state.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped) return
        if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(proj.subtitleSource, proj.dialogueEvents)) {
            return
        }
        if (!discoveredWebMediaUrl.isNullOrBlank() &&
            lookaheadJob?.isActive == true &&
            LookaheadWindowPlanner.hasRealSubtitles(proj.dialogueEvents)
        ) {
            return
        }
        val safeStartMs = startMs.coerceAtLeast(0L)
        val rawSliceEndMs = endMs.coerceAtLeast(safeStartMs + 500L)
        val bucketKey = (safeStartMs / 5_000L) * 5_000L
        if (bucketKey in scoutAudioSliceTimestamps) return

        val policy = sentinelCoordinator.currentPolicy()
        val boundaryGate = sentinelCoordinator.clipOrDeferToBoundary(
            startMs = safeStartMs,
            endMs = rawSliceEndMs,
            overlapMs = 0L,
            roundingUpToSec = false,
            minSliceMs = policy.minSubmittableSliceMs,
        )
        if (boundaryGate !is BoundarySubmissionDecision.Allowed) return
        val clippedStartMs = boundaryGate.startMs
        val clippedEndMs = minOf(boundaryGate.endMs, rawSliceEndMs)
        if (clippedEndMs <= clippedStartMs) return

        val requestCtx = captureWorkRequestContext(proj, state.quotaSaverMode)
        val newItem = CapturedAudioQueueItem(
            bucketKey = bucketKey,
            startMs = clippedStartMs,
            endMs = clippedEndMs,
            base64Audio = base64Audio,
            mimeType = mimeType.ifBlank { "audio/webm" },
            discontinuity = discontinuity,
            requestCtx = requestCtx,
        )

        val ackViewerPosMs = sentinelCoordinator.currentAcknowledgedViewerPosMs()
        val droppedRanges = mutableListOf<Pair<Long, Long>>()
        synchronized(capturedAudioQueueLock) {
            if (bucketKey in inFlightCapturedAudioBuckets) return
            // Coalesce duplicate pending bucket (keep newest valid descriptor for current context)
            val existingIdx = pendingCapturedAudioQueue.indexOfFirst { it.bucketKey == bucketKey }
            if (existingIdx >= 0) {
                pendingCapturedAudioQueue.removeAt(existingIdx)
            }
            // Enforce strict total limit <= 4 across pending + in-flight
            while (pendingCapturedAudioQueue.size + inFlightCapturedAudioBuckets.size >= 4 &&
                pendingCapturedAudioQueue.isNotEmpty()
            ) {
                val evictIdx = pendingCapturedAudioQueue.indices.maxByOrNull { idx ->
                    val candidate = pendingCapturedAudioQueue[idx]
                    val staleBonus = if (candidate.requestCtx.seekGeneration != requestCtx.seekGeneration) 1_000_000_000L else 0L
                    val distanceFromViewer = kotlin.math.abs(candidate.startMs - ackViewerPosMs)
                    staleBonus + distanceFromViewer
                } ?: break
                val evicted = pendingCapturedAudioQueue.removeAt(evictIdx)
                scoutAudioSliceTimestamps.remove(evicted.bucketKey)
                droppedRanges.add(evicted.startMs to evicted.endMs)
            }
            if (pendingCapturedAudioQueue.size + inFlightCapturedAudioBuckets.size >= 4) {
                return
            }
            scoutAudioSliceTimestamps.add(bucketKey)
            pendingCapturedAudioQueue.addLast(newItem)
        }
        droppedRanges.forEach { (dStart, dEnd) ->
            coverageLedger.recordOutcome(
                startMs = dStart,
                endMs = dEnd,
                status = CoverageStatus.PENDING,
                source = SubtitleSourceKind.AI_DRAFT,
                failureReason = "Deferred by bounded capture queue",
                incrementRetry = false,
            )
        }
        ensureCapturedAudioConsumerRunning()
    }

    private fun ensureCapturedAudioConsumerRunning() {
        if (capturedAudioConsumerJob?.isActive == true) return
        capturedAudioConsumerJob = viewModelScope.launch {
            while (isActive) {
                val nextItem: CapturedAudioQueueItem? = synchronized(capturedAudioQueueLock) {
                    val ackPos = sentinelCoordinator.currentAcknowledgedViewerPosMs()
                    val bestIdx = pendingCapturedAudioQueue.indices.minByOrNull { idx ->
                        val item = pendingCapturedAudioQueue[idx]
                        val dist = kotlin.math.abs(item.startMs - ackPos)
                        if (item.startMs in ackPos..(ackPos + 20_000L)) dist else dist + 50_000L
                    }
                    if (bestIdx != null) {
                        val picked = pendingCapturedAudioQueue.removeAt(bestIdx)
                        inFlightCapturedAudioBuckets.add(picked.bucketKey)
                        picked
                    } else {
                        null
                    }
                }
                if (nextItem == null) break
                try {
                    processCapturedAudioQueueItem(nextItem)
                } finally {
                    synchronized(capturedAudioQueueLock) {
                        inFlightCapturedAudioBuckets.remove(nextItem.bucketKey)
                    }
                }
            }
        }
    }

    private suspend fun processCapturedAudioQueueItem(item: CapturedAudioQueueItem) {
        if (!isRequestContextCurrent(item.requestCtx)) {
            scoutAudioSliceTimestamps.remove(item.bucketKey)
            return
        }
        val audioBytes = runCatching { Base64.decode(item.base64Audio, Base64.DEFAULT) }.getOrNull()
        if (audioBytes == null || audioBytes.isEmpty()) {
            scoutAudioSliceTimestamps.remove(item.bucketKey)
            onScoutCaptureDiagnosticsUpdated(
                _uiState.value.scoutCaptureDiagnostics.copy(
                    healthState = ScoutCaptureHealthState.DECODE_FAILED,
                    diagnosticDetail = "Empty or invalid captured audio payload",
                    lastSliceStartMs = item.startMs,
                    lastSliceEndMs = item.endMs,
                    discontinuityCount = _uiState.value.scoutCaptureDiagnostics.discontinuityCount + if (item.discontinuity) 1 else 0,
                )
            )
            return
        }

        val inspection = audioAnalyzer.inspectCapturedAudioChunk(audioBytes, item.mimeType)
        val curDiag = _uiState.value.scoutCaptureDiagnostics
        onScoutCaptureDiagnosticsUpdated(
            curDiag.copy(
                healthState = inspection.healthState,
                decodedSampleCount = inspection.decodedSamplesCount,
                rmsEnergy = inspection.rmsEnergy,
                peakAmplitude = inspection.peakAmplitude,
                sampleRateHz = inspection.sampleRateHz,
                lastSliceStartMs = item.startMs,
                lastSliceEndMs = item.endMs,
                discontinuityCount = curDiag.discontinuityCount + if (item.discontinuity) 1 else 0,
                diagnosticDetail = inspection.diagnosticNote,
            )
        )

        if (!isRequestContextCurrent(item.requestCtx)) {
            scoutAudioSliceTimestamps.remove(item.bucketKey)
            return
        }

        val clippedStartMs = item.startMs
        val clippedEndMs = item.endMs
        val latestProjBefore = _uiState.value.project
        if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(latestProjBefore.subtitleSource, latestProjBefore.dialogueEvents)) {
            return
        }

        when (inspection.healthState) {
            ScoutCaptureHealthState.CONFIRMED_SILENCE -> {
                val (reviewedSilence, silenceSnap) = stageAndReviewIntervalThroughReleaseGate(
                    project = latestProjBefore,
                    intervalId = "web_${clippedStartMs}_${clippedEndMs}",
                    startMs = clippedStartMs,
                    endMs = clippedEndMs,
                    events = emptyList(),
                    speakers = latestProjBefore.speakers,
                    sessionToken = item.requestCtx.sessionToken,
                    seekGeneration = item.requestCtx.seekGeneration,
                    isConfirmedSilence = true,
                    hasIndependentAudioOrFrameEvidence = true,
                    expectedConfigFingerprint = item.requestCtx.configFingerprint,
                )
                if (reviewedSilence != null && isRequestContextCurrent(item.requestCtx)) {
                    coverageLedger.recordOutcome(
                        startMs = clippedStartMs,
                        endMs = clippedEndMs,
                        status = CoverageStatus.VERIFIED_SILENCE,
                        source = SubtitleSourceKind.AI_DRAFT,
                    )
                    _uiState.update { it.copy(releaseSnapshot = silenceSnap) }
                    syncCoverageUiState()
                }
                return
            }
            ScoutCaptureHealthState.UNSUPPORTED_CAPTURE,
            ScoutCaptureHealthState.SOURCE_INACCESSIBLE,
            ScoutCaptureHealthState.DECODE_FAILED,
            ScoutCaptureHealthState.FAILED_EXHAUSTED,
            ScoutCaptureHealthState.WAITING_FOR_MEDIA -> {
                coverageLedger.recordOutcome(
                    startMs = clippedStartMs,
                    endMs = clippedEndMs,
                    status = CoverageStatus.FAILED,
                    source = SubtitleSourceKind.AI_DRAFT,
                    failureReason = inspection.diagnosticNote.ifBlank { inspection.healthState.name },
                    incrementRetry = true,
                )
                scoutAudioSliceTimestamps.remove(item.bucketKey)
                syncCoverageUiState()
                return
            }
            ScoutCaptureHealthState.USABLE_DECODED_INPUT,
            ScoutCaptureHealthState.RECORDER_STARTED -> Unit
        }

        if (!canInvokeCloudAi(item.requestCtx, clippedStartMs, clippedEndMs, enforceBoundary = true, requireUnloadedTracks = true)) {
            scoutAudioSliceTimestamps.remove(item.bucketKey)
            return
        }

        coverageLedger.recordWindowRequested()
        coverageLedger.markInProgress(clippedStartMs, clippedEndMs)
        val execStartMs = System.currentTimeMillis()
        val preferLite = _uiState.value.quotaSaverMode == QuotaSaverMode.QUOTA_SAVER || downgradedToLiteForStream

        val res = translationAiService.transcribeAndTranslateAudioDetailed(
            wavBytes = audioBytes,
            chunkStartMs = clippedStartMs,
            sourceLanguage = latestProjBefore.sourceLanguage,
            targetLanguage = latestProjBefore.targetLanguage,
            glossary = (latestProjBefore.glossary + _uiState.value.glossaryEntries).distinctBy { it.term.trim().lowercase() },
            audioMimeType = item.mimeType,
            preferLiteModel = preferLite,
        )
        if (!isRequestContextCurrent(item.requestCtx)) {
            coverageLedger.recordOutcome(
                startMs = clippedStartMs,
                endMs = clippedEndMs,
                status = CoverageStatus.PENDING,
                source = SubtitleSourceKind.AI_DRAFT,
                incrementRetry = false,
            )
            return
        }

        val latestProj = _uiState.value.project
        if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(latestProj.subtitleSource, latestProj.dialogueEvents)) {
            return
        }

        if (res.isFailure) {
            if (TranslationAiService.isQuotaOrCreditExhaustedError(res.exceptionOrNull())) {
                quotaCircuitBreakerTripped = true
            }
            coverageLedger.recordOutcome(
                startMs = clippedStartMs,
                endMs = clippedEndMs,
                status = CoverageStatus.FAILED,
                source = SubtitleSourceKind.AI_DRAFT,
                failureReason = res.exceptionOrNull()?.localizedMessage?.take(120) ?: "Live audio slice error",
                incrementRetry = true,
            )
            scoutAudioSliceTimestamps.remove(item.bucketKey)
            syncCoverageUiState()
            return
        }

        val detailed = res.getOrThrow()
        val cues = detailed.cues
        val elapsedMs = (System.currentTimeMillis() - execStartMs).coerceAtLeast(1L)
        coverageLedger.recordWindowCompleted()
        if (detailed.invalidCueCount > 0) {
            coverageLedger.recordInvalidOrAmbiguousTimestamp(detailed.invalidCueCount)
        }

        when (detailed.outcome) {
            TranscriptionOutcome.VERIFIED_SILENCE -> {
                val (reviewedSilence, silenceSnap) = stageAndReviewIntervalThroughReleaseGate(
                    project = latestProj,
                    intervalId = "web_${clippedStartMs}_${clippedEndMs}",
                    startMs = clippedStartMs,
                    endMs = clippedEndMs,
                    events = emptyList(),
                    speakers = latestProj.speakers,
                    sessionToken = item.requestCtx.sessionToken,
                    seekGeneration = item.requestCtx.seekGeneration,
                    isConfirmedSilence = true,
                    hasIndependentAudioOrFrameEvidence = true,
                    elapsedProcessingMs = elapsedMs,
                    expectedConfigFingerprint = item.requestCtx.configFingerprint,
                )
                if (reviewedSilence == null) {
                    _uiState.update { it.copy(releaseSnapshot = silenceSnap) }
                    return
                }
                if (reviewedSilence.repairedEvents.isNotEmpty()) {
                    val effectiveStatus = if (reviewedSilence.coverageStatus == CoverageStatus.VERIFIED_SILENCE) {
                        CoverageStatus.RELEASED_FALLBACK
                    } else {
                        reviewedSilence.coverageStatus
                    }
                    publishReleasedEventsToProject(
                        startMs = clippedStartMs,
                        endMs = clippedEndMs,
                        releasedEvents = reviewedSilence.repairedEvents,
                        coverageStatus = effectiveStatus,
                        source = SubtitleSourceKind.AI_DRAFT,
                    )
                } else {
                    val silenceStatus = if (reviewedSilence.coverageStatus == CoverageStatus.SUSPECTED_MISSING_DIALOGUE) {
                        CoverageStatus.SUSPECTED_MISSING_DIALOGUE
                    } else {
                        CoverageStatus.VERIFIED_SILENCE
                    }
                    coverageLedger.recordOutcome(
                        startMs = clippedStartMs,
                        endMs = clippedEndMs,
                        status = silenceStatus,
                        source = SubtitleSourceKind.AI_DRAFT,
                    )
                    _uiState.update { it.copy(releaseSnapshot = silenceSnap) }
                    syncCoverageUiState()
                }
                return
            }
            TranscriptionOutcome.INVALID_AI_RESPONSE,
            TranscriptionOutcome.INVALID_OR_AMBIGUOUS_TIMESTAMPS,
            TranscriptionOutcome.EMPTY_UNCONFIRMED -> {
                coverageLedger.recordOutcome(
                    startMs = clippedStartMs,
                    endMs = clippedEndMs,
                    status = CoverageStatus.FAILED,
                    source = SubtitleSourceKind.AI_DRAFT,
                    failureReason = detailed.diagnosticNote.ifBlank { detailed.outcome.name },
                    incrementRetry = true,
                )
                scoutAudioSliceTimestamps.remove(item.bucketKey)
                syncCoverageUiState()
                return
            }
            else -> Unit
        }

        if (cues.isEmpty()) return

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
        val mergeReport = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = latestProj.dialogueEvents.filterNot { it.sourceText.startsWith("Web Cinema ready") },
            incoming = paced,
            windowStartMs = clippedStartMs,
            windowEndMs = clippedEndMs + 1_000L,
        )
        coverageLedger.recordDuplicateDecisions(mergeReport.decisions)
        val (updatedSpeakers, attributed) = attributionEngine.assignSpeakers(
            events = mergeReport.events,
            shots = latestProj.shots,
            faceTracks = latestProj.faceTracks,
            existingSpeakers = latestProj.speakers,
            rememberedCharacterColors = _uiState.value.characterColorMemory,
            slotToSpeakerId = buildSlotToSpeakerIdMap(latestProj.speakers),
        )
        if (!isRequestContextCurrent(item.requestCtx)) return

        val windowSlice = attributed.filter { it.startTimeMs in clippedStartMs until clippedEndMs }
        val (reviewOutcome, releaseSnap) = stageAndReviewIntervalThroughReleaseGate(
            project = latestProj.copy(speakers = updatedSpeakers, dialogueEvents = attributed),
            intervalId = "web_${clippedStartMs}_${clippedEndMs}",
            startMs = clippedStartMs,
            endMs = clippedEndMs,
            events = windowSlice,
            speakers = updatedSpeakers,
            sessionToken = item.requestCtx.sessionToken,
            seekGeneration = item.requestCtx.seekGeneration,
            isConfirmedSilence = false,
            hasIndependentAudioOrFrameEvidence = true,
            elapsedProcessingMs = elapsedMs,
            expectedConfigFingerprint = item.requestCtx.configFingerprint,
        )
        if (reviewOutcome == null) {
            _uiState.update { it.copy(releaseSnapshot = releaseSnap) }
            return
        }
        val coverageStatus = when {
            detailed.outcome == TranscriptionOutcome.PARTIAL_SUCCESS -> CoverageStatus.PARTIAL_RECOVERY
            reviewOutcome.coverageStatus == CoverageStatus.VERIFIED_DIALOGUE -> CoverageStatus.VERIFIED_DIALOGUE
            else -> CoverageStatus.RELEASED_FALLBACK
        }
        publishReleasedEventsToProject(
            startMs = clippedStartMs,
            endMs = clippedEndMs,
            releasedEvents = reviewOutcome.repairedEvents,
            coverageStatus = coverageStatus,
            source = SubtitleSourceKind.AI_DRAFT,
            speakers = updatedSpeakers,
            failureReason = if (coverageStatus == CoverageStatus.PARTIAL_RECOVERY) detailed.diagnosticNote else "",
        )
        _uiState.update { cur ->
            cur.copy(
                scoutStatusText = "Scout verified live audio (${SubtitleParser.formatCompactTime(clippedStartMs)}–${SubtitleParser.formatCompactTime(clippedEndMs)})",
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

        val vid = proj.videoSource.youtubeVideoId.orEmpty()
        // Reject delayed cross-video timedtext callbacks from a previously watched video
        val observedVid = Regex("[?&]v=([^&]+)").find(observedUrl)?.groupValues?.getOrNull(1)
        if (vid.isNotBlank() && !observedVid.isNullOrBlank() && observedVid != vid) return
        val sessionToken = activeSessionToken.get()
        val langKey = Regex("[?&]lang=([^&]+)").find(observedUrl)?.groupValues?.getOrNull(1) ?: "default"
        val key = "$vid:$langKey:${if (observedBody.isNotBlank()) "body" else "url"}"
        if (!capturedYouTubeTimedTextKeys.add(key)) return

        // Always stash fallback handle if replacement is currently disallowed
        val allowReplacementNow = !LookaheadWindowPlanner.hasLoadedSubtitlesLocked(proj.subtitleSource, proj.dialogueEvents) &&
            LookaheadWindowPlanner.shouldAllowPassiveWebTimedTextOverride(
                quotaSaverMode = state.quotaSaverMode,
                quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                subtitleSource = proj.subtitleSource,
                events = proj.dialogueEvents,
            )
        if (!allowReplacementNow) {
            pendingWebTimedTextFallback = PendingWebTimedText(
                observedUrl = observedUrl,
                observedBody = observedBody,
                cookies = cookies,
                userAgent = userAgent,
            )
        }

        // Separate decisions:
        // (1) ALWAYS collect and preserve native captions as immutable reference evidence in every mode (including Best Model).
        // (2) ONLY replace the active dialogue source if mode/lock policy permits it.
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
            if (!isCurrentSession(sessionToken, proj.id)) return@launch

            val refTrack = YouTubeCaptionFetcher.buildReferenceTrackFromObservedTimedText(
                mediaId = proj.id,
                observedUrl = observedUrl,
                cues = cues,
            )
            if (refTrack != null) {
                releaseStore.updateReferenceTrack(refTrack, sessionToken)
                _uiState.update { cur ->
                    val resolvedLang = cur.youtubeNativeCcLang.ifBlank { refTrack.languageCode }
                    val nextCcState = resolveYouTubeNativeCcState(
                        trackCount = cur.youtubeNativeCcTrackCount ?: 1,
                        trackEnabled = cur.youtubeNativeCcTrackEnabled,
                        activeCuePresent = refTrack.cues.any { cur.currentTimeMs in (it.startMs - 400L)..(it.endMs + 400L) },
                        probeBlocked = false,
                        playheadMs = cur.currentTimeMs,
                        project = cur.project,
                        hasProgrammaticCaptions = true,
                    )
                    cur.copy(
                        youtubeNativeCcLang = resolvedLang,
                        youtubeNativeCcTrackCount = cur.youtubeNativeCcTrackCount ?: 1,
                        youtubeNativeCcTrackEnabled = cur.youtubeNativeCcTrackEnabled,
                        youtubeNativeCcProbeBlocked = false,
                        youtubeNativeCcState = nextCcState,
                        releaseSnapshot = releaseStore.snapshot(),
                        youtubeCapabilities = cur.youtubeCapabilities.copy(
                            hasProgrammaticCaptions = true,
                            captionProvenance = "${refTrack.originKind.name} (${refTrack.cues.size} cues · ${refTrack.languageCode.ifBlank { "und" }})",
                        ),
                    )
                }
            }

            val latestState = _uiState.value
            val latest = latestState.project
            if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(latest.subtitleSource, latest.dialogueEvents) ||
                !LookaheadWindowPlanner.shouldAllowPassiveWebTimedTextOverride(
                    quotaSaverMode = latestState.quotaSaverMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    subtitleSource = latest.subtitleSource,
                    events = latest.dialogueEvents,
                )
            ) {
                // Keep reference track available for Scout 1 & Scout 2 without replacing visible timeline or cancelling work!
                if (refTrack != null) {
                    wakeScheduler("new_caption_evidence")
                }
                return@launch
            }
            // Free YouTube captions arrived in fallback/free mode — apply as active track
            youtubeTranscriptJob?.cancel()
            applyFetchedYouTubeCues(
                baseProject = latest,
                cues = cues,
                fetchedTitle = null,
                fetchedDurationMs = null,
                fetchedLanguageCode = langKey.takeIf { it != "default" },
                sessionToken = sessionToken,
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
        sessionToken: Long = activeSessionToken.get(),
    ) {
        if (cues.isEmpty()) return
        if (!isCurrentSession(sessionToken, baseProject.id)) return
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
                val cueNeedsTranslation = !isSameResolvedLanguage(
                    sourceLanguage = resolvedSourceLang,
                    targetLanguage = baseProject.targetLanguage,
                    sampleTexts = listOf(cue.text),
                )
                DialogueEvent(
                    id = SubtitleParser.newEventId(),
                    startTimeMs = cue.startTimeMs,
                    endTimeMs = cue.endTimeMs,
                    sourceText = cue.text,
                    translatedText = if (cueNeedsTranslation) "" else cue.text,
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
                    needsTranslation = cueNeedsTranslation,
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
        coverageLedger.markAuthoritativeTrack(
            durationMs = updatedDuration,
            events = attributedEvents,
            source = SubtitleSourceKind.EMBEDDED_TRACK,
        )
        val captionLabel = fetchedLanguageCode?.uppercase() ?: resolvedSourceLang
        updateProject(
            enrichedProject,
            banner = "Captured ${attributedEvents.size} free YouTube captions ($captionLabel) — $0 credits",
        )
        syncCoverageUiState()
        _uiState.update {
            it.copy(
                isTranslatingAi = false,
                scoutStatusText = "${attributedEvents.size} CC bubbles ready ($captionLabel · $0)",
                scoutCaptionState = ScoutCaptionState.Ready(
                    sourceLabel = "CC · Free",
                    cueCount = attributedEvents.size,
                    speakerCount = updatedSpeakers.size,
                ),
            )
        }
        persistYouTubeVideoCache(
            project = enrichedProject,
            scoutedUpToMs = coverageLedger.continuousVerifiedUpToMs(),
            sessionToken = sessionToken,
        )
        val mode = _uiState.value.quotaSaverMode
        if (mode == QuotaSaverMode.PRO_ACCURACY) {
            runBackgroundCastDiscovery(enrichedProject.id)
        }
        val needsTranslation = !isSameResolvedLanguage(
            sourceLanguage = resolvedSourceLang,
            targetLanguage = baseProject.targetLanguage,
            sampleTexts = attributedEvents.map { it.sourceText },
        )
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
                    maxOf(state.scoutCompletedWindows, scoutCompletedExtents.size) >= initRequiredWins,
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
                ) || (hasCompletedWindowAt(0L) && hasRealSubs) ||
                    (cur.scoutCompletedWindows > 0 && hasRealSubs)
                val completedOpenCount = if (cur.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                    maxOf(cur.scoutCompletedWindows, scoutCompletedExtents.size)
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
        val evaluated = readinessController.evaluateCoverage(coverageLedger)
        val allowAutoStart = kind != VideoSourceKind.DEMO_SCENARIO &&
            evaluated.userIntent == UserPlaybackIntent.PLAY &&
            !evaluated.isBufferingHoldActive &&
            evaluated.state != PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR

        playbackClockAnchor.reset(0L, state.playbackSpeed, playing = allowAutoStart)
        if (!allowAutoStart) {
            if (kind == VideoSourceKind.LOCAL_MP4) {
                exoPlayer.pause()
            }
            val pauseSeq = if (kind == VideoSourceKind.YOUTUBE_COMPLIANT || kind == VideoSourceKind.WEB_STREAM) {
                ++nextStreamCommandSeq
            } else {
                null
            }
            _uiState.update { cur ->
                cur.copy(
                    isCinemaIntroActive = false,
                    introRemainingMs = 0L,
                    isOpeningSpeakersReady = !evaluated.isBufferingHoldActive &&
                        evaluated.state != PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR,
                    isScoutCatchUpBuffering = evaluated.isBufferingHoldActive && evaluated.userIntent != UserPlaybackIntent.PAUSE,
                    isPlaying = false,
                    currentTimeMs = 0L,
                    streamPlayRequest = pauseSeq?.let { it to false } ?: cur.streamPlayRequest,
                    readinessSnapshot = evaluated,
                )
            }
            return
        }

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
                        isScoutCatchUpBuffering = false,
                        isPlaying = true,
                        currentTimeMs = 0L,
                        youtubeSeekRequest = seekSeq to 0L,
                        streamPlayRequest = playSeq to true,
                        readinessSnapshot = evaluated,
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
                isScoutCatchUpBuffering = false,
                currentTimeMs = 0L,
                isPlaying = kind == VideoSourceKind.LOCAL_MP4,
                readinessSnapshot = evaluated,
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
        val preState = _uiState.value
        val previous = preState.displayMode.normalizedPrimaryMode()
        if (previous.shouldShowBubbles != normalized.shouldShowBubbles) {
            layoutEngine.clearSmoothing()
        }
        val nextReadiness = readinessController.onDisplayModeChanged(normalized, coverageLedger)
        val kind = preState.project.videoSource.kind
        val canResumeForOffMode = normalized == CaptionDisplayMode.OFF &&
            nextReadiness.state == PlaybackReadinessState.PLAYING &&
            nextReadiness.userIntent == UserPlaybackIntent.PLAY &&
            !nextReadiness.isBufferingHoldActive
        val mustPauseForHold = normalized != CaptionDisplayMode.OFF &&
            nextReadiness.isBufferingHoldActive

        val cmdSeq = if ((canResumeForOffMode && !preState.isPlaying) || (mustPauseForHold && preState.isPlaying)) {
            if (kind == VideoSourceKind.LOCAL_MP4) {
                if (canResumeForOffMode) exoPlayer.play() else exoPlayer.pause()
                null
            } else if (kind == VideoSourceKind.YOUTUBE_COMPLIANT || kind == VideoSourceKind.WEB_STREAM) {
                ++nextStreamCommandSeq
            } else {
                null
            }
        } else {
            null
        }
        if (canResumeForOffMode) {
            playbackClockAnchor.reset(preState.currentTimeMs, preState.playbackSpeed, playing = true)
        } else if (mustPauseForHold) {
            playbackClockAnchor.reset(preState.currentTimeMs, preState.playbackSpeed, playing = false)
        }

        _uiState.update { state ->
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = state.project,
                timeMs = state.currentTimeMs,
                styleSettings = state.styleSettings,
                liveFaces = currentLiveFaces,
                displayMode = normalized,
            )
            val resolvedIsPlaying = when {
                canResumeForOffMode -> true
                mustPauseForHold -> false
                else -> state.isPlaying
            }
            val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
            state.copy(
                displayMode = normalized,
                isPlaying = resolvedIsPlaying,
                isScoutCatchUpBuffering = nextReadiness.isBufferingHoldActive,
                streamPlayRequest = cmdSeq?.let { it to canResumeForOffMode } ?: state.streamPlayRequest,
                readinessSnapshot = nextReadiness,
                sentinelSnapshot = sentinelSnap,
                humanReadiness = HumanReadableStatus.resolve(
                    project = state.project,
                    scoutState = state.scoutCaptionState,
                    coverageSummary = state.coverageSummary,
                    readinessSnapshot = nextReadiness,
                    releaseSnapshot = state.releaseSnapshot,
                    sentinelSnapshot = sentinelSnap,
                ),
                placedBalloons = balloons,
                activeChapter = activeChapter,
            )
        }
        viewModelScope.launch { projectStore.saveDisplayMode(normalized) }
    }

    fun setTranslationMode(mode: TranslationMode) {
        val updated = _uiState.value.project.copy(translationMode = mode)
        releaseStore.updateSeekOrConfigGeneration(
            configFingerprint = currentConfigFingerprint(updated, _uiState.value.quotaSaverMode),
        )
        updateProject(updated)
    }

    fun updateStyleSettings(settings: BalloonStyleSettings) {
        _uiState.update { state ->
            val proj = state.project.copy(styleSettings = settings)
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = proj,
                timeMs = state.currentTimeMs,
                styleSettings = settings,
                liveFaces = currentLiveFaces,
                displayMode = state.displayMode,
            )
            state.copy(
                styleSettings = settings,
                project = proj,
                placedBalloons = balloons,
                activeChapter = activeChapter,
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
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val currIdx = speeds.indexOfFirst { kotlin.math.abs(it - _uiState.value.playbackSpeed) < 0.05f }
        val nextSpeed = speeds[(currIdx + 1) % speeds.size]
        setPlaybackSpeed(nextSpeed)
    }

    fun setPlaybackSpeed(speed: Float) {
        val nextSpeed = speed.coerceIn(0.5f, 2.0f)
        val cur = _uiState.value
        if (cur.project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
            exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
        } else {
            playbackClockAnchor.updatePlaybackState(cur.currentTimeMs, nextSpeed, cur.isPlaying)
        }
        val speedSnap = readinessController.setPlaybackSpeed(nextSpeed)
        sentinelCoordinator.onPlaybackSpeedChanged(nextSpeed)
        val sentinelSnap = sentinelCoordinator.inspectorSnapshot()
        _uiState.update {
            it.copy(
                playbackSpeed = nextSpeed,
                readinessSnapshot = speedSnap,
                sentinelSnapshot = sentinelSnap,
            )
        }
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
        statusBannerJob?.cancel()
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
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = previous,
                timeMs = state.currentTimeMs,
                styleSettings = state.styleSettings,
                liveFaces = currentLiveFaces,
                displayMode = state.displayMode,
            )
            state.copy(
                project = previous,
                canUndo = undoStack.isNotEmpty(),
                placedBalloons = balloons,
                activeChapter = activeChapter,
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

    fun quickAssignSpeakerFromPicker(
        eventId: String,
        speakerId: String,
        applyToAllLinesOfPreviousSpeaker: Boolean = false,
    ) {
        if (!applyToAllLinesOfPreviousSpeaker) {
            // Single-line safe reassignment: lock ONLY this line and never silently rewrite unrelated lines
            pushUndoSnapshot()
            val current = _uiState.value.project
            val updatedEvents = current.dialogueEvents.map { evt ->
                if (evt.id == eventId) {
                    evt.copy(
                        speakerId = speakerId,
                        speakerConfidence = 1.0f,
                        lockedByUser = true,
                    )
                } else {
                    evt
                }
            }
            val updatedSpeakers = current.speakers.map { sp ->
                if (sp.id == speakerId) sp.copy(isIdentityConfirmed = true, isLocked = true) else sp
            }
            updateProject(current.copy(speakers = updatedSpeakers, dialogueEvents = updatedEvents))
        } else {
            // Explicit user-chosen broader reassignment across unlocked lines of the previous speaker
            pushUndoSnapshot()
            val current = _uiState.value.project
            val previousSpeakerId = current.dialogueEvents.firstOrNull { it.id == eventId }?.speakerId
            val updatedEvents = current.dialogueEvents.map { evt ->
                when {
                    evt.id == eventId -> evt.copy(
                        speakerId = speakerId,
                        speakerConfidence = 1.0f,
                        lockedByUser = true,
                    )
                    previousSpeakerId != null &&
                        previousSpeakerId != speakerId &&
                        evt.speakerId == previousSpeakerId &&
                        !evt.lockedByUser -> evt.copy(
                        speakerId = speakerId,
                        speakerConfidence = maxOf(evt.speakerConfidence, 0.88f),
                    )
                    else -> evt
                }
            }
            val updatedSpeakers = current.speakers.map { sp ->
                if (sp.id == speakerId) sp.copy(isIdentityConfirmed = true, isLocked = true) else sp
            }
            updateProject(current.copy(speakers = updatedSpeakers, dialogueEvents = updatedEvents))
        }
        dismissQuickSpeakerPicker(resumePlayback = true)
    }

    fun mergeSpeakers(sourceSpeakerId: String, targetSpeakerId: String) {
        if (sourceSpeakerId == targetSpeakerId) return
        val current = _uiState.value.project
        val source = current.speakers.firstOrNull { it.id == sourceSpeakerId } ?: return
        val target = current.speakers.firstOrNull { it.id == targetSpeakerId } ?: return
        pushUndoSnapshot()
        val updatedEvents = current.dialogueEvents.map { evt ->
            if (evt.speakerId == sourceSpeakerId) evt.copy(speakerId = targetSpeakerId) else evt
        }
        val updatedTracks = current.faceTracks.map { track ->
            if (track.speakerId == sourceSpeakerId) track.copy(speakerId = targetSpeakerId) else track
        }
        val updatedSpeakers = current.speakers
            .filterNot { it.id == sourceSpeakerId }
            .map { sp ->
                if (sp.id == targetSpeakerId) {
                    sp.copy(
                        isLocked = true,
                        isIdentityConfirmed = true,
                        preferredScreenSideX = sp.preferredScreenSideX ?: source.preferredScreenSideX,
                        previousNames = (sp.previousNames + source.displayName).distinct(),
                    )
                } else {
                    sp
                }
            }
        updateProject(
            current.copy(
                speakers = updatedSpeakers,
                dialogueEvents = updatedEvents,
                faceTracks = updatedTracks,
            ),
            banner = "Merged ${source.displayName} into ${target.displayName}",
        )
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
        var substantiveSourceEdited = false
        var editedNeedsRetranslation = false
        var editedEventResult: DialogueEvent? = null
        val updatedEvents = current.dialogueEvents.map { evt ->
            if (evt.id != eventId) evt else {
                val newStart = (startTimeMs ?: evt.startTimeMs).coerceAtLeast(0L)
                val newEnd = (endTimeMs ?: evt.endTimeMs).coerceAtLeast(newStart + 300L)
                val newType = dialogueType ?: evt.dialogueType
                val nextSource = sourceText ?: evt.sourceText
                val sourceChanged = sourceText != null && nextSource.trim() != evt.sourceText.trim()
                val sameLangForCue = isSameResolvedLanguage(
                    sourceLanguage = current.sourceLanguage,
                    targetLanguage = current.targetLanguage,
                    sampleTexts = listOf(nextSource),
                )
                val resolvedTranslated: String
                val resolvedNeedsTranslation: Boolean
                if (translatedText != null) {
                    resolvedTranslated = translatedText
                    resolvedNeedsTranslation = translatedText.isBlank() && !sameLangForCue
                } else if (sourceChanged) {
                    substantiveSourceEdited = true
                    if (sameLangForCue) {
                        resolvedTranslated = nextSource
                        resolvedNeedsTranslation = false
                    } else {
                        // Invalidate obsolete target translation when source wording changes mid-request
                        resolvedTranslated = ""
                        resolvedNeedsTranslation = true
                        editedNeedsRetranslation = true
                    }
                } else {
                    resolvedTranslated = evt.translatedText
                    resolvedNeedsTranslation = evt.needsTranslation
                }
                val nextRev = if (sourceChanged) evt.sourceTextRevision + 1 else evt.sourceTextRevision
                val nextTrRev = if (!resolvedNeedsTranslation && resolvedTranslated.isNotBlank()) {
                    nextRev
                } else {
                    evt.translationSourceRevision
                }
                val nextEvt = evt.copy(
                    sourceText = nextSource,
                    translatedText = resolvedTranslated,
                    dialogueType = newType,
                    startTimeMs = newStart,
                    endTimeMs = newEnd,
                    lockedByUser = lockedByUser ?: if (sourceChanged || translatedText != null) true else evt.lockedByUser,
                    needsTranslation = resolvedNeedsTranslation,
                    stableCueKey = evt.stableCueKey.ifBlank { evt.id },
                    sourceTextRevision = nextRev,
                    translationSourceRevision = nextTrRev,
                    placementMode = if (newType != DialogueType.SPOKEN && evt.placementMode == PlacementMode.AUTO) {
                        PlacementMode.EDGE_DOCKED
                    } else if (newType == DialogueType.SPOKEN && evt.placementMode == PlacementMode.EDGE_DOCKED) {
                        PlacementMode.AUTO
                    } else {
                        evt.placementMode
                    },
                )
                editedEventResult = nextEvt
                nextEvt
            }
        }
        if (substantiveSourceEdited) {
            sourceEditRevision.incrementAndGet()
            aiTranslationJob?.cancel()
            autoAnalyzedEventIds.remove(eventId)
        }
        editedEventResult?.let { edited ->
            val snap = releaseStore.syncEditedEvent(edited, invalidateOverlappingDrafts = substantiveSourceEdited)
            _uiState.update { it.copy(releaseSnapshot = snap) }
        }
        updateProject(current.copy(dialogueEvents = updatedEvents))
        if (editedNeedsRetranslation &&
            _uiState.value.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD &&
            !quotaCircuitBreakerTripped
        ) {
            translateCurrentProjectWithAi(silentBackground = true)
        }
    }

    /**
     * Updates active source/target languages cleanly: cancels in-flight translation/lookahead jobs,
     * bumps config fingerprint so late results from old language pairs are rejected, invalidates
     * unlocked stale translations while preserving committed anchors/speakers/user-locked lines,
     * and queues targeted repair/translation when needed.
     */
    fun updateLanguageConfiguration(
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
    ) {
        val current = _uiState.value.project
        val nextSource = sourceLanguage?.trim()?.ifBlank { current.sourceLanguage } ?: current.sourceLanguage
        val nextTarget = targetLanguage?.trim()?.ifBlank { current.targetLanguage } ?: current.targetLanguage
        if (nextSource.equals(current.sourceLanguage, ignoreCase = true) &&
            nextTarget.equals(current.targetLanguage, ignoreCase = true)
        ) {
            return
        }
        pushUndoSnapshot()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        lookaheadJob?.cancel()
        healBeaconJob?.cancel()
        clearCapturedAudioQueueSync()
        sourceEditRevision.incrementAndGet()
        autoAnalyzedEventIds.clear()
        cachedAiTurnHints.clear()

        val updatedEvents = current.dialogueEvents.map { evt ->
            if (evt.lockedByUser && !evt.needsTranslation) {
                evt
            } else if (evt.sourceText.isBlank() ||
                evt.sourceText.startsWith("Embedded YouTube Player active") ||
                evt.sourceText.startsWith("Web Cinema ready")
            ) {
                evt
            } else {
                val cueSameLang = isSameResolvedLanguage(
                    sourceLanguage = nextSource,
                    targetLanguage = nextTarget,
                    sampleTexts = listOf(evt.sourceText),
                )
                if (cueSameLang) {
                    evt.copy(
                        translatedText = evt.sourceText,
                        needsTranslation = false,
                        stableCueKey = evt.stableCueKey.ifBlank { evt.id },
                        translationSourceRevision = evt.sourceTextRevision,
                    )
                } else {
                    evt.copy(
                        translatedText = "",
                        needsTranslation = true,
                        stableCueKey = evt.stableCueKey.ifBlank { evt.id },
                    )
                }
            }
        }
        val supersededProj = HealBeaconEngine.supersedeOrCancelStaleBeacons(
            project = current,
            activeGenerationToken = _uiState.value.seekGeneration,
            reason = "Language configuration changed ($nextSource -> $nextTarget)",
        )
        sentinelCoordinator.cancelOrSupersedeStaleHealTasks(_uiState.value.seekGeneration)
        val updatedProj = supersededProj.copy(
            sourceLanguage = nextSource,
            targetLanguage = nextTarget,
            dialogueEvents = updatedEvents,
        )
        val resolvedSameAll = isSameResolvedLanguage(
            sourceLanguage = nextSource,
            targetLanguage = nextTarget,
            sampleTexts = updatedEvents.map { it.sourceText },
        )
        releaseStore.updateSeekOrConfigGeneration(
            configFingerprint = currentConfigFingerprint(updatedProj, _uiState.value.quotaSaverMode),
        )
        val relSnap = releaseStore.invalidateTargetTranslations(sameLanguageResolved = resolvedSameAll)
        sentinelCoordinator.updatePendingReviewCount(releaseStore.pendingDraftCount())
        updateProject(
            updatedProj,
            banner = "Language updated ($nextSource → $nextTarget)",
        )
        _uiState.update {
            it.copy(
                isTranslatingAi = false,
                releaseSnapshot = relSnap,
                sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
            )
        }
        wakeScheduler("language_config_changed")
        if (!resolvedSameAll && updatedEvents.any { it.needsTranslation }) {
            translateCurrentProjectWithAi(overrideTargetLanguage = nextTarget, silentBackground = true)
        }
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
            stableCueKey = first.stableCueKey.ifBlank { first.id },
            parentUtteranceKey = first.parentUtteranceKey.ifBlank { first.canonicalIdentityKey },
            sourceSpanStartMs = minOf(first.sourceSpanStartMs ?: first.startTimeMs, second.sourceSpanStartMs ?: second.startTimeMs),
            sourceSpanEndMs = maxOf(first.sourceSpanEndMs ?: first.endTimeMs, second.sourceSpanEndMs ?: second.endTimeMs),
            sourceTextRevision = maxOf(first.sourceTextRevision, second.sourceTextRevision) + 1,
        )
        sorted[idx] = merged
        sorted.removeAt(idx + 1)
        val snap = releaseStore.syncEditedEvent(merged, invalidateOverlappingDrafts = true)
        _uiState.update { it.copy(releaseSnapshot = snap) }
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
        val rootKey = evt.parentUtteranceKey.ifBlank { evt.canonicalIdentityKey }
        val baseChildKey = evt.stableCueKey.ifBlank { evt.id }.substringBefore("#p")
        val nextRev = evt.sourceTextRevision + 1

        val leftEvt = evt.copy(
            endTimeMs = mid,
            sourceText = src1,
            translatedText = tr1,
            stableCueKey = "${baseChildKey}#p1",
            parentUtteranceKey = rootKey,
            sourceSpanStartMs = evt.startTimeMs,
            sourceSpanEndMs = mid,
            sourceTextRevision = nextRev,
            translationSourceRevision = nextRev,
        )
        val rightEvt = evt.copy(
            id = SubtitleParser.newEventId(),
            startTimeMs = mid + 1L,
            sourceText = src2,
            translatedText = tr2,
            stableCueKey = "${baseChildKey}#p2",
            parentUtteranceKey = rootKey,
            sourceSpanStartMs = mid + 1L,
            sourceSpanEndMs = evt.endTimeMs,
            sourceTextRevision = nextRev,
            translationSourceRevision = nextRev,
        )
        sorted[idx] = leftEvt
        sorted.add(idx + 1, rightEvt)
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
                        isUserNamed = true,
                        nameRevealedAtMs = null,
                        revealProvenance = "USER_RENAME",
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
        val sessionToken = nextSessionToken()
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        changeoverPreListenJob?.cancel()
        healBeaconJob?.cancel()
        seekAckTimeoutJob?.cancel()
        statusBannerJob?.cancel()
        clearCapturedAudioQueueSync()
        introJob?.cancel()
        lookaheadJob?.cancel()
        scoutCompletedExtents.clear()
        scoutAudioSliceTimestamps.clear()
        capturedYouTubeTimedTextKeys.clear()
        autoAnalyzedEventIds.clear()
        settledAttributedEventIds.clear()
        lastSceneSideResetKey = ""
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        lastDetectorFrameId = -1L
        measuredBubbleSizes.clear()
        undoStack.clear()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        layoutEngine.clearSmoothing()
        layoutEngine.stabilityDiagnostics.reset()
        playbackClockAnchor.reset(600L, _uiState.value.playbackSpeed, false)
        val demo = DemoScenarioFactory.createDemoProject().copy(styleSettings = _uiState.value.styleSettings)
        contextEngine.resetForProject(demo)
        autoSelfHealDetector.resetForProject(demo.id)
        coverageLedger.reset(demo.videoSource.durationMs, source = SubtitleSourceKind.BUILT_IN_DEMO)
        sentinelCoordinator.resetForProject(demo)
        val restoredGen = sentinelCoordinator.currentSchedulingGeneration()
        releaseStore.resetSession(
            mediaId = demo.id,
            sessionToken = sessionToken,
            seekGeneration = restoredGen,
            configFingerprint = currentConfigFingerprint(demo, _uiState.value.quotaSaverMode),
        )
        val demoReadiness = readinessController.resetForMedia(
            initialPositionMs = 600L,
            durationMs = demo.videoSource.durationMs,
            requiresCoverageGate = false,
            initialIntent = UserPlaybackIntent.PAUSE,
            playbackSpeed = _uiState.value.playbackSpeed,
            initialSeekGeneration = restoredGen,
        )
        _uiState.update {
            val activeMode = it.displayMode.normalizedPrimaryMode()
            it.copy(
                project = demo,
                activeDestination = AppDestination.WATCH,
                showStartLauncher = false,
                hasPendingPlayIntro = false,
                canUndo = false,
                displayMode = activeMode,
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
                scoutCaptureDiagnostics = ScoutCaptureDiagnostics(),
                youtubePlayerErrorKind = null,
                liveFaceExclusionRects = emptyList(),
                bufferedWindowRanges = emptyList(),
                scoutCompletedWindows = 0,
                scoutTotalWindows = 0,
                scoutFailedSliceCount = 0,
                coverageSummary = CoverageSummary(),
                transcriptDiagnostics = TranscriptDiagnostics(),
                readinessSnapshot = demoReadiness,
                visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
                sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                releaseSnapshot = releaseStore.snapshot(),
                seekGeneration = restoredGen,
                statusBanner = null,
            )
        }
        showStatusBanner("Loaded interactive 2-speaker Demo Scenario")
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
        val sessionToken = nextSessionToken()
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        changeoverPreListenJob?.cancel()
        healBeaconJob?.cancel()
        seekAckTimeoutJob?.cancel()
        statusBannerJob?.cancel()
        clearCapturedAudioQueueSync()
        introJob?.cancel()
        lookaheadJob?.cancel()
        downgradedToLiteForStream = false
        scoutHeartbeatService.resetForNewStream(
            if (_uiState.value.quotaSaverMode == QuotaSaverMode.PRO_ACCURACY) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
        )
        scoutHeartbeatService.setZeroCloudPaused(_uiState.value.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD)
        _uiState.update {
            it.copy(
                scoutCaptionState = ScoutCaptionState.Idle,
                scoutCaptureDiagnostics = ScoutCaptureDiagnostics(),
                youtubePlayerErrorKind = null,
            )
        }
        scoutCompletedExtents.clear()
        scoutAudioSliceTimestamps.clear()
        capturedYouTubeTimedTextKeys.clear()
        autoAnalyzedEventIds.clear()
        settledAttributedEventIds.clear()
        lastSceneSideResetKey = ""
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        lastDetectorFrameId = -1L
        measuredBubbleSizes.clear()
        layoutEngine.stabilityDiagnostics.reset()
        coverageLedger.reset(0L, source = subtitleSource)
        _uiState.update {
            it.copy(
                liveFaceExclusionRects = emptyList(),
                bufferedWindowRanges = emptyList(),
                scoutCompletedWindows = 0,
                scoutTotalWindows = 0,
                scoutFailedSliceCount = 0,
                coverageSummary = CoverageSummary(),
                transcriptDiagnostics = TranscriptDiagnostics(),
            )
        }
        layoutEngine.clearSmoothing()
        playbackClockAnchor.reset(0L, _uiState.value.playbackSpeed, false)

        analysisJob = viewModelScope.launch {
            if (!isCurrentSession(sessionToken)) return@launch
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

                if (!isCurrentSession(sessionToken)) return@launch
                if (cues.isNotEmpty() &&
                    (newProject.subtitleSource == SubtitleSourceKind.UPLOADED_FILE ||
                        newProject.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK)
                ) {
                    coverageLedger.markAuthoritativeTrack(
                        durationMs = durationMs,
                        events = initialEvents,
                        source = newProject.subtitleSource,
                    )
                } else {
                    coverageLedger.reset(durationMs, source = SubtitleSourceKind.AI_DRAFT)
                }

                // Prepare Media3 ExoPlayer immediately so playback is ready after first window
                exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
                exoPlayer.prepare()
                exoPlayer.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed)
                exoPlayer.volume = if (_uiState.value.isMuted) 0f else 1f

                undoStack.clear()
                contextEngine.resetForProject(newProject)
                autoSelfHealDetector.resetForProject(newProject.id)
                sentinelCoordinator.resetForProject(newProject)
                val restoredGen = sentinelCoordinator.currentSchedulingGeneration()
                releaseStore.resetSession(
                    mediaId = newProject.id,
                    sessionToken = sessionToken,
                    seekGeneration = restoredGen,
                    configFingerprint = currentConfigFingerprint(newProject, _uiState.value.quotaSaverMode),
                )
                val initReadiness = readinessController.resetForMedia(
                    initialPositionMs = 0L,
                    durationMs = durationMs,
                    requiresCoverageGate = cues.isEmpty(),
                    initialIntent = UserPlaybackIntent.PAUSE,
                    playbackSpeed = _uiState.value.playbackSpeed,
                    initialSeekGeneration = restoredGen,
                )
                _uiState.update {
                    val activeMode = it.displayMode.normalizedPrimaryMode()
                    val (balloons, activeChapter) = computeContextAwareLayout(
                        project = newProject,
                        timeMs = 0L,
                        styleSettings = it.styleSettings,
                        liveFaces = currentLiveFaces,
                        displayMode = activeMode,
                    )
                    it.copy(
                        project = newProject,
                        activeDestination = AppDestination.WATCH,
                        showStartLauncher = false,
                        hasPendingPlayIntro = true,
                        displayMode = activeMode,
                        isCinemaIntroActive = false,
                        introRemainingMs = 0L,
                        canUndo = false,
                        currentTimeMs = 0L,
                        isPlaying = false,
                        scoutTimeMs = 0L,
                        scoutedUpToMs = 0L,
                        scoutStatusText = "Scout buffering opening 7s context in background...",
                        youtubeNativeCcTrackCount = null,
                        youtubeNativeCcLang = "",
                        youtubeNativeCcState = YouTubeNativeCcState.OFF,
                        youtubeNativeCcTrackEnabled = false,
                        youtubeNativeCcProbeBlocked = false,
                        seekGeneration = restoredGen,
                        readinessSnapshot = initReadiness,
                        sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                        releaseSnapshot = releaseStore.snapshot(),
                        visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
                        placedBalloons = balloons,
                        activeChapter = activeChapter,
                        statusBanner = "Video loaded · Scout pre-buffering transcript & faces. Press Play for Smart Pre-Roll.",
                    )
                }
                syncCoverageUiState()

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
                        if (!isCurrentSession(sessionToken, newProject.id)) return@launch
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
                if (!isSameResolvedLanguage(sourceLanguage, targetLanguage, eventsWithAudio.map { it.sourceText })) {
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
        // Reset FAILED interval retry counters so an explicit user Retry heals unresolved gaps
        val resetIntervals = coverageLedger.intervalsSnapshot().map { iv ->
            if (!iv.verified) iv.copy(status = CoverageStatus.PENDING, retryAttempts = 0) else iv
        }
        if (resetIntervals.isNotEmpty()) {
            coverageLedger.reset(
                durationMs = current.videoSource.durationMs.coerceAtLeast(60_000L),
                seedIntervals = resetIntervals,
                source = current.subtitleSource,
            )
            syncCoverageUiState()
        }
        val hasExisting = LookaheadWindowPlanner.hasRealSubtitles(current.dialogueEvents)
        wakeScheduler("user_retry_ai")
        runYouTubeScoutTranscriptPipeline(
            projectId = current.id,
            videoId = videoId,
            skipTierOneCaptions = hasExisting,
            resumeFromHorizonSec = 0,
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
        val sessionToken = activeSessionToken.get()
        val durationMs = current.videoSource.durationMs.coerceAtLeast(1000L)
        if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(current.subtitleSource, current.dialogueEvents)) {
            return
        }
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
            val chunks = audioAnalyzer.extractChunkedWavForAsr(
                uri = videoUri,
                chunkWindowMs = 50_000L,
                maxTotalMs = durationMs,
            )
            if (!isCurrentSession(sessionToken, current.id)) return
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

            coverageLedger.reset(durationMs, source = SubtitleSourceKind.AI_DRAFT)
            val mergedGlossary = (current.glossary + _uiState.value.glossaryEntries)
                .distinctBy { it.term.trim().lowercase() }

            val hintMap = mutableMapOf<String, AiTranslatedLine>()
            val generatedEvents = mutableListOf<DialogueEvent>()
            var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                quotaSaverMode = _uiState.value.quotaSaverMode,
                quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                downgradedToLite = downgradedToLiteForStream,
            )

            val requestCtx = captureWorkRequestContext(current, _uiState.value.quotaSaverMode)
            for ((idx, chunk) in chunks.withIndex()) {
                if (!canInvokeCloudAi(requestCtx, enforceBoundary = false, requireUnloadedTracks = true)) break
                val chunkProg = 0.86f + (0.10f * (idx.toFloat() / chunks.size.coerceAtLeast(1)))
                coverageLedger.recordWindowRequested()
                coverageLedger.markInProgress(chunk.startMs, chunk.endMs)
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
                var chunkRes = translationAiService.transcribeAndTranslateAudioDetailed(
                    wavBytes = chunk.wavBytes,
                    chunkStartMs = chunk.startMs,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    glossary = mergedGlossary,
                    preferLiteModel = activeTier.preferLite,
                    chunkDurationMs = (chunk.endMs - chunk.startMs).coerceAtLeast(1_000L),
                )
                if (!isRequestContextCurrent(requestCtx)) break
                if (chunkRes.isFailure &&
                    TranslationAiService.isQuotaOrCreditExhaustedError(chunkRes.exceptionOrNull()) &&
                    activeTier == ScoutExecutionTier.BEST_AI
                ) {
                    activeTier = ScoutExecutionTier.LITE_AI
                    downgradedToLiteForStream = true
                    coverageLedger.recordQuotaFallbackTransition()
                    if (!canInvokeCloudAi(requestCtx, enforceBoundary = false, requireUnloadedTracks = true)) break
                    chunkRes = translationAiService.transcribeAndTranslateAudioDetailed(
                        wavBytes = chunk.wavBytes,
                        chunkStartMs = chunk.startMs,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        glossary = mergedGlossary,
                        preferLiteModel = true,
                    )
                    if (!isRequestContextCurrent(requestCtx)) break
                }
                if (chunkRes.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(chunkRes.exceptionOrNull())) {
                    quotaCircuitBreakerTripped = true
                }
                if (chunkRes.isSuccess) {
                    val detailed = chunkRes.getOrThrow()
                    coverageLedger.recordWindowCompleted()
                    if (detailed.invalidCueCount > 0) {
                        coverageLedger.recordInvalidOrAmbiguousTimestamp(detailed.invalidCueCount)
                    }
                    val cues = detailed.cues
                    val status = when (detailed.outcome) {
                        TranscriptionOutcome.DIALOGUE_DETECTED -> CoverageStatus.VERIFIED_DIALOGUE
                        TranscriptionOutcome.VERIFIED_SILENCE -> CoverageStatus.VERIFIED_SILENCE
                        TranscriptionOutcome.PARTIAL_SUCCESS -> CoverageStatus.PARTIAL_RECOVERY
                        else -> CoverageStatus.FAILED
                    }
                    coverageLedger.recordOutcome(
                        startMs = chunk.startMs,
                        endMs = chunk.endMs,
                        status = status,
                        source = SubtitleSourceKind.AI_DRAFT,
                        dialogueCount = cues.size,
                        failureReason = if (status == CoverageStatus.FAILED || status == CoverageStatus.PARTIAL_RECOVERY) {
                            detailed.diagnosticNote.ifBlank { detailed.outcome.name }
                        } else {
                            ""
                        },
                        incrementRetry = status == CoverageStatus.FAILED,
                    )
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
                } else {
                    coverageLedger.recordOutcome(
                        startMs = chunk.startMs,
                        endMs = chunk.endMs,
                        status = CoverageStatus.FAILED,
                        source = SubtitleSourceKind.AI_DRAFT,
                        failureReason = chunkRes.exceptionOrNull()?.localizedMessage?.take(120) ?: "Audio transcription error",
                        incrementRetry = true,
                    )
                }
                syncCoverageUiState(totalWindowHint = chunks.size)
            }

            if (!isCurrentSession(sessionToken, current.id) || !isRequestContextCurrent(requestCtx)) return
            val finalSummary = coverageLedger.summary()
            if (generatedEvents.isEmpty()) {
                val msg = if (finalSummary.isFullyVerified) {
                    "Verified silence across audio track — no spoken dialogue detected."
                } else {
                    "Auto-transcription left ${finalSummary.unresolvedGapCount} unverified gap(s) — retry or attach SRT."
                }
                _uiState.update {
                    it.copy(
                        isTranslatingAi = false,
                        analysisStatus = AnalysisStatus.Ready,
                        statusBanner = msg,
                    )
                }
                return
            }

            val pacedEvents = SubtitleParser.applyReadingPacingGuardrails(generatedEvents)
            val audioFeaturesMap = audioAnalyzer.extractVoiceFeatures(videoUri, pacedEvents)
            val withAudio = pacedEvents.map { evt ->
                evt.copy(audioFeatures = audioFeaturesMap[evt.id] ?: evt.audioFeatures)
            }
            if (!isCurrentSession(sessionToken, current.id)) return
            val latestProj = _uiState.value.project
            if (LookaheadWindowPlanner.hasLoadedSubtitlesLocked(latestProj.subtitleSource, latestProj.dialogueEvents)) {
                return
            }
            val (updatedSpeakers, attributed) = attributionEngine.assignSpeakers(
                events = withAudio,
                shots = latestProj.shots,
                faceTracks = latestProj.faceTracks,
                existingSpeakers = latestProj.speakers,
                turnHints = hintMap,
                rememberedCharacterColors = _uiState.value.characterColorMemory,
            )
            pushUndoSnapshot()
            val coverageBanner = if (finalSummary.isFullyVerified) {
                "Auto-transcribed ${attributed.size} dialogue cues (100% verified)"
            } else {
                "Auto-transcribed ${attributed.size} cues (${(finalSummary.verifiedFraction * 100).toInt()}% verified, ${finalSummary.unresolvedGapCount} gap(s))"
            }
            updateProject(
                latestProj.copy(
                    subtitleSource = SubtitleSourceKind.AI_DRAFT,
                    speakers = updatedSpeakers,
                    dialogueEvents = attributed,
                ),
                banner = coverageBanner,
            )
            syncCoverageUiState(totalWindowHint = chunks.size)
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
        if (state.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD) return
        if (sentinelCoordinator.isScrubberPreviewActive()) return
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

        val anchorMs = sentinelCoordinator.currentAcknowledgedViewerPosMs()
        val boundaryCapMs = sentinelCoordinator.currentBoundaryEndMs()
        val lookaheadWindowEnd = minOf(anchorMs + 35_000L, boundaryCapMs)
        if (lookaheadWindowEnd <= anchorMs) return
        val hasPendingLookahead = proj.dialogueEvents.any { evt ->
            !evt.lockedByUser &&
                evt.endTimeMs >= (anchorMs - 2_000L) &&
                evt.startTimeMs <= lookaheadWindowEnd &&
                !evt.sourceText.startsWith("Embedded YouTube Player active") &&
                !evt.sourceText.startsWith("Web Cinema ready") &&
                evt.id !in autoAnalyzedEventIds
        }
        if (!hasPendingLookahead) return

        lastAutoAiTriggerElapsedMs = nowElapsed
        val quotaMode = state.quotaSaverMode
        val needsTranslation = !isSameResolvedLanguage(
            sourceLanguage = proj.sourceLanguage,
            targetLanguage = proj.targetLanguage,
            sampleTexts = proj.dialogueEvents
                .filter { !it.lockedByUser && it.endTimeMs >= (anchorMs - 2_000L) && it.startTimeMs <= lookaheadWindowEnd }
                .map { it.sourceText },
        )
        if (needsTranslation) {
            translateCurrentProjectWithAi(silentBackground = true)
        } else if (quotaMode == QuotaSaverMode.PRO_ACCURACY) {
            analyzeSpeakersWithContextAndAudio(silentBackground = true)
        } else {
            // Same-language captions in Quota-Saver / Zero-Cloud: mark window scanned without burning cloud quota
            proj.dialogueEvents
                .filter { it.endTimeMs >= (anchorMs - 2_000L) && it.startTimeMs <= lookaheadWindowEnd }
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
            val windowSlice = sortedAll.subList(startIdx, endIdx)

            val isSameLanguage = isSameResolvedLanguage(
                sourceLanguage = current.sourceLanguage,
                targetLanguage = targetLang,
                sampleTexts = windowSlice.map { it.sourceText },
            )

            // Same resolved language: preserve faithful source wording directly without redundant translation calls!
            if (isSameLanguage) {
                windowSlice.forEach { autoAnalyzedEventIds.add(it.id) }
                val preservedEvents = current.dialogueEvents.map { evt ->
                    if (evt.sourceText.isNotBlank() &&
                        !evt.lockedByUser &&
                        (evt.translatedText.isBlank() || evt.needsTranslation)
                    ) {
                        evt.copy(translatedText = evt.sourceText, needsTranslation = false)
                    } else {
                        evt
                    }
                }
                val (updatedSpeakers, attributed) = attributionEngine.assignSpeakers(
                    events = preservedEvents,
                    shots = current.shots,
                    faceTracks = current.faceTracks,
                    existingSpeakers = current.speakers,
                    turnHints = cachedAiTurnHints,
                    rememberedCharacterColors = _uiState.value.characterColorMemory,
                )
                updateProject(
                    current.copy(targetLanguage = targetLang, speakers = updatedSpeakers, dialogueEvents = attributed),
                    banner = if (silentBackground) null else "Same-language cues active locally ($targetLang · $0 credits)",
                )
                _uiState.update { it.copy(isTranslatingAi = false, analysisStatus = AnalysisStatus.Ready) }
                return@launch
            }

            val srcNorm = Scout2IndependentReviewer.normalizeLanguageTag(current.sourceLanguage)
            val tgtNorm = Scout2IndependentReviewer.normalizeLanguageTag(targetLang)
            val translatable = windowSlice.filter { evt ->
                if (evt.lockedByUser && !evt.needsTranslation) return@filter false
                // For declared matching languages with code-switched lines, only translate foreign-script or flagged segments
                if (srcNorm.isNotBlank() && srcNorm == tgtNorm) {
                    val segScript = Scout2IndependentReviewer.detectDominantScriptLanguage(evt.sourceText)
                    evt.needsTranslation || (segScript.isNotBlank() && segScript != tgtNorm)
                } else {
                    evt.needsTranslation ||
                        evt.translatedText.isBlank() ||
                        !targetLang.equals(current.targetLanguage, ignoreCase = true) ||
                        evt.id !in autoAnalyzedEventIds
                }
            }
            if (translatable.isEmpty()) {
                _uiState.update { it.copy(isTranslatingAi = false, analysisStatus = AnalysisStatus.Ready) }
                return@launch
            }

            // Step 1: Check persistent line translation cache first ($0 cost)
            val cachedLineUpdates = mutableMapOf<String, Pair<String, String>>()
            val uncachedEvents = mutableListOf<DialogueEvent>()
            translatable.forEach { evt ->
                val cacheKey = ProjectStore.buildLineTranslationCacheKey(evt.sourceText, current.sourceLanguage, targetLang)
                val cachedText = inMemoryTranslationCache[cacheKey]
                if (!cachedText.isNullOrBlank()) {
                    cachedLineUpdates[evt.id] = evt.sourceText to cachedText
                    autoAnalyzedEventIds.add(evt.id)
                } else {
                    uncachedEvents.add(evt)
                }
            }
            if (cachedLineUpdates.isNotEmpty()) {
                val latestProj = _uiState.value.project
                val patchedEvents = SubtitleParser.applyReadingPacingGuardrails(
                    latestProj.dialogueEvents.map { evt ->
                        val (expectedSrc, cachedTr) = cachedLineUpdates[evt.id] ?: return@map evt
                        if (evt.sourceText != expectedSrc) return@map evt
                        if (evt.lockedByUser && !evt.needsTranslation) return@map evt
                        evt.copy(translatedText = cachedTr, needsTranslation = false)
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
                val effectiveProjectForCtx = if (!targetLang.equals(current.targetLanguage, ignoreCase = true)) {
                    val synced = current.copy(targetLanguage = targetLang)
                    _uiState.update { s ->
                        if (s.project.id == current.id) s.copy(project = s.project.copy(targetLanguage = targetLang)) else s
                    }
                    synced
                } else {
                    current
                }
                val requestCtx = captureWorkRequestContext(effectiveProjectForCtx, quotaMode)

                val translatedById = mutableMapOf<String, AiTranslatedLine>()
                var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = quotaMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                var offset = 0
                var undoPushed = false
                while (offset < uncachedEvents.size) {
                    if (!canInvokeCloudAi(requestCtx, enforceBoundary = false)) break
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
                        shouldContinue = { canInvokeCloudAi(requestCtx, enforceBoundary = false) },
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
                                if (isRequestContextCurrent(requestCtx)) {
                                    applyLiveSpeakerUpdates(current.id, updates)
                                }
                            },
                        )
                    }
                    if (!isRequestContextCurrent(requestCtx) ||
                        result.exceptionOrNull() is ScoutHeartbeatService.ScoutExecutionGatedException
                    ) {
                        break
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
                            shouldContinue = { canInvokeCloudAi(requestCtx, enforceBoundary = false) },
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
                                    if (isRequestContextCurrent(requestCtx)) {
                                        applyLiveSpeakerUpdates(current.id, updates)
                                    }
                                },
                            )
                        }
                        if (!isRequestContextCurrent(requestCtx) ||
                            result.exceptionOrNull() is ScoutHeartbeatService.ScoutExecutionGatedException
                        ) {
                            break
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
                                            evt.copy(translatedText = tr, needsTranslation = false)
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
                    // Only mark an event analyzed when a non-blank translation actually succeeded so failed chunks remain retryable
                    chunk.forEach { evt ->
                        val trLine = batchMap[evt.id]
                        if (trLine != null && trLine.naturalTranslation.isNotBlank()) {
                            autoAnalyzedEventIds.add(evt.id)
                            inMemoryTranslationCache[ProjectStore.buildLineTranslationCacheKey(evt.sourceText, current.sourceLanguage, targetLang)] =
                                trLine.naturalTranslation
                        }
                    }
                    offset = end

                    val latestProj = _uiState.value.project
                    if (latestProj.id != current.id || !latestProj.targetLanguage.equals(targetLang, ignoreCase = true)) break
                    val dispatchedSrcById = chunk.associate { it.id to it.sourceText }

                    if (batchMap.isNotEmpty()) {
                        projectStore.saveTranslationCache(inMemoryTranslationCache)
                        val updatedEvents = SubtitleParser.applyReadingPacingGuardrails(
                            latestProj.dialogueEvents.map { evt ->
                                val aiLine = batchMap[evt.id] ?: return@map evt
                                val expectedSrc = dispatchedSrcById[evt.id]
                                // Reject stale translation if source text was edited mid-request or user locked target
                                if (expectedSrc != null && evt.sourceText != expectedSrc) return@map evt
                                if (evt.lockedByUser && !evt.needsTranslation) return@map evt
                                if (aiLine.naturalTranslation.isBlank()) {
                                    return@map evt.copy(needsTranslation = true)
                                }
                                evt.copy(
                                    translatedText = aiLine.naturalTranslation,
                                    literalText = aiLine.literalTranslation,
                                    romanization = aiLine.romanization,
                                    dialogueType = if (evt.lockedByUser) evt.dialogueType else aiLine.dialogueTypeHint,
                                    contextCueReason = aiLine.contextReason,
                                    culturalNote = aiLine.culturalNote.ifBlank { evt.culturalNote },
                                    needsTranslation = false,
                                    stableCueKey = evt.stableCueKey.ifBlank { evt.id },
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
                val requestCtx = captureWorkRequestContext(current, quotaMode)
                var matchedCount = 0
                var undoPushed = false
                var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = quotaMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                val chunks = targetWindow.chunked(15)
                for ((idx, chunk) in chunks.withIndex()) {
                    if (!canInvokeCloudAi(requestCtx, enforceBoundary = false)) break
                    var result = scoutHeartbeatService.executeWithBackoffRetry(
                        modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                        maxRetries = 2,
                        onWarmToken = {
                            com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L)
                        },
                        shouldContinue = { canInvokeCloudAi(requestCtx, enforceBoundary = false) },
                    ) {
                        translationAiService.analyzeConversationSpeakers(
                            events = chunk,
                            speakers = _uiState.value.project.speakers,
                            shotKeyframes = if (idx == 0) keyframes else emptyList(),
                            videoContext = buildVideoContextString(_uiState.value.project),
                            preferLiteModel = activeTier.preferLite,
                            onDiscoveredSpeakerUpdates = { updates ->
                                if (isRequestContextCurrent(requestCtx)) {
                                    applyLiveSpeakerUpdates(current.id, updates)
                                }
                            },
                        )
                    }
                    if (!isRequestContextCurrent(requestCtx) ||
                        result.exceptionOrNull() is ScoutHeartbeatService.ScoutExecutionGatedException
                    ) {
                        break
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
                            shouldContinue = { canInvokeCloudAi(requestCtx, enforceBoundary = false) },
                        ) {
                            translationAiService.analyzeConversationSpeakers(
                                events = chunk,
                                speakers = _uiState.value.project.speakers,
                                shotKeyframes = if (idx == 0) keyframes else emptyList(),
                                videoContext = buildVideoContextString(_uiState.value.project),
                                preferLiteModel = true,
                                onDiscoveredSpeakerUpdates = { updates ->
                                    if (isRequestContextCurrent(requestCtx)) {
                                        applyLiveSpeakerUpdates(current.id, updates)
                                    }
                                },
                            )
                        }
                        if (!isRequestContextCurrent(requestCtx) ||
                            result.exceptionOrNull() is ScoutHeartbeatService.ScoutExecutionGatedException
                        ) {
                            break
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
                        slotToSpeakerId = buildSlotToSpeakerIdMap(latestProj.speakers),
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
        val sessionToken = activeSessionToken.get()
        viewModelScope.launch {
            try {
                val raw = projectStore.readTextFromUri(uri).getOrThrow()
                val cues = SubtitleParser.parse(raw, fileName)
                if (cues.isEmpty()) {
                    _uiState.update { it.copy(statusBanner = "No valid SRT/VTT/ASS cues found in $fileName") }
                    return@launch
                }
                if (!isCurrentSession(sessionToken)) return@launch
                // Uploaded subtitle track is authoritative: stop lower-authority background AI Scout jobs immediately
                youtubeTranscriptJob?.cancel()
                lookaheadJob?.cancel()
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
                val effectiveDur = maxOf(
                    current.videoSource.durationMs,
                    attributed.maxOfOrNull { it.endTimeMs } ?: 0L,
                ).coerceAtLeast(1_000L)
                coverageLedger.markAuthoritativeTrack(
                    durationMs = effectiveDur,
                    events = attributed,
                    source = SubtitleSourceKind.UPLOADED_FILE,
                )
                pushUndoSnapshot()
                val updated = current.copy(
                    subtitleSource = SubtitleSourceKind.UPLOADED_FILE,
                    videoSource = current.videoSource.copy(durationMs = effectiveDur),
                    speakers = speakers,
                    dialogueEvents = attributed,
                )
                updateProject(
                    updated,
                    banner = "Loaded ${cues.size} authoritative cues from $fileName",
                )
                syncCoverageUiState()
                _uiState.update {
                    it.copy(
                        isTranslatingAi = false,
                        scoutStatusText = "Authoritative file loaded · ${attributed.size} bubbles",
                        scoutCaptionState = ScoutCaptionState.Ready("Attached", attributed.size, speakers.size),
                    )
                }
                if (updated.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                    persistYouTubeVideoCache(updated, coverageLedger.continuousVerifiedUpToMs(), sessionToken)
                }
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
                    canContinueCloud = { _uiState.value.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD && !quotaCircuitBreakerTripped },
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
                canContinueCloud = { _uiState.value.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD && !quotaCircuitBreakerTripped },
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
            val vid = project.videoSource.youtubeVideoId
            val sessionToken = activeSessionToken.get()
            viewModelScope.launch {
                val cached = projectStore.loadCachedTranscript(
                    ProjectStore.canonicalYouTubeCacheKey(vid, project.targetLanguage)
                )
                if (!isCurrentSession(sessionToken, project.id)) return@launch
                if (project.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK ||
                    project.subtitleSource == SubtitleSourceKind.UPLOADED_FILE
                ) {
                    coverageLedger.markAuthoritativeTrack(
                        durationMs = project.videoSource.durationMs,
                        events = project.dialogueEvents,
                        source = project.subtitleSource,
                    )
                } else if (cached != null) {
                    val restored = TranscriptCoverageLedger.restoreOrMigrate(cached, project.videoSource.durationMs)
                    coverageLedger.reset(
                        durationMs = maxOf(project.videoSource.durationMs, cached.durationMs),
                        seedIntervals = restored,
                        source = project.subtitleSource,
                    )
                } else {
                    coverageLedger.reset(project.videoSource.durationMs, source = project.subtitleSource)
                }
                syncCoverageUiState()
                val summary = coverageLedger.summary()
                val full = LookaheadWindowPlanner.hasLoadedSubtitlesLocked(project.subtitleSource, project.dialogueEvents) ||
                    summary.isFullyVerified
                _uiState.update {
                    it.copy(
                        scoutStatusText = if (full) {
                            "Pre-generated ${project.dialogueEvents.size} bubbles (100% verified)"
                        } else {
                            "Pre-generated ${project.dialogueEvents.size} bubbles (${(summary.verifiedFraction * 100).toInt()}% verified · resuming gaps)"
                        },
                        scoutCaptionState = ScoutCaptionState.Ready(
                            sourceLabel = if (full) ready.sourceLabel else "${ready.sourceLabel} · Partial",
                            cueCount = project.dialogueEvents.size,
                            speakerCount = project.speakers.size,
                        ),
                        statusBanner = "Loaded ${project.dialogueEvents.size} pre-generated bubbles!",
                    )
                }
                if (!full) {
                    runYouTubeScoutTranscriptPipeline(
                        projectId = project.id,
                        videoId = vid,
                        skipTierOneCaptions = true,
                        resumeFromHorizonSec = 0,
                    )
                }
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
        val sessionToken = nextSessionToken()
        analysisJob?.cancel()
        introJob?.cancel()
        lookaheadJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        changeoverPreListenJob?.cancel()
        healBeaconJob?.cancel()
        seekAckTimeoutJob?.cancel()
        statusBannerJob?.cancel()
        clearCapturedAudioQueueSync()
        lastScoutSeekSentAtMs = 0L
        lastScoutSeekTargetMs = -1L
        scoutCompletedExtents.clear()
        scoutAudioSliceTimestamps.clear()
        capturedYouTubeTimedTextKeys.clear()
        autoAnalyzedEventIds.clear()
        settledAttributedEventIds.clear()
        lastSceneSideResetKey = ""
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        lastDetectorFrameId = -1L
        undoStack.clear()
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
        val baseCleanedProject = if (!hasRealSubs && project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
            project.copy(
                subtitleSource = SubtitleSourceKind.BUILT_IN_DEMO,
                dialogueEvents = emptyList(),
            )
        } else {
            project
        }
        baseCleanedProject.dialogueEvents.forEach { evt ->
            if (evt.speakerId != null || evt.attributionSummary == "Unidentified speaker") {
                settledAttributedEventIds.add(evt.id)
            }
        }
        val restoredBeaconsProject = HealBeaconEngine.restoreInterruptedBeacons(baseCleanedProject)
        val inferredChapters = if (restoredBeaconsProject.chapters.isEmpty()) {
            HierarchicalContextEngine.inferEditorialChapters(
                events = restoredBeaconsProject.dialogueEvents,
                durationMs = restoredBeaconsProject.videoSource.durationMs,
            )
        } else {
            restoredBeaconsProject.chapters
        }
        val cleanedProject = restoredBeaconsProject.copy(
            chapters = inferredChapters,
            lastWatchedAtEpochMs = System.currentTimeMillis(),
        )
        contextEngine.resetForProject(cleanedProject)
        autoSelfHealDetector.resetForProject(cleanedProject.id)
        measuredBubbleSizes.clear()
        layoutEngine.stabilityDiagnostics.reset()
        sentinelCoordinator.resetForProject(cleanedProject)
        val restoredGen = sentinelCoordinator.currentSchedulingGeneration()
        releaseStore.resetSession(
            mediaId = cleanedProject.id,
            sessionToken = sessionToken,
            seekGeneration = restoredGen,
            configFingerprint = currentConfigFingerprint(cleanedProject, _uiState.value.quotaSaverMode),
            initialEvents = if (hasRealSubs) cleanedProject.dialogueEvents else emptyList(),
            initialSpeakers = cleanedProject.speakers,
        )
        val resumeMs = cleanedProject.lastPlaybackPositionMs.takeIf { pos ->
            pos > 1_500L && pos < (cleanedProject.videoSource.durationMs - 3_000L)
        } ?: 0L
        if (resumeMs > 0L && cleanedProject.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
            exoPlayer.seekTo(resumeMs)
        }
        if (hasRealSubs &&
            (cleanedProject.subtitleSource == SubtitleSourceKind.UPLOADED_FILE ||
                cleanedProject.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK)
        ) {
            coverageLedger.markAuthoritativeTrack(
                durationMs = cleanedProject.videoSource.durationMs,
                events = cleanedProject.dialogueEvents,
                source = cleanedProject.subtitleSource,
            )
        } else if (hasRealSubs && cleanedProject.subtitleSource == SubtitleSourceKind.AI_DRAFT) {
            // Restore honest coverage from saved AI events so incomplete projects resume missing intervals
            val restoredHorizonMs = if (cleanedProject.coveragePercent in 1..100) {
                (cleanedProject.videoSource.durationMs * cleanedProject.coveragePercent) / 100L
            } else {
                0L
            }
            coverageLedger.restoreFromSavedAiEvents(
                durationMs = cleanedProject.videoSource.durationMs,
                events = cleanedProject.dialogueEvents,
                verifiedUpToMs = restoredHorizonMs,
                source = SubtitleSourceKind.AI_DRAFT,
            )
        } else {
            coverageLedger.reset(
                durationMs = cleanedProject.videoSource.durationMs,
                source = cleanedProject.subtitleSource,
            )
        }
        val initSummary = coverageLedger.summary()
        val initDiag = coverageLedger.diagnostics()
        val needsResumeCoverage = cleanedProject.subtitleSource == SubtitleSourceKind.AI_DRAFT &&
            !initSummary.isFullyVerified
        val requiresGate = cleanedProject.videoSource.kind != VideoSourceKind.DEMO_SCENARIO &&
            (!hasRealSubs || (needsResumeCoverage && initSummary.continuousVerifiedUpToMs <= resumeMs))
        val initReadiness = readinessController.resetForMedia(
            initialPositionMs = resumeMs,
            durationMs = cleanedProject.videoSource.durationMs,
            requiresCoverageGate = requiresGate,
            initialIntent = UserPlaybackIntent.PAUSE,
            playbackSpeed = _uiState.value.playbackSpeed,
            initialSeekGeneration = restoredGen,
        )
        sentinelCoordinator.updateAcknowledgedViewerPosition(
            positionMs = resumeMs,
            durationMs = cleanedProject.videoSource.durationMs,
            isPlaying = false,
            playbackSpeed = _uiState.value.playbackSpeed,
            seekGeneration = restoredGen,
        )
        val initScoutState = when {
            hasRealSubs && !needsResumeCoverage -> ScoutCaptionState.Ready("Saved", cleanedProject.dialogueEvents.size, cleanedProject.speakers.size)
            hasRealSubs && needsResumeCoverage -> ScoutCaptionState.Ready("Saved · Partial", cleanedProject.dialogueEvents.size, cleanedProject.speakers.size)
            else -> ScoutCaptionState.Idle
        }
        _uiState.update {
            val activeMode = it.displayMode.normalizedPrimaryMode()
            it.copy(
                project = cleanedProject,
                activeDestination = AppDestination.WATCH,
                showStartLauncher = false,
                hasPendingPlayIntro = false,
                canUndo = false,
                displayMode = activeMode,
                isPlaying = false,
                currentTimeMs = resumeMs,
                selectedEventId = null,
                selectedHealBeaconId = null,
                streamPlayRequest = null,
                youtubeSeekRequest = if (resumeMs > 0L && cleanedProject.videoSource.kind != VideoSourceKind.LOCAL_MP4) {
                    (++nextStreamCommandSeq) to resumeMs
                } else {
                    null
                },
                isCinemaIntroActive = false,
                introRemainingMs = 0L,
                isOpeningSpeakersReady = hasRealSubs,
                scoutTimeMs = 0L,
                scoutedUpToMs = initSummary.continuousVerifiedUpToMs,
                scoutStatusText = "",
                scoutPlayRequest = null,
                scoutSeekRequest = null,
                analysisStatus = AnalysisStatus.Ready,
                scoutCaptionState = initScoutState,
                scoutCaptureDiagnostics = ScoutCaptureDiagnostics(),
                youtubePlayerErrorKind = null,
                youtubeNativeCcTrackCount = null,
                youtubeNativeCcLang = "",
                youtubeNativeCcState = if (cleanedProject.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                    YouTubeNativeCcState.LOADING
                } else {
                    YouTubeNativeCcState.OFF
                },
                youtubeNativeCcTrackEnabled = false,
                youtubeNativeCcProbeBlocked = false,
                scoutDiagnosticDetail = com.example.data.ai.AppCheckDiagnostics.summaryLine(),
                activeSpeakerToast = null,
                humanReadiness = HumanReadableStatus.resolve(
                    project = cleanedProject,
                    scoutState = initScoutState,
                    coverageSummary = initSummary,
                    readinessSnapshot = initReadiness,
                    releaseSnapshot = releaseStore.snapshot(),
                    sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                ),
                readinessSnapshot = initReadiness,
                visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
                sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                releaseSnapshot = releaseStore.snapshot(),
                seekGeneration = restoredGen,
                liveFaceExclusionRects = emptyList(),
                bufferedWindowRanges = initSummary.bufferedRanges,
                scoutCompletedWindows = 0,
                scoutTotalWindows = 0,
                scoutFailedSliceCount = initSummary.failedIntervalCount,
                coverageSummary = initSummary,
                transcriptDiagnostics = initDiag,
                statusBanner = null,
            )
        }
        if (cleanedProject.healBeacons.any { it.status == HealBeaconStatus.MARKED }) {
            enqueueHealBeaconProcessing()
        }
        recomputeBalloons()

        val shouldResumeYouTubeAcquisition = cleanedProject.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT &&
            !cleanedProject.videoSource.youtubeVideoId.isNullOrBlank() &&
            (!hasRealSubs || needsResumeCoverage)
        if (shouldResumeYouTubeAcquisition) {
            runYouTubeScoutTranscriptPipeline(
                projectId = cleanedProject.id,
                videoId = cleanedProject.videoSource.youtubeVideoId!!,
                skipTierOneCaptions = hasRealSubs && cleanedProject.subtitleSource == SubtitleSourceKind.AI_DRAFT,
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
        val sessionToken = activeSessionToken.get()
        lookaheadJob = viewModelScope.launch {
            while (isActive) {
                val loopWakeEpoch = schedulerWakeEpoch.value
                if (!isCurrentSession(sessionToken, projectId)) break
                val state = _uiState.value
                val proj = state.project
                if (proj.id != projectId || !state.dualInstanceEnabled) break

                val durationMs = proj.videoSource.durationMs.coerceAtLeast(10_000L)
                coverageLedger.updateDuration(durationMs)

                // Never dispatch analysis while scrubber drag preview is active
                if (sentinelCoordinator.isScrubberPreviewActive()) {
                    awaitSchedulerWake(loopWakeEpoch)
                    continue
                }
                // Backpressure: pause Scout 1 preparation briefly when Scout 2 has pending drafts
                if (sentinelCoordinator.shouldThrottleScout1ForReviewBackpressure()) {
                    delay(150L)
                    continue
                }

                val policy = sentinelCoordinator.currentPolicy()
                val anchorPosMs = sentinelCoordinator.currentAcknowledgedViewerPosMs()
                val schedPreCheck = sentinelCoordinator.schedulerSnapshot()
                if (schedPreCheck.pausedDeadlineReached) {
                    val outcomeSnap = sentinelCoordinator.evaluateSchedulerOutcome(
                        ledger = coverageLedger,
                        quotaMode = state.quotaSaverMode,
                        quotaExhausted = quotaCircuitBreakerTripped,
                        hasPendingReviews = releaseStore.pendingDraftCount() > 0,
                    )
                    syncCoverageUiState()
                    _uiState.update {
                        it.copy(
                            analysisStatus = AnalysisStatus.Ready,
                            scoutStatusText = outcomeSnap.idleReason.ifBlank { "Some dialogue is unavailable in current window" },
                        )
                    }
                    awaitSchedulerWake(loopWakeEpoch)
                    continue
                }

                val rawWin: TimeWindowRange? = if (hasPreloadedSubtitles) {
                    LookaheadWindowPlanner.nextPriorityWindow(
                        viewerPosMs = anchorPosMs,
                        durationMs = durationMs,
                        completedWindowStarts = emptySet(),
                        completedExtents = scoutCompletedExtents,
                        horizonMs = policy.horizonMs,
                        enforceHardHorizon = true,
                    )
                } else {
                    coverageLedger.nextPriorityInterval(
                        playheadMs = anchorPosMs,
                        maxRetryAttempts = policy.maxRetriesPerInterval,
                        lookaheadHorizonMs = policy.horizonMs,
                        openingWindowMs = LookaheadWindowPlanner.WINDOW_STEP_MS,
                        rollingWindowMs = LookaheadWindowPlanner.WINDOW_STEP_MS,
                        enforceHardHorizon = true,
                        chunkOverlapMs = LookaheadWindowPlanner.WINDOW_OVERLAP_MS,
                        roundingUpToSecond = false,
                        minSliceMs = policy.minSubmittableSliceMs,
                    )?.let { TimeWindowRange(it.startMs, it.endMs) }
                }

                val nextWin: TimeWindowRange? = rawWin?.let { candidate ->
                    when (
                        val decision = sentinelCoordinator.clipOrDeferToBoundary(
                            startMs = candidate.startMs,
                            endMs = candidate.endMs,
                            overlapMs = LookaheadWindowPlanner.WINDOW_OVERLAP_MS,
                            roundingUpToSec = false,
                            minSliceMs = policy.minSubmittableSliceMs,
                        )
                    ) {
                        is BoundarySubmissionDecision.Allowed -> TimeWindowRange(decision.startMs, decision.endMs)
                        is BoundarySubmissionDecision.DeferredOutsideBoundary -> null
                    }
                }

                if (nextWin == null) {
                    val outcomeSnap = sentinelCoordinator.evaluateSchedulerOutcome(
                        ledger = coverageLedger,
                        quotaMode = state.quotaSaverMode,
                        quotaExhausted = quotaCircuitBreakerTripped,
                        hasPendingReviews = releaseStore.pendingDraftCount() > 0,
                    )
                    val summary = coverageLedger.summary()
                    syncCoverageUiState()
                    val reachedVideoEnd = outcomeSnap.boundaryEndMs >= durationMs
                    if (outcomeSnap.outcomeState == SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE ||
                        (hasPreloadedSubtitles && reachedVideoEnd)
                    ) {
                        _uiState.update {
                            it.copy(
                                analysisStatus = AnalysisStatus.Ready,
                                scoutStatusText = "Scout complete — full transcript & speaker map verified",
                            )
                        }
                        break
                    }
                    val idleMsg = when (outcomeSnap.outcomeState) {
                        SchedulerOutcomeState.WINDOW_READY_IDLE ->
                            "Current window ready through ${SubtitleParser.formatCompactTime(outcomeSnap.boundaryEndMs)}"
                        SchedulerOutcomeState.WAITING_FOR_EVIDENCE_OR_CAPABILITY ->
                            outcomeSnap.idleReason.ifBlank { "Waiting for captions or analysis capability" }
                        SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY ->
                            "Some dialogue is unavailable (${summary.unresolvedGapCount} unresolved gap(s) in current window)"
                        else ->
                            "Current window ready through ${SubtitleParser.formatCompactTime(outcomeSnap.boundaryEndMs)}"
                    }
                    _uiState.update {
                        it.copy(
                            analysisStatus = AnalysisStatus.Ready,
                            scoutStatusText = idleMsg,
                        )
                    }
                    // Suspend cleanly without polling or auto-resuming a paused viewer until playback advances / seek / retry
                    awaitSchedulerWake(loopWakeEpoch)
                    continue
                }

                if (!hasPreloadedSubtitles) {
                    coverageLedger.recordWindowRequested()
                    coverageLedger.markInProgress(nextWin.startMs, nextWin.endMs)
                }

                _uiState.update {
                    it.copy(
                        scoutStatusText = "Scout @ ${SubtitleParser.formatCompactTime(nextWin.startMs)}–${SubtitleParser.formatCompactTime(nextWin.endMs)} · Hearing & matching faces...",
                        analysisStatus = AnalysisStatus.Processing(
                            phaseLabel = "Scout looking ahead (${SubtitleParser.formatCompactTime(nextWin.startMs)}–${SubtitleParser.formatCompactTime(nextWin.endMs)})...",
                            progress = (nextWin.endMs.toFloat() / durationMs.toFloat()).coerceIn(0.08f, 0.98f),
                            analyzedUpToMs = nextWin.endMs,
                            totalMs = durationMs,
                        ),
                    )
                }

                val preDecodeCtx = captureWorkRequestContext(proj, state.quotaSaverMode)
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

                if (!isCurrentSession(sessionToken, projectId)) break
                if (!isRequestContextCurrent(preDecodeCtx)) {
                    if (!hasPreloadedSubtitles) {
                        coverageLedger.recordOutcome(
                            startMs = nextWin.startMs,
                            endMs = nextWin.endMs,
                            status = CoverageStatus.PENDING,
                            source = SubtitleSourceKind.AI_DRAFT,
                            incrementRetry = false,
                        )
                    }
                    continue
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
                val winRequestCtx = captureWorkRequestContext(latestAfterDecode, quotaMode)
                if (!isRequestContextCurrent(winRequestCtx)) continue
                var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = quotaMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                val allowCloudScout = quotaMode != QuotaSaverMode.ZERO_CLOUD && !quotaCircuitBreakerTripped
                var windowCoverageStatus: CoverageStatus = CoverageStatus.PENDING
                var windowFailureReason = ""
                var incrementRetryFlag = false

                val updatedEventsInWindow: List<DialogueEvent> = if (
                    allowCloudScout && (!hasPreloadedSubtitles || existingInWindow.isEmpty()) && wavChunk != null
                ) {
                    var asrRes = scoutHeartbeatService.executeWithBackoffRetry(
                        modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                        maxRetries = 3,
                        onWarmToken = {
                            com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L)
                        },
                        shouldContinue = {
                            canInvokeCloudAi(
                                winRequestCtx,
                                nextWin.startMs,
                                nextWin.endMs,
                                enforceBoundary = true,
                                requireUnloadedTracks = !hasPreloadedSubtitles,
                            )
                        },
                        onRetryScheduled = { attempt, maxRetries, delayMs, _ ->
                            coverageLedger.recordRetry()
                            val delaySec = String.format(java.util.Locale.US, "%.1f", delayMs / 1000.0)
                            _uiState.update { cur ->
                                cur.copy(
                                    scoutStatusText = "${activeTier.displayLabel} dropout · Retry $attempt/$maxRetries in ${delaySec}s",
                                    scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · Media Scout retry $attempt/$maxRetries",
                                )
                            }
                        },
                    ) {
                        translationAiService.transcribeAndTranslateAudioDetailed(
                            wavBytes = wavChunk.wavBytes,
                            chunkStartMs = wavChunk.startMs,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                            glossary = mergedGlossary,
                            audioMimeType = "audio/wav",
                            preferLiteModel = activeTier.preferLite,
                        )
                    }
                    if (!isRequestContextCurrent(winRequestCtx)) {
                        if (!hasPreloadedSubtitles) {
                            coverageLedger.recordOutcome(
                                startMs = nextWin.startMs,
                                endMs = nextWin.endMs,
                                status = CoverageStatus.PENDING,
                                source = SubtitleSourceKind.AI_DRAFT,
                                incrementRetry = false,
                            )
                        }
                        continue
                    }
                    if (asrRes.isFailure &&
                        asrRes.exceptionOrNull() !is ScoutHeartbeatService.ScoutExecutionGatedException &&
                        TranslationAiService.isQuotaOrCreditExhaustedError(asrRes.exceptionOrNull()) &&
                        activeTier == ScoutExecutionTier.BEST_AI
                    ) {
                        activeTier = ScoutExecutionTier.LITE_AI
                        downgradedToLiteForStream = true
                        coverageLedger.recordQuotaFallbackTransition()
                        asrRes = scoutHeartbeatService.executeWithBackoffRetry(
                            modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                            maxRetries = 2,
                            onWarmToken = {
                                com.example.data.ai.awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L)
                            },
                            shouldContinue = {
                                canInvokeCloudAi(
                                    winRequestCtx,
                                    nextWin.startMs,
                                    nextWin.endMs,
                                    enforceBoundary = true,
                                    requireUnloadedTracks = !hasPreloadedSubtitles,
                                )
                            },
                        ) {
                            translationAiService.transcribeAndTranslateAudioDetailed(
                                wavBytes = wavChunk.wavBytes,
                                chunkStartMs = wavChunk.startMs,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage,
                                glossary = mergedGlossary,
                                audioMimeType = "audio/wav",
                                preferLiteModel = true,
                            )
                        }
                        if (!isRequestContextCurrent(winRequestCtx)) {
                            if (!hasPreloadedSubtitles) {
                                coverageLedger.recordOutcome(
                                    startMs = nextWin.startMs,
                                    endMs = nextWin.endMs,
                                    status = CoverageStatus.PENDING,
                                    source = SubtitleSourceKind.AI_DRAFT,
                                    incrementRetry = false,
                                )
                            }
                            continue
                        }
                    }
                    val asrErr = asrRes.exceptionOrNull()
                    if (asrRes.isFailure && asrErr !is ScoutHeartbeatService.ScoutExecutionGatedException &&
                        TranslationAiService.isQuotaOrCreditExhaustedError(asrErr)
                    ) {
                        quotaCircuitBreakerTripped = true
                    }
                    if (asrRes.isSuccess) {
                        val detailed = asrRes.getOrThrow()
                        coverageLedger.recordWindowCompleted()
                        if (detailed.invalidCueCount > 0) {
                            coverageLedger.recordInvalidOrAmbiguousTimestamp(detailed.invalidCueCount)
                        }
                        when (detailed.outcome) {
                            TranscriptionOutcome.DIALOGUE_DETECTED -> windowCoverageStatus = CoverageStatus.VERIFIED_DIALOGUE
                            TranscriptionOutcome.VERIFIED_SILENCE -> windowCoverageStatus = CoverageStatus.VERIFIED_SILENCE
                            TranscriptionOutcome.PARTIAL_SUCCESS -> {
                                windowCoverageStatus = CoverageStatus.PARTIAL_RECOVERY
                                windowFailureReason = detailed.diagnosticNote
                                incrementRetryFlag = true
                            }
                            else -> {
                                windowCoverageStatus = CoverageStatus.FAILED
                                windowFailureReason = detailed.diagnosticNote.ifBlank { detailed.outcome.name }
                                incrementRetryFlag = true
                            }
                        }
                        val aiCues = detailed.cues
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
                    } else if (asrErr is ScoutHeartbeatService.ScoutExecutionGatedException) {
                        windowCoverageStatus = CoverageStatus.PENDING
                        windowFailureReason = ""
                        incrementRetryFlag = false
                        existingInWindow
                    } else {
                        windowCoverageStatus = CoverageStatus.FAILED
                        windowFailureReason = asrErr?.localizedMessage?.take(120) ?: "Audio ASR failure"
                        incrementRetryFlag = true
                        existingInWindow
                    }
                } else if (existingInWindow.isNotEmpty()) {
                    windowCoverageStatus = CoverageStatus.VERIFIED_DIALOGUE
                    val audioFeat = audioAnalyzer.extractVoiceFeatures(
                        uri = videoUri,
                        events = existingInWindow,
                        httpUrl = httpUrl,
                        httpHeaders = httpHeaders,
                    )
                    val enrichedExisting = existingInWindow.map { evt ->
                        evt.copy(audioFeatures = audioFeat[evt.id] ?: evt.audioFeatures)
                    }
                    if (allowCloudScout && !isSameResolvedLanguage(
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                            sampleTexts = enrichedExisting.map { it.sourceText },
                        )
                    ) {
                        var trRes = scoutHeartbeatService.executeWithBackoffRetry(
                            modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                            maxRetries = 2,
                            shouldContinue = {
                                canInvokeCloudAi(winRequestCtx, nextWin.startMs, nextWin.endMs, enforceBoundary = true)
                            },
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
                        if (!isRequestContextCurrent(winRequestCtx)) continue
                        if (trRes.isFailure &&
                            trRes.exceptionOrNull() !is ScoutHeartbeatService.ScoutExecutionGatedException &&
                            TranslationAiService.isQuotaOrCreditExhaustedError(trRes.exceptionOrNull()) &&
                            activeTier == ScoutExecutionTier.BEST_AI
                        ) {
                            activeTier = ScoutExecutionTier.LITE_AI
                            downgradedToLiteForStream = true
                            coverageLedger.recordQuotaFallbackTransition()
                            trRes = scoutHeartbeatService.executeWithBackoffRetry(
                                modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                                maxRetries = 2,
                                shouldContinue = {
                                    canInvokeCloudAi(winRequestCtx, nextWin.startMs, nextWin.endMs, enforceBoundary = true)
                                },
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
                            if (!isRequestContextCurrent(winRequestCtx)) continue
                        }
                        val trErr = trRes.exceptionOrNull()
                        if (trRes.isFailure && trErr !is ScoutHeartbeatService.ScoutExecutionGatedException &&
                            TranslationAiService.isQuotaOrCreditExhaustedError(trErr)
                        ) {
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
                    windowCoverageStatus = if (!allowCloudScout) CoverageStatus.PENDING else CoverageStatus.FAILED
                    windowFailureReason = if (wavChunk == null) "Audio extraction unavailable" else "Cloud Scout paused"
                    incrementRetryFlag = allowCloudScout
                    emptyList()
                }

                if (!isCurrentSession(sessionToken, projectId) || !isRequestContextCurrent(winRequestCtx)) {
                    if (!hasPreloadedSubtitles && windowCoverageStatus != CoverageStatus.PENDING) {
                        coverageLedger.recordOutcome(
                            startMs = nextWin.startMs,
                            endMs = nextWin.endMs,
                            status = CoverageStatus.PENDING,
                            source = SubtitleSourceKind.AI_DRAFT,
                            incrementRetry = false,
                        )
                    }
                    continue
                }
                val latestBeforeCommit = _uiState.value.project
                if (latestBeforeCommit.id != projectId) break
                if (!hasPreloadedSubtitles &&
                    LookaheadWindowPlanner.hasLoadedSubtitlesLocked(latestBeforeCommit.subtitleSource, latestBeforeCommit.dialogueEvents)
                ) {
                    break
                }

                val mergeReport = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
                    existing = latestBeforeCommit.dialogueEvents.filterNot { it.sourceText.startsWith("Web Cinema ready") },
                    incoming = updatedEventsInWindow,
                    windowStartMs = nextWin.startMs,
                    windowEndMs = nextWin.endMs,
                )

                val (updatedSpeakers, attributed) = attributionEngine.assignSpeakers(
                    events = mergeReport.events,
                    shots = mergedShots,
                    faceTracks = mergedFaces,
                    existingSpeakers = latestBeforeCommit.speakers,
                    turnHints = hintMap,
                    rememberedCharacterColors = _uiState.value.characterColorMemory,
                    slotToSpeakerId = buildSlotToSpeakerIdMap(latestBeforeCommit.speakers),
                )

                var finalEvents = attributed
                var effectiveCoverageStatus = windowCoverageStatus
                if (!hasPreloadedSubtitles &&
                    (windowCoverageStatus == CoverageStatus.VERIFIED_DIALOGUE ||
                        windowCoverageStatus == CoverageStatus.VERIFIED_SILENCE ||
                        windowCoverageStatus == CoverageStatus.PARTIAL_RECOVERY)
                ) {
                    val windowSlice = attributed.filter { it.startTimeMs in nextWin.startMs until nextWin.endMs }
                    val (reviewed, releaseSnap) = stageAndReviewIntervalThroughReleaseGate(
                        project = latestBeforeCommit.copy(speakers = updatedSpeakers, dialogueEvents = attributed),
                        intervalId = "media_${nextWin.startMs}_${nextWin.endMs}",
                        startMs = nextWin.startMs,
                        endMs = nextWin.endMs,
                        events = windowSlice,
                        speakers = updatedSpeakers,
                        sessionToken = winRequestCtx.sessionToken,
                        seekGeneration = winRequestCtx.seekGeneration,
                        isConfirmedSilence = windowCoverageStatus == CoverageStatus.VERIFIED_SILENCE && windowSlice.isEmpty(),
                        hasIndependentAudioOrFrameEvidence = true,
                        expectedConfigFingerprint = winRequestCtx.configFingerprint,
                    )
                    if (reviewed == null) {
                        coverageLedger.recordOutcome(
                            startMs = nextWin.startMs,
                            endMs = nextWin.endMs,
                            status = CoverageStatus.PENDING,
                            source = SubtitleSourceKind.AI_DRAFT,
                            incrementRetry = false,
                        )
                        _uiState.update { it.copy(releaseSnapshot = releaseSnap) }
                        continue
                    }
                    finalEvents = mergeReleasedIntervalPreservingLocks(
                        existingEvents = attributed,
                        releasedIntervalEvents = reviewed.repairedEvents,
                        startMs = nextWin.startMs,
                        endMs = nextWin.endMs,
                    )
                    effectiveCoverageStatus = when {
                        windowCoverageStatus == CoverageStatus.PARTIAL_RECOVERY -> CoverageStatus.PARTIAL_RECOVERY
                        reviewed.repairedEvents.isNotEmpty() && reviewed.coverageStatus == CoverageStatus.VERIFIED_SILENCE ->
                            CoverageStatus.RELEASED_FALLBACK
                        else -> reviewed.coverageStatus
                    }
                    _uiState.update { it.copy(releaseSnapshot = releaseSnap) }
                }

                if (!hasPreloadedSubtitles) {
                    coverageLedger.recordDuplicateDecisions(mergeReport.decisions)
                    coverageLedger.recordOutcome(
                        startMs = nextWin.startMs,
                        endMs = nextWin.endMs,
                        status = effectiveCoverageStatus,
                        source = SubtitleSourceKind.AI_DRAFT,
                        dialogueCount = finalEvents.count { it.startTimeMs in nextWin.startMs until nextWin.endMs },
                        failureReason = windowFailureReason,
                        incrementRetry = incrementRetryFlag,
                    )
                } else {
                    recordCompletedWindowExtent(nextWin.startMs, nextWin.endMs)
                }

                if (effectiveCoverageStatus == CoverageStatus.VERIFIED_DIALOGUE ||
                    effectiveCoverageStatus == CoverageStatus.VERIFIED_SILENCE
                ) {
                    recordCompletedWindowExtent(nextWin.startMs, nextWin.endMs)
                }
                updatedEventsInWindow.forEach { autoAnalyzedEventIds.add(it.id) }
                val summary = coverageLedger.summary()
                val nextSubSource = if (finalEvents.isNotEmpty() &&
                    latestBeforeCommit.subtitleSource == SubtitleSourceKind.BUILT_IN_DEMO
                ) {
                    SubtitleSourceKind.AI_DRAFT
                } else {
                    latestBeforeCommit.subtitleSource
                }
                val realCueCount = finalEvents.count { !it.sourceText.startsWith("Web Cinema ready") }
                syncCoverageUiState()
                _uiState.update { cur ->
                    val statusLabel = if (summary.failedIntervalCount > 0) {
                        "Scout ${(summary.verifiedFraction * 100).toInt()}% verified (${summary.failedIntervalCount} gap(s) retrying)"
                    } else {
                        "Scout verified up to ${SubtitleParser.formatCompactTime(summary.continuousVerifiedUpToMs)} (+7s ahead)"
                    }
                    cur.copy(
                        isOpeningSpeakersReady = cur.isOpeningSpeakersReady || realCueCount > 0,
                        scoutStatusText = statusLabel,
                        scoutCaptionState = if (realCueCount > 0) {
                            ScoutCaptionState.Ready(
                                sourceLabel = if (summary.isFullyVerified) "Stream AI · Full" else "Stream AI · Partial",
                                cueCount = realCueCount,
                                speakerCount = updatedSpeakers.size,
                            )
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
                        dialogueEvents = finalEvents,
                    )
                )
                if (!allowCloudScout && !hasPreloadedSubtitles) break
                delay(if (effectiveCoverageStatus == CoverageStatus.FAILED) 450L else 80L)
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
        val sessionToken = nextSessionToken()
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        changeoverPreListenJob?.cancel()
        healBeaconJob?.cancel()
        seekAckTimeoutJob?.cancel()
        statusBannerJob?.cancel()
        clearCapturedAudioQueueSync()
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
        scoutCompletedExtents.clear()
        scoutAudioSliceTimestamps.clear()
        capturedYouTubeTimedTextKeys.clear()
        autoAnalyzedEventIds.clear()
        settledAttributedEventIds.clear()
        lastSceneSideResetKey = ""
        lastDetectorFrameId = -1L
        measuredBubbleSizes.clear()
        layoutEngine.stabilityDiagnostics.reset()
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        coverageLedger.reset(0L, source = SubtitleSourceKind.BUILT_IN_DEMO)
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
            if (!isCurrentSession(sessionToken)) return@launch

            val initialSpeakers = listOf(
                Speaker("spk_a", "Speaker A", accentIndex = 0),
                Speaker("spk_b", "Speaker B", accentIndex = 1),
            )
            val cacheHasUsableState = cachedTranscript != null && (
                LookaheadWindowPlanner.hasRealSubtitles(cachedTranscript.dialogueEvents) ||
                    (cachedTranscript.cacheVersion >= CachedVideoTranscript.CURRENT_CACHE_VERSION &&
                        cachedTranscript.coverageIntervals.any { it.verified })
                )
            val restoredTuple = if (cues.isNotEmpty()) {
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
                    coverageIntervals = emptyList(),
                )
            } else if (cacheHasUsableState && cachedTranscript != null) {
                val dur = maxOf(
                    cachedTranscript.durationMs,
                    cachedTranscript.dialogueEvents.maxOfOrNull { it.endTimeMs } ?: 0L,
                    60_000L,
                )
                val restoredIntervals = TranscriptCoverageLedger.restoreOrMigrate(cachedTranscript, dur)
                CacheRestoreTuple(
                    speakers = cachedTranscript.speakers.ifEmpty { initialSpeakers },
                    events = cachedTranscript.dialogueEvents,
                    durationMs = dur,
                    subSource = cachedTranscript.subtitleSource,
                    sourceLanguage = cachedTranscript.sourceLanguage.ifBlank { sourceLanguage },
                    title = cachedTranscript.title.ifBlank { "YouTube · $youtubeVideoId" },
                    channelName = cachedTranscript.channelName,
                    description = cachedTranscript.description,
                    horizonMs = 0L,
                    coverageIntervals = restoredIntervals,
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
                    coverageIntervals = emptyList(),
                )
            }

            val speakers = restoredTuple.speakers
            val events = restoredTuple.events
            val resolvedDurationMs = restoredTuple.durationMs
            val resolvedSubSource = restoredTuple.subSource
            val resolvedSourceLang = restoredTuple.sourceLanguage
            val resolvedTitle = restoredTuple.title
            val resolvedChannel = restoredTuple.channelName
            val resolvedDesc = restoredTuple.description

            val restoredFromCache = cues.isEmpty() && cacheHasUsableState
            if (cues.isNotEmpty()) {
                coverageLedger.markAuthoritativeTrack(
                    durationMs = resolvedDurationMs,
                    events = events,
                    source = SubtitleSourceKind.UPLOADED_FILE,
                )
            } else if (restoredFromCache) {
                coverageLedger.reset(
                    durationMs = resolvedDurationMs,
                    seedIntervals = restoredTuple.coverageIntervals,
                    source = resolvedSubSource,
                )
            } else {
                coverageLedger.reset(
                    durationMs = resolvedDurationMs,
                    source = SubtitleSourceKind.AI_DRAFT,
                )
            }
            val initialSummary = coverageLedger.summary()
            val initialDiag = coverageLedger.diagnostics()
            val fullyCached = LookaheadWindowPlanner.hasLoadedSubtitlesLocked(resolvedSubSource, events) ||
                initialSummary.isFullyVerified

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
            val verifiedPct = (initialSummary.verifiedFraction * 100).toInt()
            if (restoredFromCache || cues.isNotEmpty()) {
                ytProject.dialogueEvents.forEach { evt ->
                    if (evt.speakerId != null || evt.attributionSummary == "Unidentified speaker") {
                        settledAttributedEventIds.add(evt.id)
                    }
                }
            }
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
                contextEngine.resetForProject(ytProject)
                autoSelfHealDetector.resetForProject(ytProject.id)
                sentinelCoordinator.resetForProject(ytProject)
                val restoredGen = sentinelCoordinator.currentSchedulingGeneration()
                releaseStore.resetSession(
                    mediaId = ytProject.id,
                    sessionToken = sessionToken,
                    seekGeneration = restoredGen,
                    configFingerprint = currentConfigFingerprint(ytProject, it.quotaSaverMode),
                )
                val initReadiness = readinessController.resetForMedia(
                    initialPositionMs = 0L,
                    durationMs = resolvedDurationMs,
                    requiresCoverageGate = !fullyCached,
                    initialIntent = UserPlaybackIntent.PAUSE,
                    playbackSpeed = it.playbackSpeed,
                    initialSeekGeneration = restoredGen,
                )
                val activeMode = it.displayMode.normalizedPrimaryMode()
                val (balloons, activeChapter) = computeContextAwareLayout(
                    project = ytProject,
                    timeMs = 0L,
                    styleSettings = it.styleSettings,
                    liveFaces = currentLiveFaces,
                    displayMode = activeMode,
                )
                it.copy(
                    project = ytProject,
                    activeDestination = AppDestination.WATCH,
                    showStartLauncher = false,
                    hasPendingPlayIntro = true,
                    displayMode = activeMode,
                    isCinemaIntroActive = false,
                    introRemainingMs = 0L,
                    introTotalDurationMs = LookaheadWindowPlanner.recommendedIntroMaxDurationMs(resolvedDurationMs),
                    requiredOpeningLeadWindows = LookaheadWindowPlanner.recommendedOpeningLeadWindows(resolvedDurationMs),
                    isScoutCatchUpBuffering = false,
                    canUndo = false,
                    isPlaying = false,
                    currentTimeMs = 0L,
                    scoutTimeMs = 0L,
                    scoutedUpToMs = initialSummary.continuousVerifiedUpToMs,
                    scoutStatusText = when {
                        cues.isNotEmpty() -> "Loaded ${events.size} authoritative bubbles · Ready"
                        restoredFromCache && fullyCached -> "Cached ${events.size} bubbles · 100% verified ($0)"
                        restoredFromCache -> "Cached ${events.size} bubbles · $verifiedPct% verified · healing gaps..."
                        else -> initialWorkingMsg
                    },
                    selectedEventId = null,
                    isTranslatingAi = false,
                    streamPlayRequest = null,
                    youtubeSeekRequest = null,
                    analysisStatus = AnalysisStatus.Ready,
                    scoutCaptionState = when {
                        cues.isNotEmpty() -> ScoutCaptionState.Ready("Attached", events.size, speakers.size)
                        restoredFromCache && fullyCached -> ScoutCaptionState.Ready("Cached · $0", events.size, speakers.size)
                        restoredFromCache -> ScoutCaptionState.Ready("Cached · Partial", events.size, speakers.size)
                        initialTier == ScoutExecutionTier.FREE_LOCAL -> ScoutCaptionState.FetchingCaptions(initialWorkingMsg)
                        else -> ScoutCaptionState.AiTranscribing(initialWorkingMsg, completedWindows = 0, cuesSoFar = 0)
                    },
                    scoutCaptureDiagnostics = ScoutCaptureDiagnostics(),
                    youtubePlayerErrorKind = null,
                    youtubeNativeCcTrackCount = null,
                    youtubeNativeCcLang = "",
                    youtubeNativeCcState = YouTubeNativeCcState.LOADING,
                    youtubeNativeCcTrackEnabled = false,
                    youtubeNativeCcProbeBlocked = false,
                    scoutDiagnosticDetail = com.example.data.ai.AppCheckDiagnostics.summaryLine(),
                    activeSpeakerToast = null,
                    liveFaceExclusionRects = emptyList(),
                    bufferedWindowRanges = initialSummary.bufferedRanges,
                    scoutCompletedWindows = 0,
                    scoutTotalWindows = LookaheadWindowPlanner.buildSequentialPreGenWindows((resolvedDurationMs / 1000L).toInt().coerceAtLeast(60)).size,
                    scoutFailedSliceCount = initialSummary.failedIntervalCount,
                    coverageSummary = initialSummary,
                    transcriptDiagnostics = initialDiag,
                    seekGeneration = restoredGen,
                    readinessSnapshot = initReadiness,
                    sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    releaseSnapshot = releaseStore.snapshot(),
                    visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
                    placedBalloons = balloons,
                    activeChapter = activeChapter,
                    statusBanner = when {
                        cues.isNotEmpty() -> "Attached ${events.size} subtitles — DialogueLens balloons active"
                        restoredFromCache && fullyCached -> "Restored ${events.size} verified cached bubbles ($0 credits)"
                        restoredFromCache -> "Restored ${events.size} cached bubbles ($verifiedPct% verified) — verifying remaining gaps"
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
                if (!isSameResolvedLanguage(
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        sampleTexts = events.map { it.sourceText },
                    )
                ) {
                    translateCurrentProjectWithAi(silentBackground = true)
                } else if (mode == QuotaSaverMode.PRO_ACCURACY) {
                    analyzeSpeakersWithContextAndAudio(silentBackground = true)
                }
            } else if (restoredFromCache) {
                if (!fullyCached) {
                    runYouTubeScoutTranscriptPipeline(
                        projectId = ytProject.id,
                        videoId = youtubeVideoId,
                        skipTierOneCaptions = true,
                        resumeFromHorizonSec = 0,
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
     * Best-Model-First YouTube Scout transcript pipeline backed by [TranscriptCoverageLedger]:
     * 1. Tracks verified dialogue, verified silence, partial recovery, and unverified/failed intervals
     *    separately so no gap is ever masked by an optimistic watermark or treated as silence.
     * 2. Uses identity-aware spoken event deduplication and decouples dialogue acquisition from
     *    speaker attribution/placement.
     * 3. Cascades cleanly on quota exhaustion (BEST_AI -> LITE_AI -> Free CC trigger) and guards all
     *    writes with the active [sessionToken] and authoritative source lock.
     */
    private fun runYouTubeScoutTranscriptPipeline(
        projectId: String,
        videoId: String,
        skipTierOneCaptions: Boolean = false,
        resumeFromHorizonSec: Int = 0,
    ) {
        youtubeTranscriptJob?.cancel()
        val sessionToken = activeSessionToken.get()
        youtubeTranscriptJob = viewModelScope.launch {
            if (!isCurrentSession(sessionToken, projectId)) return@launch
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
                if (appliedFree || !isCurrentSession(sessionToken, projectId)) return@launch

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
                // Non-blocking background metadata + reference caption collection in Best Model mode:
                // Captures immutable native captions as reference evidence WITHOUT replacing the visible timeline or cancelling Scout 1!
                viewModelScope.launch {
                    val fastMeta = YouTubeCaptionFetcher.fetchFastMetadataOnly(videoId)
                    if (!isCurrentSession(sessionToken, projectId)) return@launch
                    val cur = _uiState.value.project
                    val updatedTitle = fastMeta.title?.takeIf { it.isNotBlank() } ?: cur.title
                    val updatedChannel = fastMeta.channelName?.takeIf { it.isNotBlank() } ?: cur.videoSource.channelName
                    val updatedDesc = fastMeta.description?.takeIf { it.isNotBlank() } ?: cur.videoSource.description
                    val updatedDur = maxOf(cur.videoSource.durationMs, fastMeta.durationMs ?: 0L).coerceAtLeast(60_000L)
                    if (updatedDur != cur.videoSource.durationMs) {
                        coverageLedger.updateDuration(updatedDur)
                    }
                    val updatedChapters = if (fastMeta.chapters.isNotEmpty()) {
                        fastMeta.chapters
                    } else {
                        cur.chapters
                    }
                    if (updatedTitle != cur.title ||
                        updatedChannel != cur.videoSource.channelName ||
                        updatedDesc != cur.videoSource.description ||
                        updatedDur != cur.videoSource.durationMs ||
                        updatedChapters != cur.chapters
                    ) {
                        val patchedProject = cur.copy(
                            title = updatedTitle,
                            videoSource = cur.videoSource.copy(
                                durationMs = updatedDur,
                                channelName = updatedChannel,
                                description = updatedDesc,
                            ),
                            shots = listOf(Shot("shot_1", 0L, updatedDur, cutType = "EMBEDDED_YOUTUBE")),
                            chapters = updatedChapters,
                        )
                        readinessController.updateDuration(updatedDur)
                        sentinelCoordinator.planDefaultChapterPatrol(patchedProject)
                        updateProject(patchedProject)
                        syncCoverageUiState()
                    }

                    // Passively collect native captions as immutable reference evidence for Scout 1 & Scout 2
                    val refFetched = YouTubeCaptionFetcher.fetchMetadataAndCaptions(
                        videoId = videoId,
                        preferredSourceLanguage = _uiState.value.project.sourceLanguage,
                        preferredTargetLanguage = _uiState.value.project.targetLanguage,
                    )
                    if (!isCurrentSession(sessionToken, projectId)) return@launch
                    val refTrack = refFetched.toReferenceTrack(mediaId = projectId)
                    if (refTrack != null) {
                        releaseStore.updateReferenceTrack(refTrack, sessionToken)
                        _uiState.update { s ->
                            s.copy(
                                releaseSnapshot = releaseStore.snapshot(),
                                youtubeCapabilities = s.youtubeCapabilities.copy(
                                    hasProgrammaticCaptions = true,
                                    captionProvenance = "${refTrack.originKind.name} (${refTrack.cues.size} cues · ${refTrack.languageCode.ifBlank { "und" }})",
                                ),
                            )
                        }
                    } else {
                        _uiState.update { s ->
                            s.copy(
                                youtubeCapabilities = s.youtubeCapabilities.copy(
                                    hasProgrammaticCaptions = false,
                                    captionProvenance = "Not exposed by YouTube for this video",
                                ),
                            )
                        }
                    }
                }
            }

            if (!isCurrentSession(sessionToken, projectId)) return@launch
            val preTier2State = _uiState.value
            val preTier2Proj = preTier2State.project
            if (preTier2Proj.id != projectId ||
                LookaheadWindowPlanner.hasLoadedSubtitlesLocked(preTier2Proj.subtitleSource, preTier2Proj.dialogueEvents)
            ) {
                return@launch
            }

            val initialDurMs = preTier2Proj.videoSource.durationMs.coerceAtLeast(60_000L)
            if (coverageLedger.intervalsSnapshot().isEmpty()) {
                coverageLedger.reset(initialDurMs, source = SubtitleSourceKind.AI_DRAFT)
            } else {
                coverageLedger.updateDuration(initialDurMs)
            }
            if (resumeFromHorizonSec > 0 && coverageLedger.summary().totalVerifiedMs == 0L) {
                // Only used if a caller explicitly passes a legacy horizon into a fresh ledger: mark LEGACY_UNVERIFIED (never verified)
                coverageLedger.recordOutcome(
                    startMs = 0L,
                    endMs = minOf(initialDurMs, resumeFromHorizonSec * 1000L),
                    status = CoverageStatus.LEGACY_UNVERIFIED,
                    source = SubtitleSourceKind.AI_DRAFT,
                    failureReason = "Resumed from legacy horizon",
                )
            }

            var totalCuesGenerated = preTier2Proj.dialogueEvents.count {
                !it.sourceText.startsWith("Embedded YouTube Player active") &&
                    !it.sourceText.startsWith("Web Cinema ready")
            }
            var castDiscoveryTriggered = false
            var completedSlicesInRun = 0

            suspend fun applyWindowOutcome(
                startSec: Int,
                endSec: Int,
                outcome: SegmentExecutionOutcome.Completed,
                elapsedProcessingMs: Long = 0L,
                requestCtx: WorkRequestContext? = null,
            ) {
                val resolvedTier = outcome.resolvedTier
                if (outcome.downgradedToLite) {
                    downgradedToLiteForStream = true
                }
                lookaheadMutex.withLock {
                    if (requestCtx != null) {
                        if (!isRequestContextCurrent(requestCtx)) return
                    } else if (!isCurrentSession(sessionToken, projectId)) {
                        return
                    }
                    val liveProj = _uiState.value.project
                    if (liveProj.id != projectId ||
                        LookaheadWindowPlanner.hasLoadedSubtitlesLocked(liveProj.subtitleSource, liveProj.dialogueEvents)
                    ) {
                        return
                    }

                    coverageLedger.recordWindowCompleted(retriesUsed = outcome.retryCount)
                    if (outcome.invalidTimestampCount > 0) {
                        coverageLedger.recordInvalidOrAmbiguousTimestamp(outcome.invalidTimestampCount)
                    }
                    repeat(outcome.quotaFallbackTransitions) {
                        coverageLedger.recordQuotaFallbackTransition()
                    }
                    if (outcome.discardedDuplicates > 0) {
                        coverageLedger.recordEventCounts(accepted = 0, rejected = outcome.discardedDuplicates)
                    }

                    var workingProj = liveProj
                    val beforeSpeakers = workingProj.speakers

                    // Reconcile this window's local A..L speaker slots against the live cast roster BEFORE
                    // merging assignments or storing cue turn hints so parallel workers never mix slots.
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

                    val wStartMs = startSec * 1000L
                    val wEndMs = (endSec * 1000L).coerceAtLeast(wStartMs + 1_000L)
                    var windowReviewOutcome: IntervalReviewOutcome? = null

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

                        val cleanExisting = workingProj.dialogueEvents.filterNot { evt ->
                            evt.sourceText.startsWith("Embedded YouTube Player active") ||
                                evt.sourceText.startsWith("Web Cinema ready")
                        }
                        val pacedWindow = SubtitleParser.applyReadingPacingGuardrails(windowEvents)
                            .filter { it.startTimeMs in wStartMs until wEndMs }
                            .map { evt ->
                                val safeMinEnd = minOf(evt.startTimeMs + 350L, wEndMs)
                                val clampedEnd = evt.endTimeMs.coerceIn(safeMinEnd, wEndMs)
                                evt.copy(endTimeMs = clampedEnd)
                            }
                        val antiEchoedWindow = LookaheadWindowPlanner.filterEchoedOpeningCues(
                            existing = cleanExisting,
                            incoming = pacedWindow,
                            windowStartMs = wStartMs,
                        )
                        val mergeReport = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
                            existing = cleanExisting,
                            incoming = antiEchoedWindow,
                            windowStartMs = wStartMs,
                            windowEndMs = wEndMs,
                        )
                        coverageLedger.recordDuplicateDecisions(mergeReport.decisions)
                        cachedAiTurnHints.putAll(hintMap)
                        antiEchoedWindow.forEach { autoAnalyzedEventIds.add(it.id) }

                        // Decouple dialogue acquisition from attribution/placement: even if attribution throws,
                        // preserve every merged spoken dialogue event with safe neutral fallback placement.
                        val (enrichedSpeakers, attributedAll) = runCatching {
                            val sceneBoundaries = workingProj.chapters
                                .map { it.startMs }
                                .filter { it > 0L }
                                .sorted()
                            val (attributedSpeakers, attributedEvents) = attributionEngine.assignSpeakers(
                                events = mergeReport.events,
                                shots = workingProj.shots,
                                faceTracks = emptyList(),
                                existingSpeakers = workingProj.speakers,
                                turnHints = cachedAiTurnHints,
                                rememberedCharacterColors = _uiState.value.characterColorMemory,
                                settledEventIds = settledAttributedEventIds,
                                sceneBoundaryMs = sceneBoundaries,
                                slotToSpeakerId = buildSlotToSpeakerIdMap(workingProj.speakers),
                            )
                            val latestSceneStartMs = sceneBoundaries.lastOrNull { it <= wStartMs } ?: 0L
                            val updatedSpk = attributedSpeakers.map { spk ->
                                val spkEvents = attributedEvents.filter { it.speakerId == spk.id }
                                val earliest = spkEvents.minOfOrNull { it.startTimeMs }
                                val currentSceneAvgX = spkEvents
                                    .filter { it.dialogueType == DialogueType.SPOKEN && it.startTimeMs >= latestSceneStartMs }
                                    .mapNotNull { it.anchorPosition?.x }
                                    .takeIf { it.isNotEmpty() }
                                    ?.average()
                                    ?.toFloat()
                                spk.copy(
                                    firstAppearedMs = spk.firstAppearedMs ?: earliest,
                                    preferredScreenSideX = currentSceneAvgX,
                                )
                            }
                            val offScreenResolved = applyCutawayOffScreenSideMemory(
                                events = attributedEvents,
                                speakers = updatedSpk,
                                sceneBoundaryMs = sceneBoundaries,
                            )
                            offScreenResolved.forEach { evt ->
                                if (evt.speakerId != null || evt.attributionSummary == "Unidentified speaker") {
                                    settledAttributedEventIds.add(evt.id)
                                }
                            }
                            updatedSpk to offScreenResolved
                        }.getOrElse { err ->
                            Log.w(TAG, "Attribution fallback preserved ${mergeReport.events.size} cues", err)
                            workingProj.speakers to mergeReport.events
                        }

                        // Pass Scout 1 window through Scout 2 Independent Review & Atomic Release Gate
                        val windowSliceEvents = attributedAll.filter { it.startTimeMs in wStartMs until wEndMs }
                        val (reviewed, releaseSnap) = stageAndReviewIntervalThroughReleaseGate(
                            project = workingProj.copy(speakers = enrichedSpeakers, dialogueEvents = attributedAll),
                            intervalId = "win_${wStartMs}_${wEndMs}",
                            startMs = wStartMs,
                            endMs = wEndMs,
                            events = windowSliceEvents,
                            speakers = enrichedSpeakers,
                            sessionToken = requestCtx?.sessionToken ?: sessionToken,
                            seekGeneration = requestCtx?.seekGeneration ?: _uiState.value.seekGeneration,
                            isConfirmedSilence = false,
                            hasUnavailableEvidence = false,
                            hasIndependentAudioOrFrameEvidence = false,
                            elapsedProcessingMs = elapsedProcessingMs,
                            expectedConfigFingerprint = requestCtx?.configFingerprint,
                        )
                        if (reviewed == null) {
                            // Stale session/seek/config write was rejected by the Release Gate
                            _uiState.update { it.copy(releaseSnapshot = releaseSnap) }
                            return
                        }
                        windowReviewOutcome = reviewed
                        val finalEvents = mergeReleasedIntervalPreservingLocks(
                            existingEvents = attributedAll,
                            releasedIntervalEvents = reviewed.repairedEvents,
                            startMs = wStartMs,
                            endMs = wEndMs,
                        )

                        val maxCueEndMs = finalEvents.maxOfOrNull { it.endTimeMs } ?: 0L
                        val expandedDurationMs = maxOf(workingProj.videoSource.durationMs, maxCueEndMs, endSec * 1000L)
                        coverageLedger.updateDuration(expandedDurationMs)
                        workingProj = workingProj.copy(
                            subtitleSource = SubtitleSourceKind.AI_DRAFT,
                            videoSource = workingProj.videoSource.copy(durationMs = expandedDurationMs),
                            shots = listOf(Shot("shot_1", 0L, expandedDurationMs, cutType = "EMBEDDED_YOUTUBE")),
                            speakers = enrichedSpeakers,
                            dialogueEvents = finalEvents,
                        )
                        _uiState.update { it.copy(releaseSnapshot = releaseSnap) }
                    } else if (outcome.verifiedSilentRanges.isNotEmpty() && outcome.failedRanges.isEmpty()) {
                        // Pass silence windows through Scout 2 so reference captions can recover missed dialogue!
                        val (reviewedSilence, silenceReleaseSnap) = stageAndReviewIntervalThroughReleaseGate(
                            project = workingProj,
                            intervalId = "win_${wStartMs}_${wEndMs}",
                            startMs = wStartMs,
                            endMs = wEndMs,
                            events = emptyList(),
                            speakers = workingProj.speakers,
                            sessionToken = requestCtx?.sessionToken ?: sessionToken,
                            seekGeneration = requestCtx?.seekGeneration ?: _uiState.value.seekGeneration,
                            isConfirmedSilence = true,
                            hasUnavailableEvidence = false,
                            hasIndependentAudioOrFrameEvidence = false,
                            elapsedProcessingMs = elapsedProcessingMs,
                            expectedConfigFingerprint = requestCtx?.configFingerprint,
                        )
                        if (reviewedSilence == null) {
                            _uiState.update { it.copy(releaseSnapshot = silenceReleaseSnap) }
                            return
                        }
                        windowReviewOutcome = reviewedSilence
                        _uiState.update { it.copy(releaseSnapshot = silenceReleaseSnap) }
                        // P1 handoff repair: when Scout 2 recovers spoken dialogue inside a Scout 1 silence window,
                        // publish those recovered cues into workingProj so playback & persistence receive them!
                        if (reviewedSilence.repairedEvents.isNotEmpty()) {
                            val cleanExisting = workingProj.dialogueEvents.filterNot { evt ->
                                evt.sourceText.startsWith("Embedded YouTube Player active") ||
                                    evt.sourceText.startsWith("Web Cinema ready")
                            }
                            val recoveredMerged = mergeReleasedIntervalPreservingLocks(
                                existingEvents = cleanExisting,
                                releasedIntervalEvents = reviewedSilence.repairedEvents,
                                startMs = wStartMs,
                                endMs = wEndMs,
                            )
                            val maxCueEndMs = recoveredMerged.maxOfOrNull { it.endTimeMs } ?: 0L
                            val expandedDurationMs = maxOf(workingProj.videoSource.durationMs, maxCueEndMs, wEndMs)
                            coverageLedger.updateDuration(expandedDurationMs)
                            workingProj = workingProj.copy(
                                subtitleSource = SubtitleSourceKind.AI_DRAFT,
                                videoSource = workingProj.videoSource.copy(durationMs = expandedDurationMs),
                                shots = listOf(Shot("shot_1", 0L, expandedDurationMs, cutType = "EMBEDDED_YOUTUBE")),
                                dialogueEvents = recoveredMerged,
                            )
                        }
                    }

                    // Commit strict epistemic interval outcomes to the single-source-of-truth ledger
                    outcome.verifiedDialogueRanges.forEach { (s, e) ->
                        val sliceStartMs = s * 1000L
                        val sliceEndMs = e * 1000L
                        val sliceCueCount = workingProj.dialogueEvents.count {
                            it.startTimeMs in sliceStartMs until sliceEndMs
                        }
                        if (sliceCueCount > 0) {
                            val effectiveStatus = windowReviewOutcome?.coverageStatus?.takeIf {
                                it == CoverageStatus.VERIFIED_DIALOGUE || it == CoverageStatus.RELEASED_FALLBACK
                            } ?: CoverageStatus.VERIFIED_DIALOGUE
                            coverageLedger.recordOutcome(
                                startMs = sliceStartMs,
                                endMs = sliceEndMs,
                                status = effectiveStatus,
                                source = SubtitleSourceKind.AI_DRAFT,
                                dialogueCount = sliceCueCount,
                            )
                            recordCompletedWindowExtent(sliceStartMs, sliceEndMs)
                        } else {
                            // Epistemic integrity: if echo filtering or timestamp validation removed all cues
                            // for a claimed dialogue window, NEVER mark it verified — record FAILED for recovery.
                            coverageLedger.recordOutcome(
                                startMs = sliceStartMs,
                                endMs = sliceEndMs,
                                status = CoverageStatus.FAILED,
                                source = SubtitleSourceKind.AI_DRAFT,
                                dialogueCount = 0,
                                failureReason = "ECHO_OR_EMPTY_SLICE_REJECTED",
                                incrementRetry = true,
                            )
                        }
                    }
                    outcome.verifiedSilentRanges.forEach { (s, e) ->
                        val sMs = s * 1000L
                        val eMs = e * 1000L
                        val recoveredCueCount = workingProj.dialogueEvents.count {
                            it.startTimeMs in sMs until eMs
                        }
                        if (recoveredCueCount > 0) {
                            // NEVER mark an interval VERIFIED_SILENCE after recovering spoken dialogue inside it
                            val recoveredStatus = windowReviewOutcome?.coverageStatus?.takeIf {
                                it == CoverageStatus.VERIFIED_DIALOGUE || it == CoverageStatus.RELEASED_FALLBACK
                            } ?: CoverageStatus.RELEASED_FALLBACK
                            coverageLedger.recordOutcome(
                                startMs = sMs,
                                endMs = eMs,
                                status = recoveredStatus,
                                source = SubtitleSourceKind.AI_DRAFT,
                                dialogueCount = recoveredCueCount,
                            )
                            if (recoveredStatus == CoverageStatus.VERIFIED_DIALOGUE) {
                                recordCompletedWindowExtent(sMs, eMs)
                            }
                        } else {
                            val silenceStatus = if (windowReviewOutcome?.coverageStatus == CoverageStatus.SUSPECTED_MISSING_DIALOGUE) {
                                CoverageStatus.SUSPECTED_MISSING_DIALOGUE
                            } else {
                                CoverageStatus.VERIFIED_SILENCE
                            }
                            coverageLedger.recordOutcome(
                                startMs = sMs,
                                endMs = eMs,
                                status = silenceStatus,
                                source = SubtitleSourceKind.AI_DRAFT,
                            )
                            if (silenceStatus == CoverageStatus.VERIFIED_SILENCE) {
                                recordCompletedWindowExtent(sMs, eMs)
                            }
                        }
                    }
                    outcome.partialRanges.forEach { (s, e) ->
                        val sliceCueCount = workingProj.dialogueEvents.count {
                            it.startTimeMs in (s * 1000L) until (e * 1000L)
                        }
                        coverageLedger.recordOutcome(
                            startMs = s * 1000L,
                            endMs = e * 1000L,
                            status = CoverageStatus.PARTIAL_RECOVERY,
                            source = SubtitleSourceKind.AI_DRAFT,
                            dialogueCount = sliceCueCount,
                            failureReason = "Partial slice salvaged; remaining span unverified",
                            incrementRetry = true,
                        )
                    }
                    outcome.abortedRanges.forEach { (s, e) ->
                        coverageLedger.recordOutcome(
                            startMs = s * 1000L,
                            endMs = e * 1000L,
                            status = CoverageStatus.PENDING,
                            source = SubtitleSourceKind.AI_DRAFT,
                            incrementRetry = false,
                        )
                    }
                    outcome.failedDetails.forEach { detail ->
                        if (detail.outcome != TranscriptionOutcome.PARTIAL_SUCCESS) {
                            coverageLedger.recordOutcome(
                                startMs = detail.startSec * 1000L,
                                endMs = detail.endSec * 1000L,
                                status = CoverageStatus.FAILED,
                                source = SubtitleSourceKind.AI_DRAFT,
                                failureReason = detail.reason.ifBlank { detail.outcome.name },
                                incrementRetry = true,
                            )
                        }
                    }

                    // Defensive invariant: ensure no portion of the requested window remains stranded IN_PROGRESS
                    val requestedStartMs = startSec * 1000L
                    val requestedEndMs = (endSec * 1000L).coerceAtLeast(requestedStartMs + 1_000L)
                    coverageLedger.intervalsSnapshot()
                        .filter { interval ->
                            interval.status == CoverageStatus.IN_PROGRESS &&
                                interval.startMs < requestedEndMs &&
                                interval.endMs > requestedStartMs
                        }
                        .forEach { stranded ->
                            val overlapStart = maxOf(stranded.startMs, requestedStartMs)
                            val overlapEnd = minOf(stranded.endMs, requestedEndMs)
                            if (overlapEnd > overlapStart) {
                                coverageLedger.recordOutcome(
                                    startMs = overlapStart,
                                    endMs = overlapEnd,
                                    status = CoverageStatus.PENDING,
                                    source = SubtitleSourceKind.AI_DRAFT,
                                    failureReason = if (outcome.abortedByPolicy) "" else "UNCOMMITTED_WINDOW_SLICE_RECOVERABLE",
                                    incrementRetry = !outcome.abortedByPolicy,
                                )
                            }
                        }

                    completedSlicesInRun++
                    totalCuesGenerated = workingProj.dialogueEvents.count {
                        !it.sourceText.startsWith("Embedded YouTube Player active") &&
                            !it.sourceText.startsWith("Web Cinema ready")
                    }
                    val summary = coverageLedger.summary()
                    val latestDurSec = (workingProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
                    val totalWinCount = LookaheadWindowPlanner.buildSequentialPreGenWindows(latestDurSec).size
                    val verifiedWinEstimate = if (summary.isFullyVerified) {
                        totalWinCount
                    } else {
                        (summary.verifiedFraction * totalWinCount).toInt().coerceIn(0, (totalWinCount - 1).coerceAtLeast(0))
                    }

                    updateProject(
                        workingProj,
                        banner = null,
                    )
                    persistYouTubeVideoCache(workingProj, summary.continuousVerifiedUpToMs, sessionToken)
                    emitSpeakerGlossaryToasts(beforeSpeakers, workingProj.speakers)

                    val diagNote = buildString {
                        append(com.example.data.ai.AppCheckDiagnostics.summaryLine())
                        append(" · ${(summary.verifiedFraction * 100).toInt()}% verified")
                        if (outcome.usedBisection) append(" · Split-recovered")
                        if (summary.failedIntervalCount > 0) append(" · ${summary.failedIntervalCount} gap(s)")
                    }

                    syncCoverageUiState(
                        totalWindowHint = totalWinCount,
                        completedWindowHint = verifiedWinEstimate,
                    )
                    _uiState.update {
                        it.copy(
                            isOpeningSpeakersReady = it.isOpeningSpeakersReady || totalCuesGenerated > 0 || summary.continuousVerifiedUpToMs >= 10_000L,
                            scoutStatusText = "Scout ${(summary.verifiedFraction * 100).toInt()}% ($verifiedWinEstimate/$totalWinCount) · ${workingProj.dialogueEvents.size} bubbles · ${workingProj.speakers.size} speakers",
                            scoutDiagnosticDetail = diagNote,
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
            if (!isCurrentSession(sessionToken, projectId)) return@launch

            val quotaTriggered = AtomicBoolean(false)
            var lastQuotaError: Throwable? = null

            // Step A: Opening window runs immediately (clipped to rolling 3-minute boundary) so Smart Pre-Roll settles fast
            val openingState = _uiState.value
            val openingProj = openingState.project
            val openingDurSec = (openingProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
            val openingWindows = LookaheadWindowPlanner.buildSequentialPreGenWindows(openingDurSec)
            syncCoverageUiState(totalWindowHint = openingWindows.size)

            val openingPolicy = sentinelCoordinator.currentPolicy()
            val openingTarget = if (
                openingState.quotaSaverMode != QuotaSaverMode.ZERO_CLOUD &&
                !sentinelCoordinator.isScrubberPreviewActive() &&
                sentinelCoordinator.currentAcknowledgedViewerPosMs() == 0L
            ) {
                lookaheadMutex.withLock {
                    val firstInterval = coverageLedger.nextPriorityInterval(
                        playheadMs = 0L,
                        maxRetryAttempts = openingPolicy.maxRetriesPerInterval,
                        lookaheadHorizonMs = openingPolicy.horizonMs,
                        enforceHardHorizon = true,
                        roundingUpToSecond = true,
                        minSliceMs = openingPolicy.minSubmittableSliceMs,
                    )
                    if (firstInterval != null && firstInterval.startMs == 0L) {
                        when (
                            val gate = sentinelCoordinator.clipOrDeferToBoundary(
                                startMs = firstInterval.startMs,
                                endMs = firstInterval.endMs,
                                overlapMs = 0L,
                                roundingUpToSec = true,
                                minSliceMs = openingPolicy.minSubmittableSliceMs,
                            )
                        ) {
                            is BoundarySubmissionDecision.Allowed -> {
                                coverageLedger.recordWindowRequested()
                                coverageLedger.markInProgress(gate.startMs, gate.endMs)
                                firstInterval.copy(startMs = gate.startMs, endMs = gate.endMs)
                            }
                            is BoundarySubmissionDecision.DeferredOutsideBoundary -> null
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            }

            if (openingTarget != null && openingProj.id == projectId && isCurrentSession(sessionToken, projectId)) {
                val openStart = (openingTarget.startMs / 1000L).toInt()
                val boundaryEndSec = (sentinelCoordinator.currentBoundaryEndMs() / 1000L).toInt()
                val openEnd = ((openingTarget.endMs + 999L) / 1000L).toInt()
                    .coerceAtMost(boundaryEndSec)
                if (openEnd <= openStart) {
                    coverageLedger.recordOutcome(
                        startMs = openingTarget.startMs,
                        endMs = openingTarget.endMs,
                        status = CoverageStatus.PENDING,
                        source = SubtitleSourceKind.AI_DRAFT,
                        incrementRetry = false,
                    )
                } else {
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
                val openRequestCtx = captureWorkRequestContext(openingProj, openingState.quotaSaverMode)
                val openExecStartMs = System.currentTimeMillis()
                try {
                    val openOutcome = segmentExecutor.executeSegmentDetailed(
                        startSec = openStart,
                        endSec = openEnd,
                        initialTier = initialTier,
                        onStatusUpdate = { statusNote ->
                            if (!isCurrentSession(sessionToken, projectId)) return@executeSegmentDetailed
                            _uiState.update {
                                it.copy(
                                    scoutStatusText = statusNote,
                                    scoutCaptionState = ScoutCaptionState.AiTranscribing(
                                        message = statusNote,
                                        completedWindows = it.scoutCompletedWindows,
                                        cuesSoFar = totalCuesGenerated,
                                    ),
                                )
                            }
                        },
                        onDowngradedToLite = { downgradedToLiteForStream = true },
                        canInvokeProvider = { sliceStart, sliceEnd, _ ->
                            canInvokeCloudAi(
                                ctx = openRequestCtx,
                                sliceStartMs = sliceStart * 1000L,
                                sliceEndMs = sliceEnd * 1000L,
                                enforceBoundary = true,
                                requireUnloadedTracks = true,
                            )
                        },
                    ) { sliceStart, sliceEnd, preferLite, clarificationRetry ->
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
                            timestampClarificationRetry = clarificationRetry,
                        )
                    }

                    when (openOutcome) {
                        is SegmentExecutionOutcome.Completed -> applyWindowOutcome(
                            startSec = openStart,
                            endSec = openEnd,
                            outcome = openOutcome,
                            elapsedProcessingMs = (System.currentTimeMillis() - openExecStartMs).coerceAtLeast(1L),
                            requestCtx = openRequestCtx,
                        )
                        is SegmentExecutionOutcome.QuotaExhausted -> {
                            coverageLedger.recordOutcome(
                                startMs = openingTarget.startMs,
                                endMs = openingTarget.endMs,
                                status = CoverageStatus.FAILED,
                                source = SubtitleSourceKind.AI_DRAFT,
                                failureReason = TranscriptionOutcome.QUOTA_EXHAUSTED.name,
                                incrementRetry = false,
                            )
                            lastQuotaError = openOutcome.error
                            quotaCircuitBreakerTripped = true
                            quotaTriggered.set(true)
                        }
                    }
                } catch (ce: CancellationException) {
                    if (isCurrentSession(sessionToken, projectId)) {
                        coverageLedger.recordOutcome(
                            startMs = openingTarget.startMs,
                            endMs = openingTarget.endMs,
                            status = CoverageStatus.PENDING,
                            source = SubtitleSourceKind.AI_DRAFT,
                            incrementRetry = false,
                        )
                    }
                    throw ce
                }
                }
            }

            // Step B: Rolling 3-Minute Boundary Worker Loop with Clean Idle / Event-Driven Wake
            while (isActive && isCurrentSession(sessionToken, projectId)) {
                val outerWakeEpoch = schedulerWakeEpoch.value
                val loopState = _uiState.value
                val loopProj = loopState.project
                if (loopProj.id != projectId ||
                    LookaheadWindowPlanner.hasLoadedSubtitlesLocked(loopProj.subtitleSource, loopProj.dialogueEvents)
                ) {
                    break
                }

                // Scrubber preview active -> freeze boundary and suspend cleanly with zero dispatch until committed seek
                if (sentinelCoordinator.isScrubberPreviewActive()) {
                    awaitSchedulerWake(outerWakeEpoch)
                    continue
                }

                // CC-only / ZERO_CLOUD mode -> zero AI-provider requests; suspend cleanly until mode changes
                if (loopState.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD && !quotaTriggered.get() && !quotaCircuitBreakerTripped) {
                    sentinelCoordinator.evaluateSchedulerOutcome(
                        ledger = coverageLedger,
                        quotaMode = QuotaSaverMode.ZERO_CLOUD,
                        quotaExhausted = false,
                        hasPendingReviews = releaseStore.pendingDraftCount() > 0,
                    )
                    syncCoverageUiState()
                    _uiState.update {
                        it.copy(
                            isTranslatingAi = false,
                            scoutStatusText = "Free CC Only active (0 AI requests)",
                        )
                    }
                    awaitSchedulerWake(outerWakeEpoch)
                    continue
                }

                if (!quotaTriggered.get() && !quotaCircuitBreakerTripped && isCurrentSession(sessionToken, projectId)) {
                    val scaledWorkers = LookaheadWindowPlanner.recommendedWorkerCount(loopProj.videoSource.durationMs)
                    coroutineScope {
                        List(scaledWorkers) { workerIdx ->
                            launch {
                                if (workerIdx > 0) {
                                    delay(workerIdx * 1_100L)
                                }
                                while (isActive && !quotaTriggered.get() && !quotaCircuitBreakerTripped) {
                                    if (sentinelCoordinator.isScrubberPreviewActive()) break
                                    if (sentinelCoordinator.shouldThrottleScout1ForReviewBackpressure()) {
                                        delay(150L)
                                        continue
                                    }
                                    if (sentinelCoordinator.schedulerSnapshot().pausedDeadlineReached) break

                                    var chosenInterval: CoverageInterval? = null
                                    var submittedEndSec = 0
                                    val workerProj: DialogueLensProject
                                    val workerState: DialogueLensUiState
                                    lookaheadMutex.withLock {
                                        if (!isCurrentSession(sessionToken, projectId)) return@launch
                                        workerState = _uiState.value
                                        workerProj = workerState.project
                                        if (workerProj.id != projectId ||
                                            LookaheadWindowPlanner.hasLoadedSubtitlesLocked(workerProj.subtitleSource, workerProj.dialogueEvents) ||
                                            workerState.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD ||
                                            sentinelCoordinator.isScrubberPreviewActive()
                                        ) {
                                            return@launch
                                        }
                                        val durMs = workerProj.videoSource.durationMs.coerceAtLeast(60_000L)
                                        coverageLedger.updateDuration(durMs)
                                        val policy = sentinelCoordinator.currentPolicy()
                                        val anchorPosMs = sentinelCoordinator.currentAcknowledgedViewerPosMs()
                                        val nextIv = coverageLedger.nextPriorityInterval(
                                            playheadMs = anchorPosMs,
                                            maxRetryAttempts = policy.maxRetriesPerInterval,
                                            lookaheadHorizonMs = policy.horizonMs,
                                            enforceHardHorizon = true,
                                            roundingUpToSecond = true,
                                            minSliceMs = policy.minSubmittableSliceMs,
                                        )
                                        if (nextIv != null) {
                                            when (
                                                val gate = sentinelCoordinator.clipOrDeferToBoundary(
                                                    startMs = nextIv.startMs,
                                                    endMs = nextIv.endMs,
                                                    overlapMs = 0L,
                                                    roundingUpToSec = true,
                                                    minSliceMs = policy.minSubmittableSliceMs,
                                                )
                                            ) {
                                                is BoundarySubmissionDecision.Allowed -> {
                                                    val clipped = nextIv.copy(startMs = gate.startMs, endMs = gate.endMs)
                                                    val startSec = (clipped.startMs / 1000L).toInt()
                                                    val capEndSec = (gate.effectiveSubmittedEndMs / 1000L).toInt()
                                                    val safeEndSec = ((clipped.endMs + 999L) / 1000L).toInt()
                                                        .coerceAtMost(capEndSec)
                                                        .coerceAtLeast(startSec + 1)
                                                    if (safeEndSec * 1000L <= sentinelCoordinator.currentBoundaryEndMs()) {
                                                        coverageLedger.recordWindowRequested()
                                                        coverageLedger.markInProgress(clipped.startMs, clipped.endMs)
                                                        chosenInterval = clipped
                                                        submittedEndSec = safeEndSec
                                                    }
                                                }
                                                is BoundarySubmissionDecision.DeferredOutsideBoundary -> {
                                                    chosenInterval = null
                                                }
                                            }
                                        }
                                    }

                                    val chosen = chosenInterval ?: break
                                    val isRecoveryPick = chosen.status == CoverageStatus.FAILED ||
                                        chosen.status == CoverageStatus.PARTIAL_RECOVERY ||
                                        chosen.status == CoverageStatus.LEGACY_UNVERIFIED ||
                                        chosen.status == CoverageStatus.SUSPECTED_MISSING_DIALOGUE
                                    val winStartSec = (chosen.startMs / 1000L).toInt()
                                    val winEndSec = submittedEndSec.coerceAtLeast(winStartSec + 1)
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
                                            "Verifying gap ($winLabel) · $totalCuesGenerated bubbles..."
                                        } else {
                                            "${tier.displayLabel} lookahead ($winLabel) · $totalCuesGenerated bubbles..."
                                        }
                                        _uiState.update {
                                            it.copy(
                                                isTranslatingAi = true,
                                                scoutStatusText = progressMsg,
                                                scoutCaptionState = ScoutCaptionState.AiTranscribing(
                                                    message = progressMsg,
                                                    completedWindows = it.scoutCompletedWindows,
                                                    cuesSoFar = totalCuesGenerated,
                                                ),
                                            )
                                        }
                                    }

                                    val winRequestCtx = captureWorkRequestContext(workerProj, workerState.quotaSaverMode)
                                    try {
                                        val winExecStartMs = System.currentTimeMillis()
                                        val outcome = segmentExecutor.executeSegmentDetailed(
                                            startSec = winStartSec,
                                            endSec = winEndSec,
                                            initialTier = tier,
                                            onStatusUpdate = { statusNote ->
                                                if (workerIdx == 0 && isCurrentSession(sessionToken, projectId)) {
                                                    _uiState.update {
                                                        it.copy(
                                                            scoutStatusText = statusNote,
                                                            scoutCaptionState = ScoutCaptionState.AiTranscribing(
                                                                message = statusNote,
                                                                completedWindows = it.scoutCompletedWindows,
                                                                cuesSoFar = totalCuesGenerated,
                                                            ),
                                                        )
                                                    }
                                                }
                                            },
                                            onDowngradedToLite = { downgradedToLiteForStream = true },
                                            canInvokeProvider = { sliceStart, sliceEnd, _ ->
                                                canInvokeCloudAi(
                                                    ctx = winRequestCtx,
                                                    sliceStartMs = sliceStart * 1000L,
                                                    sliceEndMs = sliceEnd * 1000L,
                                                    enforceBoundary = true,
                                                    requireUnloadedTracks = true,
                                                )
                                            },
                                        ) { sliceStart, sliceEnd, preferLite, clarificationRetry ->
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
                                                timestampClarificationRetry = clarificationRetry,
                                            )
                                        }

                                        when (outcome) {
                                            is SegmentExecutionOutcome.Completed -> {
                                                applyWindowOutcome(
                                                    winStartSec,
                                                    winEndSec,
                                                    outcome,
                                                    elapsedProcessingMs = (System.currentTimeMillis() - winExecStartMs).coerceAtLeast(1L),
                                                    requestCtx = winRequestCtx,
                                                )
                                            }
                                            is SegmentExecutionOutcome.QuotaExhausted -> {
                                                lookaheadMutex.withLock {
                                                    coverageLedger.recordOutcome(
                                                        startMs = chosen.startMs,
                                                        endMs = chosen.endMs,
                                                        status = CoverageStatus.FAILED,
                                                        source = SubtitleSourceKind.AI_DRAFT,
                                                        failureReason = TranscriptionOutcome.QUOTA_EXHAUSTED.name,
                                                        incrementRetry = false,
                                                    )
                                                }
                                                if (quotaTriggered.compareAndSet(false, true)) {
                                                    lastQuotaError = outcome.error
                                                    quotaCircuitBreakerTripped = true
                                                }
                                                break
                                            }
                                        }
                                    } catch (ce: CancellationException) {
                                        if (isCurrentSession(sessionToken, projectId)) {
                                            coverageLedger.recordOutcome(
                                                startMs = chosen.startMs,
                                                endMs = chosen.endMs,
                                                status = CoverageStatus.PENDING,
                                                source = SubtitleSourceKind.AI_DRAFT,
                                                incrementRetry = false,
                                            )
                                        }
                                        throw ce
                                    }
                                    delay(120L)
                                }
                            }
                        }.joinAll()
                    }
                }

                if (!isCurrentSession(sessionToken, projectId)) return@launch
                val postLoopProj = _uiState.value.project
                if (postLoopProj.id != projectId) return@launch

                if (quotaTriggered.get() || quotaCircuitBreakerTripped) {
                    val freeSuccess = executeFreeYouTubeCaptionFallback(
                        projectId = projectId,
                        videoId = videoId,
                        triggeredByCreditExhaustion = true,
                    )
                    if (freeSuccess || !isCurrentSession(sessionToken, projectId)) return@launch

                    val latestAfterFallback = _uiState.value.project
                    val hasPartial = LookaheadWindowPlanner.hasRealSubtitles(latestAfterFallback.dialogueEvents)
                    val sum = coverageLedger.summary()
                    persistYouTubeVideoCache(latestAfterFallback, sum.continuousVerifiedUpToMs, sessionToken)
                    syncCoverageUiState()
                    val rawErr = lastQuotaError?.localizedMessage?.take(140).orEmpty()
                    val msg = if (hasPartial) {
                        "Credits depleted (${latestAfterFallback.dialogueEvents.size} bubbles · ${(sum.verifiedFraction * 100).toInt()}% verified) · Attach SRT or enable CC"
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

                val finalTier = LookaheadWindowPlanner.initialExecutionTier(
                    quotaSaverMode = _uiState.value.quotaSaverMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    downgradedToLite = downgradedToLiteForStream,
                )
                val schedOutcome = sentinelCoordinator.evaluateSchedulerOutcome(
                    ledger = coverageLedger,
                    quotaMode = _uiState.value.quotaSaverMode,
                    quotaExhausted = quotaCircuitBreakerTripped,
                    hasPendingReviews = releaseStore.pendingDraftCount() > 0,
                )
                val finalSummary = coverageLedger.summary()
                val finalDurSec = (postLoopProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60)
                val finalWinsCount = LookaheadWindowPlanner.buildSequentialPreGenWindows(finalDurSec).size
                val verifiedPct = (finalSummary.verifiedFraction * 100).toInt()

                persistYouTubeVideoCache(postLoopProj, finalSummary.continuousVerifiedUpToMs, sessionToken)
                syncCoverageUiState(totalWindowHint = finalWinsCount)

                when (schedOutcome.outcomeState) {
                    SchedulerOutcomeState.WHOLE_VIDEO_COMPLETE -> {
                        if (postLoopProj.dialogueEvents.isNotEmpty()) {
                            val readySummary = "Full Scout verified · ${postLoopProj.dialogueEvents.size} bubbles · ${postLoopProj.speakers.size} speakers"
                            _uiState.update {
                                it.copy(
                                    isTranslatingAi = false,
                                    isOpeningSpeakersReady = true,
                                    scoutStatusText = readySummary,
                                    scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · 100% verified · ${postLoopProj.dialogueEvents.size} bubbles",
                                    scoutCaptionState = ScoutCaptionState.Ready(
                                        sourceLabel = "${finalTier.displayLabel} · Full",
                                        cueCount = postLoopProj.dialogueEvents.size,
                                        speakerCount = postLoopProj.speakers.size,
                                    ),
                                )
                            }
                        } else {
                            val silentMsg = "Verified silence across full video · 0 spoken lines"
                            _uiState.update {
                                it.copy(
                                    isTranslatingAi = false,
                                    isOpeningSpeakersReady = true,
                                    scoutStatusText = silentMsg,
                                    scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · 100% verified silence",
                                    scoutCaptionState = ScoutCaptionState.Ready(
                                        sourceLabel = "${finalTier.displayLabel} · Silent",
                                        cueCount = 0,
                                        speakerCount = 0,
                                    ),
                                )
                            }
                        }
                        break
                    }
                    SchedulerOutcomeState.WINDOW_READY_IDLE -> {
                        val windowReadyMsg = "Current window ready through ${SubtitleParser.formatCompactTime(schedOutcome.boundaryEndMs)} · ${postLoopProj.dialogueEvents.size} bubbles"
                        _uiState.update {
                            it.copy(
                                isTranslatingAi = false,
                                isOpeningSpeakersReady = true,
                                scoutStatusText = windowReadyMsg,
                                scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · Window idle through ${SubtitleParser.formatCompactTime(schedOutcome.boundaryEndMs)} · $verifiedPct% total",
                                scoutCaptionState = ScoutCaptionState.Ready(
                                    sourceLabel = "${finalTier.displayLabel} · Window Ready",
                                    cueCount = postLoopProj.dialogueEvents.size,
                                    speakerCount = postLoopProj.speakers.size,
                                ),
                            )
                        }
                        // Suspend cleanly — ZERO requests while paused/idle; wakes on playback advance, committed seek, new evidence, or retry
                        awaitSchedulerWake(outerWakeEpoch)
                    }
                    SchedulerOutcomeState.UNRESOLVED_AFTER_BOUNDED_RECOVERY -> {
                        val unresolvedMsg = if (postLoopProj.dialogueEvents.isNotEmpty()) {
                            "Some dialogue is unavailable (${finalSummary.unresolvedGapCount} gap(s) in current window) · ${postLoopProj.dialogueEvents.size} bubbles"
                        } else {
                            "Some dialogue is unavailable (${finalSummary.unresolvedGapCount} unresolved gap(s)) — tap Retry AI or attach SRT"
                        }
                        _uiState.update {
                            it.copy(
                                isTranslatingAi = false,
                                isOpeningSpeakersReady = postLoopProj.dialogueEvents.isNotEmpty(),
                                scoutStatusText = unresolvedMsg,
                                scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · Bounded recovery stopped · ${finalSummary.unresolvedGapCount} unresolved gap(s)",
                                scoutCaptionState = if (postLoopProj.dialogueEvents.isNotEmpty()) {
                                    ScoutCaptionState.Ready(
                                        sourceLabel = "${finalTier.displayLabel} · Partial",
                                        cueCount = postLoopProj.dialogueEvents.size,
                                        speakerCount = postLoopProj.speakers.size,
                                    )
                                } else {
                                    ScoutCaptionState.Error(message = unresolvedMsg, canRetryAi = true)
                                },
                            )
                        }
                        // Suspend cleanly without endless retries; explicit user retry, seek, or playback advance wakes cleanly
                        awaitSchedulerWake(outerWakeEpoch)
                    }
                    SchedulerOutcomeState.WAITING_FOR_EVIDENCE_OR_CAPABILITY -> {
                        val waitMsg = schedOutcome.idleReason.ifBlank { "Waiting for captions or analysis capability" }
                        _uiState.update {
                            it.copy(
                                isTranslatingAi = false,
                                scoutStatusText = waitMsg,
                                scoutDiagnosticDetail = "${com.example.data.ai.AppCheckDiagnostics.summaryLine()} · $waitMsg",
                            )
                        }
                        awaitSchedulerWake(outerWakeEpoch)
                    }
                    SchedulerOutcomeState.ACTIVE -> {
                        delay(200L)
                    }
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
            fetched.toReferenceTrack(mediaId = afterFetchProj.id)?.let { refTrack ->
                releaseStore.updateReferenceTrack(refTrack, activeSessionToken.get())
                _uiState.update { s ->
                    s.copy(
                        releaseSnapshot = releaseStore.snapshot(),
                        youtubeCapabilities = s.youtubeCapabilities.copy(
                            hasProgrammaticCaptions = true,
                            captionProvenance = "${refTrack.originKind.name} (${refTrack.cues.size} cues · ${refTrack.languageCode.ifBlank { "und" }})",
                        ),
                    )
                }
            }
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
        val updatedChapters = if (fetched.chapters.isNotEmpty()) fetched.chapters else afterFetchProj.chapters
        val patchedProject = afterFetchProj.copy(
            title = fetched.title?.takeIf { it.isNotBlank() } ?: afterFetchProj.title,
            videoSource = afterFetchProj.videoSource.copy(
                durationMs = updatedDuration,
                channelName = fetched.channelName?.takeIf { it.isNotBlank() } ?: afterFetchProj.videoSource.channelName,
                description = fetched.description?.takeIf { it.isNotBlank() } ?: afterFetchProj.videoSource.description,
            ),
            shots = listOf(Shot("shot_1", 0L, updatedDuration, cutType = "EMBEDDED_YOUTUBE")),
            chapters = updatedChapters,
        )
        readinessController.updateDuration(updatedDuration)
        sentinelCoordinator.planDefaultChapterPatrol(patchedProject)
        updateProject(patchedProject)
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

    fun createDirectMediaUrlProject(
        mediaUrl: String,
        subtitleUri: Uri?,
        subtitleFileName: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
        pastedSubtitleText: String = "",
    ) {
        val resolved = MediaSourceAdapterRegistry.default.resolveInput(mediaUrl)
        if (resolved != null && !resolved.isSupported) {
            _uiState.update { it.copy(statusBanner = resolved.userNotice) }
            return
        }
        val cleanUrl = resolved?.normalizedUri ?: mediaUrl.trim()
        val parsedUri = runCatching { Uri.parse(cleanUrl) }.getOrNull() ?: return
        val title = resolved?.suggestedTitle?.ifBlank { "Direct Video Stream" } ?: "Direct Video Stream"
        if (pastedSubtitleText.isNotBlank() && subtitleUri == null) {
            val sessionToken = nextSessionToken()
            analysisJob?.cancel()
            aiTranslationJob?.cancel()
            speakerAnalysisJob?.cancel()
            castDiscoveryJob?.cancel()
            youtubeTranscriptJob?.cancel()
            introJob?.cancel()
            lookaheadJob?.cancel()
            viewModelScope.launch {
                val cues = SubtitleParser.parseSmartTranscript(pastedSubtitleText)
                val durationMs = (cues.maxOfOrNull { it.endTimeMs } ?: 180_000L).coerceAtLeast(60_000L)
                val source = VideoSource(
                    kind = VideoSourceKind.LOCAL_MP4,
                    uriString = cleanUrl,
                    displayName = title,
                    durationMs = durationMs,
                    width = 1920,
                    height = 1080,
                )
                val initialSpeakers = listOf(
                    Speaker("spk_a", "Speaker A", accentIndex = 0),
                    Speaker("spk_b", "Speaker B", accentIndex = 1),
                )
                val shots = listOf(Shot("shot_1", 0L, durationMs, cutType = "DIRECT_STREAM"))
                val rawEvents = SubtitleParser.applyReadingPacingGuardrails(
                    cues.map { cue ->
                        DialogueEvent(
                            id = SubtitleParser.newEventId(),
                            startTimeMs = cue.startTimeMs,
                            endTimeMs = cue.endTimeMs,
                            sourceText = cue.text,
                            translatedText = cue.text,
                            speakerId = null,
                            speakerConfidence = 0.5f,
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
                val (speakers, events) = attributionEngine.assignSpeakers(
                    events = rawEvents,
                    shots = shots,
                    faceTracks = emptyList(),
                    existingSpeakers = initialSpeakers,
                    rememberedCharacterColors = _uiState.value.characterColorMemory,
                )
                if (!isCurrentSession(sessionToken)) return@launch
                val project = DialogueLensProject(
                    id = "direct_${System.currentTimeMillis()}",
                    title = title,
                    createdAtEpochMs = System.currentTimeMillis(),
                    videoSource = source,
                    subtitleSource = SubtitleSourceKind.UPLOADED_FILE,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    translationMode = translationMode,
                    speakers = speakers,
                    shots = shots,
                    faceTracks = emptyList(),
                    dialogueEvents = events,
                )
                loadProject(project)
            }
            return
        }
        createProjectFromMedia(
            videoUri = parsedUri,
            videoTitle = title,
            subtitleUri = subtitleUri,
            subtitleFileName = subtitleFileName,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translationMode = translationMode,
            subtitleSource = if (subtitleUri != null) SubtitleSourceKind.UPLOADED_FILE else SubtitleSourceKind.AI_DRAFT,
            qualityMode = ProcessingQualityMode.BALANCED,
        )
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
        val resolved = MediaSourceAdapterRegistry.default.resolveInput(webUrl)
        if (resolved != null && !resolved.isSupported) {
            _uiState.update { it.copy(statusBanner = resolved.userNotice) }
            return
        }
        if (resolved?.providerKind == com.example.domain.media.MediaProviderKind.DIRECT_MEDIA_URL) {
            createDirectMediaUrlProject(
                mediaUrl = resolved.normalizedUri,
                subtitleUri = subtitleUri,
                subtitleFileName = subtitleFileName,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translationMode = translationMode,
                pastedSubtitleText = pastedSubtitleText,
            )
            return
        }
        val sessionToken = nextSessionToken()
        analysisJob?.cancel()
        aiTranslationJob?.cancel()
        speakerAnalysisJob?.cancel()
        castDiscoveryJob?.cancel()
        youtubeTranscriptJob?.cancel()
        introJob?.cancel()
        lookaheadJob?.cancel()
        changeoverPreListenJob?.cancel()
        healBeaconJob?.cancel()
        seekAckTimeoutJob?.cancel()
        statusBannerJob?.cancel()
        clearCapturedAudioQueueSync()
        downgradedToLiteForStream = false
        pendingWebTimedTextFallback = null
        scoutHeartbeatService.resetForNewStream(
            if (_uiState.value.quotaSaverMode == QuotaSaverMode.PRO_ACCURACY) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
        )
        scoutHeartbeatService.setZeroCloudPaused(_uiState.value.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD)
        _uiState.update {
            it.copy(
                scoutCaptionState = ScoutCaptionState.Idle,
                scoutCaptureDiagnostics = ScoutCaptureDiagnostics(),
                youtubePlayerErrorKind = null,
            )
        }
        lastScoutSeekSentAtMs = 0L
        lastScoutSeekTargetMs = -1L
        scoutCompletedExtents.clear()
        scoutAudioSliceTimestamps.clear()
        capturedYouTubeTimedTextKeys.clear()
        autoAnalyzedEventIds.clear()
        settledAttributedEventIds.clear()
        lastSceneSideResetKey = ""
        cachedAiTurnHints.clear()
        lastAutoAiTriggerElapsedMs = 0L
        discoveredWebMediaUrl = null
        discoveredWebMediaHeaders = emptyMap()
        currentLiveFaces = emptyList()
        lastDetectorFrameId = -1L
        measuredBubbleSizes.clear()
        layoutEngine.stabilityDiagnostics.reset()
        coverageLedger.reset(0L, source = SubtitleSourceKind.BUILT_IN_DEMO)
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
            if (!isCurrentSession(sessionToken)) return@launch
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

            if (cues.isNotEmpty()) {
                coverageLedger.markAuthoritativeTrack(
                    durationMs = defaultDurationMs,
                    events = events,
                    source = SubtitleSourceKind.UPLOADED_FILE,
                )
            } else {
                coverageLedger.reset(defaultDurationMs, source = SubtitleSourceKind.AI_DRAFT)
            }
            val initSummary = coverageLedger.summary()
            val initDiag = coverageLedger.diagnostics()

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
            contextEngine.resetForProject(webProject)
            autoSelfHealDetector.resetForProject(webProject.id)
            sentinelCoordinator.resetForProject(webProject)
            val restoredGen = sentinelCoordinator.currentSchedulingGeneration()
            releaseStore.resetSession(
                mediaId = webProject.id,
                sessionToken = sessionToken,
                seekGeneration = restoredGen,
                configFingerprint = currentConfigFingerprint(webProject, _uiState.value.quotaSaverMode),
            )
            val initReadiness = readinessController.resetForMedia(
                initialPositionMs = 0L,
                durationMs = defaultDurationMs,
                requiresCoverageGate = cues.isEmpty(),
                initialIntent = UserPlaybackIntent.PAUSE,
                playbackSpeed = _uiState.value.playbackSpeed,
                initialSeekGeneration = restoredGen,
            )
            _uiState.update {
                val activeMode = it.displayMode.normalizedPrimaryMode()
                val (balloons, activeChapter) = computeContextAwareLayout(
                    project = webProject,
                    timeMs = 0L,
                    styleSettings = it.styleSettings,
                    liveFaces = currentLiveFaces,
                    displayMode = activeMode,
                )
                it.copy(
                    project = webProject,
                    activeDestination = AppDestination.WATCH,
                    showStartLauncher = false,
                    hasPendingPlayIntro = true,
                    displayMode = activeMode,
                    isCinemaIntroActive = false,
                    introRemainingMs = 0L,
                    canUndo = false,
                    isPlaying = false,
                    currentTimeMs = 0L,
                    scoutTimeMs = 0L,
                    scoutedUpToMs = initSummary.continuousVerifiedUpToMs,
                    scoutStatusText = "Scout gathering stream captions, audio & cast...",
                    selectedEventId = null,
                    isTranslatingAi = false,
                    streamPlayRequest = null,
                    youtubeSeekRequest = null,
                    analysisStatus = AnalysisStatus.Ready,
                    seekGeneration = restoredGen,
                    readinessSnapshot = initReadiness,
                    sentinelSnapshot = sentinelCoordinator.inspectorSnapshot(),
                    releaseSnapshot = releaseStore.snapshot(),
                    visualStabilitySnapshot = layoutEngine.stabilityDiagnostics.snapshot(),
                    liveFaceExclusionRects = emptyList(),
                    bufferedWindowRanges = initSummary.bufferedRanges,
                    scoutCompletedWindows = 0,
                    scoutTotalWindows = 0,
                    scoutFailedSliceCount = initSummary.failedIntervalCount,
                    coverageSummary = initSummary,
                    transcriptDiagnostics = initDiag,
                    youtubeNativeCcTrackCount = null,
                    youtubeNativeCcLang = "",
                    youtubeNativeCcState = YouTubeNativeCcState.OFF,
                    youtubeNativeCcTrackEnabled = false,
                    youtubeNativeCcProbeBlocked = false,
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
                    placedBalloons = balloons,
                    activeChapter = activeChapter,
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
                if (!isSameResolvedLanguage(sourceLanguage, targetLanguage, events.map { it.sourceText })) {
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

    private fun persistYouTubeVideoCache(
        project: DialogueLensProject,
        scoutedUpToMs: Long,
        sessionToken: Long = activeSessionToken.get(),
    ) {
        if (!isCurrentSession(sessionToken, project.id)) return
        val vid = project.videoSource.youtubeVideoId
        val intervals = coverageLedger.intervalsSnapshot()
        val hasCoverageHistory = intervals.any {
            it.verified || it.status == CoverageStatus.PARTIAL_RECOVERY || it.status == CoverageStatus.FAILED
        }
        if (project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT ||
            vid.isNullOrBlank() ||
            (!LookaheadWindowPlanner.hasRealSubtitles(project.dialogueEvents) && !hasCoverageHistory)
        ) {
            return
        }
        val key = ProjectStore.canonicalYouTubeCacheKey(vid, project.targetLanguage)
        val honestHorizonMs = coverageLedger.continuousVerifiedUpToMs().coerceAtLeast(0L)
        viewModelScope.launch {
            if (!isCurrentSession(sessionToken, project.id)) return@launch
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
                    scoutedUpToMs = honestHorizonMs.takeIf { intervals.isNotEmpty() } ?: scoutedUpToMs,
                    castDiscoverySummary = project.castDiscoverySummary,
                    cacheVersion = CachedVideoTranscript.CURRENT_CACHE_VERSION,
                    coverageIntervals = intervals,
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
        val chaptersWithFallback = if (project.chapters.isEmpty() && project.dialogueEvents.size >= 4) {
            HierarchicalContextEngine.inferEditorialChapters(
                events = project.dialogueEvents,
                durationMs = project.videoSource.durationMs,
            )
        } else {
            project.chapters
        }
        val covPct = (coverageLedger.summary().verifiedFraction * 100f).toInt().takeIf { it > 0 } ?: project.coveragePercent
        val curInMemory = _uiState.value.project
        val preservedPosMs = if (curInMemory.id == project.id &&
            curInMemory.lastWatchedAtEpochMs > project.lastWatchedAtEpochMs &&
            curInMemory.lastPlaybackPositionMs > 0L
        ) {
            curInMemory.lastPlaybackPositionMs
        } else {
            project.lastPlaybackPositionMs
        }
        val preservedWatchedAt = if (curInMemory.id == project.id) {
            maxOf(curInMemory.lastWatchedAtEpochMs, project.lastWatchedAtEpochMs)
        } else {
            project.lastWatchedAtEpochMs
        }
        val normalizedProject = project.copy(
            speakers = reconciledSpeakers,
            chapters = chaptersWithFallback,
            coveragePercent = covPct,
            lastPlaybackPositionMs = preservedPosMs,
            lastWatchedAtEpochMs = preservedWatchedAt,
        )
        if (updatedMemory != _uiState.value.characterColorMemory) {
            persistCharacterColorMemory(updatedMemory)
        }
        contextEngine.syncProjectMemory(normalizedProject)
        _uiState.update { state ->
            val (balloons, activeChapter) = computeContextAwareLayout(
                project = normalizedProject,
                timeMs = state.currentTimeMs,
                styleSettings = state.styleSettings,
                liveFaces = currentLiveFaces,
                displayMode = state.displayMode,
            )
            state.copy(
                project = normalizedProject,
                placedBalloons = balloons,
                activeChapter = activeChapter,
                humanReadiness = HumanReadableStatus.resolve(
                    project = normalizedProject,
                    scoutState = state.scoutCaptionState,
                    coverageSummary = state.coverageSummary,
                    readinessSnapshot = state.readinessSnapshot,
                    releaseSnapshot = state.releaseSnapshot,
                    sentinelSnapshot = state.sentinelSnapshot,
                ),
                mediaCapabilities = MediaSourceAdapterRegistry.default.capabilitiesFor(
                    source = normalizedProject.videoSource,
                    hasDiscoveredDirectAudio = !discoveredWebMediaUrl.isNullOrBlank(),
                ),
            )
        }
        if (!banner.isNullOrBlank()) {
            showStatusBanner(banner)
        }
        if (normalizedProject.id != DemoScenarioFactory.DEMO_PROJECT_ID) {
            viewModelScope.launch {
                projectStore.saveProject(normalizedProject)
                refreshSavedProjects()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Heal Beacon System ("Pause. Mark. Heal. Continue") & Automatic Self-Heal
    // -------------------------------------------------------------------------

    fun markHealBeaconAtCurrentTime(
        scope: HealScope = HealScope.ADAPTIVE,
        autoTriggered: Boolean = false,
    ) {
        val state = _uiState.value
        val ts = state.currentTimeMs.coerceAtLeast(0L)
        val (updatedProj, beacon) = HealBeaconEngine.placeOrMergeBeacon(
            project = state.project,
            timestampMs = ts,
            scope = scope,
            autoTriggered = autoTriggered,
            generationToken = state.seekGeneration,
        )
        sentinelCoordinator.enqueueHealBeacon(beacon)
        updateProject(
            project = updatedProj,
            banner = if (autoTriggered) null else "Moment marked for healing — you can keep watching",
        )
        enqueueHealBeaconProcessing()
    }

    fun inspectHealBeacon(beaconId: String?) {
        _uiState.update { it.copy(selectedHealBeaconId = beaconId) }
    }

    fun updateHealBeaconProblemHint(beaconId: String, hint: HealProblemHint) {
        val current = _uiState.value.project
        val updated = current.copy(
            healBeacons = current.healBeacons.map { b ->
                if (b.id == beaconId) b.copy(problemHint = hint, updatedAtEpochMs = System.currentTimeMillis()) else b
            },
        )
        updateProject(updated)
    }

    fun retryHealBeacon(beaconId: String, scopeOverride: HealScope? = null) {
        val state = _uiState.value
        val existing = state.project.healBeacons.firstOrNull { it.id == beaconId } ?: return
        val effectiveScope = scopeOverride ?: existing.scope
        val window = HealBeaconEngine.computeWindow(existing.timestampMs, effectiveScope, state.project)
        val queued = existing.copy(
            scope = effectiveScope,
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            status = HealBeaconStatus.MARKED,
            repairRevision = existing.repairRevision + 1,
            generationToken = state.seekGeneration,
            updatedAtEpochMs = System.currentTimeMillis(),
            statusNote = "Queued for localized repair",
        )
        val updated = state.project.copy(
            healBeacons = state.project.healBeacons.map { if (it.id == beaconId) queued else it },
        )
        sentinelCoordinator.enqueueHealBeacon(queued)
        updateProject(updated)
        enqueueHealBeaconProcessing()
    }

    fun undoHealBeacon(beaconId: String) {
        val current = _uiState.value.project
        val reverted = HealBeaconEngine.undoBeaconRepair(current, beaconId)
        if (reverted != current) {
            updateProject(reverted, banner = "Original dialogue restored for that moment")
        }
    }

    fun dismissHealBeacon(beaconId: String) {
        val current = _uiState.value.project
        val updated = HealBeaconEngine.dismissBeacon(current, beaconId)
        _uiState.update { cur ->
            cur.copy(selectedHealBeaconId = if (cur.selectedHealBeaconId == beaconId) null else cur.selectedHealBeaconId)
        }
        updateProject(updated)
    }

    private fun enqueueHealBeaconProcessing() {
        if (healBeaconJob?.isActive == true) return
        val sessionToken = activeSessionToken.get()
        healBeaconJob = viewModelScope.launch {
            healBeaconMutex.withLock {
                while (isActive) {
                    val state = _uiState.value
                    val project = state.project
                    if (!isCurrentSession(sessionToken, project.id)) break
                    val nextBeacon = project.healBeacons.firstOrNull { it.status == HealBeaconStatus.MARKED } ?: break
                    sentinelCoordinator.enqueueHealBeacon(nextBeacon)
                    val claimed = sentinelCoordinator.claimHealBeaconWork(
                        workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
                        targetBeaconId = nextBeacon.id,
                    )
                    if (claimed == null) {
                        val snap = sentinelCoordinator.inspectorSnapshot()
                        _uiState.update { it.copy(sentinelSnapshot = snap) }
                        break
                    }
                    val (_, reservation) = claimed
                    try {
                        processSingleHealBeacon(nextBeacon.id, sessionToken, reservation.reservationId)
                    } finally {
                        sentinelCoordinator.markWorkerIdle(
                            workerId = SentinelWorkforceCoordinator.WORKER_2_ID,
                            reservationId = reservation.reservationId,
                        )
                        _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
                    }
                }
            }
        }
    }

    private suspend fun processSingleHealBeacon(
        beaconId: String,
        sessionToken: Long,
        reservationId: String = "",
    ) {
        val startState = _uiState.value
        val startProject = startState.project
        if (!isCurrentSession(sessionToken, startProject.id)) return
        val beacon = startProject.healBeacons.firstOrNull { it.id == beaconId } ?: return
        val repairCtx = captureWorkRequestContext(startProject, _uiState.value.quotaSaverMode)
        if (beacon.generationToken > 0L && beacon.generationToken != repairCtx.seekGeneration) {
            val supersededProj = HealBeaconEngine.markBeaconSuperseded(
                project = startProject,
                beaconId = beaconId,
                reason = "Superseded by newer seek/session generation",
            )
            updateProject(supersededProj)
            return
        }

        val healingBeacon = beacon.copy(
            status = HealBeaconStatus.HEALING,
            generationToken = repairCtx.seekGeneration,
            coordinatorJobId = reservationId.ifBlank { beacon.coordinatorJobId },
            updatedAtEpochMs = System.currentTimeMillis(),
            statusNote = "Re-checking dialogue and speaker context…",
        )
        updateProject(
            startProject.copy(
                healBeacons = startProject.healBeacons.map { if (it.id == beaconId) healingBeacon else it },
            )
        )

        val quotaMode = _uiState.value.quotaSaverMode
        val videoSource = startProject.videoSource

        // Local/Zero-Cloud safe revalidation path (also used for Demo scenarios or quota circuit tripped)
        if (quotaMode == QuotaSaverMode.ZERO_CLOUD ||
            quotaCircuitBreakerTripped ||
            videoSource.kind == VideoSourceKind.DEMO_SCENARIO
        ) {
            val sanitizedEvents = AutoSelfHealDetector.sanitizeCorruptedAnchors(startProject.dialogueEvents)
            val windowEvents = sanitizedEvents.filter { evt ->
                val mid = (evt.startTimeMs + evt.endTimeMs) / 2L
                mid in beacon.windowStartMs..beacon.windowEndMs
            }
            val cleanedProj = AutoSelfHealDetector.consolidateDuplicateSpeakers(
                startProject.copy(dialogueEvents = sanitizedEvents),
            )
            val (reSpeakers, reAttributed) = attributionEngine.assignSpeakers(
                events = windowEvents,
                shots = cleanedProj.shots,
                faceTracks = cleanedProj.faceTracks,
                existingSpeakers = cleanedProj.speakers,
                rememberedCharacterColors = _uiState.value.characterColorMemory,
                slotToSpeakerId = buildSlotToSpeakerIdMap(cleanedProj.speakers),
            )
            if (!isRequestContextCurrent(repairCtx)) {
                val curProj = _uiState.value.project
                if (curProj.id == startProject.id) {
                    updateProject(HealBeaconEngine.markBeaconSuperseded(curProj, beaconId, reason = "Superseded during local repair"))
                }
                return
            }
            val outcome = HealBeaconEngine.applyValidatedLocalizedRepair(
                project = cleanedProj.copy(speakers = reSpeakers),
                beaconId = beaconId,
                candidateEvents = reAttributed,
                silenceExplicitlyConfirmed = false,
                expectedRepairRevision = beacon.repairRevision,
                expectedGenerationToken = repairCtx.seekGeneration,
            )
            if (isRequestContextCurrent(repairCtx)) {
                layoutEngine.invalidateEventsOnly(outcome.modifiedEventIds)
                beacon.patrolChapterId?.let { chId ->
                    sentinelCoordinator.markChapterRepairVerified(chId, outcome.reason)
                }
                updateProject(outcome.updatedProject)
            }
            return
        }

        val isAutoPatrol = beacon.patrolChapterId != null

        // Cloud localized reanalysis for YouTube streams via ResilientYouTubeSegmentExecutor
        if (videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT && !videoSource.youtubeVideoId.isNullOrBlank()) {
            val ytId = videoSource.youtubeVideoId
            val startSec = (beacon.windowStartMs / 1000L).toInt().coerceAtLeast(0)
            val rawEndSec = ((beacon.windowEndMs + 999L) / 1000L).toInt().coerceAtLeast(startSec + 6)
            val endSec = if (isAutoPatrol) {
                rawEndSec.coerceAtMost((sentinelCoordinator.currentBoundaryEndMs() / 1000L).toInt())
            } else {
                rawEndSec
            }
            if (isAutoPatrol && endSec <= startSec) return
            val initialTier = if (quotaMode == QuotaSaverMode.QUOTA_SAVER || downgradedToLiteForStream) {
                ScoutExecutionTier.LITE_AI
            } else {
                ScoutExecutionTier.BEST_AI
            }
            val mergedGlossary = (startProject.glossary + _uiState.value.glossaryEntries)
                .distinctBy { it.term.trim().lowercase() }
            try {
                val segOutcome = segmentExecutor.executeSegmentDetailed(
                    startSec = startSec,
                    endSec = endSec,
                    initialTier = initialTier,
                    onStatusUpdate = {},
                    onDowngradedToLite = { downgradedToLiteForStream = true },
                    canInvokeProvider = { sliceStart, sliceEnd, _ ->
                        canInvokeCloudAi(
                            ctx = repairCtx,
                            sliceStartMs = sliceStart * 1000L,
                            sliceEndMs = sliceEnd * 1000L,
                            enforceBoundary = isAutoPatrol,
                        )
                    },
                ) { sliceStart, sliceEnd, preferLite, clarificationRetry ->
                    val snapProj = _uiState.value.project
                    translationAiService.transcribeYouTubeVideoWindow(
                        videoId = ytId,
                        windowStartSec = sliceStart,
                        windowEndSec = sliceEnd,
                        totalDurationSec = (snapProj.videoSource.durationMs / 1000L).toInt().coerceAtLeast(60),
                        sourceLanguage = snapProj.sourceLanguage,
                        targetLanguage = snapProj.targetLanguage,
                        videoContext = buildVideoContextString(snapProj),
                        knownSpeakers = snapProj.speakers,
                        glossary = mergedGlossary,
                        preferLiteModel = preferLite,
                        timestampClarificationRetry = clarificationRetry,
                    )
                }

                if (!isRequestContextCurrent(repairCtx)) {
                    val curProj = _uiState.value.project
                    if (curProj.id == startProject.id) {
                        updateProject(HealBeaconEngine.markBeaconSuperseded(curProj, beaconId, reason = "Superseded during YouTube repair"))
                    }
                    return
                }
                val liveProj = _uiState.value.project
                when (segOutcome) {
                    is SegmentExecutionOutcome.Completed -> {
                        val (reconciledCues, reconciledUpdates, _) = reconcileWindowOutcomeSpeakersAndCues(
                            existingSpeakers = liveProj.speakers,
                            cues = segOutcome.cues,
                            speakerUpdates = segOutcome.speakerUpdates,
                        )
                        var workingProj = liveProj
                        if (reconciledUpdates.isNotEmpty()) {
                            val (castMerged, updatedMem) = mergeCastSlotAssignments(
                                project = workingProj,
                                assignments = reconciledUpdates,
                                rememberedColors = _uiState.value.characterColorMemory,
                                reconcileColors = attributionEngine::reconcileCharacterColors,
                                summary = segOutcome.summary,
                                discoveryTimestampMs = beacon.windowStartMs,
                            )
                            if (updatedMem != _uiState.value.characterColorMemory) {
                                persistCharacterColorMemory(updatedMem)
                            }
                            workingProj = castMerged
                        }
                        val validSlots = setOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")
                        val candidateEvents = reconciledCues.map { cue ->
                            val isOffOrNonSpoken = cue.dialogueType != DialogueType.SPOKEN
                            val spatialAnchor = if (!isOffOrNonSpoken && cue.anchorX != null && cue.anchorY != null) {
                                NormalizedPoint(cue.anchorX, cue.anchorY)
                            } else {
                                null
                            }
                            val slotUpper = cue.suggestedSpeakerSlot.trim().uppercase()
                            val matchedSpeaker = if (slotUpper in validSlots) {
                                workingProj.speakers.firstOrNull {
                                    it.id.equals("spk_${slotUpper.lowercase()}", ignoreCase = true)
                                }
                            } else {
                                null
                            }
                            DialogueEvent(
                                id = SubtitleParser.newEventId(),
                                startTimeMs = cue.startMs,
                                endTimeMs = cue.endMs,
                                sourceText = cue.sourceText,
                                translatedText = cue.naturalTranslation,
                                literalText = cue.literalTranslation,
                                romanization = cue.romanization,
                                speakerId = matchedSpeaker?.id,
                                speakerConfidence = if (matchedSpeaker != null) 0.86f else 0.45f,
                                faceTrackId = null,
                                faceConfidence = if (spatialAnchor != null) 0.78f else 0f,
                                shotId = workingProj.shots.firstOrNull { cue.startMs in it.startMs..it.endMs }?.id ?: "shot_1",
                                dialogueType = cue.dialogueType,
                                anchorPosition = spatialAnchor,
                                placementMode = when {
                                    isOffOrNonSpoken -> PlacementMode.EDGE_DOCKED
                                    spatialAnchor != null -> PlacementMode.AUTO
                                    else -> PlacementMode.NEUTRAL_FALLBACK
                                },
                                contextCueReason = cue.contextReason,
                                culturalNote = cue.culturalNote,
                                preferredDeadSpaceZone = cue.deadSpaceZone,
                                avoidBoxes = cue.avoidBoxes,
                            )
                        }
                        val silenceConfirmed = segOutcome.cues.isEmpty() &&
                            segOutcome.verifiedSilentRanges.isNotEmpty() &&
                            segOutcome.failedRanges.isEmpty()
                        val repairOutcome = HealBeaconEngine.applyValidatedLocalizedRepair(
                            project = workingProj,
                            beaconId = beaconId,
                            candidateEvents = candidateEvents,
                            silenceExplicitlyConfirmed = silenceConfirmed,
                            expectedRepairRevision = beacon.repairRevision,
                            expectedGenerationToken = repairCtx.seekGeneration,
                        )
                        if (!isRequestContextCurrent(repairCtx)) {
                            updateProject(HealBeaconEngine.markBeaconSuperseded(liveProj, beaconId, reason = "Superseded before publication"))
                            return
                        }
                        settledAttributedEventIds.removeAll(repairOutcome.modifiedEventIds)
                        repairOutcome.updatedProject.dialogueEvents
                            .filter { it.id in repairOutcome.modifiedEventIds && (it.speakerId != null || it.attributionSummary == "Unidentified speaker") }
                            .forEach { settledAttributedEventIds.add(it.id) }
                        layoutEngine.invalidateEventsOnly(repairOutcome.modifiedEventIds)
                        beacon.patrolChapterId?.let { chId ->
                            sentinelCoordinator.markChapterRepairVerified(chId, repairOutcome.reason)
                        }
                        updateProject(repairOutcome.updatedProject)
                        _uiState.update { it.copy(sentinelSnapshot = sentinelCoordinator.inspectorSnapshot()) }
                    }
                    is SegmentExecutionOutcome.QuotaExhausted -> {
                        val attentionBeacon = healingBeacon.copy(
                            status = HealBeaconStatus.NEEDS_ATTENTION,
                            updatedAtEpochMs = System.currentTimeMillis(),
                            attemptCount = healingBeacon.attemptCount + 1,
                            statusNote = "AI quota reached — existing dialogue kept safely. Retry later or edit in Studio.",
                        )
                        updateProject(
                            liveProj.copy(
                                healBeacons = liveProj.healBeacons.map { if (it.id == beaconId) attentionBeacon else it },
                            )
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                if (!isCurrentSession(sessionToken, startProject.id)) return
                val liveProj = _uiState.value.project
                val attentionBeacon = healingBeacon.copy(
                    status = HealBeaconStatus.NEEDS_ATTENTION,
                    updatedAtEpochMs = System.currentTimeMillis(),
                    attemptCount = healingBeacon.attemptCount + 1,
                    statusNote = "Could not complete localized repair — check connection and retry.",
                )
                updateProject(
                    liveProj.copy(
                        healBeacons = liveProj.healBeacons.map { if (it.id == beaconId) attentionBeacon else it },
                    )
                )
            }
            return
        }

        // Cross-platform audio-slice AI repair for Local MP4, Direct Media URLs (HTTP/HLS),
        // and HTML5 Web Streams that expose an accessible non-DRM audio stream URL.
        val liveProj = _uiState.value.project
        val directHttpAudioUrl: String? = when {
            videoSource.kind == VideoSourceKind.LOCAL_MP4 &&
                (videoSource.uriString.startsWith("http://", true) || videoSource.uriString.startsWith("https://", true)) ->
                videoSource.uriString
            videoSource.kind == VideoSourceKind.WEB_STREAM && !discoveredWebMediaUrl.isNullOrBlank() ->
                discoveredWebMediaUrl
            else -> null
        }
        val localContentUri: Uri? = if (
            videoSource.kind == VideoSourceKind.LOCAL_MP4 &&
            directHttpAudioUrl == null &&
            videoSource.uriString.isNotBlank()
        ) {
            runCatching { Uri.parse(videoSource.uriString) }.getOrNull()
        } else {
            null
        }

        if (localContentUri != null || !directHttpAudioUrl.isNullOrBlank()) {
            try {
                val wavChunk = audioAnalyzer.extractWavWindowForAsr(
                    uri = localContentUri,
                    httpUrl = directHttpAudioUrl,
                    httpHeaders = if (videoSource.kind == VideoSourceKind.WEB_STREAM) discoveredWebMediaHeaders else emptyMap(),
                    windowStartMs = beacon.windowStartMs,
                    windowEndMs = beacon.windowEndMs,
                )
                if (wavChunk != null &&
                    wavChunk.wavBytes.isNotEmpty() &&
                    canInvokeCloudAi(repairCtx, wavChunk.startMs, wavChunk.endMs, enforceBoundary = isAutoPatrol)
                ) {
                    val mergedGlossary = (liveProj.glossary + _uiState.value.glossaryEntries)
                        .distinctBy { it.term.trim().lowercase() }
                    val preferLite = quotaMode == QuotaSaverMode.QUOTA_SAVER || downgradedToLiteForStream
                    val aiResult = translationAiService.transcribeAndTranslateAudioDetailed(
                        wavBytes = wavChunk.wavBytes,
                        chunkStartMs = wavChunk.startMs,
                        sourceLanguage = liveProj.sourceLanguage,
                        targetLanguage = liveProj.targetLanguage,
                        glossary = mergedGlossary,
                        preferLiteModel = preferLite,
                    )
                    if (!isRequestContextCurrent(repairCtx)) return
                    if (aiResult.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(aiResult.exceptionOrNull())) {
                        quotaCircuitBreakerTripped = true
                    }
                    val currentProj = _uiState.value.project
                    val detailed = aiResult.getOrNull()
                    val isUsableOutcome = detailed != null && (
                        detailed.outcome == TranscriptionOutcome.DIALOGUE_DETECTED ||
                            detailed.outcome == TranscriptionOutcome.VERIFIED_SILENCE ||
                            detailed.outcome == TranscriptionOutcome.PARTIAL_SUCCESS
                        )
                    if (isUsableOutcome && detailed != null) {
                        val candidateEvents = detailed.cues.map { cue ->
                            DialogueEvent(
                                id = SubtitleParser.newEventId(),
                                startTimeMs = cue.startMs,
                                endTimeMs = cue.endMs,
                                sourceText = cue.sourceText,
                                translatedText = cue.naturalTranslation,
                                literalText = cue.literalTranslation,
                                romanization = cue.romanization,
                                speakerId = null,
                                speakerConfidence = 0.55f,
                                faceTrackId = null,
                                faceConfidence = 0f,
                                shotId = currentProj.shots.firstOrNull { cue.startMs in it.startMs..it.endMs }?.id ?: "shot_1",
                                dialogueType = cue.dialogueType,
                                placementMode = if (cue.dialogueType == DialogueType.SPOKEN) {
                                    PlacementMode.NEUTRAL_FALLBACK
                                } else {
                                    PlacementMode.EDGE_DOCKED
                                },
                                culturalNote = cue.culturalNote,
                            )
                        }
                        val (reSpeakers, attributedCandidates) = if (candidateEvents.isNotEmpty()) {
                            attributionEngine.assignSpeakers(
                                events = candidateEvents,
                                shots = currentProj.shots,
                                faceTracks = currentProj.faceTracks,
                                existingSpeakers = currentProj.speakers,
                                rememberedCharacterColors = _uiState.value.characterColorMemory,
                                slotToSpeakerId = buildSlotToSpeakerIdMap(currentProj.speakers),
                            )
                        } else {
                            currentProj.speakers to emptyList()
                        }
                        if (!isRequestContextCurrent(repairCtx)) {
                            updateProject(HealBeaconEngine.markBeaconSuperseded(currentProj, beaconId, reason = "Superseded before publication"))
                            return
                        }
                        val silenceConfirmed = detailed.outcome == TranscriptionOutcome.VERIFIED_SILENCE
                        val repairOutcome = HealBeaconEngine.applyValidatedLocalizedRepair(
                            project = currentProj.copy(speakers = reSpeakers),
                            beaconId = beaconId,
                            candidateEvents = attributedCandidates,
                            silenceExplicitlyConfirmed = silenceConfirmed,
                            expectedRepairRevision = beacon.repairRevision,
                            expectedGenerationToken = repairCtx.seekGeneration,
                        )
                        updateProject(repairOutcome.updatedProject)
                        return
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Fall through cleanly to local revalidation below without losing existing dialogue
            }
        }

        if (!isRequestContextCurrent(repairCtx)) {
            val curProj = _uiState.value.project
            if (curProj.id == startProject.id) {
                updateProject(HealBeaconEngine.markBeaconSuperseded(curProj, beaconId, reason = "Superseded before fallback repair"))
            }
            return
        }
        // Graceful fallback: local speaker re-attribution & anchor sanitization (preserves all dialogue)
        val sanitized = AutoSelfHealDetector.consolidateDuplicateSpeakers(
            liveProj.copy(dialogueEvents = AutoSelfHealDetector.sanitizeCorruptedAnchors(liveProj.dialogueEvents)),
        )
        val windowEvents = sanitized.dialogueEvents.filter { evt ->
            val mid = (evt.startTimeMs + evt.endTimeMs) / 2L
            mid in beacon.windowStartMs..beacon.windowEndMs
        }
        val (reSpeakers, reAttributed) = attributionEngine.assignSpeakers(
            events = windowEvents,
            shots = sanitized.shots,
            faceTracks = sanitized.faceTracks,
            existingSpeakers = sanitized.speakers,
            rememberedCharacterColors = _uiState.value.characterColorMemory,
            slotToSpeakerId = buildSlotToSpeakerIdMap(sanitized.speakers),
        )
        val fallbackOutcome = HealBeaconEngine.applyValidatedLocalizedRepair(
            project = sanitized.copy(speakers = reSpeakers),
            beaconId = beaconId,
            candidateEvents = reAttributed,
            silenceExplicitlyConfirmed = false,
            expectedRepairRevision = beacon.repairRevision,
            expectedGenerationToken = repairCtx.seekGeneration,
        )
        val finalProject = if (videoSource.kind == VideoSourceKind.WEB_STREAM && discoveredWebMediaUrl.isNullOrBlank()) {
            val notedBeacons = fallbackOutcome.updatedProject.healBeacons.map { b ->
                if (b.id == beaconId) {
                    b.copy(
                        statusNote = "Refreshed speaker placement locally. This web player restricts direct audio extraction — attach a subtitle file or edit in Studio for text changes.",
                    )
                } else b
            }
            fallbackOutcome.updatedProject.copy(healBeacons = notedBeacons)
        } else {
            fallbackOutcome.updatedProject
        }
        if (isRequestContextCurrent(repairCtx)) {
            updateProject(finalProject)
        }
    }

    private fun maybeRunAutomaticSelfHealCheck(timeMs: Long, placedBalloons: List<PlacedBalloon>) {
        val now = System.currentTimeMillis()
        if (now - lastAutoSelfHealCheckAtMs < 4_500L) return
        lastAutoSelfHealCheckAtMs = now
        val state = _uiState.value
        if (state.showStartLauncher || state.isCinemaIntroActive) return
        val findings = autoSelfHealDetector.inspectPlaybackFrame(
            project = state.project,
            playbackMs = timeMs,
            placedBalloons = placedBalloons,
        )
        if (findings.isEmpty()) return

        // 1. Apply safe zero-cloud local fixes immediately (duplicate speakers / corrupted coords)
        if (findings.any { it.kind == SelfHealSignalKind.DUPLICATE_SPEAKER_IDENTITIES || it.kind == SelfHealSignalKind.INVALID_COORDINATES }) {
            val cleaned = AutoSelfHealDetector.consolidateDuplicateSpeakers(
                state.project.copy(
                    dialogueEvents = AutoSelfHealDetector.sanitizeCorruptedAnchors(state.project.dialogueEvents),
                ),
            )
            if (cleaned != state.project) {
                updateProject(cleaned)
            }
        }

        // 2. Bounded automatic cloud healing only for structural anomalies when authorized
        val cloudWorthy = findings.firstOrNull { !it.canFixLocallyWithoutCloud } ?: return
        if (autoSelfHealDetector.canTriggerAutomaticCloudHeal(
                project = _uiState.value.project,
                timestampMs = cloudWorthy.timestampMs,
                quotaMode = state.quotaSaverMode,
                nowEpochMs = now,
            )
        ) {
            autoSelfHealDetector.recordAutomaticCloudHealTriggered(cloudWorthy.timestampMs, now)
            markHealBeaconAtCurrentTime(scope = HealScope.QUICK, autoTriggered = true)
        }
    }

    internal fun maybePersistPlaybackProgress(timeMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgressPersistedAtMs < 10_000L) return
        val proj = _uiState.value.project
        if (proj.id == DemoScenarioFactory.DEMO_PROJECT_ID) return
        lastProgressPersistedAtMs = now
        val schedState = sentinelCoordinator.exportPersistedState(now)
        val updated = proj.copy(
            lastPlaybackPositionMs = timeMs,
            lastWatchedAtEpochMs = now,
            schedulingState = schedState,
        )
        _uiState.update { cur ->
            if (cur.project.id == proj.id) {
                cur.copy(
                    project = cur.project.copy(
                        lastPlaybackPositionMs = timeMs,
                        lastWatchedAtEpochMs = now,
                        schedulingState = schedState,
                    )
                )
            } else {
                cur
            }
        }
        viewModelScope.launch {
            projectStore.saveProject(updated)
        }
    }

    internal fun persistPlaybackProgressNow() {
        val state = _uiState.value
        val proj = state.project
        if (proj.id == DemoScenarioFactory.DEMO_PROJECT_ID) return
        val now = System.currentTimeMillis()
        lastProgressPersistedAtMs = now
        val schedState = sentinelCoordinator.exportPersistedState(now)
        val updated = proj.copy(
            lastPlaybackPositionMs = state.currentTimeMs,
            lastWatchedAtEpochMs = now,
            schedulingState = schedState,
        )
        _uiState.update { cur ->
            if (cur.project.id == proj.id) {
                cur.copy(
                    project = cur.project.copy(
                        lastPlaybackPositionMs = state.currentTimeMs,
                        lastWatchedAtEpochMs = now,
                        schedulingState = schedState,
                    )
                )
            } else {
                cur
            }
        }
        viewModelScope.launch {
            projectStore.saveProject(updated)
            refreshSavedProjects()
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
        changeoverPreListenJob?.cancel()
        healBeaconJob?.cancel()
        statusBannerJob?.cancel()
        seekAckTimeoutJob?.cancel()
        clearCapturedAudioQueueSync()
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
    val coverageIntervals: List<CoverageInterval> = emptyList(),
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
                nameRevealedAtMs = existing.nameRevealedAtMs ?: discoveryTimestampMs,
                isUserNamed = existing.isUserNamed,
                revealProvenance = if (existing.isUserNamed) "USER_RENAME" else "SCOUT_DISCOVERY",
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
    sceneBoundaryMs: List<Long> = emptyList(),
): List<DialogueEvent> {
    if (events.isEmpty()) return events
    val sortedBoundaries = sceneBoundaryMs.filter { it > 0L }.sorted()
    fun sceneKeyFor(event: DialogueEvent): String {
        val chapterBucket = sortedBoundaries.count { event.startTimeMs >= it }
        return "${event.shotId}:$chapterBucket"
    }

    val speakerById = speakers.associateBy { it.id }
    val spokenAvgSideBySceneAndSpeaker = events
        .filter { it.dialogueType == DialogueType.SPOKEN && it.speakerId != null && it.anchorPosition != null }
        .groupBy { sceneKeyFor(it) to it.speakerId!! }
        .mapValues { (_, list) -> list.mapNotNull { it.anchorPosition?.x }.average().toFloat() }
    val allowGlobalFallback = sortedBoundaries.isEmpty()

    return events.map { evt ->
        val isOffScreen = evt.dialogueType == DialogueType.OFF_SCREEN_LEFT ||
            evt.dialogueType == DialogueType.OFF_SCREEN_RIGHT ||
            evt.dialogueType == DialogueType.OFF_SCREEN_BEHIND
        if (!isOffScreen) return@map evt

        val resolvedType = if (evt.dialogueType == DialogueType.OFF_SCREEN_BEHIND && evt.speakerId != null) {
            val sceneSideX = spokenAvgSideBySceneAndSpeaker[sceneKeyFor(evt) to evt.speakerId]
            val sideX = sceneSideX ?: if (allowGlobalFallback) speakerById[evt.speakerId]?.preferredScreenSideX else null
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

internal fun parseCapturedWebTrackJson(
    rawJson: String,
    shotId: String = "shot_1",
    sourceLanguage: String = "English",
    targetLanguage: String = "English",
): List<DialogueEvent> {
    if (rawJson.length > 1_000_000) return emptyList()
    val parsed = runCatching {
        webTrackAdapter.fromJson(rawJson)
    }.getOrNull() ?: return emptyList()

    val srcNorm = Scout2IndependentReviewer.normalizeLanguageTag(sourceLanguage)
    val tgtNorm = Scout2IndependentReviewer.normalizeLanguageTag(targetLanguage)
    val maxDurationCapMs = 86_400_000L
    val events = parsed.take(2_500).mapNotNull { item ->
        val cleanedText = item.text
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
            .trim()
        if (cleanedText.isEmpty() || cleanedText.length > 1_000) return@mapNotNull null
        val startMs = item.startMs.coerceIn(0L, maxDurationCapMs)
        val endMs = item.endMs.coerceIn(startMs + 350L, minOf(maxDurationCapMs + 60_000L, startMs + 60_000L))
        val inferredType = if (
            cleanedText.startsWith("[") || cleanedText.startsWith("(") || cleanedText.contains("♪") || cleanedText.contains("♫")
        ) {
            DialogueType.SOUND_DESCRIPTION
        } else {
            DialogueType.SPOKEN
        }
        val detectedScript = Scout2IndependentReviewer.detectDominantScriptLanguage(cleanedText)
        val needsTranslation = tgtNorm.isNotBlank() && tgtNorm != "auto" && when {
            srcNorm.isNotBlank() && srcNorm != "auto" -> srcNorm != tgtNorm || (detectedScript.isNotBlank() && detectedScript != tgtNorm)
            detectedScript.isNotBlank() -> detectedScript != tgtNorm
            else -> true
        }
        DialogueEvent(
            id = SubtitleParser.newEventId(),
            startTimeMs = startMs,
            endTimeMs = endMs,
            sourceText = cleanedText,
            translatedText = if (needsTranslation) "" else cleanedText,
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
            needsTranslation = needsTranslation,
        )
    }
    return SubtitleParser.applyReadingPacingGuardrails(events)
}
