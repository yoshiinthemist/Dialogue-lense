package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealScope
import com.example.ui.state.HumanReadableReadiness
import com.example.ui.state.ReadinessTone
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.ConfidenceHighColor
import com.example.ui.theme.ConfidenceLowColor
import com.example.ui.theme.ConfidenceMediumColor
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.LensAmethyst
import com.example.ui.theme.LensSky

/**
 * Contextual "Heal this moment" pill shown calmly when playback is paused.
 * Follows DialogueLens's guiding principle: "Pause. Mark. Heal. Continue."
 */
@Composable
fun HealThisMomentPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = LensAmethyst.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, LensAmethyst.copy(alpha = 0.55f)),
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("heal_this_moment_button")
            .semantics { contentDescription = "Heal this moment — mark and repair dialogue here" },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (isCompact) 14.dp else 16.dp,
                vertical = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoFixHigh,
                contentDescription = null,
                tint = LensAmethyst,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Heal this moment",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.1.sp,
                ),
                color = InkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Calm, plain-language readiness indicator for the Watch header.
 * Never exposes technical telemetry (Scout segment counts, model names, or percentages).
 */
@Composable
fun ReadinessStatusPill(
    readiness: HumanReadableReadiness,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (readiness.tone) {
        ReadinessTone.READY -> ConfidenceHighColor
        ReadinessTone.OFFLINE -> ConfidenceHighColor
        ReadinessTone.ACTIVE -> LensSky
        ReadinessTone.WARNING -> ConfidenceMediumColor
        ReadinessTone.ATTENTION -> ConfidenceLowColor
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = CinemaElevated,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
            .heightIn(min = 36.dp)
            .testTag("readiness_status_pill")
            .semantics { contentDescription = "${readiness.label}. ${readiness.detail}" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (readiness.isInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = accent,
                )
            } else {
                when (readiness.tone) {
                    ReadinessTone.OFFLINE -> Icon(
                        imageVector = Icons.Rounded.CloudOff,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(13.dp),
                    )
                    ReadinessTone.WARNING, ReadinessTone.ATTENTION -> Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(13.dp),
                    )
                    else -> Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
            }
            Text(
                text = readiness.label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = InkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun healBeaconColor(status: HealBeaconStatus): Color = when (status) {
    HealBeaconStatus.UNDONE -> LensSky
    HealBeaconStatus.MARKED -> LensSky
    HealBeaconStatus.HEALING -> LensSky
    HealBeaconStatus.HEALED -> ConfidenceHighColor
    HealBeaconStatus.NEEDS_ATTENTION -> ConfidenceMediumColor
}

fun healBeaconStatusLabel(status: HealBeaconStatus): String = when (status) {
    HealBeaconStatus.UNDONE -> "Undone"
    HealBeaconStatus.MARKED -> "Marked"
    HealBeaconStatus.HEALING -> "Healing"
    HealBeaconStatus.HEALED -> "Healed"
    HealBeaconStatus.NEEDS_ATTENTION -> "Needs attention"
}

fun healScopeLabel(scope: HealScope): String = when (scope) {
    HealScope.ADAPTIVE -> "Adaptive"
    HealScope.QUICK -> "Quick Heal"
    HealScope.SCENE -> "Scene Heal"
    HealScope.CHAPTER -> "Chapter Heal"
}

fun formatBeaconTimestamp(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0L) {
        "%d:%02d:%02d".format(hrs, mins, secs)
    } else {
        "%02d:%02d".format(mins, secs)
    }
}

/**
 * Contextual Bottom Sheet for inspecting, jumping to, retrying with an expanded scope, undoing,
 * or dismissing a single Heal Beacon.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HealBeaconDetailSheet(
    beacon: HealBeacon,
    hasChapters: Boolean,
    onDismissSheet: () -> Unit,
    onJumpToMoment: (Long) -> Unit,
    onRetry: (String, HealScope) -> Unit,
    onUndo: (String) -> Unit,
    onRemoveBeacon: (String) -> Unit,
) {
    var selectedScope by remember(beacon.id, beacon.scope) { mutableStateOf(beacon.scope) }
    val statusColor = healBeaconColor(beacon.status)
    val canUndo = beacon.status !in setOf(HealBeaconStatus.HEALING, HealBeaconStatus.UNDONE) &&
        (beacon.preHealSnapshotEvents.isNotEmpty() || beacon.replacedEventIds.isNotEmpty() || beacon.preRepairCoverage.isNotEmpty())

    ModalBottomSheet(
        onDismissRequest = onDismissSheet,
        containerColor = CinemaSurface,
        contentColor = InkPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.18f),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when (beacon.status) {
                                HealBeaconStatus.HEALING -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = statusColor,
                                )
                                HealBeaconStatus.HEALED -> Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(20.dp),
                                )
                                HealBeaconStatus.NEEDS_ATTENTION -> Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(20.dp),
                                )
                                HealBeaconStatus.UNDONE -> Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undone")
                                HealBeaconStatus.MARKED -> Icon(
                                    Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Column {
                        Text(
                            text = "Heal Beacon · ${formatBeaconTimestamp(beacon.timestampMs)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkPrimary,
                        )
                        Text(
                            text = "${healBeaconStatusLabel(beacon.status)} · Window ${formatBeaconTimestamp(beacon.windowStartMs)}–${formatBeaconTimestamp(beacon.windowEndMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary,
                        )
                    }
                }
            }

            if (beacon.statusNote.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaElevated,
                    border = BorderStroke(1.dp, CinemaBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = beacon.statusNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkPrimary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // Optional expanded repair scope selector (default remains Adaptive)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Repair scope",
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val scopes = buildList {
                        add(HealScope.ADAPTIVE)
                        add(HealScope.QUICK)
                        add(HealScope.SCENE)
                        if (hasChapters) add(HealScope.CHAPTER)
                    }
                    scopes.forEach { scope ->
                        FilterChip(
                            selected = selectedScope == scope,
                            onClick = { selectedScope = scope },
                            label = { Text(healScopeLabel(scope)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LensSky.copy(alpha = 0.2f),
                                selectedLabelColor = InkPrimary,
                            ),
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }

            HorizontalDivider(color = CinemaBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        onJumpToMoment(beacon.timestampMs)
                        onDismissSheet()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("heal_beacon_jump_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = LensSky),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Jump to moment")
                }

                OutlinedButton(
                    onClick = {
                        onRetry(beacon.id, selectedScope)
                        onDismissSheet()
                    },
                    enabled = beacon.status != HealBeaconStatus.HEALING,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("heal_beacon_retry_button"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (beacon.status == HealBeaconStatus.HEALED) "Re-heal" else "Retry")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canUndo) {
                    TextButton(
                        onClick = {
                            onUndo(beacon.id)
                            onDismissSheet()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("heal_beacon_undo_button"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Undo,
                            contentDescription = null,
                            tint = LensSky,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Undo repair", color = LensSky)
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                TextButton(
                    onClick = {
                        onRemoveBeacon(beacon.id)
                        onDismissSheet()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("heal_beacon_dismiss_button"),
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = InkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Remove beacon", color = InkMuted)
                }
            }
        }
    }
}
