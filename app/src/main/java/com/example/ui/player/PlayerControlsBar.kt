package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.DialogueLensProject
import com.example.data.model.isBubbleSpeakerUnknown
import com.example.data.model.isNonSpeakerSpecificEvent
import com.example.data.subtitle.SubtitleParser
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.TimeWindowRange
import com.example.ui.state.AnalysisStatus
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.LensAmber
import com.example.ui.theme.LensSky
import com.example.ui.theme.NonSpeakerBubbleWhite
import com.example.ui.theme.SpeakerAccentPalette
import com.example.ui.theme.UnknownSpeakerGray

@Composable
fun PlayerControlsBar(
    project: DialogueLensProject,
    currentTimeMs: Long,
    isPlaying: Boolean,
    isMuted: Boolean,
    playbackSpeed: Float = 1.0f,
    isFullscreenTheater: Boolean = false,
    displayMode: CaptionDisplayMode,
    styleSettings: BalloonStyleSettings,
    isEditMode: Boolean,
    dualInstanceEnabled: Boolean = true,
    scoutedUpToMs: Long = 0L,
    bufferedRanges: List<TimeWindowRange> = emptyList(),
    failedRanges: List<TimeWindowRange> = emptyList(),
    analysisStatus: AnalysisStatus,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onCyclePlaybackSpeed: () -> Unit = {},
    onToggleFullscreenTheater: () -> Unit = {},
    onToggleDualInstance: () -> Unit = {},
    onSelectDisplayMode: (CaptionDisplayMode) -> Unit,
    onCycleTranslationMode: () -> Unit = {},
    onToggleSpeakerLabels: () -> Unit = {},
    onToggleBubbleStyle: () -> Unit = {},
    onToggleEditMode: () -> Unit = {},
    onOpenStyleSheet: () -> Unit = {},
    onOpenCastDrawer: () -> Unit = {},
    onOpenToolsDrawer: () -> Unit = {},
    showDrawerTriggers: Boolean = true,
    onHealThisMoment: () -> Unit = {},
    onInspectHealBeacon: (String) -> Unit = {},
    activeChapterTitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val duration = project.videoSource.durationMs.coerceAtLeast(1000L)
    val unconfirmedCount = project.speakers.count { !it.isConfirmedCharacter }
    val hasActiveToolBadges = isEditMode || playbackSpeed != 1.0f

    Surface(
        color = CinemaSurface,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Progressive Analysis Status / Error strip (shown only when actively processing or on error)
            when (analysisStatus) {
                is AnalysisStatus.Processing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = analysisStatus.phaseLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = LensSky,
                            )
                            Text(
                                text = "${(analysisStatus.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { analysisStatus.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(CircleShape),
                            color = LensSky,
                        )
                    }
                }
                is AnalysisStatus.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = analysisStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                AnalysisStatus.Ready -> Unit
            }

            // 1. Unified Cinema Scrubber (merges speaker rhythm ticks, audio waveform peaks, Scout lookahead buffer, & seek thumb)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .pointerInput(duration, project.healBeacons) {
                            detectTapGestures { offset ->
                                val widthF = size.width.toFloat().coerceAtLeast(1f)
                                val hitRadiusPx = 18.dp.toPx()
                                val tappedBeacon = project.healBeacons.minByOrNull { beacon ->
                                    val bx = (beacon.timestampMs.toFloat() / duration.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) * widthF
                                    kotlin.math.abs(offset.x - bx)
                                }?.takeIf { beacon ->
                                    val bx = (beacon.timestampMs.toFloat() / duration.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) * widthF
                                    kotlin.math.abs(offset.x - bx) <= hitRadiusPx
                                }
                                if (tappedBeacon != null) {
                                    onInspectHealBeacon(tappedBeacon.id)
                                } else {
                                    val frac = (offset.x / widthF).coerceIn(0f, 1f)
                                    onSeekTo((frac * duration).toLong())
                                }
                            }
                        }
                        .pointerInput(duration) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                onSeekTo((frac * duration).toLong())
                            }
                        }
                        .semantics {
                            contentDescription = "Video timeline scrubber"
                        }
                        .testTag("timeline_seek_slider"),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("waveform_timeline_strip"),
                    ) {
                        val durF = duration.toFloat().coerceAtLeast(1f)
                        val trackH = 6.dp.toPx()
                        val trackTop = (size.height - trackH) * 0.5f
                        val corner = CornerRadius(trackH * 0.5f, trackH * 0.5f)

                        // Subtle ambient waveform behind rail
                        val peaks = project.audioWaveformPeaks
                        if (peaks.isNotEmpty()) {
                            val barSlotW = size.width / peaks.size.toFloat()
                            val barW = (barSlotW * 0.52f).coerceAtLeast(1.5f)
                            peaks.forEachIndexed { idx, amp ->
                                val bh = (amp.coerceIn(0.10f, 1f) * (size.height - 6.dp.toPx()))
                                val bx = idx * barSlotW + (barSlotW - barW) * 0.5f
                                val by = (size.height - bh) * 0.5f
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.11f),
                                    topLeft = Offset(bx, by),
                                    size = Size(barW, bh),
                                    cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f),
                                )
                            }
                        }

                        // Base rail
                        drawRoundRect(
                            color = CinemaElevated,
                            topLeft = Offset(0f, trackTop),
                            size = Size(size.width, trackH),
                            cornerRadius = corner,
                        )

                        // Verified Scout coverage segments (never bridge across unverified/failed gaps)
                        val coalescedBuffered = LookaheadWindowPlanner.coalesceBufferedRanges(
                            ranges = bufferedRanges,
                            bridgeGapMs = 1L,
                        )
                        if (coalescedBuffered.isNotEmpty()) {
                            coalescedBuffered.forEach { range ->
                                val sFrac = (range.startMs.toFloat() / durF).coerceIn(0f, 1f)
                                val eFrac = (range.endMs.toFloat() / durF).coerceIn(sFrac, 1f)
                                val segW = (eFrac - sFrac) * size.width
                                if (segW > 2f) {
                                    drawRoundRect(
                                        color = LensSky.copy(alpha = 0.30f),
                                        topLeft = Offset(sFrac * size.width, trackTop),
                                        size = Size(segW, trackH),
                                        cornerRadius = corner,
                                    )
                                }
                            }
                        } else {
                            val scoutFrac = (scoutedUpToMs.toFloat() / durF).coerceIn(0f, 1f)
                            if (scoutFrac > 0.005f) {
                                drawRoundRect(
                                    color = LensSky.copy(alpha = 0.24f),
                                    topLeft = Offset(0f, trackTop),
                                    size = Size(size.width * scoutFrac, trackH),
                                    cornerRadius = corner,
                                )
                            }
                        }

                        // Unresolved / failed gap intervals shown honestly in subtle amber
                        val coalescedFailed = LookaheadWindowPlanner.coalesceBufferedRanges(
                            ranges = failedRanges,
                            bridgeGapMs = 1L,
                        )
                        coalescedFailed.forEach { range ->
                            val sFrac = (range.startMs.toFloat() / durF).coerceIn(0f, 1f)
                            val eFrac = (range.endMs.toFloat() / durF).coerceIn(sFrac, 1f)
                            val segW = ((eFrac - sFrac) * size.width).coerceAtLeast(3.dp.toPx())
                            drawRoundRect(
                                color = LensAmber.copy(alpha = 0.55f),
                                topLeft = Offset(sFrac * size.width, trackTop),
                                size = Size(segW, trackH),
                                cornerRadius = corner,
                            )
                        }

                        // Coalesced speaker-colored dialogue ticks along bottom edge of rail
                        val tickH = 2.5.dp.toPx()
                        val tickCorner = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                        val tickY = trackTop + trackH + 3.dp.toPx()
                        val sortedEvents = project.dialogueEvents.sortedBy { it.startTimeMs }
                        var curSpanStart = -1L
                        var curSpanEnd = -1L
                        var curSpanColor: Color? = null
                        fun flushTickSpan() {
                            val col = curSpanColor ?: return
                            if (curSpanEnd <= curSpanStart) return
                            val sx = (curSpanStart / durF) * size.width
                            val ex = (curSpanEnd / durF) * size.width
                            drawRoundRect(
                                color = col,
                                topLeft = Offset(sx, tickY),
                                size = Size((ex - sx).coerceAtLeast(2.5.dp.toPx()), tickH),
                                cornerRadius = tickCorner,
                            )
                        }
                        sortedEvents.forEach { evt ->
                            val spk = project.speakers.firstOrNull { it.id == evt.speakerId }
                            val barColor = when {
                                isNonSpeakerSpecificEvent(evt) -> NonSpeakerBubbleWhite.copy(alpha = 0.65f)
                                isBubbleSpeakerUnknown(evt, spk) -> UnknownSpeakerGray.copy(alpha = 0.72f)
                                spk != null -> SpeakerAccentPalette[spk.accentIndex % SpeakerAccentPalette.size].copy(alpha = 0.90f)
                                else -> UnknownSpeakerGray.copy(alpha = 0.72f)
                            }
                            if (curSpanColor == barColor && evt.startTimeMs <= curSpanEnd + 380L) {
                                curSpanEnd = maxOf(curSpanEnd, evt.endTimeMs)
                            } else {
                                flushTickSpan()
                                curSpanStart = evt.startTimeMs
                                curSpanEnd = evt.endTimeMs
                                curSpanColor = barColor
                            }
                        }
                        flushTickSpan()

                        // Subtle chapter boundary notches
                        if (project.chapters.size >= 2) {
                            project.chapters.drop(1).forEach { chapter ->
                                val cx = ((chapter.startMs.toFloat() / durF).coerceIn(0f, 1f)) * size.width
                                drawLine(
                                    color = Color.White.copy(alpha = 0.42f),
                                    start = Offset(cx, trackTop - 2.dp.toPx()),
                                    end = Offset(cx, trackTop + trackH + 2.dp.toPx()),
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                            }
                        }

                        // Played progress bar
                        val headFrac = (currentTimeMs.toFloat() / durF).coerceIn(0f, 1f)
                        val headX = headFrac * size.width
                        if (headX > 0f) {
                            drawRoundRect(
                                color = LensSky,
                                topLeft = Offset(0f, trackTop),
                                size = Size(headX, trackH),
                                cornerRadius = corner,
                            )
                        }

                        // Heal Beacon diamond markers (clearly distinct from chapter & speaker ticks)
                        project.healBeacons.forEach { beacon ->
                            val bx = ((beacon.timestampMs.toFloat() / durF).coerceIn(0f, 1f)) * size.width
                            val by = trackTop - 6.dp.toPx()
                            val r = 4.2.dp.toPx()
                            val bColor = healBeaconColor(beacon.status)
                            val diamond = Path().apply {
                                moveTo(bx, by - r)
                                lineTo(bx + r, by)
                                lineTo(bx, by + r)
                                lineTo(bx - r, by)
                                close()
                            }
                            drawPath(path = diamond, color = bColor)
                            drawPath(
                                path = diamond,
                                color = Color.White.copy(alpha = 0.85f),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }

                        // Crisp luminous scrub thumb
                        val thumbR = 7.dp.toPx()
                        val clampedThumbX = headX.coerceIn(thumbR, (size.width - thumbR).coerceAtLeast(thumbR))
                        val centerY = trackTop + trackH * 0.5f
                        drawCircle(
                            color = LensSky.copy(alpha = 0.28f),
                            radius = thumbR * 1.45f,
                            center = Offset(clampedThumbX, centerY),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = thumbR,
                            center = Offset(clampedThumbX, centerY),
                        )
                        drawCircle(
                            color = LensSky,
                            radius = thumbR * 0.56f,
                            center = Offset(clampedThumbX, centerY),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = SubtitleParser.formatCompactTime(currentTimeMs),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isEditMode) {
                        Text(
                            text = "Edit Mode · Drag bubbles to reposition",
                            style = MaterialTheme.typography.labelSmall,
                            color = LensSky,
                        )
                    } else if (!activeChapterTitle.isNullOrBlank()) {
                        Text(
                            text = activeChapterTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else if (playbackSpeed != 1.0f) {
                        Text(
                            text = "${playbackSpeed}x speed",
                            style = MaterialTheme.typography.labelSmall,
                            color = LensSky,
                        )
                    }
                    Text(
                        text = SubtitleParser.formatCompactTime(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 2. Spacious 5-Control Primary Transport Row (generous 48dp+ touch targets, balanced symmetry)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("mute_button"),
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute audio" else "Mute audio",
                        tint = if (isMuted) LensAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = { onSeekTo((currentTimeMs - 5_000L).coerceAtLeast(0L)) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("rewind_5s_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay5,
                        contentDescription = "Back 5 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    color = LensSky,
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onTogglePlayPause)
                        .testTag("play_pause_button"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = CinemaObsidian,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                IconButton(
                    onClick = { onSeekTo((currentTimeMs + 5_000L).coerceAtMost(duration)) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("forward_5s_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward5,
                        contentDescription = "Forward 5 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                IconButton(
                    onClick = onToggleFullscreenTheater,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("fullscreen_theater_button"),
                ) {
                    Icon(
                        imageVector = if (isFullscreenTheater) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreenTheater) "Exit theater fullscreen" else "Theater fullscreen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 2b. Contextual Heal Beacon action — appears calmly whenever paused ("Pause. Mark. Heal. Continue.")
            if (!isPlaying && !isEditMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HealThisMomentPill(onClick = onHealThisMoment)
                }
            }

            // 3. Calm Essential Watching Dock: 3-Icon Caption Switcher + Cast + Tools Drawer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val activePrimaryMode = displayMode.normalizedPrimaryMode()
                Surface(
                    color = CinemaElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.testTag("caption_mode_icon_group"),
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CaptionDisplayMode.primaryModes.forEach { mode ->
                            val isSelected = activePrimaryMode == mode
                            val a11yLabel = when (mode) {
                                CaptionDisplayMode.BALLOONS -> "Speech bubbles mode"
                                CaptionDisplayMode.CLASSIC -> "Standard subtitle bar mode"
                                CaptionDisplayMode.OFF -> "No subtitles"
                                CaptionDisplayMode.BOTH -> "Speech bubbles mode"
                            }
                            val selectedBg = when (mode) {
                                CaptionDisplayMode.BALLOONS -> LensSky
                                CaptionDisplayMode.CLASSIC -> LensAmber
                                CaptionDisplayMode.OFF -> Color(0xFFEF4444)
                                CaptionDisplayMode.BOTH -> LensSky
                            }
                            val fgColor = if (isSelected) {
                                if (mode == CaptionDisplayMode.OFF) Color.White else CinemaObsidian
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(
                                color = if (isSelected) selectedBg else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                                onClick = { onSelectDisplayMode(mode) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .semantics {
                                        contentDescription = a11yLabel
                                        role = Role.RadioButton
                                        selected = isSelected
                                    }
                                    .testTag("display_mode_${mode.name.lowercase()}"),
                            ) {
                                Box(
                                    modifier = Modifier.size(width = 48.dp, height = 36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when (mode) {
                                        CaptionDisplayMode.BALLOONS,
                                        CaptionDisplayMode.BOTH -> SpeechBubblesModeIcon(
                                            tint = fgColor,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        CaptionDisplayMode.CLASSIC -> ScreenSubtitleBarIcon(
                                            tint = fgColor,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        CaptionDisplayMode.OFF -> BigXOffIcon(
                                            tint = fgColor,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showDrawerTriggers) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = CinemaElevated,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                if (unconfirmedCount > 0) LensAmber.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            onClick = onOpenCastDrawer,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("open_cast_glossary_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Groups,
                                    contentDescription = "Open speaker cast glossary",
                                    tint = LensSky,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "Cast (${project.speakers.size})",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (unconfirmedCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(LensAmber)
                                    )
                                }
                            }
                        }

                        Surface(
                            color = if (hasActiveToolBadges) LensSky.copy(alpha = 0.16f) else CinemaElevated,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                if (hasActiveToolBadges) LensSky.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            onClick = onOpenToolsDrawer,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("open_tools_drawer_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = "Open tools, lines, and style drawer",
                                    tint = if (hasActiveToolBadges) LensSky else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "Tools",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                } else {
                    // Expanded tablet layout: keep quick Tools & Style trigger right beside the caption switcher
                    Surface(
                        color = if (hasActiveToolBadges) LensSky.copy(alpha = 0.16f) else CinemaElevated,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            if (hasActiveToolBadges) LensSky.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        onClick = onOpenToolsDrawer,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("open_tools_drawer_button"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "Open tools and style settings",
                                tint = if (hasActiveToolBadges) LensSky else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Tools & Style",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Unmistakable dual comic speech bubbles icon for Spatial Bubbles mode.
 */
@Composable
private fun SpeechBubblesModeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Back/right secondary speech bubble
        drawRoundRect(
            color = tint.copy(alpha = 0.72f),
            topLeft = Offset(w * 0.36f, h * 0.12f),
            size = Size(w * 0.56f, h * 0.42f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
            style = stroke,
        )

        // Front/left primary filled speech bubble with tail
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.06f, h * 0.34f),
            size = Size(w * 0.62f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.15f, w * 0.15f),
        )
        val tail = Path().apply {
            moveTo(w * 0.22f, h * 0.74f)
            lineTo(w * 0.14f, h * 0.94f)
            lineTo(w * 0.38f, h * 0.76f)
            close()
        }
        drawPath(path = tail, color = tint)
    }
}

/**
 * Unmistakable screen/monitor outline with a bold bottom subtitle bar for Standard Subtitles mode.
 */
@Composable
private fun ScreenSubtitleBarIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val frameStroke = Stroke(width = w * 0.095f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Widescreen monitor/TV frame
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.06f, h * 0.14f),
            size = Size(w * 0.88f, h * 0.68f),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = frameStroke,
        )
        // Bold bottom subtitle bar inside the screen
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.57f),
            size = Size(w * 0.64f, h * 0.15f),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        )
        // Subtle shorter second caption line above it
        drawRoundRect(
            color = tint.copy(alpha = 0.65f),
            topLeft = Offset(w * 0.28f, h * 0.42f),
            size = Size(w * 0.44f, h * 0.09f),
            cornerRadius = CornerRadius(w * 0.045f, w * 0.045f),
        )
    }
}

/**
 * Unmistakable big bold X icon for No Subtitles (Off).
 */
@Composable
private fun BigXOffIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val pad = w * 0.16f
        val sw = w * 0.16f
        drawLine(
            color = tint,
            start = Offset(pad, pad),
            end = Offset(w - pad, h - pad),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w - pad, pad),
            end = Offset(pad, h - pad),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}
