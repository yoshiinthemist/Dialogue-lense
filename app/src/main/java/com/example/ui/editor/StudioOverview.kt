package com.example.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealBeaconStatus
import com.example.domain.analysis.CoverageSummary
import com.example.ui.player.formatBeaconTimestamp
import com.example.ui.theme.*

@Composable
internal fun StudioOverview(
    project: DialogueLensProject, position: Long, duration: Long?, lead: Long,
    coverage: CoverageSummary, layoutPasses: Long, onTab: (InspectorTab) -> Unit,
) {
    val marks = project.healBeacons
    val unresolved = marks.count { it.status == HealBeaconStatus.NEEDS_ATTENTION }
    val manualQueued = marks.count { !it.autoTriggered && it.status == HealBeaconStatus.MARKED }
    val active = marks.filter { it.status == HealBeaconStatus.HEALING }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Dialogue coverage", style = MaterialTheme.typography.titleMedium)
        Text("${formatBeaconTimestamp(lead)} continuously prepared from ${formatBeaconTimestamp(position)}")
        if (duration != null && duration > 0) {
            TimelineTracks(project, coverage, position, duration)
        }
        Text("${formatBeaconTimestamp(coverage.verifiedDialogueMs)} verified dialogue · ${formatBeaconTimestamp(coverage.verifiedSilenceMs)} verified silence",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onTab(InspectorTab.TIMELINE) }) { Text("Edit dialogue and timing") }
        HorizontalDivider()
        Text("Healing", style = MaterialTheme.typography.titleMedium)
        Text("$manualQueued manual marks queued · ${active.size} active · $unresolved need review")
        active.forEach {
            Text("${if (it.autoTriggered) "Automatic repair" else "Marked moment"} · ${formatBeaconTimestamp(it.windowStartMs)}–${formatBeaconTimestamp(it.windowEndMs)}",
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onTab(InspectorTab.REPAIRS) }) { Text("Review marks and repairs") }
        HorizontalDivider()
        val chapterBounds = project.chapters.filter { it.startMs > 0 }.associate { it.id to it.startMs }
        val inspected = project.chapterInspections.count { chapterBounds[it.chapterId] == it.boundaryMs }
        Text("Chapter patrol", style = MaterialTheme.typography.titleMedium)
        Text(if (chapterBounds.isEmpty()) "No chapter transitions available" else "$inspected of ${chapterBounds.size} chapter transitions inspected")
        if (chapterBounds.isNotEmpty()) TextButton(onClick = { onTab(InspectorTab.CHAPTERS) }) { Text("Inspect chapters") }
        Text("Spatial layout passes · $layoutPasses", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TimelineTracks(project: DialogueLensProject, coverage: CoverageSummary, position: Long, duration: Long) {
    val tracks = remember(project.dialogueEvents, project.speakers) {
        project.dialogueEvents.groupBy { it.speakerId }.map { (id, cues) ->
            val speaker = project.speakers.firstOrNull { it.id == id }
            Triple(speaker?.displayName ?: "Unassigned", speaker?.accentIndex, cues)
        }
    }
    if (project.audioWaveformPeaks.isNotEmpty()) {
        Text("Audio waveform", style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val peaks = project.audioWaveformPeaks
            val w = size.width / peaks.size
            peaks.forEachIndexed { i, amplitude ->
                val half = amplitude.coerceIn(0f, 1f) * size.height / 2f
                drawLine(InkSecondary, Offset(i * w, size.height / 2 - half), Offset(i * w, size.height / 2 + half), w.coerceAtLeast(1f))
            }
        }
    }
    Text("Verified coverage", style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        fun x(ms: Long) = (ms.toDouble() / duration).toFloat().coerceIn(0f, 1f) * size.width
        fun span(start: Long, end: Long, color: Color) { drawRect(color, Offset(x(start), 0f), Size((x(end) - x(start)).coerceAtLeast(0f), size.height)) }
        drawRect(CinemaSurfaceHigh)
        coverage.bufferedRanges.forEach { span(it.startMs, it.endMs, LensMint) }
        coverage.failedRanges.forEach { span(it.startMs, it.endMs, LensAmber) }
        drawLine(InkPrimary, Offset(x(position), 0f), Offset(x(position), size.height), 2.dp.toPx())
    }
    tracks.forEach { (label, color, cues) ->
        Text(label, style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(CinemaSurfaceHigh)
            val accent = color?.let { SpeakerAccentPalette[Math.floorMod(it, SpeakerAccentPalette.size)] } ?: InkMuted
            cues.forEach { cue ->
                val x = (cue.startTimeMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) * size.width
                val end = (cue.endTimeMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) * size.width
                if (end > x) drawRect(accent, Offset(x, 0f), Size(end - x, size.height))
            }
        }
    }
}
