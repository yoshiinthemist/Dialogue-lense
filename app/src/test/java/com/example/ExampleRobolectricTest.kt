package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.domain.analysis.WebNavigationState
import com.example.ui.player.WebBrowserControlStrip
import com.example.ui.theme.DialogueLensTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("DialogueLens", appName)
  }

  @Test
  fun browserControlsBarTriggersBackForwardRefreshAndScoutInspector() {
    var backClicks = 0
    var forwardClicks = 0
    var refreshClicks = 0
    var scoutClicks = 0

    composeTestRule.setContent {
      DialogueLensTheme {
        WebBrowserControlStrip(
          seedUrl = "https://missav.ws/en/sample",
          viewerNav = WebNavigationState(
            currentUrl = "https://missav.ws/en/sample",
            currentHost = "missav.ws",
            canGoBack = true,
            canGoForward = true,
            hasActiveVideo = true,
          ),
          scoutNav = WebNavigationState(
            currentUrl = "https://stripchat.com",
            currentHost = "stripchat.com",
            hasActiveVideo = false,
            isAdRedirectSuspected = true,
          ),
          scoutTimeMs = 0L,
          dualInstanceEnabled = true,
          onViewerBack = { backClicks++ },
          onViewerForward = { forwardClicks++ },
          onViewerRefresh = { refreshClicks++ },
          onViewerResetSource = {},
          onViewerAllowOnce = {},
          onOpenScoutModal = { scoutClicks++ },
        )
      }
    }

    composeTestRule.onNodeWithText("Browser Controls").assertIsDisplayed()
    composeTestRule.onNodeWithTag("web_viewer_back_button").assertIsEnabled().performClick()
    composeTestRule.onNodeWithTag("web_viewer_forward_button").assertIsEnabled().performClick()
    composeTestRule.onNodeWithTag("web_viewer_refresh_button").assertIsEnabled().performClick()
    composeTestRule.onNodeWithTag("open_scout_browser_button").assertIsDisplayed().performClick()

    assertEquals(1, backClicks)
    assertEquals(1, forwardClicks)
    assertEquals(1, refreshClicks)
    assertTrue(scoutClicks == 1)
  }
}
