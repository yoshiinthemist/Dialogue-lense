package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
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

    @Test
    fun pauseMarkReviewAndReturnKeepsTheMomentAndIntent() {
        lateinit var model: DialogueLensViewModel
        ui.activityRule.scenario.onActivity { model = ViewModelProvider(it)[DialogueLensViewModel::class.java] }
        ui.onNodeWithText("Try the demo").performClick()
        ui.onNodeWithTag("heal_this_moment_button").assertDoesNotExist()

        ui.onNodeWithTag("toggle_play_pause_button").performClick()
        ui.waitUntil(5_000) { model.uiState.value.viewerWantsPlay }
        ui.onNodeWithTag("heal_this_moment_button").assertDoesNotExist()
        ui.onNodeWithTag("toggle_play_pause_button").performClick()
        ui.waitUntil(5_000) { model.uiState.value.playbackPhase == PlaybackPhase.USER_PAUSED }
        val pausedAt = model.uiState.value.currentTimeMs
        ui.onNodeWithTag("heal_this_moment_button").assertIsDisplayed().performClick()
        ui.waitUntil(5_000) { model.uiState.value.project.healBeacons.any { !it.autoTriggered } }
        val mark = model.uiState.value.project.healBeacons.single { !it.autoTriggered }
        assertEquals(pausedAt, mark.timestampMs)
        ui.onNode(hasTestTag("nav_bar_studio") or hasTestTag("nav_rail_studio")).performClick()
        ui.onNodeWithTag("inspector_tab_repairs").performClick()
        ui.onNodeWithTag("studio_heal_beacons_list").assertIsDisplayed()
        ui.onNode(hasTestTag("nav_bar_watch") or hasTestTag("nav_rail_watch")).performClick()
        ui.onNodeWithTag("heal_this_moment_button").assertIsDisplayed()
        assertEquals(pausedAt, model.uiState.value.currentTimeMs)
        assertFalse(model.uiState.value.viewerWantsPlay)
    }
}
