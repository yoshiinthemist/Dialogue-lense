package com.example.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.SpeakerLabelMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalloonStyleSheet(
    settings: BalloonStyleSettings,
    onUpdate: (BalloonStyleSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Balloon Style & Accessibility", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { onUpdate(BalloonStyleSettings()) }) {
                    Text("Reset")
                }
            }

            Text("Bubble Visual Preset", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BubbleVisualStyle.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.visualStyle == preset,
                        onClick = { onUpdate(settings.copy(visualStyle = preset)) },
                        label = { Text(preset.label) },
                        modifier = Modifier.testTag("preset_${preset.name.lowercase()}"),
                    )
                }
            }

            Text(
                text = "Font Scale (${(settings.fontScale * 100).roundToInt()}%)",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = settings.fontScale,
                onValueChange = { onUpdate(settings.copy(fontScale = it)) },
                valueRange = 0.80f..1.45f,
                modifier = Modifier.testTag("font_scale_slider"),
            )

            Text(
                text = "Balloon Opacity (${(settings.balloonOpacity * 100).roundToInt()}%) · 86% tuned for moving video",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = settings.balloonOpacity,
                onValueChange = { onUpdate(settings.copy(balloonOpacity = it)) },
                valueRange = 0.60f..1.0f,
                modifier = Modifier.testTag("opacity_slider"),
            )

            Text(
                text = "Preferred Wrap Lines (${settings.maxLines}) · Full text always shown",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = settings.maxLines.toFloat(),
                onValueChange = { onUpdate(settings.copy(maxLines = it.roundToInt().coerceIn(1, 4))) },
                valueRange = 1f..4f,
                steps = 2,
            )

            HorizontalDivider()

            StyleSwitchRow(
                title = "High-Contrast Accessibility Balloons",
                subtitle = "Pure black background with yellow border & white text",
                checked = settings.highContrastMode,
                onCheckedChange = { onUpdate(settings.copy(highContrastMode = it)) },
                tag = "switch_high_contrast",
            )

            StyleSwitchRow(
                title = "Reduced Motion",
                subtitle = "Disable smoothing animations & cross-fade transitions",
                checked = settings.reducedMotion,
                onCheckedChange = { onUpdate(settings.copy(reducedMotion = it)) },
                tag = "switch_reduced_motion",
            )

            StyleSwitchRow(
                title = "Minimal Tail Pointer",
                subtitle = "Point subtle tail toward speaker when confident",
                checked = settings.showTail,
                onCheckedChange = { onUpdate(settings.copy(showTail = it)) },
                tag = "switch_tail",
            )

            StyleSwitchRow(
                title = "Remembered Character Outlines",
                subtitle = "Distinct character outline hue remembered on first line · thickens on shouts",
                checked = settings.speakerSpecificColors,
                onCheckedChange = { onUpdate(settings.copy(speakerSpecificColors = it)) },
                tag = "switch_speaker_colors",
            )

            Text("Speaker Name Labels", style = MaterialTheme.typography.labelMedium)
            Text(
                text = "Auto shows each character's name on their first 10 bubbles, then switches cleanly to color outlines",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeakerLabelMode.entries.forEach { mode ->
                    val chipText = when (mode) {
                        SpeakerLabelMode.AUTO_FIRST_10 -> "1st 10 (Auto)"
                        SpeakerLabelMode.ALWAYS -> "Always"
                        SpeakerLabelMode.OFF -> "Off"
                    }
                    FilterChip(
                        selected = settings.labelMode == mode,
                        onClick = {
                            onUpdate(
                                settings.copy(
                                    labelMode = mode,
                                    showSpeakerLabels = mode != SpeakerLabelMode.OFF,
                                )
                            )
                        },
                        label = { Text(chipText) },
                        modifier = Modifier.testTag("label_mode_${mode.name.lowercase()}"),
                    )
                }
            }

            StyleSwitchRow(
                title = "Confidence Indicators",
                subtitle = "Show confidence dot & uncertain prompt while in Edit Mode",
                checked = settings.showConfidenceIndicators,
                onCheckedChange = { onUpdate(settings.copy(showConfidenceIndicators = it)) },
                tag = "switch_confidence_dots",
            )

            StyleSwitchRow(
                title = "Vertical Manga CJK (Tategaki)",
                subtitle = "Render Japanese/Korean/Chinese lines in traditional vertical columns",
                checked = settings.tategakiVerticalJapanese,
                onCheckedChange = { onUpdate(settings.copy(tategakiVerticalJapanese = it)) },
                tag = "switch_tategaki_vertical",
            )

            HorizontalDivider()

            Text("Deaf-First Cinema Experience", style = MaterialTheme.typography.labelMedium)

            StyleSwitchRow(
                title = "Lip-Reading Safe Zone Lock",
                subtitle = "Keep lips, jawline & neck unobstructed even in tight close-ups",
                checked = settings.lipReadingSafeZone,
                onCheckedChange = { onUpdate(settings.copy(lipReadingSafeZone = it)) },
                tag = "switch_lip_safe_zone",
            )

            StyleSwitchRow(
                title = "Off-Screen Directional Edge Glow",
                subtitle = "Subtle peripheral frame glow tinted in the off-screen speaker's hue",
                checked = settings.offScreenEdgeGlow,
                onCheckedChange = { onUpdate(settings.copy(offScreenEdgeGlow = it)) },
                tag = "switch_offscreen_glow",
            )

            StyleSwitchRow(
                title = "Beat-Synced Ambient Mood Border",
                subtitle = "Thin outer glow modulated by music cues, sound tension & shouts",
                checked = settings.ambientMoodBorder,
                onCheckedChange = { onUpdate(settings.copy(ambientMoodBorder = it)) },
                tag = "switch_ambient_mood_border",
            )
        }
    }
}

@Composable
private fun StyleSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
    }
}
