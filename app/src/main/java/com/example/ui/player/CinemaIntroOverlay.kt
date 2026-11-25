package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LinearProgressIndicator
import com.example.data.model.Speaker
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.LensMint
import com.example.ui.theme.LensSky
import com.example.ui.theme.SpeakerAccentPalette
import com.example.ui.theme.UnknownSpeakerGray
import kotlin.math.ceil

/**
 * Adaptive Smart Pre-Roll slate displayed over Instance 1 (Viewer) while Scout locks onto opening
 * speakers, context, and dialogue cues — releases immediately once opening lines & cast are ready.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CinemaIntroOverlay(
    visible: Boolean,
    remainingMs: Long,
    totalIntroMs: Long = LookaheadWindowPlanner.INTRO_DURATION_MS,
    scoutStatusText: String,
    readyLinesCount: Int,
    openingSpeakersReady: Boolean = false,
    speakers: List<Speaker> = emptyList(),
    bufferedWindows: Int = 0,
    totalWindows: Int = 0,
    requiredOpeningWindows: Int = 1,
    onSkipIntro: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(320)),
        modifier = modifier,
    ) {
        val safeTotal = totalIntroMs.coerceAtLeast(1L)
        val fraction = (remainingMs.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
        val secRemaining = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1)

        val pulseTransition = rememberInfiniteTransition(label = "intro_wave_pulse")
        val wavePhase by pulseTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 720, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "wave_bar_amp",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF101B2E),
                            CinemaObsidian.copy(alpha = 0.96f),
                            Color.Black.copy(alpha = 0.98f),
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .testTag("cinema_intro_overlay"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val badgeTint = if (openingSpeakersReady) LensMint else LensSky
                Surface(
                    color = badgeTint.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, badgeTint.copy(alpha = 0.48f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = badgeTint,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (openingSpeakersReady) {
                                "DIALOGUELENS · CAST & BUBBLES READY"
                            } else {
                                "DIALOGUELENS · SMART PRE-ROLL"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                            ),
                            color = badgeTint,
                        )
                    }
                }

                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 1f - fraction },
                        modifier = Modifier.size(56.dp),
                        color = badgeTint,
                        trackColor = CinemaElevated,
                        strokeWidth = 4.dp,
                    )
                    Text(
                        text = "${secRemaining}s",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = Color.White,
                    )
                }

                // Subtle dual-instance waveform equalizer bar
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(16.dp),
                ) {
                    val heights = listOf(0.45f, 0.85f, 0.62f, 1.0f, 0.55f, 0.9f, 0.48f)
                    heights.forEachIndexed { idx, base ->
                        val mod = if (idx % 2 == 0) wavePhase else (1.25f - wavePhase).coerceIn(0.25f, 1f)
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height((14.dp * base * mod).coerceAtLeast(4.dp))
                                .clip(CircleShape)
                                .background(if (idx % 2 == 0) LensSky else LensMint)
                        )
                    }
                }

                Text(
                    text = scoutStatusText.ifBlank { "Scout is buffering opening 35s context & speakers..." },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.88f),
                )

                if (totalWindows > 0) {
                    val targetOpen = requiredOpeningWindows.coerceAtLeast(1)
                    val openFrac = (bufferedWindows.toFloat() / targetOpen.toFloat()).coerceIn(0f, 1f)
                    val winFrac = (bufferedWindows.toFloat() / totalWindows.coerceAtLeast(1)).coerceIn(0.05f, 1f)
                    if (targetOpen > 1) {
                        Text(
                            text = if (bufferedWindows >= targetOpen) {
                                "Opening lead ready ($bufferedWindows/$targetOpen scenes · $totalWindows chunks total)"
                            } else {
                                "Buffering opening lead: ${minOf(bufferedWindows, targetOpen)}/$targetOpen scenes ($bufferedWindows/$totalWindows chunks)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = LensSky.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    LinearProgressIndicator(
                        progress = {
                            when {
                                openingSpeakersReady -> maxOf(winFrac, 0.25f)
                                targetOpen > 1 -> maxOf(openFrac * 0.85f, (1f - fraction) * 0.65f, 0.08f)
                                else -> (1f - fraction).coerceAtLeast(0.08f)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.66f)
                            .height(4.dp)
                            .clip(CircleShape),
                        color = badgeTint,
                        trackColor = CinemaElevated,
                    )
                }

                val displaySpeakers = speakers.take(5)
                if (displaySpeakers.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(0.92f),
                    ) {
                        displaySpeakers.forEach { spk ->
                            val chipTint = if (spk.isConfirmedCharacter) {
                                SpeakerAccentPalette[spk.accentIndex % SpeakerAccentPalette.size]
                            } else {
                                UnknownSpeakerGray
                            }
                            Surface(
                                color = CinemaElevated.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, chipTint.copy(alpha = 0.55f)),
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
                                        text = spk.displayName,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = Color.White.copy(alpha = 0.92f),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (readyLinesCount > 0) {
                        Surface(
                            color = LensMint.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, LensMint.copy(alpha = 0.5f)),
                        ) {
                            Text(
                                text = if (openingSpeakersReady) {
                                    "$readyLinesCount lines · ready"
                                } else {
                                    "$readyLinesCount lines buffered"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = LensMint,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Surface(
                        color = LensSky,
                        shape = RoundedCornerShape(20.dp),
                        onClick = onSkipIntro,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("skip_intro_button"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Start watching now",
                                tint = CinemaObsidian,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Watch Now",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = CinemaObsidian,
                            )
                        }
                    }
                }
            }
        }
    }
}
