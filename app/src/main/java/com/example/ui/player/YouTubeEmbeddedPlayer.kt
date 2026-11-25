package com.example.ui.player

import com.example.domain.analysis.MediaTransport
import com.example.domain.analysis.ViewerTransportSample

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

/** Official embedded player; provider restrictions surface with an external viewing option. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeEmbeddedPlayer(
    videoId: String,
    isMuted: Boolean,
    playbackSpeed: Float = 1.0f,
    playRequest: Pair<Int, Boolean>? = null,
    seekRequest: Pair<Int, Long>?,
    onViewerTransport: ((ViewerTransportSample) -> Unit)? = null,
    onPlaybackState: (positionMs: Long, durationMs: Long, isPlaying: Boolean) -> Unit,
    isScoutInstance: Boolean = false,
    suppressNativeCaptions: Boolean = false,
    preferredCaptionLang: String = "en",
    onCaptionTrackStatus: (trackCount: Int, activeLang: String) -> Unit = { _, _ -> },
    onEmbedFallback: (errorCode: Int) -> Unit = {},
    onCapturedTimedTextBody: (url: String, body: String, cookies: String?, userAgent: String?) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val transportCallback by rememberUpdatedState(onViewerTransport)
    val latestCallback by rememberUpdatedState(onPlaybackState)
    val latestTrackStatusCallback by rememberUpdatedState(onCaptionTrackStatus)
    val latestEmbedFallbackCallback by rememberUpdatedState(onEmbedFallback)
    val latestCapturedTimedTextCallback by rememberUpdatedState(onCapturedTimedTextBody)
    val seenTimedTextUrls = remember(videoId) { mutableSetOf<String>() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadedVideoId by remember { mutableStateOf<String?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var lastHandledPlaySeq by remember { mutableStateOf(0) }
    var lastHandledSeekSeq by remember { mutableStateOf(0) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val effectiveMuted = isMuted || isScoutInstance
    val effectiveSuppressCc = suppressNativeCaptions

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

    LaunchedEffect(playRequest, playerReady) {
        val req = playRequest ?: return@LaunchedEffect
        if (!playerReady || req.first == lastHandledPlaySeq) return@LaunchedEffect
        lastHandledPlaySeq = req.first
        val js = if (req.second) "window.__dlPlaySequence = ${req.first}; window.dlPlay && window.dlPlay();" else "window.__dlPlaySequence = ${req.first}; window.dlPause && window.dlPause();"
        webViewRef?.evaluateJavascript(js, null)
    }

    LaunchedEffect(effectiveMuted, playerReady) {
        if (!playerReady) return@LaunchedEffect
        val js = if (effectiveMuted) "window.dlMute && window.dlMute();" else "window.dlUnmute && window.dlUnmute();"
        webViewRef?.evaluateJavascript(js, null)
    }

    LaunchedEffect(playbackSpeed, playerReady) {
        if (!playerReady) return@LaunchedEffect
        webViewRef?.evaluateJavascript("window.dlRate && window.dlRate($playbackSpeed);", null)
    }

    LaunchedEffect(seekRequest, playerReady) {
        val req = seekRequest ?: return@LaunchedEffect
        if (!playerReady || req.first == lastHandledSeekSeq) return@LaunchedEffect
        lastHandledSeekSeq = req.first
        val seconds = (req.second / 1000.0)
        webViewRef?.evaluateJavascript("window.__dlSeekSequence = ${req.first}; window.dlSeek && window.dlSeek($seconds);", null)
    }

    LaunchedEffect(effectiveSuppressCc, playerReady) {
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
                        }
                    }
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onReady() {
                        mainHandler.post { playerReady = true }
                    }

                    @JavascriptInterface
                    fun onProgress(posMs: Long, durMs: Long, code: Int, seekSeq: Int, playSeq: Int) {
                        val transport = when (code) {
                            1 -> MediaTransport.PLAYING
                            2 -> MediaTransport.PAUSED
                            3 -> MediaTransport.BUFFERING
                            4 -> MediaTransport.ENDED
                            else -> MediaTransport.UNAVAILABLE
                        }
                        mainHandler.post {
                            val callback = transportCallback
                            if (callback != null) callback(ViewerTransportSample(posMs, durMs, transport, seekSeq, playSeq))
                            else latestCallback(posMs, durMs, transport == MediaTransport.PLAYING)
                        }
                    }

                    @JavascriptInterface
                    fun onCaptionTrackInfo(trackCount: Int, activeLang: String?) {
                        mainHandler.post {
                            latestTrackStatusCallback(trackCount, activeLang.orEmpty())
                        }
                    }

                    @JavascriptInterface
                    fun onTimedTextCaptured(url: String?, body: String?) {
                        val safeUrl = url.orEmpty()
                        val safeBody = body.orEmpty()
                        if (safeUrl.isBlank() && safeBody.isBlank()) return
                        mainHandler.post {
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
                            playerReady = false
                            latestEmbedFallbackCallback(errorCode)
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
    val ccLoadParam = if (suppressNativeCaptions) 0 else 1
    val forceMuteFlag = if (isScoutInstance) "true" else "false"
    val initialSuppressFlag = if (suppressNativeCaptions) "true" else "false"
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
            var forceScoutMute = $forceMuteFlag;
            var suppressCc = $initialSuppressFlag;
            var hasAppliedSoftMute = false;
            var embedUnavailable = false;

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
                    if (suppressCc) applySoftCaptionMuteOnce();
                    if (window.DialogueLensYt) window.DialogueLensYt.onReady();
                    setTimeout(reportNativeCaptionTracks, 900);
                    if (pendingDesiredPlay === true && ytPlayer.playVideo) {
                      ytPlayer.playVideo();
                      pendingDesiredPlay = null;
                    } else if (pendingDesiredPlay === false && ytPlayer.pauseVideo) {
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
                    if (!embedUnavailable &&
                        window.DialogueLensYt &&
                        typeof window.DialogueLensYt.onEmbedBlocked === 'function') {
                      embedUnavailable = true;
                      if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
                      window.DialogueLensYt.onEmbedBlocked(code);
                    }
                    var el = document.getElementById('errBanner');
                    var txt = document.getElementById('errText');
                    if (el && txt) {
                      el.style.display = 'flex';
                      if (code === 101 || code === 150) {
                        txt.textContent = 'The owner allows this video to play on YouTube.';
                      } else if (code === 153) {
                        txt.textContent = 'YouTube could not verify this embedded player.';
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
              if (!ytReady && !embedUnavailable) attachYtPlayer();
              if (!ytReady && !embedUnavailable && window.DialogueLensYt) {
                window.DialogueLensYt.onReady();
                startPolling();
              }
            }, 1200);

            function startPolling() {
              if (pollTimer) clearInterval(pollTimer);
              pollTimer = setInterval(pushState, 50);
            }

            function pushState() {
              if (embedUnavailable || !ytPlayer || !ytPlayer.getCurrentTime || !window.DialogueLensYt) return;
              var curMs = Math.round((ytPlayer.getCurrentTime() || 0) * 1000);
              var durMs = Math.round((ytPlayer.getDuration ? (ytPlayer.getDuration() || 0) : 0) * 1000);
              var st = ytPlayer.getPlayerState ? ytPlayer.getPlayerState() : -1;
              var code = st === 1 ? 1 : st === 3 ? 3 : st === 0 ? 4 : 2;
              window.DialogueLensYt.onProgress(curMs, durMs, code, window.__dlSeekSequence || 0, window.__dlPlaySequence || 0);
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
