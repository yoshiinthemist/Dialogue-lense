package com.example.ui.state

import android.net.Uri
import com.example.data.ai.AiTranslatedLine
import com.example.data.ai.OnDeviceTranslatorService
import com.example.data.ai.ScoutHeartbeatService
import com.example.data.ai.TranslationAiService
import com.example.data.ai.awaitWarmAppCheckToken
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CachedVideoTranscript
import com.example.data.model.CoverageInterval
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.GlossaryEntry
import com.example.data.model.NormalizedPoint
import com.example.data.model.PlacementMode
import com.example.data.model.ProcessingQualityMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.project.ProjectStore
import com.example.data.subtitle.RawSubtitleCue
import com.example.data.subtitle.SubtitleParser
import com.example.data.subtitle.YouTubeCaptionFetcher
import com.example.domain.analysis.AudioAnalyzer
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.ResilientYouTubeSegmentExecutor
import com.example.domain.analysis.ScoutExecutionTier
import com.example.domain.analysis.SegmentExecutionOutcome
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.analysis.TranscriptionOutcome
import com.example.domain.analysis.VideoAnalyzer
import com.example.domain.attribution.SpeakerAttributionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SubtitlePrepState {
    data object Idle : SubtitlePrepState
    data class Working(
        val title: String,
        val sourceLabel: String,
        val progress: Float,
        val completedWindows: Int,
        val totalWindows: Int,
        val cueCount: Int,
        val speakerCount: Int,
        val statusText: String,
        val partialProject: DialogueLensProject? = null,
    ) : SubtitlePrepState

    data class Ready(
        val generatedProject: DialogueLensProject,
        val sourceLabel: String,
        val cueCount: Int,
        val speakerCount: Int,
        val isPartial: Boolean = false,
        val note: String = "",
    ) : SubtitlePrepState

    data class Error(
        val message: String,
        val partialProject: DialogueLensProject? = null,
    ) : SubtitlePrepState
}

/**
 * Generates full-timeline subtitle projects (.srt / .vtt / .ass) directly from the Home Screen
 * without requiring the player view to stay open, following Quota-Saver priority:
 * Per-Video Cache -> Free YouTube CC / Embedded Track -> Heartbeat-guarded Flash-Lite + ML Kit.
 */
class SubtitlePreGenerator(
    private val projectStore: ProjectStore,
    private val videoAnalyzer: VideoAnalyzer,
    private val audioAnalyzer: AudioAnalyzer,
    private val attributionEngine: SpeakerAttributionEngine,
    private val translationAiService: TranslationAiService,
    private val scoutHeartbeatService: ScoutHeartbeatService,
    private val onDeviceTranslator: OnDeviceTranslatorService,
) {
    private val segmentExecutor = ResilientYouTubeSegmentExecutor(scoutHeartbeatService)

    suspend fun prepareFromTextOrCues(
        title: String,
        videoUrl: String,
        videoKind: VideoSourceKind,
        youtubeVideoId: String?,
        rawText: String,
        fileName: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
        styleSettings: BalloonStyleSettings,
        glossary: List<GlossaryEntry>,
        characterColorMemory: Map<String, Int>,
        quotaSaverMode: QuotaSaverMode,
        quotaCircuitBreakerTripped: Boolean,
        sourceLabel: String = "Attached Text",
        onProgress: (SubtitlePrepState) -> Unit,
    ) {
        onProgress(
            SubtitlePrepState.Working(
                title = title,
                sourceLabel = sourceLabel,
                progress = 0.25f,
                completedWindows = 0,
                totalWindows = 1,
                cueCount = 0,
                speakerCount = 2,
                statusText = "Parsing & timing subtitle lines...",
            )
        )
        val cues = if (fileName.isNotBlank()) {
            SubtitleParser.parse(rawText, fileName)
        } else {
            SubtitleParser.parseSmartTranscript(rawText)
        }
        if (cues.isEmpty()) {
            onProgress(SubtitlePrepState.Error("No valid subtitle lines detected in input."))
            return
        }
        val baseProject = buildProjectFromCues(
            id = "prep_${System.currentTimeMillis()}",
            title = title,
            videoUrl = videoUrl,
            videoKind = videoKind,
            youtubeVideoId = youtubeVideoId,
            cues = cues,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translationMode = translationMode,
            subtitleSource = SubtitleSourceKind.UPLOADED_FILE,
            styleSettings = styleSettings,
            glossary = glossary,
            characterColorMemory = characterColorMemory,
            durationHintMs = null,
        )
        val translatedProject = maybeTranslateProjectOfflineOrCloud(
            project = baseProject,
            quotaSaverMode = quotaSaverMode,
            quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
            onStatus = { msg ->
                onProgress(
                    SubtitlePrepState.Working(
                        title = baseProject.title,
                        sourceLabel = sourceLabel,
                        progress = 0.72f,
                        completedWindows = 0,
                        totalWindows = 1,
                        cueCount = baseProject.dialogueEvents.size,
                        speakerCount = baseProject.speakers.size,
                        statusText = msg,
                        partialProject = baseProject,
                    )
                )
            },
        )
        onProgress(
            SubtitlePrepState.Ready(
                generatedProject = translatedProject,
                sourceLabel = sourceLabel,
                cueCount = translatedProject.dialogueEvents.size,
                speakerCount = translatedProject.speakers.size,
                note = "Ready to download or launch straight into playback.",
            )
        )
    }

    suspend fun prepareYouTubeSubtitles(
        youtubeUrl: String,
        videoId: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
        styleSettings: BalloonStyleSettings,
        glossary: List<GlossaryEntry>,
        characterColorMemory: Map<String, Int>,
        quotaSaverMode: QuotaSaverMode,
        quotaCircuitBreakerTripped: Boolean,
        onQuotaTripped: () -> Unit,
        onProgress: (SubtitlePrepState) -> Unit,
    ) {
        val defaultTitle = "YouTube · $videoId"
        val cacheKey = ProjectStore.canonicalYouTubeCacheKey(videoId, targetLanguage)
        onProgress(
            SubtitlePrepState.Working(
                title = defaultTitle,
                sourceLabel = "Checking Cache",
                progress = 0.06f,
                completedWindows = 0,
                totalWindows = 1,
                cueCount = 0,
                speakerCount = 2,
                statusText = "Checking local per-video cache ($0)...",
            )
        )

        val cached = projectStore.loadCachedTranscript(cacheKey)
        val restoredIntervals = if (cached != null) {
            TranscriptCoverageLedger.restoreOrMigrate(
                cached = cached,
                resolvedDurationMs = maxOf(cached.durationMs, cached.scoutedUpToMs, 60_000L),
            )
        } else {
            emptyList()
        }
        if (cached != null && LookaheadWindowPlanner.hasRealSubtitles(cached.dialogueEvents)) {
            val cachedDur = maxOf(cached.durationMs, cached.scoutedUpToMs, 60_000L)
            val cacheLedger = TranscriptCoverageLedger(initialDurationMs = cachedDur, initialIntervals = restoredIntervals)
            val cachedProj = DialogueLensProject(
                id = "yt_prep_${videoId}_${System.currentTimeMillis()}",
                title = cached.title.ifBlank { defaultTitle },
                createdAtEpochMs = System.currentTimeMillis(),
                sourceLanguage = cached.sourceLanguage.ifBlank { sourceLanguage },
                targetLanguage = targetLanguage,
                translationMode = translationMode,
                subtitleSource = cached.subtitleSource,
                qualityMode = ProcessingQualityMode.BALANCED,
                videoSource = VideoSource(
                    uriString = youtubeUrl.trim(),
                    displayName = "YouTube ($videoId)",
                    kind = VideoSourceKind.YOUTUBE_COMPLIANT,
                    durationMs = cachedDur,
                    width = 1920,
                    height = 1080,
                    youtubeVideoId = videoId,
                    channelName = cached.channelName,
                    description = cached.description,
                ),
                shots = cached.shots.ifEmpty { listOf(Shot("shot_1", 0L, cachedDur, cutType = "EMBEDDED_YOUTUBE")) },
                speakers = cached.speakers.ifEmpty { defaultSpeakers() },
                faceTracks = emptyList(),
                dialogueEvents = cached.dialogueEvents,
                styleSettings = styleSettings,
                glossary = glossary,
                castDiscoverySummary = cached.castDiscoverySummary,
            )
            val isAuthoritativeTrack = cached.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK ||
                cached.subtitleSource == SubtitleSourceKind.UPLOADED_FILE
            val isFullCache = isAuthoritativeTrack || cacheLedger.isFullyVerified()
            if (isFullCache) {
                onProgress(
                    SubtitlePrepState.Ready(
                        generatedProject = cachedProj,
                        sourceLabel = "Cached ($0)",
                        cueCount = cachedProj.dialogueEvents.size,
                        speakerCount = cachedProj.speakers.size,
                        note = "Restored verified cached transcript at $0 credits.",
                    )
                )
                return
            }
        }

        val skipUpfrontFreeCc = LookaheadWindowPlanner.shouldSkipUpfrontFreeCaptionSearch(
            quotaSaverMode = quotaSaverMode,
            quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
        )

        if (!skipUpfrontFreeCc) {
            onProgress(
                SubtitlePrepState.Working(
                    title = cached?.title?.ifBlank { defaultTitle } ?: defaultTitle,
                    sourceLabel = "Free YouTube CC",
                    progress = 0.14f,
                    completedWindows = 0,
                    totalWindows = 1,
                    cueCount = 0,
                    speakerCount = 2,
                    statusText = if (quotaCircuitBreakerTripped) {
                        "Credits depleted — searching free YouTube closed captions ($0)..."
                    } else {
                        "Searching free YouTube closed captions ($0)..."
                    },
                )
            )
            val fetched = YouTubeCaptionFetcher.fetchMetadataAndCaptions(
                videoId = videoId,
                preferredSourceLanguage = sourceLanguage,
                preferredTargetLanguage = targetLanguage,
            )
            if (fetched.cues.isNotEmpty()) {
                val resolvedTitle = fetched.title?.takeIf { it.isNotBlank() } ?: (cached?.title?.ifBlank { defaultTitle } ?: defaultTitle)
                val resolvedChannel = fetched.channelName?.takeIf { it.isNotBlank() } ?: cached?.channelName.orEmpty()
                val resolvedDescription = fetched.description?.takeIf { it.isNotBlank() } ?: cached?.description.orEmpty()
                val resolvedDurationMs = maxOf(
                    fetched.durationMs ?: 0L,
                    cached?.durationMs ?: 0L,
                    60_000L,
                )
                val resolvedSrcLang = if (sourceLanguage.equals("Auto-Detect", ignoreCase = true)) {
                    isoToFriendlyLanguage(fetched.languageCode, sourceLanguage)
                } else {
                    sourceLanguage
                }
                val ccProj = buildProjectFromCues(
                    id = "yt_prep_${videoId}_${System.currentTimeMillis()}",
                    title = resolvedTitle,
                    videoUrl = youtubeUrl.trim(),
                    videoKind = VideoSourceKind.YOUTUBE_COMPLIANT,
                    youtubeVideoId = videoId,
                    cues = fetched.cues,
                    sourceLanguage = resolvedSrcLang,
                    targetLanguage = targetLanguage,
                    translationMode = translationMode,
                    subtitleSource = SubtitleSourceKind.EMBEDDED_TRACK,
                    styleSettings = styleSettings,
                    glossary = glossary,
                    characterColorMemory = characterColorMemory,
                    durationHintMs = resolvedDurationMs,
                    channelName = resolvedChannel,
                    description = resolvedDescription,
                )
                val finalCcProj = maybeTranslateProjectOfflineOrCloud(
                    project = ccProj,
                    quotaSaverMode = quotaSaverMode,
                    quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                    onQuotaTripped = onQuotaTripped,
                    onStatus = { msg ->
                        onProgress(
                            SubtitlePrepState.Working(
                                title = ccProj.title,
                                sourceLabel = "Free YouTube CC",
                                progress = 0.78f,
                                completedWindows = 1,
                                totalWindows = 1,
                                cueCount = ccProj.dialogueEvents.size,
                                speakerCount = ccProj.speakers.size,
                                statusText = msg,
                                partialProject = ccProj,
                            )
                        )
                    },
                )
                saveCacheEntry(finalCcProj, finalCcProj.videoSource.durationMs)
                onProgress(
                    SubtitlePrepState.Ready(
                        generatedProject = finalCcProj,
                        sourceLabel = "Free YouTube CC ($0)",
                        cueCount = finalCcProj.dialogueEvents.size,
                        speakerCount = finalCcProj.speakers.size,
                        note = "Captured free native YouTube captions and cached for instant playback.",
                    )
                )
                return
            }

            val reason = if (quotaCircuitBreakerTripped) {
                "Credits depleted and no free YouTube CC track was exposed — open stream to capture player captions or attach an .srt."
            } else {
                "No free YouTube CC track was found while Free CC Only is active. Switch to Best Model to pre-generate with Gemini 3.8 Flash."
            }
            onProgress(SubtitlePrepState.Error(reason))
            return
        }

        // Best-Model-First / Lite-First path: start AI immediately without blocking 8-10s on Free CC endpoints.
        coroutineScope {
            val fastMetaDeferred = async {
                runCatching { YouTubeCaptionFetcher.fetchFastMetadataOnly(videoId) }.getOrNull()
            }

            var activeTier = LookaheadWindowPlanner.initialExecutionTier(
                quotaSaverMode = quotaSaverMode,
                quotaCircuitBreakerTripped = false,
            )
            val initialDurationMs = maxOf(cached?.durationMs ?: 0L, 180_000L)
            var durationSec = (initialDurationMs / 1000L).toInt().coerceAtLeast(60)
            var workingProj = DialogueLensProject(
                id = "yt_prep_${videoId}_${System.currentTimeMillis()}",
                title = cached?.title?.ifBlank { defaultTitle } ?: defaultTitle,
                createdAtEpochMs = System.currentTimeMillis(),
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translationMode = translationMode,
                subtitleSource = SubtitleSourceKind.AI_DRAFT,
                qualityMode = ProcessingQualityMode.BALANCED,
                videoSource = VideoSource(
                    uriString = youtubeUrl.trim(),
                    displayName = "YouTube ($videoId)",
                    kind = VideoSourceKind.YOUTUBE_COMPLIANT,
                    durationMs = initialDurationMs,
                    width = 1920,
                    height = 1080,
                    youtubeVideoId = videoId,
                    channelName = cached?.channelName.orEmpty(),
                    description = cached?.description.orEmpty(),
                ),
                shots = listOf(Shot("shot_1", 0L, initialDurationMs, cutType = "EMBEDDED_YOUTUBE")),
                speakers = cached?.speakers?.ifEmpty { defaultSpeakers() } ?: defaultSpeakers(),
                faceTracks = emptyList(),
                dialogueEvents = cached?.dialogueEvents.orEmpty(),
                styleSettings = styleSettings,
                glossary = glossary,
                castDiscoverySummary = cached?.castDiscoverySummary.orEmpty(),
            )

            // Peek up to 350ms for instant title/duration without stalling opening AI call
            val quickMeta = withTimeoutOrNull(350L) { fastMetaDeferred.await() }
            if (quickMeta != null) {
                val quickDur = maxOf(workingProj.videoSource.durationMs, quickMeta.durationMs ?: 0L)
                durationSec = (quickDur / 1000L).toInt().coerceAtLeast(60)
                workingProj = workingProj.copy(
                    title = quickMeta.title?.takeIf { it.isNotBlank() } ?: workingProj.title,
                    videoSource = workingProj.videoSource.copy(
                        durationMs = quickDur,
                        channelName = quickMeta.channelName?.takeIf { it.isNotBlank() } ?: workingProj.videoSource.channelName,
                        description = quickMeta.description?.takeIf { it.isNotBlank() } ?: workingProj.videoSource.description,
                    ),
                    shots = listOf(Shot("shot_1", 0L, quickDur, cutType = "EMBEDDED_YOUTUBE")),
                )
            }

            val hintMap = mutableMapOf<String, AiTranslatedLine>()
            var currentColorMemory = characterColorMemory
            val ledger = TranscriptCoverageLedger(
                initialDurationMs = workingProj.videoSource.durationMs,
                initialIntervals = restoredIntervals,
            )

            awaitWarmAppCheckToken(forceRefresh = false, timeoutMs = 4_000L)

            while (true) {
                if (fastMetaDeferred.isCompleted) {
                    val resolvedMeta = runCatching { fastMetaDeferred.getCompleted() }.getOrNull()
                    if (resolvedMeta != null) {
                        val metaDur = maxOf(workingProj.videoSource.durationMs, resolvedMeta.durationMs ?: 0L)
                        if (metaDur != workingProj.videoSource.durationMs ||
                            (!resolvedMeta.title.isNullOrBlank() && workingProj.title == defaultTitle)
                        ) {
                            durationSec = (metaDur / 1000L).toInt().coerceAtLeast(60)
                            ledger.updateDuration(metaDur)
                            workingProj = workingProj.copy(
                                title = resolvedMeta.title?.takeIf { it.isNotBlank() } ?: workingProj.title,
                                videoSource = workingProj.videoSource.copy(
                                    durationMs = metaDur,
                                    channelName = resolvedMeta.channelName?.takeIf { it.isNotBlank() } ?: workingProj.videoSource.channelName,
                                    description = resolvedMeta.description?.takeIf { it.isNotBlank() } ?: workingProj.videoSource.description,
                                ),
                                shots = listOf(Shot("shot_1", 0L, metaDur, cutType = "EMBEDDED_YOUTUBE")),
                            )
                        }
                    }
                }

                val nextInterval = ledger.nextPriorityInterval(
                    playheadMs = 0L,
                    maxRetryAttempts = 3,
                ) ?: break

                val startSec = (nextInterval.startMs / 1000L).toInt().coerceAtLeast(0)
                val endSec = ((nextInterval.endMs + 999L) / 1000L).toInt().coerceAtLeast(startSec + 1)
                val sliceStartMs = startSec * 1000L
                val sliceEndMs = minOf(workingProj.videoSource.durationMs, endSec * 1000L)
                ledger.markInProgress(sliceStartMs, sliceEndMs)
                ledger.recordWindowRequested()

                val curSum = ledger.summary()
                val estTotalWindows = ((workingProj.videoSource.durationMs / 40_000L).toInt()).coerceAtLeast(1)
                val verifiedCount = curSum.verifiedIntervalCount
                val windowLabel = "${SubtitleParser.formatCompactTime(sliceStartMs)}–${SubtitleParser.formatCompactTime(sliceEndMs)}"
                val modelTag = activeTier.displayLabel
                onProgress(
                    SubtitlePrepState.Working(
                        title = workingProj.title,
                        sourceLabel = modelTag,
                        progress = (0.12f + 0.84f * curSum.verifiedFraction).coerceIn(0.12f, 0.96f),
                        completedWindows = verifiedCount,
                        totalWindows = maxOf(estTotalWindows, verifiedCount + curSum.unresolvedGapCount),
                        cueCount = workingProj.dialogueEvents.size,
                        speakerCount = workingProj.speakers.size,
                        statusText = if (nextInterval.status == CoverageStatus.FAILED || nextInterval.status == CoverageStatus.PARTIAL_RECOVERY) {
                            "$modelTag healing gap ($windowLabel)..."
                        } else {
                            "$modelTag verifying segment ($windowLabel)..."
                        },
                        partialProject = workingProj.takeIf { LookaheadWindowPlanner.hasRealSubtitles(it.dialogueEvents) },
                    )
                )

                val videoContext = buildString {
                    if (workingProj.title.isNotBlank()) append("Title: ${workingProj.title}")
                    if (workingProj.videoSource.channelName.isNotBlank()) append(" | Channel: ${workingProj.videoSource.channelName}")
                    if (workingProj.videoSource.description.isNotBlank()) {
                        append(" | Description: ${workingProj.videoSource.description.replace('\n', ' ').take(420)}")
                    }
                }

                val outcome = segmentExecutor.executeSegmentDetailed(
                    startSec = startSec,
                    endSec = endSec,
                    initialTier = activeTier,
                    onStatusUpdate = { statusMsg ->
                        val snapSum = ledger.summary()
                        onProgress(
                            SubtitlePrepState.Working(
                                title = workingProj.title,
                                sourceLabel = activeTier.displayLabel,
                                progress = (0.12f + 0.84f * snapSum.verifiedFraction).coerceIn(0.12f, 0.96f),
                                completedWindows = snapSum.verifiedIntervalCount,
                                totalWindows = maxOf(estTotalWindows, snapSum.verifiedIntervalCount + snapSum.unresolvedGapCount),
                                cueCount = workingProj.dialogueEvents.size,
                                speakerCount = workingProj.speakers.size,
                                statusText = "$statusMsg ($windowLabel)",
                                partialProject = workingProj.takeIf { LookaheadWindowPlanner.hasRealSubtitles(it.dialogueEvents) },
                            )
                        )
                    },
                    onDowngradedToLite = {
                        activeTier = ScoutExecutionTier.LITE_AI
                    },
                ) { sliceStart, sliceEnd, preferLite, clarifyTimestamps ->
                    translationAiService.transcribeYouTubeVideoWindow(
                        videoId = videoId,
                        windowStartSec = sliceStart,
                        windowEndSec = sliceEnd,
                        totalDurationSec = durationSec,
                        sourceLanguage = workingProj.sourceLanguage,
                        targetLanguage = targetLanguage,
                        videoContext = videoContext,
                        knownSpeakers = workingProj.speakers,
                        glossary = glossary,
                        preferLiteModel = preferLite,
                        timestampClarificationRetry = clarifyTimestamps,
                    )
                }

                if (outcome is SegmentExecutionOutcome.QuotaExhausted) {
                    onQuotaTripped()
                    val preQuotaSum = ledger.summary()
                    // Credit-dry cascade step 2: try free YouTube closed captions ($0)
                    onProgress(
                        SubtitlePrepState.Working(
                            title = workingProj.title,
                            sourceLabel = "Free CC Fallback",
                            progress = 0.85f,
                            completedWindows = preQuotaSum.verifiedIntervalCount,
                            totalWindows = maxOf(estTotalWindows, preQuotaSum.verifiedIntervalCount + preQuotaSum.unresolvedGapCount),
                            cueCount = workingProj.dialogueEvents.size,
                            speakerCount = workingProj.speakers.size,
                            statusText = "Credits depleted — checking free YouTube CC fallback ($0)...",
                            partialProject = workingProj.takeIf { LookaheadWindowPlanner.hasRealSubtitles(it.dialogueEvents) },
                        )
                    )
                    val fallbackCc = YouTubeCaptionFetcher.fetchMetadataAndCaptions(
                        videoId = videoId,
                        preferredSourceLanguage = sourceLanguage,
                        preferredTargetLanguage = targetLanguage,
                    )
                    if (fallbackCc.cues.isNotEmpty()) {
                        val resolvedSrcLang = if (sourceLanguage.equals("Auto-Detect", ignoreCase = true)) {
                            isoToFriendlyLanguage(fallbackCc.languageCode, sourceLanguage)
                        } else {
                            sourceLanguage
                        }
                        val ccProj = buildProjectFromCues(
                            id = "yt_prep_${videoId}_${System.currentTimeMillis()}",
                            title = fallbackCc.title?.takeIf { it.isNotBlank() } ?: workingProj.title,
                            videoUrl = youtubeUrl.trim(),
                            videoKind = VideoSourceKind.YOUTUBE_COMPLIANT,
                            youtubeVideoId = videoId,
                            cues = fallbackCc.cues,
                            sourceLanguage = resolvedSrcLang,
                            targetLanguage = targetLanguage,
                            translationMode = translationMode,
                            subtitleSource = SubtitleSourceKind.EMBEDDED_TRACK,
                            styleSettings = styleSettings,
                            glossary = glossary,
                            characterColorMemory = currentColorMemory,
                            durationHintMs = maxOf(workingProj.videoSource.durationMs, fallbackCc.durationMs ?: 0L, 60_000L),
                            channelName = fallbackCc.channelName?.takeIf { it.isNotBlank() } ?: workingProj.videoSource.channelName,
                            description = fallbackCc.description?.takeIf { it.isNotBlank() } ?: workingProj.videoSource.description,
                        )
                        val translatedCc = maybeTranslateProjectOfflineOrCloud(
                            project = ccProj,
                            quotaSaverMode = QuotaSaverMode.ZERO_CLOUD,
                            quotaCircuitBreakerTripped = true,
                            onQuotaTripped = onQuotaTripped,
                            onStatus = { msg ->
                                onProgress(
                                    SubtitlePrepState.Working(
                                        title = ccProj.title,
                                        sourceLabel = "Free CC Fallback",
                                        progress = 0.92f,
                                        completedWindows = 1,
                                        totalWindows = 1,
                                        cueCount = ccProj.dialogueEvents.size,
                                        speakerCount = ccProj.speakers.size,
                                        statusText = msg,
                                        partialProject = ccProj,
                                    )
                                )
                            },
                        )
                        val ccLedger = TranscriptCoverageLedger().apply {
                            markAuthoritativeTrack(
                                durationMs = translatedCc.videoSource.durationMs,
                                events = translatedCc.dialogueEvents,
                                source = SubtitleSourceKind.EMBEDDED_TRACK,
                            )
                        }
                        saveCacheEntry(
                            project = translatedCc,
                            scoutedUpToMs = translatedCc.videoSource.durationMs,
                            coverageIntervals = ccLedger.intervalsSnapshot(),
                        )
                        onProgress(
                            SubtitlePrepState.Ready(
                                generatedProject = translatedCc,
                                sourceLabel = "Free CC Fallback ($0)",
                                cueCount = translatedCc.dialogueEvents.size,
                                speakerCount = translatedCc.speakers.size,
                                note = "Cloud credits ran dry — switched cleanly to free native YouTube captions ($0).",
                            )
                        )
                        return@coroutineScope
                    }

                    ledger.recordOutcome(
                        startMs = sliceStartMs,
                        endMs = sliceEndMs,
                        status = CoverageStatus.FAILED,
                        failureReason = "Credits depleted mid-generation",
                        incrementRetry = true,
                    )
                    saveCacheEntry(workingProj, ledger.continuousVerifiedUpToMs(), ledger.intervalsSnapshot())
                    val hasPartial = LookaheadWindowPlanner.hasRealSubtitles(workingProj.dialogueEvents)
                    if (hasPartial) {
                        val partialSum = ledger.summary()
                        onProgress(
                            SubtitlePrepState.Ready(
                                generatedProject = workingProj,
                                sourceLabel = "${activeTier.displayLabel} (Partial)",
                                cueCount = workingProj.dialogueEvents.size,
                                speakerCount = workingProj.speakers.size,
                                isPartial = true,
                                note = "Credits depleted (${(partialSum.verifiedFraction * 100).toInt()}% verified, ${partialSum.unresolvedGapCount} unverified span(s)) — ${workingProj.dialogueEvents.size} partial lines saved.",
                            )
                        )
                    } else {
                        onProgress(
                            SubtitlePrepState.Error(
                                message = "Gemini credits depleted and no free YouTube CC track was found — attach an .srt or open the video player."
                            )
                        )
                    }
                    return@coroutineScope
                }

                val completed = outcome as SegmentExecutionOutcome.Completed
                if (completed.downgradedToLite && activeTier == ScoutExecutionTier.BEST_AI) {
                    activeTier = completed.resolvedTier
                }
                ledger.recordWindowCompleted(completed.retryCount)
                if (completed.invalidTimestampCount > 0) ledger.recordInvalidOrAmbiguousTimestamp(completed.invalidTimestampCount)
                if (completed.quotaFallbackTransitions > 0) ledger.recordQuotaFallbackTransition()

                if (completed.speakerUpdates.isNotEmpty() || completed.summary.isNotBlank()) {
                    val (castMerged, updatedMem) = mergeCastSlotAssignments(
                        project = workingProj,
                        assignments = completed.speakerUpdates,
                        rememberedColors = currentColorMemory,
                        reconcileColors = attributionEngine::reconcileCharacterColors,
                        summary = completed.summary,
                        discoveryTimestampMs = sliceStartMs,
                    )
                    currentColorMemory = updatedMem
                    workingProj = castMerged
                }

                if (completed.cues.isNotEmpty()) {
                    val validSlots = setOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")
                    val windowEvents = completed.cues.map { cue ->
                        val eventId = SubtitleParser.newEventId()
                        val isNonSpeaker = cue.dialogueType == DialogueType.SOUND_DESCRIPTION ||
                            cue.dialogueType == DialogueType.NARRATION
                        val isOffScreen = cue.dialogueType != DialogueType.SPOKEN
                        val spatialAnchor = if (!isOffScreen && cue.anchorX != null && cue.anchorY != null) {
                            NormalizedPoint(cue.anchorX, cue.anchorY)
                        } else null
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
                            speakerConfidence = if (slotUpper in validSlots) 0.84f else 0.42f,
                            faceTrackId = null,
                            faceConfidence = if (spatialAnchor != null) 0.78f else 0f,
                            shotId = "shot_1",
                            dialogueType = cue.dialogueType,
                            anchorPosition = spatialAnchor,
                            placementMode = when {
                                isNonSpeaker || isOffScreen -> PlacementMode.EDGE_DOCKED
                                spatialAnchor != null -> PlacementMode.AUTO
                                else -> PlacementMode.NEUTRAL_FALLBACK
                            },
                            contextCueReason = cue.contextReason,
                            culturalNote = cue.culturalNote,
                            preferredDeadSpaceZone = cue.deadSpaceZone,
                            avoidBoxes = cue.avoidBoxes,
                        )
                    }
                    val paced = SubtitleParser.applyReadingPacingGuardrails(windowEvents)
                    val antiEchoed = LookaheadWindowPlanner.filterEchoedOpeningCues(
                        existing = workingProj.dialogueEvents,
                        incoming = paced,
                        windowStartMs = sliceStartMs,
                    )
                    val mergeReport = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
                        existing = workingProj.dialogueEvents,
                        incoming = antiEchoed,
                        windowStartMs = sliceStartMs,
                        windowEndMs = sliceEndMs,
                    )
                    ledger.recordDuplicateDecisions(mergeReport.decisions)
                    val (attributedSpeakers, attributedEvents) = attributionEngine.assignSpeakers(
                        events = mergeReport.events,
                        shots = workingProj.shots,
                        faceTracks = emptyList(),
                        existingSpeakers = workingProj.speakers,
                        turnHints = hintMap,
                        rememberedCharacterColors = currentColorMemory,
                    )
                    val maxCueEnd = attributedEvents.maxOfOrNull { it.endTimeMs } ?: 0L
                    val expandedDur = maxOf(workingProj.videoSource.durationMs, maxCueEnd, sliceEndMs)
                    ledger.updateDuration(expandedDur)
                    workingProj = workingProj.copy(
                        subtitleSource = SubtitleSourceKind.AI_DRAFT,
                        videoSource = workingProj.videoSource.copy(durationMs = expandedDur),
                        shots = listOf(Shot("shot_1", 0L, expandedDur, cutType = "EMBEDDED_YOUTUBE")),
                        speakers = attributedSpeakers,
                        dialogueEvents = attributedEvents,
                    )
                }

                // Update coverage ledger strictly by actual execution outcomes
                completed.verifiedDialogueRanges.forEach { (vs, ve) ->
                    val sMs = vs * 1000L
                    val eMs = minOf(workingProj.videoSource.durationMs, ve * 1000L)
                    val count = workingProj.dialogueEvents.count { it.startTimeMs in sMs until eMs }
                    if (count > 0) {
                        ledger.recordOutcome(
                            startMs = sMs,
                            endMs = eMs,
                            status = CoverageStatus.VERIFIED_DIALOGUE,
                            source = SubtitleSourceKind.AI_DRAFT,
                            dialogueCount = count,
                        )
                    } else {
                        ledger.recordOutcome(
                            startMs = sMs,
                            endMs = eMs,
                            status = CoverageStatus.FAILED,
                            source = SubtitleSourceKind.AI_DRAFT,
                            dialogueCount = 0,
                            failureReason = "ECHO_OR_EMPTY_SLICE_REJECTED",
                            incrementRetry = true,
                        )
                    }
                }
                completed.verifiedSilentRanges.forEach { (vs, ve) ->
                    ledger.recordOutcome(
                        startMs = vs * 1000L,
                        endMs = minOf(workingProj.videoSource.durationMs, ve * 1000L),
                        status = CoverageStatus.VERIFIED_SILENCE,
                        source = SubtitleSourceKind.AI_DRAFT,
                    )
                }
                completed.failedDetails.forEach { detail ->
                    val status = if (detail.outcome == TranscriptionOutcome.PARTIAL_SUCCESS) {
                        CoverageStatus.PARTIAL_RECOVERY
                    } else {
                        CoverageStatus.FAILED
                    }
                    ledger.recordOutcome(
                        startMs = detail.startSec * 1000L,
                        endMs = minOf(workingProj.videoSource.durationMs, detail.endSec * 1000L),
                        status = status,
                        source = SubtitleSourceKind.AI_DRAFT,
                        failureReason = detail.reason.ifBlank { detail.outcome.name },
                        incrementRetry = true,
                    )
                }
                ledger.intervalsSnapshot()
                    .filter { interval ->
                        interval.status == CoverageStatus.IN_PROGRESS &&
                            interval.startMs < sliceEndMs &&
                            interval.endMs > sliceStartMs
                    }
                    .forEach { stranded ->
                        val overlapStart = maxOf(stranded.startMs, sliceStartMs)
                        val overlapEnd = minOf(stranded.endMs, sliceEndMs)
                        if (overlapEnd > overlapStart) {
                            ledger.recordOutcome(
                                startMs = overlapStart,
                                endMs = overlapEnd,
                                status = CoverageStatus.FAILED,
                                source = SubtitleSourceKind.AI_DRAFT,
                                failureReason = "UNCOMMITTED_WINDOW_SLICE_RECOVERABLE",
                                incrementRetry = true,
                            )
                        }
                    }
                // Defensive guard: if slice still remains IN_PROGRESS (no verified/failed ranges emitted), mark FAILED
                val stillInProgress = ledger.intervalsSnapshot().any {
                    it.status == CoverageStatus.IN_PROGRESS &&
                        it.endMs > sliceStartMs && it.startMs < sliceEndMs
                }
                if (stillInProgress) {
                    ledger.recordOutcome(
                        startMs = sliceStartMs,
                        endMs = sliceEndMs,
                        status = CoverageStatus.FAILED,
                        source = SubtitleSourceKind.AI_DRAFT,
                        failureReason = "No confirmed dialogue or silence outcome returned",
                        incrementRetry = true,
                    )
                }

                saveCacheEntry(
                    project = workingProj,
                    scoutedUpToMs = ledger.continuousVerifiedUpToMs(),
                    coverageIntervals = ledger.intervalsSnapshot(),
                )
                delay(120L)
            }

            val finalSummary = ledger.summary()
            val hasRealDialogue = LookaheadWindowPlanner.hasRealSubtitles(workingProj.dialogueEvents)
            if (hasRealDialogue || finalSummary.isFullyVerified) {
                saveCacheEntry(
                    project = workingProj,
                    scoutedUpToMs = finalSummary.continuousVerifiedUpToMs,
                    coverageIntervals = ledger.intervalsSnapshot(),
                )
                val isFullyVerified = finalSummary.isFullyVerified
                val readyNote = if (isFullyVerified) {
                    "Full episode verified across all ${finalSummary.verifiedIntervalCount} interval(s) — ${workingProj.dialogueEvents.size} lines saved."
                } else {
                    val gapDesc = finalSummary.failedRanges.take(2).joinToString(", ") { range ->
                        "${SubtitleParser.formatCompactTime(range.startMs)}–${SubtitleParser.formatCompactTime(range.endMs)}"
                    }
                    val gapSuffix = if (gapDesc.isNotBlank()) " ($gapDesc)" else ""
                    "Partial transcript (${(finalSummary.verifiedFraction * 100).toInt()}% verified, ${finalSummary.unresolvedGapCount} unverified gap(s)$gapSuffix) — ${workingProj.dialogueEvents.size} lines saved."
                }
                if (hasRealDialogue) {
                    onProgress(
                        SubtitlePrepState.Ready(
                            generatedProject = workingProj,
                            sourceLabel = if (isFullyVerified) "${activeTier.displayLabel} · Cached" else "${activeTier.displayLabel} · Partial",
                            cueCount = workingProj.dialogueEvents.size,
                            speakerCount = workingProj.speakers.size,
                            isPartial = !isFullyVerified,
                            note = readyNote,
                        )
                    )
                } else {
                    onProgress(SubtitlePrepState.Error("Verified silence across entire clip — no spoken dialogue found."))
                }
            } else {
                onProgress(SubtitlePrepState.Error("No spoken dialogue could be extracted from this video."))
            }
        }
    }

    suspend fun prepareLocalVideoSubtitles(
        videoUri: Uri,
        videoTitle: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
        styleSettings: BalloonStyleSettings,
        glossary: List<GlossaryEntry>,
        characterColorMemory: Map<String, Int>,
        quotaSaverMode: QuotaSaverMode,
        quotaCircuitBreakerTripped: Boolean,
        onQuotaTripped: () -> Unit,
        onProgress: (SubtitlePrepState) -> Unit,
    ) {
        val cleanTitle = videoTitle.ifBlank { "Local Video" }
        onProgress(
            SubtitlePrepState.Working(
                title = cleanTitle,
                sourceLabel = "Local Audio",
                progress = 0.08f,
                completedWindows = 0,
                totalWindows = 1,
                cueCount = 0,
                speakerCount = 2,
                statusText = "Inspecting local video metadata & embedded subtitle tracks...",
            )
        )
        val metadata = videoAnalyzer.extractMetadata(videoUri)
        val durationMs = metadata.durationMs.coerceAtLeast(10_000L)
        val embeddedCues = audioAnalyzer.extractEmbeddedSubtitles(videoUri)

        if (embeddedCues.isNotEmpty()) {
            val embProj = buildProjectFromCues(
                id = "local_prep_${System.currentTimeMillis()}",
                title = cleanTitle,
                videoUrl = videoUri.toString(),
                videoKind = VideoSourceKind.LOCAL_MP4,
                youtubeVideoId = null,
                cues = embeddedCues,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translationMode = translationMode,
                subtitleSource = SubtitleSourceKind.EMBEDDED_TRACK,
                styleSettings = styleSettings,
                glossary = glossary,
                characterColorMemory = characterColorMemory,
                durationHintMs = durationMs,
                width = metadata.width,
                height = metadata.height,
            )
            val translated = maybeTranslateProjectOfflineOrCloud(
                project = embProj,
                quotaSaverMode = quotaSaverMode,
                quotaCircuitBreakerTripped = quotaCircuitBreakerTripped,
                onStatus = { msg ->
                    onProgress(
                        SubtitlePrepState.Working(
                            title = cleanTitle,
                            sourceLabel = "Embedded Track ($0)",
                            progress = 0.76f,
                            completedWindows = 1,
                            totalWindows = 1,
                            cueCount = embProj.dialogueEvents.size,
                            speakerCount = embProj.speakers.size,
                            statusText = msg,
                            partialProject = embProj,
                        )
                    )
                },
            )
            onProgress(
                SubtitlePrepState.Ready(
                    generatedProject = translated,
                    sourceLabel = "Embedded Track ($0)",
                    cueCount = translated.dialogueEvents.size,
                    speakerCount = translated.speakers.size,
                    note = "Extracted embedded video subtitles at $0 credits.",
                )
            )
            return
        }

        if (quotaSaverMode == QuotaSaverMode.ZERO_CLOUD || quotaCircuitBreakerTripped) {
            onProgress(
                SubtitlePrepState.Error(
                    if (quotaCircuitBreakerTripped) {
                        "Credits depleted and this file has no embedded subtitle track."
                    } else {
                        "No embedded subtitle track in this file while Zero-Cloud is active. Switch to Quota-Saver to transcribe audio."
                    }
                )
            )
            return
        }

        onProgress(
            SubtitlePrepState.Working(
                title = cleanTitle,
                sourceLabel = "Audio Extraction",
                progress = 0.16f,
                completedWindows = 0,
                totalWindows = 1,
                cueCount = 0,
                speakerCount = 2,
                statusText = "Decoding audio track chunks for transcription...",
            )
        )
        val chunks = audioAnalyzer.extractChunkedWavForAsr(videoUri)
        if (chunks.isEmpty()) {
            onProgress(SubtitlePrepState.Error("No audible speech track found in this video file."))
            return
        }

        var activeTier = LookaheadWindowPlanner.initialExecutionTier(
            quotaSaverMode = quotaSaverMode,
            quotaCircuitBreakerTripped = false,
        )
        val hintMap = mutableMapOf<String, AiTranslatedLine>()
        val collectedEvents = mutableListOf<DialogueEvent>()
        var currentSpeakers = defaultSpeakers()
        val shotList = listOf(Shot("shot_1", 0L, durationMs))

        for ((idx, chunk) in chunks.withIndex()) {
            val partialProjSnapshot = if (collectedEvents.isNotEmpty()) {
                DialogueLensProject(
                    id = "local_prep_${System.currentTimeMillis()}",
                    title = cleanTitle,
                    createdAtEpochMs = System.currentTimeMillis(),
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    translationMode = translationMode,
                    subtitleSource = SubtitleSourceKind.AI_DRAFT,
                    qualityMode = ProcessingQualityMode.BALANCED,
                    videoSource = VideoSource(
                        uriString = videoUri.toString(),
                        displayName = cleanTitle,
                        kind = VideoSourceKind.LOCAL_MP4,
                        durationMs = durationMs,
                        width = metadata.width,
                        height = metadata.height,
                        audioTracks = metadata.audioTracks,
                        embeddedSubtitleTracks = metadata.subtitleTracks,
                    ),
                    shots = shotList,
                    speakers = currentSpeakers,
                    faceTracks = emptyList(),
                    dialogueEvents = SubtitleParser.applyReadingPacingGuardrails(collectedEvents),
                    styleSettings = styleSettings,
                    glossary = glossary,
                )
            } else null

            val modelTag = activeTier.displayLabel
            onProgress(
                SubtitlePrepState.Working(
                    title = cleanTitle,
                    sourceLabel = modelTag,
                    progress = (0.20f + 0.72f * (idx.toFloat() / chunks.size.coerceAtLeast(1))).coerceIn(0.20f, 0.94f),
                    completedWindows = idx,
                    totalWindows = chunks.size,
                    cueCount = collectedEvents.size,
                    speakerCount = currentSpeakers.size,
                    statusText = "$modelTag transcribing audio chunk ${idx + 1}/${chunks.size}...",
                    partialProject = partialProjSnapshot,
                )
            )

            var chunkRes = scoutHeartbeatService.executeWithBackoffRetry(
                modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                maxRetries = 3,
                onWarmToken = { awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L) },
            ) {
                translationAiService.transcribeAndTranslateAudioDetailed(
                    wavBytes = chunk.wavBytes,
                    chunkStartMs = chunk.startMs,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    glossary = glossary,
                    preferLiteModel = activeTier.preferLite,
                )
            }

            if (chunkRes.isFailure &&
                TranslationAiService.isQuotaOrCreditExhaustedError(chunkRes.exceptionOrNull()) &&
                activeTier == ScoutExecutionTier.BEST_AI
            ) {
                activeTier = LookaheadWindowPlanner.nextFallbackTierOnQuotaError(activeTier)
                chunkRes = scoutHeartbeatService.executeWithBackoffRetry(
                    modelName = activeTier.modelId ?: "gemini-3.5-flash-lite",
                    maxRetries = 2,
                    onWarmToken = { awaitWarmAppCheckToken(forceRefresh = true, timeoutMs = 4_000L) },
                ) {
                    translationAiService.transcribeAndTranslateAudioDetailed(
                        wavBytes = chunk.wavBytes,
                        chunkStartMs = chunk.startMs,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        glossary = glossary,
                        preferLiteModel = true,
                    )
                }
            }

            if (chunkRes.isFailure) {
                val err = chunkRes.exceptionOrNull()
                if (TranslationAiService.isQuotaOrCreditExhaustedError(err)) onQuotaTripped()
                if (partialProjSnapshot != null) {
                    onProgress(
                        SubtitlePrepState.Ready(
                            generatedProject = partialProjSnapshot,
                            sourceLabel = "${activeTier.displayLabel} (Partial)",
                            cueCount = partialProjSnapshot.dialogueEvents.size,
                            speakerCount = partialProjSnapshot.speakers.size,
                            isPartial = true,
                            note = "Saved ${partialProjSnapshot.dialogueEvents.size} partial lines before interruption.",
                        )
                    )
                } else {
                    onProgress(
                        SubtitlePrepState.Error(
                            err?.localizedMessage?.take(120) ?: "Audio subtitle generation failed."
                        )
                    )
                }
                return
            }

            val audioResult = chunkRes.getOrThrow()
            audioResult.cues.forEach { c ->
                val id = SubtitleParser.newEventId()
                collectedEvents.add(
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
                        shotId = "shot_1",
                        dialogueType = c.dialogueType,
                        placementMode = if (c.dialogueType == DialogueType.SPOKEN) PlacementMode.AUTO else PlacementMode.EDGE_DOCKED,
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

        if (collectedEvents.isEmpty()) {
            onProgress(SubtitlePrepState.Error("Transcription produced 0 spoken lines for this video."))
            return
        }

        val paced = SubtitleParser.applyReadingPacingGuardrails(collectedEvents)
        val audioFeatures = runCatching { audioAnalyzer.extractVoiceFeatures(videoUri, paced) }.getOrNull().orEmpty()
        val enrichedEvents = paced.map { evt -> evt.copy(audioFeatures = audioFeatures[evt.id] ?: evt.audioFeatures) }
        val (attributedSpeakers, attributed) = attributionEngine.assignSpeakers(
            events = enrichedEvents,
            shots = shotList,
            faceTracks = emptyList(),
            existingSpeakers = currentSpeakers,
            turnHints = hintMap,
            rememberedCharacterColors = characterColorMemory,
        )
        currentSpeakers = attributedSpeakers
        val readyProj = DialogueLensProject(
            id = "local_prep_${System.currentTimeMillis()}",
            title = cleanTitle,
            createdAtEpochMs = System.currentTimeMillis(),
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translationMode = translationMode,
            subtitleSource = SubtitleSourceKind.AI_DRAFT,
            qualityMode = ProcessingQualityMode.BALANCED,
            videoSource = VideoSource(
                uriString = videoUri.toString(),
                displayName = cleanTitle,
                kind = VideoSourceKind.LOCAL_MP4,
                durationMs = durationMs,
                width = metadata.width,
                height = metadata.height,
                audioTracks = metadata.audioTracks,
                embeddedSubtitleTracks = metadata.subtitleTracks,
            ),
            shots = shotList,
            speakers = currentSpeakers,
            faceTracks = emptyList(),
            dialogueEvents = attributed,
            styleSettings = styleSettings,
            glossary = glossary,
        )
        onProgress(
            SubtitlePrepState.Ready(
                generatedProject = readyProj,
                sourceLabel = "${activeTier.displayLabel} Audio",
                cueCount = attributed.size,
                speakerCount = currentSpeakers.size,
                note = "Generated subtitles & voice speaker splits ready for download.",
            )
        )
    }

    private suspend fun maybeTranslateProjectOfflineOrCloud(
        project: DialogueLensProject,
        quotaSaverMode: QuotaSaverMode,
        quotaCircuitBreakerTripped: Boolean,
        onQuotaTripped: () -> Unit = {},
        onStatus: (String) -> Unit,
    ): DialogueLensProject {
        val src = project.sourceLanguage
        val tgt = project.targetLanguage
        if (src.equals(tgt, ignoreCase = true) || src.equals("Auto-Detect", ignoreCase = true)) {
            return project
        }
        val translatable = project.dialogueEvents.filter { it.sourceText.isNotBlank() }
        if (translatable.isEmpty()) return project

        val cacheMap = projectStore.loadTranslationCache().toMutableMap()
        val cachedUpdates = mutableMapOf<String, String>()
        val uncached = mutableListOf<DialogueEvent>()
        translatable.forEach { evt ->
            val key = ProjectStore.buildLineTranslationCacheKey(evt.sourceText, src, tgt)
            val hit = cacheMap[key]
            if (!hit.isNullOrBlank()) {
                cachedUpdates[evt.id] = hit
            } else {
                uncached.add(evt)
            }
        }

        val preferCloudBestModel = quotaSaverMode == QuotaSaverMode.PRO_ACCURACY && !quotaCircuitBreakerTripped
        if (uncached.isNotEmpty() && preferCloudBestModel) {
            onStatus("Translating ${uncached.size} lines into $tgt with Gemini 3.8 Flash...")
            var batchRes = translationAiService.translateBatch(
                events = uncached.take(45),
                sourceLanguage = src,
                targetLanguage = tgt,
                speakers = project.speakers,
                glossary = project.glossary,
                preferLiteModel = false,
            )
            if (batchRes.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(batchRes.exceptionOrNull())) {
                onStatus("Gemini 3.8 Flash credits depleted — switching to Flash-Lite...")
                batchRes = translationAiService.translateBatch(
                    events = uncached.take(45),
                    sourceLanguage = src,
                    targetLanguage = tgt,
                    speakers = project.speakers,
                    glossary = project.glossary,
                    preferLiteModel = true,
                )
            }
            if (batchRes.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(batchRes.exceptionOrNull())) {
                onQuotaTripped()
            }
            batchRes.getOrNull().orEmpty().forEach { line ->
                val srcText = uncached.firstOrNull { it.id == line.id }?.sourceText.orEmpty()
                if (srcText.isNotBlank() && line.naturalTranslation.isNotBlank()) {
                    cacheMap[ProjectStore.buildLineTranslationCacheKey(srcText, src, tgt)] = line.naturalTranslation
                    cachedUpdates[line.id] = line.naturalTranslation
                }
            }
            if (cachedUpdates.isNotEmpty()) {
                projectStore.saveTranslationCache(cacheMap)
            }
        }

        val stillUncached = uncached.filter { it.id !in cachedUpdates }
        if (stillUncached.isNotEmpty() && onDeviceTranslator.canTranslateOffline(src, tgt)) {
            onStatus("Translating ${stillUncached.size} lines offline into $tgt via ML Kit ($0)...")
            val offlineMap = onDeviceTranslator.translateEventsOffline(stillUncached, src, tgt).getOrNull().orEmpty()
            offlineMap.forEach { (id, tr) ->
                val srcText = stillUncached.firstOrNull { it.id == id }?.sourceText.orEmpty()
                if (srcText.isNotBlank() && tr.isNotBlank()) {
                    cacheMap[ProjectStore.buildLineTranslationCacheKey(srcText, src, tgt)] = tr
                    cachedUpdates[id] = tr
                }
            }
            projectStore.saveTranslationCache(cacheMap)
        } else if (stillUncached.isNotEmpty() &&
            quotaSaverMode != QuotaSaverMode.ZERO_CLOUD &&
            !quotaCircuitBreakerTripped &&
            !preferCloudBestModel
        ) {
            onStatus("Translating ${stillUncached.size} lines into $tgt...")
            val batchRes = translationAiService.translateBatch(
                events = stillUncached.take(45),
                sourceLanguage = src,
                targetLanguage = tgt,
                speakers = project.speakers,
                glossary = project.glossary,
                preferLiteModel = true,
            )
            if (batchRes.isFailure && TranslationAiService.isQuotaOrCreditExhaustedError(batchRes.exceptionOrNull())) {
                onQuotaTripped()
            }
            batchRes.getOrNull().orEmpty().forEach { line ->
                val srcText = stillUncached.firstOrNull { it.id == line.id }?.sourceText.orEmpty()
                if (srcText.isNotBlank() && line.naturalTranslation.isNotBlank()) {
                    cacheMap[ProjectStore.buildLineTranslationCacheKey(srcText, src, tgt)] = line.naturalTranslation
                    cachedUpdates[line.id] = line.naturalTranslation
                }
            }
            if (cachedUpdates.isNotEmpty()) {
                projectStore.saveTranslationCache(cacheMap)
            }
        }

        if (cachedUpdates.isEmpty()) return project
        val patched = SubtitleParser.applyReadingPacingGuardrails(
            project.dialogueEvents.map { evt ->
                val tr = cachedUpdates[evt.id] ?: return@map evt
                evt.copy(translatedText = tr)
            }
        )
        return project.copy(dialogueEvents = patched)
    }

    private suspend fun saveCacheEntry(
        project: DialogueLensProject,
        scoutedUpToMs: Long,
        coverageIntervals: List<CoverageInterval> = emptyList(),
    ) {
        val vid = project.videoSource.youtubeVideoId
        val hasVerifiedOrPartial = coverageIntervals.any {
            it.verified || it.status == CoverageStatus.FAILED || it.status == CoverageStatus.PARTIAL_RECOVERY
        }
        if (project.videoSource.kind != VideoSourceKind.YOUTUBE_COMPLIANT ||
            vid.isNullOrBlank() ||
            (!LookaheadWindowPlanner.hasRealSubtitles(project.dialogueEvents) && !hasVerifiedOrPartial)
        ) {
            return
        }
        val key = ProjectStore.canonicalYouTubeCacheKey(vid, project.targetLanguage)
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
                cacheVersion = CachedVideoTranscript.CURRENT_CACHE_VERSION,
                coverageIntervals = coverageIntervals,
            )
        )
    }

    private fun buildProjectFromCues(
        id: String,
        title: String,
        videoUrl: String,
        videoKind: VideoSourceKind,
        youtubeVideoId: String?,
        cues: List<RawSubtitleCue>,
        sourceLanguage: String,
        targetLanguage: String,
        translationMode: TranslationMode,
        subtitleSource: SubtitleSourceKind,
        styleSettings: BalloonStyleSettings,
        glossary: List<GlossaryEntry>,
        characterColorMemory: Map<String, Int>,
        durationHintMs: Long?,
        width: Int = 1920,
        height: Int = 1080,
        channelName: String = "",
        description: String = "",
    ): DialogueLensProject {
        val durationMs = maxOf(
            durationHintMs ?: 0L,
            cues.maxOfOrNull { it.endTimeMs } ?: 0L,
            60_000L,
        )
        val cutType = when (videoKind) {
            VideoSourceKind.YOUTUBE_COMPLIANT -> "EMBEDDED_YOUTUBE"
            VideoSourceKind.WEB_STREAM -> "WEB_STREAM"
            else -> "CUT"
        }
        val shots = listOf(Shot("shot_1", 0L, durationMs, cutType = cutType))
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
        val (speakers, attributed) = attributionEngine.assignSpeakers(
            events = rawEvents,
            shots = shots,
            faceTracks = emptyList(),
            existingSpeakers = defaultSpeakers(),
            rememberedCharacterColors = characterColorMemory,
        )
        return DialogueLensProject(
            id = id,
            title = title,
            createdAtEpochMs = System.currentTimeMillis(),
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translationMode = translationMode,
            subtitleSource = subtitleSource,
            qualityMode = ProcessingQualityMode.BALANCED,
            videoSource = VideoSource(
                uriString = videoUrl,
                displayName = title,
                kind = videoKind,
                durationMs = durationMs,
                width = width,
                height = height,
                youtubeVideoId = youtubeVideoId,
                channelName = channelName,
                description = description,
            ),
            shots = shots,
            speakers = speakers,
            faceTracks = emptyList(),
            dialogueEvents = attributed,
            styleSettings = styleSettings,
            glossary = glossary,
        )
    }

    private fun defaultSpeakers(): List<Speaker> = listOf(
        Speaker("spk_a", "Speaker A", accentIndex = 0),
        Speaker("spk_b", "Speaker B", accentIndex = 1),
    )

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
}
