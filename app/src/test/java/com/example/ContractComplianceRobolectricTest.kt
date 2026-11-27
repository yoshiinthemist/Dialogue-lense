package com.example

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.NormalizedPoint
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.model.YouTubeNativeCcState
import com.example.domain.analysis.SniffedWebMediaStream
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.playback.PlaybackReadinessController
import com.example.domain.playback.UserPlaybackIntent
import com.example.domain.sentinel.BoundarySubmissionDecision
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import com.example.ui.state.DialogueLensViewModel
import com.example.ui.state.HumanReadableStatus
import com.example.ui.state.ScoutCaptionState
import com.example.ui.state.parseCapturedWebTrackJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract compliance Robolectric suite verifying R01-R13 / T03, T04, T06, T14, T21, T24
 * against the real [DialogueLensViewModel], [HumanReadableStatus], and [SentinelWorkforceCoordinator].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContractComplianceRobolectricTest {

    private fun sampleYouTubeProject(
        id: String = "proj_contract_yt",
        sourceLang: String = "English",
        targetLang: String = "English",
        durationMs: Long = 1_095_000L,
        events: List<DialogueEvent> = emptyList(),
    ): DialogueLensProject = DialogueLensProject(
        id = id,
        title = "Contract YouTube Test",
        createdAtEpochMs = 1_000L,
        sourceLanguage = sourceLang,
        targetLanguage = targetLang,
        subtitleSource = SubtitleSourceKind.AI_DRAFT,
        videoSource = VideoSource(
            uriString = "https://youtu.be/RzagPoxaolQ",
            displayName = "Contract Video",
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            durationMs = durationMs,
            width = 1920,
            height = 1080,
            youtubeVideoId = "RzagPoxaolQ",
        ),
        shots = listOf(Shot("shot_1", 0L, durationMs, cutType = "EMBEDDED_YOUTUBE")),
        speakers = listOf(
            Speaker(id = "spk_1", defaultLabel = "Speaker 1", accentIndex = 0),
        ),
        faceTracks = emptyList(),
        dialogueEvents = events,
    )

    @Test
    fun youtubeNativeCcState_distinguishesAllSixStatesTracksActiveChangesAndSurvivesViewerOverlays() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val proj = sampleYouTubeProject()
        vm.loadProject(proj)

        assertEquals(YouTubeNativeCcState.LOADING, vm.uiState.value.youtubeNativeCcState)

        // 0 embeddable tracks and not enabled -> UNAVAILABLE_OR_BLOCKED
        vm.onYouTubeCaptionTrackStatus(
            trackCount = 0,
            activeLang = "",
            trackEnabled = false,
            activeCuePresent = false,
            probeBlocked = false,
        )
        assertEquals(YouTubeNativeCcState.UNAVAILABLE_OR_BLOCKED, vm.uiState.value.youtubeNativeCcState)

        // Enabled track whose DOM cues cannot be probed -> ENABLED_INACCESSIBLE
        vm.onYouTubeCaptionTrackStatus(
            trackCount = 1,
            activeLang = "en",
            trackEnabled = true,
            activeCuePresent = false,
            probeBlocked = true,
        )
        assertEquals(YouTubeNativeCcState.ENABLED_INACCESSIBLE, vm.uiState.value.youtubeNativeCcState)

        // Available tracks disabled -> OFF
        vm.onYouTubeCaptionTrackStatus(
            trackCount = 2,
            activeLang = "en",
            trackEnabled = false,
            activeCuePresent = false,
            probeBlocked = false,
        )
        assertEquals(YouTubeNativeCcState.OFF, vm.uiState.value.youtubeNativeCcState)

        // Enabled track between cues -> ENABLED_NO_CURRENT_CUE (never misclassified as unavailable or silence)
        vm.onYouTubeCaptionTrackStatus(
            trackCount = 2,
            activeLang = "en",
            trackEnabled = true,
            activeCuePresent = false,
            probeBlocked = false,
        )
        assertEquals(YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE, vm.uiState.value.youtubeNativeCcState)

        // Active-track language change with unchanged trackCount=2 updates state faithfully
        vm.onYouTubeCaptionTrackStatus(
            trackCount = 2,
            activeLang = "ja",
            trackEnabled = true,
            activeCuePresent = true,
            probeBlocked = false,
        )
        assertEquals(YouTubeNativeCcState.ENABLED_ACTIVE_CUES, vm.uiState.value.youtubeNativeCcState)
        assertEquals("ja", vm.uiState.value.youtubeNativeCcLang)

        // Viewer overlay preference (Balloons only vs Classic/Off) never disables timedtext capture or switches CC off
        vm.setDisplayMode(CaptionDisplayMode.BALLOONS)
        vm.onYouTubeTimedTextCaptured(
            observedUrl = "https://www.youtube.com/api/timedtext?v=RzagPoxaolQ&lang=en",
            observedBody = """{"events":[{"tStartMs":10000,"dDurationMs":3000,"segs":[{"utf8":"Captured line"}]}]}""",
            cookies = null,
            userAgent = null,
        )
        val deadlineMs = System.currentTimeMillis() + 3_000L
        while (!vm.uiState.value.youtubeCapabilities.hasProgrammaticCaptions && System.currentTimeMillis() < deadlineMs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        assertTrue(vm.uiState.value.youtubeCapabilities.hasProgrammaticCaptions)
        vm.seekTo(11_000L)
        assertEquals(YouTubeNativeCcState.ENABLED_ACTIVE_CUES, vm.uiState.value.youtubeNativeCcState)
        vm.seekTo(50_000L)
        assertEquals(YouTubeNativeCcState.ENABLED_NO_CURRENT_CUE, vm.uiState.value.youtubeNativeCcState)
    }

    @Test
    fun isSameResolvedLanguage_handlesAutoDetectCodeSwitchingAndSameLanguagePreservation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)

        // Auto-Detect is unresolved — never assumed to match English even for Latin script
        assertFalse(vm.isSameResolvedLanguage("Auto-Detect", "English", listOf("Hello everyone")))
        assertFalse(vm.isSameResolvedLanguage("auto", "Spanish", listOf("Buenos dias")))

        // Foreign CJK/Kana with Auto-Detect -> Japanese resolves unambiguously as matching
        assertTrue(vm.isSameResolvedLanguage("Auto-Detect", "Japanese", listOf("おはようございます")))

        // Declared English->English with code-switched foreign cues must NOT suppress translation
        assertFalse(vm.isSameResolvedLanguage("English", "English", listOf("Welcome back", "今すぐ出発しよう")))

        // Resolved matching English->English allows direct faithful preservation without redundant translation
        assertTrue(vm.isSameResolvedLanguage("English", "English", listOf("Welcome back to the show.")))
    }

    @Test
    fun midRequestSourceEditAndLanguageSwitch_invalidateStaleTranslationsPreserveAnchorsAndRejectStaleContexts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val anchoredPos = NormalizedPoint(0.36f, 0.58f)
        val unlockedCue = DialogueEvent(
            id = "cue_1",
            startTimeMs = 1_000L,
            endTimeMs = 4_000L,
            sourceText = "古い台詞",
            translatedText = "Old translation",
            speakerId = "spk_1",
            speakerConfidence = 0.9f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            anchorPosition = anchoredPos,
            placementMode = PlacementMode.AUTO,
            stableCueKey = "stable_cue_1",
            lockedByUser = false,
            needsTranslation = false,
        )
        val lockedForeignCue = DialogueEvent(
            id = "cue_locked",
            startTimeMs = 5_000L,
            endTimeMs = 8_000L,
            sourceText = "ありがとう",
            translatedText = "Pinned custom translation",
            speakerId = "spk_1",
            speakerConfidence = 1.0f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            anchorPosition = NormalizedPoint(0.62f, 0.44f),
            placementMode = PlacementMode.AUTO,
            stableCueKey = "stable_locked",
            lockedByUser = true,
            needsTranslation = false,
        )
        val proj = sampleYouTubeProject(
            sourceLang = "Japanese",
            targetLang = "English",
            events = listOf(unlockedCue, lockedForeignCue),
        )
        vm.loadProject(proj)
        vm.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD) // keep test deterministic with zero network calls

        val preEditCtx = vm.captureWorkRequestContext()
        val revBefore = vm.uiState.value.releaseSnapshot.releaseRevision

        // Source edit on cross-language cue must invalidate stale translation, bump revision, and preserve anchor/key
        vm.updateDialogueEvent(eventId = "cue_1", sourceText = "新しい台詞")
        assertFalse("Substantive source edit must invalidate pre-edit WorkRequestContext", vm.isRequestContextCurrent(preEditCtx))
        assertTrue("Release revision must advance on source edit", vm.uiState.value.releaseSnapshot.releaseRevision > revBefore)

        val editedCue = vm.uiState.value.project.dialogueEvents.first { it.id == "cue_1" }
        assertEquals("新しい台詞", editedCue.sourceText)
        assertEquals("", editedCue.translatedText)
        assertTrue(editedCue.needsTranslation)
        assertEquals(anchoredPos, editedCue.anchorPosition)
        assertEquals("stable_cue_1", editedCue.stableCueKey)

        // Switching target language mid-request rejects prior configFingerprint, clears unlocked translations, and keeps user-locked cue intact
        val preLangCtx = vm.captureWorkRequestContext()
        vm.updateLanguageConfiguration(sourceLanguage = "Japanese", targetLanguage = "Spanish")
        assertFalse("Language switch must invalidate pre-switch WorkRequestContext", vm.isRequestContextCurrent(preLangCtx))
        assertEquals("Spanish", vm.uiState.value.project.targetLanguage)

        val postSwitchEvents = vm.uiState.value.project.dialogueEvents
        val switchedUnlocked = postSwitchEvents.first { it.id == "cue_1" }
        val switchedLocked = postSwitchEvents.first { it.id == "cue_locked" }
        assertEquals("", switchedUnlocked.translatedText)
        assertTrue(switchedUnlocked.needsTranslation)
        assertEquals(anchoredPos, switchedUnlocked.anchorPosition)
        assertEquals("Pinned custom translation", switchedLocked.translatedText)
        assertTrue(switchedLocked.lockedByUser)
    }

    @Test
    fun webStreamSniffedDescriptorUpgradeAndCapturedTrackSanitization_behaveSafely() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val webProj = DialogueLensProject(
            id = "proj_web_1",
            title = "Web Stream Test",
            createdAtEpochMs = 1_000L,
            sourceLanguage = "Japanese",
            targetLanguage = "English",
            subtitleSource = SubtitleSourceKind.AI_DRAFT,
            videoSource = VideoSource(
                uriString = "https://stream.example.com/watch/123",
                displayName = "Web Stream Test",
                kind = VideoSourceKind.WEB_STREAM,
                durationMs = 120_000L,
                width = 1280,
                height = 720,
            ),
            shots = listOf(Shot("shot_1", 0L, 120_000L)),
            speakers = emptyList(),
            faceTracks = emptyList(),
            dialogueEvents = emptyList(),
        )
        vm.loadProject(webProj)
        vm.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)

        // Sparse same-URL descriptor followed by richer same-URL descriptor with auth headers
        vm.onWebStreamSniffedMediaStream(
            SniffedWebMediaStream(
                url = "https://cdn.example.com/hls/master.m3u8",
                isHlsPlaylist = true,
            )
        )
        val (url1, hdr1) = vm.currentDiscoveredWebMediaForTest()
        assertEquals("https://cdn.example.com/hls/master.m3u8", url1)
        assertTrue(hdr1.isEmpty())

        vm.onWebStreamSniffedMediaStream(
            SniffedWebMediaStream(
                url = "https://cdn.example.com/hls/master.m3u8",
                referer = "https://stream.example.com/watch/123",
                cookieHeader = "session=synthetic_cookie_token",
                origin = "https://stream.example.com",
                isHlsPlaylist = true,
            )
        )
        val (url2, hdr2) = vm.currentDiscoveredWebMediaForTest()
        assertEquals("https://cdn.example.com/hls/master.m3u8", url2)
        assertEquals("https://stream.example.com/watch/123", hdr2["Referer"])
        assertEquals("session=synthetic_cookie_token", hdr2["Cookie"])

        // Cross-language captured track must NOT copy foreign text into translatedText and must strip control chars
        val rawJson = """
            [
              {"startMs": 5000, "endMs": 4000, "text": "こんにちは\u0007世界"},
              {"startMs": -200, "endMs": 2000, "text": "   "}
            ]
        """.trimIndent()
        val parsed = parseCapturedWebTrackJson(
            rawJson = rawJson,
            sourceLanguage = "Japanese",
            targetLanguage = "English",
        )
        assertEquals(1, parsed.size)
        assertEquals("こんにちは世界", parsed.first().sourceText)
        assertEquals("", parsed.first().translatedText)
        assertTrue(parsed.first().needsTranslation)
        assertTrue(parsed.first().endTimeMs > parsed.first().startTimeMs)
    }

    @Test
    fun fractionalBoundaryClippingAndNearEndOrDistantCueReadiness_remainStrictlyHonest() {
        val proj = sampleYouTubeProject(durationMs = 1_095_000L)
        val coordinator = SentinelWorkforceCoordinator()
        val ledger = TranscriptCoverageLedger()
        ledger.reset(proj.videoSource.durationMs, source = SubtitleSourceKind.AI_DRAFT)
        coordinator.resetForProject(proj)

        // T06: Fractional playhead 120_500ms -> boundary 300_500ms; rounded whole-second submission stays <= 300_000ms (inside 300_500ms)
        coordinator.updateAcknowledgedViewerPosition(120_500L, proj.videoSource.durationMs, isPlaying = true)
        assertEquals(300_500L, coordinator.currentBoundaryEndMs())
        val gate = coordinator.clipOrDeferToBoundary(
            startMs = 280_000L,
            endMs = 305_000L,
            overlapMs = 2_000L,
            roundingUpToSec = true,
        )
        assertTrue(gate is BoundarySubmissionDecision.Allowed)
        val allowed = gate as BoundarySubmissionDecision.Allowed
        assertTrue(
            "Submitted rounded+overlapped end (${allowed.effectiveSubmittedEndMs}ms) must not exceed 300_500ms",
            allowed.effectiveSubmittedEndMs <= 300_500L,
        )
        assertEquals(0, (allowed.effectiveSubmittedEndMs % 1000L).toInt())

        // T24: Future-only dialogue at 240s when viewer is at 0s must NOT produce "Current window ready"
        val futureOnlyProj = proj.copy(
            dialogueEvents = listOf(
                DialogueEvent(
                    id = "future_1",
                    startTimeMs = 240_000L,
                    endTimeMs = 244_000L,
                    sourceText = "Future speech at 4 minutes",
                    translatedText = "Future speech at 4 minutes",
                    speakerId = "spk_1",
                    speakerConfidence = 0.9f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "shot_1",
                )
            )
        )
        val readinessCtrl = PlaybackReadinessController()
        readinessCtrl.resetForMedia(
            initialPositionMs = 0L,
            durationMs = proj.videoSource.durationMs,
            requiresCoverageGate = true,
            initialIntent = UserPlaybackIntent.PLAY,
        )
        val futureReadiness = readinessCtrl.evaluateCoverage(ledger)
        val futureLabel = HumanReadableStatus.resolve(
            project = futureOnlyProj,
            scoutState = ScoutCaptionState.Idle,
            coverageSummary = ledger.summary(),
            readinessSnapshot = futureReadiness,
            sentinelSnapshot = coordinator.inspectorSnapshot(),
        )
        assertNotEquals(
            "Future-only cues cannot falsely claim Current window ready",
            "Current window ready",
            futureLabel.label,
        )

        // T24: Near video end (17:00 -> 75s remaining), WINDOW_READY_IDLE reports shortened prepared span instead of 3 minutes
        coordinator.updateAcknowledgedViewerPosition(1_020_000L, proj.videoSource.durationMs, isPlaying = true)
        ledger.recordOutcome(1_020_000L, proj.videoSource.durationMs, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 10)
        coordinator.evaluateSchedulerOutcome(ledger)
        val endReadiness = readinessCtrl.onUserSeekRequested(1_020_000L, ledger)
        val endLabel = HumanReadableStatus.resolve(
            project = proj,
            scoutState = ScoutCaptionState.Ready("Window Ready", 10, 1),
            coverageSummary = ledger.summary(),
            readinessSnapshot = endReadiness,
            sentinelSnapshot = coordinator.inspectorSnapshot(),
        )
        assertEquals("Current window ready", endLabel.label)
        assertTrue(
            "Near-end window status should show actual shortened span (75s), got: ${endLabel.detail}",
            endLabel.detail.contains("75s"),
        )
    }
}
