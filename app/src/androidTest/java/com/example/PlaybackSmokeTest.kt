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
import androidx.annotation.OptIn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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

    @Before
    fun findActivityPlayer() {
        ui.activityRule.scenario.onActivity { activity ->
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

        onPlayer { togglePlayPause() }
        waitForPlayer("decoded opening video frames with first cue") {
            exoPlayer.isPlaying && exoPlayer.currentPosition in 820L..2_300L &&
                (exoPlayer.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
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

    private fun fixture(name: String): Uri {
        val output = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open("playback/$name").use { source ->
            output.outputStream().use { target -> source.copyTo(target) }
        }
        return Uri.fromFile(output)
    }

    private fun waitForPlayer(description: String, ready: DialogueLensViewModel.() -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onPlayer(ready)) return
            SystemClock.sleep(35L)
        }
        val state = onPlayer {
            "transport=${exoPlayer.playbackState} position=${exoPlayer.currentPosition} " +
                "intent=${exoPlayer.playWhenReady} error=${exoPlayer.playerError?.errorCodeName} " +
                "balloons=${uiState.value.placedBalloons.map { it.event.sourceText }}"
        }
        throw AssertionError("Timed out waiting for $description. $state")
    }

    private fun <T> onPlayer(block: DialogueLensViewModel.() -> T): T {
        val task = FutureTask { model.block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
