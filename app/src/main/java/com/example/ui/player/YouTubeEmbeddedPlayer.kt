package com.example.ui.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.analysis.RedirectDecision
import com.example.domain.analysis.WebNavigationGuard

/**
 * Official compliant YouTube IFrame Player API wrapper via Android WebView with automatic
 * YouTube Mobile Watch fallback when a creator/network restricts third-party iframe embeds
 * (Error 101 / 150 / 152 / 153). Keeps playback timestamps, transport controls, and native
 * caption visibility synchronized with DialogueLens bubbles in both modes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeEmbeddedPlayer(
    videoId: String,
    isMuted: Boolean,
    playbackSpeed: Float = 1.0f,
    playRequest: Pair<Int, Boolean>? = null,
    seekRequest: Pair<Int, Long>?,
    onPlaybackState: (positionMs: Long, durationMs: Long, isPlaying: Boolean) -> Unit,
    isScoutInstance: Boolean = false,
    suppressNativeCaptions: Boolean = false,
    preferredCaptionLang: String = "en",
    onCaptionTrackStatus: (trackCount: Int, activeLang: String) -> Unit = { _, _ -> },
    onEmbedFallback: (errorCode: Int) -> Unit = {},
    onCapturedTimedTextBody: (url: String, body: String, cookies: String?, userAgent: String?) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val latestCallback by rememberUpdatedState(onPlaybackState)
    val latestTrackStatusCallback by rememberUpdatedState(onCaptionTrackStatus)
    val latestEmbedFallbackCallback by rememberUpdatedState(onEmbedFallback)
    val latestPreferredLang by rememberUpdatedState(preferredCaptionLang)
    val latestCapturedTimedTextCallback by rememberUpdatedState(onCapturedTimedTextBody)
    val seenTimedTextUrls = remember(videoId) { mutableSetOf<String>() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadedVideoId by remember { mutableStateOf<String?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var usingMobileWatchFallback by remember { mutableStateOf(false) }
    var fallbackAttempted by remember { mutableStateOf(false) }
    var lastHandledPlaySeq by remember { mutableStateOf(0) }
    var lastHandledSeekSeq by remember { mutableStateOf(0) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val effectiveMuted = isMuted || isScoutInstance
    val effectiveSuppressCc = suppressNativeCaptions
    val latestMuted by rememberUpdatedState(effectiveMuted)
    val latestSuppressCc by rememberUpdatedState(effectiveSuppressCc)

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                removeJavascriptInterface("DialogueLensYt")
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    LaunchedEffect(playRequest, playerReady, usingMobileWatchFallback) {
        val req = playRequest ?: return@LaunchedEffect
        if (!playerReady || req.first == lastHandledPlaySeq) return@LaunchedEffect
        lastHandledPlaySeq = req.first
        val js = if (req.second) "window.dlPlay && window.dlPlay();" else "window.dlPause && window.dlPause();"
        webViewRef?.evaluateJavascript(js, null)
    }

    LaunchedEffect(effectiveMuted, playerReady, usingMobileWatchFallback) {
        if (!playerReady) return@LaunchedEffect
        val js = if (effectiveMuted) "window.dlMute && window.dlMute();" else "window.dlUnmute && window.dlUnmute();"
        webViewRef?.evaluateJavascript(js, null)
    }

    LaunchedEffect(playbackSpeed, playerReady, usingMobileWatchFallback) {
        if (!playerReady) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.dlRate && window.dlRate($playbackSpeed);", null)
    }

    LaunchedEffect(seekRequest, playerReady, usingMobileWatchFallback) {
        val req = seekRequest ?: return@LaunchedEffect
        if (!playerReady || req.first == lastHandledSeekSeq) return@LaunchedEffect
        lastHandledSeekSeq = req.first
        val seconds = (req.second / 1000.0)
        webViewRef?.evaluateJavascript("window.dlSeek && window.dlSeek($seconds);", null)
    }

    LaunchedEffect(effectiveSuppressCc, playerReady, usingMobileWatchFallback) {
        if (!playerReady) return@LaunchedEffect
        val flag = if (effectiveSuppressCc) "true" else "false"
        webViewRef?.evaluateJavascript("window.dlSetSuppressCc && window.dlSetSuppressCc($flag);", null)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val packageOrigin = "https://${ctx.packageName}"
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Use normal hardware window rendering (LAYER_TYPE_HARDWARE creates an offscreen FBO that can blank WebView video)
                setLayerType(View.LAYER_TYPE_NONE, null)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
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
                        return false
                    }
                }
                // Let Chromium handle YouTube requests cleanly while blocking pop-ups, external app intents,
                // and cross-site ad redirects across both Scout and Viewer YouTube WebViews.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val reqUrl = request?.url?.toString().orEmpty()
                        if (reqUrl.isBlank()) return false
                        val decision = WebNavigationGuard.evaluateYouTubeNavigation(
                            targetUrl = reqUrl,
                            appOriginHost = ctx.packageName,
                        )
                        return decision is RedirectDecision.Block
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        if (reqUrl.contains("/api/timedtext", ignoreCase = true) &&
                            seenTimedTextUrls.add(reqUrl.take(420))
                        ) {
                            mainHandler.post {
                                val cookie = runCatching { CookieManager.getInstance().getCookie(reqUrl) }.getOrNull()
                                val ua = runCatching { webViewRef?.settings?.userAgentString }.getOrNull()
                                latestCapturedTimedTextCallback(reqUrl, "", cookie, ua)
                            }
                        }
                        if (request?.isForMainFrame == true &&
                            reqUrl.isNotBlank() &&
                            WebNavigationGuard.evaluateYouTubeNavigation(
                                targetUrl = reqUrl,
                                appOriginHost = ctx.packageName,
                            ) is RedirectDecision.Block
                        ) {
                            return android.webkit.WebResourceResponse(
                                "text/html",
                                "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0)),
                            )
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val cleanUrl = url.orEmpty()
                        if (cleanUrl.isNotBlank() &&
                            WebNavigationGuard.evaluateYouTubeNavigation(
                                targetUrl = cleanUrl,
                                appOriginHost = ctx.packageName,
                            ) is RedirectDecision.Block
                        ) {
                            view?.stopLoading()
                            val activeId = loadedVideoId?.replace(Regex("[^0-9A-Za-z_-]"), "").orEmpty()
                            if (usingMobileWatchFallback && activeId.isNotEmpty()) {
                                val safeLang = latestPreferredLang.lowercase().replace(Regex("[^a-z-]"), "").ifBlank { "en" }
                                view?.loadUrl("https://m.youtube.com/watch?v=$activeId&hl=$safeLang")
                            }
                            return
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (usingMobileWatchFallback &&
                            !url.isNullOrBlank() &&
                            url.contains("youtube.com", ignoreCase = true)
                        ) {
                            view?.evaluateJavascript(
                                buildYouTubeMobileWatchHookScript(
                                    isScoutInstance = isScoutInstance,
                                    suppressNativeCaptions = latestSuppressCc,
                                    initialMuted = latestMuted,
                                ),
                                null,
                            )
                        }
                    }
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onReady() {
                        mainHandler.post { playerReady = true }
                    }

                    @JavascriptInterface
                    fun onProgress(posMs: Long, durMs: Long, playing: Boolean) {
                        mainHandler.post { latestCallback(posMs, durMs, playing) }
                    }

                    @JavascriptInterface
                    fun onCaptionTrackInfo(trackCount: Int, activeLang: String?) {
                        mainHandler.post {
                            latestTrackStatusCallback(trackCount, activeLang.orEmpty())
                        }
                    }

                    @JavascriptInterface
                    fun onTimedTextCaptured(url: String?, body: String?) {
                        val safeUrl = url.orEmpty().trim()
                        val safeBody = body.orEmpty()
                        if (safeUrl.isBlank() && safeBody.isBlank()) return
                        if (safeUrl.isNotBlank()) {
                            val host = runCatching { android.net.Uri.parse(safeUrl).host.orEmpty().lowercase() }.getOrDefault("")
                            val isYouTubeHost = host == "youtube.com" ||
                                host.endsWith(".youtube.com") ||
                                host == "youtube-nocookie.com" ||
                                host.endsWith(".youtube-nocookie.com")
                            if (!isYouTubeHost) return
                        }
                        mainHandler.post {
                            val activeVid = loadedVideoId?.replace(Regex("[^0-9A-Za-z_-]"), "").orEmpty()
                            val urlVid = Regex("[?&]v=([^&]+)").find(safeUrl)?.groupValues?.getOrNull(1).orEmpty()
                            if (activeVid.isNotBlank() && urlVid.isNotBlank() && urlVid != activeVid) {
                                return@post
                            }
                            val cookie = if (safeUrl.startsWith("http")) {
                                runCatching { CookieManager.getInstance().getCookie(safeUrl) }.getOrNull()
                            } else {
                                null
                            }
                            val ua = runCatching { webViewRef?.settings?.userAgentString }.getOrNull()
                            latestCapturedTimedTextCallback(safeUrl, safeBody, cookie, ua)
                        }
                    }

                    @JavascriptInterface
                    fun onEmbedBlocked(errorCode: Int) {
                        mainHandler.post {
                            val activeId = loadedVideoId?.replace(Regex("[^0-9A-Za-z_-]"), "").orEmpty()
                            if (!fallbackAttempted && activeId.isNotEmpty()) {
                                fallbackAttempted = true
                                usingMobileWatchFallback = true
                                playerReady = false
                                lastHandledPlaySeq = 0
                                lastHandledSeekSeq = 0
                                latestEmbedFallbackCallback(errorCode)
                                val safeLang = latestPreferredLang
                                    .lowercase()
                                    .replace(Regex("[^a-z-]"), "")
                                    .ifBlank { "en" }
                                webViewRef?.loadUrl("https://m.youtube.com/watch?v=$activeId&hl=$safeLang")
                            }
                        }
                    }

                    @JavascriptInterface
                    fun openInYouTubeApp(targetId: String) {
                        mainHandler.post {
                            val clean = targetId.replace(Regex("[^0-9A-Za-z_-]"), "")
                            if (clean.isNotEmpty()) {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.youtube.com/watch?v=$clean")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { ctx.startActivity(intent) }
                            }
                        }
                    }
                }
                addJavascriptInterface(bridge, "DialogueLensYt")
                webViewRef = this
            }.also {
                it.tag = packageOrigin
            }
        },
        update = { webView ->
            if (loadedVideoId != videoId && videoId.isNotBlank()) {
                loadedVideoId = videoId
                playerReady = false
                usingMobileWatchFallback = false
                fallbackAttempted = false
                lastHandledPlaySeq = 0
                lastHandledSeekSeq = 0
                val packageOrigin = (webView.tag as? String) ?: "https://${webView.context.packageName}"
                val html = buildYouTubeIframeHtml(
                    videoId = videoId,
                    packageOrigin = packageOrigin,
                    isScoutInstance = isScoutInstance,
                    suppressNativeCaptions = effectiveSuppressCc,
                    preferredCaptionLang = preferredCaptionLang,
                )
                webView.loadDataWithBaseURL(
                    packageOrigin,
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

private fun buildYouTubeIframeHtml(
    videoId: String,
    packageOrigin: String,
    isScoutInstance: Boolean = false,
    suppressNativeCaptions: Boolean = false,
    preferredCaptionLang: String = "en",
): String {
    val safeId = videoId.replace(Regex("[^0-9A-Za-z_-]"), "")
    val safeOrigin = packageOrigin.replace("'", "")
    val safeLang = preferredCaptionLang.lowercase().replace(Regex("[^a-z-]"), "").ifBlank { "en" }
    val controlsParam = if (isScoutInstance) 0 else 1
    // Scout 1's viewing path MUST enable available native YouTube CC to collect real caption evidence.
    val effectiveSuppress = if (isScoutInstance) false else suppressNativeCaptions
    val ccLoadParam = if (effectiveSuppress) 0 else 1
    val forceMuteFlag = if (isScoutInstance) "true" else "false"
    val initialSuppressFlag = if (effectiveSuppress) "true" else "false"
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <meta name="referrer" content="strict-origin-when-cross-origin">
          <style>
            html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: transparent; overflow: hidden; font-family: sans-serif; }
            #player { position: fixed; inset: 0; width: 100%; height: 100%; border: 0; }
            #errBanner {
              display: none;
              position: absolute;
              inset: 0;
              background: #0b0f17;
              color: #f8fafc;
              padding: 18px;
              text-align: center;
              flex-direction: column;
              align-items: center;
              justify-content: center;
              gap: 10px;
              font-size: 13px;
              line-height: 1.45;
            }
            #errBtn {
              background: #38bdf8;
              color: #0b0f17;
              border: 0;
              border-radius: 18px;
              padding: 8px 16px;
              font-weight: 700;
              font-size: 12px;
              cursor: pointer;
            }
          </style>
        </head>
        <body>
          <iframe
            id="player"
            src="https://www.youtube.com/embed/$safeId?enablejsapi=1&playsinline=1&rel=0&modestbranding=1&start=0&controls=$controlsParam&iv_load_policy=3&hl=$safeLang&cc_lang_pref=$safeLang&cc_load_policy=$ccLoadParam&origin=$safeOrigin"
            referrerpolicy="strict-origin-when-cross-origin"
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; fullscreen"
            allowfullscreen>
          </iframe>
          <div id="errBanner">
            <div id="errText"></div>
            <button id="errBtn" onclick="window.DialogueLensYt && window.DialogueLensYt.openInYouTubeApp('$safeId')">
              Open in YouTube App
            </button>
          </div>
          <script src="https://www.youtube.com/iframe_api"></script>
          <script>
            var ytPlayer = null;
            var ytReady = false;
            var pollTimer = null;
            var pendingDesiredPlay = null;
            var playGraceUntilMs = 0;
            var forceScoutMute = $forceMuteFlag;
            var suppressCc = $initialSuppressFlag;
            var hasAppliedSoftMute = false;
            var switchedToWatchFallback = false;

            var lastReportedTrackCount = -1;

            function reportNativeCaptionTracks() {
              try {
                if (!ytPlayer || typeof ytPlayer.getOption !== 'function' || !window.DialogueLensYt) return;
                var list = ytPlayer.getOption('captions', 'tracklist');
                var active = ytPlayer.getOption('captions', 'track');
                var count = Array.isArray(list) ? list.length : 0;
                var lang = (active && active.languageCode) ? String(active.languageCode) :
                  (Array.isArray(list) && list.length > 0 && list[0].languageCode ? String(list[0].languageCode) : '');
                if (count !== lastReportedTrackCount) {
                  lastReportedTrackCount = count;
                  window.DialogueLensYt.onCaptionTrackInfo(count, lang);
                }
              } catch (e) {}
            }

            function applySoftCaptionMuteOnce() {
              if (!suppressCc || !ytPlayer || hasAppliedSoftMute) return;
              hasAppliedSoftMute = true;
              try {
                // Softly clear active track so DialogueLens bubbles don't double up with YouTube captions,
                // WITHOUT unloading the captions module so the user's CC toggle always remains functional.
                if (typeof ytPlayer.setOption === 'function') {
                  ytPlayer.setOption('captions', 'track', {});
                }
              } catch (e) {}
            }

            function attachYtPlayer() {
              if (ytPlayer || typeof YT === 'undefined' || !YT.Player) return;
              ytPlayer = new YT.Player('player', {
                events: {
                  onReady: function() {
                    ytReady = true;
                    if (forceScoutMute && ytPlayer.mute) ytPlayer.mute();
                    if (forceScoutMute && ytPlayer.getCurrentTime && ytPlayer.getCurrentTime() > 4 && ytPlayer.seekTo) {
                      ytPlayer.seekTo(0, true);
                    }
                    if (forceScoutMute) {
                      try {
                        if (typeof ytPlayer.loadModule === 'function') ytPlayer.loadModule('captions');
                        if (typeof ytPlayer.setOption === 'function') {
                          ytPlayer.setOption('captions', 'track', { languageCode: '$safeLang' });
                        }
                      } catch (e) {}
                    } else if (suppressCc) {
                      applySoftCaptionMuteOnce();
                    }
                    if (window.DialogueLensYt) window.DialogueLensYt.onReady();
                    setTimeout(reportNativeCaptionTracks, 900);
                    if (pendingDesiredPlay === true && ytPlayer.playVideo) {
                      playGraceUntilMs = Date.now() + 2500;
                      ytPlayer.playVideo();
                      pendingDesiredPlay = null;
                    } else if (pendingDesiredPlay === false && ytPlayer.pauseVideo) {
                      playGraceUntilMs = 0;
                      ytPlayer.pauseVideo();
                      pendingDesiredPlay = null;
                    }
                    startPolling();
                  },
                  onStateChange: function(ev) {
                    if (forceScoutMute && ytPlayer && ytPlayer.mute) ytPlayer.mute();
                    if (suppressCc && !hasAppliedSoftMute) {
                      applySoftCaptionMuteOnce();
                    }
                    reportNativeCaptionTracks();
                    pushState();
                  },
                  onError: function(e) {
                    var code = (e && typeof e.data === 'number' ? e.data : 0);
                    if ((code === 101 || code === 150 || code === 152 || code === 153) &&
                        !switchedToWatchFallback &&
                        window.DialogueLensYt &&
                        typeof window.DialogueLensYt.onEmbedBlocked === 'function') {
                      switchedToWatchFallback = true;
                      if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
                      window.DialogueLensYt.onEmbedBlocked(code);
                      return;
                    }
                    var el = document.getElementById('errBanner');
                    var txt = document.getElementById('errText');
                    if (el && txt) {
                      el.style.display = 'flex';
                      if (code === 101 || code === 150 || code === 152 || code === 153) {
                        txt.textContent = 'This video (' + code + ') restricts embedded playback by creator/network policy. Open it in the YouTube app or attach subtitles here.';
                      } else {
                        txt.textContent = 'YouTube player notice (' + (code || 'unknown') + '). Check network or open directly in the YouTube app.';
                      }
                    }
                  }
                }
              });
            }

            function onYouTubeIframeAPIReady() {
              attachYtPlayer();
            }

            // Guard in case iframe_api loaded synchronously or before callback fired
            setTimeout(function() {
              if (!ytReady && !switchedToWatchFallback) attachYtPlayer();
              if (!ytReady && !switchedToWatchFallback && window.DialogueLensYt) {
                window.DialogueLensYt.onReady();
                startPolling();
              }
            }, 1200);

            function startPolling() {
              if (pollTimer) clearInterval(pollTimer);
              pollTimer = setInterval(pushState, 50);
            }

            function pushState() {
              if (switchedToWatchFallback || !ytPlayer || !ytPlayer.getCurrentTime || !window.DialogueLensYt) return;
              var curMs = Math.round((ytPlayer.getCurrentTime() || 0) * 1000);
              var durMs = Math.round((ytPlayer.getDuration ? (ytPlayer.getDuration() || 0) : 0) * 1000);
              var st = ytPlayer.getPlayerState ? ytPlayer.getPlayerState() : -1;
              if (st === 1 || st === 2 || st === 0) {
                playGraceUntilMs = 0;
              }
              var playing = (st === 1) || (st === 3) || (Date.now() < playGraceUntilMs && st !== 2 && st !== 0);
              window.DialogueLensYt.onProgress(curMs, durMs, playing);
            }

            function postYtCommand(func, args) {
              try {
                var fr = document.getElementById('player');
                if (fr && fr.contentWindow) {
                  fr.contentWindow.postMessage(
                    JSON.stringify({ event: 'command', func: func, args: args || [] }),
                    '*'
                  );
                }
              } catch (err) {}
            }

            window.dlPlay = function() {
              playGraceUntilMs = Date.now() + 2500;
              if (forceScoutMute) {
                if (ytPlayer && typeof ytPlayer.mute === 'function') ytPlayer.mute();
                else postYtCommand('mute');
              }
              if (ytPlayer && typeof ytPlayer.playVideo === 'function') {
                ytPlayer.playVideo();
              } else {
                pendingDesiredPlay = true;
                postYtCommand('playVideo');
              }
            };
            window.dlPause = function() {
              playGraceUntilMs = 0;
              if (ytPlayer && typeof ytPlayer.pauseVideo === 'function') {
                ytPlayer.pauseVideo();
              } else {
                pendingDesiredPlay = false;
                postYtCommand('pauseVideo');
              }
            };
            window.dlMute = function() {
              if (ytPlayer && typeof ytPlayer.mute === 'function') ytPlayer.mute();
              else postYtCommand('mute');
            };
            window.dlUnmute = function() {
              if (forceScoutMute) return;
              if (ytPlayer && typeof ytPlayer.unMute === 'function') ytPlayer.unMute();
              else postYtCommand('unMute');
            };
            window.dlRate = function(rate) {
              if (ytPlayer && typeof ytPlayer.setPlaybackRate === 'function') ytPlayer.setPlaybackRate(rate);
              else postYtCommand('setPlaybackRate', [rate]);
            };
            window.dlSeek = function(sec) {
              if (ytPlayer && typeof ytPlayer.seekTo === 'function') ytPlayer.seekTo(sec, true);
              else postYtCommand('seekTo', [sec, true]);
            };
            window.dlSetSuppressCc = function(flag) {
              var nextSuppress = !!flag;
              if (nextSuppress && !suppressCc) {
                suppressCc = true;
                hasAppliedSoftMute = false;
                applySoftCaptionMuteOnce();
              } else if (!nextSuppress && suppressCc) {
                suppressCc = false;
                hasAppliedSoftMute = false;
              }
            };
          </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Injected into m.youtube.com/watch?v=... when third-party iframe embedding is restricted
 * by creator/network policy (Error 101 / 150 / 152 / 153). Frames YouTube's official mobile
 * video player cleanly inside the 16:9 stage and bridges timestamps + transport controls
 * directly to DialogueLens so AI Scout speech bubbles stay locked in sync.
 */
private fun buildYouTubeMobileWatchHookScript(
    isScoutInstance: Boolean,
    suppressNativeCaptions: Boolean,
    initialMuted: Boolean,
): String = """
(function() {
  var forceScoutMute = ${if (isScoutInstance) "true" else "false"};
  var suppressCc = ${if (suppressNativeCaptions) "true" else "false"};
  var desiredMuted = ${if (initialMuted || isScoutInstance) "true" else "false"};

  if (window.__dlYtmHookInstalled) {
    if (window.dlSetSuppressCc) window.dlSetSuppressCc(suppressCc);
    return;
  }
  window.__dlYtmHookInstalled = true;
  var notifiedReady = false;
  var playGraceUntilMs = 0;
  var pendingPlay = false;
  var pendingSeekSec = null;
  var lastReportedTracks = -1;

  function ensureStageCss() {
    if (document.getElementById('dl-ytm-stage-css')) return;
    var style = document.createElement('style');
    style.id = 'dl-ytm-stage-css';
    style.textContent =
      'html, body { margin: 0 !important; padding: 0 !important; width: 100% !important; height: 100% !important; overflow: hidden !important; background: #000 !important; }' +
      'ytm-app, #app, .page-container { padding-top: 0 !important; margin-top: 0 !important; background: #000 !important; }' +
      'ytm-mobile-topbar-renderer, .mobile-topbar-header, #header-bar, ytm-watch-below-the-player, .watch-below-the-player, ytm-single-column-watch-next-results-renderer, ytm-pivot-bar-renderer, .watch-page-banner { display: none !important; }' +
      '#player-container-id, .player-container { position: fixed !important; inset: 0 !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; max-height: 100vh !important; margin: 0 !important; padding: 0 !important; z-index: 999 !important; background: #000 !important; }' +
      '#player-container-id .html5-video-player, #player-container-id .html5-video-container, #player-container-id video, .player-container video { width: 100% !important; height: 100% !important; max-width: 100% !important; max-height: 100% !important; top: 0 !important; left: 0 !important; object-fit: contain !important; }';
    (document.head || document.documentElement).appendChild(style);
  }

  function applyCcVisibilityCss() {
    var existing = document.getElementById('dl-ytm-hide-cc');
    if (suppressCc) {
      if (!existing) {
        var st = document.createElement('style');
        st.id = 'dl-ytm-hide-cc';
        st.textContent =
          '.ytp-caption-window-container, .caption-window, .captions-text { opacity: 0 !important; pointer-events: none !important; }';
        (document.head || document.documentElement).appendChild(st);
      }
    } else if (existing && existing.parentNode) {
      existing.parentNode.removeChild(existing);
    }
  }

  function findVideo() {
    return document.querySelector('#player-container-id video, .html5-video-player video, video');
  }

  function isAdPlaying() {
    var p = document.querySelector('.html5-video-player');
    return !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
  }

  var sentTimedTextBody = false;
  function emitTimedTextBody(url, text) {
    try {
      if (!window.DialogueLensYt || !window.DialogueLensYt.onTimedTextCaptured) return;
      if (!text || text.length < 16) return;
      sentTimedTextBody = true;
      window.DialogueLensYt.onTimedTextCaptured(url || '', text);
    } catch (e) {}
  }

  // Intercept live XHR & fetch responses for /api/timedtext (which carry browser PO-tokens & cookies)
  (function installTimedTextInterceptors() {
    if (window.__dlTtHooked) return;
    window.__dlTtHooked = true;
    try {
      var origFetch = window.fetch;
      if (typeof origFetch === 'function') {
        window.fetch = function(input, init) {
          var reqUrl = (typeof input === 'string') ? input : (input && input.url ? String(input.url) : '');
          var p = origFetch.apply(this, arguments);
          if (reqUrl.indexOf('/api/timedtext') !== -1) {
            p.then(function(resp) {
              try {
                if (resp && resp.ok && typeof resp.clone === 'function') {
                  resp.clone().text().then(function(body) { emitTimedTextBody(reqUrl, body); }).catch(function(){});
                }
              } catch (e) {}
            }).catch(function(){});
          }
          return p;
        };
      }
    } catch (e) {}
    try {
      var OrigXhrOpen = XMLHttpRequest.prototype.open;
      var OrigXhrSend = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function(method, url) {
        this.__dlUrl = (typeof url === 'string') ? url : String(url || '');
        return OrigXhrOpen.apply(this, arguments);
      };
      XMLHttpRequest.prototype.send = function() {
        var self = this;
        if (self.__dlUrl && self.__dlUrl.indexOf('/api/timedtext') !== -1) {
          self.addEventListener('load', function() {
            try {
              if (self.status >= 200 && self.status < 300 && typeof self.responseText === 'string') {
                emitTimedTextBody(self.__dlUrl, self.responseText);
              }
            } catch (e) {}
          });
        }
        return OrigXhrSend.apply(this, arguments);
      };
    } catch (e) {}
  })();

  function tryHarvestInitialPlayerCaptions() {
    if (sentTimedTextBody) return;
    try {
      var pr = window.ytInitialPlayerResponse;
      var tracks = pr && pr.captions && pr.captions.playerCaptionsTracklistRenderer && pr.captions.playerCaptionsTracklistRenderer.captionTracks;
      if (Array.isArray(tracks) && tracks.length > 0 && tracks[0].baseUrl) {
        if (!window.__dlProbedInitUrl) {
          window.__dlProbedInitUrl = true;
          var baseUrl = String(tracks[0].baseUrl);
          if (baseUrl.indexOf('fmt=') === -1) baseUrl += '&fmt=json3';
          fetch(baseUrl, { credentials: 'include' })
            .then(function(r) { return r.ok ? r.text() : ''; })
            .then(function(t) { emitTimedTextBody(baseUrl, t); })
            .catch(function(){});
        }
      }
    } catch (e) {}
  }

  function reportTrackInfo() {
    try {
      if (!window.DialogueLensYt || !window.DialogueLensYt.onCaptionTrackInfo) return;
      var mp = document.getElementById('movie_player');
      if (mp && typeof mp.getOption === 'function') {
        var list = mp.getOption('captions', 'tracklist');
        var active = mp.getOption('captions', 'track');
        var count = Array.isArray(list) ? list.length : 0;
        var lang = (active && active.languageCode) ? String(active.languageCode) :
          (Array.isArray(list) && list.length > 0 && list[0].languageCode ? String(list[0].languageCode) : '');
        if (count !== lastReportedTracks) {
          lastReportedTracks = count;
          window.DialogueLensYt.onCaptionTrackInfo(count, lang);
        }
        if (count > 0 && !sentTimedTextBody && typeof mp.setOption === 'function' && (!active || !active.languageCode)) {
          try {
            mp.setOption('captions', 'track', { languageCode: lang || 'en' });
          } catch (e) {}
        }
      }
      tryHarvestInitialPlayerCaptions();
    } catch (e) {}
  }

  function kickVideoPlay(v) {
    if (!v) return;
    v.muted = forceScoutMute || desiredMuted;
    if (typeof v.play === 'function') {
      var pr = v.play();
      if (pr && typeof pr.catch === 'function') {
        pr.catch(function() {
          // If browser required muted kickstart, retry muted briefly
          try {
            var btn = document.querySelector('.ytp-large-play-button, .player-control-play-pause-icon, button[aria-label*="Play"]');
            if (btn) btn.click();
          } catch (e) {}
        });
      }
    }
  }

  window.dlPlay = function() {
    playGraceUntilMs = Date.now() + 2500;
    pendingPlay = true;
    var v = findVideo();
    if (v) {
      pendingPlay = false;
      kickVideoPlay(v);
    }
  };
  window.dlPause = function() {
    playGraceUntilMs = 0;
    pendingPlay = false;
    var v = findVideo();
    if (v && typeof v.pause === 'function') v.pause();
  };
  window.dlMute = function() {
    desiredMuted = true;
    var v = findVideo();
    if (v) v.muted = true;
  };
  window.dlUnmute = function() {
    if (forceScoutMute) return;
    desiredMuted = false;
    var v = findVideo();
    if (v) {
      v.muted = false;
      try {
        var unmuteBtn = document.querySelector('.ytp-unmute');
        if (unmuteBtn && unmuteBtn.offsetParent !== null) unmuteBtn.click();
      } catch (e) {}
    }
  };
  window.dlRate = function(rate) {
    var v = findVideo();
    if (v) v.playbackRate = rate;
  };
  window.dlSeek = function(sec) {
    var v = findVideo();
    if (v && isFinite(sec)) {
      v.currentTime = sec;
      pendingSeekSec = null;
    } else {
      pendingSeekSec = sec;
    }
  };
  window.dlSetSuppressCc = function(flag) {
    suppressCc = !!flag;
    applyCcVisibilityCss();
  };

  ensureStageCss();
  applyCcVisibilityCss();

  setInterval(function() {
    ensureStageCss();
    applyCcVisibilityCss();
    var v = findVideo();
    if (!v || !window.DialogueLensYt) return;

    if (!notifiedReady) {
      notifiedReady = true;
      window.DialogueLensYt.onReady();
    }
    if (forceScoutMute) {
      v.muted = true;
      v.volume = 0;
    }
    if (pendingSeekSec !== null && isFinite(v.duration) && v.duration > 0) {
      v.currentTime = pendingSeekSec;
      pendingSeekSec = null;
    }
    if (pendingPlay) {
      pendingPlay = false;
      kickVideoPlay(v);
    }

    reportTrackInfo();
    if (isAdPlaying()) return;

    var curMs = Math.round((v.currentTime || 0) * 1000);
    var durMs = (isFinite(v.duration) && v.duration > 0) ? Math.round(v.duration * 1000) : 0;
    var playing = (!v.paused && !v.ended) || (Date.now() < playGraceUntilMs && !v.ended);
    window.DialogueLensYt.onProgress(curMs, durMs, playing);
  }, 50);
})();
""".trimIndent()
