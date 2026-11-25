package com.example.ui.setup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DialogueLensProject
import com.example.data.model.ProcessingQualityMode
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.subtitle.SubtitleParser
import com.example.ui.state.SubtitlePrepState
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.LensAmber
import com.example.ui.theme.LensMint
import com.example.ui.theme.LensSky

@Composable
fun StartLauncherScreen(
    savedProjects: List<DialogueLensProject>,
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
    ) -> Unit,
    onOpenSavedProject: (DialogueLensProject) -> Unit,
    onLoadDemoScenario: () -> Unit,
    subtitlePrepState: SubtitlePrepState = SubtitlePrepState.Idle,
    selectedPrepFormat: SubtitleParser.SubtitleExportFormat = SubtitleParser.SubtitleExportFormat.SRT,
    onSelectPrepFormat: (SubtitleParser.SubtitleExportFormat) -> Unit = {},
    onGenerateSubtitlesFromUrl: (
        rawUrl: String,
        youtubeVideoId: String?,
        pastedSubtitleText: String,
        subtitleUri: Uri?,
        subtitleName: String,
        sourceLang: String,
        targetLang: String,
        translationMode: TranslationMode,
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onGenerateSubtitlesFromLocal: (
        videoUri: Uri,
        videoTitle: String,
        subtitleUri: Uri?,
        subtitleName: String,
        sourceLang: String,
        targetLang: String,
        translationMode: TranslationMode,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onCancelSubtitlePrep: () -> Unit = {},
    onClearSubtitlePrep: () -> Unit = {},
    onDownloadPreparedSubtitles: () -> Unit = {},
    onLaunchPreparedProject: () -> Unit = {},
    onExportSavedProjectSubtitles: (DialogueLensProject) -> Unit = {},
) {
    var urlInput by remember { mutableStateOf("") }
    var pastedTranscriptInput by remember { mutableStateOf("") }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf("") }
    var selectedSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var selectedSubtitleName by remember { mutableStateOf("") }
    var sourceLanguage by remember { mutableStateOf("Auto-Detect") }
    var targetLanguage by remember { mutableStateOf("English") }
    var translationMode by remember { mutableStateOf(TranslationMode.NATURAL) }

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

    val extractedYoutubeId = remember(urlInput) { extractYouTubeId(urlInput) }
    val normalizedWebUrl = remember(urlInput) { normalizeWebStreamUrl(urlInput) }
    val isUrlReady = extractedYoutubeId != null || normalizedWebUrl != null

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = CinemaObsidian,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0C1526),
                            CinemaObsidian,
                            Color(0xFF070A10),
                        )
                    )
                )
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = LensSky.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, LensSky.copy(alpha = 0.45f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ChatBubble,
                                contentDescription = null,
                                tint = LensSky,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "DIALOGUELENS · v${com.example.BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.7.sp,
                                ),
                                color = LensSky,
                            )
                        }
                    }

                    Text(
                        text = "Watch any video with speaker speech bubbles",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Paste a video link or pick a file to start muted background scouting and live spatial speech bubbles.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Card 1: Paste Video URL
                Surface(
                    color = CinemaSurface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, LensSky.copy(alpha = 0.35f)),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = null,
                                tint = LensSky,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Paste Video URL",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("YouTube or web stream URL (https://...)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_url_input"),
                        )

                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = extractedYoutubeId == "3AFJtcP2cp0",
                                onClick = {
                                    urlInput = "https://youtu.be/3AFJtcP2cp0?is=Fd7HSQvVvb3qyMVC"
                                },
                                label = { Text("Load Test Video", fontSize = 12.sp) },
                                modifier = Modifier.testTag("start_test_youtube_chip"),
                            )
                        }

                        if (urlInput.isNotBlank()) {
                            Text(
                                text = when {
                                    extractedYoutubeId != null ->
                                        "YouTube ready ($extractedYoutubeId) · Scout will fetch captions, metadata & cast names"
                                    normalizedWebUrl != null ->
                                        "Web Cinema ready · Captures HTML5 captions, audio & page cast context"
                                    else ->
                                        "Enter a valid http:// or https:// video link"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isUrlReady) LensMint else LensAmber,
                            )
                        }

                        OutlinedTextField(
                            value = pastedTranscriptInput,
                            onValueChange = { pastedTranscriptInput = it },
                            label = { Text("Optional SRT / VTT or plain transcript") },
                            maxLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_pasted_transcript_input"),
                        )

                        Button(
                            enabled = isUrlReady,
                            onClick = {
                                if (extractedYoutubeId != null) {
                                    onStartYouTubeProject(
                                        urlInput,
                                        extractedYoutubeId,
                                        selectedSubtitleUri,
                                        selectedSubtitleName,
                                        sourceLanguage,
                                        targetLanguage,
                                        translationMode,
                                        pastedTranscriptInput,
                                    )
                                } else if (normalizedWebUrl != null) {
                                    onStartWebStreamProject(
                                        normalizedWebUrl,
                                        selectedSubtitleUri,
                                        selectedSubtitleName,
                                        sourceLanguage,
                                        targetLanguage,
                                        translationMode,
                                        pastedTranscriptInput,
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LensSky,
                                contentColor = CinemaObsidian,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("start_submit_url_button"),
                        ) {
                            Text(
                                text = "Submit & Open Stream",
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        // Pre-generate subtitle file (.srt / .vtt / .ass) without opening the player
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Prep File:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SubtitleParser.SubtitleExportFormat.entries.forEach { fmt ->
                                FilterChip(
                                    selected = selectedPrepFormat == fmt,
                                    onClick = { onSelectPrepFormat(fmt) },
                                    label = { Text(fmt.label, fontSize = 12.sp) },
                                    modifier = Modifier.testTag("prep_format_${fmt.extension}"),
                                )
                            }
                        }

                        OutlinedButton(
                            enabled = (extractedYoutubeId != null || pastedTranscriptInput.isNotBlank()) &&
                                subtitlePrepState !is SubtitlePrepState.Working,
                            onClick = {
                                onGenerateSubtitlesFromUrl(
                                    urlInput,
                                    extractedYoutubeId,
                                    pastedTranscriptInput,
                                    selectedSubtitleUri,
                                    selectedSubtitleName,
                                    sourceLanguage,
                                    targetLanguage,
                                    translationMode,
                                )
                            },
                            border = BorderStroke(
                                1.dp,
                                if (extractedYoutubeId != null || pastedTranscriptInput.isNotBlank()) {
                                    LensMint.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("start_generate_url_subtitles_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Generate subtitle file",
                                tint = LensMint,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Generate & Download ${selectedPrepFormat.label}",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = subtitlePrepState != SubtitlePrepState.Idle) {
                    SubtitlePrepCard(
                        prepState = subtitlePrepState,
                        selectedFormat = selectedPrepFormat,
                        onSelectFormat = onSelectPrepFormat,
                        onCancel = onCancelSubtitlePrep,
                        onClear = onClearSubtitlePrep,
                        onDownload = onDownloadPreparedSubtitles,
                        onWatchNow = onLaunchPreparedProject,
                    )
                }

                // Card 2: Upload Local Video
                Surface(
                    color = CinemaSurface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Movie,
                                contentDescription = null,
                                tint = LensMint,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Upload Local Video",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    videoPhotoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("start_pick_video_button"),
                            ) {
                                Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pick Video")
                            }

                            OutlinedButton(
                                onClick = { videoDocPicker.launch(arrayOf("video/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("start_browse_mp4_button"),
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "*/*"))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("start_pick_subtitle_button"),
                        ) {
                            Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (selectedSubtitleName.isBlank()) {
                                    "Attach Subtitle File (.srt, .vtt, .ass) · Optional"
                                } else {
                                    "Subtitle: $selectedSubtitleName"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                                    SubtitleSourceKind.UPLOADED_FILE,
                                    ProcessingQualityMode.BALANCED,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("start_submit_local_button"),
                        ) {
                            Text(
                                text = "Submit Uploaded Video",
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        OutlinedButton(
                            enabled = selectedVideoUri != null && subtitlePrepState !is SubtitlePrepState.Working,
                            onClick = {
                                val uri = selectedVideoUri ?: return@OutlinedButton
                                onGenerateSubtitlesFromLocal(
                                    uri,
                                    selectedVideoName,
                                    selectedSubtitleUri,
                                    selectedSubtitleName,
                                    sourceLanguage,
                                    targetLanguage,
                                    translationMode,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("start_generate_local_subtitles_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Generate local video subtitles",
                                tint = LensMint,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Pre-Generate ${selectedPrepFormat.label} for Local Video",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Compact Spoken & Target Language Strip
                Surface(
                    color = CinemaElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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

                        Text("Target Bubble Language", style = MaterialTheme.typography.labelMedium)
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
                    }
                }

                // Quick Actions: Interactive Demo & Recent Projects
                OutlinedButton(
                    onClick = onLoadDemoScenario,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("start_demo_button"),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try Interactive Demo")
                }

                if (savedProjects.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = LensSky,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Recent Projects",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        savedProjects.take(5).forEach { proj ->
                            Surface(
                                color = CinemaSurface,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                onClick = { onOpenSavedProject(proj) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("start_saved_proj_${proj.id}"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = proj.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${proj.sourceLanguage} → ${proj.targetLanguage} · ${proj.dialogueEvents.size} lines",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        if (proj.dialogueEvents.isNotEmpty()) {
                                            IconButton(
                                                onClick = { onExportSavedProjectSubtitles(proj) },
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .testTag("start_export_proj_${proj.id}"),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Download,
                                                    contentDescription = "Export translations for ${proj.title}",
                                                    tint = LensMint,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Filled.PlayCircle,
                                            contentDescription = "Open ${proj.title}",
                                            tint = LensSky,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitlePrepCard(
    prepState: SubtitlePrepState,
    selectedFormat: SubtitleParser.SubtitleExportFormat,
    onSelectFormat: (SubtitleParser.SubtitleExportFormat) -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onDownload: () -> Unit,
    onWatchNow: () -> Unit,
) {
    when (prepState) {
        SubtitlePrepState.Idle -> Unit
        is SubtitlePrepState.Working -> {
            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LensSky.copy(alpha = 0.55f)),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subtitle_prep_working_card"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Generating ${selectedFormat.label} · ${prepState.sourceLabel}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = LensSky,
                            )
                            Text(
                                text = prepState.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Surface(
                            color = LensSky.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = "${prepState.cueCount} lines",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = LensSky,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { prepState.progress.coerceIn(0.02f, 1f) },
                        color = LensSky,
                        trackColor = CinemaObsidian,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = prepState.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("prep_cancel_button"),
                    ) {
                        Text(
                            text = if (prepState.partialProject != null) {
                                "Stop & Save ${prepState.cueCount} Partial Lines"
                            } else {
                                "Cancel Generation"
                            }
                        )
                    }
                }
            }
        }
        is SubtitlePrepState.Ready -> {
            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.2.dp, LensMint.copy(alpha = 0.65f)),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subtitle_prep_ready_card"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = LensMint,
                                modifier = Modifier.size(20.dp),
                            )
                            Column {
                                Text(
                                    text = if (prepState.isPartial) "Partial Subtitles Ready" else "Subtitles Ready to Download",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = LensMint,
                                )
                                Text(
                                    text = "${prepState.cueCount} lines · ${prepState.speakerCount} speakers · ${prepState.sourceLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("prep_dismiss_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss generated subtitle card",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (prepState.note.isNotBlank()) {
                        Text(
                            text = prepState.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Format:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SubtitleParser.SubtitleExportFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = selectedFormat == fmt,
                                onClick = { onSelectFormat(fmt) },
                                label = { Text(fmt.label, fontSize = 12.sp) },
                                modifier = Modifier.testTag("prep_ready_format_${fmt.extension}"),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LensMint,
                                contentColor = CinemaObsidian,
                            ),
                            modifier = Modifier
                                .weight(1.25f)
                                .minimumInteractiveComponentSize()
                                .testTag("prep_download_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Download ${selectedFormat.label}",
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        OutlinedButton(
                            onClick = onWatchNow,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("prep_watch_now_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayCircle,
                                contentDescription = null,
                                tint = LensSky,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Watch Now")
                        }
                    }
                }
            }
        }
        is SubtitlePrepState.Error -> {
            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LensAmber.copy(alpha = 0.65f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subtitle_prep_error_card"),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = LensAmber,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = prepState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("prep_error_dismiss_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss subtitle prep error",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
