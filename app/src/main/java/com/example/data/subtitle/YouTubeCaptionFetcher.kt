package com.example.data.subtitle

import com.example.data.model.DialogueType
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
)

/**
 * Fetches publicly available Closed Caption / WebVTT / JSON3 accessibility tracks and basic
 * title/duration metadata for a YouTube video (never downloads audio or video media streams).
 */
object YouTubeCaptionFetcher {

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(Map::class.java)

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val ANDROID_YT_UA =
        "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip"
    private const val IOS_YT_UA =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    private const val ANDROID_VR_YT_UA =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    suspend fun fetchFastMetadataOnly(
        videoId: String,
    ): YouTubeCaptionResult = withContext(Dispatchers.IO) {
        val cleanId = videoId.replace(Regex("[^0-9A-Za-z_-]"), "")
        if (cleanId.isBlank()) return@withContext YouTubeCaptionResult()
        kotlinx.coroutines.withTimeoutOrNull(1_800L) {
            val meta = runCatching { fetchFromAndroidVrInnertube(cleanId) }.getOrNull()
                ?: runCatching { fetchFromWatchPage(cleanId) }.getOrNull()
            if (meta != null) {
                YouTubeCaptionResult(
                    title = meta.title,
                    channelName = meta.channelName,
                    description = meta.description,
                    durationMs = meta.durationMs,
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
            val watchMeta = runCatching { fetchFromWatchPage(cleanId) }.getOrNull()
            val apiKey = watchMeta?.innertubeApiKey
            val visitorData = watchMeta?.visitorData

            val candidates = buildList {
                if (watchMeta != null) add(watchMeta)
                runCatching { fetchFromAndroidVrInnertube(cleanId, apiKey, visitorData) }.getOrNull()?.let(::add)
                runCatching { fetchFromAndroidInnertube(cleanId, apiKey, visitorData) }.getOrNull()?.let(::add)
                if (size < 2) {
                    runCatching { fetchFromEmbedPage(cleanId) }.getOrNull()?.let(::add)
                }
            }

            var emptyDownloadAttempts = 0
            for (meta in candidates) {
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
                        return@withTimeoutOrNull YouTubeCaptionResult(
                            title = bestTitle ?: meta.title,
                            channelName = bestChannelName ?: meta.channelName,
                            description = bestDescription ?: meta.description,
                            durationMs = bestDurationMs ?: meta.durationMs,
                            languageCode = track.languageCode,
                            cues = cues,
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
        )
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
        if (observedBody.isNotBlank()) {
            val directCues = runCatching { parseCaptionPayload(observedBody) }.getOrDefault(emptyList())
            if (directCues.isNotEmpty()) return@withContext directCues
        }
        if (!observedUrl.startsWith("http")) return@withContext emptyList()
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
        val innertubeApiKey: String? = null,
        val visitorData: String? = null,
    )

    private fun fetchFromAndroidVrInnertube(
        videoId: String,
        apiKey: String? = null,
        visitorData: String? = null,
    ): PlayerMetaResult? {
        val body = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  "clientName": "ANDROID_VR",
                  "clientVersion": "1.60.19",
                  "deviceMake": "Oculus",
                  "deviceModel": "Quest 3",
                  "androidSdkVersion": 32,
                  "hl": "en",
                  "gl": "US"
                }
              }
            }
        """.trimIndent()
        return postPlayerEndpoint(
            body = body,
            userAgent = ANDROID_VR_YT_UA,
            clientNameHeader = "28",
            clientVersionHeader = "1.60.19",
            apiKey = apiKey,
            visitorData = visitorData,
        )
    }

    private fun fetchFromAndroidInnertube(
        videoId: String,
        apiKey: String? = null,
        visitorData: String? = null,
    ): PlayerMetaResult? {
        val body = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  "clientName": "ANDROID",
                  "clientVersion": "19.44.38",
                  "androidSdkVersion": 34,
                  "hl": "en",
                  "gl": "US"
                }
              }
            }
        """.trimIndent()
        return postPlayerEndpoint(
            body = body,
            userAgent = ANDROID_YT_UA,
            clientNameHeader = "3",
            clientVersionHeader = "19.44.38",
            apiKey = apiKey,
            visitorData = visitorData,
        )
    }

    private fun fetchFromIosInnertube(
        videoId: String,
        apiKey: String? = null,
        visitorData: String? = null,
    ): PlayerMetaResult? {
        val body = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  "clientName": "IOS",
                  "clientVersion": "19.45.4",
                  "deviceModel": "iPhone16,2",
                  "hl": "en",
                  "gl": "US"
                }
              }
            }
        """.trimIndent()
        return postPlayerEndpoint(
            body = body,
            userAgent = IOS_YT_UA,
            clientNameHeader = "5",
            clientVersionHeader = "19.45.4",
            apiKey = apiKey,
            visitorData = visitorData,
        )
    }

    private fun fetchFromWebInnertube(
        videoId: String,
        apiKey: String? = null,
        visitorData: String? = null,
    ): PlayerMetaResult? {
        val body = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "2.20241120.01.00",
                  "hl": "en",
                  "gl": "US"
                }
              }
            }
        """.trimIndent()
        return postPlayerEndpoint(
            body = body,
            userAgent = DESKTOP_UA,
            clientNameHeader = "1",
            clientVersionHeader = "2.20241120.01.00",
            apiKey = apiKey,
            visitorData = visitorData,
        )
    }

    private fun postPlayerEndpoint(
        body: String,
        userAgent: String,
        clientNameHeader: String,
        clientVersionHeader: String,
        apiKey: String? = null,
        visitorData: String? = null,
    ): PlayerMetaResult? {
        val endpoint = if (!apiKey.isNullOrBlank()) {
            "https://www.youtube.com/youtubei/v1/player?key=$apiKey&prettyPrint=false"
        } else {
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        }
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Origin", "https://www.youtube.com")
            setRequestProperty("X-YouTube-Client-Name", clientNameHeader)
            setRequestProperty("X-YouTube-Client-Version", clientVersionHeader)
            if (!visitorData.isNullOrBlank()) {
                setRequestProperty("X-Goog-Visitor-Id", visitorData)
            }
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val cookies = extractCookieHeader(conn)
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = mapAdapter.fromJson(raw) ?: return null
            extractMetaFromPlayerMap(parsed, cookies, userAgent)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchFromWatchPage(videoId: String): PlayerMetaResult? {
        val conn = (URL("https://www.youtube.com/watch?v=$videoId&hl=en&bpctr=9999999999").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+678")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val cookies = extractCookieHeader(conn)
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val apiKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val visitorData = Regex(""""visitorData":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val jsonObj = extractJsonObjectAfterMarker(html, "ytInitialPlayerResponse = ")
                ?: extractJsonObjectAfterMarker(html, "\"playerResponse\":\"")
            val parsed = jsonObj?.let { mapAdapter.fromJson(it) }
            if (parsed != null) {
                extractMetaFromPlayerMap(
                    root = parsed,
                    cookies = cookies,
                    userAgent = DESKTOP_UA,
                    innertubeApiKey = apiKey,
                    visitorData = visitorData,
                )
            } else {
                PlayerMetaResult(
                    title = null,
                    durationMs = null,
                    tracks = emptyList(),
                    cookies = cookies,
                    preferredUserAgent = DESKTOP_UA,
                    innertubeApiKey = apiKey,
                    visitorData = visitorData,
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
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Referer", "https://www.youtube.com/")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val cookies = extractCookieHeader(conn)
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val apiKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val visitorData = Regex(""""visitorData":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val jsonObj = extractJsonObjectAfterMarker(html, "ytInitialPlayerResponse = ")
                ?: extractJsonObjectAfterMarker(html, "\"embedded_player_response\":\"")
            val parsed = jsonObj?.let { runCatching { mapAdapter.fromJson(it) }.getOrNull() }
            if (parsed != null) {
                extractMetaFromPlayerMap(
                    root = parsed,
                    cookies = cookies,
                    userAgent = DESKTOP_UA,
                    innertubeApiKey = apiKey,
                    visitorData = visitorData,
                )
            } else {
                PlayerMetaResult(
                    title = null,
                    durationMs = null,
                    tracks = emptyList(),
                    cookies = cookies,
                    preferredUserAgent = DESKTOP_UA,
                    innertubeApiKey = apiKey,
                    visitorData = visitorData,
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
        innertubeApiKey: String? = null,
        visitorData: String? = null,
    ): PlayerMetaResult {
        val videoDetails = root["videoDetails"] as? Map<String, Any?>
        val title = (videoDetails?.get("title") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val channelName = (videoDetails?.get("author") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val description = (videoDetails?.get("shortDescription") as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(900)
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
            innertubeApiKey = innertubeApiKey,
            visitorData = visitorData,
        )
    }

    private fun downloadAndParseTrack(
        baseUrl: String,
        cookies: String? = null,
        userAgent: String? = null,
    ): List<RawSubtitleCue> {
        val cleanBase = baseUrl.replace("\\u0026", "&")

        // 1. Always try the exact unmodified signed URL first (preserves live pot= tokens & params)
        val rawBody = httpGetText(cleanBase, cookies, userAgent)
        if (!rawBody.isNullOrBlank()) {
            val parsedRaw = parseCaptionPayload(rawBody)
            if (parsedRaw.isNotEmpty()) return parsedRaw
        }

        val withoutFmt = cleanBase
            .replace(Regex("[?&]fmt=[^&]*"), "")
            .let { url ->
                if (!url.contains('?') && url.contains('&')) url.replaceFirst('&', '?') else url
            }
        val sep = if (withoutFmt.contains('?')) "&" else "?"

        // 2. JSON3
        val json3Body = httpGetText("${withoutFmt}${sep}fmt=json3", cookies, userAgent)
        if (!json3Body.isNullOrBlank()) {
            val parsedJson = parseCaptionPayload(json3Body)
            if (parsedJson.isNotEmpty()) return parsedJson
        }

        // 3. WebVTT
        val vttBody = httpGetText("${withoutFmt}${sep}fmt=vtt", cookies, userAgent)
        if (!vttBody.isNullOrBlank()) {
            val parsedVtt = parseCaptionPayload(vttBody)
            if (parsedVtt.isNotEmpty()) return parsedVtt
        }

        // 4. TimedText XML srv1 (<text start=... dur=...>)
        val srv1Body = httpGetText("${withoutFmt}${sep}fmt=srv1", cookies, userAgent)
        if (!srv1Body.isNullOrBlank()) {
            val parsedSrv1 = parseCaptionPayload(srv1Body)
            if (parsedSrv1.isNotEmpty()) return parsedSrv1
        }
        return emptyList()
    }

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
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DESKTOP_UA)
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
