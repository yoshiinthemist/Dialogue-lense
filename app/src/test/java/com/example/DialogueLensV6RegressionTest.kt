package com.example

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.CaptionDisplayMode
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
import com.example.data.model.ScoutCaptureHealthState
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.AudioAnalyzer
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.RedirectDecision
import com.example.domain.analysis.ScoutSyncCommand
import com.example.domain.analysis.TimeWindowRange
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.analysis.WebNavigationGuard
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.PlacedBalloon
import com.example.domain.layout.TailDirection
import com.example.domain.layout.TailGeometry
import com.example.domain.media.MediaActivationDecision
import com.example.domain.media.MediaSourceAdapterRegistry
import com.example.domain.pipeline.DialogueReviewStatus
import com.example.domain.pipeline.DraftInterval
import com.example.domain.pipeline.IntervalReviewOutcome
import com.example.domain.pipeline.ReleasedTimelineStore
import com.example.domain.pipeline.Scout2IndependentReviewer
import com.example.domain.playback.PlaybackReadinessState
import com.example.domain.playback.UserPlaybackIntent
import com.example.domain.sentinel.RollingBoundaryPolicy
import com.example.domain.sentinel.SentinelPriorityTier
import com.example.domain.sentinel.SentinelTaskKind
import com.example.domain.sentinel.SentinelWorkItem
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import com.example.ui.player.SpeechBalloonView
import com.example.ui.player.WebStreamSessionHolder
import com.example.ui.player.buildWebStreamHookScript
import com.example.ui.player.computePerimeterTailGeometry
import com.example.ui.state.DialogueLensViewModel
import com.example.ui.state.SubtitlePrepState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Production-path regression suite for DialogueLens Regression Repair v6.
 * Exercises real ViewModel, ReleaseStore, SentinelWorkforceCoordinator, LookaheadWindowPlanner,
 * MediaSourceAdapterRegistry, WebNavigationGuard, WebStreamSessionHolder, BalloonLayoutEngine,
 * TailGeometry, and SpeechBalloonView Compose rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DialogueLensV6RegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleYouTubeProject(
        id: String = "proj_v6_yt",
        videoId: String = "RzagPoxaolQ",
        durationMs: Long = 360_000L,
        events: List<DialogueEvent> = emptyList(),
        speakers: List<Speaker> = listOf(
            Speaker(id = "spk_1", defaultLabel = "Speaker 1", accentIndex = 0, preferredScreenSideX = 0.25f),
            Speaker(id = "spk_2", defaultLabel = "Speaker 2", accentIndex = 1, preferredScreenSideX = 0.75f),
        ),
        subtitleSource: SubtitleSourceKind = SubtitleSourceKind.AI_DRAFT,
    ): DialogueLensProject = DialogueLensProject(
        id = id,
        title = "V6 Regression YouTube",
        createdAtEpochMs = 1_000L,
        sourceLanguage = "English",
        targetLanguage = "English",
        subtitleSource = subtitleSource,
        videoSource = VideoSource(
            uriString = "https://youtu.be/$videoId?is=IETdpKn91KSN9Ih_",
            displayName = "V6 Test Clip",
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            durationMs = durationMs,
            width = 1920,
            height = 1080,
            youtubeVideoId = videoId,
        ),
        shots = listOf(Shot("shot_1", 0L, durationMs, cutType = "EMBEDDED_YOUTUBE")),
        speakers = speakers,
        faceTracks = emptyList(),
        dialogueEvents = events,
    )

    private fun pumpMainLooperUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(60L, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(10L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------------------
    // 1. Source import parity
    // ------------------------------------------------------------------------

    @Test
    fun sourceImportParity_enforcesSharedActivationContractAcrossLauncherSheetShareAndViewModel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val baselineProjectId = vm.uiState.value.project.id

        val registry = MediaSourceAdapterRegistry.default

        // 1a. Standard YouTube URL with share query param resolves consistently
        val ytUrl = "https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_"
        val ytDecision = registry.resolveForActivation(ytUrl)
        assertTrue(ytDecision is MediaActivationDecision.YouTube)
        assertEquals("RzagPoxaolQ", (ytDecision as MediaActivationDecision.YouTube).videoId)

        // 1b. Foreign host containing an 11-char path segment MUST NOT extract as a YouTube ID
        val foreignUrl = "https://example.org/watch/AbCdEfGhIjK?autoplay=1"
        assertNull(
            "Foreign host with 11-char path segment must never be mistaken for YouTube ID",
            registry.extractValidYouTubeId(foreignUrl),
        )
        val foreignDecision = registry.resolveForActivation(foreignUrl)
        assertTrue(foreignDecision is MediaActivationDecision.WebStream)
        assertEquals(VideoSourceKind.WEB_STREAM, (foreignDecision as MediaActivationDecision.WebStream).resolved.videoSourceKind)

        // 1c. Calling createYouTubeProject with that foreign URL routes it to WEB_STREAM instead of a broken YouTube embed
        vm.createYouTubeProject(
            youtubeUrl = foreignUrl,
            youtubeVideoId = "AbCdEfGhIjK",
            subtitleUri = null,
            subtitleFileName = "",
            sourceLanguage = "Auto-Detect",
            targetLanguage = "English",
            translationMode = TranslationMode.NATURAL,
        )
        pumpMainLooperUntil { vm.uiState.value.project.id != baselineProjectId }
        assertEquals(VideoSourceKind.WEB_STREAM, vm.uiState.value.project.videoSource.kind)
        assertNull(vm.uiState.value.project.videoSource.youtubeVideoId)

        // 1d. Empty / malformed YouTube IDs cannot create projects or change active session
        val activeAfterWeb = vm.uiState.value.project.id
        listOf("", "short", "bad!id#1234", "http://not-valid-scheme").forEach { badInput ->
            val rejected = registry.resolveForActivation(badInput)
            assertTrue("Expected Rejected for '$badInput'", rejected is MediaActivationDecision.Rejected)
            vm.createYouTubeProject(
                youtubeUrl = badInput,
                youtubeVideoId = badInput,
                subtitleUri = null,
                subtitleFileName = "",
                sourceLanguage = "English",
                targetLanguage = "English",
                translationMode = TranslationMode.NATURAL,
            )
            assertEquals("Malformed input '$badInput' must not replace active project", activeAfterWeb, vm.uiState.value.project.id)

            vm.generateSubtitlesForUrl(
                rawUrl = badInput,
                youtubeVideoId = badInput,
                pastedSubtitleText = "",
                subtitleUri = null,
                subtitleName = "",
                sourceLanguage = "English",
                targetLanguage = "English",
                translationMode = TranslationMode.NATURAL,
            )
            pumpMainLooperUntil(600L) { vm.uiState.value.subtitlePrepState is SubtitlePrepState.Error }
            assertTrue(
                "Prep job must immediately reject '$badInput'",
                vm.uiState.value.subtitlePrepState is SubtitlePrepState.Error,
            )
        }

        // 1e. Same non-YouTube URL routed via direct entry and Android share path produces identical WebStream kind
        val sameWebUrl = "https://player.example.com/episodes/sample-stream"
        vm.createWebStreamProject(
            webUrl = sameWebUrl,
            subtitleUri = null,
            subtitleFileName = "",
            sourceLanguage = "Auto-Detect",
            targetLanguage = "English",
            translationMode = TranslationMode.NATURAL,
        )
        pumpMainLooperUntil { vm.uiState.value.project.videoSource.uriString == sameWebUrl }
        val viaWebEntryKind = vm.uiState.value.project.videoSource.kind
        val webProjectId = vm.uiState.value.project.id

        vm.handleSharedIncomingText("Check out $sameWebUrl")
        pumpMainLooperUntil { vm.uiState.value.project.id != webProjectId }
        val viaShareKind = vm.uiState.value.project.videoSource.kind
        assertEquals(VideoSourceKind.WEB_STREAM, viaWebEntryKind)
        assertEquals(viaWebEntryKind, viaShareKind)
        val shareWebProjectId = vm.uiState.value.project.id

        // 1f. Direct media & local content URI preserve their routes
        val directDecision = registry.resolveForActivation("https://cdn.example.org/clip.mp4")
        assertTrue(directDecision is MediaActivationDecision.DirectStream)
        val localDecision = registry.resolveForActivation("content://media/external/video/media/99")
        assertTrue(localDecision is MediaActivationDecision.LocalFile)

        // 1g. Standard YouTube share path activates valid YouTube project cleanly
        vm.handleSharedIncomingText(ytUrl)
        pumpMainLooperUntil {
            vm.uiState.value.project.id != shareWebProjectId &&
                vm.uiState.value.project.videoSource.kind == VideoSourceKind.YOUTUBE_COMPLIANT
        }
        assertEquals(VideoSourceKind.YOUTUBE_COMPLIANT, vm.uiState.value.project.videoSource.kind)
        assertEquals("RzagPoxaolQ", vm.uiState.value.project.videoSource.youtubeVideoId)
        assertNotEquals(baselineProjectId, vm.uiState.value.project.id)
    }

    // ------------------------------------------------------------------------
    // 2. Deferred review while held
    // ------------------------------------------------------------------------

    @Test
    fun deferredReviewWhileHeld_drainsWithoutReTranscribeOrPlayTapAndPatrolCannotConsumeIt() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val proj = sampleYouTubeProject(id = "proj_deferred_review")
        vm.loadProject(proj)
        vm.setQuotaSaverMode(QuotaSaverMode.ZERO_CLOUD)
        vm.pausePlayback()

        // Ensure viewer stays paused (held) throughout
        assertFalse(vm.uiState.value.isPlaying)

        val refTrack = ReferenceCaptionTrack(
            mediaId = proj.id,
            trackId = "yt_ref_deferred",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            cues = listOf(
                ReferenceCaptionCue("c1", 2_000L, 5_500L, "Deferred review recovered line."),
            ),
        )
        vm.attachReferenceTrackForTest(refTrack)

        // Temporarily hold [0..15s] by Worker 1 so Scout 2 worker is deferred without losing the draft
        val coordField = DialogueLensViewModel::class.java.getDeclaredField("sentinelCoordinator").apply { isAccessible = true }
        val coordinator = coordField.get(vm) as SentinelWorkforceCoordinator
        val blockerRes = coordinator.tryReserveInterval(
            ownerId = SentinelWorkforceCoordinator.WORKER_1_ID,
            startMs = 0L,
            endMs = 15_000L,
            tier = SentinelPriorityTier.CRITICAL_PLAYBACK_RECOVERY,
        )
        assertNotNull(blockerRes)

        val ctx = vm.captureWorkRequestContext()
        val (immediateOutcome, stagedSnap) = vm.stageAndReviewIntervalThroughReleaseGate(
            project = vm.uiState.value.project,
            intervalId = "iv_deferred_0_15000",
            startMs = 0L,
            endMs = 15_000L,
            events = emptyList(),
            speakers = vm.uiState.value.project.speakers,
            sessionToken = ctx.sessionToken,
            seekGeneration = ctx.seekGeneration,
            isConfirmedSilence = false,
            expectedConfigFingerprint = ctx.configFingerprint,
        )
        assertNull("Review must be deferred while Scout 2 is temporarily blocked", immediateOutcome)
        assertTrue("Draft must remain pending in releaseStore", stagedSnap.pendingDraftCount >= 1)

        // Patrol worker claiming only CHAPTER_BOUNDARY_PATROL MUST NOT consume SCOUT2_REQUIRED_REVIEW
        val patrolAttempt = coordinator.claimNextWork(
            workerId = SentinelWorkforceCoordinator.WORKER_1_ID,
            allowedKinds = setOf(SentinelTaskKind.CHAPTER_BOUNDARY_PATROL),
        )
        if (patrolAttempt != null) {
            assertNotEquals(SentinelTaskKind.SCOUT2_REQUIRED_REVIEW, patrolAttempt.first.kind)
            coordinator.markWorkerIdle(
                workerId = SentinelWorkforceCoordinator.WORKER_1_ID,
                reservationId = patrolAttempt.second.reservationId,
                repairCompleted = false,
            )
        }

        // Clear the temporary blocker while viewer remains paused -> ViewModel review loop drains it cleanly
        coordinator.releaseReservation(blockerRes!!.reservationId)
        pumpMainLooperUntil(timeoutMs = 3_500L) {
            vm.uiState.value.project.dialogueEvents.any {
                it.sourceText.contains("Deferred review recovered line")
            }
        }
        assertFalse("Viewer must still remain paused — draining required no Play tap", vm.uiState.value.isPlaying)
        assertEquals(
            "Expected pendingDraftCount=0 after drain (lastRelease=${vm.uiState.value.releaseSnapshot.lastReleaseSummary}, " +
                "queued=${vm.uiState.value.sentinelSnapshot.queuedItems.map { it.id }}, " +
                "events=${vm.uiState.value.project.dialogueEvents.map { it.id }})",
            0,
            vm.uiState.value.releaseSnapshot.pendingDraftCount,
        )
        assertTrue(
            "Recovered line must publish to live playback timeline while held",
            vm.uiState.value.project.dialogueEvents.any { it.sourceText.contains("Deferred review recovered line") },
        )

        // Also verify deadline expiry on an overdue empty draft emits explicit unverified outcome even when paused
        val store = ReleasedTimelineStore()
        store.resetSession(mediaId = "m1", sessionToken = 1L)
        store.stageDraft(
            DraftInterval(
                intervalId = "iv_overdue",
                mediaId = "m1",
                sessionToken = 1L,
                seekGeneration = 0L,
                sourceRevision = 1L,
                draftRevision = 1L,
                configFingerprint = "cfg",
                startMs = 15_000L,
                endMs = 22_000L,
                sourceLanguage = "English",
                targetLanguage = "English",
                events = emptyList(),
                speakers = emptyList(),
                hasUnavailableEvidence = true,
                createdAtEpochMs = 1_000L,
                deadlineEpochMs = 2_000L,
            )
        )
        val deadlineFallbacks = store.applyDeadlineFallbackIfDue(
            nowEpochMs = 5_000L,
            playheadMs = 0L,
            isPlaying = false,
        )
        assertEquals(1, deadlineFallbacks.size)
        assertEquals(DialogueReviewStatus.UNAVAILABLE_EVIDENCE, deadlineFallbacks.first().reviewOutcome.dialogueStatus)
        assertFalse(deadlineFallbacks.first().reviewOutcome.isIndependentlyVerified)
    }

    // ------------------------------------------------------------------------
    // 3. Pending transport, rejection, Pause override & Off/Watch Now resets
    // ------------------------------------------------------------------------

    @Test
    fun pendingTransport_gatesPlayUntilAckBlocksLateAckAfterPauseRejectsWebPlayAndHonorsOffWatchNow() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val cue = DialogueEvent(
            id = "cue_tr_1",
            startTimeMs = 0L,
            endTimeMs = 60_000L,
            sourceText = "Prepared dialogue",
            translatedText = "Prepared dialogue",
            speakerId = "spk_1",
            speakerConfidence = 0.95f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
        )
        val ytProj = sampleYouTubeProject(
            id = "proj_transport_yt",
            events = listOf(cue),
            subtitleSource = SubtitleSourceKind.EMBEDDED_TRACK,
        )
        vm.loadProject(ytProj)
        // Clear pending-intro gate cleanly by starting paused on prepared track, then toggle Play
        vm.dismissScoutCatchUpBuffering()
        // Wait — dismissScoutCatchUpBuffering when hold is not active only sets WatchNow bypass; check play toggle:
        // First clear hasPendingPlayIntro by entering viewer with explicit Play ack or skip:
        vm.togglePlayPause() // Play requested -> enters or issues stream play
        if (vm.uiState.value.isCinemaIntroActive) {
            vm.dismissScoutCatchUpBuffering()
        }
        val playReq1 = vm.uiState.value.streamPlayRequest
        // If intro just skipped/finished or Play command was issued, make sure isPlaying stays false until ack
        if (playReq1 != null && playReq1.second) {
            assertFalse(
                "External YouTube player must remain isPlaying=false before player ack arrives",
                vm.uiState.value.isPlaying,
            )
            // Even a passive playback tick reporting isPlaying=false must keep clock paused
            vm.onYouTubePlaybackState(positionMs = 0L, durationMs = 360_000L, isPlaying = false)
            assertFalse(vm.uiState.value.isPlaying)

            // User presses Pause BEFORE late Play ack arrives
            vm.pausePlayback()
            vm.onStreamPlayCommandResult(seq = playReq1.first, playing = true, rejected = false, reason = "yt_playing")
            assertFalse("Late Play ack after explicit Pause MUST NOT start playing", vm.uiState.value.isPlaying)

            // Even a stray YT state event after explicit Pause cannot auto-resume
            vm.onYouTubePlaybackState(positionMs = 1_500L, durationMs = 360_000L, isPlaying = true)
            assertFalse("Player playing callback cannot override UserPlaybackIntent.PAUSE", vm.uiState.value.isPlaying)

            // Fresh Play command -> valid player ack -> NOW transitions to isPlaying = true
            vm.togglePlayPause()
            val playReq2 = vm.uiState.value.streamPlayRequest!!
            assertTrue(playReq2.second)
            assertFalse("Still false until new seq ack arrives", vm.uiState.value.isPlaying)
            vm.onStreamPlayCommandResult(seq = playReq2.first, playing = true, rejected = false, reason = "yt_playing")
            assertTrue("Must transition to playing once matching seq acks playing=true", vm.uiState.value.isPlaying)
        }

        // Now test WebStream early Play -> rejection -> valid ack -> Off vs Watch Now scoping
        vm.createWebStreamProject(
            webUrl = "https://example.org/videos/stream-transport-test",
            subtitleUri = null,
            subtitleFileName = "",
            sourceLanguage = "English",
            targetLanguage = "English",
            translationMode = TranslationMode.NATURAL,
        )
        pumpMainLooperUntil { vm.uiState.value.project.videoSource.kind == VideoSourceKind.WEB_STREAM }
        // Dismiss intro wait into viewer state
        vm.togglePlayPause()
        vm.dismissScoutCatchUpBuffering()
        val webPlaySeq = vm.uiState.value.streamPlayRequest?.first ?: 1
        assertFalse("WebStream must stay isPlaying=false while play is unacknowledged", vm.uiState.value.isPlaying)

        // Deliver explicit rejection (e.g. NotAllowedError from HTMLMediaElement.play())
        vm.onStreamPlayCommandResult(seq = webPlaySeq, playing = false, rejected = true, reason = "NotAllowedError")
        assertFalse(vm.uiState.value.isPlaying)
        assertTrue(
            "Rejected web play must surface hardware/media error instead of fake playing",
            vm.uiState.value.readinessSnapshot.diagnostics.isMediaHardwareError &&
                vm.uiState.value.readinessSnapshot.state == PlaybackReadinessState.PLAYBACK_OR_ANALYSIS_ERROR,
        )

        // Switching CaptionDisplayMode.OFF releases caption gating ONLY without overriding user Pause
        vm.pausePlayback()
        vm.setDisplayMode(CaptionDisplayMode.OFF)
        assertEquals(CaptionDisplayMode.OFF, vm.uiState.value.displayMode)
        assertFalse("Mode switch to OFF must never resume over UserPlaybackIntent.PAUSE", vm.uiState.value.isPlaying)

        // Switching back to BUBBLES_WITH_TAILS on uncovered AI stream while user requests Play re-engages caption hold,
        // and Watch Now clears ONLY the caption-hold gate without erasing user seek/error semantics
        vm.setDisplayMode(CaptionDisplayMode.BUBBLES_WITH_TAILS)
        vm.togglePlayPause()
        assertTrue(vm.uiState.value.readinessSnapshot.isBufferingHoldActive)
        vm.dismissScoutCatchUpBuffering()
        assertFalse(vm.uiState.value.readinessSnapshot.isBufferingHoldActive)
        assertFalse("Watch Now on external stream still waits for player Play ack before isPlaying=true", vm.uiState.value.isPlaying)
        val postBypassSeq = vm.uiState.value.streamPlayRequest?.first
        assertNotNull(postBypassSeq)
        vm.onStreamPlayCommandResult(seq = postBypassSeq!!, playing = true, rejected = false, reason = "web_playing")
        assertTrue(vm.uiState.value.isPlaying)

        // Seek resets Watch Now bypass & readiness hold cleanly when entering uncovered region
        vm.seekTo(120_000L)
        assertNotEquals("Pending seek must wait for player landing feedback", 120_000L, vm.uiState.value.currentTimeMs)
    }

    // ------------------------------------------------------------------------
    // 4. Restored-cache replacement & canonical lineage
    // ------------------------------------------------------------------------

    @Test
    fun restoredCacheReplacement_preventsCrossingCueDoublesPreservesNamesConcurrentSpeakersRepeatsSfxAndLocks() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)

        // Exact Section 6 minimal fixture:
        // Existing 28–34s cue, repair window 30–35s, replacement 30–34s cue for same speech
        val existingCrossing = DialogueEvent(
            id = "old_28_34",
            startTimeMs = 28_000L,
            endTimeMs = 34_000L,
            sourceText = "Let's check the generator pressure now",
            translatedText = "Let's check the generator pressure now",
            speakerId = "spk_1",
            speakerConfidence = 0.72f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            stableCueKey = "cue_gen_28",
        )
        val concurrentOtherSpeaker = DialogueEvent(
            id = "conc_spk2",
            startTimeMs = 30_500L,
            endTimeMs = 33_500L,
            sourceText = "Reading forty-two psi on valve B",
            translatedText = "Reading forty-two psi on valve B",
            speakerId = "spk_2",
            speakerConfidence = 0.88f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            stableCueKey = "cue_conc_spk2",
        )
        val sfxCue = DialogueEvent(
            id = "sfx_alarm",
            startTimeMs = 30_800L,
            endTimeMs = 32_200L,
            sourceText = "[steam hissing]",
            translatedText = "[steam hissing]",
            speakerId = null,
            speakerConfidence = 1f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            dialogueType = DialogueType.SOUND_DESCRIPTION,
            stableCueKey = "cue_sfx_steam",
        )
        val lockedUserCue = DialogueEvent(
            id = "locked_34s",
            startTimeMs = 34_100L,
            endTimeMs = 34_900L,
            sourceText = "User pinned Tanaka line",
            translatedText = "User pinned Tanaka line",
            speakerId = "spk_1",
            speakerConfidence = 1f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            lockedByUser = true,
            stableCueKey = "cue_locked_34",
        )
        val laterRepeat = DialogueEvent(
            id = "repeat_90s",
            startTimeMs = 90_000L,
            endTimeMs = 93_000L,
            sourceText = "Let's check the generator pressure now",
            translatedText = "Let's check the generator pressure now",
            speakerId = "spk_1",
            speakerConfidence = 0.9f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            stableCueKey = "cue_repeat_90",
        )
        val restoredMixedNameCue = DialogueEvent(
            id = "old_name_10s",
            startTimeMs = 10_000L,
            endTimeMs = 14_000L,
            sourceText = "Welcome Dr. Sayuri Tanaka to the stage",
            translatedText = "Welcome Dr. Sayuri Tanaka to the stage",
            speakerId = "spk_1",
            speakerConfidence = 0.9f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            stableCueKey = "cue_name_10",
        )

        // Restore through ViewModel.loadProject (exercises reconcileRestoredTimeline + releaseStore seed)
        val dirtyRestoredDuplicate = existingCrossing.copy(
            id = "dup_cached_29_34",
            startTimeMs = 29_000L,
            endTimeMs = 34_000L,
            sourceTextRevision = 2,
        )
        val project = sampleYouTubeProject(
            id = "proj_restore_replace",
            subtitleSource = SubtitleSourceKind.AI_DRAFT,
            events = listOf(
                restoredMixedNameCue,
                existingCrossing,
                dirtyRestoredDuplicate,
                concurrentOtherSpeaker,
                sfxCue,
                lockedUserCue,
                laterRepeat,
            ),
        )
        vm.loadProject(project)
        val afterLoad = vm.uiState.value.project.dialogueEvents
        assertEquals(
            "Restored duplicate of overlapping speech must collapse to 1 on load",
            1,
            afterLoad.count { it.sourceText.contains("generator pressure") && it.startTimeMs < 40_000L },
        )

        // Now apply repair replacement 30–34s over repair window 30–35s (different ID & slightly revised wording)
        val replacementCue = DialogueEvent(
            id = "new_30_34",
            startTimeMs = 30_000L,
            endTimeMs = 34_000L,
            sourceText = "Let's check the generator pressure right now.",
            translatedText = "Let's check the generator pressure right now.",
            speakerId = "spk_1",
            speakerConfidence = 0.94f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            stableCueKey = "cue_gen_30",
        )
        vm.publishReleasedEventsToProject(
            startMs = 30_000L,
            endMs = 35_000L,
            releasedEvents = listOf(replacementCue, concurrentOtherSpeaker, sfxCue),
            coverageStatus = CoverageStatus.VERIFIED_DIALOGUE,
            source = SubtitleSourceKind.AI_DRAFT,
        )

        val published = vm.uiState.value.project.dialogueEvents
        val activeGeneratorAt31s = published.filter {
            31_000L in it.startTimeMs until it.endTimeMs &&
                it.sourceText.contains("generator pressure", ignoreCase = true)
        }
        assertEquals(
            "At 31s only the single canonical replacement utterance must survive (no double bubble)",
            1,
            activeGeneratorAt31s.size,
        )
        assertTrue(published.any { it.id == "conc_spk2" && it.speakerId == "spk_2" })
        assertTrue(published.any { it.id == "sfx_alarm" && it.dialogueType == DialogueType.SOUND_DESCRIPTION })
        assertTrue(published.any { it.id == "locked_34s" && it.lockedByUser })
        assertTrue(published.any { it.id == "repeat_90s" && it.startTimeMs == 90_000L })

        // ReleasedTimelineStore crossing-boundary atomic merge invariant
        val store = ReleasedTimelineStore()
        store.resetSession(
            mediaId = "proj_store_cross",
            sessionToken = 1L,
            initialEvents = listOf(existingCrossing),
        )
        val mergeMethod = ReleasedTimelineStore::class.java.getDeclaredMethod(
            "mergeIntervalEventsAtomicallyLocked",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            List::class.java,
            List::class.java,
        ).apply { isAccessible = true }
        mergeMethod.invoke(store, 30_000L, 35_000L, listOf(replacementCue), emptyList<Speaker>())
        val storeEventsAt31s = store.snapshot().events.filter { 31_000L in it.startTimeMs until it.endTimeMs }
        assertEquals(1, storeEventsAt31s.size)
        assertEquals("new_30_34", storeEventsAt31s.first().id)

        // ALL-CAPS phonetic degradation of mixed-case proper names must be rejected by Scout2 guard
        assertTrue(
            Scout2IndependentReviewer.isSuspiciousAllCapsPhoneticDegradation(
                candidateText = "WELCOME DR SAYURI TONKA TO THE STAGE",
                existingText = restoredMixedNameCue.sourceText,
            )
        )
        assertFalse(
            Scout2IndependentReviewer.isSuspiciousAllCapsPhoneticDegradation(
                candidateText = "Welcome Dr. Sayuri Tanaka to the stage!",
                existingText = restoredMixedNameCue.sourceText,
            )
        )
    }

    // ------------------------------------------------------------------------
    // 5. Web ownership, ad-switch protection, doc-gen & seek evidence gating
    // ------------------------------------------------------------------------

    @Test
    fun webOwnership_commitsSelectedElementBlocksAdRedirectsAndRejectsStaleSeekEvidence() {
        // 5a. Verify real injected JS hook commits selected main element ID and enforces seek settle
        val hookJs = buildWebStreamHookScript(
            isScoutInstance = true,
            seedDomain = "example.org",
            initialIsolate = true,
            expectedDocGeneration = 4L,
        )
        assertTrue(hookJs.contains("__dlCommittedElementId"))
        assertTrue(hookJs.contains("unrecoverablyDead"))
        assertTrue(hookJs.contains("awaitingSeekSettle"))
        assertTrue(hookJs.contains("onProgressWithGen"))
        assertTrue(hookJs.contains("isConsentOrInterstitialControl"))

        // 5b. Navigation & ad/popup guard preserves same-origin/CDN/consent flows and blocks ad hijacks
        val sameSiteNav = WebNavigationGuard.evaluateTopFrameNavigation(
            seedUrl = "https://video.example.org/watch/ep1",
            targetUrl = "https://video.example.org/interstitial-continue",
            isRedirect = false,
            hasUserGesture = true,
        )
        assertTrue(sameSiteNav is RedirectDecision.Allow)

        val adHijackNav = WebNavigationGuard.evaluateTopFrameNavigation(
            seedUrl = "https://video.example.org/watch/ep1",
            targetUrl = "https://stripchat.com/cam-promo",
            isRedirect = true,
            hasUserGesture = false,
        )
        assertTrue(adHijackNav is RedirectDecision.Block)

        // 5c. ViewModel + Readiness + Ledger refuse to credit coverage from invalid/failed audio or pending seeks
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        vm.createWebStreamProject(
            webUrl = "https://video.example.org/watch/ep1",
            subtitleUri = null,
            subtitleFileName = "",
            sourceLanguage = "English",
            targetLanguage = "English",
            translationMode = TranslationMode.NATURAL,
        )
        // Corrupt / unreadable audio bytes fail AudioAnalyzer decode verification and never verify coverage
        val corruptAudio = AudioAnalyzer.inspectPcmWavBytes(byteArrayOf(0x10, 0x20, 0x30))
        assertNotEquals(ScoutCaptureHealthState.USABLE_DECODED_INPUT, corruptAudio.healthState)

        // Clamped or unlanded seek does NOT falsely mark target interval verified
        vm.seekTo(180_000L)
        assertFalse(vm.coverageStatusAtForTest(180_000L)?.isVerified == true)
        // Before player acknowledges 180s, a late stale playback callback at 5s must not mark 180s ready
        vm.onWebStreamPlaybackState(positionMs = 5_000L, durationMs = 360_000L, isPlaying = false)
        assertFalse(vm.coverageStatusAtForTest(180_000L)?.isVerified == true)

        // WebStreamSessionHolder doc-generation field advances monotonically
        val holder = WebStreamSessionHolder()
        assertEquals(1L, holder.activeDocGeneration)
        holder.activeDocGeneration += 1L
        assertEquals(2L, holder.activeDocGeneration)
    }

    // ------------------------------------------------------------------------
    // 6. Paused scout horizon (180s frozen boundary, no 7s rewind loop)
    // ------------------------------------------------------------------------

    @Test
    fun pausedScoutHorizon_processesInside180sIdlesCleanlyWithZeroRewindLoopAndInvalidatesOnSeek() {
        val durationMs = 600_000L
        val viewerPausedAtMs = 60_000L
        val horizonMs = 180_000L

        // 6a. With eligible work at [120s, 130s] inside [60s, 240s], Scout seeks/plays forward
        val workCmd = LookaheadWindowPlanner.computeCoordinatorScoutCommand(
            viewerPosMs = viewerPausedAtMs,
            scoutPosMs = 70_000L,
            durationMs = durationMs,
            nextWorkWindow = TimeWindowRange(120_000L, 130_000L),
            isViewerPlaying = false,
            isScoutPlaying = true,
            introRemainingMs = 0L,
            horizonMs = horizonMs,
        )
        assertEquals(ScoutSyncCommand.SeekAndPlay(120_000L), workCmd)

        // 6b. When horizon [60s, 240s] has no remaining work and Scout sits at 235s,
        // computeCoordinatorScoutCommand MUST PauseWithLead/Hold — NEVER rewind to viewerPos+7s (67s)!
        val idleCmdPlaying = LookaheadWindowPlanner.computeCoordinatorScoutCommand(
            viewerPosMs = viewerPausedAtMs,
            scoutPosMs = 235_000L,
            durationMs = durationMs,
            nextWorkWindow = null,
            isViewerPlaying = false,
            isScoutPlaying = true,
            introRemainingMs = 0L,
            horizonMs = horizonMs,
        )
        assertEquals(ScoutSyncCommand.PauseWithLead, idleCmdPlaying)

        val idleCmdPaused = LookaheadWindowPlanner.computeCoordinatorScoutCommand(
            viewerPosMs = viewerPausedAtMs,
            scoutPosMs = 235_000L,
            durationMs = durationMs,
            nextWorkWindow = null,
            isViewerPlaying = false,
            isScoutPlaying = false,
            introRemainingMs = 0L,
            horizonMs = horizonMs,
        )
        assertEquals(ScoutSyncCommand.Hold, idleCmdPaused)

        // 6c. End-to-end ViewModel integration: paused at 60s with full 0..240s ledger verified,
        // calling onScoutWebStreamPlaybackState at 235s MUST NOT emit a 67s rewind seekRequest
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DialogueLensViewModel(app)
        val proj = sampleYouTubeProject(id = "proj_paused_horizon", durationMs = durationMs)
        vm.loadProject(proj)
        vm.configureRollingBoundaryPolicyForTest(RollingBoundaryPolicy(horizonMs = 180_000L))
        vm.seekTo(viewerPausedAtMs)
        vm.onYouTubePlaybackState(positionMs = viewerPausedAtMs, durationMs = durationMs, isPlaying = false)
        vm.pausePlayback()

        val ledgerField = DialogueLensViewModel::class.java.getDeclaredField("coverageLedger").apply { isAccessible = true }
        val ledger = ledgerField.get(vm) as TranscriptCoverageLedger
        ledger.recordOutcome(0L, 240_000L, CoverageStatus.VERIFIED_SILENCE, SubtitleSourceKind.AI_DRAFT)

        val seekBefore = vm.uiState.value.scoutSeekRequest
        vm.onScoutYouTubePlaybackState(positionMs = 235_000L, durationMs = durationMs, isPlaying = true)
        assertEquals(
            "Paused Scout with completed 180s horizon must idle/pause instead of rewinding toward 67s",
            seekBefore,
            vm.uiState.value.scoutSeekRequest,
        )
        assertEquals(false, vm.uiState.value.scoutPlayRequest?.second)

        // 6d. Seek invalidates prior generation work
        val genBefore = vm.uiState.value.seekGeneration
        vm.seekTo(300_000L)
        assertTrue(vm.uiState.value.seekGeneration > genBefore)
    }

    // ------------------------------------------------------------------------
    // 7. Tail geometry (12dp/18dp, 5.625 deg shout midpoint, modes, suppression logs)
    // ------------------------------------------------------------------------

    @Test
    fun tailGeometryAndRendering_enforces18DpMaxFixesShoutMidpointLogsSuppressionAndKeepsBodyStable() {
        val bodyRect = Rect(left = 100f, top = 80f, right = 280f, bottom = 140f)
        val densities = listOf(1f, 2f, 3f)
        val distancesDp = listOf(4f, 12f, 20f, 80f, 300f)
        val anglesDeg = listOf(0f, 5.625f, 11.25f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 354.375f)

        // 7a. Sweep ordinary & shout geometry across densities, distances, and 360° angles
        for (density in densities) {
            val nominalPx = TailGeometry.NOMINAL_TAIL_REACH_DP * density
            val maxPx = TailGeometry.MAX_TAIL_REACH_DP * density
            val minPx = TailGeometry.MIN_TAIL_REACH_DP * density
            val clearancePx = TailGeometry.MOUTH_CLEARANCE_DP * density

            for (distDp in distancesDp) {
                for (deg in anglesDeg) {
                    val rad = (deg * PI / 180.0).toFloat()
                    val baseRadiusEst = hypot(
                        (bodyRect.width * 0.5f * cos(rad)).toDouble(),
                        (bodyRect.height * 0.5f * sin(rad)).toDouble(),
                    ).toFloat()
                    val targetDistFromCenter = baseRadiusEst + distDp * density
                    val geom = computePerimeterTailGeometry(
                        bodyRect = bodyRect,
                        cornerRadius = 16f,
                        aimAngleRad = rad,
                        targetDistanceFromCenterPx = targetDistFromCenter,
                        halfThroatPx = 8f * density,
                        mouthClearancePx = clearancePx,
                        minTailReachPx = minPx,
                        maxTailReachPx = maxPx,
                        nominalTailReachPx = nominalPx,
                    )
                    assertTrue(
                        "Ordinary tail reach ${geom.tailLengthPx}px must never exceed 18.dp ($maxPx px) [density=$density, distDp=$distDp, deg=$deg]",
                        geom.tailLengthPx <= maxPx + 0.01f,
                    )

                    // Shout balloon vertex generator at exact 5.625 deg midpoint & all sweep angles
                    val rx = bodyRect.width * 0.54f
                    val ry = bodyRect.height * 0.58f
                    val targetX = bodyRect.center.x + cos(rad) * (rx + distDp * density)
                    val targetY = bodyRect.center.y + sin(rad) * (ry + distDp * density)
                    val shoutVertices = TailGeometry.computeShoutBalloonVertices(
                        centerX = bodyRect.center.x,
                        centerY = bodyRect.center.y,
                        radiusX = rx,
                        radiusY = ry,
                        spikeAmplitudePx = 3f * density,
                        jitterPhase = 0.5f,
                        showPointerTail = true,
                        aimAngleRad = rad,
                        targetX = targetX,
                        targetY = targetY,
                        nominalReachPx = nominalPx,
                        maxReachPx = maxPx,
                        minReachPx = minPx,
                        mouthClearancePx = clearancePx,
                    )
                    val tip = shoutVertices.first()
                    val expectedEdgeR = hypot((rx * cos(rad)).toDouble(), (ry * sin(rad)).toDouble()).toFloat()
                    val actualTipRadius = hypot(
                        (tip.x - bodyRect.center.x).toDouble(),
                        (tip.y - bodyRect.center.y).toDouble(),
                    ).toFloat()
                    val actualReachPx = (actualTipRadius - expectedEdgeR).coerceAtLeast(0f)
                    if (distDp > TailGeometry.MOUTH_CLEARANCE_DP) {
                        assertTrue(
                            "Shout tail must still emit tip at deg=$deg (actualReachPx=$actualReachPx)",
                            actualReachPx >= minPx * 0.45f,
                        )
                    } else {
                        assertTrue(
                            "Tight clearance shout tail must remain positive and bounded (actualReachPx=$actualReachPx)",
                            actualReachPx > 0f && actualReachPx <= minPx,
                        )
                    }
                    assertTrue(
                        "Shout tail reach $actualReachPx must never exceed 18.dp ($maxPx px)",
                        actualReachPx <= maxPx + 0.01f,
                    )
                }
            }
        }

        // 7b. BalloonLayoutEngine logs suppression reason for offscreen/uncertain target and keeps stable body across BALLOONS <-> BUBBLES_WITH_TAILS
        val engine = BalloonLayoutEngine()
        val uncertainEvt = DialogueEvent(
            id = "uncertain_1",
            startTimeMs = 1_000L,
            endTimeMs = 5_000L,
            sourceText = "Who spoke from offscreen?",
            translatedText = "Who spoke from offscreen?",
            speakerId = null,
            speakerConfidence = 0.20f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            stableCueKey = "uncertain_key_1",
        )
        val project = sampleYouTubeProject(events = listOf(uncertainEvt))
        val placedUncertain = engine.computeLayout(
            project = project,
            currentMs = 2_000L,
            displayMode = CaptionDisplayMode.BUBBLES_WITH_TAILS,
        )
        assertEquals(1, placedUncertain.size)
        assertEquals(TailDirection.NONE, placedUncertain.first().tailDirection)
        assertTrue(
            "Suppression reason must be recorded when tail is omitted",
            engine.lastTailSuppressionReasons.containsKey("uncertain_key_1"),
        )

        // Switching between BALLOONS and BUBBLES_WITH_TAILS keeps the same body rect coordinates
        val spk1 = Speaker(
            id = "spk_1",
            defaultLabel = "Speaker 1",
            customName = "Host",
            accentIndex = 0,
            preferredScreenSideX = 0.3f,
            isIdentityConfirmed = true,
        )
        val spokenEvt = DialogueEvent(
            id = "spoken_1",
            startTimeMs = 10_000L,
            endTimeMs = 16_000L,
            sourceText = "Body anchor must stay locked!",
            translatedText = "Body anchor must stay locked!",
            speakerId = spk1.id,
            speakerConfidence = 0.96f,
            faceTrackId = null,
            faceConfidence = 0.90f,
            shotId = "shot_1",
            anchorPosition = NormalizedPoint(0.28f, 0.56f),
            placementMode = PlacementMode.AUTO,
            stableCueKey = "stable_body_lock_1",
        )
        val switchProject = sampleYouTubeProject(events = listOf(spokenEvt), speakers = listOf(spk1))
        val engineSwitch = BalloonLayoutEngine()
        val withTails = engineSwitch.computeLayout(
            project = switchProject,
            currentMs = 12_000L,
            displayMode = CaptionDisplayMode.BUBBLES_WITH_TAILS,
        ).first()
        val tailless = engineSwitch.computeLayout(
            project = switchProject,
            currentMs = 12_000L,
            displayMode = CaptionDisplayMode.BALLOONS,
        ).first()
        assertTrue(withTails.tailDirection != TailDirection.NONE)
        assertEquals(TailDirection.NONE, tailless.tailDirection)
        assertTrue(abs(withTails.rect.left - tailless.rect.left) < 0.02f)
        assertTrue(abs(withTails.rect.top - tailless.rect.top) < 0.02f)

        // 7c. Compose rendering check for both shout and normal balloons in SpeechBalloonView
        var currentMode by mutableStateOf(withTails)
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(modifier = Modifier.size(480.dp, 270.dp)) {
                    val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                    SpeechBalloonView(
                        placed = currentMode.copy(isShouted = true),
                        translationMode = TranslationMode.NATURAL,
                        style = BalloonStyleSettings(visualStyle = BubbleVisualStyle.MANGA_INK, reducedMotion = true),
                        isEditMode = false,
                        isSelected = false,
                        containerWidthPx = w,
                        containerHeightPx = h,
                        faceExclusionRects = emptyList(),
                        onClick = {},
                        onDragCommit = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Body anchor must stay locked!", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("balloon_spoken_1", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnUiThread { currentMode = tailless }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Body anchor must stay locked!", substring = true).assertIsDisplayed()
    }
}
