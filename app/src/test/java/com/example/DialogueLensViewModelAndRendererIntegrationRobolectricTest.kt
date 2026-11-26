package com.example

import android.app.Application
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CaptionOriginKind
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.ReferenceCaptionCue
import com.example.data.model.ReferenceCaptionTrack
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.project.ProjectStore
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.LiveStageFrameSnapshot
import com.example.domain.layout.PlacedBalloon
import com.example.domain.layout.TailDirection
import com.example.domain.layout.VisualStabilityDiagnostics
import com.example.ui.player.SpeechBalloonView
import com.example.ui.state.DialogueLensViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end Robolectric integration tests covering the REAL [DialogueLensViewModel],
 * REAL [SpeechBalloonView] with normal motion (`reducedMotion = false`), and REAL [ProjectStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DialogueLensViewModelAndRendererIntegrationRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun makeProject(
        id: String = "yt_proj_1",
        videoId: String = "RzagPoxaolQ",
        subtitleSource: SubtitleSourceKind = SubtitleSourceKind.EMBEDDED_TRACK,
        events: List<DialogueEvent> = emptyList(),
    ): DialogueLensProject {
        val spk = Speaker(id = "spk_1", defaultLabel = "Speaker 1", accentIndex = 0)
        return DialogueLensProject(
            id = id,
            title = "Integration Test Video",
            createdAtEpochMs = 1_000L,
            videoSource = VideoSource(
                uriString = "https://youtu.be/$videoId",
                displayName = "Integration Test Video",
                kind = VideoSourceKind.YOUTUBE_COMPLIANT,
                durationMs = 120_000L,
                width = 1920,
                height = 1080,
                youtubeVideoId = videoId,
            ),
            subtitleSource = subtitleSource,
            sourceLanguage = "English",
            targetLanguage = "English",
            shots = listOf(Shot("shot_1", 0L, 120_000L, cutType = "EMBEDDED_YOUTUBE")),
            speakers = listOf(spk),
            faceTracks = emptyList(),
            dialogueEvents = events,
        )
    }

    private fun makePlaced(
        id: String,
        stableKey: String,
        sourceText: String,
        translatedText: String = sourceText,
        needsTranslation: Boolean = false,
        centerX: Float = 0.36f,
        centerY: Float = 0.24f,
    ): PlacedBalloon {
        val speaker = Speaker(id = "spk_1", defaultLabel = "Host", accentIndex = 0)
        val evt = DialogueEvent(
            id = id,
            startTimeMs = 1_000L,
            endTimeMs = 9_000L,
            sourceText = sourceText,
            translatedText = translatedText,
            speakerId = speaker.id,
            speakerConfidence = 0.92f,
            faceTrackId = null,
            faceConfidence = 0.85f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            anchorPosition = NormalizedPoint(0.32f, 0.56f),
            placementMode = PlacementMode.AUTO,
            stableCueKey = stableKey,
            needsTranslation = needsTranslation,
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
    fun viewModelSilenceToDialogueHandoffReachesPlaybackAndPreservesLockedEdits() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val lockedCue = DialogueEvent(
            id = "locked_1",
            startTimeMs = 1_000L,
            endTimeMs = 3_000L,
            sourceText = "User edited line",
            translatedText = "User edited line",
            speakerId = "spk_1",
            speakerConfidence = 1.0f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            lockedByUser = true,
            stableCueKey = "stable_locked_1",
        )
        val proj = makeProject(events = listOf(lockedCue))
        vm.loadProject(proj)

        // Attach reference captions containing spoken dialogue at 5s..8s
        val refTrack = ReferenceCaptionTrack(
            mediaId = proj.id,
            trackId = "yt_ref_en",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            cues = listOf(
                ReferenceCaptionCue("r_5s", 5_000L, 8_500L, "Recovered spoken dialogue from reference."),
            ),
        )
        vm.attachReferenceTrackForTest(refTrack)

        val ctx = vm.captureWorkRequestContext()
        // Scout 1 mistakenly reports silence with empty events
        val (outcome, _) = vm.stageAndReviewIntervalThroughReleaseGate(
            project = vm.uiState.value.project,
            intervalId = "iv_0_15000",
            startMs = 0L,
            endMs = 15_000L,
            events = emptyList(),
            speakers = vm.uiState.value.project.speakers,
            sessionToken = ctx.sessionToken,
            seekGeneration = ctx.seekGeneration,
            isConfirmedSilence = true,
            expectedConfigFingerprint = ctx.configFingerprint,
        )
        assertTrue("Scout 2 must recover spoken dialogue from reference", outcome != null && outcome.repairedEvents.isNotEmpty())

        // Publish through the authoritative path even if caller originally thought VERIFIED_SILENCE
        vm.publishReleasedEventsToProject(
            startMs = 0L,
            endMs = 15_000L,
            releasedEvents = outcome!!.repairedEvents,
            coverageStatus = CoverageStatus.VERIFIED_SILENCE,
            source = SubtitleSourceKind.EMBEDDED_TRACK,
        )

        val finalEvents = vm.uiState.value.project.dialogueEvents
        assertTrue(
            "Recovered dialogue must reach live playback project.dialogueEvents",
            finalEvents.any { it.sourceText.contains("Recovered spoken dialogue") },
        )
        assertTrue(
            "User-locked edit inside the interval must be preserved",
            finalEvents.any { it.id == "locked_1" && it.sourceText == "User edited line" && it.lockedByUser },
        )
        assertNotEquals(
            "Interval with recovered spoken dialogue must NEVER be marked VERIFIED_SILENCE",
            CoverageStatus.VERIFIED_SILENCE,
            vm.coverageStatusAtForTest(6_000L),
        )
    }

    @Test
    fun viewModelRejectsStaleRequestContextsStaleVisionSnapshotsAndCrossVideoTimedText() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val proj = makeProject(id = "proj_active", videoId = "RzagPoxaolQ")
        vm.loadProject(proj)

        val initialCtx = vm.captureWorkRequestContext()
        assertTrue(vm.isRequestContextCurrent(initialCtx))

        // Changing quota mode invalidates previous WorkRequestContext
        vm.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)
        assertFalse("Config change must invalidate earlier WorkRequestContext", vm.isRequestContextCurrent(initialCtx))

        // Stale LiveStageFrameSnapshot with wrong projectId must be ignored
        val staleSnap = LiveStageFrameSnapshot(
            faces = listOf(
                LiveFaceObservation(
                    trackingId = 99,
                    boundingBox = NormalizedRect(0.2f, 0.2f, 0.4f, 0.5f),
                    mouthPoint = NormalizedPoint(0.3f, 0.4f),
                    headYawDeg = 0f,
                    capturedAtMs = 1_000L,
                ),
            ),
            visualCutDetected = false,
            cutDistance = 0f,
            frameLumaAverage = 0.5f,
            capturedAtMs = 1_000L,
            projectId = "other_project_id",
            seekGeneration = 0L,
            observationSeq = 5L,
        )
        vm.onLiveStageFrameSnapshot(1_000L, staleSnap)
        assertTrue("Stale projectId snapshot must not update live faces", vm.currentLiveFacesForTest().isEmpty())

        // Cross-video timedtext URL must be ignored
        val eventsBefore = vm.uiState.value.project.dialogueEvents.size
        vm.onYouTubeTimedTextCaptured(
            observedUrl = "https://www.youtube.com/api/timedtext?v=DIFFERENT_ID&lang=en",
            observedBody = """{"events":[{"tStartMs":1000,"dDurationMs":3000,"segs":[{"utf8":"Wrong video line"}]}]}""",
            cookies = null,
            userAgent = null,
        )
        assertEquals(
            "Cross-video timedtext payload must be ignored",
            eventsBefore,
            vm.uiState.value.project.dialogueEvents.size,
        )

        // ZERO_CLOUD blocks web audio slice before dispatch
        vm.onScoutWebStreamAudioSlice(startMs = 0L, base64Audio = "AAAA", mimeType = "audio/webm")
        assertTrue(vm.uiState.value.quotaSaverMode == QuotaSaverMode.ZERO_CLOUD)
    }

    @Test
    fun viewModelPreservesPlaybackProgressIncrementsMeasurementEpochAndHonorsIncompleteSavedAi() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val savedAiProj = makeProject(
            id = "proj_saved_ai",
            subtitleSource = SubtitleSourceKind.AI_DRAFT,
            events = listOf(
                DialogueEvent(
                    id = "ai_1",
                    startTimeMs = 1_000L,
                    endTimeMs = 12_000L,
                    sourceText = "Saved opening dialogue",
                    translatedText = "Saved opening dialogue",
                    speakerId = "spk_1",
                    speakerConfidence = 0.9f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "shot_1",
                ),
            ),
        )
        vm.loadProject(savedAiProj)
        assertFalse(
            "Incomplete saved AI project must not be marked fully verified",
            vm.uiState.value.coverageSummary.isFullyVerified,
        )

        // Viewport / font scale change increments measurementEpoch
        val epochBefore = vm.uiState.value.measurementEpoch
        vm.onViewportOrFontScaleChanged(widthPx = 1080, heightPx = 608, fontScale = 1.25f)
        assertEquals(epochBefore + 1L, vm.uiState.value.measurementEpoch)

        // Playback progress persisted must stay reflected in project.lastPlaybackPositionMs
        vm.maybePersistPlaybackProgress(42_500L)
        assertEquals(42_500L, vm.uiState.value.project.lastPlaybackPositionMs)
    }

    @Test
    fun speechBalloonViewNormalMotionZeroReplayUntranslatedBadgeAndMeasurementEpoch() {
        val diagnostics = VisualStabilityDiagnostics()
        var currentPlaced by mutableStateOf(
            makePlaced(
                id = "cue_nm_1",
                stableKey = "stable_nm_1",
                sourceText = "今すぐ出発しよう",
                translatedText = "",
                needsTranslation = true,
            ),
        )
        var epoch by mutableLongStateOf(0L)
        var measuredReports = 0

        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(modifier = Modifier.size(320.dp, 240.dp)) {
                    val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                    SpeechBalloonView(
                        placed = currentPlaced,
                        translationMode = TranslationMode.NATURAL,
                        // Explicitly test NORMAL motion (reducedMotion = false)!
                        style = BalloonStyleSettings(reducedMotion = false),
                        isEditMode = false,
                        isSelected = false,
                        containerWidthPx = stageW,
                        containerHeightPx = stageH,
                        faceExclusionRects = listOf(NormalizedRect(0.24f, 0.20f, 0.44f, 0.46f)),
                        measurementEpoch = epoch,
                        onClick = {},
                        onDragCommit = {},
                        onMeasuredNormalizedSize = { _, _ -> measuredReports++ },
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
        // Untranslated original text AND "Untranslated" badge must be displayed (Decision 1)
        composeRule.onNodeWithText("今すぐ出発しよう", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Untranslated", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("untranslated_badge_stable_nm_1", useUnmergedTree = true).assertIsDisplayed()
        val initialReports = measuredReports
        assertTrue("Initial measured size must be reported", initialReports >= 1)

        // Incrementing measurementEpoch must force a fresh size report even when dimensions are identical
        composeRule.runOnUiThread { epoch = 1L }
        composeRule.waitForIdle()
        assertTrue("Incrementing measurementEpoch must emit fresh dimension report", measuredReports > initialReports)

        // Translation arrives for the same stableCueKey -> zero entry animation replay, badge disappears
        composeRule.runOnUiThread {
            currentPlaced = makePlaced(
                id = "cue_nm_1",
                stableKey = "stable_nm_1",
                sourceText = "今すぐ出発しよう",
                translatedText = "Let's head out right now.",
                needsTranslation = false,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Let's head out right now.", substring = true).assertIsDisplayed()

        val snap = diagnostics.snapshot()
        assertEquals("Normal-motion update sharing stableCueKey must have zero entry replays", 0, snap.entryAnimationReplays)
        assertEquals("Normal-motion single-authority renderer must have zero oscillations", 0, snap.renderedOscillations)
    }
}

/**
 * Verifies API 24-28 (Android 9 / API 28) zero-permission app-scoped export fallback in [ProjectStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectStoreLegacyApiExportRobolectricTest {

    @Test
    fun saveTextToDownloadsWorksCleanlyOnApi28WithoutUnsupportedOperationException() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = ProjectStore(context)
        val res = store.saveTextToDownloads(
            fileName = "audit_export_test.srt",
            mimeType = "application/x-subrip",
            content = "1\n00:00:01,000 --> 00:00:03,000\nHello API 28\n",
        )
        assertTrue("saveTextToDownloads must succeed cleanly on API 28 (was: ${res.exceptionOrNull()})", res.isSuccess)
    }
}
