package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.Speaker
import com.example.data.model.TranslationMode
import com.example.domain.layout.PlacedBalloon
import com.example.domain.layout.TailDirection
import com.example.domain.layout.VisualStabilityDiagnostics
import com.example.ui.player.SpeechBalloonView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renderer-level Robolectric + Compose UI test validating the ACTUAL [SpeechBalloonView]
 * rendering path:
 * - Single-authority placement without competing ad-hoc pixel jumps when noisy faces appear
 * - Persistent cue identity across rolling caption updates & controlled visible corrections
 * - Zero entry animation replays and zero rendered oscillations in [VisualStabilityDiagnostics]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeechBalloonRendererStabilityRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun makePlaced(
        id: String,
        stableKey: String,
        text: String,
        centerX: Float = 0.34f,
        centerY: Float = 0.24f,
    ): PlacedBalloon {
        val speaker = Speaker(id = "spk_1", defaultLabel = "Host", accentIndex = 0)
        val evt = DialogueEvent(
            id = id,
            startTimeMs = 1_000L,
            endTimeMs = 9_000L,
            sourceText = text,
            translatedText = text,
            speakerId = speaker.id,
            speakerConfidence = 0.90f,
            faceTrackId = null,
            faceConfidence = 0.85f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            anchorPosition = NormalizedPoint(0.32f, 0.56f),
            placementMode = PlacementMode.AUTO,
            stableCueKey = stableKey,
        )
        return PlacedBalloon(
            event = evt,
            speaker = speaker,
            rect = NormalizedRect(
                left = (centerX - 0.16f).coerceIn(0.02f, 0.90f),
                top = (centerY - 0.08f).coerceIn(0.02f, 0.90f),
                right = (centerX + 0.16f).coerceIn(0.10f, 0.98f),
                bottom = (centerY + 0.08f).coerceIn(0.10f, 0.98f),
            ),
            tailTarget = NormalizedPoint(0.32f, 0.56f),
            tailDirection = TailDirection.DOWN_LEFT,
            placementMode = PlacementMode.AUTO,
            isOffScreenEdge = false,
            edgeSide = null,
            isAmbiguousFallback = false,
            isUnknownSpeaker = false,
        )
    }

    @Test
    fun speechBalloonPreservesIdentityAcrossRollingUpdatesAndIgnoresCompetingFaceJumps() {
        val diagnostics = VisualStabilityDiagnostics()
        var currentPlaced by mutableStateOf(
            makePlaced(id = "cue_1", stableKey = "stable_cue_1", text = "We need to fix")
        )
        var currentFaces by mutableStateOf(
            listOf(NormalizedRect(0.24f, 0.20f, 0.44f, 0.46f))
        )

        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier.size(640.dp, 360.dp),
                ) {
                    val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                    SpeechBalloonView(
                        placed = currentPlaced,
                        translationMode = TranslationMode.NATURAL,
                        style = BalloonStyleSettings(reducedMotion = true),
                        isEditMode = false,
                        isSelected = false,
                        containerWidthPx = stageW,
                        containerHeightPx = stageH,
                        faceExclusionRects = currentFaces,
                        onClick = {},
                        onDragCommit = {},
                        onRenderedFrameReported = { cueKey, leftPx, topPx, widthPx, heightPx ->
                            diagnostics.recordRenderedFrame(
                                cueKey = cueKey,
                                renderedLeftPx = leftPx,
                                renderedTopPx = topPx,
                                widthPx = widthPx,
                                heightPx = heightPx,
                                containerWidthPx = stageW,
                                containerHeightPx = stageH,
                            )
                        },
                        onEntryAnimationStarted = { cueKey ->
                            diagnostics.recordEntryAnimationStarted(cueKey)
                        },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("We need to fix", substring = true).assertIsDisplayed()

        // 1. Progressive rolling caption update preserving stableCueKey must NOT replay entry animation
        composeRule.runOnUiThread {
            currentPlaced = makePlaced(
                id = "cue_1_rev2",
                stableKey = "stable_cue_1",
                text = "We need to fix the roads",
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("We need to fix the roads", substring = true).assertIsDisplayed()

        // 2. Oscillating noisy face boxes overlapping the balloon must NOT cause competing renderer jumps
        //    (BalloonLayoutEngine is the single placement authority)
        val noisyFaces = listOf(
            listOf(NormalizedRect(0.22f, 0.16f, 0.46f, 0.48f)),
            emptyList(),
            listOf(NormalizedRect(0.26f, 0.18f, 0.48f, 0.50f)),
            emptyList(),
        )
        noisyFaces.forEach { faceList ->
            composeRule.runOnUiThread {
                currentFaces = faceList
            }
            composeRule.waitForIdle()
        }

        // 3. Controlled visible text correction preserves identity with zero entry replay
        composeRule.runOnUiThread {
            currentPlaced = makePlaced(
                id = "cue_1_rev2",
                stableKey = "stable_cue_1",
                text = "We need to fix the bridges",
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("We need to fix the bridges", substring = true).assertIsDisplayed()

        val snap = diagnostics.snapshot()
        assertTrue("Expected rendered frame samples to be captured (was ${snap.renderedFrameSamples})", snap.renderedFrameSamples >= 1)
        assertEquals(
            "Rolling updates and controlled corrections sharing stableCueKey must NEVER replay entry animation",
            0,
            snap.entryAnimationReplays,
        )
        assertEquals(
            "Single-authority renderer must produce zero rendered oscillations under noisy face boxes",
            0,
            snap.renderedOscillations,
        )
        assertEquals(
            "Single-authority renderer must produce zero large competing pixel jumps",
            0,
            snap.renderedLargeJumps,
        )
    }
}
