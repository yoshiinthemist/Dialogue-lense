package com.example.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DialogueLensProject
import com.example.data.model.ProcessingQualityMode
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSourceKind
import com.example.domain.media.MediaProviderKind
import com.example.domain.media.MediaSourceAdapterRegistry
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.LensAmber
import com.example.ui.theme.LensMint
import com.example.ui.theme.LensSky

internal val SOURCE_LANGUAGES = listOf(
    "Auto-Detect", "English", "Japanese", "Korean", "Spanish",
    "French", "German", "Mandarin", "Italian", "Portuguese", "Hindi", "Arabic",
)
internal val TARGET_LANGUAGES = listOf(
    "English", "Spanish", "French", "Japanese", "Korean",
    "German", "Mandarin", "Italian", "Portuguese", "Hindi", "Arabic",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaImportSheet(
    onDismiss: () -> Unit,
    onStartLocalProject: (
        videoUri: Uri,
        videoTitle: String,
        subtitleUri: Uri?,
        subtitleName: String,
        sourceLang: String,
        targetLang: String,
        translationMode: TranslationMode,
        subtitleSource: SubtitleSourceKind,
        qualityMode: ProcessingQualityMode,
    ) -> Unit,
    onStartYouTubeProject: (
        youtubeUrl: String,
        youtubeVideoId: String,
        subtitleUri: Uri?,
        subtitleName: String,
        sourceLang: String,
        targetLang: String,
        translationMode: TranslationMode,
        pastedSubtitleText: String,
    ) -> Unit,
    onStartWebStreamProject: (
        webUrl: String,
        subtitleUri: Uri?,
        subtitleName: String,
        sourceLang: String,
        targetLang: String,
        translationMode: TranslationMode,
        pastedSubtitleText: String,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onAttachSubtitleOnly: (Uri, String) -> Unit,
    onLoadDemoScenario: () -> Unit,
) {
    val context = LocalContext.current
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf("") }
    var selectedSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var selectedSubtitleName by remember { mutableStateOf("") }
    var youtubeUrlInput by remember { mutableStateOf("") }
    var pastedTranscriptInput by remember { mutableStateOf("") }
    var sourceLanguage by remember { mutableStateOf("Auto-Detect") }
    var targetLanguage by remember { mutableStateOf("English") }
    var translationMode by remember { mutableStateOf(TranslationMode.NATURAL) }
    var subtitleSource by remember { mutableStateOf(SubtitleSourceKind.UPLOADED_FILE) }
    var qualityMode by remember { mutableStateOf(ProcessingQualityMode.BALANCED) }

    val videoPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            selectedVideoName = uri.lastPathSegment?.substringAfterLast('/') ?: "Local MP4"
        }
    }

    val videoDocPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            selectedVideoName = uri.lastPathSegment?.substringAfterLast('/') ?: "Local MP4"
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedSubtitleUri = uri
            selectedSubtitleName = uri.lastPathSegment?.substringAfterLast('/') ?: "subtitles.srt"
        }
    }

    val resolvedMedia = remember(youtubeUrlInput) {
        MediaSourceAdapterRegistry.default.resolveInput(youtubeUrlInput)
    }
    val extractedYoutubeId = resolvedMedia?.youtubeVideoId
        ?: remember(youtubeUrlInput) { extractYouTubeId(youtubeUrlInput) }
    val normalizedWebUrl = remember(youtubeUrlInput, resolvedMedia) {
        resolvedMedia?.normalizedUri?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: normalizeWebStreamUrl(youtubeUrlInput)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Load Video & Subtitles", style = MaterialTheme.typography.headlineSmall)

            // Video Pickers (Zero-permission Photo Picker + SAF)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        videoPhotoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pick_video_gallery_button"),
                ) {
                    Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pick Video")
                }

                OutlinedButton(
                    onClick = { videoDocPicker.launch(arrayOf("video/*")) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pick_video_file_button"),
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse MP4")
                }
            }

            if (selectedVideoUri != null) {
                Text(
                    text = "Selected Video: $selectedVideoName",
                    style = MaterialTheme.typography.labelMedium,
                    color = LensMint,
                )
            }

            // Subtitle File Picker (SRT / VTT / ASS)
            OutlinedButton(
                onClick = {
                    subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "*/*"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pick_subtitle_button"),
            ) {
                Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (selectedSubtitleName.isBlank()) {
                        "Attach Subtitle File (.srt, .vtt, .ass) · Optional"
                    } else {
                        "Subtitle: $selectedSubtitleName"
                    }
                )
            }
            if (selectedVideoUri != null && selectedSubtitleName.isBlank()) {
                Text(
                    text = "No subtitle file selected: DialogueLens will check embedded tracks or auto-transcribe speech audio via Gemini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LensSky,
                )
            }

            if (selectedVideoUri == null && selectedSubtitleUri != null) {
                OutlinedButton(
                    onClick = {
                        onAttachSubtitleOnly(selectedSubtitleUri!!, selectedSubtitleName)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Apply Subtitle File to Current Scene")
                }
            }

            HorizontalDivider()

            // Universal Web Stream & YouTube URL Section + Chrome/YouTube Share tip
            Text("Web Stream or YouTube URL (Chrome / Streaming)", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Tip: Tap Share → DialogueLens from Chrome or the YouTube app to launch any link straight here with spatial bubbles.",
                style = MaterialTheme.typography.bodySmall,
                color = LensSky,
            )
            OutlinedTextField(
                value = youtubeUrlInput,
                onValueChange = { youtubeUrlInput = it },
                label = { Text("Paste any YouTube or web video URL (https://...)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("youtube_url_input"),
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = extractedYoutubeId == "3AFJtcP2cp0",
                    onClick = {
                        youtubeUrlInput = "https://youtu.be/3AFJtcP2cp0?is=Fd7HSQvVvb3qyMVC"
                    },
                    label = { Text("Load Test Video (youtu.be/3AFJtcP2cp0)", fontSize = 12.sp) },
                    modifier = Modifier.testTag("test_youtube_chip"),
                )
            }

            if (youtubeUrlInput.isNotBlank()) {
                val isDrmBlocked = resolvedMedia?.providerKind == MediaProviderKind.UNSUPPORTED_DRM_PLATFORM
                val isDirectStream = resolvedMedia?.providerKind == MediaProviderKind.DIRECT_MEDIA_URL
                val isGenericWeb = extractedYoutubeId == null && normalizedWebUrl != null && !isDrmBlocked
                val accentColor = when {
                    isDrmBlocked -> MaterialTheme.colorScheme.error
                    isDirectStream -> LensMint
                    isGenericWeb -> LensSky
                    else -> LensAmber
                }
                Surface(
                    color = CinemaElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        accentColor.copy(alpha = 0.55f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = when {
                                    isDrmBlocked -> "${resolvedMedia?.providerName ?: "DRM Service"} · Not Supported"
                                    extractedYoutubeId != null -> "YouTube Video ID: $extractedYoutubeId"
                                    isDirectStream -> "Direct Media Stream (Native Player)"
                                    normalizedWebUrl != null -> "HTML5 Web Embed Ready"
                                    else -> "Enter a valid YouTube, direct media, or https:// web link"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = accentColor,
                            )
                        }
                        Text(
                            text = when {
                                isDrmBlocked ->
                                    resolvedMedia?.userNotice?.ifBlank {
                                        "This service uses DRM protection. DialogueLens respects DRM and cannot read or play protected streams."
                                    } ?: "DRM-protected streams are not supported."
                                extractedYoutubeId != null ->
                                    "Plays via YouTube's official embedded IFrame player. Attach SRT/VTT or paste transcript below for spatial balloons (creators can restrict embedding on specific uploads)."
                                isDirectStream ->
                                    resolvedMedia?.userNotice?.ifBlank {
                                        "Plays directly in the native player with full playback sync, audio transcription, live face tracking, and Heal Beacon support."
                                    } ?: "Direct media stream ready."
                                normalizedWebUrl != null ->
                                    resolvedMedia?.userNotice?.ifBlank {
                                        "Opens in Web Cinema: captures available HTML5 captions and overlays speaker-aware bubbles. Cross-origin sites may limit audio extraction."
                                    } ?: "HTML5 web playback ready."
                                else ->
                                    "Paste a full http:// or https:// web video link, direct .mp4/.m3u8 URL, YouTube URL, or pick a local video."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!isDrmBlocked) {
                            OutlinedTextField(
                                value = pastedTranscriptInput,
                                onValueChange = { pastedTranscriptInput = it },
                                label = { Text("Paste SRT/VTT or plain transcript lines (optional)") },
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("youtube_paste_transcript_input"),
                            )
                        }
                        if (!isDrmBlocked && extractedYoutubeId != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        onStartYouTubeProject(
                                            youtubeUrlInput,
                                            extractedYoutubeId,
                                            selectedSubtitleUri,
                                            selectedSubtitleName,
                                            sourceLanguage,
                                            targetLanguage,
                                            translationMode,
                                            pastedTranscriptInput,
                                        )
                                        onDismiss()
                                    },
                                    modifier = Modifier.testTag("load_youtube_embed_button"),
                                ) {
                                    Text("Play in App", fontSize = 12.sp)
                                }
                                TextButton(
                                    onClick = {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://www.youtube.com/watch?v=$extractedYoutubeId")
                                        )
                                        context.startActivity(intent)
                                    }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("YouTube App", fontSize = 12.sp)
                                }
                            }
                        } else if (!isDrmBlocked && normalizedWebUrl != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        onStartWebStreamProject(
                                            normalizedWebUrl,
                                            selectedSubtitleUri,
                                            selectedSubtitleName,
                                            sourceLanguage,
                                            targetLanguage,
                                            translationMode,
                                            pastedTranscriptInput,
                                        )
                                        onDismiss()
                                    },
                                    modifier = Modifier.testTag("load_web_stream_button"),
                                ) {
                                    Text(
                                        text = if (isDirectStream) "Play Direct Stream" else "Open in Web Cinema",
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Language & Processing Pipeline Options
            Text("Spoken Language", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SOURCE_LANGUAGES.forEach { lang ->
                    FilterChip(
                        selected = sourceLanguage == lang,
                        onClick = { sourceLanguage = lang },
                        label = { Text(lang, fontSize = 12.sp) },
                    )
                }
            }

            Text("Target Translation Language", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TARGET_LANGUAGES.forEach { lang ->
                    FilterChip(
                        selected = targetLanguage == lang,
                        onClick = { targetLanguage = lang },
                        label = { Text(lang, fontSize = 12.sp) },
                    )
                }
            }

            Text("Translation Style & Analysis Quality", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TranslationMode.entries.forEach { tm ->
                    FilterChip(
                        selected = translationMode == tm,
                        onClick = { translationMode = tm },
                        label = { Text(tm.label, fontSize = 12.sp) },
                    )
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProcessingQualityMode.entries.forEach { qm ->
                    FilterChip(
                        selected = qualityMode == qm,
                        onClick = { qualityMode = qm },
                        label = { Text(qm.label, fontSize = 12.sp) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onLoadDemoScenario()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Demo Scene")
                }

                Button(
                    enabled = selectedVideoUri != null,
                    onClick = {
                        onStartLocalProject(
                            selectedVideoUri!!,
                            selectedVideoName,
                            selectedSubtitleUri,
                            selectedSubtitleName,
                            sourceLanguage,
                            targetLanguage,
                            translationMode,
                            subtitleSource,
                            qualityMode,
                        )
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("analyze_video_button"),
                ) {
                    Text("Analyze & Play")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectAndPrivacySheet(
    currentProject: DialogueLensProject,
    savedProjects: List<DialogueLensProject>,
    exportProgress: Float? = null,
    onDismiss: () -> Unit,
    onExportProjectJson: () -> Unit,
    onExportSrt: () -> Unit,
    onExportVtt: () -> Unit = {},
    onExportAss: () -> Unit = {},
    onOpenSubtitleExportOptions: () -> Unit = {},
    onExportBurnedMp4: () -> Unit = {},
    onImportProjectJson: () -> Unit,
    onOpenSavedProject: (DialogueLensProject) -> Unit,
    onDeleteCurrentProject: () -> Unit,
    onWipeAllProjects: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.PrivacyTip, contentDescription = null, tint = LensSky)
                Text("Project Files & Privacy", style = MaterialTheme.typography.headlineSmall)
            }

            // Clear Privacy Model Disclosure
            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = LensMint, modifier = Modifier.size(16.dp))
                        Text("Where Processing Happens", style = MaterialTheme.typography.titleSmall, color = LensMint)
                    }
                    Text(
                        text = "• On-Device Vision: Video decoding, frame sampling, shot-cut detection, and ML Kit face/mouth tracking run locally on your Android device. Raw video frames are never uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "• Cloud Translation: Only subtitle dialogue text lines are sent via Firebase AI Logic (Gemini) for context-aware translation and conversational turn hints.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "• Ephemeral Local Identities: Speaker labels (Speaker A–D) and face tracks stay inside this project file only. No persistent biometric identity database is ever stored, and uploaded media is never used for model training.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Export / Portability
            Text("Export & Burn-In", style = MaterialTheme.typography.titleSmall)
            if (exportProgress != null) {
                LinearProgressIndicator(
                    progress = { exportProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = LensSky,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onExportSrt,
                    enabled = currentProject.dialogueEvents.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_srt_button"),
                ) {
                    Text("Export .srt", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onExportVtt,
                    enabled = currentProject.dialogueEvents.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_vtt_button"),
                ) {
                    Text("Export .vtt", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onExportAss,
                    enabled = currentProject.dialogueEvents.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_ass_button"),
                ) {
                    Text("Spatial .ass", fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenSubtitleExportOptions()
                    },
                    enabled = currentProject.dialogueEvents.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_subtitle_export_sheet_button"),
                ) {
                    Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Save to Downloads…", fontSize = 12.sp)
                }

                Button(
                    onClick = onExportBurnedMp4,
                    enabled = exportProgress == null && currentProject.videoSource.kind == VideoSourceKind.LOCAL_MP4,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_burned_mp4_button"),
                ) {
                    Text(
                        text = if (exportProgress != null) "Burning ${(exportProgress * 100).toInt()}%" else "Burn .mp4 Video",
                        fontSize = 12.sp,
                    )
                }
            }

            Button(
                onClick = onExportProjectJson,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("export_json_button"),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Export Full Project (.dlens.json)", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onImportProjectJson,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import .dlens.json Project")
            }

            if (savedProjects.isNotEmpty()) {
                HorizontalDivider()
                Text("Saved Local Projects (${savedProjects.size})", style = MaterialTheme.typography.titleSmall)
                savedProjects.forEach { proj ->
                    Surface(
                        color = CinemaElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onOpenSavedProject(proj)
                            onDismiss()
                        },
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(proj.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${proj.sourceLanguage} → ${proj.targetLanguage} · ${proj.dialogueEvents.size} lines",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onDeleteCurrentProject()
                        onDismiss()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("delete_project_button"),
                ) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete Project", fontSize = 12.sp)
                }

                TextButton(
                    onClick = {
                        onWipeAllProjects()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Wipe All Local Data", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    }
}

internal fun extractYouTubeId(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val patterns = listOf(
        Regex("""(?:v=|/)([0-9A-Za-z_-]{11})(?:[?&/#]|$)"""),
        Regex("""youtu\.be/([0-9A-Za-z_-]{11})"""),
        Regex("""shorts/([0-9A-Za-z_-]{11})"""),
        Regex("""live/([0-9A-Za-z_-]{11})"""),
    )
    return patterns.firstNotNullOfOrNull { it.find(trimmed)?.groupValues?.getOrNull(1) }
}

internal fun normalizeWebStreamUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val candidate = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        trimmed
    } else if (trimmed.contains('.') && !trimmed.contains(' ')) {
        "https://$trimmed"
    } else {
        return null
    }
    return if (Regex("""^https?://[A-Za-z0-9.-]+\.[A-Za-z]{2,}.*$""").matches(candidate)) {
        candidate
    } else {
        null
    }
}
