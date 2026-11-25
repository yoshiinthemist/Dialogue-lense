package com.example.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmethystHeal
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaGraphite
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.SoftJade
import com.example.ui.theme.WarmAmber

private data class OnboardingStep(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val accent: Color,
)

/**
 * Calm, skippable 3-step first-time onboarding overlay for DialogueLens 2.0.
 * Explains spatial speaker bubbles, Heal Beacons, and instant customization without clutter.
 */
@Composable
fun FirstTimeOnboardingOverlay(
    onComplete: () -> Unit,
    onTryDemoScene: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = remember {
        listOf(
            OnboardingStep(
                title = "Dialogue that belongs to the speaker",
                body = "Open a video link or file. Speech bubbles appear beside the speaker when their position is known.",
                icon = Icons.Rounded.ChatBubbleOutline,
                accent = SoftJade,
            ),
            OnboardingStep(
                title = "Pause. Mark. Heal. Continue.",
                body = "Pause and tap “Heal this moment” to mark a line for review. You can press Play immediately.",
                icon = Icons.Rounded.AutoFixHigh,
                accent = AmethystHeal,
            ),
            OnboardingStep(
                title = "Tuned for effortless reading",
                body = "Open Subtitle settings to try Reading Comfort. Studio has the timeline, characters, and repair history.",
                icon = Icons.Rounded.PlayCircleOutline,
                accent = WarmAmber,
            ),
        )
    }

    var currentStep by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val step = steps[currentStep]
    val isLast = currentStep == steps.lastIndex

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaObsidian.copy(alpha = 0.90f))
            .padding(20.dp)
            .testTag("first_time_onboarding_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = CinemaGraphite,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, CinemaBorder),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Welcome to DialogueLens",
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSecondary,
                    )
                    TextButton(
                        onClick = onComplete,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("onboarding_skip_button"),
                    ) {
                        Text("Skip", color = InkSecondary)
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = step.accent.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, step.accent.copy(alpha = 0.45f)),
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = step.icon,
                            contentDescription = null,
                            tint = step.accent,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = InkPrimary,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = step.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                    textAlign = TextAlign.Center,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    steps.forEachIndexed { idx, s ->
                        Box(
                            modifier = Modifier
                                .size(if (idx == currentStep) 10.dp else 7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx == currentStep) s.accent else InkSecondary.copy(alpha = 0.35f)
                                ),
                        )
                    }
                }

                if (isLast) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                onComplete()
                                onTryDemoScene()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoftJade,
                                contentColor = CinemaObsidian,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("onboarding_try_demo_button"),
                        ) {
                            Text("Try Interactive Demo Scene", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onComplete,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("onboarding_get_started_button"),
                        ) {
                            Text("Choose a video", color = InkPrimary)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = { currentStep = (currentStep + 1).coerceAtMost(steps.lastIndex) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoftJade,
                                contentColor = CinemaObsidian,
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("onboarding_next_button"),
                        ) {
                            Spacer(Modifier.width(8.dp))
                            Text("Next", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }
        }
    }
}
