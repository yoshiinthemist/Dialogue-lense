package com.example.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.ScoutCaptureDiagnostics
import com.example.data.model.ScoutCaptureHealthState
import com.example.domain.analysis.RedirectDecision
import com.example.domain.analysis.SniffedWebMediaStream
import com.example.domain.analysis.WebNavAction
import com.example.domain.analysis.WebNavCommand
import com.example.domain.analysis.WebNavigationGuard
import com.example.domain.analysis.WebNavigationState
import org.json.JSONObject

/**
 * Holds a single live WebView session so an instance (such as the background Scout WebView)
 * can be seamlessly reparented between a hidden/PiP viewport and an interactive full-width modal
 * without dropping cookies, page history, <video> buffers, or audio/caption hooks.
 */
@Stable
class WebStreamSessionHolder {
    internal var webView: WebView? = null
    internal var loadedSeedUrl: String? = null
    internal var bridgeReady by mutableStateOf(false)
    internal var lastHandledPlaySeq: Int = 0
    internal var lastHandledSeekSeq: Int = 0
    internal var lastHandledNavSeq: Int = 0
    internal var isolateVideoInViewport: Boolean = true
    internal var lastSniffedMediaUrl: String = ""
    internal var lastSniffedHeaderCount: Int = 0

    internal val temporarilyAllowedHosts = mutableMapOf<String, Long>()
    internal var navState: WebNavigationState = WebNavigationState()
    internal var lastCleanVideoUrl: String = ""
    internal var lastBounceAtMs: Long = 0L
    internal var lastEarlyInjectAtMs: Long = 0L

    internal var playbackCallback: (Long, Long, Boolean) -> Unit = { _, _, _ -> }
    internal var captionsCallback: (String) -> Unit = {}
    internal var mediaUrlCallback: (String) -> Unit = {}
    internal var sniffedMediaCallback: (SniffedWebMediaStream) -> Unit = {}
    internal var audioSliceCallback: (Long, String, String, Long, Boolean) -> Unit = { _, _, _, _, _ -> }
    internal var captureDiagnosticsCallback: (ScoutCaptureDiagnostics) -> Unit = {}
    internal var metadataCallback: (String, String, String) -> Unit = { _, _, _ -> }
    internal var navStateCallback: (WebNavigationState) -> Unit = {}

    fun activeAllowedHostsSnapshot(nowMs: Long = SystemClock.elapsedRealtime()): Set<String> {
        temporarilyAllowedHosts.entries.removeAll { it.value < nowMs }
        return temporarilyAllowedHosts.keys.toSet()
    }

    fun allowHostTemporarily(rawHost: String, durationMs: Long = 25_000L) {
        val clean = rawHost.trim().lowercase()
        if (clean.isEmpty()) return
        val expiry = SystemClock.elapsedRealtime() + durationMs
        temporarilyAllowedHosts[clean] = expiry
        val reg = WebNavigationGuard.registrableDomain(clean)
        if (reg.isNotEmpty()) {
            temporarilyAllowedHosts[reg] = expiry
        }
        syncAllowedHostsToJs()
    }

    internal fun syncAllowedHostsToJs() {
        val wv = webView ?: return
        if (!bridgeReady) return
        val snapshot = activeAllowedHostsSnapshot()
        val jsonArr = org.json.JSONArray(snapshot.toList()).toString()
        wv.evaluateJavascript("window.dlUpdateAllowedHosts && window.dlUpdateAllowedHosts($jsonArr);", null)
    }

    fun dispose() {
        webView?.apply {
            (parent as? ViewGroup)?.removeView(this)
            removeJavascriptInterface("DialogueLensWeb")
            stopLoading()
            destroy()
        }
        webView = null
        loadedSeedUrl = null
        bridgeReady = false
        temporarilyAllowedHosts.clear()
        navState = WebNavigationState()
    }
}

/**
 * In-app Web Cinema browser for non-YouTube web video pages shared from Chrome or pasted by URL.
 * Blocks pop-up windows and cross-site ad redirects, strips transparent click-hijack overlays,
 * hooks HTML5 <video> transport controls & TextTracks, and reports live navigation telemetry.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebStreamPlayer(
    webUrl: String,
    isMuted: Boolean,
    playbackSpeed: Float = 1.0f,
    playRequest: Pair<Int, Boolean>? = null,
    seekRequest: Pair<Int, Long>?,
    onPlaybackState: (positionMs: Long, durationMs: Long, isPlaying: Boolean) -> Unit,
    onCapturedCaptions: (cuesJson: String) -> Unit,
    isScoutInstance: Boolean = false,
    isolateVideoInViewport: Boolean = true,
    onDirectMediaUrl: (String) -> Unit = {},
    onSniffedMediaStream: (SniffedWebMediaStream) -> Unit = {},
    onCapturedAudioSlice: (startMs: Long, base64Audio: String, mimeType: String, endMs: Long, discontinuity: Boolean) -> Unit = { _, _, _, _, _ -> },
    onCaptureDiagnostics: (ScoutCaptureDiagnostics) -> Unit = {},
    onPageMetadata: (title: String, author: String, description: String) -> Unit = { _, _, _ -> },
    navCommand: WebNavCommand? = null,
    onNavigationStateChange: (WebNavigationState) -> Unit = {},
    sessionHolder: WebStreamSessionHolder? = null,
    autoDisposeOnLeave: Boolean = sessionHolder == null,
    modifier: Modifier = Modifier,
) {
    val ownedHolder = remember { if (sessionHolder == null) WebStreamSessionHolder() else null }
    val holder = sessionHolder ?: ownedHolder!!

    val latestSeedUrl by rememberUpdatedState(webUrl)
    holder.isolateVideoInViewport = isolateVideoInViewport
    holder.playbackCallback = onPlaybackState
    holder.captionsCallback = onCapturedCaptions
    holder.mediaUrlCallback = onDirectMediaUrl
    holder.sniffedMediaCallback = onSniffedMediaStream
    holder.audioSliceCallback = onCapturedAudioSlice
    holder.captureDiagnosticsCallback = onCaptureDiagnostics
    holder.metadataCallback = onPageMetadata
    holder.navStateCallback = onNavigationStateChange

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val effectiveMuted = isMuted || isScoutInstance

    if (autoDisposeOnLeave) {
        DisposableEffect(holder) {
            onDispose {
                holder.dispose()
            }
        }
    }

    LaunchedEffect(isolateVideoInViewport, holder.bridgeReady) {
        if (!holder.bridgeReady) return@LaunchedEffect
        val flag = if (isolateVideoInViewport) "true" else "false"
        holder.webView?.evaluateJavascript("window.dlSetVideoIsolation && window.dlSetVideoIsolation($flag);", null)
    }

    LaunchedEffect(playRequest, holder.bridgeReady) {
        val req = playRequest ?: return@LaunchedEffect
        if (!holder.bridgeReady || req.first == holder.lastHandledPlaySeq) return@LaunchedEffect
        holder.lastHandledPlaySeq = req.first
        val js = if (req.second) "window.dlWebPlay && window.dlWebPlay();" else "window.dlWebPause && window.dlWebPause();"
        holder.webView?.evaluateJavascript(js, null)
    }

    LaunchedEffect(effectiveMuted, holder.bridgeReady) {
        if (!holder.bridgeReady) return@LaunchedEffect
        val js = if (effectiveMuted) "window.dlWebMute && window.dlWebMute();" else "window.dlWebUnmute && window.dlWebUnmute();"
        holder.webView?.evaluateJavascript(js, null)
    }

    LaunchedEffect(playbackSpeed, holder.bridgeReady) {
        if (!holder.bridgeReady) return@LaunchedEffect
        holder.webView?.evaluateJavascript("window.dlWebRate && window.dlWebRate($playbackSpeed);", null)
    }

    LaunchedEffect(seekRequest, holder.bridgeReady) {
        val req = seekRequest ?: return@LaunchedEffect
        if (!holder.bridgeReady || req.first == holder.lastHandledSeekSeq) return@LaunchedEffect
        holder.lastHandledSeekSeq = req.first
        val seconds = req.second / 1000.0
        holder.webView?.evaluateJavascript("window.dlWebSeek && window.dlWebSeek($seconds);", null)
    }

    LaunchedEffect(navCommand) {
        val cmd = navCommand ?: return@LaunchedEffect
        if (cmd.seq == holder.lastHandledNavSeq) return@LaunchedEffect
        holder.lastHandledNavSeq = cmd.seq
        val wv = holder.webView ?: return@LaunchedEffect
        when (cmd.action) {
            WebNavAction.BACK -> if (wv.canGoBack()) wv.goBack()
            WebNavAction.FORWARD -> if (wv.canGoForward()) wv.goForward()
            WebNavAction.REFRESH -> wv.reload()
            WebNavAction.RESET_TO_SOURCE -> {
                val seed = latestSeedUrl.trim()
                if (seed.isNotBlank()) wv.loadUrl(seed)
            }
            WebNavAction.LOAD_URL -> {
                val target = cmd.targetUrl.trim()
                if (target.isNotBlank()) {
                    holder.allowHostTemporarily(WebNavigationGuard.extractHost(target), durationMs = 20_000L)
                    wv.loadUrl(target)
                }
            }
            WebNavAction.ALLOW_TEMP_HOST -> {
                val targetHost = cmd.host.ifBlank { WebNavigationGuard.extractHost(cmd.targetUrl) }
                holder.allowHostTemporarily(targetHost, durationMs = 25_000L)
                val target = cmd.targetUrl.trim()
                if (target.isNotBlank()) {
                    wv.loadUrl(target)
                }
            }
        }
        val next = holder.navState.copy(
            canGoBack = wv.canGoBack(),
            canGoForward = wv.canGoForward(),
        )
        holder.navState = next
        holder.navStateCallback(next)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val container = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val webView = holder.webView ?: createGuardedWebView(
                context = ctx,
                holder = holder,
                isScoutInstance = isScoutInstance,
                seedUrlProvider = { latestSeedUrl },
                mainHandler = mainHandler,
            ).also { holder.webView = it }

            attachWebViewToContainer(webView, container)
            container
        },
        update = { container ->
            val webView = holder.webView ?: return@AndroidView
            attachWebViewToContainer(webView, container)
            if (holder.bridgeReady) {
                val flag = if (isolateVideoInViewport) "true" else "false"
                webView.evaluateJavascript("window.dlSetVideoIsolation && window.dlSetVideoIsolation($flag);", null)
            }
            if (holder.loadedSeedUrl != webUrl && webUrl.isNotBlank()) {
                holder.loadedSeedUrl = webUrl
                holder.lastSniffedMediaUrl = ""
                holder.lastSniffedHeaderCount = 0
                holder.bridgeReady = false
                holder.navState = WebNavigationState(
                    currentUrl = webUrl,
                    currentHost = WebNavigationGuard.extractHost(webUrl),
                    isLoading = true,
                )
                holder.navStateCallback(holder.navState)
                webView.loadUrl(webUrl)
            }
        },
    )
}

private fun attachWebViewToContainer(webView: WebView, container: FrameLayout) {
    val existingParent = webView.parent as? ViewGroup
    if (existingParent !== container) {
        existingParent?.removeView(webView)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createGuardedWebView(
    context: Context,
    holder: WebStreamSessionHolder,
    isScoutInstance: Boolean,
    seedUrlProvider: () -> String,
    mainHandler: Handler,
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        isFocusable = true
        isFocusableInTouchMode = true
        setOnTouchListener { v, _ ->
            if (!v.hasFocus()) v.requestFocus()
            false
        }
        // Normal hardware window rendering prevents black-frame FBO glitches on HTML5 <video>
        setLayerType(View.LAYER_TYPE_NONE, null)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // SupportMultipleWindows=true + onCreateWindow returning false hard-blocks all popup tabs
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun updateNavState(transform: (WebNavigationState) -> WebNavigationState) {
            val next = transform(holder.navState)
            if (next != holder.navState) {
                holder.navState = next
                holder.navStateCallback(next)
            }
        }

        fun isAuthorizedPageOrigin(pageUrl: String): Boolean {
            val seedDomain = WebNavigationGuard.registrableDomain(
                WebNavigationGuard.extractHost(seedUrlProvider())
            )
            val curHost = WebNavigationGuard.extractHost(pageUrl)
            val curDomain = WebNavigationGuard.registrableDomain(curHost)
            val allowedNow = holder.activeAllowedHostsSnapshot()
            if (seedDomain.isEmpty()) return curDomain.isNotEmpty()
            return curDomain.isNotEmpty() && (
                curDomain == seedDomain ||
                    allowedNow.any { curHost == it || curHost.endsWith(".$it") }
                )
        }

        val bridge = object {
            @JavascriptInterface
            fun onProgress(posMs: Long, durMs: Long, playing: Boolean) {
                if (posMs < 0L || durMs < 0L) return
                mainHandler.post { holder.playbackCallback(posMs, durMs, playing) }
            }

            @JavascriptInterface
            fun onCapturedTracks(cuesJson: String) {
                if (cuesJson.isBlank() || cuesJson.length > 512_000) return
                mainHandler.post {
                    val pageUrl = url.orEmpty().ifBlank { holder.navState.currentUrl }
                    if (!isAuthorizedPageOrigin(pageUrl)) return@post
                    holder.captionsCallback(cuesJson)
                }
            }

            @JavascriptInterface
            fun onDirectMediaUrl(mediaUrl: String) {
                val clean = mediaUrl.trim()
                if (clean.length <= 2048 &&
                    clean.startsWith("http", ignoreCase = true) &&
                    WebNavigationGuard.isLikelyPlayableMediaUrl(clean)
                ) {
                    mainHandler.post {
                        val pageUrl = holder.navState.currentUrl.ifBlank { url.orEmpty() }.ifBlank { seedUrlProvider() }
                        if (!isAuthorizedPageOrigin(pageUrl)) return@post
                        holder.mediaUrlCallback(clean)
                        val origin = runCatching {
                            val u = java.net.URI(pageUrl)
                            if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else ""
                        }.getOrDefault("")
                        val cookie = runCatching { CookieManager.getInstance().getCookie(clean) }.getOrNull().orEmpty()
                        val sniffed = SniffedWebMediaStream(
                            url = clean,
                            referer = pageUrl,
                            cookieHeader = cookie,
                            userAgent = settings.userAgentString.orEmpty(),
                            origin = origin,
                            isHlsPlaylist = WebNavigationGuard.isHlsPlaylistUrl(clean),
                        )
                        val headersCount = sniffed.toRequestHeaders().size
                        val isNewUrl = !clean.equals(holder.lastSniffedMediaUrl, ignoreCase = true)
                        if (isNewUrl || headersCount > holder.lastSniffedHeaderCount) {
                            holder.lastSniffedMediaUrl = clean
                            holder.lastSniffedHeaderCount = headersCount
                            holder.sniffedMediaCallback(sniffed)
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onCapturedAudioChunk(
                startMs: Long,
                base64Audio: String,
                mimeType: String,
                endMs: Long,
                discontinuity: Boolean,
            ) {
                if (startMs < 0L) return
                val cleanMime = mimeType.trim().lowercase().substringBefore(';')
                if (cleanMime !in ALLOWED_AUDIO_MIME_TYPES) return
                if (base64Audio.length !in 64..6_000_000) return
                val safeEnd = endMs.coerceAtLeast(startMs + 250L)
                mainHandler.post {
                    val currentPageUrl = url.orEmpty().ifBlank { holder.navState.currentUrl }
                    if (!isAuthorizedPageOrigin(currentPageUrl)) return@post
                    holder.audioSliceCallback(startMs.coerceAtLeast(0L), base64Audio, cleanMime, safeEnd, discontinuity)
                }
            }

            @JavascriptInterface
            fun onCaptureDiagnostics(diagJson: String) {
                if (diagJson.isBlank() || diagJson.length > 8192) return
                mainHandler.post {
                    val parsed = runCatching {
                        val obj = JSONObject(diagJson)
                        val stateName = obj.optString("healthState", "WAITING_FOR_MEDIA")
                        val health = ScoutCaptureHealthState.entries.firstOrNull { it.name == stateName }
                            ?: ScoutCaptureHealthState.WAITING_FOR_MEDIA
                        ScoutCaptureDiagnostics(
                            healthState = health,
                            selectedElementId = obj.optString("selectedElementId", ""),
                            documentGeneration = obj.optLong("documentGeneration", 0L),
                            recorderId = obj.optString("recorderId", ""),
                            outputMuted = obj.optBoolean("outputMuted", true),
                            outputVolumeZero = obj.optBoolean("outputVolumeZero", true),
                            trackReadyState = obj.optString("trackReadyState", ""),
                            trackMuted = obj.optBoolean("trackMuted", false),
                            trackEnabled = obj.optBoolean("trackEnabled", false),
                            lastMimeType = obj.optString("lastMimeType", ""),
                            lastSliceStartMs = obj.optLong("lastSliceStartMs", -1L),
                            lastSliceEndMs = obj.optLong("lastSliceEndMs", -1L),
                            droppedGapCount = obj.optInt("droppedGapCount", 0),
                            diagnosticDetail = obj.optString("diagnosticDetail", ""),
                        )
                    }.getOrNull()
                    if (parsed != null) {
                        holder.captureDiagnosticsCallback(parsed)
                    }
                }
            }

            @JavascriptInterface
            fun onPageMetadata(title: String, author: String, description: String) {
                if (title.isNotBlank() || author.isNotBlank() || description.isNotBlank()) {
                    mainHandler.post {
                        val pageUrl = url.orEmpty().ifBlank { holder.navState.currentUrl }
                        if (!isAuthorizedPageOrigin(pageUrl)) return@post
                        holder.metadataCallback(title.take(180), author.take(120), description.take(1_200))
                    }
                }
            }

            @JavascriptInterface
            fun onPopupBlocked(rawUrl: String) {
                mainHandler.post {
                    val clean = rawUrl.trim()
                    val host = WebNavigationGuard.extractHost(clean).ifBlank { "popup" }
                    updateNavState { cur ->
                        cur.copy(
                            blockedRedirectCount = cur.blockedRedirectCount + 1,
                            lastBlockedUrl = clean.takeIf { it.startsWith("http", ignoreCase = true) } ?: cur.lastBlockedUrl,
                            lastBlockedHost = host,
                        )
                    }
                }
            }

            @JavascriptInterface
            fun onNavHeartbeat(href: String, hasVideo: Boolean, suspectedAdDom: Boolean) {
                mainHandler.post {
                    val host = WebNavigationGuard.extractHost(href)
                    val seedDomain = WebNavigationGuard.registrableDomain(
                        WebNavigationGuard.extractHost(seedUrlProvider())
                    )
                    val curDomain = WebNavigationGuard.registrableDomain(host)
                    val allowedNow = holder.activeAllowedHostsSnapshot()
                    val offDomainWithoutAllow = seedDomain.isNotEmpty() &&
                        curDomain.isNotEmpty() &&
                        curDomain != seedDomain &&
                        host !in allowedNow &&
                        curDomain !in allowedNow
                    val adRedirect = suspectedAdDom ||
                        WebNavigationGuard.isKnownAdOrPromoHost(host, href) ||
                        (!hasVideo && offDomainWithoutAllow)
                    updateNavState { cur ->
                        cur.copy(
                            currentUrl = href.ifBlank { cur.currentUrl },
                            currentHost = host.ifBlank { cur.currentHost },
                            canGoBack = canGoBack(),
                            canGoForward = canGoForward(),
                            hasActiveVideo = hasVideo,
                            isAdRedirectSuspected = adRedirect,
                        )
                    }
                }
            }
        }
        addJavascriptInterface(bridge, "DialogueLensWeb")

        webChromeClient = object : WebChromeClient() {
            override fun getDefaultVideoPoster(): Bitmap {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val hitExtra = view?.hitTestResult?.extra?.takeIf { it.startsWith("http", ignoreCase = true) }
                val host = WebNavigationGuard.extractHost(hitExtra).ifBlank { "pop-up" }
                mainHandler.post {
                    updateNavState { cur ->
                        cur.copy(
                            blockedRedirectCount = cur.blockedRedirectCount + 1,
                            lastBlockedUrl = hitExtra ?: cur.lastBlockedUrl,
                            lastBlockedHost = host,
                        )
                    }
                }
                return false
            }
        }

        fun recoverFromBlockedTopFrame(view: WebView?, blockedUrl: String, blockedHost: String) {
            if (view == null) return
            val seed = seedUrlProvider().trim()
            val seedDomain = WebNavigationGuard.registrableDomain(WebNavigationGuard.extractHost(seed))
            val nowMs = SystemClock.elapsedRealtime()
            val shouldBounce = (nowMs - holder.lastBounceAtMs) > 650L
            if (shouldBounce) {
                holder.lastBounceAtMs = nowMs
                view.stopLoading()
                val history = runCatching { view.copyBackForwardList() }.getOrNull()
                var safeHistoryUrl: String? = null
                if (history != null && history.currentIndex > 0) {
                    for (idx in (history.currentIndex - 1) downTo 0) {
                        val candidate = history.getItemAtIndex(idx)?.url.orEmpty()
                        val candHost = WebNavigationGuard.extractHost(candidate)
                        val candDomain = WebNavigationGuard.registrableDomain(candHost)
                        if (candidate.startsWith("http", ignoreCase = true) &&
                            (seedDomain.isEmpty() || candDomain == seedDomain) &&
                            !WebNavigationGuard.isLikelyRedirectorUrl(candidate) &&
                            !WebNavigationGuard.isKnownAdOrPromoHost(candHost, candidate)
                        ) {
                            safeHistoryUrl = candidate
                            break
                        }
                    }
                }
                val fallbackClean = safeHistoryUrl
                    ?: holder.lastCleanVideoUrl.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: seed
                if (fallbackClean.isNotBlank()) {
                    view.loadUrl(fallbackClean)
                }
            }
            mainHandler.post {
                updateNavState { cur ->
                    cur.copy(
                        blockedRedirectCount = cur.blockedRedirectCount + 1,
                        lastBlockedUrl = blockedUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: cur.lastBlockedUrl,
                        lastBlockedHost = blockedHost.ifBlank { "redirect" },
                        isAdRedirectSuspected = false,
                        isLoading = false,
                    )
                }
            }
        }

        fun injectGuardScript(view: WebView?) {
            if (view == null) return
            val seedDomain = WebNavigationGuard.registrableDomain(
                WebNavigationGuard.extractHost(seedUrlProvider())
            )
            view.evaluateJavascript(
                buildWebStreamHookScript(
                    isScoutInstance = isScoutInstance,
                    seedDomain = seedDomain,
                    initialIsolate = holder.isolateVideoInViewport,
                    initialAllowedHosts = holder.activeAllowedHostsSnapshot(),
                ),
                null,
            )
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val targetUrl = request?.url?.toString().orEmpty()
                if (targetUrl.isBlank()) {
                    return super.shouldInterceptRequest(view, request)
                }
                val allowed = holder.activeAllowedHostsSnapshot()
                val seed = seedUrlProvider().trim()
                if (request?.isForMainFrame == true) {
                    if (!targetUrl.equals(seed, ignoreCase = true)) {
                        val decision = WebNavigationGuard.evaluateTopFrameNavigation(
                            seedUrl = seed,
                            targetUrl = targetUrl,
                            isRedirect = true,
                            hasUserGesture = request.hasGesture(),
                            temporarilyAllowedHosts = allowed,
                        )
                        if (decision is RedirectDecision.Block) {
                            mainHandler.post {
                                recoverFromBlockedTopFrame(view, decision.targetUrl, decision.host)
                            }
                            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                val reqHeaders = request?.requestHeaders.orEmpty()
                val isSubframeDest = reqHeaders["Sec-Fetch-Dest"].equals("iframe", ignoreCase = true)
                if (WebNavigationGuard.shouldBlockSubresource(seed, targetUrl, allowed) ||
                    (isScoutInstance && isSubframeDest &&
                        WebNavigationGuard.evaluateSubframeRequest(
                            seedUrl = seed,
                            subframeUrl = targetUrl,
                            temporarilyAllowedHosts = allowed,
                            strictScoutSubframes = true,
                        ) is RedirectDecision.Block)
                ) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                // Sniff real progressive or HLS media streams + HTTP headers for silent lookahead audio decoding
                val acceptHeader = reqHeaders["Accept"].orEmpty()
                val fetchDest = reqHeaders["Sec-Fetch-Dest"].orEmpty()
                if (WebNavigationGuard.isLikelyPlayableMediaUrl(targetUrl, acceptHeader, fetchDest)) {
                    val isHls = WebNavigationGuard.isHlsPlaylistUrl(targetUrl)
                    val pageReferer = reqHeaders["Referer"]
                        ?.takeIf { it.isNotBlank() }
                        ?: holder.navState.currentUrl.ifBlank { seed }
                    val reqOrigin = reqHeaders["Origin"].orEmpty().ifBlank {
                        runCatching {
                            val u = java.net.URI(pageReferer)
                            if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else ""
                        }.getOrDefault("")
                    }
                    val reqUa = reqHeaders["User-Agent"]?.takeIf { it.isNotBlank() }
                        ?: settings.userAgentString.orEmpty()
                    val cookie = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull().orEmpty()
                    val sniffed = SniffedWebMediaStream(
                        url = targetUrl,
                        referer = pageReferer,
                        cookieHeader = cookie,
                        userAgent = reqUa,
                        origin = reqOrigin,
                        isHlsPlaylist = isHls,
                    )
                    val headersCount = sniffed.toRequestHeaders().size
                    val isSameUrl = targetUrl.equals(holder.lastSniffedMediaUrl, ignoreCase = true)
                    val shouldEmit = holder.lastSniffedMediaUrl.isBlank() ||
                        (isHls && !WebNavigationGuard.isHlsPlaylistUrl(holder.lastSniffedMediaUrl)) ||
                        (!isSameUrl && isHls && targetUrl.contains("master", ignoreCase = true)) ||
                        (isSameUrl && headersCount > holder.lastSniffedHeaderCount)
                    if (shouldEmit) {
                        holder.lastSniffedMediaUrl = targetUrl
                        holder.lastSniffedHeaderCount = headersCount
                        mainHandler.post {
                            holder.mediaUrlCallback(targetUrl)
                            holder.sniffedMediaCallback(sniffed)
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val targetUrl = request?.url?.toString().orEmpty()
                if (targetUrl.isBlank()) return false
                val allowed = holder.activeAllowedHostsSnapshot()
                val decision = if (request?.isForMainFrame != false) {
                    WebNavigationGuard.evaluateTopFrameNavigation(
                        seedUrl = seedUrlProvider(),
                        targetUrl = targetUrl,
                        isRedirect = request?.isRedirect == true,
                        hasUserGesture = request?.hasGesture() == true,
                        temporarilyAllowedHosts = allowed,
                    )
                } else {
                    WebNavigationGuard.evaluateSubframeRequest(
                        seedUrl = seedUrlProvider(),
                        subframeUrl = targetUrl,
                        temporarilyAllowedHosts = allowed,
                        strictScoutSubframes = isScoutInstance,
                    )
                }
                if (decision is RedirectDecision.Block) {
                    if (request?.isForMainFrame != false) {
                        mainHandler.post {
                            updateNavState { cur ->
                                cur.copy(
                                    blockedRedirectCount = cur.blockedRedirectCount + 1,
                                    lastBlockedUrl = decision.targetUrl,
                                    lastBlockedHost = decision.host,
                                )
                            }
                        }
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val cleanUrl = url.orEmpty()
                val host = WebNavigationGuard.extractHost(cleanUrl)
                val allowed = holder.activeAllowedHostsSnapshot()
                val decision = WebNavigationGuard.evaluateTopFrameNavigation(
                    seedUrl = seedUrlProvider(),
                    targetUrl = cleanUrl,
                    isRedirect = true,
                    hasUserGesture = false,
                    temporarilyAllowedHosts = allowed,
                )
                if (decision is RedirectDecision.Block) {
                    recoverFromBlockedTopFrame(view, decision.targetUrl, decision.host)
                    return
                }
                if (cleanUrl.startsWith("http", ignoreCase = true) && !WebNavigationGuard.isLikelyRedirectorUrl(cleanUrl)) {
                    holder.lastCleanVideoUrl = cleanUrl
                }
                // Inject anti-popup & overlay guard immediately on page start so early Play taps are already protected
                injectGuardScript(view)
                val adHost = WebNavigationGuard.isKnownAdOrPromoHost(host, cleanUrl)
                mainHandler.post {
                    updateNavState { cur ->
                        cur.copy(
                            currentUrl = cleanUrl.ifBlank { cur.currentUrl },
                            currentHost = host.ifBlank { cur.currentHost },
                            canGoBack = view?.canGoBack() == true,
                            canGoForward = view?.canGoForward() == true,
                            isLoading = true,
                            isAdRedirectSuspected = adHost,
                        )
                    }
                }
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                val now = SystemClock.elapsedRealtime()
                if (!holder.bridgeReady && now - holder.lastEarlyInjectAtMs > 550L) {
                    holder.lastEarlyInjectAtMs = now
                    injectGuardScript(view)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val cleanUrl = url.orEmpty()
                val host = WebNavigationGuard.extractHost(cleanUrl)
                val decision = WebNavigationGuard.evaluateTopFrameNavigation(
                    seedUrl = seedUrlProvider(),
                    targetUrl = cleanUrl,
                    isRedirect = true,
                    hasUserGesture = false,
                    temporarilyAllowedHosts = holder.activeAllowedHostsSnapshot(),
                )
                if (decision is RedirectDecision.Block) {
                    recoverFromBlockedTopFrame(view, decision.targetUrl, decision.host)
                    return
                }
                if (cleanUrl.startsWith("http", ignoreCase = true) && !WebNavigationGuard.isLikelyRedirectorUrl(cleanUrl)) {
                    holder.lastCleanVideoUrl = cleanUrl
                }
                mainHandler.post {
                    updateNavState { cur ->
                        cur.copy(
                            currentUrl = cleanUrl.ifBlank { cur.currentUrl },
                            currentHost = host.ifBlank { cur.currentHost },
                            canGoBack = view?.canGoBack() == true,
                            canGoForward = view?.canGoForward() == true,
                        )
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectGuardScript(view)
                holder.bridgeReady = true
                val cleanUrl = url.orEmpty()
                val host = WebNavigationGuard.extractHost(cleanUrl)
                mainHandler.post {
                    updateNavState { cur ->
                        cur.copy(
                            currentUrl = cleanUrl.ifBlank { cur.currentUrl },
                            currentHost = host.ifBlank { cur.currentHost },
                            canGoBack = view?.canGoBack() == true,
                            canGoForward = view?.canGoForward() == true,
                            isLoading = false,
                        )
                    }
                }
            }
        }
    }
}

private fun buildWebStreamHookScript(
    isScoutInstance: Boolean,
    seedDomain: String,
    initialIsolate: Boolean,
    initialAllowedHosts: Set<String> = emptySet(),
): String {
    val safeSeedDomain = seedDomain.replace("'", "")
    val allowedJson = org.json.JSONArray(initialAllowedHosts.toList()).toString()
    return """
(function() {
  var isScout = ${if (isScoutInstance) "true" else "false"};
  var seedDomain = '$safeSeedDomain';
  window.__dlAllowedHosts = $allowedJson;
  window.dlUpdateAllowedHosts = function(hosts) {
    if (Array.isArray(hosts)) {
      window.__dlAllowedHosts = hosts.map(function(x) { return String(x || '').toLowerCase(); });
    }
  };
  window.__dlIsolateVideo = ${if (initialIsolate) "true" else "false"};
  if (window.__dlWebHookInstalled) {
    if (window.dlSetVideoIsolation) window.dlSetVideoIsolation(window.__dlIsolateVideo);
    if (window.__dlScanTracks) window.__dlScanTracks();
    return;
  }
  window.__dlWebHookInstalled = true;
  window.__dlHookVersion = 4;
  window.__dlLastTrackSig = '';
  window.__dlLastDirectSrc = '';
  window.__dlLastMetaSig = '';
  window.__dlLastNavSig = '';
  window.__dlPlayGraceUntil = 0;
  window.__dlElemSeq = 0;
  window.__dlDocGeneration = (window.__dlDocGeneration || 0) + 1;
  window.__dlSelectedElementId = '';
  window.__dlLastDiagKey = '';
  window.__dlCapState = window.__dlCapState || {
    active: false,
    rec: null,
    ownedTracks: [],
    timerId: null,
    recSeq: 0,
    elementId: '',
    sliceStartMs: 0,
    seekDirty: false,
    droppedGapCount: 0,
    discontinuityNext: false,
    onSeeked: null,
    onEmptied: null,
    onEnded: null,
    onPause: null,
    video: null
  };
  window.__dlIsolatedTarget = null;

  function notifyPopupBlocked(url) {
    try {
      if (window.DialogueLensWeb && window.DialogueLensWeb.onPopupBlocked) {
        window.DialogueLensWeb.onPopupBlocked(String(url || 'popup'));
      }
    } catch (e) {}
  }

  function reportCandidateStreamUrl(rawUrl) {
    if (!rawUrl || !window.DialogueLensWeb || !window.DialogueLensWeb.onDirectMediaUrl) return;
    var s = String(rawUrl).trim();
    if (s.indexOf('http') !== 0) return;
    var low = s.toLowerCase();
    if (low.indexOf('.m3u8') !== -1 || low.indexOf('.mp4') !== -1 || low.indexOf('.webm') !== -1 ||
        low.indexOf('.m4a') !== -1 || low.indexOf('mime=video') !== -1 || low.indexOf('mime=audio') !== -1) {
      if (low.indexOf('preview') === -1 && low.indexOf('thumb') === -1 && low.indexOf('sprite') === -1) {
        try { window.DialogueLensWeb.onDirectMediaUrl(s); } catch (e) {}
      }
    }
  }

  // Passively observe JS network requests for HLS .m3u8 or progressive MP4 CDN streams
  try {
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function(input) {
        try {
          var u = typeof input === 'string' ? input : (input && input.url ? input.url : '');
          reportCandidateStreamUrl(u);
        } catch (e) {}
        return origFetch.apply(this, arguments);
      };
    }
  } catch (e) {}
  try {
    var origXhrOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      try { reportCandidateStreamUrl(url); } catch (e) {}
      return origXhrOpen.apply(this, arguments);
    };
  } catch (e) {}

  // Neutralize JS popunders and window.open ad tabs immediately
  try {
    window.open = function(u) {
      notifyPopupBlocked(u);
      return null;
    };
  } catch (e) {}

  function isOffsiteAdHref(rawHref) {
    if (!rawHref) return false;
    var low = String(rawHref).trim().toLowerCase();
    if (!low || low.indexOf('#') === 0 || low.indexOf('javascript:') === 0) return false;
    if (low.indexOf('http') !== 0 && low.indexOf('//') !== 0) return false;
    try {
      var u = new URL(rawHref, location.href);
      var h = (u.hostname || '').toLowerCase().replace(/^www\./, '');
      var allowedList = window.__dlAllowedHosts || [];
      for (var i = 0; i < allowedList.length; i++) {
        var ah = allowedList[i];
        if (ah && (h === ah || h.endsWith('.' + ah))) return false;
      }
      if (seedDomain && h !== seedDomain && h.indexOf('.' + seedDomain) === -1) {
        return true;
      }
    } catch (e) {}
    return false;
  }

  function neutralizeClickShields(video) {
    if (!video) return;
    try {
      var vRect = video.getBoundingClientRect();
      if (!vRect || vRect.width < 60 || vRect.height < 40) return;
      var els = document.querySelectorAll('a, div, ins, iframe');
      for (var i = 0; i < Math.min(els.length, 100); i++) {
        var el = els[i];
        if (!el || el === video || el.contains(video) || video.contains(el)) continue;
        var st = window.getComputedStyle(el);
        if (!st || st.display === 'none' || st.visibility === 'hidden' || st.pointerEvents === 'none') continue;
        var r = el.getBoundingClientRect();
        var coversVideo = r.width >= vRect.width * 0.72 && r.height >= vRect.height * 0.72 &&
          r.left <= vRect.left + 24 && r.top <= vRect.top + 24;
        if (coversVideo) {
          var z = parseInt(st.zIndex || '0', 10) || 0;
          var op = parseFloat(st.opacity || '1');
          var emptyOverlay = ((el.innerText || '').trim().length === 0) && (op < 0.25 || st.backgroundColor === 'rgba(0, 0, 0, 0)' || st.backgroundColor === 'transparent');
          var externalLink = el.tagName === 'A' && isOffsiteAdHref(el.getAttribute('href'));
          if ((emptyOverlay && z >= 2) || externalLink) {
            el.dataset.dlShieldBlocked = '1';
            el.style.pointerEvents = 'none';
            if (externalLink) el.style.display = 'none';
          }
        }
      }
    } catch (e) {}
  }

  function hidePromoOverlays(primaryVideo) {
    try {
      var candidates = document.querySelectorAll('div, aside, section, ins');
      for (var i = 0; i < Math.min(candidates.length, 120); i++) {
        var el = candidates[i];
        if (!el || (primaryVideo && (el === primaryVideo || el.contains(primaryVideo) || primaryVideo.contains(el)))) continue;
        if (el.dataset && el.dataset.dlPromoHidden === '1') continue;
        var txt = ((el.innerText || '') + ' ' + (el.className || '') + ' ' + (el.id || '')).toLowerCase().slice(0, 500);
        var isCamPromo = txt.indexOf('stripchat') >= 0 ||
          txt.indexOf('chaturbate') >= 0 ||
          txt.indexOf('faphouse') >= 0 ||
          txt.indexOf('mega sale') >= 0 ||
          (txt.indexOf('create free account') >= 0 && txt.indexOf('models') >= 0) ||
          (txt.indexOf('join free') >= 0 && txt.indexOf('private shows') >= 0);
        if (isCamPromo) {
          el.dataset.dlPromoHidden = '1';
          el.style.setProperty('display', 'none', 'important');
        }
      }
    } catch (e) {}
  }

  function injectBaseStyles() {
    if (document.getElementById('dl-hide-native-captions')) return;
    var style = document.createElement('style');
    style.id = 'dl-hide-native-captions';
    style.textContent =
      'video::-webkit-media-text-track-display { display: none !important; } ' +
      'video::cue { opacity: 0 !important; color: transparent !important; background: transparent !important; } ' +
      'html.dl-isolated-root, body.dl-isolated-root { overflow: hidden !important; background: #000 !important; margin: 0 !important; padding: 0 !important; } ' +
      '.dl-isolated-hidden { visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; } ' +
      '.dl-isolated-ancestor { overflow: visible !important; transform: none !important; clip-path: none !important; filter: none !important; z-index: auto !important; } ' +
      '.dl-isolated-stage { position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; ' +
      'width: 100vw !important; height: 100vh !important; min-width: 100vw !important; min-height: 100vh !important; ' +
      'max-width: 100vw !important; max-height: 100vh !important; margin: 0 !important; padding: 0 !important; ' +
      'background: #000 !important; z-index: 2147483000 !important; display: flex !important; align-items: center !important; justify-content: center !important; } ' +
      '.dl-isolated-stage video, video.dl-isolated-stage { position: relative !important; width: 100% !important; height: 100% !important; ' +
      'max-width: 100vw !important; max-height: 100vh !important; object-fit: contain !important; margin: auto !important; background: #000 !important; } ' +
      '.dl-isolated-stage iframe, iframe.dl-isolated-stage { width: 100vw !important; height: 100vh !important; border: 0 !important; }';
    (document.head || document.documentElement).appendChild(style);
  }

  function clearVideoIsolation() {
    try {
      if (document.documentElement) document.documentElement.classList.remove('dl-isolated-root');
      if (document.body) document.body.classList.remove('dl-isolated-root');
      document.querySelectorAll('.dl-isolated-stage').forEach(function(n) { n.classList.remove('dl-isolated-stage'); });
      document.querySelectorAll('.dl-isolated-hidden').forEach(function(n) { n.classList.remove('dl-isolated-hidden'); });
      document.querySelectorAll('.dl-isolated-ancestor').forEach(function(n) { n.classList.remove('dl-isolated-ancestor'); });
      window.__dlIsolatedTarget = null;
    } catch (e) {}
  }

  function resolvePlayerWrapper(mediaEl) {
    if (!mediaEl) return null;
    // In Scout PiP, keep strict frame around the video element itself (or immediate wrapper with exact same size)
    if (isScout) {
      return mediaEl;
    }
    var vRect = mediaEl.getBoundingClientRect();
    var best = mediaEl;
    var cur = mediaEl.parentElement;
    for (var depth = 0; depth < 4 && cur && cur !== document.body && cur !== document.documentElement; depth++) {
      var tag = (cur.tagName || '').toLowerCase();
      if (tag === 'main' || tag === 'body' || tag === 'article' || tag === 'section') break;
      var cls = ((cur.className || '') + ' ' + (cur.id || '')).toLowerCase();
      var r = cur.getBoundingClientRect();
      var compactWrap = r.width > 0 && r.height > 0 &&
        r.height <= Math.max(vRect.height * 1.42 + 84, 220) &&
        r.width <= (window.innerWidth || 1000) + 32;
      var looksLikePlayer = cls.indexOf('player') >= 0 || cls.indexOf('vjs') >= 0 ||
        cls.indexOf('plyr') >= 0 || cls.indexOf('jw') >= 0 || cls.indexOf('video-wrap') >= 0;
      if (compactWrap && (looksLikePlayer || cur.children.length <= 4)) {
        best = cur;
      } else if (!compactWrap) {
        break;
      }
      cur = cur.parentElement;
    }
    return best;
  }

  function applyVideoIsolation(mediaEl) {
    if (!window.__dlIsolateVideo || !mediaEl) {
      clearVideoIsolation();
      return;
    }
    try {
      if (mediaEl.ownerDocument !== document) {
        // Accessible iframe video: isolate its frame element in the parent document
        var iframes = document.querySelectorAll('iframe');
        for (var k = 0; k < iframes.length; k++) {
          try {
            if (iframes[k].contentDocument === mediaEl.ownerDocument) {
              mediaEl = iframes[k];
              break;
            }
          } catch (err) {}
        }
      }
      var stage = resolvePlayerWrapper(mediaEl);
      if (!stage) return;
      if (window.__dlIsolatedTarget === stage && stage.classList.contains('dl-isolated-stage')) return;
      clearVideoIsolation();
      window.__dlIsolatedTarget = stage;
      if (document.documentElement) document.documentElement.classList.add('dl-isolated-root');
      if (document.body) document.body.classList.add('dl-isolated-root');
      stage.classList.add('dl-isolated-stage');
      var cur = stage;
      while (cur && cur.parentElement && cur !== document.documentElement) {
        var p = cur.parentElement;
        p.classList.add('dl-isolated-ancestor');
        var kids = p.children;
        for (var i = 0; i < kids.length; i++) {
          if (kids[i] !== cur && kids[i].tagName !== 'SCRIPT' && kids[i].tagName !== 'STYLE') {
            kids[i].classList.add('dl-isolated-hidden');
          }
        }
        cur = p;
      }
    } catch (e) {}
  }

  window.dlSetVideoIsolation = function(enabled) {
    window.__dlIsolateVideo = !!enabled;
    if (!window.__dlIsolateVideo) {
      clearVideoIsolation();
    } else {
      var target = findPrimaryVideo() || findFallbackPlayerIframe();
      if (target) applyVideoIsolation(target);
    }
  };

  // Capture-phase touch/pointer/click interceptor stops offsite links & invisible hijack overlays before site scripts fire
  function handlePotentialHijackEvent(ev) {
    var node = ev.target;
    while (node && node !== document.body && node !== document.documentElement) {
      if (node.dataset && node.dataset.dlShieldBlocked === '1') {
        ev.preventDefault();
        ev.stopPropagation();
        if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
        return false;
      }
      if (node.tagName === 'A') {
        var href = node.getAttribute('href') || '';
        var tgt = (node.getAttribute('target') || '').toLowerCase();
        if (tgt === '_blank' || isOffsiteAdHref(href)) {
          ev.preventDefault();
          ev.stopPropagation();
          if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
          notifyPopupBlocked(href || 'blank-tab');
          return false;
        }
      }
      node = node.parentElement;
    }
  }
  ['touchstart', 'pointerdown', 'mousedown', 'click'].forEach(function(evtName) {
    document.addEventListener(evtName, handlePotentialHijackEvent, true);
  });

  try {
    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
      var href = this.getAttribute('href') || '';
      var tgt = (this.getAttribute('target') || '').toLowerCase();
      if (tgt === '_blank' || isOffsiteAdHref(href)) {
        notifyPopupBlocked(href || 'programmatic-click');
        return;
      }
      return origAnchorClick.apply(this, arguments);
    };
  } catch (e) {}

  function reportPageMetadata() {
    if (!window.DialogueLensWeb || !window.DialogueLensWeb.onPageMetadata) return;
    function metaContent(selector) {
      var el = document.querySelector(selector);
      return el ? (el.getAttribute('content') || '').trim() : '';
    }
    var title = metaContent('meta[property="og:title"]') || metaContent('meta[name="twitter:title"]') || (document.title || '').trim();
    var author = metaContent('meta[name="author"]') || metaContent('meta[property="og:site_name"]') || '';
    var desc = metaContent('meta[property="og:description"]') || metaContent('meta[name="description"]') || '';
    var sig = title + '|' + author + '|' + desc.substring(0, 120);
    if ((title || author || desc) && sig !== window.__dlLastMetaSig) {
      window.__dlLastMetaSig = sig;
      try {
        window.DialogueLensWeb.onPageMetadata(title, author, desc.substring(0, 900));
      } catch (e) {}
    }
  }

  function ensureVideoElementId(v) {
    if (!v) return '';
    if (!v.dataset.dlElementId) {
      window.__dlElemSeq = (window.__dlElemSeq || 0) + 1;
      v.dataset.dlElementId = 'dl_vid_' + window.__dlElemSeq;
    }
    return v.dataset.dlElementId;
  }

  function scoreVideoCandidate(v) {
    var r = v.getBoundingClientRect();
    var area = Math.max(0, r.width * r.height);
    var score = area;
    var id = ensureVideoElementId(v);
    if (id && id === window.__dlSelectedElementId && area > 2400) score += 90000;
    if (v.controls) score += 40000;
    if (v.readyState >= 2) score += 80000;
    if (v.videoWidth > 0 && v.videoHeight > 0) score += 45000;
    if (isFinite(v.duration) && v.duration > 20) score += 60000;
    if (v.loop && !v.controls && (!isFinite(v.duration) || v.duration <= 20)) score -= 90000;
    if (v.currentSrc || v.src || v.querySelector('source')) score += 15000;
    return score;
  }

  function findPrimaryVideo() {
    var vids = Array.prototype.slice.call(document.querySelectorAll('video'));
    var frames = document.querySelectorAll('iframe');
    for (var i = 0; i < frames.length; i++) {
      try {
        var doc = frames[i].contentDocument;
        if (doc) {
          var subVids = doc.querySelectorAll('video');
          for (var j = 0; j < subVids.length; j++) vids.push(subVids[j]);
        }
      } catch (e) {}
    }
    if (!vids.length) return null;
    vids.sort(function(a, b) {
      return scoreVideoCandidate(b) - scoreVideoCandidate(a);
    });
    var top = vids[0];
    if (!top) return null;
    var r = top.getBoundingClientRect();
    var usable = (r.width >= 60 && r.height >= 40) || !top.paused || (isFinite(top.duration) && top.duration > 0);
    if (usable) {
      window.__dlSelectedElementId = ensureVideoElementId(top);
    }
    return usable ? top : null;
  }

  function findFallbackPlayerIframe() {
    var frames = Array.prototype.slice.call(document.querySelectorAll('iframe'));
    var best = null;
    var bestArea = 0;
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      var src = (f.getAttribute('src') || '').toLowerCase();
      if (!src || src.indexOf('stripchat') >= 0 || src.indexOf('chaturbate') >= 0 || src.indexOf('exosrv') >= 0) continue;
      var r = f.getBoundingClientRect();
      var area = r.width * r.height;
      var looksEmbed = src.indexOf('embed') >= 0 || src.indexOf('player') >= 0 || src.indexOf('video') >= 0 ||
        f.hasAttribute('allowfullscreen');
      if (looksEmbed && r.width >= 200 && r.height >= 120 && area > bestArea) {
        bestArea = area;
        best = f;
      }
    }
    return best;
  }

  function checkAdLandingDom(hasVideo) {
    if (hasVideo) return false;
    try {
      var txt = ((document.body && document.body.innerText) || '').toLowerCase().slice(0, 1500);
      var titleLow = (document.title || '').toLowerCase();
      var combined = titleLow + ' ' + txt;
      return combined.indexOf('stripchat') >= 0 ||
        combined.indexOf('chaturbate') >= 0 ||
        (combined.indexOf('create free account') >= 0 && combined.indexOf('models') >= 0) ||
        (combined.indexOf('join free') >= 0 && combined.indexOf('private shows') >= 0);
    } catch (e) {
      return false;
    }
  }

  function reportNavHeartbeat(hasVideo) {
    if (!window.DialogueLensWeb || !window.DialogueLensWeb.onNavHeartbeat) return;
    var adDom = checkAdLandingDom(hasVideo);
    var href = String(location.href || '');
    var sig = href + '|' + (hasVideo ? '1' : '0') + '|' + (adDom ? '1' : '0');
    if (sig !== window.__dlLastNavSig) {
      window.__dlLastNavSig = sig;
      try {
        window.DialogueLensWeb.onNavHeartbeat(href, !!hasVideo, !!adDom);
      } catch (e) {}
    }
  }

  function reportDirectSrc(video) {
    if (!video || !window.DialogueLensWeb) return;
    var src = video.currentSrc || video.src || '';
    if (!src) {
      var srcEl = video.querySelector('source');
      if (srcEl && srcEl.src) src = srcEl.src;
    }
    if (src && src.indexOf('http') === 0 && src !== window.__dlLastDirectSrc) {
      window.__dlLastDirectSrc = src;
      try {
        window.DialogueLensWeb.onDirectMediaUrl(src);
      } catch (e) {}
    }
  }

  function emitCaptureDiagnostics(healthState, video, mime, lastStartMs, lastEndMs, detail) {
    if (!isScout || !window.DialogueLensWeb || !window.DialogueLensWeb.onCaptureDiagnostics) return;
    var st = window.__dlCapState || {};
    var primaryTrack = (st.ownedTracks && st.ownedTracks.length > 0) ? st.ownedTracks[0] : null;
    var payload = {
      healthState: String(healthState || 'WAITING_FOR_MEDIA'),
      selectedElementId: String((video && video.dataset && video.dataset.dlElementId) || window.__dlSelectedElementId || ''),
      documentGeneration: Number(window.__dlDocGeneration || 1),
      recorderId: st.recSeq ? ('rec_' + st.recSeq) : '',
      outputMuted: video ? !!video.muted : true,
      outputVolumeZero: video ? (video.volume === 0) : true,
      trackReadyState: primaryTrack ? String(primaryTrack.readyState || '') : '',
      trackMuted: primaryTrack ? !!primaryTrack.muted : false,
      trackEnabled: primaryTrack ? !!primaryTrack.enabled : false,
      lastMimeType: String(mime || ''),
      lastSliceStartMs: typeof lastStartMs === 'number' ? lastStartMs : -1,
      lastSliceEndMs: typeof lastEndMs === 'number' ? lastEndMs : -1,
      droppedGapCount: Number(st.droppedGapCount || 0),
      diagnosticDetail: String(detail || '')
    };
    var sig = payload.healthState + '|' + payload.recorderId + '|' + payload.trackReadyState + '|' +
      payload.droppedGapCount + '|' + payload.lastSliceStartMs + '|' + payload.lastSliceEndMs + '|' + payload.diagnosticDetail;
    if (sig !== window.__dlLastDiagKey) {
      window.__dlLastDiagKey = sig;
      try { window.DialogueLensWeb.onCaptureDiagnostics(JSON.stringify(payload)); } catch (e) {}
    }
  }

  function stopActiveCapture(reason) {
    var st = window.__dlCapState;
    if (!st) return;
    if (st.timerId) {
      try { clearTimeout(st.timerId); } catch (e) {}
      st.timerId = null;
    }
    if (st.rec) {
      try {
        st.rec.ondataavailable = null;
        st.rec.onerror = null;
        st.rec.onstop = null;
        if (st.rec.state !== 'inactive') st.rec.stop();
      } catch (e) {}
      st.rec = null;
    }
    if (st.ownedTracks && st.ownedTracks.length) {
      for (var i = 0; i < st.ownedTracks.length; i++) {
        try { st.ownedTracks[i].stop(); } catch (e) {}
      }
      st.ownedTracks = [];
    }
    if (st.video) {
      try {
        if (st.onSeeked) st.video.removeEventListener('seeked', st.onSeeked);
        if (st.onEmptied) st.video.removeEventListener('emptied', st.onEmptied);
        if (st.onEnded) st.video.removeEventListener('ended', st.onEnded);
        if (st.onPause) st.video.removeEventListener('pause', st.onPause);
      } catch (e) {}
    }
    st.active = false;
    st.elementId = '';
    st.video = null;
    if (reason) {
      emitCaptureDiagnostics('WAITING_FOR_MEDIA', null, '', -1, -1, reason);
    }
  }

  function tryStartAudioCapture(video) {
    var st = window.__dlCapState;
    if (!isScout || !video) return;
    var elemId = ensureVideoElementId(video);
    if (st.active && st.video === video && st.elementId === elemId) return;
    if (st.active) stopActiveCapture('');

    if (!window.MediaRecorder) {
      emitCaptureDiagnostics('UNSUPPORTED_CAPTURE', video, '', -1, -1, 'MediaRecorder unavailable');
      return;
    }
    var captureFn = video.captureStream || video.mozCaptureStream;
    if (!captureFn) {
      emitCaptureDiagnostics('UNSUPPORTED_CAPTURE', video, '', -1, -1, 'HTMLVideoElement.captureStream unavailable');
      return;
    }
    try {
      var stream = captureFn.call(video);
      var rawTracks = stream && stream.getAudioTracks ? stream.getAudioTracks() : [];
      if (!rawTracks || rawTracks.length === 0) {
        emitCaptureDiagnostics('UNSUPPORTED_CAPTURE', video, '', -1, -1, 'No audio track exposed by stream');
        return;
      }
      var clonedTracks = [];
      for (var t = 0; t < rawTracks.length; t++) {
        var cloned = rawTracks[t].clone ? rawTracks[t].clone() : rawTracks[t];
        clonedTracks.push(cloned);
      }
      var audioOnly = new MediaStream(clonedTracks);
      var mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : (MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm'
          : (MediaRecorder.isTypeSupported('audio/mp4') ? 'audio/mp4' : ''));
      if (!mime) {
        for (var k = 0; k < clonedTracks.length; k++) {
          try { if (clonedTracks[k].stop) clonedTracks[k].stop(); } catch (e) {}
        }
        emitCaptureDiagnostics('UNSUPPORTED_CAPTURE', video, '', -1, -1, 'No supported MediaRecorder audio MIME');
        return;
      }

      st.active = true;
      st.video = video;
      st.elementId = elemId;
      st.ownedTracks = clonedTracks;
      st.sliceStartMs = Math.max(0, Math.round((video.currentTime || 0) * 1000));
      st.seekDirty = false;
      st.discontinuityNext = false;

      st.onSeeked = function() {
        st.sliceStartMs = Math.max(0, Math.round((video.currentTime || 0) * 1000));
        st.seekDirty = true;
        st.discontinuityNext = true;
        if (st.rec && st.rec.state === 'recording') {
          try { st.rec.stop(); } catch (e) {}
        }
      };
      st.onEmptied = function() { stopActiveCapture('Media element emptied'); };
      st.onEnded = function() { stopActiveCapture('Media element ended'); };
      st.onPause = function() { stopActiveCapture('Scout paused'); };
      video.addEventListener('seeked', st.onSeeked);
      video.addEventListener('emptied', st.onEmptied, { once: true });
      video.addEventListener('ended', st.onEnded, { once: true });
      video.addEventListener('pause', st.onPause, { once: true });

      function runStandaloneClip() {
        if (!st.active || st.video !== video || video.paused || video.ended) return;
        st.recSeq = (st.recSeq || 0) + 1;
        var cleanMime = mime.split(';')[0];
        var clipChunks = [];
        var capturedStart = st.sliceStartMs;
        try {
          var rec = new MediaRecorder(audioOnly, { mimeType: mime });
          st.rec = rec;
          emitCaptureDiagnostics('RECORDER_STARTED', video, cleanMime, capturedStart, capturedStart, 'Clip #' + st.recSeq + ' started');
          rec.ondataavailable = function(ev) {
            if (ev.data && ev.data.size > 0) clipChunks.push(ev.data);
          };
          rec.onerror = function() {
            emitCaptureDiagnostics('DECODE_FAILED', video, cleanMime, capturedStart, capturedStart, 'MediaRecorder error');
            stopActiveCapture('');
          };
          rec.onstop = function() {
            if (st.timerId) {
              try { clearTimeout(st.timerId); } catch (e) {}
              st.timerId = null;
            }
            var curEndMs = Math.max(capturedStart + 350, Math.round((video.currentTime || 0) * 1000));
            if (st.seekDirty) {
              st.seekDirty = false;
              st.sliceStartMs = Math.max(0, Math.round((video.currentTime || 0) * 1000));
              if (st.active && !video.paused && !video.ended) runStandaloneClip();
              return;
            }
            st.sliceStartMs = curEndMs;
            var blob = clipChunks.length > 0 ? new Blob(clipChunks, { type: mime }) : null;
            if (blob && blob.size >= 1024 && window.DialogueLensWeb) {
              var hadDisc = !!st.discontinuityNext;
              st.discontinuityNext = false;
              var reader = new FileReader();
              reader.onloadend = function() {
                var res = reader.result || '';
                var comma = res.indexOf(',');
                if (comma > 0) {
                  var b64 = res.substring(comma + 1);
                  try {
                    window.DialogueLensWeb.onCapturedAudioChunk(capturedStart, b64, cleanMime, curEndMs, hadDisc);
                  } catch (err) {}
                }
              };
              reader.readAsDataURL(blob);
            } else {
              st.droppedGapCount = (st.droppedGapCount || 0) + 1;
              st.discontinuityNext = true;
              emitCaptureDiagnostics('WAITING_FOR_MEDIA', video, cleanMime, capturedStart, curEndMs, 'Dropped tiny clip (' + (blob ? blob.size : 0) + 'B)');
            }
            if (st.active && !video.paused && !video.ended) {
              runStandaloneClip();
            }
          };
          rec.start();
          st.timerId = setTimeout(function() {
            if (st.rec === rec && rec.state === 'recording') {
              try { rec.stop(); } catch (e) {}
            }
          }, 6200);
        } catch (err) {
          emitCaptureDiagnostics('SOURCE_INACCESSIBLE', video, cleanMime, capturedStart, capturedStart, String(err && err.name ? err.name : 'CaptureError'));
          stopActiveCapture('');
        }
      }

      runStandaloneClip();
    } catch (securityErr) {
      var errName = String(securityErr && securityErr.name ? securityErr.name : 'SecurityError');
      emitCaptureDiagnostics('SOURCE_INACCESSIBLE', video, '', -1, -1, errName);
      stopActiveCapture('');
    }
  }

  function harvestTextTracks(video) {
    if (!video || !video.textTracks || !window.DialogueLensWeb) return;
    var collected = [];
    var hasUsableTrack = false;
    for (var i = 0; i < video.textTracks.length; i++) {
      var track = video.textTracks[i];
      if (track.kind !== 'subtitles' && track.kind !== 'captions') continue;
      if (track.mode === 'disabled') {
        track.mode = 'hidden';
      }
      var cues = track.cues;
      if (cues && cues.length > 0) {
        hasUsableTrack = true;
        if (track.mode === 'showing') track.mode = 'hidden';
        for (var j = 0; j < cues.length; j++) {
          var c = cues[j];
          var txt = (c.text || '').replace(/<[^>]+>/g, '').trim();
          if (txt) {
            collected.push({
              startMs: Math.max(0, Math.round((c.startTime || 0) * 1000)),
              endMs: Math.max(300, Math.round((c.endTime || 0) * 1000)),
              text: txt
            });
          }
        }
        break;
      }
    }
    if (hasUsableTrack && collected.length > 0) {
      var sig = collected.length + ':' + collected[0].startMs + ':' + collected[collected.length - 1].endMs;
      if (sig !== window.__dlLastTrackSig) {
        window.__dlLastTrackSig = sig;
        try {
          window.DialogueLensWeb.onCapturedTracks(JSON.stringify(collected));
        } catch (err) {}
      }
    }
  }

  function syncCycle() {
    injectBaseStyles();
    reportPageMetadata();
    var v = findPrimaryVideo();
    var fallbackFrame = v ? null : findFallbackPlayerIframe();
    hidePromoOverlays(v || fallbackFrame);
    reportNavHeartbeat(!!v);
    if (window.__dlIsolateVideo && (v || fallbackFrame)) {
      applyVideoIsolation(v || fallbackFrame);
    }
    if (!v || !window.DialogueLensWeb) return;
    neutralizeClickShields(v);
    if (isScout) {
      v.muted = true;
      v.volume = 0;
    }
    var curMs = Math.round((v.currentTime || 0) * 1000);
    var durMs = (isFinite(v.duration) && v.duration > 0) ? Math.round(v.duration * 1000) : 0;
    var playing = (!v.paused && !v.ended) || (Date.now() < window.__dlPlayGraceUntil && !v.ended);
    try {
      window.DialogueLensWeb.onProgress(curMs, durMs, playing);
    } catch (err) {}
    harvestTextTracks(v);
    reportDirectSrc(v);
    if (isScout && !v.paused && curMs > 500) {
      tryStartAudioCapture(v);
    }
  }

  window.__dlScanTracks = syncCycle;
  reportPageMetadata();
  setInterval(syncCycle, 80);

  window.dlWebPlay = function() {
    window.__dlPlayGraceUntil = Date.now() + 2000;
    var v = findPrimaryVideo();
    if (v) {
      if (isScout) { v.muted = true; v.volume = 0; }
      if (v.play) {
        var p = v.play();
        if (p && p.catch) p.catch(function() {});
      }
    }
  };
  window.dlWebPause = function() {
    window.__dlPlayGraceUntil = 0;
    var v = findPrimaryVideo();
    if (v && v.pause) v.pause();
  };
  window.dlWebMute = function() { var v = findPrimaryVideo(); if (v) v.muted = true; };
  window.dlWebUnmute = function() {
    if (isScout) return;
    var v = findPrimaryVideo();
    if (v) v.muted = false;
  };
  window.dlWebRate = function(r) { var v = findPrimaryVideo(); if (v) v.playbackRate = r; };
  window.dlWebSeek = function(sec) { var v = findPrimaryVideo(); if (v) v.currentTime = sec; };
})();
""".trimIndent()
}

private val ALLOWED_AUDIO_MIME_TYPES = setOf(
    "audio/webm",
    "audio/mp4",
    "audio/ogg",
    "audio/wav",
    "audio/mpeg",
    "audio/aac",
)
