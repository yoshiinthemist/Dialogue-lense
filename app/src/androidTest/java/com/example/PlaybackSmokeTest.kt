package com.example

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.ProcessingQualityMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.ui.state.AppDestination
import com.example.ui.state.DialogueLensViewModel
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class PlaybackSmokeTest {
    @get:Rule
    val ui = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: DialogueLensViewModel
    private lateinit var screen: MainActivity

    @Before
    fun findActivityPlayer() {
        ui.activityRule.scenario.onActivity { activity ->
            screen = activity
            model = ViewModelProvider(activity)[DialogueLensViewModel::class.java]
        }
    }

    @Test
    fun launchAndNavigate() {
        ui.onNodeWithText("What do you want to watch?").assertIsDisplayed()
        ui.onNodeWithText("Open local video").assertIsDisplayed()
        for (destination in listOf(AppDestination.LIBRARY, AppDestination.STUDIO, AppDestination.WATCH)) {
            val name = destination.name.lowercase()
            ui.onNode(hasTestTag("nav_bar_$name") or hasTestTag("nav_rail_$name")).performClick()
            assertEquals(destination, model.uiState.value.activeDestination)
        }
        ui.onNodeWithTag("start_url_input").assertIsDisplayed()
    }

    @Test
    fun preparedVideoShowsTheRightBalloonAfterPlayingAndPausedSeeks() {
        val video = fixture("two-lines.mp4")
        val captions = fixture("two-lines.srt")
        onPlayer { setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD) }
        onPlayer {
            createProjectFromMedia(
                videoUri = video,
                videoTitle = "Two spoken lines",
                subtitleUri = captions,
                subtitleFileName = "two-lines.srt",
                sourceLanguage = "English",
                targetLanguage = "English",
                translationMode = TranslationMode.NATURAL,
                subtitleSource = SubtitleSourceKind.UPLOADED_FILE,
                qualityMode = ProcessingQualityMode.FAST,
            )
        }
        waitForPlayer("local media and verified sidecar ready") {
            exoPlayer.playbackState == Player.STATE_READY &&
                uiState.value.project.videoSource.uriString == video.toString() &&
                uiState.value.coverageSummary.isFullyVerified
        }
        val cues = model.uiState.value.project.dialogueEvents.associate { it.sourceText to it.id }
        val opening = cues.getValue("Opening line.")
        val afterCut = cues.getValue("After the cut.")

        ui.onNodeWithTag("video_player_stage").assertIsDisplayed()
        waitForPlayer("Watch video surface attached before the first tap") {
            descendants(screen.window.decorView).filterIsInstance<PlayerView>().any { view ->
                val surface = view.videoSurfaceView as? SurfaceView
                view.player === exoPlayer && view.isShown && surface?.holder?.surface?.isValid == true
            }
        }
        ui.onNodeWithTag("toggle_play_pause_button").assertIsDisplayed().performClick()
        waitForPlayer("decoded opening video frames with first cue") {
            exoPlayer.isPlaying && exoPlayer.currentPosition in 820L..2_300L &&
                (exoPlayer.videoDecoderCounters?.apply { ensureUpdated() }?.renderedOutputBufferCount ?: 0) > 0 &&
                uiState.value.placedBalloons.any { it.event.id == opening }
        }
        onPlayer { seekTo(7_600L) }
        waitForPlayer("playing through prepared seek with the second cue") {
            exoPlayer.isPlaying && exoPlayer.currentPosition in 7_850L..9_400L &&
                uiState.value.placedBalloons.any { it.event.id == afterCut } &&
                uiState.value.placedBalloons.none { it.event.id == opening }
        }
        onPlayer {
            pausePlayback()
            seekTo(1_400L)
        }
        waitForPlayer("paused backward seek acknowledged") {
            !exoPlayer.playWhenReady && exoPlayer.playbackState == Player.STATE_READY &&
                abs(exoPlayer.currentPosition - 1_400L) < 100L &&
                uiState.value.placedBalloons.any { it.event.id == opening }
        }
        ui.onNodeWithTag("balloon_$opening", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("balloon_$afterCut", useUnmergedTree = true).assertDoesNotExist()

        val boundPlayer = onPlayer { exoPlayer }
        val originalOrientation = ui.activity.requestedOrientation
        try {
            ui.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(timeoutMillis = 15_000L) {
                instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            ui.onNodeWithTag("balloon_$opening", useUnmergedTree = true).assertIsDisplayed()
            onPlayer {
                assertTrue("Resizing must retain the media player", exoPlayer === boundPlayer)
                assertFalse(exoPlayer.playWhenReady)
                assertTrue(abs(exoPlayer.currentPosition - 1_400L) < 100L)
            }
        } finally {
            ui.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }

        onPlayer { seekTo(5_000L) }
        waitForPlayer("silence between the two cues") {
            abs(exoPlayer.currentPosition - 5_000L) < 100L && uiState.value.placedBalloons.isEmpty()
        }
        // Observe across real transport ticks, including work completed after the seek.
        val start = onPlayer { exoPlayer.currentPosition }
        SystemClock.sleep(650L)
        onPlayer {
            assertFalse("Paused viewers must remain paused", exoPlayer.playWhenReady)
            assertTrue(abs(exoPlayer.currentPosition - start) < 100L)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun addingSubtitlesResumesAnUnpreparedSeekAtTheLatestDestination() {
        val resolver = instrumentation.targetContext.contentResolver
        val metadata = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "DialogueLens test clip.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val video = requireNotNull(resolver.insert(collection, metadata))
        try {
            instrumentation.context.assets.open("playback/two-lines.mp4").use { input ->
                requireNotNull(resolver.openOutputStream(video)).use { output -> input.copyTo(output) }
            }
            resolver.update(video, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            onPlayer { setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD) }
            // Substitute only the system picker's selection. Use its actual launcher, callback,
            // content URI, metadata lookup, media decoder, and caption attachment handler.
            val picker = instrumentation.addMonitor(
                IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addDataType("*/*")
                },
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(video)),
                true,
            )
            try {
                ui.onNodeWithTag("open_local_video").performClick()
                waitForPlayer("local file from the Watch picker") {
                    uiState.value.project.videoSource.uriString == video.toString() &&
                        exoPlayer.playbackState == Player.STATE_READY
                }
                assertEquals(1, picker.hits)
            } finally {
                instrumentation.removeMonitor(picker)
            }

            onPlayer {
                togglePlayPause()
                seekTo(3_000L)
                seekTo(7_800L)
            }
            waitForPlayer("unprepared seek held at the latest target") {
                uiState.value.viewerWantsPlay && !uiState.value.seekUnacknowledged &&
                    !exoPlayer.playWhenReady && exoPlayer.playbackState == Player.STATE_READY &&
                    abs(exoPlayer.currentPosition - 7_800L) < 100L && uiState.value.project.dialogueEvents.isEmpty()
            }
            val captions = fixture("two-lines.srt")
            onPlayer { attachSubtitleFileToCurrentProject(captions, "two-lines.srt") }
            waitForPlayer("automatic resume from the newly verified target") {
                exoPlayer.isPlaying && exoPlayer.currentPosition in 8_050L..9_400L &&
                    uiState.value.project.subtitleSource == SubtitleSourceKind.UPLOADED_FILE &&
                    uiState.value.placedBalloons.any { it.event.sourceText == "After the cut." }
            }
        } finally {
            onPlayer { pausePlayback() }
            resolver.delete(video, null, null)
        }
    }

    @Test
    fun directLinkDecodesAndSeeksOverHttp() {
        FixtureMediaServer(instrumentation.context.assets).use { server ->
            val url = server.origin + "/clip.mp4"
            openLink(url)
            waitForPlayer("direct MP4 decoded from HTTP") {
                uiState.value.project.videoSource.uriString == url &&
                    exoPlayer.playbackState == Player.STATE_READY && exoPlayer.duration in 11_900L..12_100L
            }
            val captions = fixture("two-lines.srt")
            onPlayer { attachSubtitleFileToCurrentProject(captions, "two-lines.srt") }
            waitForPlayer("file attached to direct stream") { uiState.value.coverageSummary.isFullyVerified }
            onPlayer {
                assertEquals("The sidecar must cover the observed transport duration",
                    exoPlayer.duration, uiState.value.coverageSummary.totalDurationMs)
            }
            onPlayer { seekTo(7_800L); togglePlayPause() }
            waitForPlayer("direct HTTP playback and matching second cue") {
                exoPlayer.isPlaying && exoPlayer.currentPosition in 8_000L..9_400L &&
                    (exoPlayer.videoDecoderCounters?.apply { ensureUpdated() }?.renderedOutputBufferCount ?: 0) > 0 &&
                    uiState.value.placedBalloons.any { it.event.sourceText == "After the cut." }
            }
            assertTrue(server.requested.contains("/clip.mp4"))
            onPlayer { pausePlayback() }
        }
    }

    @Test
    fun webPageCapturesNativeTrackAndUsesTheHtmlVideoClock() {
        FixtureMediaServer(instrumentation.context.assets).use { server ->
            openLink(server.origin + "/watch")
            waitForPlayer("HTML5 track harvested from the page") {
                uiState.value.project.subtitleSource == SubtitleSourceKind.EMBEDDED_TRACK &&
                    uiState.value.project.dialogueEvents.map { it.startTimeMs to it.sourceText } == listOf(
                        820L to "Opening line.", 7_780L to "After the cut.",
                    )
            }
            onPlayer {
                seekTo(7_800L)
                togglePlayPause()
            }
            waitForPlayer("embedded player and subtitles after seek") {
                uiState.value.isPlaying && uiState.value.currentTimeMs in 8_000L..9_400L &&
                    uiState.value.placedBalloons.any { it.event.sourceText == "After the cut." }
            }
            val running = htmlMediaClock()
            assertTrue("HTML video playhead $running", running.getDouble(0) in 7.8..11.0)
            assertFalse(running.getBoolean(1))
            onPlayer {
                pausePlayback()
                seekTo(1_400L)
            }
            waitForPlayer("paused HTML seek and opening cue") {
                !uiState.value.isPlaying && !uiState.value.seekUnacknowledged &&
                    uiState.value.placedBalloons.any { it.event.sourceText == "Opening line." }
            }
            val paused = htmlMediaClock()
            assertTrue(paused.getBoolean(1))
            assertEquals(1.4, paused.getDouble(0), 0.15)
            assertEquals(12.0, paused.getDouble(2), 0.1)
            assertTrue(server.requested.containsAll(listOf("/watch", "/clip.mp4", "/lines.vtt")))
        }
    }

    private fun openLink(url: String) {
        onPlayer { setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD) }
        ui.onNodeWithTag("start_url_input").performTextInput(url)
        closeSoftKeyboard()
        ui.onNodeWithTag("start_submit_url_button").performClick()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun htmlMediaClock(): JSONArray {
        val result = LinkedBlockingQueue<String>(1)
        ui.activityRule.scenario.onActivity { activity ->
            val viewer = descendants(activity.window.decorView).filterIsInstance<WebView>().filter { it.isShown }
                .maxByOrNull { it.width.toLong() * it.height } ?: error("No visible WebView")
            viewer.evaluateJavascript("""
                (() => {
                    const v = document.querySelector('video');
                    return v ? [v.currentTime, v.paused, v.duration] : [];
                })()
            """.trimIndent()) { result.offer(it) }
        }
        return JSONArray(requireNotNull(result.poll(10, TimeUnit.SECONDS)) { "No HTML media clock response" })
    }

    private fun fixture(name: String): Uri {
        val output = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open("playback/$name").use { source ->
            output.outputStream().use { target -> source.copyTo(target) }
        }
        return Uri.fromFile(output)
    }

    private fun waitForPlayer(description: String, ready: DialogueLensViewModel.() -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000L
        val observations = mutableListOf<String>()
        var nextSample = 0L
        fun snapshot() = onPlayer {
            val state = uiState.value
            val frames = exoPlayer.videoDecoderCounters?.apply { ensureUpdated() }?.renderedOutputBufferCount
            "media=${exoPlayer.currentPosition} ui=${state.currentTimeMs} frames=$frames " +
                "transport=${exoPlayer.playbackState} intent=${state.viewerWantsPlay} " +
                "phase=${state.playbackPhase} error=${exoPlayer.playerError?.errorCodeName} " +
                "cues=${state.placedBalloons.map { it.event.sourceText }}"
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            // Compose test frames and the real Media3 clock run independently. Render Watch
            // between transport samples, as a real device choreographer does outside a test.
            ui.mainClock.advanceTimeByFrame()
            if (onPlayer(ready)) return
            val now = SystemClock.elapsedRealtime()
            if (observations.size < 14 && now >= nextSample) {
                observations += snapshot()
                nextSample = now + 250L
            }
            SystemClock.sleep(35L)
        }
        throw AssertionError("Timed out waiting for $description. ${observations.joinToString(" | ")} | ${snapshot()}")
    }

    private fun <T> onPlayer(block: DialogueLensViewModel.() -> T): T {
        val task = FutureTask { model.block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
