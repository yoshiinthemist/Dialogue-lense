package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.subtitle.SubtitleParser
import com.example.domain.analysis.SniffedWebMediaStream
import com.example.domain.analysis.WebNavCommand
import com.example.domain.analysis.WebNavigationGuard
import com.example.domain.analysis.WebNavigationState
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaElevated
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.CinemaSurfaceHigh
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.LensAmber
import com.example.ui.theme.LensAmberContainer
import com.example.ui.theme.LensMint
import com.example.ui.theme.LensSky
import com.example.ui.theme.LensSkyContainer

/**
 * Composable Browser Controls bar below the video player:
 * provides Back, Forward, and Refresh actions for the main WebView, displays blocked pop-up/redirect
 * protection with Allow Once override, and opens the floating Scout Inspector modal.
 */
@Composable
fun WebBrowserControlStrip(
    seedUrl: String,
    viewerNav: WebNavigationState,
    scoutNav: WebNavigationState,
    scoutTimeMs: Long,
    dualInstanceEnabled: Boolean,
    onViewerBack: () -> Unit,
    onViewerForward: () -> Unit,
    onViewerRefresh: () -> Unit,
    onViewerResetSource: () -> Unit,
    onViewerAllowOnce: () -> Unit,
    onOpenScoutModal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seedHost = WebNavigationGuard.extractHost(seedUrl).ifBlank { "web" }
    val displayHost = viewerNav.currentHost.ifBlank { seedHost }
    val driftedFromSource = viewerNav.currentUrl.isNotBlank() &&
        seedUrl.isNotBlank() &&
        WebNavigationGuard.registrableDomain(viewerNav.currentHost) != WebNavigationGuard.registrableDomain(seedHost)
    val scoutNeedsAttention = dualInstanceEnabled &&
        (scoutNav.isAdRedirectSuspected || (!scoutNav.hasActiveVideo && scoutTimeMs <= 500L))

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            color = CinemaSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (viewerNav.isAdRedirectSuspected) LensAmber.copy(alpha = 0.7f) else CinemaBorder,
            ),
            modifier = Modifier.testTag("web_browser_control_strip"),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Browser Controls",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        ),
                        color = InkSecondary,
                    )
                    Text(
                        text = buildString {
                            append(displayHost)
                            if (viewerNav.blockedRedirectCount > 0) {
                                append(" · ${viewerNav.blockedRedirectCount} blocked")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = when {
                            viewerNav.isAdRedirectSuspected -> LensAmber
                            viewerNav.blockedRedirectCount > 0 -> LensMint
                            else -> InkSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = onViewerBack,
                        enabled = viewerNav.canGoBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("web_viewer_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Viewer back",
                            tint = if (viewerNav.canGoBack) InkPrimary else InkSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(19.dp),
                        )
                    }

                    IconButton(
                        onClick = onViewerForward,
                        enabled = viewerNav.canGoForward,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("web_viewer_forward_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Viewer forward",
                            tint = if (viewerNav.canGoForward) InkPrimary else InkSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(19.dp),
                        )
                    }

                    IconButton(
                        onClick = onViewerRefresh,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("web_viewer_refresh_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Viewer refresh",
                            tint = InkPrimary,
                            modifier = Modifier.size(19.dp),
                        )
                    }

                    // Active site shield pill
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CinemaElevated)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = if (viewerNav.isAdRedirectSuspected) Icons.Filled.WarningAmber else Icons.Filled.Shield,
                            contentDescription = "Redirect shield status",
                            tint = when {
                                viewerNav.isAdRedirectSuspected -> LensAmber
                                viewerNav.blockedRedirectCount > 0 -> LensMint
                                else -> LensSky
                            },
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (viewerNav.isAdRedirectSuspected) "Ad redirect?" else "Protected",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (driftedFromSource || viewerNav.isAdRedirectSuspected) {
                        Surface(
                            color = LensAmberContainer,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, LensAmber.copy(alpha = 0.7f)),
                            onClick = onViewerResetSource,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("web_viewer_reset_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Replay,
                                    contentDescription = "Return to video page",
                                    tint = LensAmber,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Video",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = LensAmber,
                                )
                            }
                        }
                    }

                    if (dualInstanceEnabled) {
                        Surface(
                            color = if (scoutNeedsAttention) LensAmberContainer else CinemaSurfaceHigh,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (scoutNeedsAttention) LensAmber.copy(alpha = 0.75f) else LensSky.copy(alpha = 0.45f),
                            ),
                            onClick = onOpenScoutModal,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("open_scout_browser_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (scoutNeedsAttention) LensAmber else LensMint),
                                )
                                Icon(
                                    imageVector = Icons.Filled.TravelExplore,
                                    contentDescription = "Open Scout Inspector",
                                    tint = if (scoutNeedsAttention) LensAmber else LensSky,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = if (scoutNeedsAttention) "Scout!" else "Scout Inspector",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (scoutNeedsAttention) LensAmber else InkPrimary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = !viewerNav.lastBlockedUrl.isNullOrBlank()) {
            Surface(
                color = CinemaElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CinemaBorder),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = LensMint,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Blocked redirect to ${viewerNav.lastBlockedHost ?: "external site"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        color = LensSkyContainer,
                        shape = RoundedCornerShape(8.dp),
                        onClick = onViewerAllowOnce,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("web_viewer_allow_once_button"),
                    ) {
                        Text(
                            text = "Allow Once",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = LensSky,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Large, floating, expandable Scout Inspector modal hosting the real live Scout WebView session.
 * Grows comfortably to nearly full screen so users can clearly read pages, dismiss ads,
 * complete manual authentication/CAPTCHAs, navigate Back/Forward/Refresh, or match Viewer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoutBrowserInspectorModal(
    webUrl: String,
    playbackSpeed: Float,
    scoutPlayRequest: Pair<Int, Boolean>?,
    scoutSeekRequest: Pair<Int, Long>?,
    scoutNavCommand: WebNavCommand?,
    scoutNav: WebNavigationState,
    scoutTimeMs: Long,
    scoutSessionHolder: WebStreamSessionHolder,
    onScoutPlaybackState: (Long, Long, Boolean) -> Unit,
    onCapturedCaptions: (String) -> Unit,
    onScoutDirectMediaUrl: (String) -> Unit,
    onScoutSniffedMediaStream: (SniffedWebMediaStream) -> Unit = {},
    onScoutAudioSlice: (Long, Long, String, String) -> Unit,
    onScoutNavChanged: (WebNavigationState) -> Unit,
    onScoutBack: () -> Unit,
    onScoutForward: () -> Unit,
    onScoutRefresh: () -> Unit,
    onScoutSyncFromViewer: () -> Unit,
    onScoutResetSource: () -> Unit,
    onScoutAllowOnce: () -> Unit,
    onDismiss: () -> Unit,
) {
    var isMaximized by remember { mutableStateOf(false) }
    val widthFraction by animateFloatAsState(
        targetValue = if (isMaximized) 0.99f else 0.94f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scout_modal_width",
    )
    val heightFraction by animateFloatAsState(
        targetValue = if (isMaximized) 0.96f else 0.85f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scout_modal_height",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .safeDrawingPadding()
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = CinemaObsidian,
                shape = RoundedCornerShape(if (isMaximized) 14.dp else 20.dp),
                border = BorderStroke(1.2.dp, LensSky.copy(alpha = 0.48f)),
                tonalElevation = 8.dp,
                shadowElevation = 18.dp,
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .fillMaxHeight(heightFraction)
                    .testTag("scout_inspector_floating_modal"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Header row: Scout Inspector title, current domain, Expand/Grow, Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Scout Inspector",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkPrimary,
                            )
                            Text(
                                text = buildString {
                                    append(scoutNav.currentHost.ifBlank { WebNavigationGuard.extractHost(webUrl) })
                                    if (scoutNav.blockedRedirectCount > 0) {
                                        append(" · ${scoutNav.blockedRedirectCount} pop-ups blocked")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { isMaximized = !isMaximized },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scout_expand_size_button"),
                        ) {
                            Icon(
                                imageVector = if (isMaximized) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                                contentDescription = if (isMaximized) "Shrink Scout window" else "Grow Scout window",
                                tint = LensSky,
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("close_scout_browser_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close Scout Inspector",
                                tint = InkPrimary,
                            )
                        }
                    }

                    // Compact status ribbon
                    Surface(
                        color = if (scoutNav.hasActiveVideo && !scoutNav.isAdRedirectSuspected) {
                            CinemaSurfaceHigh
                        } else {
                            LensAmberContainer
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (scoutNav.hasActiveVideo && !scoutNav.isAdRedirectSuspected) {
                                LensMint.copy(alpha = 0.6f)
                            } else {
                                LensAmber.copy(alpha = 0.7f)
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (scoutNav.hasActiveVideo && !scoutNav.isAdRedirectSuspected) {
                                    Icons.Filled.CheckCircle
                                } else {
                                    Icons.Filled.WarningAmber
                                },
                                contentDescription = null,
                                tint = if (scoutNav.hasActiveVideo && !scoutNav.isAdRedirectSuspected) LensMint else LensAmber,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = when {
                                    scoutNav.isAdRedirectSuspected ->
                                        "Scout hit an ad or redirect — clear it below, go Back, or Match Viewer."
                                    scoutNav.hasActiveVideo ->
                                        "Video locked @ ${SubtitleParser.formatCompactTime(scoutTimeMs)} — streaming ahead quietly."
                                    else ->
                                        "No active <video> yet — clear ads, log in, tap Play, or Match Viewer."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = InkPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Full-height weighted interactive live Scout WebView viewport (grows with modal!)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .testTag("scout_inspector_webview_container"),
                    ) {
                        WebStreamPlayer(
                            webUrl = webUrl,
                            isMuted = true,
                            playbackSpeed = playbackSpeed,
                            playRequest = scoutPlayRequest,
                            seekRequest = scoutSeekRequest,
                            onPlaybackState = onScoutPlaybackState,
                            onCapturedCaptions = onCapturedCaptions,
                            isScoutInstance = true,
                            isolateVideoInViewport = false,
                            onDirectMediaUrl = onScoutDirectMediaUrl,
                            onSniffedMediaStream = onScoutSniffedMediaStream,
                            onCapturedAudioSlice = onScoutAudioSlice,
                            navCommand = scoutNavCommand,
                            onNavigationStateChange = onScoutNavChanged,
                            sessionHolder = scoutSessionHolder,
                            autoDisposeOnLeave = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    HorizontalDivider(color = CinemaBorder)

                    // Bottom browser controls row: Back, Forward, Refresh, Match Viewer, Reset URL, Allow Redirect
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = onScoutBack,
                            enabled = scoutNav.canGoBack,
                            border = BorderStroke(1.dp, CinemaBorder),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scout_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Back", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onScoutForward,
                            enabled = scoutNav.canGoForward,
                            border = BorderStroke(1.dp, CinemaBorder),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scout_forward_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Fwd", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onScoutRefresh,
                            border = BorderStroke(1.dp, CinemaBorder),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scout_refresh_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Refresh", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onScoutSyncFromViewer,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LensSkyContainer,
                                contentColor = LensSky,
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scout_sync_viewer_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Match Viewer", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onScoutResetSource,
                            border = BorderStroke(1.dp, CinemaBorder),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scout_reset_url_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Replay,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Reset", fontSize = 12.sp)
                        }

                        if (!scoutNav.lastBlockedUrl.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = onScoutAllowOnce,
                                border = BorderStroke(1.dp, LensAmber.copy(alpha = 0.7f)),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("scout_allow_once_button"),
                            ) {
                                Text(
                                    text = "Allow Redirect",
                                    color = LensAmber,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
