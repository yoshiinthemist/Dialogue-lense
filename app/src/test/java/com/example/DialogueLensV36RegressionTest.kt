package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import com.example.data.ai.AiCastSlotAssignment
import com.example.data.ai.TranslationAiService
import com.example.data.model.CaptionOriginKind
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.PlacementMode
import com.example.data.model.ReferenceCaptionCue
import com.example.data.model.ReferenceCaptionTrack
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.domain.attribution.SpeakerAttributionEngine
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.pipeline.DraftInterval
import com.example.domain.pipeline.ReleasedTimelineStore
import com.example.domain.pipeline.Scout1CueTimestamp
import com.example.domain.pipeline.Scout1TimestampReport
import com.example.domain.pipeline.Scout2IndependentReviewer
import com.example.domain.pipeline.ScoutClockProvenance
import com.example.domain.pipeline.TimestampAdjustmentRecord
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import com.example.ui.editor.InspectorTab
import com.example.ui.editor.TimelineSubtitleEditor
import com.example.ui.state.mergeCastSlotAssignments
import com.example.ui.state.reconcileWindowOutcomeSpeakersAndCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression suite for v36.0:
 * 1. Secondary speakers (B, C, D...) receive immediate inferred/contextual names across both
 *    YouTube and Audio ASR paths, stay visible across all timestamps, and upgrade cleanly in place
 *    when visually confirmed later without duplicating slots or overriding user-named speakers.
 * 2. Scout 1 attaches structured [Scout1TimestampReport] timestamps to [DraftInterval]; Scout 2
 *    audits and unconditionally snaps drifted/overlapping non-locked cues to [ReferenceCaptionTrack]
 *    while clamping/de-overlapping unreferenced intervals.
 * 3. Sentinel Inspector card in Studio Diagnostics surfaces the Scout 1 -> 2 Timestamp Audit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DialogueLensV36RegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun testProject(
        id: String = "proj_v36",
        speakers: List<Speaker> = emptyList(),
        events: List<DialogueEvent> = emptyList(),
    ): DialogueLensProject = DialogueLensProject(
        id = id,
        title = "V36 Regression",
        createdAtEpochMs = 1_000L,
        sourceLanguage = "English",
        targetLanguage = "English",
        subtitleSource = SubtitleSourceKind.AI_DRAFT,
        videoSource = VideoSource(
            uriString = "https://youtu.be/RzagPoxaolQ",
            displayName = "RzagPoxaolQ",
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            durationMs = 360_000L,
            width = 1920,
            height = 1080,
            youtubeVideoId = "RzagPoxaolQ",
        ),
        shots = listOf(Shot("shot_1", 0L, 360_000L, cutType = "EMBEDDED_YOUTUBE")),
        speakers = speakers,
        faceTracks = emptyList(),
        dialogueEvents = events,
    )

    @Test
    fun secondarySpeakersNamedImmediatelyAndUpgradedInPlaceAcrossYouTubeAndAudioAsr() {
        val audioPayload = """
            {
              "noSpeechConfirmed": false,
              "speakerUpdates": [
                {"slot": "A", "displayName": "Roy Wood Jr.", "isRolePlaceholder": false, "isVisuallyConfirmed": true, "visualVoiceNotes": "Host suit"},
                {"slot": "B", "displayName": "Panelist", "isRolePlaceholder": true, "isVisuallyConfirmed": false, "visualVoiceNotes": "Warm tenor, right panel"},
                {"slot": "C", "displayName": "Guest Comedian", "isRolePlaceholder": true, "isVisuallyConfirmed": false, "visualVoiceNotes": "Fast alto, center seat"},
                {"slot": "D", "displayName": "Speaker D", "isRolePlaceholder": true, "isVisuallyConfirmed": false, "visualVoiceNotes": "Deep voice offstage"}
              ],
              "cues": [
                {"startOffsetSec": 1.5, "endOffsetSec": 4.0, "sourceText": "Welcome back everyone.", "naturalTranslation": "Welcome back everyone.", "speakerSlot": "A", "dialogueType": "SPOKEN"},
                {"startOffsetSec": 4.4, "endOffsetSec": 7.2, "sourceText": "Look at how fast the polls moved.", "naturalTranslation": "Look at how fast the polls moved.", "speakerSlot": "B", "dialogueType": "SPOKEN"},
                {"startOffsetSec": 7.6, "endOffsetSec": 10.5, "sourceText": "I couldn't believe it either.", "naturalTranslation": "I couldn't believe it either.", "speakerSlot": "C", "dialogueType": "SPOKEN"}
              ]
            }
        """.trimIndent()

        val parsedAudio = TranslationAiService.parseAudioTranscriptionPayload(
            rawJson = audioPayload,
            chunkStartMs = 40_000L,
            chunkDurationMs = 20_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
        )
        assertEquals(4, parsedAudio.speakerUpdates.size)
        assertEquals(41_500L, parsedAudio.cues.first().startMs)
        assertEquals(1.5, parsedAudio.cues.first().rawStartSec!!, 0.01)
        assertEquals(40_000L, parsedAudio.cues.first().appliedClockOffsetMs)

        val attribution = SpeakerAttributionEngine()
        val (reconciledCues1, reconciledUpdates1, _) = reconcileWindowOutcomeSpeakersAndCues(
            existingSpeakers = emptyList(),
            cues = parsedAudio.cues,
            speakerUpdates = parsedAudio.speakerUpdates,
        )
        val (merged1, mem1) = mergeCastSlotAssignments(
            project = testProject(),
            assignments = reconciledUpdates1,
            rememberedColors = emptyMap(),
            reconcileColors = attribution::reconcileCharacterColors,
            discoveryTimestampMs = 40_000L,
        )
        assertEquals(3, reconciledCues1.size)
        val spkA = merged1.speakers.first { it.id == "spk_a" }
        val spkB = merged1.speakers.first { it.id == "spk_b" }
        val spkC = merged1.speakers.first { it.id == "spk_c" }
        val spkD = merged1.speakers.first { it.id == "spk_d" }

        assertEquals("Roy Wood Jr.", spkA.visibleDisplayNameAt(2_000L))
        assertEquals("Panelist", spkB.visibleDisplayNameAt(2_000L))
        assertEquals("Guest Comedian", spkC.visibleDisplayNameAt(2_000L))
        assertTrue(spkB.isConfirmedCharacter)
        assertTrue(spkC.isConfirmedCharacter)
        assertEquals("SCOUT_INFERRED", spkB.revealProvenance)
        assertEquals("SCOUT_INFERRED", spkC.revealProvenance)
        assertNull(spkB.nameRevealedAtMs)
        assertFalse("Generic Speaker D label must remain unconfirmed", spkD.isConfirmedCharacter)

        // Next window confirms Slot B's real name ("Michael Ian Black") and infers Slot D ("Producer")
        // while User manually named Slot C earlier -> user name stays protected and slot reconciles cleanly!
        val userLockedC = merged1.copy(
            speakers = merged1.speakers.map { s ->
                if (s.id == "spk_c") {
                    s.copy(
                        customName = "Amber Ruffin",
                        previousNames = (s.previousNames + s.displayName).distinct(),
                        isUserNamed = true,
                        revealProvenance = "USER_RENAME",
                    )
                } else s
            },
        )
        val window2Updates = listOf(
            AiCastSlotAssignment(
                slot = "B",
                displayName = "Michael Ian Black",
                isRolePlaceholder = false,
                visualVoiceNotes = "Warm tenor, right panel",
                isVisuallyConfirmed = true,
            ),
            AiCastSlotAssignment(
                slot = "C",
                displayName = "Guest Comedian",
                isRolePlaceholder = true,
                visualVoiceNotes = "Fast alto, center seat",
                isVisuallyConfirmed = false,
            ),
            AiCastSlotAssignment(
                slot = "D",
                displayName = "Producer",
                isRolePlaceholder = true,
                visualVoiceNotes = "Deep voice offstage",
                isVisuallyConfirmed = false,
            ),
        )
        val (_, reconciledUpdates2, remap2) = reconcileWindowOutcomeSpeakersAndCues(
            existingSpeakers = userLockedC.speakers,
            cues = emptyList(),
            speakerUpdates = window2Updates,
        )
        assertEquals("B", remap2["B"])
        val (merged2, _) = mergeCastSlotAssignments(
            project = userLockedC,
            assignments = reconciledUpdates2,
            rememberedColors = mem1,
            reconcileColors = attribution::reconcileCharacterColors,
            discoveryTimestampMs = 80_000L,
        )
        assertEquals("Slot B must upgrade in place without creating extra speakers", 4, merged2.speakers.size)
        val spkBAfter = merged2.speakers.first { it.id == "spk_b" }
        val spkCAfter = merged2.speakers.first { it.id == "spk_c" }
        val spkDAfter = merged2.speakers.first { it.id == "spk_d" }
        assertEquals("Michael Ian Black", spkBAfter.visibleDisplayNameAt(2_000L))
        assertEquals("SCOUT_DISCOVERY", spkBAfter.revealProvenance)
        assertFalse(spkBAfter.isRolePlaceholder)
        assertEquals("Amber Ruffin", spkCAfter.visibleDisplayNameAt(2_000L))
        assertEquals("Producer", spkDAfter.visibleDisplayNameAt(2_000L))
    }

    @Test
    fun scout1TimestampReportAndScout2AlwaysSnapToReferenceProtectLockedAndAuditInSentinel() {
        val reviewer = Scout2IndependentReviewer()
        val store = ReleasedTimelineStore()
        val sentinel = SentinelWorkforceCoordinator()
        val layout = BalloonLayoutEngine()
        val project = testProject(
            speakers = listOf(
                Speaker(id = "spk_a", defaultLabel = "Host", customName = "Roy Wood Jr.", accentIndex = 0, isIdentityConfirmed = true),
            ),
        )
        store.resetSession(project.id, sessionToken = 1L, initialSpeakers = project.speakers)
        sentinel.resetForProject(project)

        val refTrack = ReferenceCaptionTrack(
            mediaId = project.id,
            trackId = "yt_en_ref",
            languageCode = "en",
            originKind = CaptionOriginKind.AUTHORED,
            sourceRevision = 1L,
            provenanceLabel = "youtube_authored_captions",
            cues = listOf(
                ReferenceCaptionCue("ref_1", 10_000L, 13_200L, "Let us examine the housing proposal carefully."),
                ReferenceCaptionCue("ref_2", 18_000L, 21_500L, "That policy has already started rolling out."),
                ReferenceCaptionCue("ref_3", 25_000L, 27_400L, "User locked line should stay where the user put it."),
            ),
        )
        assertTrue(store.updateReferenceTrack(refTrack, sessionToken = 1L))

        val evtOverlapDrift = DialogueEvent(
            id = "e_overlap",
            startTimeMs = 10_900L,
            endTimeMs = 13_900L,
            sourceText = "Let us examine the housing proposal carefully.",
            translatedText = "Let us examine the housing proposal carefully.",
            speakerId = "spk_a",
            speakerConfidence = 0.88f,
            faceTrackId = null,
            faceConfidence = 0.8f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.NEUTRAL_FALLBACK,
            stableCueKey = "k_overlap",
        )
        val evtDriftedSemantic = DialogueEvent(
            id = "e_semantic_drift",
            startTimeMs = 15_000L,
            endTimeMs = 17_600L,
            sourceText = "That policy has already started rolling out.",
            translatedText = "That policy has already started rolling out.",
            speakerId = "spk_a",
            speakerConfidence = 0.88f,
            faceTrackId = null,
            faceConfidence = 0.8f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.NEUTRAL_FALLBACK,
            stableCueKey = "k_semantic",
        )
        val evtLocked = DialogueEvent(
            id = "e_locked",
            startTimeMs = 24_100L,
            endTimeMs = 26_600L,
            sourceText = "User locked line should stay where the user put it.",
            translatedText = "User locked line should stay where the user put it.",
            speakerId = "spk_a",
            speakerConfidence = 0.95f,
            faceTrackId = null,
            faceConfidence = 0.9f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            lockedByUser = true,
            stableCueKey = "k_locked",
        )
        val report = Scout1TimestampReport(
            windowStartMs = 0L,
            windowEndMs = 40_000L,
            clockProvenance = ScoutClockProvenance.OFFSET_SHIFTED_RELATIVE,
            cueTimestamps = listOf(
                Scout1CueTimestamp("e_overlap", "k_overlap", 10_900L, 13_900L, rawStartSec = 10.9, rawEndSec = 13.9, appliedClockOffsetMs = 0L),
                Scout1CueTimestamp("e_semantic_drift", "k_semantic", 15_000L, 17_600L, rawStartSec = 5.0, rawEndSec = 7.6, appliedClockOffsetMs = 10_000L),
                Scout1CueTimestamp("e_locked", "k_locked", 24_100L, 26_600L, rawStartSec = 24.1, rawEndSec = 26.6, appliedClockOffsetMs = 0L),
            ),
        )
        val draft = DraftInterval(
            intervalId = "win_0_40000",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = 0L,
            endMs = 40_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(evtOverlapDrift, evtDriftedSemantic, evtLocked),
            speakers = project.speakers,
            referenceTrackId = refTrack.trackId,
            timestampReport = report,
        )
        assertTrue(store.stageDraft(draft))
        val outcome = reviewer.reviewDraft(draft, refTrack, layout, project)
        val snap = store.commitReviewedRelease(draft, outcome, 1_500L)
        assertNotNull(snap)

        val snapped1 = outcome.repairedEvents.first { it.id == "e_overlap" }
        val snapped2 = outcome.repairedEvents.first { it.id == "e_semantic_drift" }
        val lockedAfter = outcome.repairedEvents.first { it.id == "e_locked" }

        assertEquals(10_000L, snapped1.startTimeMs)
        assertEquals(13_200L, snapped1.endTimeMs)
        assertEquals(18_000L, snapped2.startTimeMs)
        assertEquals(21_500L, snapped2.endTimeMs)
        assertEquals(24_100L, lockedAfter.startTimeMs)
        assertEquals(26_600L, lockedAfter.endTimeMs)
        assertEquals(2, outcome.timestampAdjustedCount)
        assertEquals(1, outcome.timestampVerifiedCount)
        assertTrue(outcome.timestampAdjustments.all { it.reason == "SNAPPED_TO_REFERENCE" })
        assertTrue(outcome.scout1ReportSummary.contains("OFFSET_SHIFTED_RELATIVE"))

        sentinel.recordScoutTimestampAudit(
            reportSummary = outcome.scout1ReportSummary,
            verifiedCount = outcome.timestampVerifiedCount,
            adjustedCount = outcome.timestampAdjustedCount,
            adjustments = outcome.timestampAdjustments,
        )
        val inspectorSnap = sentinel.inspectorSnapshot()
        assertEquals(2, inspectorSnap.totalTimestampsAdjusted)
        assertEquals(1, inspectorSnap.totalTimestampsVerified)
        assertEquals(2, inspectorSnap.recentTimestampAdjustments.size)

        // Render Sentinel Inspector Diagnostics UI and verify Scout 1 -> 2 Timestamp Audit appears cleanly
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(480.dp, 900.dp)) {
                    TimelineSubtitleEditor(
                        project = project.copy(dialogueEvents = outcome.repairedEvents),
                        currentTimeMs = 12_000L,
                        selectedEventId = null,
                        isTranslatingAi = false,
                        initialTab = InspectorTab.DIAGNOSTICS,
                        onSelectEvent = {},
                        onSeekTo = {},
                        onAssignSpeaker = { _, _ -> },
                        onUpdateEvent = { _, _, _, _, _, _, _ -> },
                        onMergeWithNext = {},
                        onSplitEvent = {},
                        onResetAnchor = {},
                        onRenameSpeaker = { _, _, _ -> },
                        onTranslateWithAi = {},
                        onAnalyzeSpeakers = {},
                        onAddManualLine = { _, _, _ -> },
                        sentinelSnapshot = inspectorSnap,
                        releaseSnapshot = snap!!,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("studio_diagnostics_list", useUnmergedTree = true)
            .performScrollToKey("sentinel_inspector_card")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sentinel_inspector_card", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("sentinel_timestamp_audit", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Scout 1 → 2 Timestamp Audit", substring = true, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 ok · 2 snapped", substring = true, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("SNAPPED_TO_REFERENCE", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
            .onFirst()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun scout2ClampsOutOfWindowAndResolvesOverlapWhenNoReferenceTrackAttached() {
        val reviewer = Scout2IndependentReviewer()
        val layout = BalloonLayoutEngine()
        val project = testProject(
            speakers = listOf(Speaker(id = "spk_a", defaultLabel = "Host", accentIndex = 0, isIdentityConfirmed = true)),
        )
        val draft = DraftInterval(
            intervalId = "win_noref",
            mediaId = project.id,
            sessionToken = 1L,
            seekGeneration = 0L,
            sourceRevision = 1L,
            draftRevision = 1L,
            configFingerprint = "English|English|NATURAL|PRO_ACCURACY",
            startMs = 20_000L,
            endMs = 40_000L,
            sourceLanguage = "English",
            targetLanguage = "English",
            events = listOf(
                DialogueEvent(
                    id = "c1",
                    startTimeMs = 19_200L,
                    endTimeMs = 24_000L,
                    sourceText = "First sentence starts slightly early.",
                    translatedText = "First sentence starts slightly early.",
                    speakerId = "spk_a",
                    speakerConfidence = 0.85f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "shot_1",
                    dialogueType = DialogueType.SPOKEN,
                ),
                DialogueEvent(
                    id = "c2",
                    startTimeMs = 23_400L,
                    endTimeMs = 27_000L,
                    sourceText = "Second sentence overlaps first.",
                    translatedText = "Second sentence overlaps first.",
                    speakerId = "spk_a",
                    speakerConfidence = 0.85f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "shot_1",
                    dialogueType = DialogueType.SPOKEN,
                ),
            ),
            speakers = project.speakers,
            hasIndependentAudioOrFrameEvidence = true,
            timestampReport = Scout1TimestampReport(
                windowStartMs = 20_000L,
                windowEndMs = 40_000L,
                clockProvenance = ScoutClockProvenance.CHUNK_RELATIVE_OFFSET,
                cueTimestamps = listOf(
                    Scout1CueTimestamp("c1", "c1", 19_200L, 24_000L, rawStartSec = -0.8, rawEndSec = 4.0, appliedClockOffsetMs = 20_000L),
                    Scout1CueTimestamp("c2", "c2", 23_400L, 27_000L, rawStartSec = 3.4, rawEndSec = 7.0, appliedClockOffsetMs = 20_000L),
                ),
            ),
        )
        val outcome = reviewer.reviewDraft(draft, referenceTrack = null, layoutEngine = layout, projectTemplate = project)
        val c1 = outcome.repairedEvents.first { it.id == "c1" }
        val c2 = outcome.repairedEvents.first { it.id == "c2" }
        assertTrue("c1 clamped inside [20s, 40s)", c1.startTimeMs >= 20_000L)
        assertTrue("Same-speaker cues resolved without overlap", c1.endTimeMs <= c2.startTimeMs)
        assertTrue(outcome.timestampAdjustedCount >= 1)
    }
}
