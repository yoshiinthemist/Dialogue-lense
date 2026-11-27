package com.example.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Refresh
import com.example.data.model.ChapterSource
import com.example.data.model.ConfidenceTier
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.GlossaryEntry
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealScope
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.Speaker
import com.example.data.model.VideoSourceKind
import com.example.data.subtitle.SubtitleParser
import com.example.domain.analysis.TranscriptDiagnostics
import com.example.domain.layout.VisualStabilityDiagnosticsSnapshot
import com.example.domain.media.MediaCapabilities
import com.example.domain.media.MediaSourceAdapterRegistry
import com.example.domain.pipeline.ReleasedPlaybackSnapshot
import com.example.domain.pipeline.YouTubeCapabilityReport
import com.example.domain.playback.PlaybackReadinessSnapshot
import com.example.domain.sentinel.ChapterInspectionStatus
import com.example.domain.sentinel.SentinelInspectorSnapshot
import com.example.ui.player.formatBeaconTimestamp
import com.example.ui.player.healBeaconColor
import com.example.ui.player.healBeaconStatusLabel
import com.example.ui.player.healScopeLabel
import com.example.ui.theme.AmethystHeal
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.ConfidenceHighColor
import com.example.ui.theme.ConfidenceLowColor
import com.example.ui.theme.ConfidenceMediumColor
import com.example.ui.theme.LensAmber
import com.example.ui.theme.LensMint
import com.example.ui.theme.LensSky
import com.example.ui.theme.SoftJade
import com.example.ui.theme.SpeakerAccentPalette
import com.example.ui.theme.UnknownSpeakerGray
import com.example.ui.theme.WarmAmber
import kotlin.math.roundToInt

enum class InspectorTab {
    TIMELINE,
    CAST,
    REPAIRS,
    CHAPTERS,
    DIAGNOSTICS,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineSubtitleEditor(
    project: DialogueLensProject,
    currentTimeMs: Long,
    selectedEventId: String?,
    isTranslatingAi: Boolean,
    canUndo: Boolean = false,
    glossaryEntries: List<GlossaryEntry> = project.glossary,
    initialTab: InspectorTab = InspectorTab.TIMELINE,
    onSelectEvent: (String?) -> Unit,
    onSeekTo: (Long) -> Unit,
    onAssignSpeaker: (String, String?) -> Unit,
    onUpdateEvent: (
        eventId: String,
        sourceText: String?,
        translatedText: String?,
        dialogueType: DialogueType?,
        startTimeMs: Long?,
        endTimeMs: Long?,
        lockedByUser: Boolean?,
    ) -> Unit,
    onMergeWithNext: (String) -> Unit,
    onSplitEvent: (String) -> Unit,
    onResetAnchor: (String) -> Unit,
    onRenameSpeaker: (String, String, Int?) -> Unit,
    onTranslateWithAi: () -> Unit,
    onAutoTranscribeAudio: () -> Unit = {},
    onAnalyzeSpeakers: () -> Unit,
    onAddManualLine: (String, String, DialogueType) -> Unit,
    onUndo: () -> Unit = {},
    onShiftTiming: (Long) -> Unit = {},
    onAddGlossaryEntry: (String, String, String) -> Unit = { _, _, _ -> },
    onRemoveGlossaryEntry: (String) -> Unit = {},
    onExportSubtitles: () -> Unit = {},
    onMergeSpeakers: (sourceSpeakerId: String, targetSpeakerId: String) -> Unit = { _, _ -> },
    onMarkHealBeaconHere: (HealScope) -> Unit = {},
    onRetryHealBeacon: (beaconId: String, scope: HealScope) -> Unit = { _, _ -> },
    onUndoHealBeacon: (beaconId: String) -> Unit = {},
    onDismissHealBeacon: (beaconId: String) -> Unit = {},
    onChapterHealAt: (timestampMs: Long) -> Unit = {},
    mediaCapabilities: MediaCapabilities = MediaSourceAdapterRegistry.default.capabilitiesFor(project.videoSource),
    transcriptDiagnostics: TranscriptDiagnostics = TranscriptDiagnostics(),
    visualStabilitySnapshot: VisualStabilityDiagnosticsSnapshot = VisualStabilityDiagnosticsSnapshot(),
    readinessSnapshot: PlaybackReadinessSnapshot = PlaybackReadinessSnapshot(),
    sentinelSnapshot: SentinelInspectorSnapshot = SentinelInspectorSnapshot(),
    releaseSnapshot: ReleasedPlaybackSnapshot = ReleasedPlaybackSnapshot(),
    youtubeCapabilities: YouTubeCapabilityReport = YouTubeCapabilityReport(),
    quotaSaverMode: QuotaSaverMode = QuotaSaverMode.PRO_ACCURACY,
    onSelectQuotaMode: (QuotaSaverMode) -> Unit = {},
    onRetryAiTranscription: () -> Unit = {},
    onAttachSubtitleFile: () -> Unit = {},
    onInspectChapterNow: (String) -> Unit = {},
    onResetStabilityDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) {
        activeTab = initialTab
    }
    var onlyUncertain by remember { mutableStateOf(false) }
    var showToolsDrawer by remember { mutableStateOf(true) }
    var renamingSpeaker by remember { mutableStateOf<Speaker?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showGlossaryDialog by remember { mutableStateOf(false) }

    val isSameLanguage = project.sourceLanguage.equals(project.targetLanguage, ignoreCase = true)

    val sortedEvents = remember(project.dialogueEvents, onlyUncertain) {
        project.dialogueEvents
            .sortedBy { it.startTimeMs }
            .filter { !onlyUncertain || it.confidenceTier != ConfidenceTier.HIGH }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaObsidian),
    ) {
        // Primary Clean Header + Collapsible Wrapping Tools Drawer (never clips horizontally)
        Surface(color = CinemaSurface, tonalElevation = 1.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Primary Studio Tab Switcher: Timeline · Cast · Repairs · Chapters · Diagnostics
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = activeTab == InspectorTab.TIMELINE,
                        onClick = { activeTab = InspectorTab.TIMELINE },
                        leadingIcon = {
                            Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        label = { Text("Timeline (${project.dialogueEvents.size})", fontSize = 12.sp) },
                        modifier = Modifier.testTag("inspector_tab_timeline"),
                    )
                    FilterChip(
                        selected = activeTab == InspectorTab.CAST,
                        onClick = { activeTab = InspectorTab.CAST },
                        leadingIcon = {
                            Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        label = { Text("Cast (${project.speakers.size})", fontSize = 12.sp) },
                        modifier = Modifier.testTag("inspector_tab_cast"),
                    )
                    FilterChip(
                        selected = activeTab == InspectorTab.REPAIRS,
                        onClick = { activeTab = InspectorTab.REPAIRS },
                        leadingIcon = {
                            Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        label = { Text("Repairs (${project.healBeacons.size})", fontSize = 12.sp) },
                        modifier = Modifier.testTag("inspector_tab_repairs"),
                    )
                    FilterChip(
                        selected = activeTab == InspectorTab.CHAPTERS,
                        onClick = { activeTab = InspectorTab.CHAPTERS },
                        leadingIcon = {
                            Icon(Icons.Filled.Bookmarks, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        label = { Text("Chapters (${project.chapters.size})", fontSize = 12.sp) },
                        modifier = Modifier.testTag("inspector_tab_chapters"),
                    )
                    FilterChip(
                        selected = activeTab == InspectorTab.DIAGNOSTICS,
                        onClick = { activeTab = InspectorTab.DIAGNOSTICS },
                        leadingIcon = {
                            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        label = { Text("Diagnostics", fontSize = 12.sp) },
                        modifier = Modifier.testTag("inspector_tab_diagnostics"),
                    )
                }

                if (activeTab == InspectorTab.TIMELINE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Dialogue Cues",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = showToolsDrawer,
                            onClick = { showToolsDrawer = !showToolsDrawer },
                            leadingIcon = {
                                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            label = { Text("Tools", fontSize = 11.5.sp) },
                            modifier = Modifier.testTag("toggle_timeline_tools"),
                        )

                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("add_line_button"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Line", fontSize = 11.5.sp)
                        }

                        Button(
                            onClick = onTranslateWithAi,
                            enabled = !isTranslatingAi,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("ai_translate_button"),
                        ) {
                            if (isTranslatingAi) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = CinemaObsidian,
                                )
                            } else {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = when {
                                    isTranslatingAi -> "Analyzing"
                                    isSameLanguage -> "SDH Enhance"
                                    else -> "Translate"
                                },
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = showToolsDrawer) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (canUndo) {
                                OutlinedButton(
                                    onClick = onUndo,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("undo_edit_button"),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo edit", modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Undo", fontSize = 11.5.sp)
                                }
                            }

                            if (project.videoSource.kind == VideoSourceKind.LOCAL_MP4) {
                                OutlinedButton(
                                    onClick = onAutoTranscribeAudio,
                                    enabled = !isTranslatingAi,
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("auto_transcribe_button"),
                                ) {
                                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Auto ASR", fontSize = 11.5.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = onAnalyzeSpeakers,
                                enabled = !isTranslatingAi,
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("context_audio_match_button"),
                            ) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Context+Audio", fontSize = 11.5.sp)
                            }

                            val activeGlossary = (project.glossary + glossaryEntries).distinctBy { it.term.lowercase() }
                            FilterChip(
                                selected = activeGlossary.isNotEmpty(),
                                onClick = { showGlossaryDialog = true },
                                label = { Text("Glossary (${activeGlossary.size})", fontSize = 11.5.sp) },
                                leadingIcon = {
                                    Icon(Icons.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                modifier = Modifier.testTag("glossary_chip"),
                            )

                            FilterChip(
                                selected = onlyUncertain,
                                onClick = { onlyUncertain = !onlyUncertain },
                                label = {
                                    val count = project.dialogueEvents.count { it.confidenceTier != ConfidenceTier.HIGH }
                                    Text("Review ($count)", fontSize = 11.5.sp)
                                },
                                modifier = Modifier.testTag("filter_uncertain_chip"),
                            )

                            OutlinedButton(
                                onClick = onExportSubtitles,
                                enabled = project.dialogueEvents.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("timeline_export_subtitles_button"),
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Export", fontSize = 11.5.sp)
                            }
                        }

                        // Sync timing bar & Speaker Cast Roster wrapping cleanly
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                color = CinemaElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    val offsetSign = if (project.subtitleOffsetMs >= 0L) "+" else ""
                                    Text(
                                        text = "Sync $offsetSign${String.format(java.util.Locale.US, "%.1f", project.subtitleOffsetMs / 1000f)}s",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                    TextButton(onClick = { onShiftTiming(-500L) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                        Text("-0.5s", fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { onShiftTiming(-100L) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                        Text("-0.1s", fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { onShiftTiming(100L) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                        Text("+0.1s", fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { onShiftTiming(500L) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                        Text("+0.5s", fontSize = 11.sp)
                                    }
                                }
                            }

                            project.speakers.forEach { spk ->
                                val isKnown = spk.isConfirmedCharacter
                                val accent = if (isKnown) {
                                    SpeakerAccentPalette[spk.accentIndex % SpeakerAccentPalette.size]
                                } else {
                                    UnknownSpeakerGray
                                }
                                Surface(
                                    color = CinemaElevated,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { renamingSpeaker = spk }
                                        .testTag("speaker_pill_${spk.id}"),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(accent)
                                        )
                                        Text(
                                            text = spk.displayName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Rename ${spk.displayName}",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (project.castDiscoverySummary.isNotBlank() || project.groundingSources.isNotEmpty()) {
                            Surface(
                                color = CinemaElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, LensSky.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("cast_grounding_attribution_card"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    if (project.castDiscoverySummary.isNotBlank()) {
                                        Text(
                                            text = "Cast Discovery: ${project.castDiscoverySummary}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = LensMint,
                                        )
                                    }
                                    if (project.groundingSources.isNotEmpty()) {
                                        Text(
                                            text = "Sources: ${project.groundingSources.joinToString(" · ")}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        when (activeTab) {
            InspectorTab.CAST -> {
                RunningSpeakerGlossaryTab(
                    project = project,
                    onSeekTo = onSeekTo,
                    onRenameSpeaker = { spk -> renamingSpeaker = spk },
                    onMergeSpeakers = onMergeSpeakers,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            InspectorTab.REPAIRS -> {
                StudioHealBeaconsTab(
                    project = project,
                    currentTimeMs = currentTimeMs,
                    onMarkHere = onMarkHealBeaconHere,
                    onSeekTo = onSeekTo,
                    onRetry = onRetryHealBeacon,
                    onUndo = onUndoHealBeacon,
                    onDismiss = onDismissHealBeacon,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            InspectorTab.CHAPTERS -> {
                StudioChaptersTab(
                    project = project,
                    currentTimeMs = currentTimeMs,
                    sentinelSnapshot = sentinelSnapshot,
                    onSeekTo = onSeekTo,
                    onChapterHeal = onChapterHealAt,
                    onInspectChapterNow = onInspectChapterNow,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            InspectorTab.DIAGNOSTICS -> {
                StudioDiagnosticsTab(
                    project = project,
                    mediaCapabilities = mediaCapabilities,
                    transcriptDiagnostics = transcriptDiagnostics,
                    visualStabilitySnapshot = visualStabilitySnapshot,
                    readinessSnapshot = readinessSnapshot,
                    sentinelSnapshot = sentinelSnapshot,
                    releaseSnapshot = releaseSnapshot,
                    youtubeCapabilities = youtubeCapabilities,
                    quotaSaverMode = quotaSaverMode,
                    onSelectQuotaMode = onSelectQuotaMode,
                    onRetryAi = onRetryAiTranscription,
                    onAttachSubtitles = onAttachSubtitleFile,
                    onInspectChapterNow = onInspectChapterNow,
                    onResetStabilityDiagnostics = onResetStabilityDiagnostics,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            InspectorTab.TIMELINE -> if (sortedEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (onlyUncertain) {
                        "All dialogue lines have high-confidence speaker attribution."
                    } else {
                        "No dialogue cues yet. The AI Scout is listening, or you can attach an SRT/VTT file or tap + Line."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(sortedEvents, key = { _, item -> item.id }) { index, event ->
                    val isActiveAtPlayhead = currentTimeMs in event.startTimeMs..event.endTimeMs
                    val isSelected = selectedEventId == event.id
                    DialogueEventCard(
                        event = event,
                        speakers = project.speakers,
                        isActiveAtPlayhead = isActiveAtPlayhead,
                        isExpanded = isSelected,
                        canMergeNext = index < sortedEvents.lastIndex,
                        onToggleExpand = {
                            onSelectEvent(if (isSelected) null else event.id)
                        },
                        onSeekHere = { onSeekTo(event.startTimeMs + 50L) },
                        onAssignSpeaker = { spkId -> onAssignSpeaker(event.id, spkId) },
                        onUpdateEvent = { src, tr, type, st, en, locked ->
                            onUpdateEvent(event.id, src, tr, type, st, en, locked)
                        },
                        onMergeNext = { onMergeWithNext(event.id) },
                        onSplit = { onSplitEvent(event.id) },
                        onResetAnchor = { onResetAnchor(event.id) },
                    )
                }
            }
        }
        }
    }

    renamingSpeaker?.let { spk ->
        var draft by remember(spk.id) { mutableStateOf(spk.customName.ifBlank { spk.defaultLabel }) }
        var selectedColorIdx by remember(spk.id) { mutableStateOf(spk.accentIndex % SpeakerAccentPalette.size) }
        var userPickedColor by remember(spk.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { renamingSpeaker = null },
            title = { Text("Character Name & Outline Color") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Character name (remembered across scenes)") },
                        singleLine = true,
                    )
                    Text(
                        text = "Outline color (Primary -> Secondary -> Distinct, or pick custom)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SpeakerAccentPalette.forEachIndexed { idx, swatchColor ->
                            val selected = selectedColorIdx == idx
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .then(
                                        if (selected) {
                                            Modifier.border(2.5.dp, Color.White, CircleShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable {
                                        selectedColorIdx = idx
                                        userPickedColor = true
                                    }
                                    .testTag("speaker_color_swatch_$idx")
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onRenameSpeaker(
                        spk.id,
                        draft,
                        if (userPickedColor || spk.colorRemembered) selectedColorIdx else null,
                    )
                    renamingSpeaker = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingSpeaker = null }) { Text("Cancel") }
            },
        )
    }

    if (showAddDialog) {
        var orig by remember { mutableStateOf("") }
        var trans by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(DialogueType.SPOKEN) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Dialogue Event at ${SubtitleParser.formatCompactTime(currentTimeMs)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = orig,
                        onValueChange = { orig = it },
                        label = { Text("Original text") },
                    )
                    OutlinedTextField(
                        value = trans,
                        onValueChange = { trans = it },
                        label = { Text("Translated text") },
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            DialogueType.SPOKEN,
                            DialogueType.OFF_SCREEN_RIGHT,
                            DialogueType.NARRATION,
                            DialogueType.SOUND_DESCRIPTION,
                        ).forEach { dt ->
                            FilterChip(
                                selected = type == dt,
                                onClick = { type = dt },
                                label = { Text(dt.label, fontSize = 11.sp) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = orig.isNotBlank() || trans.isNotBlank(),
                    onClick = {
                        onAddManualLine(orig.ifBlank { trans }, trans, type)
                        showAddDialog = false
                    },
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showGlossaryDialog) {
        var newTerm by remember { mutableStateOf("") }
        var newTranslation by remember { mutableStateOf("") }
        var newNote by remember { mutableStateOf("") }
        val activeGlossary = (project.glossary + glossaryEntries).distinctBy { it.term.lowercase() }
        AlertDialog(
            onDismissRequest = { showGlossaryDialog = false },
            title = { Text("Series Glossary & Translation Memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Terms saved here are injected into every Gemini translation batch for consistent names, honorifics & lore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (activeGlossary.isNotEmpty()) {
                        activeGlossary.take(6).forEach { entry ->
                            Surface(
                                color = CinemaElevated,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${entry.term} → ${entry.translation}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = { onRemoveGlossaryEntry(entry.term) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newTerm,
                        onValueChange = { newTerm = it },
                        label = { Text("Source term (e.g. 先輩 or Sora)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newTranslation,
                        onValueChange = { newTranslation = it },
                        label = { Text("Consistent translation (e.g. Senpai)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newNote,
                        onValueChange = { newNote = it },
                        label = { Text("Context note (optional)") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = newTerm.isNotBlank() && newTranslation.isNotBlank(),
                    onClick = {
                        onAddGlossaryEntry(newTerm, newTranslation, newNote)
                        newTerm = ""
                        newTranslation = ""
                        newNote = ""
                    },
                ) { Text("Add Term") }
            },
            dismissButton = {
                TextButton(onClick = { showGlossaryDialog = false }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun DialogueEventCard(
    event: DialogueEvent,
    speakers: List<Speaker>,
    isActiveAtPlayhead: Boolean,
    isExpanded: Boolean,
    canMergeNext: Boolean,
    onToggleExpand: () -> Unit,
    onSeekHere: () -> Unit,
    onAssignSpeaker: (String?) -> Unit,
    onUpdateEvent: (String?, String?, DialogueType?, Long?, Long?, Boolean?) -> Unit,
    onMergeNext: () -> Unit,
    onSplit: () -> Unit,
    onResetAnchor: () -> Unit,
) {
    val confColor = when (event.confidenceTier) {
        ConfidenceTier.HIGH -> ConfidenceHighColor
        ConfidenceTier.MEDIUM -> ConfidenceMediumColor
        ConfidenceTier.LOW -> ConfidenceLowColor
    }
    val assignedSpeaker = speakers.firstOrNull { it.id == event.speakerId }
    val speakerAccent = assignedSpeaker?.let {
        SpeakerAccentPalette[it.accentIndex % SpeakerAccentPalette.size]
    } ?: MaterialTheme.colorScheme.outline

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActiveAtPlayhead) CinemaElevated else CinemaSurface,
        ),
        border = BorderStroke(
            width = if (isActiveAtPlayhead || isExpanded) 1.5.dp else 1.dp,
            color = when {
                isActiveAtPlayhead -> LensSky
                event.confidenceTier == ConfidenceTier.LOW -> ConfidenceLowColor.copy(alpha = 0.65f)
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .testTag("timeline_card_${event.id}"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top Meta Row: Seek timecode, Shot, Confidence, Lock
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = CinemaObsidian,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onSeekHere),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Jump to timestamp",
                                tint = LensSky,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = "${SubtitleParser.formatCompactTime(event.startTimeMs)}–${SubtitleParser.formatCompactTime(event.endTimeMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = LensSky,
                            )
                        }
                    }

                    Surface(
                        color = confColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "${event.confidenceTier.label} ${(event.speakerConfidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = confColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }

                    if (event.dialogueType != DialogueType.SPOKEN) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = event.dialogueType.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (event.charactersPerSecond > 22f) {
                        Surface(
                            color = LensAmber.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = "${event.charactersPerSecond.roundToInt()} cps",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = LensAmber,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { onUpdateEvent(null, null, null, null, null, !event.lockedByUser) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (event.lockedByUser) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (event.lockedByUser) "Locked" else "Lock speaker",
                        tint = if (event.lockedByUser) LensSky else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Translated + Original text
            Text(
                text = event.translatedText.ifBlank { event.sourceText },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (event.sourceText.isNotBlank() && event.sourceText != event.translatedText) {
                Text(
                    text = event.sourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.attributionSummary.isNotBlank()) {
                Text(
                    text = event.attributionSummary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = LensSky.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.culturalNote.isNotBlank()) {
                Surface(
                    color = LensMint.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, LensMint.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = "Cultural Note: ${event.culturalNote}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = LensMint,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }

            // 1-tap Speaker Assignment Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                speakers.forEach { spk ->
                    val selected = event.speakerId == spk.id
                    val isKnown = spk.isConfirmedCharacter
                    val accent = if (isKnown) {
                        SpeakerAccentPalette[spk.accentIndex % SpeakerAccentPalette.size]
                    } else {
                        UnknownSpeakerGray
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onAssignSpeaker(spk.id) },
                        label = { Text(spk.displayName, fontSize = 12.sp) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) CinemaObsidian else accent)
                            )
                        },
                        modifier = Modifier.testTag("assign_${event.id}_${spk.id}"),
                    )
                }
                if (event.placementMode == PlacementMode.MANUAL_OVERRIDE) {
                    OutlinedButton(
                        onClick = onResetAnchor,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset Pos", fontSize = 11.sp)
                    }
                }
            }

            // Expanded Inline Inspector: Edit text, change DialogueType, adjust timing, Merge/Split
            AnimatedVisibility(visible = isExpanded) {
                var draftTranslated by remember(event.id, event.translatedText) {
                    mutableStateOf(event.translatedText)
                }
                var draftSource by remember(event.id, event.sourceText) {
                    mutableStateOf(event.sourceText)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draftTranslated,
                        onValueChange = { draftTranslated = it },
                        label = { Text("Translated Dialogue") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draftSource,
                        onValueChange = { draftSource = it },
                        label = { Text("Original Text") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateEvent(null, null, null, event.startTimeMs - 250L, null, null)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) { Text("-0.25s Start", fontSize = 11.sp) }
                            OutlinedButton(
                                onClick = {
                                    onUpdateEvent(null, null, null, null, event.endTimeMs + 250L, null)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) { Text("+0.25s End", fontSize = 11.sp) }
                        }
                        Button(
                            onClick = {
                                onUpdateEvent(draftSource, draftTranslated, null, null, null, true)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text("Apply Text", fontSize = 12.sp)
                        }
                    }

                    Text(
                        text = "Dialogue Classification",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DialogueType.entries.forEach { dtype ->
                            FilterChip(
                                selected = event.dialogueType == dtype,
                                onClick = { onUpdateEvent(null, null, dtype, null, null, true) },
                                label = { Text(dtype.label, fontSize = 11.sp) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (canMergeNext) {
                            OutlinedButton(
                                onClick = onMergeNext,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.CallMerge, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Merge Next", fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = onSplit,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Split", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningSpeakerGlossaryTab(
    project: DialogueLensProject,
    onSeekTo: (Long) -> Unit,
    onRenameSpeaker: (Speaker) -> Unit,
    onMergeSpeakers: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var mergingSourceSpeaker by remember { mutableStateOf<Speaker?>(null) }
    val lineCountsBySpeaker = remember(project.dialogueEvents) {
        project.dialogueEvents
            .filter { it.speakerId != null && it.dialogueType != DialogueType.SOUND_DESCRIPTION }
            .groupingBy { it.speakerId!! }
            .eachCount()
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.testTag("running_speaker_glossary_list"),
    ) {
        item(key = "cast_glossary_header") {
            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, LensSky.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Groups,
                            contentDescription = null,
                            tint = LensSky,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            text = "Running Speaker Glossary (${project.speakers.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = "The Scout adds speakers as they appear (dotted gray until identified) and automatically renames their profile once a name is spoken or shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (project.castDiscoverySummary.isNotBlank()) {
                        Text(
                            text = project.castDiscoverySummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = LensMint,
                        )
                    }
                }
            }
        }

        if (project.speakers.isEmpty()) {
            item(key = "cast_glossary_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No speakers detected yet. Start playback or wait for the AI Scout to listen ahead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(project.speakers, key = { _, spk -> spk.id }) { _, spk ->
                val isKnown = spk.isConfirmedCharacter
                val accent = if (isKnown) {
                    SpeakerAccentPalette[spk.accentIndex % SpeakerAccentPalette.size]
                } else {
                    UnknownSpeakerGray
                }
                val lineCount = lineCountsBySpeaker[spk.id] ?: 0
                val namedCount = lineCount.coerceAtMost(10)
                val statusTag = when {
                    spk.isLocked -> "Locked"
                    spk.isConfirmedCharacter && !spk.isRolePlaceholder -> "Visually Confirmed"
                    spk.isConfirmedCharacter && spk.isRolePlaceholder -> "Role Confirmed"
                    else -> "Unconfirmed · Dotted Gray"
                }
                val sideLabel = spk.preferredScreenSideX?.let { x ->
                    when {
                        x < 0.36f -> "Left"
                        x > 0.64f -> "Right"
                        else -> "Center"
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = CinemaSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, accent.copy(alpha = if (isKnown) 0.72f else 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("glossary_speaker_card_${spk.id}"),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(accent.copy(alpha = if (isKnown) 0.22f else 0.12f))
                                    .border(2.dp, accent.copy(alpha = if (isKnown) 1f else 0.65f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(if (isKnown) 12.dp else 8.dp)
                                        .clip(CircleShape)
                                        .background(accent)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = spk.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append(statusTag)
                                        append(" · ")
                                        append("$lineCount lines")
                                        if (lineCount > 0) append(" ($namedCount/10 named)")
                                        if (sideLabel != null) append(" · $sideLabel")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent,
                                )
                            }
                            OutlinedButton(
                                onClick = { onRenameSpeaker(spk) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("glossary_rename_${spk.id}"),
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename ${spk.displayName}", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isKnown) "Edit" else "Name", fontSize = 11.5.sp)
                            }
                        }

                        if (project.speakers.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { mergingSourceSpeaker = spk },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("glossary_merge_${spk.id}"),
                                ) {
                                    Icon(Icons.Filled.MergeType, contentDescription = "Merge ${spk.displayName}", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Merge into another speaker", fontSize = 11.5.sp)
                                }
                            }
                        }

                        if (spk.previousNames.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.History,
                                    contentDescription = "Rename history",
                                    tint = LensMint,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "Renamed from: ${spk.previousNames.joinToString(" → ")} → ${spk.displayName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LensMint,
                                )
                            }
                        }

                        if (spk.visualVoiceNotes.isNotBlank()) {
                            Text(
                                text = "Notes: ${spk.visualVoiceNotes}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (spk.discoveryReason.isNotBlank()) {
                            Text(
                                text = "Clue: ${spk.discoveryReason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = LensSky,
                            )
                        }

                        if (spk.firstAppearedMs != null) {
                            TextButton(
                                onClick = { onSeekTo(spk.firstAppearedMs) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "First appeared @ ${SubtitleParser.formatCompactTime(spk.firstAppearedMs)}",
                                    fontSize = 11.5.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    mergingSourceSpeaker?.let { source ->
        val candidates = project.speakers.filter { it.id != source.id }
        AlertDialog(
            onDismissRequest = { mergingSourceSpeaker = null },
            title = { Text("Merge \"${source.displayName}\" into…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "All lines spoken by ${source.displayName} will be reassigned to the selected speaker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    candidates.forEach { target ->
                        OutlinedButton(
                            onClick = {
                                onMergeSpeakers(source.id, target.id)
                                mergingSourceSpeaker = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("merge_target_${target.id}"),
                        ) {
                            Text(target.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { mergingSourceSpeaker = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StudioHealBeaconsTab(
    project: DialogueLensProject,
    currentTimeMs: Long,
    onMarkHere: (HealScope) -> Unit,
    onSeekTo: (Long) -> Unit,
    onRetry: (String, HealScope) -> Unit,
    onUndo: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.testTag("studio_heal_beacons_list"),
    ) {
        item(key = "heal_beacons_header") {
            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, LensSky.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Heal Beacons · Localized Dialogue Repair",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Mark any moment where dialogue, timing, or speaker attribution looks off. DialogueLens repairs only that window while keeping your edits and the rest of the video untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onMarkHere(HealScope.ADAPTIVE) },
                            modifier = Modifier.testTag("studio_mark_adaptive_heal_button"),
                        ) {
                            Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Heal at ${formatBeaconTimestamp(currentTimeMs)}", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { onMarkHere(HealScope.SCENE) },
                            modifier = Modifier.testTag("studio_mark_scene_heal_button"),
                        ) {
                            Text("Scene Heal", fontSize = 12.sp)
                        }
                        if (project.chapters.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { onMarkHere(HealScope.CHAPTER) },
                                modifier = Modifier.testTag("studio_mark_chapter_heal_button"),
                            ) {
                                Text("Chapter Heal", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        if (project.healBeacons.isEmpty()) {
            item(key = "heal_beacons_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No Heal Beacons placed yet. Pause on Watch and tap \"Heal this moment\" whenever something needs attention.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(
                project.healBeacons.sortedBy { it.timestampMs },
                key = { _, b -> b.id },
            ) { _, beacon ->
                val accent = healBeaconColor(beacon.status)
                val canUndo = beacon.status == HealBeaconStatus.HEALED &&
                    (beacon.preHealSnapshotEvents.isNotEmpty() || beacon.replacedEventIds.isNotEmpty())
                Card(
                    colors = CardDefaults.cardColors(containerColor = CinemaSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_beacon_card_${beacon.id}"),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${formatBeaconTimestamp(beacon.timestampMs)} · ${healBeaconStatusLabel(beacon.status)}",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = accent,
                                )
                                Text(
                                    text = "${healScopeLabel(beacon.scope)} · ${formatBeaconTimestamp(beacon.windowStartMs)}–${formatBeaconTimestamp(beacon.windowEndMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { onDismiss(beacon.id) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "Remove beacon",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        if (beacon.statusNote.isNotBlank()) {
                            Text(
                                text = beacon.statusNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onSeekTo(beacon.timestampMs) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Jump", fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = { onRetry(beacon.id, beacon.scope) },
                                enabled = beacon.status != HealBeaconStatus.HEALING,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (beacon.status == HealBeaconStatus.HEALED) "Re-heal" else "Retry", fontSize = 11.5.sp)
                            }
                            if (canUndo) {
                                TextButton(onClick = { onUndo(beacon.id) }) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Undo", fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioChaptersTab(
    project: DialogueLensProject,
    currentTimeMs: Long,
    sentinelSnapshot: SentinelInspectorSnapshot = SentinelInspectorSnapshot(),
    onSeekTo: (Long) -> Unit,
    onChapterHeal: (Long) -> Unit,
    onInspectChapterNow: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val patrolByChapterId = remember(sentinelSnapshot.chapterPatrolRecords) {
        sentinelSnapshot.chapterPatrolRecords.associateBy { it.chapterId }
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.testTag("studio_chapters_list"),
    ) {
        if (project.chapters.isEmpty()) {
            item(key = "chapters_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Chapters appear automatically from video descriptions or once dialogue sections are detected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(project.chapters, key = { _, ch -> ch.id }) { idx, chapter ->
                val isCurrent = currentTimeMs in chapter.startMs..chapter.endMs
                val sourceLabel = when (chapter.source) {
                    ChapterSource.YOUTUBE_DESCRIPTION -> "Video chapter"
                    ChapterSource.YOUTUBE_METADATA -> "Video chapter"
                    ChapterSource.INFERRED_EDITORIAL -> "Inferred section"
                }
                val patrol = patrolByChapterId[chapter.id]
                val patrolStatusText = patrol?.status?.label
                val patrolStatusColor = patrol?.status?.let { chapterInspectionColor(it) }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) CinemaElevated else CinemaSurface,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        if (isCurrent) 1.5.dp else 1.dp,
                        if (isCurrent) LensSky else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeekTo(chapter.startMs + 50L) }
                        .testTag("studio_chapter_card_${chapter.id}"),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${idx + 1}. ${chapter.title}",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${formatBeaconTimestamp(chapter.startMs)}–${formatBeaconTimestamp(chapter.endMs)} · $sourceLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCurrent) LensSky else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (patrol != null) {
                                    OutlinedButton(
                                        onClick = { onInspectChapterNow(chapter.id) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("inspect_chapter_${chapter.id}"),
                                    ) {
                                        Text("Inspect", fontSize = 11.sp)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onChapterHeal(chapter.startMs + 1000L) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Heal", fontSize = 11.5.sp)
                                }
                            }
                        }
                        if (patrolStatusText != null && patrolStatusColor != null) {
                            Text(
                                text = "Boundary patrol: $patrolStatusText${patrol.summary.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = patrolStatusColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun chapterInspectionColor(status: ChapterInspectionStatus): Color = when (status) {
    ChapterInspectionStatus.INSPECTED_WITHOUT_DEFECTS,
    ChapterInspectionStatus.REPAIR_VERIFIED -> SoftJade
    ChapterInspectionStatus.INSPECTING,
    ChapterInspectionStatus.INSPECTION_QUEUED,
    ChapterInspectionStatus.REPAIR_IN_PROGRESS -> AmethystHeal
    ChapterInspectionStatus.REPAIR_REQUIRED -> WarmAmber
    ChapterInspectionStatus.NOT_INSPECTED -> UnknownSpeakerGray
}

@Composable
private fun StudioDiagnosticsTab(
    project: DialogueLensProject,
    mediaCapabilities: MediaCapabilities,
    transcriptDiagnostics: TranscriptDiagnostics,
    visualStabilitySnapshot: VisualStabilityDiagnosticsSnapshot = VisualStabilityDiagnosticsSnapshot(),
    readinessSnapshot: PlaybackReadinessSnapshot = PlaybackReadinessSnapshot(),
    sentinelSnapshot: SentinelInspectorSnapshot = SentinelInspectorSnapshot(),
    releaseSnapshot: ReleasedPlaybackSnapshot = ReleasedPlaybackSnapshot(),
    youtubeCapabilities: YouTubeCapabilityReport = YouTubeCapabilityReport(),
    quotaSaverMode: QuotaSaverMode,
    onSelectQuotaMode: (QuotaSaverMode) -> Unit,
    onRetryAi: () -> Unit,
    onAttachSubtitles: () -> Unit,
    onInspectChapterNow: (String) -> Unit = {},
    onResetStabilityDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.testTag("studio_diagnostics_list"),
    ) {
        // 0. Two-Scout Release Gate & Reference Provenance (Prepare -> Review -> Release)
        item(key = "two_scout_release_gate_card") {
            Surface(
                color = CinemaSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, LensMint.copy(alpha = 0.40f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("two_scout_release_diagnostics_card"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Two-Scout Release Gate",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Rev #${releaseSnapshot.releaseRevision} · ${releaseSnapshot.releasedIntervals.size} released · ${releaseSnapshot.repairedIntervalCount} repaired · ${releaseSnapshot.fallbackReleaseCount} fallback",
                        style = MaterialTheme.typography.bodySmall,
                        color = LensMint,
                    )
                    DiagnosticMetricRow("Pending drafts", "${releaseSnapshot.pendingDraftCount}")
                    DiagnosticMetricRow("Released intervals", "${releaseSnapshot.releasedIntervals.size} (${releaseSnapshot.repairedIntervalCount} repaired)")
                    DiagnosticMetricRow("Fallback releases", "${releaseSnapshot.fallbackReleaseCount}")
                    DiagnosticMetricRow("Stale writes rejected", "${releaseSnapshot.staleCallbacksRejected}")
                    DiagnosticMetricRow("Visible repairs", "${releaseSnapshot.visibleCorrectionCount}")
                    DiagnosticMetricRow(
                        "Reference track",
                        releaseSnapshot.referenceTrackSummary,
                    )
                    if (project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                        DiagnosticMetricRow(
                            "YouTube limits",
                            "IFrame player only · raw audio: ${if (youtubeCapabilities.hasRawAudioAccess) "Yes" else "No"} · future frames: ${if (youtubeCapabilities.hasFutureFrameAccess) "Yes" else "No"} · captions: ${youtubeCapabilities.captionProvenance}",
                        )
                    }
                }
            }
        }

        // 1. Visual Stability Diagnostics (Engine + Actual Rendered Frames)
        item(key = "visual_stability_diag_card") {
            Surface(
                color = CinemaSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, SoftJade.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("visual_stability_diagnostics_card"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Visual Stability Diagnostics",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(
                            onClick = onResetStabilityDiagnostics,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Reset", fontSize = 11.sp)
                        }
                    }
                    Text(
                        text = "${visualStabilitySnapshot.totalRelocations} relocations · ${visualStabilitySnapshot.suppressedAbaOscillations} bounces blocked · ${visualStabilitySnapshot.renderedOscillations} rendered bounces",
                        style = MaterialTheme.typography.bodySmall,
                        color = SoftJade,
                    )
                    DiagnosticMetricRow("Layout evaluations", "${visualStabilitySnapshot.layoutEvaluations}")
                    DiagnosticMetricRow("Committed relocations", "${visualStabilitySnapshot.totalRelocations}")
                    DiagnosticMetricRow("A-B-A bounces blocked", "${visualStabilitySnapshot.suppressedAbaOscillations}")
                    DiagnosticMetricRow("Rendered frames", "${visualStabilitySnapshot.renderedFrameSamples} (${visualStabilitySnapshot.renderedLargeJumps} jumps · ${visualStabilitySnapshot.renderedOscillations} bounces)")
                    DiagnosticMetricRow("Entry anim replays", "${visualStabilitySnapshot.entryAnimationReplays}")
                    DiagnosticMetricRow("Tail state toggles", "${visualStabilitySnapshot.tailVisibilityToggles}")
                    DiagnosticMetricRow("Tracking dropouts", "${visualStabilitySnapshot.trackingDropouts}")
                    DiagnosticMetricRow("Scene epochs", "${visualStabilitySnapshot.sceneEpochChanges}")
                }
            }
        }

        // 2. Playback Timing & Adaptive Buffering Diagnostics (DialogueLens 2.0)
        item(key = "playback_timing_diag_card") {
            val diag = readinessSnapshot.diagnostics
            Surface(
                color = CinemaSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, LensSky.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playback_timing_diagnostics_card"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Playback Readiness & Adaptive Buffer",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${readinessSnapshot.state.name} · Intent: ${readinessSnapshot.userIntent.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LensSky,
                    )
                    DiagnosticMetricRow("Playback speed", "${diag.playbackSpeed}x")
                    DiagnosticMetricRow(
                        "Released / Verified lead",
                        "${readinessSnapshot.contiguousReleasedLeadMs / 1000L}s released · ${readinessSnapshot.contiguousVerifiedLeadMs / 1000L}s verified (target ${readinessSnapshot.requiredLeadMs / 1000L}s)",
                    )
                    DiagnosticMetricRow(
                        "Measured latency",
                        "${diag.measuredProcessingLatencyMs}ms",
                    )
                    DiagnosticMetricRow(
                        "Seek gen / ack",
                        "#${diag.seekGeneration} · ${if (!diag.isSeekInFlight) "Acknowledged" else "Awaiting"}",
                    )
                    DiagnosticMetricRow("Buffering episodes", "${diag.bufferingEpisodeCount}")
                    DiagnosticMetricRow("Auto-resumes", "${diag.autoResumeCount}")
                    if (readinessSnapshot.isUsingUnverifiedFallback) {
                        DiagnosticMetricRow("Fallback status", "Using unverified fallback ahead")
                    }
                    if (readinessSnapshot.isWatchNowBypassActive) {
                        DiagnosticMetricRow("Watch Now bypass", "Active for current segment")
                    }
                }
            }
        }

        // 3. Sentinel Workforce & YouTube Chapter Patrol Inspector (DialogueLens 2.0)
        item(key = "sentinel_inspector_card") {
            Surface(
                color = CinemaSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AmethystHeal.copy(alpha = 0.40f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sentinel_inspector_card"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Sentinel Workforce & Chapter Patrol",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${sentinelSnapshot.activeWorkerCount}/${sentinelSnapshot.maxAllowedWorkers} workers · ${sentinelSnapshot.queuedItems.size} queued · ${sentinelSnapshot.totalLocalCleanChapterChecks} clean / ${sentinelSnapshot.totalCloudEscalatedChapterChecks} escalated",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmethystHeal,
                    )
                    DiagnosticMetricRow("Active workers", "${sentinelSnapshot.activeWorkerCount} / ${sentinelSnapshot.maxAllowedWorkers}")
                    DiagnosticMetricRow("Queued jobs", "${sentinelSnapshot.queuedItems.size}")
                    DiagnosticMetricRow("Active reservations", "${sentinelSnapshot.activeReservations.size}")
                    DiagnosticMetricRow(
                        "Chapter patrol",
                        when {
                            sentinelSnapshot.chapterPatrolRecords.isEmpty() -> "No YouTube chapters"
                            sentinelSnapshot.patrolSuspendedForPlayback -> "Suspended (${sentinelSnapshot.patrolSuspensionReason.ifBlank { "buffering priority" }})"
                            else -> "Active (${sentinelSnapshot.chapterPatrolRecords.size} chapters)"
                        },
                    )
                    if (sentinelSnapshot.chapterPatrolRecords.isNotEmpty()) {
                        sentinelSnapshot.chapterPatrolRecords.take(6).forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${entry.chapterTitle.take(22)} · ${entry.status.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chapterInspectionColor(entry.status),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { onInspectChapterNow(entry.chapterId) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Text("Inspect", fontSize = 10.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "media_compat_card") {
            Surface(
                color = CinemaSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, LensSky.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Media Source Compatibility · ${mediaCapabilities.providerName}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (mediaCapabilities.limitationSummary.isNotBlank()) {
                        Text(
                            text = mediaCapabilities.limitationSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    mediaCapabilities.capabilityChecklist().forEach { (axis, status) ->
                        val supported = !status.contains("Unavailable", true) &&
                            !status.contains("Restricted", true) &&
                            !status.contains("Limited", true)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = axis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (supported) Icons.Filled.CheckCircle else Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = if (supported) ConfidenceHighColor else ConfidenceMediumColor,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (supported) ConfidenceHighColor else ConfidenceMediumColor,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "transcript_health_card") {
            Surface(
                color = CinemaSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Dialogue Coverage & Processing",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = transcriptDiagnostics.summaryLine(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Lines: ${project.dialogueEvents.size} · Speakers: ${project.speakers.size} · Chapters: ${project.chapters.size} · Beacons: ${project.healBeacons.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LensSky,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        QuotaSaverMode.entries.forEach { mode ->
                            FilterChip(
                                selected = quotaSaverMode == mode,
                                onClick = { onSelectQuotaMode(mode) },
                                label = { Text(mode.label, fontSize = 11.5.sp) },
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                            OutlinedButton(onClick = onRetryAi) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Retry AI Analysis", fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(onClick = onAttachSubtitles) {
                            Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Attach SRT / VTT", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticMetricRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
