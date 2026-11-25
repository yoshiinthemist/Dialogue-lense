package com.example.ui.setup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.domain.media.MediaProviderKind
import com.example.domain.media.MediaSourceAdapterRegistry
import com.example.ui.player.formatBeaconTimestamp
import com.example.ui.state.DialogueLensViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ui.theme.LensMint

internal fun Context.documentName(uri: Uri, fallback: String): String {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: fallback
}

@Composable
internal fun WatchHome(
    projects: List<DialogueLensProject>,
    viewModel: DialogueLensViewModel,
    onSelectExisting: () -> Unit,
    onQuickTour: () -> Unit,
    hasCompletedOnboarding: Boolean,
    cloudDialogueConfigured: Boolean,
    onAdvancedImport: () -> Unit,
    onLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dialoguelens_intake", Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(prefs.getString("target_language", "English") ?: "English") }
    var chooseLanguage by remember { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    val resolved = remember(url) { MediaSourceAdapterRegistry.default.resolveInput(url.trim()) }
    val supported = resolved?.isSupported == true && resolved.providerKind in setOf(
        MediaProviderKind.YOUTUBE_EMBED, MediaProviderKind.HTML5_WEB_EMBED, MediaProviderKind.DIRECT_MEDIA_URL,
    )
    val scope = rememberCoroutineScope()
    val localVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val title = withContext(Dispatchers.IO) { context.documentName(uri, "Local video") }
            viewModel.createProjectFromMedia(uri, title, null, "", "Auto-Detect", language,
                TranslationMode.NATURAL, SubtitleSourceKind.EMBEDDED_TRACK, ProcessingQualityMode.BALANCED)
        }
    }
    val recent = remember(projects) { projects.sortedByDescending { maxOf(it.lastWatchedAtEpochMs, it.createdAtEpochMs) } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 660.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ChatBubble, null, tint = LensMint)
                    Spacer(Modifier.width(8.dp))
                    Text("DialogueLens", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { chooseLanguage = true }) {
                            Icon(Icons.Filled.Translate, "Subtitle language")
                            Spacer(Modifier.width(6.dp))
                            Text(language)
                        }
                        DropdownMenu(chooseLanguage, { chooseLanguage = false }) {
                            TARGET_LANGUAGES.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    language = name
                                    prefs.edit().putString("target_language", name).apply()
                                    chooseLanguage = false
                                })
                            }
                        }
                    }
                }
            }
            item {
                Text("What do you want to watch?", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            if (!cloudDialogueConfigured) {
                item {
                    Text("This build uses native or attached subtitles. For a video without captions, attach an SRT or VTT file in Import options.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
                    label = { Text("Video link") }, leadingIcon = { Icon(Icons.Filled.Link, null) },
                    isError = url.isNotBlank() && !supported,
                    supportingText = if (url.isNotBlank() && !supported) ({
                        Text(resolved?.userNotice?.takeIf { it.isNotBlank() } ?: "Enter a supported video link.")
                    }) else null,
                    modifier = Modifier.fillMaxWidth().testTag("start_url_input"))
            }
            item {
                Button(enabled = supported, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("start_submit_url_button"),
                    onClick = {
                        val media = resolved ?: return@Button
                        when (media.providerKind) {
                            MediaProviderKind.YOUTUBE_EMBED -> viewModel.createYouTubeProject(media.normalizedUri, media.youtubeVideoId, null, "", "Auto-Detect", language, TranslationMode.NATURAL, "")
                            MediaProviderKind.DIRECT_MEDIA_URL -> viewModel.createDirectMediaUrlProject(media.normalizedUri, null, "", "Auto-Detect", language, TranslationMode.NATURAL, "")
                            MediaProviderKind.HTML5_WEB_EMBED -> viewModel.createWebStreamProject(media.normalizedUri, null, "", "Auto-Detect", language, TranslationMode.NATURAL, "")
                            else -> Unit
                        }
                    }) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start watching")
                }
            }
            item {
                OutlinedButton(onClick = { localVideo.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open_local_video")) {
                    Icon(Icons.Filled.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open local video")
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onAdvancedImport) { Text("Import options") }
                    TextButton(onClick = onQuickTour, modifier = Modifier.testTag("open_quick_tour")) {
                        Text(if (hasCompletedOnboarding) "Replay tour" else "Quick tour")
                    }
                    TextButton(onClick = { onSelectExisting(); viewModel.loadDemoScenario() }) { Text("Try the demo") }
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text("Recently opened", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp).semantics { heading() })
                }
                items(recent.take(3), key = { it.id }) { project ->
                    ProjectRow(project, onOpen = { onSelectExisting(); viewModel.loadProject(project) })
                }
                item { TextButton(onClick = onLibrary) { Text("View library") } }
            }
        }
    }
}

private enum class LibraryFilter(val label: String) {
    ALL("All"), CONTINUE("Continue watching"), SUBTITLES("Saved dialogue"), ATTENTION("Needs attention")
}

@Composable
internal fun MediaLibrary(
    projects: List<DialogueLensProject>,
    onOpen: (DialogueLensProject) -> Unit,
    onInspect: (DialogueLensProject) -> Unit,
    onExport: (DialogueLensProject) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var remove by remember { mutableStateOf<DialogueLensProject?>(null) }
    val matches = remember(projects, query, filter) {
        projects.filter { it.title.contains(query.trim(), ignoreCase = true) && when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.CONTINUE -> it.lastPlaybackPositionMs > 0 && (it.videoSource.durationMs <= 0 || it.lastPlaybackPositionMs < it.videoSource.durationMs - 1000)
            LibraryFilter.SUBTITLES -> it.dialogueEvents.isNotEmpty()
            LibraryFilter.ATTENTION -> it.healBeacons.any { b -> b.status == HealBeaconStatus.NEEDS_ATTENTION }
        } }.sortedByDescending { maxOf(it.lastWatchedAtEpochMs, it.createdAtEpochMs) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your library", style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    IconButton(onClick = onImport) { Icon(Icons.Filled.FolderOpen, "Import a saved project") }
                }
            }
            if (projects.isEmpty()) {
                item { Text("No saved videos yet", style = MaterialTheme.typography.titleMedium) }
                item { Text("Open a video in Watch to save your place here.") }
            } else {
                item {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Search saved videos") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryFilter.entries.forEach { value ->
                            FilterChip(filter == value, { filter = value }, label = { Text(value.label) })
                        }
                    }
                }
                if (matches.isEmpty()) item { Text("No matching videos") }
                items(matches, key = { it.id }) { project ->
                    ProjectRow(project, onOpen = { onOpen(project) }) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options for ${project.title}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Resume") }, { menu = false; onOpen(project) })
                                DropdownMenuItem({ Text("Restart") }, { menu = false; onOpen(project.copy(lastPlaybackPositionMs = 0)) })
                                DropdownMenuItem({ Text("Inspect in Studio") }, { menu = false; onInspect(project) })
                                if (project.dialogueEvents.isNotEmpty()) {
                                    DropdownMenuItem({ Text("Export subtitles") }, { menu = false; onExport(project) })
                                }
                                DropdownMenuItem({ Text("Remove from library") }, { menu = false; remove = project })
                            }
                        }
                    }
                }
            }
        }
    }
    remove?.let { project ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${project.title}?") },
            text = { Text("This deletes the saved DialogueLens project, including its edits and repair history. Your video file stays on your device.") },
            confirmButton = { TextButton(onClick = { remove = null; onRemove(project.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ProjectRow(project: DialogueLensProject, onOpen: () -> Unit, actions: @Composable () -> Unit = {}) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("start_saved_proj_${project.id}")) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.PlayCircle, "Open ${project.title}", tint = LensMint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val position = project.lastPlaybackPositionMs
                val verifiedLead = remember(project.coverageIntervals, project.videoSource.durationMs, position) {
                    com.example.domain.analysis.TranscriptCoverageLedger(project.videoSource.durationMs, project.coverageIntervals).verifiedLeadMs(position)
                }
                val details = buildList {
                    add(project.targetLanguage)
                    if (position > 0) add("Resume at ${formatBeaconTimestamp(position)}")
                    val attention = project.healBeacons.count { it.status == HealBeaconStatus.NEEDS_ATTENTION }
                    if (attention > 0) add("$attention to review")
                    if (verifiedLead > 0L) add("${formatBeaconTimestamp(verifiedLead)} of dialogue prepared ahead")
                    else if (project.dialogueEvents.isNotEmpty()) add("${project.dialogueEvents.size} saved lines")
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (position > 0 && project.videoSource.durationMs > 0) {
                    LinearProgressIndicator(progress = { (position.toFloat() / project.videoSource.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            actions()
        }
    }
}
