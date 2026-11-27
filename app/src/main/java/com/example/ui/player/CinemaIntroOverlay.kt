package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Speaker
import com.example.data.subtitle.SubtitleParser
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.playback.PlaybackReadinessSnapshot
import com.example.domain.playback.PlaybackReadinessState
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaGraphite
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.SoftJade
import com.example.ui.theme.SpeakerAccentPalette
import com.example.ui.theme.UnknownSpeakerGray
import com.example.ui.theme.WarmAmber

/**
 * Calm, honest DialogueLens 2.0 Intelligent Buffering Overlay (Section 47 & Section 14).
 * - Never displays fake countdown timers, fake percentages, or internal agent/model jargon.
 * - Promises automatic resumption ONLY when the user's intent is PLAY and no timeout occurred.
 * - Clearly displays the requested seek timestamp when preparing a jumped section.
 * - Offers explicit "Watch Now" with a transparent limitation note, plus structured recovery
 *   actions ("Retry", "Watch without subtitles", "Attach subtitles") on timeout or failure.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CinemaIntroOverlay(
    visible: Boolean,
    remainingMs: Long = 0L,
    totalIntroMs: Long = LookaheadWindowPlanner.INTRO_DURATION_MS,
    scoutStatusText: String = "",
    readyLinesCount: Int = 0,
    openingSpeakersReady: Boolean = false,
    speakers: List<Speaker> = emptyList(),
    bufferedWindows: Int = 0,
    totalWindows: Int = 0,
    requiredOpeningWindows: Int = 1,
    currentTimeMs: Long = 0L,
    readinessSnapshot: PlaybackReadinessSnapshot? = null,
    onRetryBuffering: () -> Unit = {},
    onOpenAttachCaptions: () -> Unit = {},
    onSkipIntro: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMediaHardwareError = readinessSnapshot?.diagnostics?.isMediaHardwareError == true
    val isErrorOrTimeout = readinessSnapshot?.state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR ||
        isMediaHardwareError ||
        readinessSnapshot?.waitTimedOut == true
    val isSeekBuffer = readinessSnapshot?.state == PlaybackReadinessState.BUFFERING_FOR_SEEK
    val targetMs = readinessSnapshot?.targetPositionMs ?: 0L
    val willAutoResume = readinessSnapshot?.willAutoResumeWhenReady ?: true

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(260)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            CinemaObsidian.copy(alpha = 0.88f),
                            CinemaObsidian.copy(alpha = 0.94f),
                        )
                    )
                )
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .testTag("cinema_intro_overlay"),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = CinemaGraphite.copy(alpha = 0.94f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    if (isErrorOrTimeout) WarmAmber.copy(alpha = 0.55f) else CinemaBorder,
                ),
                modifier = Modifier.widthIn(max = 360.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isErrorOrTimeout) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = if (isMediaHardwareError) "Video playback error" else "Dialogue preparation attention",
                            tint = WarmAmber,
                            modifier = Modifier.size(28.dp),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = SoftJade,
                            trackColor = CinemaObsidian,
                            strokeWidth = 2.8.dp,
                        )
                    }

                    Text(
                        text = when {
                            isMediaHardwareError -> "Video failed to load"
                            isErrorOrTimeout -> "Dialogue unavailable here"
                            isSeekBuffer -> "Preparing dialogue at ${SubtitleParser.formatCompactTime(targetMs)}"
                            else -> "Preparing dialogue"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = InkPrimary,
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = when {
                            isMediaHardwareError -> readinessSnapshot?.failureReason?.ifBlank {
                                "The video stream could not be loaded. Check your connection or retry."
                            } ?: "The video stream could not be loaded. Check your connection or retry."
                            isErrorOrTimeout -> readinessSnapshot?.failureReason?.ifBlank {
                                "This section could not be prepared in time."
                            } ?: "This section could not be prepared in time."
                            willAutoResume -> "Playback will resume automatically once dialogue is synced."
                            else -> "Syncing speakers and timing for this moment."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                        textAlign = TextAlign.Center,
                    )

                    val displaySpeakers = speakers.filter {
                        it.isLocked || (it.isIdentityConfirmed && it.hasVisibleCharacterNameAt(currentTimeMs))
                    }.take(4)
                    if (displaySpeakers.isNotEmpty() && !isErrorOrTimeout) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            displaySpeakers.forEach { spk ->
                                val chipTint = SpeakerAccentPalette[spk.accentIndex % SpeakerAccentPalette.size]
                                Surface(
                                    color = CinemaObsidian.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, chipTint.copy(alpha = 0.45f)),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(chipTint)
                                        )
                                        Text(
                                            text = spk.visibleDisplayNameAt(currentTimeMs),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = InkPrimary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isErrorOrTimeout) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                color = SoftJade,
                                shape = RoundedCornerShape(18.dp),
                                onClick = onRetryBuffering,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("buffering_retry_button"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Retry dialogue preparation",
                                        tint = CinemaObsidian,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = "Retry",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = CinemaObsidian,
                                    )
                                }
                            }
                            Surface(
                                color = CinemaObsidian.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, CinemaBorder),
                                onClick = onSkipIntro,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("skip_intro_button"),
                            ) {
                                Text(
                                    text = "Watch without subtitles",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = InkPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                            Surface(
                                color = Color.Transparent,
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, CinemaBorder),
                                onClick = onOpenAttachCaptions,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("buffering_attach_subtitles_button"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Subtitles,
                                        contentDescription = "Attach subtitles",
                                        tint = InkSecondary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "Attach subtitles",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InkSecondary,
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Surface(
                                color = CinemaObsidian.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, SoftJade.copy(alpha = 0.55f)),
                                onClick = onSkipIntro,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("skip_intro_button"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Watch now",
                                        tint = SoftJade,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Watch Now",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = InkPrimary,
                                    )
                                }
                            }
                            Text(
                                text = "Some bubbles may appear after dialogue catches up.",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = InkSecondary.copy(alpha = 0.82f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
