package com.example.domain.analysis

import java.net.URI
import java.util.Locale

enum class WebNavAction {
    BACK,
    FORWARD,
    REFRESH,
    RESET_TO_SOURCE,
    LOAD_URL,
    ALLOW_TEMP_HOST,
}

data class WebNavCommand(
    val seq: Int,
    val action: WebNavAction,
    val targetUrl: String = "",
    val host: String = "",
)

data class WebNavigationState(
    val currentUrl: String = "",
    val currentHost: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val hasActiveVideo: Boolean = false,
    val isAdRedirectSuspected: Boolean = false,
    val blockedRedirectCount: Int = 0,
    val lastBlockedUrl: String? = null,
    val lastBlockedHost: String? = null,
    val isLoading: Boolean = false,
)

data class SniffedWebMediaStream(
    val url: String,
    val referer: String = "",
    val cookieHeader: String = "",
    val userAgent: String = "",
    val origin: String = "",
    val isHlsPlaylist: Boolean = false,
) {
    fun toRequestHeaders(): Map<String, String> = buildMap {
        if (userAgent.isNotBlank()) put("User-Agent", userAgent)
        if (referer.isNotBlank()) put("Referer", referer)
        if (origin.isNotBlank()) put("Origin", origin)
        if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
    }
}

sealed interface RedirectDecision {
    data object Allow : RedirectDecision
    data class Block(
        val targetUrl: String,
        val host: String,
        val reason: String,
    ) : RedirectDecision
}

/**
 * Pure navigation & anti-popunder policy engine for Web Cinema viewer and Scout browsers.
 * Stops pop-up tabs, external intent/market hijacks, and cross-site ad/cam redirects while
 * preserving same-site navigation and explicit temporary user overrides.
 */
object WebNavigationGuard {

    private val TWO_LABEL_TLDS = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "co.jp", "ne.jp", "or.jp", "ac.jp",
        "com.au", "net.au", "org.au",
        "com.br", "com.mx", "com.tw", "co.kr", "com.cn",
        "com.sg", "com.hk", "co.nz", "co.in",
    )

    private val KNOWN_AD_HOST_TOKENS = listOf(
        "stripchat", "chaturbate", "bongacams", "livejasmin", "cam4", "myfreecams",
        "camsoda", "jerkmate", "flirt4free", "exoclick", "exosrv", "trafficjunky", "juicyads",
        "popads", "popcash", "propellerads", "adsterra", "hilltopads", "clickadu",
        "onclicka", "realsrv", "magsrv", "tsyndicate", "betteradsystem", "doubleclick",
        "googlesyndication", "adnxs", "outbrain", "taboola", "mgid", "adtng",
        "ero-advertising", "plugrush", "trafficfactory", "clickadilla", "a-ads",
        "admaven", "monetag", "acscdn", "highperformanceformat", "topcreativeformat",
        "xlivrdr", "xlirdr", "strpjmp", "mnaspm", "mmcdn", "sacdnssedge",
        "pushame", "pushwhy", "al5sm", "bnhtml", "trackwilltrk", "adxprts",
        "popunderjs", "go2affise", "voluum", "adglare", "adserver",
    )

    private val LIKELY_VIDEO_HOST_TOKENS = listOf(
        "cloudflarestream", "videodelivery", "jwplatform", "jwpcdn", "vimeocdn",
        "vimeo", "dailymotion", "streamtape", "dood", "mixdrop", "filemoon",
        "bunnycdn", "b-cdn", "cloudfront", "akamaized", "fastly", "mcloud",
        "vidstream", "surrit", "sixyik", "mycdn", "hls", "player.", "embed.",
    )

    fun extractHost(url: String?): String {
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) return ""
        return runCatching {
            val host = URI(clean).host?.lowercase(Locale.US).orEmpty()
            host.removePrefix("www.")
        }.getOrElse { "" }
    }

    fun registrableDomain(rawHost: String): String {
        val host = rawHost.trim().lowercase(Locale.US).removePrefix("www.").trimEnd('.')
        if (host.isEmpty()) return ""
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
        return if (lastTwo in TWO_LABEL_TLDS && parts.size >= 3) {
            "${parts[parts.size - 3]}.$lastTwo"
        } else {
            lastTwo
        }
    }

    fun isKnownAdOrPromoHost(host: String, url: String = ""): Boolean {
        val lowerHost = host.trim().lowercase(Locale.US)
        val lowerUrl = url.trim().lowercase(Locale.US)
        if (lowerHost.isNotEmpty() && KNOWN_AD_HOST_TOKENS.any { lowerHost.contains(it) }) {
            return true
        }
        if (lowerUrl.contains("utm_source=pop") ||
            lowerUrl.contains("popunder") ||
            lowerUrl.contains("/aff?") ||
            lowerUrl.contains("creative_id=") && lowerUrl.contains("zone_id=")
        ) {
            return true
        }
        return false
    }

    fun isLikelyVideoPlayerHost(host: String): Boolean {
        val lower = host.trim().lowercase(Locale.US)
        if (lower.isEmpty()) return false
        return LIKELY_VIDEO_HOST_TOKENS.any { lower.contains(it) }
    }

    fun evaluateTopFrameNavigation(
        seedUrl: String,
        targetUrl: String,
        isRedirect: Boolean = false,
        hasUserGesture: Boolean = false,
        temporarilyAllowedHosts: Set<String> = emptySet(),
    ): RedirectDecision {
        val cleanTarget = targetUrl.trim()
        if (cleanTarget.isEmpty() || cleanTarget.equals("about:blank", ignoreCase = true)) {
            return RedirectDecision.Allow
        }

        val lowerScheme = cleanTarget.substringBefore(':').lowercase(Locale.US)
        if (lowerScheme != "http" && lowerScheme != "https") {
            return RedirectDecision.Block(
                targetUrl = cleanTarget,
                host = lowerScheme,
                reason = "Blocked external app launch ($lowerScheme:)",
            )
        }

        val targetHost = extractHost(cleanTarget)
        if (targetHost.isEmpty()) {
            return RedirectDecision.Block(
                targetUrl = cleanTarget,
                host = "unknown",
                reason = "Blocked invalid redirect URL",
            )
        }

        val targetDomain = registrableDomain(targetHost)
        val normalizedAllowed = temporarilyAllowedHosts.map { it.lowercase(Locale.US) }.toSet()
        if (targetHost in normalizedAllowed || targetDomain in normalizedAllowed) {
            return RedirectDecision.Allow
        }

        if (isKnownAdOrPromoHost(targetHost, cleanTarget)) {
            return RedirectDecision.Block(
                targetUrl = cleanTarget,
                host = targetHost,
                reason = "Blocked ad redirect ($targetHost)",
            )
        }

        val seedHost = extractHost(seedUrl)
        val seedDomain = registrableDomain(seedHost)
        if (seedDomain.isEmpty() || targetDomain == seedDomain) {
            return RedirectDecision.Allow
        }

        // Cross-site top-frame redirect/popup: block by default even on click so transparent overlay traps cannot hijack playback
        val reason = when {
            isRedirect -> "Blocked cross-site redirect ($targetHost)"
            hasUserGesture -> "Blocked pop-up navigation to $targetHost"
            else -> "Blocked background redirect to $targetHost"
        }
        return RedirectDecision.Block(
            targetUrl = cleanTarget,
            host = targetHost,
            reason = reason,
        )
    }

    fun evaluateSubframeRequest(
        seedUrl: String,
        subframeUrl: String,
        temporarilyAllowedHosts: Set<String> = emptySet(),
        strictScoutSubframes: Boolean = false,
    ): RedirectDecision {
        val clean = subframeUrl.trim()
        if (clean.isEmpty() || clean.equals("about:blank", ignoreCase = true)) {
            return RedirectDecision.Allow
        }
        val lowerScheme = clean.substringBefore(':').lowercase(Locale.US)
        if (lowerScheme == "data" || lowerScheme == "blob") {
            return RedirectDecision.Allow
        }
        if (lowerScheme != "http" && lowerScheme != "https") {
            return RedirectDecision.Block(clean, lowerScheme, "Blocked subframe external scheme")
        }
        val host = extractHost(clean)
        if (host.isEmpty()) {
            return RedirectDecision.Block(clean, "unknown", "Blocked invalid subframe URL")
        }
        val domain = registrableDomain(host)
        val normalizedAllowed = temporarilyAllowedHosts.map { it.lowercase(Locale.US) }.toSet()
        if (host in normalizedAllowed || domain in normalizedAllowed) {
            return RedirectDecision.Allow
        }
        if (isKnownAdOrPromoHost(host, clean)) {
            return RedirectDecision.Block(clean, host, "Blocked ad frame ($host)")
        }
        // Allow same-domain iframes & video player embeds automatically
        val seedDomain = registrableDomain(extractHost(seedUrl))
        if ((seedDomain.isNotEmpty() && domain == seedDomain) || isLikelyVideoPlayerHost(host)) {
            return RedirectDecision.Allow
        }
        if (strictScoutSubframes) {
            return RedirectDecision.Block(clean, host, "Blocked cross-site Scout frame ($host)")
        }
        return RedirectDecision.Allow
    }

    /**
     * Enforces strict domain isolation on YouTube iframe and mobile watch WebViews (both Scout & Viewer)
     * so external redirects, ad schemes, and off-YouTube navigations are blocked before loading.
     */
    fun evaluateYouTubeNavigation(
        targetUrl: String,
        appOriginHost: String = "",
        temporarilyAllowedHosts: Set<String> = emptySet(),
    ): RedirectDecision {
        val clean = targetUrl.trim()
        if (clean.isEmpty() || clean.equals("about:blank", ignoreCase = true)) {
            return RedirectDecision.Allow
        }
        val lowerScheme = clean.substringBefore(':').lowercase(Locale.US)
        if (lowerScheme == "data" || lowerScheme == "blob") {
            return RedirectDecision.Allow
        }
        if (lowerScheme != "http" && lowerScheme != "https") {
            return RedirectDecision.Block(clean, lowerScheme, "Blocked external app redirect ($lowerScheme:)")
        }
        val host = extractHost(clean)
        if (host.isEmpty()) {
            return RedirectDecision.Block(clean, "unknown", "Blocked invalid YouTube redirect")
        }
        val domain = registrableDomain(host)
        val normalizedAllowed = temporarilyAllowedHosts.map { it.lowercase(Locale.US) }.toSet()
        if (host in normalizedAllowed || domain in normalizedAllowed) {
            return RedirectDecision.Allow
        }
        if (appOriginHost.isNotBlank() && host.equals(appOriginHost.trim().lowercase(Locale.US), ignoreCase = true)) {
            return RedirectDecision.Allow
        }
        if (isKnownAdOrPromoHost(host, clean)) {
            return RedirectDecision.Block(clean, host, "Blocked YouTube ad redirect ($host)")
        }
        val trustedYouTubeDomains = setOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "googlevideo.com",
            "ytimg.com",
            "ggpht.com",
            "google.com",
            "gstatic.com",
            "googleapis.com",
        )
        return if (domain in trustedYouTubeDomains) {
            RedirectDecision.Allow
        } else {
            RedirectDecision.Block(clean, host, "Blocked cross-site YouTube redirect ($host)")
        }
    }

    /**
     * Evaluates third-party script/frame/beacon subresources before Chromium loads them.
     * Drops known popunder/cam/ad-network scripts so click-hijackers never execute.
     */
    fun shouldBlockSubresource(
        seedUrl: String,
        resourceUrl: String,
        temporarilyAllowedHosts: Set<String> = emptySet(),
    ): Boolean {
        val clean = resourceUrl.trim()
        if (clean.isEmpty() ||
            clean.startsWith("data:", ignoreCase = true) ||
            clean.startsWith("blob:", ignoreCase = true)
        ) {
            return false
        }
        val host = extractHost(clean)
        if (host.isEmpty()) return false
        val domain = registrableDomain(host)
        val normalizedAllowed = temporarilyAllowedHosts.map { it.lowercase(Locale.US) }.toSet()
        if (host in normalizedAllowed || domain in normalizedAllowed) {
            return false
        }
        val seedDomain = registrableDomain(extractHost(seedUrl))
        if (seedDomain.isNotEmpty() && domain == seedDomain) {
            return false
        }
        if (isLikelyVideoPlayerHost(host)) {
            return false
        }
        return isKnownAdOrPromoHost(host, clean)
    }

    fun isLikelyRedirectorUrl(url: String?): Boolean {
        val clean = url?.trim()?.lowercase(Locale.US).orEmpty()
        if (clean.isEmpty()) return false
        val host = extractHost(clean)
        if (isKnownAdOrPromoHost(host, clean)) return true
        return clean.contains("/go/") ||
            clean.contains("/out/") ||
            clean.contains("/aff/") ||
            clean.contains("/redirect") ||
            clean.contains("?aff=") ||
            clean.contains("&aff=")
    }

    fun shouldAutoSyncScoutFromViewer(
        viewerNav: WebNavigationState,
        scoutNav: WebNavigationState,
        scoutPosMs: Long,
        viewerPosMs: Long,
        lastAutoSyncAtMs: Long,
        nowMs: Long,
        cooldownMs: Long = 9_000L,
    ): Boolean {
        if (nowMs - lastAutoSyncAtMs < cooldownMs) return false
        val viewerHasWorkingVideo = (viewerNav.hasActiveVideo || viewerPosMs >= 1_500L) &&
            !viewerNav.isAdRedirectSuspected &&
            viewerNav.currentUrl.startsWith("http", ignoreCase = true)
        if (!viewerHasWorkingVideo) return false

        val scoutStuckOrOnAd = scoutNav.isAdRedirectSuspected ||
            (!scoutNav.hasActiveVideo && scoutPosMs <= 500L)
        if (!scoutStuckOrOnAd) return false

        // Sync if Scout landed on an ad/different page or has stalled without <video> while Viewer works
        return scoutNav.isAdRedirectSuspected ||
            scoutNav.currentUrl.isBlank() ||
            scoutNav.currentUrl != viewerNav.currentUrl ||
            scoutPosMs <= 500L
    }

    fun isHlsPlaylistUrl(url: String?): Boolean {
        val clean = url?.trim()?.lowercase(Locale.US).orEmpty()
        if (!clean.startsWith("http")) return false
        val path = clean.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8") ||
            clean.contains(".m3u8?") ||
            clean.contains(".m3u8&") ||
            clean.contains("format=m3u8") ||
            clean.contains("mime=application/vnd.apple.mpegurl")
    }

    fun isLikelyPlayableMediaUrl(
        url: String?,
        acceptHeader: String = "",
        secFetchDest: String = "",
    ): Boolean {
        val clean = url?.trim().orEmpty()
        if (!clean.startsWith("http", ignoreCase = true)) return false
        val lower = clean.lowercase(Locale.US)
        val path = lower.substringBefore('?').substringBefore('#')
        val host = extractHost(clean)
        if (host.isEmpty() || isKnownAdOrPromoHost(host, lower)) return false

        // Ignore thumbnails, posters, tiny previews, scripts, stylesheets, and captions
        val ignoredSuffixes = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
            ".css", ".js", ".mjs", ".woff", ".woff2", ".ttf",
            ".vtt", ".srt", ".ass", ".json", ".xml", ".html", ".php",
            ".ts", ".m4s", ".aac", // individual HLS chunks; prefer .m3u8 master/media playlist
        )
        if (ignoredSuffixes.any { path.endsWith(it) }) return false
        if (lower.contains("/thumb") ||
            lower.contains("preview") ||
            lower.contains("sprite") ||
            lower.contains("poster") ||
            lower.contains("teaser") ||
            lower.contains("beacon")
        ) {
            return false
        }

        if (isHlsPlaylistUrl(clean)) return true
        val progressiveExts = listOf(".mp4", ".webm", ".m4a", ".mov", ".m4v", ".mp3", ".ogg")
        if (progressiveExts.any { path.endsWith(it) || lower.contains("$it?") || lower.contains("$it&") }) {
            return true
        }
        val destLow = secFetchDest.trim().lowercase(Locale.US)
        if (destLow == "video" || destLow == "audio") return true
        val acceptLow = acceptHeader.trim().lowercase(Locale.US)
        if (acceptLow.contains("video/") || acceptLow.contains("audio/")) return true
        return lower.contains("mime=video") ||
            lower.contains("mime=audio") ||
            lower.contains("mime_type=video") ||
            (lower.contains("/video/") && (lower.contains("token=") || lower.contains("ttl=") || lower.contains("hash=")))
    }
}
