package com.example

import androidx.compose.ui.test.assertIsDisplayed
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.ProcessingQualityMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import java.io.File
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.analysis.PlaybackPhase
import com.example.ui.state.DialogueLensViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchHealingUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun tapRenderedTransport(label: String, model: DialogueLensViewModel) {
        val button = ui.onNode(hasTestTag("toggle_play_pause_button") and hasContentDescription(label))
        val deadline = SystemClock.elapsedRealtime() + 2_500L
        // In manual-clock mode, input can have been delivered while the corresponding
        // layout, accessibility state, and Android frame are still pending.
        do {
            ui.mainClock.advanceTimeByFrame()
            if (button.isDisplayed()) {
                button.performClick()
                return
            }
            SystemClock.sleep(16L)
        } while (SystemClock.elapsedRealtime() < deadline)
        val state = model.uiState.value
        throw AssertionError("Expected visible $label at ${state.currentTimeMs} ms, " +
            "${state.playbackPhase}, intent=${state.viewerWantsPlay}.\n${ui.onRoot().printToString(maxDepth = 10)}")
    }

    private fun playAndPauseWithVisibleControls(model: DialogueLensViewModel) {
        val startMs = model.uiState.value.currentTimeMs
        // Live playback continuously invalidates the stage. Drive UI frames while the
        // real media clock advances so Espresso never waits for a playing video to end.
        ui.mainClock.autoAdvance = false
        try {
            tapRenderedTransport("Play", model)
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            fun advanced() = model.uiState.value.let {
                it.viewerWantsPlay && it.isPlaying && it.currentTimeMs >= startMs + 350L
            }
            while (!advanced() && SystemClock.elapsedRealtime() < deadline) {
                ui.mainClock.advanceTimeByFrame()
                SystemClock.sleep(25L)
            }
            assertTrue("Expected playback from $startMs, observed ${model.uiState.value.currentTimeMs} " +
                "in ${model.uiState.value.playbackPhase}", advanced())
            repeat(2) { ui.mainClock.advanceTimeByFrame() }
            ui.onNodeWithTag("heal_this_moment_button").assertDoesNotExist()
            tapRenderedTransport("Pause", model)
            ui.mainClock.advanceTimeByFrame()
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitUntil(5_000) { model.uiState.value.playbackPhase == PlaybackPhase.USER_PAUSED }
    }

    @Test
    fun tourIsOptionalSkippableAndReplayable() {
        ui.onNodeWithTag("first_time_onboarding_overlay").assertDoesNotExist()
        ui.onNodeWithTag("open_quick_tour").performScrollTo().performClick()
        ui.onNodeWithTag("first_time_onboarding_overlay").assertIsDisplayed()
        ui.onNodeWithTag("onboarding_skip_button").performClick()
        ui.onNodeWithTag("first_time_onboarding_overlay").assertDoesNotExist()
        ui.onNodeWithText("Replay tour").performScrollTo().performClick()
        repeat(2) { ui.onNodeWithTag("onboarding_next_button").performScrollTo().performClick() }
        ui.onNodeWithTag("onboarding_try_demo_button").performScrollTo().performClick()
        ui.onNodeWithTag("video_player_stage", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun landscapeTransportLeavesAVisibleStageAndControlsLivePlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun fixture(name: String): Uri {
            val target = File(instrumentation.targetContext.cacheDir, "landscape-$name")
            instrumentation.context.assets.open("playback/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return Uri.fromFile(target)
        }
        val video = fixture("two-lines.mp4")
        val sidecar = fixture("two-lines.srt")
        lateinit var model: DialogueLensViewModel
        val orientation = ui.activity.requestedOrientation
        try {
            ui.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                model = ViewModelProvider(activity)[DialogueLensViewModel::class.java]
                model.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)
                model.createProjectFromMedia(video, "Landscape playback", sidecar, "captions.srt", "English", "English",
                    TranslationMode.NATURAL, SubtitleSourceKind.UPLOADED_FILE, ProcessingQualityMode.FAST)
            }
            ui.waitUntil(20_000) {
                instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    model.uiState.value.project.videoSource.uriString == video.toString() &&
                    model.uiState.value.playerCanSeek && model.uiState.value.coverageSummary.isFullyVerified
            }
            val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
            val videoBox = ui.onNodeWithTag("video_player_stage", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Landscape video must occupy at least half the usable height", videoBox.height > root.height * .5f)
            ui.onNodeWithTag("timeline_seek_slider").assertIsDisplayed()
            val transport = ui.onNodeWithTag("toggle_play_pause_button").assertIsDisplayed()
            if (instrumentation.targetContext.resources.configuration.screenHeightDp < 500) {
                assertTrue("Landscape controls must lie within the video stage", videoBox.contains(transport.fetchSemanticsNode().boundsInRoot.center))
            }
            ui.onNodeWithContentDescription("More playback options").performClick()
            ui.onNodeWithText("Hide controls").performClick()
            ui.onNodeWithTag("reveal_watch_controls").assertIsDisplayed().performClick()
            playAndPauseWithVisibleControls(model)
            ui.onNodeWithContentDescription("Fullscreen").performClick()
            ui.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed().performClick()
            assertFalse(model.uiState.value.viewerWantsPlay)
        } finally {
            ui.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        }
    }

    @Test
    fun pauseMarkReviewAndReturnKeepsTheMomentAndIntent() {
        lateinit var model: DialogueLensViewModel
        ui.activityRule.scenario.onActivity { model = ViewModelProvider(it)[DialogueLensViewModel::class.java] }
        ui.onNodeWithText("Try the demo").performClick()
        ui.onNodeWithTag("heal_this_moment_button").assertDoesNotExist()

        playAndPauseWithVisibleControls(model)
        val pausedAt = model.uiState.value.currentTimeMs
        ui.onNodeWithTag("heal_this_moment_button").assertIsDisplayed().performClick()
        ui.waitUntil(5_000) { model.uiState.value.project.healBeacons.any { !it.autoTriggered } }
        val mark = model.uiState.value.project.healBeacons.single { !it.autoTriggered }
        assertEquals(pausedAt, mark.timestampMs)
        ui.onNode(hasTestTag("nav_bar_studio") or hasTestTag("nav_rail_studio")).performClick()
        ui.onNodeWithTag("inspector_tab_repairs").performScrollTo().performClick()
        ui.onNodeWithTag("studio_heal_beacons_list").assertIsDisplayed()
        ui.onNode(hasTestTag("nav_bar_watch") or hasTestTag("nav_rail_watch")).performClick()
        ui.onNodeWithTag("heal_this_moment_button").assertIsDisplayed()
        assertEquals(pausedAt, model.uiState.value.currentTimeMs)
        assertFalse(model.uiState.value.viewerWantsPlay)
    }
}
