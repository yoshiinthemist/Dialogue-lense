package com.example.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DialogueLensProject
import com.example.data.model.TranslationMode
import com.example.data.subtitle.SubtitleParser
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.LensAmber
import com.example.ui.theme.LensMint
import com.example.ui.theme.LensSky

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitleExportSheet(
    project: DialogueLensProject,
    initialFormat: SubtitleParser.SubtitleExportFormat = SubtitleParser.SubtitleExportFormat.SRT,
    onDismiss: () -> Unit,
    onSaveToDownloads: (
        format: SubtitleParser.SubtitleExportFormat,
        includeSpeakerPrefixes: Boolean,
        translationMode: TranslationMode,
    ) -> Unit,
    onSaveAsDocument: (
        format: SubtitleParser.SubtitleExportFormat,
        includeSpeakerPrefixes: Boolean,
        translationMode: TranslationMode,
    ) -> Unit,
) {
    var selectedFormat by remember(project.id) { mutableStateOf(initialFormat) }
    var selectedTranslationMode by remember(project.id) { mutableStateOf(project.translationMode) }
    var includeSpeakerPrefixes by remember(project.id) { mutableStateOf(true) }

    val hasCues = project.dialogueEvents.isNotEmpty()
    val suggestedFileName = remember(project.title, selectedFormat) {
        SubtitleParser.suggestedExportFilename(project.title, selectedFormat)
    }
    val previewSnippet = remember(
        project,
        selectedFormat,
        includeSpeakerPrefixes,
        selectedTranslationMode,
    ) {
        if (!hasCues) {
            ""
        } else {
            SubtitleParser.exportPreview(
                project = project,
                format = selectedFormat,
                includeSpeakerPrefixes = includeSpeakerPrefixes,
                translationMode = selectedTranslationMode,
                maxCues = 2,
            )
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CinemaObsidian,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = LensMint.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Subtitles,
                        contentDescription = null,
                        tint = LensMint,
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export Translated Subtitles",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "${project.title} · ${project.sourceLanguage} → ${project.targetLanguage} · ${project.dialogueEvents.size} lines",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!hasCues) {
                Surface(
                    color = LensAmber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, LensAmber.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = LensAmber, modifier = Modifier.size(18.dp))
                        Text(
                            text = "No dialogue cues have been generated for this video yet. Let Scout finish or run Translate first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Format Selector (.srt, .vtt, .ass)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "File Format",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SubtitleParser.SubtitleExportFormat.entries.forEach { fmt ->
                        val isSelected = selectedFormat == fmt
                        val subtitle = when (fmt) {
                            SubtitleParser.SubtitleExportFormat.SRT -> "Universal"
                            SubtitleParser.SubtitleExportFormat.VTT -> "Web / HTML5"
                            SubtitleParser.SubtitleExportFormat.ASS -> "Styled Spatial"
                        }
                        Surface(
                            color = if (isSelected) LensSky.copy(alpha = 0.16f) else CinemaSurface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) LensSky else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            onClick = { selectedFormat = fmt },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("subtitle_export_format_${fmt.extension}"),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = fmt.label,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSelected) LensSky else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            // Translation Mode Variant
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Translation Style",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TranslationMode.entries.forEach { mode ->
                        val isSelected = selectedTranslationMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTranslationMode = mode },
                            label = {
                                Text(
                                    text = if (mode == TranslationMode.LEARNING) "Learning (+Pronunciation)" else mode.label,
                                    fontSize = 12.sp,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LensSky.copy(alpha = 0.22f),
                                selectedLabelColor = LensSky,
                            ),
                            modifier = Modifier.testTag("subtitle_export_mode_${mode.name.lowercase()}"),
                        )
                    }
                }
            }

            // Speaker Prefix Toggle
            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = if (selectedFormat == SubtitleParser.SubtitleExportFormat.ASS) {
                                "Speaker Colors & Spatial Coordinates"
                            } else {
                                "Include Speaker Labels ([Name])"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = if (selectedFormat == SubtitleParser.SubtitleExportFormat.ASS) {
                                "Always embedded in .ass styles, screen \\pos(x,y) coordinates, and speaker tracks."
                            } else {
                                "Prefixes confirmed speaker names before each cue line in .srt and .vtt files."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = if (selectedFormat == SubtitleParser.SubtitleExportFormat.ASS) true else includeSpeakerPrefixes,
                        enabled = selectedFormat != SubtitleParser.SubtitleExportFormat.ASS,
                        onCheckedChange = { includeSpeakerPrefixes = it },
                        modifier = Modifier.testTag("subtitle_export_speakers_switch"),
                    )
                }
            }

            // Live Cue Output Preview
            if (previewSnippet.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Preview ($suggestedFileName)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        color = CinemaSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = previewSnippet.lines().take(10).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
            }

            // Local Storage Save Actions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = hasCues,
                    onClick = {
                        onSaveToDownloads(selectedFormat, includeSpeakerPrefixes, selectedTranslationMode)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LensMint,
                        contentColor = CinemaObsidian,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("subtitle_export_save_downloads_button"),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Save ${selectedFormat.label} to Downloads",
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    enabled = hasCues,
                    onClick = {
                        onSaveAsDocument(selectedFormat, includeSpeakerPrefixes, selectedTranslationMode)
                        onDismiss()
                    },
                    border = BorderStroke(1.dp, LensSky.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("subtitle_export_save_as_button"),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = LensSky, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Choose Local Folder (Save As…)",
                        color = LensSky,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
