package com.example.data.project

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.CachedVideoTranscript
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.DialogueLensProject
import com.example.data.model.GlossaryEntry
import com.example.data.model.QuotaSaverMode
import com.example.data.model.SpeakerLabelMode
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore by preferencesDataStore(name = "dialoguelens_prefs")

class ProjectStore(private val context: Context) {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val projectAdapter = moshi.adapter(DialogueLensProject::class.java).indent("  ")
    private val cachedTranscriptAdapter = moshi.adapter(CachedVideoTranscript::class.java).indent("  ")
    private val stringMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    private val translationCacheAdapter = moshi.adapter<Map<String, String>>(stringMapType)
    private val colorMapType = Types.newParameterizedType(Map::class.java, String::class.java, Integer::class.java)
    private val colorMapAdapter = moshi.adapter<Map<String, Int>>(colorMapType)
    private val glossaryListType = Types.newParameterizedType(List::class.java, GlossaryEntry::class.java)
    private val glossaryListAdapter = moshi.adapter<List<GlossaryEntry>>(glossaryListType)

    private val projectsDir: File
        get() = File(context.filesDir, "dlens_projects").apply { mkdirs() }

    private val videoCacheDir: File
        get() = File(context.filesDir, "dlens_video_cache").apply { mkdirs() }

    private val translationCacheFile: File
        get() = File(videoCacheDir, "line_translations_v1.json")

    private object Keys {
        val DISPLAY_MODE = stringPreferencesKey("caption_display_mode")
        val VISUAL_STYLE = stringPreferencesKey("balloon_visual_style")
        val FONT_SCALE = floatPreferencesKey("balloon_font_scale")
        val OPACITY = floatPreferencesKey("balloon_opacity")
        val HIGH_CONTRAST = booleanPreferencesKey("balloon_high_contrast")
        val SHOW_TAIL = booleanPreferencesKey("balloon_show_tail")
        val SPEAKER_COLORS = booleanPreferencesKey("balloon_speaker_colors")
        val MAX_LINES = intPreferencesKey("balloon_max_lines")
        val REDUCED_MOTION = booleanPreferencesKey("balloon_reduced_motion")
        val STABLE_POSITIONING = booleanPreferencesKey("balloon_stable_positioning")
        val MOVEMENT_SENSITIVITY = floatPreferencesKey("balloon_movement_sensitivity")
        val SHOW_SPEAKER_LABELS = booleanPreferencesKey("balloon_show_labels")
        val SPEAKER_LABEL_MODE = stringPreferencesKey("balloon_speaker_label_mode")
        val SHOW_CONFIDENCE = booleanPreferencesKey("balloon_show_confidence")
        val TATEGAKI_VERTICAL = booleanPreferencesKey("balloon_tategaki_vertical")
        val LIP_READING_SAFE_ZONE = booleanPreferencesKey("balloon_lip_reading_safe_zone")
        val OFF_SCREEN_EDGE_GLOW = booleanPreferencesKey("balloon_off_screen_edge_glow")
        val AMBIENT_MOOD_BORDER = booleanPreferencesKey("balloon_ambient_mood_border")
        val CHARACTER_COLOR_MEMORY = stringPreferencesKey("character_color_memory_json")
        val GLOSSARY_MEMORY = stringPreferencesKey("series_glossary_memory_json")
        val QUOTA_SAVER_MODE = stringPreferencesKey("quota_saver_mode")
    }

    private val defaultStyle = BalloonStyleSettings()

    val styleSettingsFlow: Flow<BalloonStyleSettings> = context.settingsDataStore.data.map { prefs ->
        val rawStyle = prefs[Keys.VISUAL_STYLE]
        val visualStyle = BubbleVisualStyle.entries.firstOrNull { it.name == rawStyle }
            ?: defaultStyle.visualStyle
        val rawLabelMode = prefs[Keys.SPEAKER_LABEL_MODE]
        val labelMode = SpeakerLabelMode.entries.firstOrNull { it.name == rawLabelMode }
            ?: defaultStyle.labelMode
        BalloonStyleSettings(
            visualStyle = visualStyle,
            fontScale = prefs[Keys.FONT_SCALE] ?: defaultStyle.fontScale,
            balloonOpacity = prefs[Keys.OPACITY] ?: defaultStyle.balloonOpacity,
            highContrastMode = prefs[Keys.HIGH_CONTRAST] ?: defaultStyle.highContrastMode,
            showTail = prefs[Keys.SHOW_TAIL] ?: defaultStyle.showTail,
            speakerSpecificColors = prefs[Keys.SPEAKER_COLORS] ?: defaultStyle.speakerSpecificColors,
            maxLines = prefs[Keys.MAX_LINES] ?: defaultStyle.maxLines,
            reducedMotion = prefs[Keys.REDUCED_MOTION] ?: defaultStyle.reducedMotion,
            stablePositioning = prefs[Keys.STABLE_POSITIONING] ?: defaultStyle.stablePositioning,
            movementSensitivity = (prefs[Keys.MOVEMENT_SENSITIVITY] ?: defaultStyle.movementSensitivity)
                .coerceIn(0f, 1f),
            showSpeakerLabels = labelMode != SpeakerLabelMode.OFF,
            labelMode = labelMode,
            showConfidenceIndicators = prefs[Keys.SHOW_CONFIDENCE] ?: defaultStyle.showConfidenceIndicators,
            tategakiVerticalJapanese = prefs[Keys.TATEGAKI_VERTICAL] ?: defaultStyle.tategakiVerticalJapanese,
            lipReadingSafeZone = prefs[Keys.LIP_READING_SAFE_ZONE] ?: defaultStyle.lipReadingSafeZone,
            offScreenEdgeGlow = prefs[Keys.OFF_SCREEN_EDGE_GLOW] ?: defaultStyle.offScreenEdgeGlow,
            ambientMoodBorder = prefs[Keys.AMBIENT_MOOD_BORDER] ?: defaultStyle.ambientMoodBorder,
        )
    }

    val displayModeFlow: Flow<CaptionDisplayMode> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[Keys.DISPLAY_MODE]
        (CaptionDisplayMode.entries.firstOrNull { it.name == raw } ?: CaptionDisplayMode.BALLOONS)
            .normalizedPrimaryMode()
    }

    val characterColorsFlow: Flow<Map<String, Int>> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[Keys.CHARACTER_COLOR_MEMORY] ?: return@map emptyMap()
        runCatching { colorMapAdapter.fromJson(raw) }.getOrNull() ?: emptyMap()
    }

    val glossaryMemoryFlow: Flow<List<GlossaryEntry>> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[Keys.GLOSSARY_MEMORY] ?: return@map emptyList()
        runCatching { glossaryListAdapter.fromJson(raw) }.getOrNull() ?: emptyList()
    }

    val quotaSaverModeFlow: Flow<QuotaSaverMode> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[Keys.QUOTA_SAVER_MODE]
        QuotaSaverMode.entries.firstOrNull { it.name == raw } ?: QuotaSaverMode.PRO_ACCURACY
    }

    suspend fun saveQuotaSaverMode(mode: QuotaSaverMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.QUOTA_SAVER_MODE] = mode.name
        }
    }

    suspend fun saveCharacterColors(colors: Map<String, Int>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CHARACTER_COLOR_MEMORY] = colorMapAdapter.toJson(colors)
        }
    }

    suspend fun saveGlossaryMemory(entries: List<GlossaryEntry>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.GLOSSARY_MEMORY] = glossaryListAdapter.toJson(entries)
        }
    }

    suspend fun saveStyleSettings(settings: BalloonStyleSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.VISUAL_STYLE] = settings.visualStyle.name
            prefs[Keys.FONT_SCALE] = settings.fontScale
            prefs[Keys.OPACITY] = settings.balloonOpacity
            prefs[Keys.HIGH_CONTRAST] = settings.highContrastMode
            prefs[Keys.SHOW_TAIL] = settings.showTail
            prefs[Keys.SPEAKER_COLORS] = settings.speakerSpecificColors
            prefs[Keys.MAX_LINES] = settings.maxLines
            prefs[Keys.REDUCED_MOTION] = settings.reducedMotion
            prefs[Keys.STABLE_POSITIONING] = settings.stablePositioning
            prefs[Keys.MOVEMENT_SENSITIVITY] = settings.movementSensitivity.coerceIn(0f, 1f)
            prefs[Keys.SHOW_SPEAKER_LABELS] = settings.labelMode != SpeakerLabelMode.OFF
            prefs[Keys.SPEAKER_LABEL_MODE] = settings.labelMode.name
            prefs[Keys.SHOW_CONFIDENCE] = settings.showConfidenceIndicators
            prefs[Keys.TATEGAKI_VERTICAL] = settings.tategakiVerticalJapanese
            prefs[Keys.LIP_READING_SAFE_ZONE] = settings.lipReadingSafeZone
            prefs[Keys.OFF_SCREEN_EDGE_GLOW] = settings.offScreenEdgeGlow
            prefs[Keys.AMBIENT_MOOD_BORDER] = settings.ambientMoodBorder
        }
    }

    suspend fun saveDisplayMode(mode: CaptionDisplayMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DISPLAY_MODE] = mode.normalizedPrimaryMode().name
        }
    }

    // Acquire before dispatching to IO so a slower older snapshot cannot overtake a newer edit.
    private val projectWrites = Mutex()
    private val cacheWrites = Mutex()

    private fun writeTextAtomically(file: File, content: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun projectFile(id: String): File {
        require(isUsableProjectId(id)) { "Invalid project identifier" }
        return File(projectsDir, "$id.dlens.json")
    }

    suspend fun saveProject(project: DialogueLensProject) = projectWrites.withLock {
        withContext(Dispatchers.IO) {
            writeTextAtomically(projectFile(project.id), projectAdapter.toJson(project))
        }
    }

    suspend fun listSavedProjects(): List<DialogueLensProject> = projectWrites.withLock {
        withContext(Dispatchers.IO) {
            // AtomicFile on older Android versions may leave only the backup after a power loss.
            projectsDir.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
                .filter { it.endsWith(".dlens.json") }.distinct().mapNotNull { name ->
                    val file = File(projectsDir, name)
                    runCatching { deserializeProject(String(AtomicFile(file).readFully(), Charsets.UTF_8)) }
                        .getOrNull()
                }.sortedByDescending { maxOf(it.lastWatchedAtEpochMs, it.createdAtEpochMs) }
        }
    }

    suspend fun deleteProject(projectId: String) = projectWrites.withLock {
        withContext(Dispatchers.IO) { AtomicFile(projectFile(projectId)).delete() }
    }

    suspend fun deleteAllProjects() = projectWrites.withLock {
        cacheWrites.withLock {
            withContext(Dispatchers.IO) {
                projectsDir.listFiles()?.forEach { it.delete() }
                videoCacheDir.listFiles()?.forEach { it.delete() }
                Unit
            }
        }
    }

    suspend fun saveCachedTranscript(entry: CachedVideoTranscript) = cacheWrites.withLock {
        withContext(Dispatchers.IO) {
            val safeKey = entry.canonicalKey.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
            val hasCoverageProgress = entry.coverageIntervals.any {
                it.verified || it.status == com.example.data.model.CoverageStatus.FAILED ||
                    it.status == com.example.data.model.CoverageStatus.PARTIAL_RECOVERY
            }
            if (safeKey.isBlank() || (entry.dialogueEvents.isEmpty() && !hasCoverageProgress)) return@withContext
            val file = File(videoCacheDir, "$safeKey.cache.json")
            runCatching { writeTextAtomically(file, cachedTranscriptAdapter.toJson(entry)) }
        }
    }

    suspend fun loadCachedTranscript(canonicalKey: String): CachedVideoTranscript? = cacheWrites.withLock {
        withContext(Dispatchers.IO) {
            val safeKey = canonicalKey.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
            if (safeKey.isBlank()) return@withContext null
            val file = File(videoCacheDir, "$safeKey.cache.json")
            if (!AtomicFile(file).existsCompat()) return@withContext null
            runCatching { cachedTranscriptAdapter.fromJson(String(AtomicFile(file).readFully(), Charsets.UTF_8))?.takeIf { it.canonicalKey == canonicalKey } }.getOrNull()
        }
    }

    suspend fun loadTranslationCache(): Map<String, String> = cacheWrites.withLock {
        withContext(Dispatchers.IO) {
            if (!AtomicFile(translationCacheFile).existsCompat()) return@withContext emptyMap()
            runCatching { translationCacheAdapter.fromJson(String(AtomicFile(translationCacheFile).readFully(), Charsets.UTF_8)) }.getOrNull().orEmpty()
        }
    }

    suspend fun saveTranslationCache(cache: Map<String, String>) = cacheWrites.withLock {
        withContext(Dispatchers.IO) {
            val trimmed = if (cache.size > 1200) {
                cache.entries.toList().takeLast(1000).associate { it.key to it.value }
            } else {
                cache
            }
            runCatching { writeTextAtomically(translationCacheFile, translationCacheAdapter.toJson(trimmed)) }
        }
    }

    private fun AtomicFile.existsCompat(): Boolean = baseFile.exists() || File(baseFile.path + ".bak").exists()

    companion object {
        private fun isUsableProjectId(id: String): Boolean =
            id.isNotBlank() && id.toByteArray(Charsets.UTF_8).size <= 220 &&
                id != "." && id != ".." && id.none { it == '/' || it == '\\' || it.isISOControl() }

        fun canonicalYouTubeCacheKey(videoId: String, targetLanguage: String): String {
            val cleanId = videoId.replace(Regex("[^0-9A-Za-z_-]"), "")
            val langSlug = targetLanguage.trim().lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "en" }
            return "yt_${cleanId}_$langSlug"
        }

        fun canonicalCacheKey(
            source: com.example.data.model.VideoSource,
            targetLanguage: String,
        ): String = com.example.domain.media.MediaSourceAdapterRegistry.default.canonicalCacheKey(
            source = source,
            targetLanguage = targetLanguage,
        )

        fun buildLineTranslationCacheKey(
            sourceText: String,
            sourceLanguage: String,
            targetLanguage: String,
        ): String {
            val normText = sourceText.trim().lowercase().replace(Regex("\\s+"), " ")
            return "${sourceLanguage.trim().lowercase()}|${targetLanguage.trim().lowercase()}|${normText.hashCode()}|${normText.take(64)}"
        }
    }

    fun serializeProject(project: DialogueLensProject): String = projectAdapter.toJson(project)

    fun deserializeProject(json: String): DialogueLensProject? =
        runCatching { projectAdapter.fromJson(json) }.getOrNull()?.takeIf { isUsableProjectId(it.id) }

    suspend fun writeTextToUri(uri: Uri, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(content)
            } ?: context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(content)
            } ?: error("Unable to open output stream for export.")
        }
    }

    /**
     * Writes generated subtitle text directly into public Downloads/DialogueLens on Android 10+
     * with zero storage permissions via MediaStore.Downloads. Returns the user-visible path.
     */
    suspend fun saveTextToDownloads(
        fileName: String,
        mimeType: String,
        content: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext Result.failure(UnsupportedOperationException("Use file picker on Android 9 and below."))
        }
        runCatching {
            val resolver = context.contentResolver
            val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/DialogueLens"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads.")
            try {
                resolver.openOutputStream(itemUri, "w")?.bufferedWriter()?.use { writer ->
                    writer.write(content)
                } ?: error("Could not open Downloads output stream.")
                val finalized = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(itemUri, finalized, null, null)
                "Downloads/DialogueLens/$fileName"
            } catch (t: Throwable) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw t
            }
        }
    }

    suspend fun readTextFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.readText()
            } ?: error("Unable to open input stream for selected file.")
        }
    }
}
