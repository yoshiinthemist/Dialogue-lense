package com.example.data.subtitle

import com.example.data.model.ChapterSource
import com.example.data.model.DialogueType
import com.example.data.model.VideoChapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class YouTubeCaptionResult(
    val title: String? = null,
    val channelName: String? = null,
    val description: String? = null,
    val durationMs: Long? = null,
    val languageCode: String? = null,
    val cues: List<RawSubtitleCue> = emptyList(),
    val chapters: List<VideoChapter> = emptyList(),
    val restricted: Boolean = false,
)

/**
 * Fetches publicly available Closed Caption / WebVTT / JSON3 accessibility tracks and basic
 * title/duration metadata for a YouTube video (never downloads audio or video media streams).
 */
object YouTubeCaptionFetcher {

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(Map::class.java)

    private const val CAPTION_USER_AGENT = "DialogueLens/2.0 (Android)"

    suspend fun fetchFastMetadataOnly(
        videoId: String,
    ): YouTubeCaptionResult = withContext(Dispatchers.IO) {
        val cleanId = videoId.replace(Regex("[^0-9A-Za-z_-]"), "")
        if (cleanId.isBlank()) return@withContext YouTubeCaptionResult()
        kotlinx.coroutines.withTimeoutOrNull(1_800L) {
            val meta = runCatching { fetchFromWatchPage(cleanId) }.getOrNull()
            if (meta != null) {
                YouTubeCaptionResult(
                    title = meta.title,
                    channelName = meta.channelName,
                    description = meta.description,
                    durationMs = meta.durationMs,
                    chapters = extractChaptersFromDescription(meta.description, meta.durationMs ?: 0L),
                )
            } else {
                null
            }
        } ?: YouTubeCaptionResult()
    }

    suspend fun fetchMetadataAndCaptions(
        videoId: String,
        preferredSourceLanguage: String,
        preferredTargetLanguage: String,
    ): YouTubeCaptionResult = withContext(Dispatchers.IO) {
        val cleanId = videoId.replace(Regex("[^0-9A-Za-z_-]"), "")
        if (cleanId.isBlank()) return@withContext YouTubeCaptionResult()

        var bestTitle: String? = null
        var bestChannelName: String? = null
        var bestDescription: String? = null
        var bestDurationMs: Long? = null

        val result = kotlinx.coroutines.withTimeoutOrNull(8_500L) {
            var emptyDownloadAttempts = 0
            // Use caption tracks actually advertised for a playable, public video.
            val pages = listOf(::fetchFromWatchPage, ::fetchFromEmbedPage)
            for (loadPage in pages) {
                val meta = runCatching { loadPage(cleanId) }.getOrNull() ?: continue
                if (meta.restricted) return@withTimeoutOrNull YouTubeCaptionResult(restricted = true)
                if (bestTitle == null && !meta.title.isNullOrBlank()) bestTitle = meta.title
                if (bestChannelName == null && !meta.channelName.isNullOrBlank()) bestChannelName = meta.channelName
                if (bestDescription == null && !meta.description.isNullOrBlank()) bestDescription = meta.description
                if (bestDurationMs == null && (meta.durationMs ?: 0L) > 0L) bestDurationMs = meta.durationMs

                if (meta.tracks.isEmpty() || emptyDownloadAttempts >= 3) continue
                val orderedTracks = orderCandidateTracks(
                    tracks = meta.tracks,
                    preferredSourceLang = preferredSourceLanguage,
                    preferredTargetLang = preferredTargetLanguage,
                )
                for (track in orderedTracks.take(2)) {
                    if (emptyDownloadAttempts >= 3) break
                    val cues = runCatching {
                        downloadAndParseTrack(track.baseUrl, meta.cookies, meta.preferredUserAgent)
                    }.getOrDefault(emptyList())
                    if (cues.isNotEmpty()) {
                        val finalDesc = bestDescription ?: meta.description
                        val finalDur = bestDurationMs ?: meta.durationMs
                        return@withTimeoutOrNull YouTubeCaptionResult(
                            title = bestTitle ?: meta.title,
                            channelName = bestChannelName ?: meta.channelName,
                            description = finalDesc,
                            durationMs = finalDur,
                            languageCode = track.languageCode,
                            cues = cues,
                            chapters = extractChaptersFromDescription(finalDesc, finalDur ?: 0L),
                        )
                    } else {
                        emptyDownloadAttempts++
                    }
                }
            }
            null
        }

        result ?: YouTubeCaptionResult(
            title = bestTitle,
            channelName = bestChannelName,
            description = bestDescription,
            durationMs = bestDurationMs,
            chapters = extractChaptersFromDescription(bestDescription, bestDurationMs ?: 0L),
        )
    }

    private val chapterTimestampRegex = Regex(
        """^\s*(?:\[\s*)?(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:\s*\])?\s*(?:[-–—|:\u2022]\s*)?(.+?)\s*$"""
    )

    /**
     * Parses standard YouTube description chapter lists (e.g. "0:00 Intro", "02:15 Interview",
     * "1:04:10 Closing Q&A"). Requires at least 2 valid strictly increasing timestamps, starting
     * near the beginning of the video (within 15s) or spanning meaningful chapter intervals.
     */
    fun extractChaptersFromDescription(
        description: String?,
        durationMs: Long = 0L,
    ): List<VideoChapter> {
        if (description.isNullOrBlank()) return emptyList()
        val rawEntries = mutableListOf<Pair<Long, String>>()
        description.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.length < 5) return@forEach
            val match = chapterTimestampRegex.matchEntire(line) ?: return@forEach
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: return@forEach
            val seconds = match.groupValues[3].toLongOrNull() ?: return@forEach
            if (seconds >= 60L || (match.groupValues[1].isNotEmpty() && minutes >= 60L)) return@forEach
            val startMs = ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L
            val title = match.groupValues[4]
                .replace(Regex("""^\s*[-–—|:]\s*"""), "")
                .trim()
                .take(80)
            if (title.isBlank()) return@forEach
            if (durationMs > 0L && startMs >= durationMs) return@forEach
            if (rawEntries.isEmpty() || startMs > rawEntries.last().first + 3_000L) {
                rawEntries.add(startMs to title)
            }
        }
        if (rawEntries.size < 2) return emptyList()
        // Preserve only the chapter titles and timestamps actually supplied by the author.
        val effectiveDuration = durationMs.takeIf { it > 0L } ?: (rawEntries.last().first + 1L)
        return rawEntries.mapIndexed { idx, (startMs, title) ->
            val endMs = if (idx < rawEntries.lastIndex) {
                rawEntries[idx + 1].first
            } else {
                effectiveDuration
            }
            VideoChapter(
                id = "chapter_${idx + 1}",
                title = title,
                startMs = startMs,
                endMs = endMs.coerceAtLeast(startMs + 1L),
                source = ChapterSource.YOUTUBE_DESCRIPTION,
            )
        }
    }

    /**
     * Parses a live `youtube.com/api/timedtext` body or URL observed inside the embedded WebView
     * (preserving WebView cookies, User-Agent, and PO/signature parameters).
     */
    suspend fun fetchTimedTextFromObservedUrl(
        observedUrl: String,
        cookies: String? = null,
        observedBody: String = "",
        userAgent: String? = null,
    ): List<RawSubtitleCue> = withContext(Dispatchers.IO) {
        if (!isPublicCaptionUrl(observedUrl)) return@withContext emptyList()
        if (observedBody.isNotBlank()) {
            val directCues = runCatching { parseCaptionPayload(observedBody) }.getOrDefault(emptyList())
            if (directCues.isNotEmpty()) return@withContext directCues
        }
        runCatching {
            downloadAndParseTrack(observedUrl, cookies, userAgent)
        }.getOrDefault(emptyList())
    }

    internal data class CaptionTrackMeta(
        val baseUrl: String,
        val languageCode: String,
        val kind: String = "",
    )

    internal data class PlayerMetaResult(
        val title: String?,
        val channelName: String? = null,
        val description: String? = null,
        val durationMs: Long?,
        val tracks: List<CaptionTrackMeta>,
        val cookies: String? = null,
        val preferredUserAgent: String? = null,
        val restricted: Boolean = false,
    )

    private fun fetchFromWatchPage(videoId: String): PlayerMetaResult? {
        val conn = (URL("https://www.youtube.com/watch?v=$videoId&hl=en").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("User-Agent", CAPTION_USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val cookies = extractCookieHeader(conn)
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonObj = extractJsonObjectAfterMarker(html, "ytInitialPlayerResponse = ")
                ?: extractJsonObjectAfterMarker(html, "\"playerResponse\":\"")
            val parsed = jsonObj?.let { mapAdapter.fromJson(it) }
            if (parsed != null) {
                extractMetaFromPlayerMap(
                    root = parsed,
                    cookies = cookies,
                    userAgent = CAPTION_USER_AGENT,
                )
            } else {
                PlayerMetaResult(
                    title = null,
                    durationMs = null,
                    tracks = emptyList(),
                    cookies = cookies,
                    preferredUserAgent = CAPTION_USER_AGENT,
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchFromEmbedPage(videoId: String): PlayerMetaResult? {
        val conn = (URL("https://www.youtube.com/embed/$videoId?hl=en").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("User-Agent", CAPTION_USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Referer", "https://www.youtube.com/")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val cookies = extractCookieHeader(conn)
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonObj = extractJsonObjectAfterMarker(html, "ytInitialPlayerResponse = ")
                ?: extractJsonObjectAfterMarker(html, "\"embedded_player_response\":\"")
            val parsed = jsonObj?.let { runCatching { mapAdapter.fromJson(it) }.getOrNull() }
            if (parsed != null) {
                extractMetaFromPlayerMap(
                    root = parsed,
                    cookies = cookies,
                    userAgent = CAPTION_USER_AGENT,
                )
            } else {
                PlayerMetaResult(
                    title = null,
                    durationMs = null,
                    tracks = emptyList(),
                    cookies = cookies,
                    preferredUserAgent = CAPTION_USER_AGENT,
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractCookieHeader(conn: HttpURLConnection): String? {
        val setCookies = conn.headerFields["Set-Cookie"].orEmpty() +
            conn.headerFields["set-cookie"].orEmpty()
        val pairs = setCookies.mapNotNull { it.substringBefore(';').trim().takeIf { kv -> kv.contains('=') } }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    @Suppress("UNCHECKED_CAST")
    internal fun extractMetaFromPlayerMap(
        root: Map<String, Any?>,
        cookies: String? = null,
        userAgent: String? = null,
    ): PlayerMetaResult {
        val status = (root["playabilityStatus"] as? Map<*, *>)?.get("status") as? String
        if (status != null && status != "OK") {
            return PlayerMetaResult(title = null, durationMs = null, tracks = emptyList(), restricted = true)
        }
        val videoDetails = root["videoDetails"] as? Map<String, Any?>
        val title = (videoDetails?.get("title") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val channelName = (videoDetails?.get("author") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val description = (videoDetails?.get("shortDescription") as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(16_000)
        val lengthSec = videoDetails?.get("lengthSeconds")?.toString()?.toDoubleOrNull()?.toLong()
        val durationMs = lengthSec?.takeIf { it > 0 }?.times(1000L)

        val captions = root["captions"] as? Map<String, Any?>
        val renderer = captions?.get("playerCaptionsTracklistRenderer") as? Map<String, Any?>
        val rawTracks = renderer?.get("captionTracks") as? List<Map<String, Any?>> ?: emptyList()

        val tracks = rawTracks.mapNotNull { t ->
            val rawUrl = (t["baseUrl"] as? String)?.trim().orEmpty()
            val url = when {
                rawUrl.startsWith("http") -> rawUrl
                rawUrl.startsWith("/") -> "https://www.youtube.com$rawUrl"
                else -> return@mapNotNull null
            }
            val lang = (t["languageCode"] as? String).orEmpty()
            val kind = (t["kind"] as? String).orEmpty()
            CaptionTrackMeta(baseUrl = url, languageCode = lang, kind = kind)
        }
        return PlayerMetaResult(
            title = title,
            channelName = channelName,
            description = description,
            durationMs = durationMs,
            tracks = tracks,
            cookies = cookies,
            preferredUserAgent = userAgent,
        )
    }

    internal fun isWholeTrackForVideo(url: String, videoId: String): Boolean = runCatching {
        if (!isPublicCaptionUrl(url)) return false
        val query = java.net.URI(url).rawQuery.orEmpty().split('&').associate {
            it.substringBefore('=') to it.substringAfter('=', "")
        }
        query["v"] == videoId && query.keys.none { it in setOf("sq", "start", "end", "begin", "endtime") }
    }.getOrDefault(false)

    private fun downloadAndParseTrack(
        baseUrl: String,
        cookies: String? = null,
        userAgent: String? = null,
    ): List<RawSubtitleCue> {
        val cleanBase = baseUrl.replace("\\u0026", "&")

        if (!isPublicCaptionUrl(cleanBase)) return emptyList()
        return httpGetText(cleanBase, cookies, userAgent)?.let(::parseCaptionPayload).orEmpty()
    }

    internal fun isPublicCaptionUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")) &&
            uri.path == "/api/timedtext"
    }.getOrDefault(false)

    internal fun parseCaptionPayload(raw: String): List<RawSubtitleCue> {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{")) {
            val jsonCues = parseJson3Captions(trimmed)
            if (jsonCues.isNotEmpty()) return jsonCues
        }
        if (trimmed.startsWith("WEBVTT")) {
            val vttCues = SubtitleParser.parse(trimmed, "youtube.vtt")
            if (vttCues.isNotEmpty()) return vttCues
        }
        val xmlSrv1 = parseTimedTextXml(trimmed)
        if (xmlSrv1.isNotEmpty()) return xmlSrv1
        val xmlSrv3 = parseTimedTextSrv3Xml(trimmed)
        if (xmlSrv3.isNotEmpty()) return xmlSrv3
        return SubtitleParser.parse(trimmed, "youtube.vtt")
    }

    private fun httpGetText(
        urlStr: String,
        cookies: String? = null,
        userAgent: String? = null,
    ): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 2000
            readTimeout = 2500
            setRequestProperty("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: CAPTION_USER_AGENT)
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("Origin", "https://www.youtube.com")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (!cookies.isNullOrBlank()) {
                setRequestProperty("Cookie", cookies)
            }
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun selectBestCaptionTrack(
        tracks: List<CaptionTrackMeta>,
        preferredSourceLang: String,
        preferredTargetLang: String,
    ): CaptionTrackMeta? = orderCandidateTracks(tracks, preferredSourceLang, preferredTargetLang).firstOrNull()

    private fun orderCandidateTracks(
        tracks: List<CaptionTrackMeta>,
        preferredSourceLang: String,
        preferredTargetLang: String,
    ): List<CaptionTrackMeta> {
        if (tracks.isEmpty()) return emptyList()
        val srcCode = languageNameToIso(preferredSourceLang)
        val tgtCode = languageNameToIso(preferredTargetLang)

        fun matchesCode(trackLang: String, code: String?): Boolean {
            if (code == null) return false
            val norm = trackLang.lowercase(Locale.US)
            return norm == code || norm.startsWith("$code-")
        }

        val ranked = buildList {
            tracks.firstOrNull { matchesCode(it.languageCode, srcCode) && it.kind != "asr" }?.let(::add)
            tracks.firstOrNull { matchesCode(it.languageCode, srcCode) }?.let(::add)
            tracks.firstOrNull { matchesCode(it.languageCode, tgtCode) && it.kind != "asr" }?.let(::add)
            tracks.firstOrNull { matchesCode(it.languageCode, tgtCode) }?.let(::add)
            tracks.firstOrNull { matchesCode(it.languageCode, "en") && it.kind != "asr" }?.let(::add)
            tracks.firstOrNull { matchesCode(it.languageCode, "en") }?.let(::add)
            tracks.firstOrNull { it.kind != "asr" }?.let(::add)
            addAll(tracks)
        }
        return ranked.distinctBy { "${it.languageCode}:${it.kind}:${it.baseUrl}" }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseJson3Captions(json: String): List<RawSubtitleCue> {
        val root = runCatching { mapAdapter.fromJson(json) }.getOrNull() ?: return emptyList()
        val rawEvents = root["events"] as? List<Map<String, Any?>> ?: return emptyList()
        val cues = mutableListOf<RawSubtitleCue>()

        for (evt in rawEvents) {
            val tStartMs = (evt["tStartMs"] as? Number)?.toLong() ?: continue
            val durMs = ((evt["dDurationMs"] as? Number)?.toLong() ?: 2400L).coerceAtLeast(450L)
            val segs = evt["segs"] as? List<Map<String, Any?>> ?: continue
            val rawText = segs.mapNotNull { it["utf8"] as? String }.joinToString("")
            val cleaned = unescapeHtmlEntities(rawText)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.isBlank() || cleaned == "\n") continue
            val inferred = if (cleaned.startsWith("[") || cleaned.startsWith("(") || cleaned.contains("♪")) {
                DialogueType.SOUND_DESCRIPTION
            } else {
                DialogueType.SPOKEN
            }
            cues.add(
                RawSubtitleCue(
                    startTimeMs = tStartMs.coerceAtLeast(0L),
                    endTimeMs = (tStartMs + durMs).coerceAtLeast(tStartMs + 450L),
                    text = cleaned,
                    inferredType = inferred,
                )
            )
        }
        return mergeRollingDuplicates(cues)
    }

    internal fun parseTimedTextXml(xml: String): List<RawSubtitleCue> {
        val cues = mutableListOf<RawSubtitleCue>()
        val tagRegex = Regex("""<text\s+([^>]*)>(.*?)</text>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val attrRegex = Regex("""(start|dur)=["']([0-9.]+)["']""")

        for (match in tagRegex.findAll(xml)) {
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            var startSec = 0.0
            var durSec = 2.0
            for (attr in attrRegex.findAll(attrs)) {
                when (attr.groupValues[1].lowercase(Locale.US)) {
                    "start" -> startSec = attr.groupValues[2].toDoubleOrNull() ?: 0.0
                    "dur" -> durSec = attr.groupValues[2].toDoubleOrNull() ?: 2.0
                }
            }
            val cleaned = unescapeHtmlEntities(body.replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.isNotEmpty()) {
                val startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L)
                val endMs = (startMs + (durSec * 1000.0).toLong()).coerceAtLeast(startMs + 450L)
                val inferred = if (cleaned.startsWith("[") || cleaned.startsWith("(") || cleaned.contains("♪")) {
                    DialogueType.SOUND_DESCRIPTION
                } else {
                    DialogueType.SPOKEN
                }
                cues.add(RawSubtitleCue(startMs, endMs, cleaned, inferred))
            }
        }
        return mergeRollingDuplicates(cues)
    }

    internal fun parseTimedTextSrv3Xml(xml: String): List<RawSubtitleCue> {
        val cues = mutableListOf<RawSubtitleCue>()
        val pRegex = Regex("""<p\s+([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val attrRegex = Regex("""\b(t|d)=["'](\d+)["']""")

        for (match in pRegex.findAll(xml)) {
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            var startMs = -1L
            var durMs = 2400L
            for (attr in attrRegex.findAll(attrs)) {
                when (attr.groupValues[1].lowercase(Locale.US)) {
                    "t" -> startMs = attr.groupValues[2].toLongOrNull() ?: -1L
                    "d" -> durMs = attr.groupValues[2].toLongOrNull() ?: 2400L
                }
            }
            if (startMs < 0L) continue
            val cleaned = unescapeHtmlEntities(body.replace(Regex("<[^>]+>"), ""))
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.isNotEmpty()) {
                val endMs = (startMs + durMs).coerceAtLeast(startMs + 450L)
                val inferred = if (cleaned.startsWith("[") || cleaned.startsWith("(") || cleaned.contains("♪")) {
                    DialogueType.SOUND_DESCRIPTION
                } else {
                    DialogueType.SPOKEN
                }
                cues.add(RawSubtitleCue(startMs, endMs, cleaned, inferred))
            }
        }
        return mergeRollingDuplicates(cues)
    }

    internal fun mergeRollingDuplicates(cues: List<RawSubtitleCue>): List<RawSubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        val sorted = cues.sortedBy { it.startTimeMs }
        val result = mutableListOf<RawSubtitleCue>()
        for (cue in sorted) {
            val prev = result.lastOrNull()
            if (prev == null) {
                result.add(cue)
                continue
            }
            val overlapMs = minOf(prev.endTimeMs, cue.endTimeMs) - cue.startTimeMs
            val startDeltaMs = kotlin.math.abs(cue.startTimeMs - prev.startTimeMs)
            val hasGenuineOverlap = overlapMs >= 150L || startDeltaMs <= 300L
            if (!hasGenuineOverlap) {
                result.add(cue)
                continue
            }

            val prevNorm = prev.text.trim().lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}]+"), "")
            val currNorm = cue.text.trim().lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}]+"), "")
            if (prevNorm.isNotEmpty() && prevNorm == currNorm) {
                result[result.lastIndex] = prev.copy(endTimeMs = maxOf(prev.endTimeMs, cue.endTimeMs))
                continue
            }

            val prevWords = prev.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val currWords = cue.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (prevWords.isNotEmpty() && currWords.isNotEmpty()) {
                // Case 1: Rolling ASR repeats prev as prefix of curr -> keep only new suffix words
                if (currWords.size > prevWords.size &&
                    currWords.take(prevWords.size).map { it.lowercase(Locale.US) } ==
                    prevWords.map { it.lowercase(Locale.US) }
                ) {
                    val trimmed = currWords.drop(prevWords.size).joinToString(" ").trim()
                    if (trimmed.isNotEmpty()) {
                        result.add(cue.copy(text = trimmed))
                    }
                    continue
                }
                // Case 2: Rolling ASR starts curr with last k >= 2 words of prev -> strip overlap
                val maxOverlap = minOf(prevWords.size, currWords.size)
                var matchedK = 0
                for (k in maxOverlap downTo 2) {
                    val tail = prevWords.takeLast(k).map { it.lowercase(Locale.US).trim(',', '.', '!', '?') }
                    val head = currWords.take(k).map { it.lowercase(Locale.US).trim(',', '.', '!', '?') }
                    if (tail == head) {
                        matchedK = k
                        break
                    }
                }
                if (matchedK >= 2) {
                    val remaining = currWords.drop(matchedK).joinToString(" ").trim()
                    if (remaining.isNotEmpty()) {
                        result.add(cue.copy(text = remaining))
                    }
                    continue
                }
            }
            result.add(cue)
        }
        return result
    }

    private fun extractJsonObjectAfterMarker(source: String, marker: String): String? {
        val markerIdx = source.indexOf(marker)
        if (markerIdx == -1) return null
        val startIdx = source.indexOf('{', markerIdx + marker.length)
        if (startIdx == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until source.length) {
            val ch = source[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return source.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun languageNameToIso(name: String): String? {
        return when (name.trim().lowercase(Locale.US)) {
            "auto-detect", "" -> null
            "english" -> "en"
            "japanese" -> "ja"
            "korean" -> "ko"
            "spanish" -> "es"
            "french" -> "fr"
            "german" -> "de"
            "mandarin", "chinese" -> "zh"
            "italian" -> "it"
            "portuguese" -> "pt"
            "hindi" -> "hi"
            "arabic" -> "ar"
            else -> null
        }
    }

    private fun unescapeHtmlEntities(raw: String): String {
        val pass1 = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("\n", " ")
        return pass1
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }
}
