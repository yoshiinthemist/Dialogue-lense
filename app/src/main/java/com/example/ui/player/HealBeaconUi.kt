package com.example.ui.player

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.data.model.HealProblemHint
import com.example.data.model.HealScope
import com.example.ui.state.HumanReadableReadiness
import com.example.ui.state.ReadinessTone
import com.example.ui.theme.AmethystHeal
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
import com.example.ui.theme.SoftJade
import com.example.ui.theme.WarmAmber

/**
 * Contextual "Heal this moment" pill shown calmly when playback is intentionally paused.
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
        color = AmethystHeal.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, AmethystHeal.copy(alpha = 0.58f)),
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
                tint = AmethystHeal,
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
    HealBeaconStatus.MARKED -> AmethystHeal
    HealBeaconStatus.HEALING -> AmethystHeal
    HealBeaconStatus.HEALED -> SoftJade
    HealBeaconStatus.NEEDS_ATTENTION -> WarmAmber
    HealBeaconStatus.UNDONE -> InkSecondary
}

fun healBeaconStatusLabel(status: HealBeaconStatus): String = when (status) {
    HealBeaconStatus.MARKED -> "Marked"
    HealBeaconStatus.HEALING -> "Healing"
    HealBeaconStatus.HEALED -> "Healed"
    HealBeaconStatus.NEEDS_ATTENTION -> "Needs attention"
    HealBeaconStatus.UNDONE -> "Undone"
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

@Composable
fun RepairComparison(beacon: HealBeacon) {
    val changes = beacon.postHealSnapshotEvents
    if (changes.isEmpty()) return
    var index by remember(beacon.id, changes) { mutableStateOf(-1) }
    TextButton(onClick = { index = if (index >= 0) -1 else 0 }) {
        Text(if (index >= 0) "Close comparison" else "Review changes (${changes.size})")
    }
    val after = changes.getOrNull(index) ?: return
    val before = beacon.preHealSnapshotEvents.firstOrNull { it.id == after.id }
    val newText = after.translatedText.ifBlank { after.sourceText }
    val oldText = before?.translatedText?.ifBlank { before.sourceText }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Line ${index + 1} of ${changes.size} · ${formatBeaconTimestamp(after.startTimeMs)}",
            style = MaterialTheme.typography.labelMedium)
        when {
            before == null -> Text("Added dialogue")
            oldText != newText -> {
                Text("Before", style = MaterialTheme.typography.labelSmall)
                Text(oldText.orEmpty())
                Text("After", style = MaterialTheme.typography.labelSmall)
            }
            else -> {
                val details = buildList {
                    if (before.startTimeMs != after.startTimeMs || before.endTimeMs != after.endTimeMs) add("Timing adjusted")
                    if (before.speakerId != after.speakerId) add("Speaker reassigned")
                    if (before.anchorPosition != after.anchorPosition || before.placementMode != after.placementMode) add("Position adjusted")
                }
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(newText)
        if (changes.size > 1) Row {
            TextButton(enabled = index > 0, onClick = { index-- }) { Text("Previous line") }
            TextButton(enabled = index < changes.lastIndex, onClick = { index++ }) { Text("Next line") }
        }
    }
}

/**
 * Contextual Bottom Sheet for inspecting, jumping to, retrying with an expanded scope or problem hint,
 * previewing before/after repair changes, undoing, or dismissing a single Heal Beacon.
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
    onUpdateProblemHint: (String, HealProblemHint) -> Unit = { _, _ -> },
) {
    var selectedScope by remember(beacon.id, beacon.scope) { mutableStateOf(beacon.scope) }
    var selectedHint by remember(beacon.id, beacon.problemHint) { mutableStateOf(beacon.problemHint) }
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
                .verticalScroll(rememberScrollState())
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
                                HealBeaconStatus.MARKED -> Icon(
                                    Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(20.dp),
                                )
                                HealBeaconStatus.UNDONE -> Icon(
                                    Icons.AutoMirrored.Rounded.Undo,
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

            RepairComparison(beacon)

            if (beacon.statusNote.isNotBlank() || beacon.changeSummary.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaElevated,
                    border = BorderStroke(1.dp, CinemaBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (beacon.statusNote.isNotBlank()) {
                            Text(
                                text = beacon.statusNote,
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkPrimary,
                            )
                        }
                        if (beacon.changeSummary.isNotBlank() && beacon.changeSummary != beacon.statusNote) {
                            Text(
                                text = beacon.changeSummary,
                                style = MaterialTheme.typography.labelMedium,
                                color = SoftJade,
                            )
                        }
                        if (beacon.postHealSnapshotEvents.isEmpty() && (beacon.beforeSampleText.isNotBlank() || beacon.afterSampleText.isNotBlank())) {
                            HorizontalDivider(color = CinemaBorder)
                            if (beacon.beforeSampleText.isNotBlank()) {
                                Text(
                                    text = "Before: ${beacon.beforeSampleText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (beacon.afterSampleText.isNotBlank()) {
                                Text(
                                    text = "After: ${beacon.afterSampleText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // Optional problem hint selector (helps narrow what to inspect without blocking quick healing)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "What looked off? (Optional)",
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HealProblemHint.entries.forEach { hint ->
                        FilterChip(
                            selected = selectedHint == hint,
                            onClick = {
                                selectedHint = hint
                                onUpdateProblemHint(beacon.id, hint)
                            },
                            label = { Text(hint.label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmethystHeal.copy(alpha = 0.22f),
                                selectedLabelColor = InkPrimary,
                            ),
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .testTag("heal_hint_${hint.name.lowercase()}"),
                        )
                    }
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
                                selectedContainerColor = AmethystHeal.copy(alpha = 0.22f),
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
                    colors = ButtonDefaults.buttonColors(containerColor = SoftJade, contentColor = CinemaSurface),
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
                            tint = AmethystHeal,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Undo repair", color = AmethystHeal)
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
