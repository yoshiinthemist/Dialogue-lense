package com.example.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.HealBeaconStatus
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.PlaybackPhase
import com.example.ui.state.DialogueLensUiState
import com.example.ui.state.DialogueLensViewModel
import com.example.ui.state.ScoutCaptionState
import com.example.ui.theme.*

@Composable
internal fun WatchControls(
    state: DialogueLensUiState,
    viewModel: DialogueLensViewModel,
    onAppearance: () -> Unit,
    onLanguage: () -> Unit,
    onAttach: () -> Unit,
    onBrowser: () -> Unit,
    onCast: () -> Unit,
    onOpenVideo: () -> Unit,
    onMenuVisible: (Boolean) -> Unit,
) {
    var menu by remember(state.project.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(menu) { onMenuVisible(menu != null) }
    val duration = state.playbackDurationMs ?: 0L
    val seekable = state.mediaCapabilities.supportsSeeking && duration > 0
    var scrub by remember(state.project.id, state.playbackGeneration) { mutableStateOf<Float?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = state.currentTimeMs
    val fraction = scrub ?: (current.toDouble() / duration.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
    val manualMarks = remember(state.project.healBeacons) { state.project.healBeacons.filterNot { it.autoTriggered }.sortedBy { it.timestampMs } }
    Surface(color = CinemaSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (seekable) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(18.dp)) {
                        fun x(time: Long) = (time.toDouble() / duration).toFloat().coerceIn(0f, 1f) * size.width
                        val center = size.height / 2f
                        val h = 3.dp.toPx()
                        drawRect(InkMuted, Offset(0f, center - h / 2), Size(size.width, h))
                        state.coverageSummary.bufferedRanges.forEach {
                            drawRect(LensMint, Offset(x(it.startMs), center - h / 2), Size((x(it.endMs) - x(it.startMs)).coerceAtLeast(0f), h))
                        }
                        state.coverageSummary.failedRanges.forEach {
                            drawRect(LensAmber, Offset(x(it.startMs), center - h / 2), Size((x(it.endMs) - x(it.startMs)).coerceAtLeast(0f), h))
                        }
                        state.project.chapters.forEach { drawLine(InkPrimary, Offset(x(it.startMs), center - h), Offset(x(it.startMs), center + h), 1.dp.toPx()) }
                        manualMarks.forEach {
                            val marker = x(it.timestampMs)
                            val r = 3.dp.toPx()
                            when (it.status) {
                                HealBeaconStatus.HEALED -> drawCircle(LensMint, r, Offset(marker, center + 2 * r))
                                HealBeaconStatus.NEEDS_ATTENTION -> drawRect(LensAmber, Offset(marker - r, center + r), Size(2*r, 2*r))
                                else -> drawPath(Path().apply {
                                    moveTo(marker, center); lineTo(marker-r, center+2*r); lineTo(marker+r, center+2*r); close()
                                }, LensAmethyst)
                            }
                        }
                    }
                    Slider(value = fraction, onValueChange = { scrub = it },
                        onValueChangeFinished = { scrub?.let { viewModel.seekTo((it * duration).toLong()) }; scrub = null },
                        colors = SliderDefaults.colors(thumbColor = InkPrimary, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" }.testTag("timeline_seek_slider"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(formatBeaconTimestamp(if (scrub != null) (fraction * duration).toLong() else current), style = MaterialTheme.typography.labelMedium)
                state.activeChapter?.let { Text(it.title, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium) }
                Text(if (duration > 0) formatBeaconTimestamp(duration) else "Duration unknown", style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                IconButton(onClick = { viewModel.seekTo((current - 10_000L).coerceAtLeast(0)) }, enabled = seekable) { Icon(Icons.Filled.Replay10, "Back ten seconds") }
                FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.testTag("toggle_play_pause_button")) {
                    Icon(if (state.viewerWantsPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.viewerWantsPlay) "Pause" else "Play")
                }
                IconButton(onClick = { viewModel.seekTo((current + 10_000L).coerceAtMost(duration)) }, enabled = seekable) { Icon(Icons.Filled.Forward10, "Forward ten seconds") }
                IconButton(onClick = viewModel::toggleMute) {
                    Icon(if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (state.isMuted) "Unmute" else "Mute")
                }
                Box {
                    IconButton(onClick = { menu = "captions" }) { Icon(Icons.Filled.Subtitles, "Subtitle settings") }
                    DropdownMenu(menu == "captions", { menu = null }) {
                        listOf(CaptionDisplayMode.BALLOONS to "Speech bubbles", CaptionDisplayMode.CLASSIC to "Traditional subtitles", CaptionDisplayMode.OFF to "Subtitles off").forEach { (mode, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.setDisplayMode(mode); menu = null },
                                trailingIcon = { if (mode == state.displayMode.normalizedPrimaryMode()) Icon(Icons.Filled.Check, "Selected") })
                        }
                        DropdownMenuItem({ Text("Subtitle language · ${state.project.targetLanguage}") }, { menu = null; onLanguage() })
                        DropdownMenuItem({ Text("Appearance") }, { menu = null; onAppearance() })
                        DropdownMenuItem({ Text("Attach subtitle file") }, { menu = null; onAttach() })
                    }
                }
                IconButton(onClick = viewModel::toggleFullscreenTheater) {
                    Icon(if (state.isFullscreenTheater) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        if (state.isFullscreenTheater) "Exit fullscreen" else "Fullscreen")
                }
                Box {
                    IconButton(onClick = { menu = "more" }) { Icon(Icons.Filled.MoreVert, "More playback options") }
                    DropdownMenu(menu == "more", { menu = null }) {
                        DropdownMenuItem({ Text("Playback speed · ${state.playbackSpeed}×") }, { menu = null; viewModel.cyclePlaybackSpeed() })
                        DropdownMenuItem({ Text("Characters") }, { menu = null; onCast() })
                        if (manualMarks.isNotEmpty()) DropdownMenuItem({ Text("Marked moments (${manualMarks.size})") }, { menu = "marks" })
                        if (state.project.videoSource.kind == VideoSourceKind.WEB_STREAM) DropdownMenuItem({ Text("Browser controls") }, { menu = null; onBrowser() })
                        if (state.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT) {
                            state.project.videoSource.youtubeVideoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { id ->
                                DropdownMenuItem({ Text("Open in YouTube") }, {
                                    menu = null
                                    val url = android.net.Uri.parse("https://www.youtube.com/watch?v=$id&t=${state.currentTimeMs / 1000}")
                                    try {
                                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url))
                                        viewModel.pausePlayback()
                                    } catch (_: android.content.ActivityNotFoundException) {
                                        android.widget.Toast.makeText(context, "No app can open this link.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                })
                            }
                        }
                        DropdownMenuItem({ Text("Open another video") }, { menu = null; onOpenVideo() })
                    }
                    DropdownMenu(menu == "marks", { menu = null }) {
                        manualMarks.forEach { beacon ->
                            DropdownMenuItem({ Text("${formatBeaconTimestamp(beacon.timestampMs)} · ${healBeaconStatusLabel(beacon.status)}") },
                                { menu = null; viewModel.inspectHealBeacon(beacon.id) })
                        }
                    }
                }
            }
            if (state.playbackPhase == PlaybackPhase.USER_PAUSED && !state.isEditMode && state.quickPickerEventId == null && state.mediaCapabilities.supportsHealBeacon) {
                HealThisMomentPill({ viewModel.markHealBeaconAtCurrentTime() }, isCompact = true)
            }
        }
    }
}

@Composable
internal fun PlaybackNotice(state: DialogueLensUiState, viewModel: DialogueLensViewModel, onAttach: () -> Unit) {
    val phase = state.playbackPhase
    val text = when (phase) {
        PlaybackPhase.WAITING_FOR_SEEK -> "Finding playback position"
        PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING_DIALOGUE -> "Preparing dialogue at ${formatBeaconTimestamp(state.currentTimeMs)}"
        PlaybackPhase.BUFFERING_MEDIA -> "Waiting for video"
        PlaybackPhase.PROCESSING_UNAVAILABLE -> when {
            (state.scoutCaptionState as? ScoutCaptionState.Error)?.isQuotaExhausted == true -> "AI usage limit reached"
            state.seekUnacknowledged -> "The player hasn’t reached this position"
            else -> "Playback preparation stalled"
        }
        PlaybackPhase.LIMITED -> "Playing with available dialogue"
        else -> return
    }
    Surface(color = CinemaSurface, modifier = Modifier.fillMaxWidth().testTag("playback_readiness_notice")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.titleSmall)
            val preparing = phase == PlaybackPhase.PREPARING || phase == PlaybackPhase.BUFFERING_DIALOGUE
            if (preparing && state.resumeTargetMs > 0 && state.viewerWantsPlay) {
                LinearProgressIndicator(progress = { (state.verifiedLeadMs.toFloat() / state.resumeTargetMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("Playback will resume when dialogue is ready.", style = MaterialTheme.typography.bodySmall)
            }
            if (phase == PlaybackPhase.PROCESSING_UNAVAILABLE || preparing) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    if (phase == PlaybackPhase.PROCESSING_UNAVAILABLE) TextButton(onClick = viewModel::retryPlaybackPreparation) { Text("Retry") }
                    if (!state.seekUnacknowledged) TextButton(onClick = viewModel::dismissScoutCatchUpBuffering) { Text("Play with gaps") }
                    TextButton(onClick = onAttach) { Text("Attach subtitles") }
                }
            }
        }
    }
}
