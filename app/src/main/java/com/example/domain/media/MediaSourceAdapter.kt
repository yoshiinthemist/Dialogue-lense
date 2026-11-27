package com.example.domain.media

import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import java.net.URI
import java.util.Locale

/**
 * Modular cross-platform media provider taxonomy (Section 23).
 * Core DialogueLens engines (dialogue analysis, speaker attribution, speech bubble placement,
 * Heal Beacons, and persistence) operate purely against [MediaCapabilities] and [VideoSource]
 * rather than hardcoding YouTube-specific assumptions.
 */
enum class MediaProviderKind(val label: String) {
    LOCAL_FILE("Local Video File"),
    DIRECT_MEDIA_URL("Direct Video Stream"),
    YOUTUBE_EMBED("YouTube"),
    HTML5_WEB_EMBED("Embedded Web Video"),
    DEMO_SCENARIO("Interactive Demo"),
    UNSUPPORTED_DRM_PLATFORM("DRM-Protected Platform"),
}

/**
 * Capability matrix independently describing what each media source supports and where
 * platform/browser/DRM boundaries require graceful degradation.
 */
data class MediaCapabilities(
    val providerKind: MediaProviderKind,
    val providerName: String,
    val supportsPlayback: Boolean,
    val supportsPlaybackSync: Boolean,
    val supportsNativeCaptions: Boolean,
    val supportsAudioExtraction: Boolean,
    val supportsLiveVisualTracking: Boolean,
    val supportsSeeking: Boolean,
    val supportsSpeechBubbles: Boolean,
    val supportsHealBeacon: Boolean,
    val supportsPersistence: Boolean,
    val isDrmRestricted: Boolean = false,
    val limitationSummary: String = "",
) {
    /**
     * Structured capability rows displayed in Studio Diagnostics so users always see
     * honest, verified platform support rather than misleading promises.
     */
    fun capabilityChecklist(): List<Pair<String, String>> = listOf(
        "Media playback" to if (supportsPlayback) "Supported" else "Unavailable",
        "Playback sync" to if (supportsPlaybackSync) "Supported" else "Unavailable",
        "Caption availability" to if (supportsNativeCaptions) "Embedded / page captions" else "Optional file or AI audio",
        "Audio accessibility" to if (supportsAudioExtraction) "Accessible for analysis" else "Restricted by source",
        "Visual face tracking" to if (supportsLiveVisualTracking) "Active on stage" else "Limited by player sandbox",
        "Seeking & timeline" to if (supportsSeeking) "Full seek control" else "Restricted",
        "Speaker dialogue bubbles" to if (supportsSpeechBubbles) "Supported" else "Unavailable",
        "Heal Beacon repairs" to if (supportsHealBeacon) "Supported" else "Unavailable",
        "Analysis persistence" to if (supportsPersistence) "Saved locally" else "Not saved",
    )
}

/**
 * Result of resolving user-provided media input (URL, local content URI, or demo identifier)
 * through [MediaSourceAdapterRegistry].
 */
data class ResolvedMediaSource(
    val providerKind: MediaProviderKind,
    val providerName: String,
    val normalizedUri: String,
    val videoSourceKind: VideoSourceKind,
    val youtubeVideoId: String = "",
    val suggestedTitle: String = "",
    val capabilities: MediaCapabilities,
    val isSupported: Boolean = !capabilities.isDrmRestricted && capabilities.supportsPlayback,
    val userNotice: String = capabilities.limitationSummary,
) {
    val validYoutubeVideoId: String?
        get() = youtubeVideoId.takeIf {
            providerKind == MediaProviderKind.YOUTUBE_EMBED && YouTubeMediaAdapter.isValidVideoId(it)
        }
}

sealed interface MediaActivationDecision {
    data class Rejected(
        val reason: String,
        val providerName: String = "",
        val isDrm: Boolean = false,
    ) : MediaActivationDecision

    data class YouTube(
        val normalizedUrl: String,
        val videoId: String,
        val resolved: ResolvedMediaSource,
    ) : MediaActivationDecision

    data class DirectStream(
        val normalizedUrl: String,
        val resolved: ResolvedMediaSource,
    ) : MediaActivationDecision

    data class WebStream(
        val normalizedUrl: String,
        val resolved: ResolvedMediaSource,
    ) : MediaActivationDecision

    data class LocalFile(
        val normalizedUri: String,
        val resolved: ResolvedMediaSource,
    ) : MediaActivationDecision

    data class Demo(
        val normalizedUri: String,
        val resolved: ResolvedMediaSource,
    ) : MediaActivationDecision
}

/**
 * Platform-specific media ingestion contract.
 * Adding support for another media provider in the future only requires implementing this
 * interface and registering it in [MediaSourceAdapterRegistry] — without modifying core
 * DialogueLens dialogue, attribution, rendering, or Heal Beacon engines.
 */
interface MediaSourceAdapter {
    val providerKind: MediaProviderKind
    val displayName: String

    fun canHandleInput(rawInput: String): Boolean
    fun resolveInput(rawInput: String): ResolvedMediaSource?
    fun canHandleSource(source: VideoSource): Boolean
    fun capabilitiesFor(source: VideoSource): MediaCapabilities
    fun canonicalCacheKey(source: VideoSource, targetLanguage: String): String
}

internal fun sanitizeCacheLanguageSlug(targetLanguage: String): String =
    targetLanguage.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").ifBlank { "en" }

internal fun stablePositiveHexHash(value: String): String {
    var hash = -0x340d631b // FNV-1a 32-bit offset basis
    for (ch in value) {
        hash = hash xor ch.code
        hash *= 0x01000193
    }
    return java.lang.Integer.toHexString(hash)
}

internal fun parseHostLower(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return runCatching {
        URI(withScheme).host.orEmpty().lowercase(Locale.US).removePrefix("www.").removePrefix("m.")
    }.getOrDefault("")
}

internal fun parsePathLower(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return runCatching { URI(withScheme).path.orEmpty().lowercase(Locale.US) }.getOrDefault("")
}

/**
 * Explicitly identifies DRM-protected streaming services (Widevine/PlayReady/FairPlay) and
 * refuses unsupported playback/extraction honestly while respecting DRM and access controls.
 */
class DrmRestrictedPlatformAdapter : MediaSourceAdapter {
    override val providerKind: MediaProviderKind = MediaProviderKind.UNSUPPORTED_DRM_PLATFORM
    override val displayName: String = "DRM-Protected Streaming Service"

    private val drmDomainNames: Map<String, String> = mapOf(
        "netflix.com" to "Netflix",
        "disneyplus.com" to "Disney+",
        "max.com" to "Max",
        "hbomax.com" to "Max",
        "primevideo.com" to "Prime Video",
        "amazon.com" to "Prime Video",
        "tv.apple.com" to "Apple TV+",
        "hulu.com" to "Hulu",
        "peacocktv.com" to "Peacock",
        "paramountplus.com" to "Paramount+",
        "crunchyroll.com" to "Crunchyroll",
        "espn.com" to "ESPN+",
        "discoveryplus.com" to "Discovery+",
        "starz.com" to "Starz",
        "showtime.com" to "Showtime",
    )

    private fun matchedPlatformName(rawInput: String): String? {
        val host = parseHostLower(rawInput)
        if (host.isBlank()) return null
        if (host == "amazon.com" || host.endsWith(".amazon.com")) {
            val path = parsePathLower(rawInput)
            if (!path.contains("/gp/video") && !path.contains("/detail") && !path.contains("/primevideo")) {
                return null
            }
        }
        return drmDomainNames.entries.firstOrNull { (domain, _) ->
            host == domain || host.endsWith(".$domain")
        }?.value
    }

    override fun canHandleInput(rawInput: String): Boolean = matchedPlatformName(rawInput) != null

    override fun resolveInput(rawInput: String): ResolvedMediaSource? {
        val serviceName = matchedPlatformName(rawInput) ?: return null
        val caps = MediaCapabilities(
            providerKind = MediaProviderKind.UNSUPPORTED_DRM_PLATFORM,
            providerName = serviceName,
            supportsPlayback = false,
            supportsPlaybackSync = false,
            supportsNativeCaptions = false,
            supportsAudioExtraction = false,
            supportsLiveVisualTracking = false,
            supportsSeeking = false,
            supportsSpeechBubbles = false,
            supportsHealBeacon = false,
            supportsPersistence = false,
            isDrmRestricted = true,
            limitationSummary = "$serviceName uses DRM protection and cannot be played or analyzed inside DialogueLens. Try a local video file, direct stream URL (.mp4/.m3u8), YouTube, or an unrestricted HTML5 web video.",
        )
        return ResolvedMediaSource(
            providerKind = MediaProviderKind.UNSUPPORTED_DRM_PLATFORM,
            providerName = serviceName,
            normalizedUri = rawInput.trim(),
            videoSourceKind = VideoSourceKind.WEB_STREAM,
            suggestedTitle = serviceName,
            capabilities = caps,
            isSupported = false,
            userNotice = caps.limitationSummary,
        )
    }

    override fun canHandleSource(source: VideoSource): Boolean =
        source.kind == VideoSourceKind.WEB_STREAM && matchedPlatformName(source.uriString) != null

    override fun capabilitiesFor(source: VideoSource): MediaCapabilities =
        resolveInput(source.uriString)?.capabilities ?: MediaCapabilities(
            providerKind = MediaProviderKind.UNSUPPORTED_DRM_PLATFORM,
            providerName = displayName,
            supportsPlayback = false,
            supportsPlaybackSync = false,
            supportsNativeCaptions = false,
            supportsAudioExtraction = false,
            supportsLiveVisualTracking = false,
            supportsSeeking = false,
            supportsSpeechBubbles = false,
            supportsHealBeacon = false,
            supportsPersistence = false,
            isDrmRestricted = true,
            limitationSummary = "This streaming service uses DRM protection and is not supported.",
        )

    override fun canonicalCacheKey(source: VideoSource, targetLanguage: String): String = ""
}

/**
 * YouTube adapter handling watch, shorts, live, embed, and youtu.be URLs.
 */
class YouTubeMediaAdapter : MediaSourceAdapter {
    override val providerKind: MediaProviderKind = MediaProviderKind.YOUTUBE_EMBED
    override val displayName: String = "YouTube"

    fun extractVideoId(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank() || trimmed.contains(' ')) return null
        val lowerScheme = trimmed.lowercase(Locale.US)
        if (lowerScheme.startsWith("content://") || lowerScheme.startsWith("file://") || lowerScheme.startsWith("demo://")) {
            return null
        }
        val withScheme = if (lowerScheme.startsWith("http://") || lowerScheme.startsWith("https://")) {
            trimmed
        } else if (trimmed.contains('.')) {
            "https://$trimmed"
        } else {
            return null
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host.orEmpty().lowercase(Locale.US)
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("music.")
            .removePrefix("gaming.")
            .removePrefix("kids.")
        val path = uri.path.orEmpty()
        val segments = path.split('/').filter { it.isNotBlank() }

        val candidateId = when {
            host == "youtu.be" -> segments.firstOrNull()
            host == "youtube.com" || host == "youtube-nocookie.com" -> {
                val first = segments.firstOrNull()?.lowercase(Locale.US)
                when (first) {
                    "watch", "", null -> {
                        val queryParamV = uri.rawQuery.orEmpty()
                            .split('&')
                            .firstNotNullOfOrNull { pair ->
                                val idx = pair.indexOf('=')
                                if (idx > 0 && pair.substring(0, idx).equals("v", ignoreCase = true)) {
                                    pair.substring(idx + 1)
                                } else {
                                    null
                                }
                            }
                        queryParamV ?: if (first == "watch") segments.getOrNull(1) else null
                    }
                    "embed", "shorts", "live", "v" -> segments.getOrNull(1)
                    else -> null
                }
            }
            else -> null
        }
        return candidateId?.takeIf { isValidVideoId(it) }
    }

    companion object {
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

        fun isValidVideoId(candidate: String?): Boolean {
            val clean = candidate?.trim().orEmpty()
            return clean.length == 11 && VIDEO_ID_REGEX.matches(clean)
        }
    }

    override fun canHandleInput(rawInput: String): Boolean = extractVideoId(rawInput) != null

    override fun resolveInput(rawInput: String): ResolvedMediaSource? {
        val id = extractVideoId(rawInput) ?: return null
        val normalized = "https://www.youtube.com/watch?v=$id"
        val caps = defaultCapabilities()
        return ResolvedMediaSource(
            providerKind = MediaProviderKind.YOUTUBE_EMBED,
            providerName = displayName,
            normalizedUri = normalized,
            videoSourceKind = VideoSourceKind.YOUTUBE_COMPLIANT,
            youtubeVideoId = id,
            suggestedTitle = "YouTube Video ($id)",
            capabilities = caps,
            isSupported = true,
            userNotice = caps.limitationSummary,
        )
    }

    override fun canHandleSource(source: VideoSource): Boolean =
        source.kind == VideoSourceKind.YOUTUBE_COMPLIANT

    override fun capabilitiesFor(source: VideoSource): MediaCapabilities = defaultCapabilities()

    override fun canonicalCacheKey(source: VideoSource, targetLanguage: String): String {
        val cleanId = (source.youtubeVideoId.orEmpty().ifBlank { extractVideoId(source.uriString).orEmpty() })
            .replace(Regex("[^0-9A-Za-z_-]"), "")
        val langSlug = sanitizeCacheLanguageSlug(targetLanguage)
        return if (cleanId.isNotBlank()) "yt_${cleanId}_$langSlug" else "yt_${stablePositiveHexHash(source.uriString)}_$langSlug"
    }

    private fun defaultCapabilities() = MediaCapabilities(
        providerKind = MediaProviderKind.YOUTUBE_EMBED,
        providerName = displayName,
        supportsPlayback = true,
        supportsPlaybackSync = true,
        supportsNativeCaptions = true,
        supportsAudioExtraction = true,
        supportsLiveVisualTracking = true,
        supportsSeeking = true,
        supportsSpeechBubbles = true,
        supportsHealBeacon = true,
        supportsPersistence = true,
        isDrmRestricted = false,
        limitationSummary = "Uses existing captions when available, or AI dialogue analysis when captions are missing.",
    )
}

/**
 * Direct media stream adapter (.mp4, .webm, .mkv, .m4v, .mov, .m3u8 HLS, .mpd DASH).
 * Plays natively via Media3 ExoPlayer for exact playback sync, clean seeking,
 * live visual face tracking, and direct audio-slice Heal Beacon repair.
 */
class DirectMediaUrlAdapter : MediaSourceAdapter {
    override val providerKind: MediaProviderKind = MediaProviderKind.DIRECT_MEDIA_URL
    override val displayName: String = "Direct Video Stream"

    private val directMediaExtensions = setOf(
        ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".m3u8", ".mpd", ".ogv", ".ts",
    )

    private fun isDirectMediaUrl(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return false
        val path = parsePathLower(trimmed)
        if (directMediaExtensions.any { path.endsWith(it) }) return true
        // Also recognize signed CDN query patterns like ?format=mp4 or mime=video/mp4
        val lower = trimmed.lowercase(Locale.US)
        return lower.contains("mime=video%2f") || lower.contains("mime=video/")
    }

    override fun canHandleInput(rawInput: String): Boolean = isDirectMediaUrl(rawInput)

    override fun resolveInput(rawInput: String): ResolvedMediaSource? {
        if (!isDirectMediaUrl(rawInput)) return null
        val clean = rawInput.trim()
        val host = parseHostLower(clean).ifBlank { "Stream" }
        val fileName = parsePathLower(clean).substringAfterLast('/').substringBefore('?')
            .ifBlank { host }
        val caps = defaultCapabilities(host)
        return ResolvedMediaSource(
            providerKind = MediaProviderKind.DIRECT_MEDIA_URL,
            providerName = displayName,
            normalizedUri = clean,
            videoSourceKind = VideoSourceKind.LOCAL_MP4,
            suggestedTitle = fileName,
            capabilities = caps,
            isSupported = true,
            userNotice = caps.limitationSummary,
        )
    }

    override fun canHandleSource(source: VideoSource): Boolean =
        source.kind == VideoSourceKind.LOCAL_MP4 &&
            (source.uriString.startsWith("http://", true) || source.uriString.startsWith("https://", true))

    override fun capabilitiesFor(source: VideoSource): MediaCapabilities =
        defaultCapabilities(parseHostLower(source.uriString).ifBlank { "Direct Stream" })

    override fun canonicalCacheKey(source: VideoSource, targetLanguage: String): String {
        val baseUrl = source.uriString.substringBefore('?').trim().lowercase(Locale.US)
        val langSlug = sanitizeCacheLanguageSlug(targetLanguage)
        return "direct_${stablePositiveHexHash(baseUrl)}_$langSlug"
    }

    private fun defaultCapabilities(host: String) = MediaCapabilities(
        providerKind = MediaProviderKind.DIRECT_MEDIA_URL,
        providerName = "$displayName ($host)",
        supportsPlayback = true,
        supportsPlaybackSync = true,
        supportsNativeCaptions = false,
        supportsAudioExtraction = true,
        supportsLiveVisualTracking = true,
        supportsSeeking = true,
        supportsSpeechBubbles = true,
        supportsHealBeacon = true,
        supportsPersistence = true,
        isDrmRestricted = false,
        limitationSummary = "Plays natively in ExoPlayer with full sync, seeking, visual tracking, and Heal Beacon audio repair.",
    )
}

/**
 * Local device media adapter (content://, file://, Android Photo Picker / SAF videos).
 */
class LocalFileMediaAdapter : MediaSourceAdapter {
    override val providerKind: MediaProviderKind = MediaProviderKind.LOCAL_FILE
    override val displayName: String = "Local Video File"

    override fun canHandleInput(rawInput: String): Boolean {
        val lower = rawInput.trim().lowercase(Locale.US)
        return lower.startsWith("content://") || lower.startsWith("file://")
    }

    override fun resolveInput(rawInput: String): ResolvedMediaSource? {
        if (!canHandleInput(rawInput)) return null
        val clean = rawInput.trim()
        val caps = defaultCapabilities()
        return ResolvedMediaSource(
            providerKind = MediaProviderKind.LOCAL_FILE,
            providerName = displayName,
            normalizedUri = clean,
            videoSourceKind = VideoSourceKind.LOCAL_MP4,
            suggestedTitle = clean.substringAfterLast('/').ifBlank { "Local Video" },
            capabilities = caps,
            isSupported = true,
            userNotice = caps.limitationSummary,
        )
    }

    override fun canHandleSource(source: VideoSource): Boolean =
        source.kind == VideoSourceKind.LOCAL_MP4 &&
            !source.uriString.startsWith("http://", true) &&
            !source.uriString.startsWith("https://", true)

    override fun capabilitiesFor(source: VideoSource): MediaCapabilities = defaultCapabilities()

    override fun canonicalCacheKey(source: VideoSource, targetLanguage: String): String {
        val identity = "${source.displayName.trim().lowercase(Locale.US)}|${source.durationMs}|${source.uriString.take(120)}"
        val langSlug = sanitizeCacheLanguageSlug(targetLanguage)
        return "local_${stablePositiveHexHash(identity)}_$langSlug"
    }

    private fun defaultCapabilities() = MediaCapabilities(
        providerKind = MediaProviderKind.LOCAL_FILE,
        providerName = displayName,
        supportsPlayback = true,
        supportsPlaybackSync = true,
        supportsNativeCaptions = false,
        supportsAudioExtraction = true,
        supportsLiveVisualTracking = true,
        supportsSeeking = true,
        supportsSpeechBubbles = true,
        supportsHealBeacon = true,
        supportsPersistence = true,
        isDrmRestricted = false,
        limitationSummary = "Full offline playback, exact sync, local face tracking, optional subtitle import, and localized Heal Beacon repair.",
    )
}

/**
 * Generic HTML5 `<video>` web page adapter (Vimeo, Dailymotion, educational sites, indie streams).
 * Honestly documents browser sandbox / cross-origin audio limitations and gracefully degrades
 * to page captions, user-attached SRT/VTT, or captured stream audio when permitted.
 */
class Html5WebEmbedAdapter : MediaSourceAdapter {
    override val providerKind: MediaProviderKind = MediaProviderKind.HTML5_WEB_EMBED
    override val displayName: String = "Embedded Web Video"

    private fun normalizeWebUrl(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank() || trimmed.contains(' ')) return null
        val candidate = when {
            trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true) -> trimmed
            trimmed.contains('.') && !trimmed.startsWith("content://", true) && !trimmed.startsWith("file://", true) ->
                "https://$trimmed"
            else -> return null
        }
        val host = parseHostLower(candidate)
        return if (host.contains('.') && host.length >= 4) candidate else null
    }

    override fun canHandleInput(rawInput: String): Boolean = normalizeWebUrl(rawInput) != null

    override fun resolveInput(rawInput: String): ResolvedMediaSource? {
        val normalized = normalizeWebUrl(rawInput) ?: return null
        val host = parseHostLower(normalized)
        val caps = defaultCapabilities(host)
        return ResolvedMediaSource(
            providerKind = MediaProviderKind.HTML5_WEB_EMBED,
            providerName = "$displayName ($host)",
            normalizedUri = normalized,
            videoSourceKind = VideoSourceKind.WEB_STREAM,
            suggestedTitle = host,
            capabilities = caps,
            isSupported = true,
            userNotice = caps.limitationSummary,
        )
    }

    override fun canHandleSource(source: VideoSource): Boolean =
        source.kind == VideoSourceKind.WEB_STREAM

    override fun capabilitiesFor(source: VideoSource): MediaCapabilities {
        val host = parseHostLower(source.uriString).ifBlank { "Web Video" }
        return defaultCapabilities(host)
    }

    override fun canonicalCacheKey(source: VideoSource, targetLanguage: String): String {
        val cleanUrl = source.uriString.substringBefore('#').trim().lowercase(Locale.US)
        val langSlug = sanitizeCacheLanguageSlug(targetLanguage)
        return "web_${stablePositiveHexHash(cleanUrl)}_$langSlug"
    }

    private fun defaultCapabilities(host: String) = MediaCapabilities(
        providerKind = MediaProviderKind.HTML5_WEB_EMBED,
        providerName = "$displayName ($host)",
        supportsPlayback = true,
        supportsPlaybackSync = true,
        supportsNativeCaptions = true,
        supportsAudioExtraction = false, // May upgrade dynamically if a non-DRM audio stream URL is permitted
        supportsLiveVisualTracking = true,
        supportsSeeking = true,
        supportsSpeechBubbles = true,
        supportsHealBeacon = true,
        supportsPersistence = true,
        isDrmRestricted = false,
        limitationSummary = "Plays embedded HTML5 video. Uses page captions or attached subtitles; some sites restrict cross-origin audio analysis.",
    )
}

/**
 * Built-in offline interactive demo adapter.
 */
class DemoScenarioMediaAdapter : MediaSourceAdapter {
    override val providerKind: MediaProviderKind = MediaProviderKind.DEMO_SCENARIO
    override val displayName: String = "Interactive Demo"

    override fun canHandleInput(rawInput: String): Boolean =
        rawInput.trim().startsWith("demo://", ignoreCase = true)

    override fun resolveInput(rawInput: String): ResolvedMediaSource? {
        if (!canHandleInput(rawInput)) return null
        val caps = defaultCapabilities()
        return ResolvedMediaSource(
            providerKind = MediaProviderKind.DEMO_SCENARIO,
            providerName = displayName,
            normalizedUri = rawInput.trim(),
            videoSourceKind = VideoSourceKind.DEMO_SCENARIO,
            suggestedTitle = "Interactive Demo",
            capabilities = caps,
            isSupported = true,
            userNotice = caps.limitationSummary,
        )
    }

    override fun canHandleSource(source: VideoSource): Boolean =
        source.kind == VideoSourceKind.DEMO_SCENARIO

    override fun capabilitiesFor(source: VideoSource): MediaCapabilities = defaultCapabilities()

    override fun canonicalCacheKey(source: VideoSource, targetLanguage: String): String =
        "demo_${stablePositiveHexHash(source.uriString)}_${sanitizeCacheLanguageSlug(targetLanguage)}"

    private fun defaultCapabilities() = MediaCapabilities(
        providerKind = MediaProviderKind.DEMO_SCENARIO,
        providerName = displayName,
        supportsPlayback = true,
        supportsPlaybackSync = true,
        supportsNativeCaptions = true,
        supportsAudioExtraction = false,
        supportsLiveVisualTracking = true,
        supportsSeeking = true,
        supportsSpeechBubbles = true,
        supportsHealBeacon = true,
        supportsPersistence = true,
        isDrmRestricted = false,
        limitationSummary = "Built-in offline demonstration showcasing speaker bubbles, camera cuts, and Heal Beacons.",
    )
}

/**
 * Central registry decoupling DialogueLens core engines from platform-specific media adapters.
 * Order matters: DRM checks run first so protected services are never misrouted into a broken WebView,
 * followed by YouTube, Direct Media URLs (ExoPlayer), Local Files, Demo, and finally generic HTML5 Web embeds.
 */
class MediaSourceAdapterRegistry(
    private val adapters: List<MediaSourceAdapter> = defaultAdapters(),
) {
    fun resolveInput(rawInput: String): ResolvedMediaSource? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null
        for (adapter in adapters) {
            if (adapter.canHandleInput(trimmed)) {
                adapter.resolveInput(trimmed)?.let { return it }
            }
        }
        return null
    }

    fun extractValidYouTubeId(rawInput: String): String? {
        val resolved = resolveInput(rawInput) ?: return null
        return resolved.validYoutubeVideoId
    }

    fun resolveForActivation(rawInput: String): MediaActivationDecision {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return MediaActivationDecision.Rejected("Enter a valid video URL or local media path.")
        }
        val resolved = resolveInput(trimmed)
            ?: return MediaActivationDecision.Rejected("Enter a valid YouTube, direct stream (.mp4/.m3u8), or http(s) web video link.")
        if (!resolved.isSupported) {
            return MediaActivationDecision.Rejected(
                reason = resolved.userNotice.ifBlank { "${resolved.providerName} is not supported." },
                providerName = resolved.providerName,
                isDrm = resolved.capabilities.isDrmRestricted,
            )
        }
        return when (resolved.providerKind) {
            MediaProviderKind.YOUTUBE_EMBED -> {
                val vid = resolved.validYoutubeVideoId
                    ?: return MediaActivationDecision.Rejected("Could not extract a valid YouTube video ID from that link.")
                MediaActivationDecision.YouTube(
                    normalizedUrl = resolved.normalizedUri,
                    videoId = vid,
                    resolved = resolved,
                )
            }
            MediaProviderKind.DIRECT_MEDIA_URL -> MediaActivationDecision.DirectStream(
                normalizedUrl = resolved.normalizedUri,
                resolved = resolved,
            )
            MediaProviderKind.HTML5_WEB_EMBED -> MediaActivationDecision.WebStream(
                normalizedUrl = resolved.normalizedUri,
                resolved = resolved,
            )
            MediaProviderKind.LOCAL_FILE -> MediaActivationDecision.LocalFile(
                normalizedUri = resolved.normalizedUri,
                resolved = resolved,
            )
            MediaProviderKind.DEMO_SCENARIO -> MediaActivationDecision.Demo(
                normalizedUri = resolved.normalizedUri,
                resolved = resolved,
            )
            MediaProviderKind.UNSUPPORTED_DRM_PLATFORM -> MediaActivationDecision.Rejected(
                reason = resolved.userNotice,
                providerName = resolved.providerName,
                isDrm = true,
            )
        }
    }

    fun adapterFor(source: VideoSource): MediaSourceAdapter {
        return adapters.firstOrNull { it.canHandleSource(source) } ?: Html5WebEmbedAdapter()
    }

    fun capabilitiesFor(source: VideoSource, hasDiscoveredDirectAudio: Boolean = false): MediaCapabilities {
        val base = adapterFor(source).capabilitiesFor(source)
        return if (base.providerKind == MediaProviderKind.HTML5_WEB_EMBED && hasDiscoveredDirectAudio) {
            base.copy(
                supportsAudioExtraction = true,
                limitationSummary = "Embedded HTML5 video with accessible audio stream for dialogue and Heal Beacon analysis.",
            )
        } else {
            base
        }
    }

    fun canonicalCacheKey(source: VideoSource, targetLanguage: String): String =
        adapterFor(source).canonicalCacheKey(source, targetLanguage)

    fun withAdditionalAdapter(adapter: MediaSourceAdapter): MediaSourceAdapterRegistry =
        MediaSourceAdapterRegistry(listOf(adapter) + adapters)

    companion object {
        val default: MediaSourceAdapterRegistry by lazy { MediaSourceAdapterRegistry() }

        fun defaultAdapters(): List<MediaSourceAdapter> = listOf(
            DrmRestrictedPlatformAdapter(),
            YouTubeMediaAdapter(),
            DirectMediaUrlAdapter(),
            LocalFileMediaAdapter(),
            DemoScenarioMediaAdapter(),
            Html5WebEmbedAdapter(),
        )
    }
}
