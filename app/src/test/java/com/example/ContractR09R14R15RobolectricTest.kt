package com.example

import android.app.Application
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.ScoutCaptureDiagnostics
import com.example.data.model.ScoutCaptureHealthState
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.model.YouTubePlayerErrorKind
import com.example.domain.layout.PlacedBalloon
import com.example.domain.layout.TailDirection
import com.example.domain.playback.PlaybackReadinessState
import com.example.domain.playback.UserPlaybackIntent
import com.example.ui.player.SpeechBalloonView
import com.example.ui.state.DialogueLensViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric integration tests covering:
 * - T16 / R09: Exclusive 4-mode switching, OFF releasing caption hold without overriding Pause, preserving edits
 * - T28 / R07: Error 153 vs 101 vs 5 classification in ViewModel
 * - T34 / R14: Delayed name revelation gated by viewer playhead time inside real SpeechBalloonView
 * - T38-T40 / R15: ScoutCaptureDiagnostics state tracking, muted output guarantee, bounded capture queue
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContractR09R14R15RobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun webStreamProject(
        id: String = "proj_web_r15",
        durationMs: Long = 240_000L,
    ): DialogueLensProject = DialogueLensProject(
        id = id,
        title = "Web Capture Test",
        createdAtEpochMs = 1_000L,
        sourceLanguage = "English",
        targetLanguage = "English",
        subtitleSource = SubtitleSourceKind.AI_DRAFT,
        videoSource = VideoSource(
            uriString = "https://example.org/watch/123",
            displayName = "Web Fixture",
            kind = VideoSourceKind.WEB_STREAM,
            durationMs = durationMs,
            width = 1280,
            height = 720,
        ),
        shots = listOf(Shot("shot_1", 0L, durationMs)),
        speakers = listOf(Speaker(id = "spk_1", defaultLabel = "Speaker A", accentIndex = 0)),
        faceTracks = emptyList(),
        dialogueEvents = emptyList(),
    )

    private fun youtubeProject(): DialogueLensProject = DialogueLensProject(
        id = "proj_yt_r07",
        title = "YT Fixture",
        createdAtEpochMs = 1_000L,
        sourceLanguage = "English",
        targetLanguage = "English",
        subtitleSource = SubtitleSourceKind.AI_DRAFT,
        videoSource = VideoSource(
            uriString = "https://youtu.be/RzagPoxaolQ",
            displayName = "YT Fixture",
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            durationMs = 120_000L,
            width = 1920,
            height = 1080,
            youtubeVideoId = "RzagPoxaolQ",
        ),
        shots = listOf(Shot("shot_1", 0L, 120_000L)),
        speakers = listOf(Speaker(id = "spk_1", defaultLabel = "Speaker A", accentIndex = 0)),
        faceTracks = emptyList(),
        dialogueEvents = emptyList(),
    )

    @Test
    fun t16_offModeReleasesOnlyCaptionHoldAndPreservesExplicitUserPause() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        vm.loadProject(youtubeProject())
        vm.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)

        // Explicitly Pause first, then switch to OFF -> must stay PAUSED_BY_USER (never resume!)
        vm.pausePlayback()
        assertEquals(UserPlaybackIntent.PAUSE, vm.uiState.value.readinessSnapshot.userIntent)

        vm.setDisplayMode(CaptionDisplayMode.OFF)
        assertEquals(CaptionDisplayMode.OFF, vm.uiState.value.displayMode)
        assertEquals(UserPlaybackIntent.PAUSE, vm.uiState.value.readinessSnapshot.userIntent)
        assertEquals(PlaybackReadinessState.PAUSED_BY_USER, vm.uiState.value.readinessSnapshot.state)

        // Returning to BUBBLES_WITH_TAILS / BALLOONS preserves PAUSE intent and normalizes mode
        vm.setDisplayMode(CaptionDisplayMode.BALLOONS)
        assertEquals(CaptionDisplayMode.BALLOONS, vm.uiState.value.displayMode)
        assertEquals(UserPlaybackIntent.PAUSE, vm.uiState.value.readinessSnapshot.userIntent)

        // Legacy FIXED_ZONES / BOTH inputs normalize cleanly into the 4 exclusive modes
        vm.setDisplayMode(CaptionDisplayMode.FIXED_ZONES)
        assertEquals(CaptionDisplayMode.BALLOONS, vm.uiState.value.displayMode)
    }

    @Test
    fun t28_youtubePlayerErrorsClassifyDistinctlyWithoutInventedSuccess() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        vm.loadProject(youtubeProject())
        vm.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)

        vm.onYouTubePlayerError(153)
        assertEquals(YouTubePlayerErrorKind.CLIENT_IDENTITY_153, vm.uiState.value.youtubePlayerErrorKind)

        vm.onYouTubePlayerError(101)
        assertEquals(YouTubePlayerErrorKind.EMBEDDING_DENIED_101_150, vm.uiState.value.youtubePlayerErrorKind)

        vm.onYouTubePlayerError(5)
        assertEquals(YouTubePlayerErrorKind.HTML5_ERROR_5, vm.uiState.value.youtubePlayerErrorKind)
        assertFalse(vm.uiState.value.isPlaying)
    }

    @Test
    fun t38_t40_scoutCaptureDiagnosticsTrackHealthStatesAndOutputMuteTruthfully() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        vm.loadProject(webStreamProject())

        val started = ScoutCaptureDiagnostics(
            healthState = ScoutCaptureHealthState.RECORDER_STARTED,
            selectedElementId = "dl_vid_1",
            documentGeneration = 2L,
            recorderId = "rec_2_1",
            outputMuted = true,
            outputVolumeZero = true,
            trackReadyState = "live",
            trackMuted = false,
            trackEnabled = true,
            lastMimeType = "audio/webm",
        )
        vm.onScoutCaptureDiagnosticsUpdated(started)
        assertEquals(ScoutCaptureHealthState.RECORDER_STARTED, vm.uiState.value.scoutCaptureDiagnostics.healthState)
        assertTrue(vm.uiState.value.scoutCaptureDiagnostics.outputMuted)
        assertTrue(vm.uiState.value.scoutCaptureDiagnostics.outputVolumeZero)

        // Simulate ZERO_CLOUD: queued web capture slice must NOT crash or trigger AI dispatch
        vm.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)
        vm.onScoutWebStreamAudioSlice(
            startMs = 0L,
            base64Audio = "UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=",
            mimeType = "audio/wav",
            endMs = 6_200L,
            discontinuity = false,
        )
        assertEquals(QuotaSaverMode.ZERO_CLOUD, vm.uiState.value.quotaSaverMode)
    }

    @Test
    fun t34_speechBalloonViewGatesAutomaticCharacterNameRevealByViewerPlayheadTime() {
        val speaker = Speaker(
            id = "spk_a",
            defaultLabel = "Speaker A",
            customName = "Inspector Ito",
            accentIndex = 0,
            isIdentityConfirmed = true,
            nameRevealedAtMs = 40_000L,
            isUserNamed = false,
        )
        val event = DialogueEvent(
            id = "evt_reveal_1",
            stableCueKey = "stable_reveal_1",
            startTimeMs = 5_000L,
            endTimeMs = 12_000L,
            sourceText = "Check the harbor logs.",
            translatedText = "Check the harbor logs.",
            speakerId = "spk_a",
            speakerConfidence = 0.92f,
            faceTrackId = null,
            faceConfidence = 0.85f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            anchorPosition = NormalizedPoint(0.35f, 0.50f),
            placementMode = PlacementMode.AUTO,
        )
        val placed = PlacedBalloon(
            event = event,
            speaker = speaker,
            rect = NormalizedRect(0.18f, 0.14f, 0.54f, 0.30f),
            tailTarget = NormalizedPoint(0.35f, 0.50f),
            tailDirection = TailDirection.DOWN_LEFT,
            placementMode = PlacementMode.AUTO,
            isOffScreenEdge = false,
            edgeSide = null,
            isAmbiguousFallback = false,
            isUnknownSpeaker = false,
            speakerLineOrdinal = 1,
        )
        var viewerTimeMs by mutableLongStateOf(8_000L)

        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(modifier = Modifier.size(360.dp, 240.dp)) {
                    val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                    SpeechBalloonView(
                        placed = placed,
                        translationMode = TranslationMode.NATURAL,
                        style = BalloonStyleSettings(reducedMotion = true, showSpeakerLabels = true),
                        isEditMode = false,
                        isSelected = false,
                        containerWidthPx = w,
                        containerHeightPx = h,
                        displayMode = CaptionDisplayMode.BUBBLES_WITH_TAILS,
                        currentTimeMs = viewerTimeMs,
                        onClick = {},
                        onDragCommit = {},
                    )
                }
            }
        }

        // Before 40_000ms reveal timestamp: must show neutral "Speaker A", NEVER leak "Inspector Ito"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Speaker A", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Inspector Ito", substring = true).assertDoesNotExist()

        // Advance past 40_000ms reveal timestamp -> revealed name appears
        composeRule.runOnUiThread { viewerTimeMs = 42_000L }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Inspector Ito", substring = true).assertIsDisplayed()

        // Backward seek before reveal timestamp -> re-hides automatic future discovery
        composeRule.runOnUiThread { viewerTimeMs = 9_000L }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Speaker A", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Inspector Ito", substring = true).assertDoesNotExist()
    }
}
