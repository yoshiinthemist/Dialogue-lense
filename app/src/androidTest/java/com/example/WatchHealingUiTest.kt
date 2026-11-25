package com.example

import androidx.compose.ui.test.assertIsDisplayed
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private fun tapPause() {
        if (ui.onAllNodesWithText("Show controls").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithText("Show controls").assertIsDisplayed().performClick()
        }
        ui.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
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
            ui.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
            ui.waitUntil(10_000) { model.uiState.value.isPlaying && model.uiState.value.currentTimeMs > 350L }
            tapPause()
            ui.waitUntil(5_000) { model.uiState.value.playbackPhase == PlaybackPhase.USER_PAUSED }
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

        val start = model.uiState.value.currentTimeMs
        ui.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
        ui.waitUntil(5_000) {
            val state = model.uiState.value
            state.viewerWantsPlay && state.isPlaying && state.currentTimeMs > start
        }
        ui.onNodeWithTag("heal_this_moment_button").assertDoesNotExist()
        tapPause()
        ui.waitUntil(5_000) { model.uiState.value.playbackPhase == PlaybackPhase.USER_PAUSED }
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
