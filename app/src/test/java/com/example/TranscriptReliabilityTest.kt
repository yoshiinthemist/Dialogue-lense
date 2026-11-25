package com.example

import com.example.data.ai.AiTranscribedCue
import com.example.data.ai.AiYouTubeTranscriptionResult
import com.example.data.ai.ScoutHeartbeatService
import com.example.data.ai.TranslationAiService
import com.example.data.model.CachedVideoTranscript
import com.example.data.model.CoverageInterval
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueType
import com.example.data.model.PlacementMode
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.subtitle.RawSubtitleCue
import com.example.data.subtitle.YouTubeCaptionFetcher
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.ResilientYouTubeSegmentExecutor
import com.example.domain.analysis.ScoutExecutionTier
import com.example.domain.analysis.SegmentExecutionOutcome
import com.example.domain.analysis.TimeWindowRange
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.analysis.TranscriptionOutcome
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test suite for DialogueLens transcript integrity, identity-aware deduplication,
 * epistemic coverage ledger tracking, failure recovery, and legacy cache migration.
 */
class TranscriptReliabilityTest {

    private fun makeEvent(
        id: String,
        startMs: Long,
        endMs: Long,
        text: String,
        speakerId: String? = "spk_a",
        lockedByUser: Boolean = false,
    ): DialogueEvent = DialogueEvent(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        sourceText = text,
        translatedText = text,
        speakerId = speakerId,
        speakerConfidence = 0.85f,
        faceTrackId = null,
        faceConfidence = 0f,
        shotId = "shot_1",
        dialogueType = DialogueType.SPOKEN,
        placementMode = PlacementMode.AUTO,
        lockedByUser = lockedByUser,
    )

    private fun makeAiCue(
        startMs: Long,
        endMs: Long,
        text: String,
        slot: String = "A",
    ): AiTranscribedCue = AiTranscribedCue(
        startMs = startMs,
        endMs = endMs,
        sourceText = text,
        naturalTranslation = text,
        literalTranslation = "",
        romanization = "",
        dialogueType = DialogueType.SPOKEN,
        suggestedSpeakerSlot = slot,
        contextReason = "",
        culturalNote = "",
    )

    // =========================================================================
    // 1. IDENTITY-AWARE DEDUPLICATION & PRESERVATION OF LEGITIMATE DIALOGUE
    // =========================================================================

    @Test
    fun distinctCharactersRepeatingIdenticalLineTenSecondsApartAreBothPreserved() {
        val existing = listOf(
            makeEvent("e1", 10_000L, 12_200L, "What are you doing here?", speakerId = "spk_a")
        )
        val incoming = listOf(
            makeEvent("e2", 20_000L, 22_200L, "What are you doing here?", speakerId = "spk_b")
        )

        val report = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existing,
            incoming = incoming,
            windowStartMs = 18_000L,
            windowEndMs = 40_000L,
        )

        assertEquals(2, report.events.size)
        assertEquals(1, report.acceptedCount)
        assertEquals(0, report.rejectedCount)
        assertTrue(report.events.any { it.id == "e1" && it.speakerId == "spk_a" })
        assertTrue(report.events.any { it.id == "e2" && it.speakerId == "spk_b" })
    }

    @Test
    fun singleSpeakerRepeatingIdenticalLongSentenceAcross4s10s16s24sIsPreservedEveryLayer() {
        val repeated = "I told you we need to leave immediately!"

        // Layer A: TranslationAiService YouTube payload parser (formerly had 16s same-text filter)
        val json = """
            {
              "noSpeechConfirmed": false,
              "summary": "Urgent scene",
              "speakers": [],
              "cues": [
                {"startSec": 4.0, "endSec": 6.0, "sourceText": "$repeated", "naturalTranslation": "$repeated", "speakerSlot": "A"},
                {"startSec": 8.2, "endSec": 10.2, "sourceText": "$repeated", "naturalTranslation": "$repeated", "speakerSlot": "A"},
                {"startSec": 18.0, "endSec": 20.0, "sourceText": "$repeated", "naturalTranslation": "$repeated", "speakerSlot": "A"},
                {"startSec": 30.0, "endSec": 32.0, "sourceText": "$repeated", "naturalTranslation": "$repeated", "speakerSlot": "A"}
              ]
            }
        """.trimIndent()

        val parsed = TranslationAiService.parseYouTubeTranscriptionPayload(
            rawJson = json,
            windowStartSec = 0,
            windowEndSec = 40,
        )
        assertEquals(TranscriptionOutcome.DIALOGUE_DETECTED, parsed.outcome)
        assertEquals(4, parsed.cues.size)

        // Layer B: ResilientYouTubeSegmentExecutor deduplicateAndClampCuesWithCount
        val (execDeduped, execDiscarded) = ResilientYouTubeSegmentExecutor.deduplicateAndClampCuesWithCount(
            cues = parsed.cues,
            windowStartMs = 0L,
            windowEndMs = 40_000L,
        )
        assertEquals(4, execDeduped.size)
        assertEquals(0, execDiscarded)

        // Layer C: LookaheadWindowPlanner mergeWindowDialogueEventsWithReport (formerly had 24s/10s/4.5s rules)
        val existing = listOf(makeEvent("e1", 4_000L, 6_000L, repeated, speakerId = "spk_a"))
        val incoming = listOf(
            makeEvent("e2", 8_200L, 10_200L, repeated, speakerId = "spk_a"),
            makeEvent("e3", 18_000L, 20_000L, repeated, speakerId = "spk_a"),
            makeEvent("e4", 26_000L, 28_000L, repeated, speakerId = "spk_a"),
        )
        val report = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existing,
            incoming = incoming,
            windowStartMs = 8_000L,
            windowEndMs = 40_000L,
        )
        assertEquals(4, report.events.size)
        assertEquals(0, report.rejectedCount)
    }

    @Test
    fun shortExclamationsWithinTwoSecondsArePreservedInAiAndYouTubeCaptions() {
        val rawCc = listOf(
            RawSubtitleCue(startTimeMs = 1_000L, endTimeMs = 1_600L, text = "Wait!"),
            RawSubtitleCue(startTimeMs = 2_400L, endTimeMs = 3_000L, text = "Wait!"),
            RawSubtitleCue(startTimeMs = 3_800L, endTimeMs = 4_300L, text = "No!"),
            RawSubtitleCue(startTimeMs = 5_000L, endTimeMs = 5_500L, text = "No!"),
        )
        val mergedCc = YouTubeCaptionFetcher.mergeRollingDuplicates(rawCc)
        assertEquals(4, mergedCc.size)

        val aiCues = listOf(
            makeAiCue(1_000L, 1_600L, "Wait!", slot = "A"),
            makeAiCue(2_400L, 3_000L, "Wait!", slot = "A"),
        )
        val (dedupedAi, discarded) = ResilientYouTubeSegmentExecutor.deduplicateAndClampCuesWithCount(
            cues = aiCues,
            windowStartMs = 0L,
            windowEndMs = 10_000L,
        )
        assertEquals(2, dedupedAi.size)
        assertEquals(0, discarded)
    }

    @Test
    fun simultaneousDistinctSpeakersSayingIdenticalTextAtSameTimestampAreBothPreserved() {
        val existing = listOf(
            makeEvent("e_a", 15_000L, 17_000L, "Happy birthday to you!", speakerId = "spk_a")
        )
        val incoming = listOf(
            makeEvent("e_b", 15_050L, 17_050L, "Happy birthday to you!", speakerId = "spk_b")
        )
        val report = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existing,
            incoming = incoming,
            windowStartMs = 10_000L,
            windowEndMs = 30_000L,
        )
        assertEquals(2, report.events.size)
        assertEquals(0, report.rejectedCount)

        // Also check segment executor with distinct slots A vs B
        val (slotDeduped, slotDiscarded) = ResilientYouTubeSegmentExecutor.deduplicateAndClampCuesWithCount(
            cues = listOf(
                makeAiCue(15_000L, 17_000L, "Happy birthday to you!", slot = "A"),
                makeAiCue(15_050L, 17_050L, "Happy birthday to you!", slot = "B"),
            ),
            windowStartMs = 10_000L,
            windowEndMs = 30_000L,
        )
        assertEquals(2, slotDeduped.size)
        assertEquals(0, slotDiscarded)
    }

    @Test
    fun overlappingWindowDuplicateForSameUtteranceAndSpeakerIsDeduplicatedWithAuditRecord() {
        val existing = listOf(
            makeEvent("e1", 34_200L, 36_400L, "Let's check the hallway before midnight.", speakerId = "spk_a")
        )
        val incoming = listOf(
            makeEvent("e1_seam", 34_350L, 36_500L, "Let's check the hallway before midnight!", speakerId = "spk_a")
        )
        val report = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existing,
            incoming = incoming,
            windowStartMs = 34_000L,
            windowEndMs = 74_000L,
        )
        assertEquals(1, report.events.size)
        assertEquals(0, report.acceptedCount)
        assertEquals(1, report.rejectedCount)
        assertFalse(report.decisions.first().accepted)
    }

    @Test
    fun repeatingChorusMidWindowIsPreservedByAntiEchoFilter() {
        val chorus = "We are standing on the edge of tomorrow night"
        val existing = listOf(
            makeEvent("intro_chorus", 5_000L, 8_000L, chorus, speakerId = "spk_a")
        )
        // Chorus repeats genuinely at 95s inside a 80s..120s window (not at the 80s..84s leading edge)
        val incoming = listOf(
            makeEvent("mid_verse", 85_000L, 88_000L, "The lights fade down across the stage", speakerId = "spk_a"),
            makeEvent("second_chorus", 95_000L, 98_000L, chorus, speakerId = "spk_a"),
        )
        val filtered = LookaheadWindowPlanner.filterEchoedOpeningCues(
            existing = existing,
            incoming = incoming,
            windowStartMs = 80_000L,
        )
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.id == "second_chorus" })
    }

    @Test
    fun lockedUserEventProtectsSameSpeakerOverlapWhilePreservingSimultaneousDistinctSpeaker() {
        val locked = makeEvent("locked_a", 12_000L, 14_000L, "Don't open that door!", speakerId = "spk_a", lockedByUser = true)
        val incoming = listOf(
            makeEvent("dup_a", 12_200L, 13_900L, "Do not open that door!", speakerId = "spk_a"),
            makeEvent("simul_b", 12_300L, 14_100L, "Wait, someone is knocking!", speakerId = "spk_b"),
        )
        val report = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = listOf(locked),
            incoming = incoming,
            windowStartMs = 10_000L,
            windowEndMs = 20_000L,
        )
        assertEquals(2, report.events.size)
        assertTrue(report.events.any { it.id == "locked_a" })
        assertTrue(report.events.any { it.id == "simul_b" })
    }

    // =========================================================================
    // 2. EXPLICIT TRANSCRIPTION OUTCOMES (VERIFIED SILENCE VS FAILURE/AMBIGUITY)
    // =========================================================================

    @Test
    fun youtubeParserSeparatesVerifiedSilenceFromUnconfirmedEmptyAndMalformedPayloads() {
        val verifiedSilence = TranslationAiService.parseYouTubeTranscriptionPayload(
            rawJson = """{"noSpeechConfirmed": true, "summary": "Instrumental intro", "speakers": [], "cues": []}""",
            windowStartSec = 0,
            windowEndSec = 35,
        )
        assertEquals(TranscriptionOutcome.VERIFIED_SILENCE, verifiedSilence.outcome)
        assertTrue(verifiedSilence.noSpeechConfirmed)
        assertTrue(verifiedSilence.cues.isEmpty())

        val unconfirmedEmpty = TranslationAiService.parseYouTubeTranscriptionPayload(
            rawJson = """{"noSpeechConfirmed": false, "summary": "", "speakers": [], "cues": []}""",
            windowStartSec = 35,
            windowEndSec = 75,
        )
        assertEquals(TranscriptionOutcome.EMPTY_UNCONFIRMED, unconfirmedEmpty.outcome)
        assertFalse(unconfirmedEmpty.noSpeechConfirmed)

        val malformed = TranslationAiService.parseYouTubeTranscriptionPayload(
            rawJson = "{truncated json...",
            windowStartSec = 35,
            windowEndSec = 75,
        )
        assertEquals(TranscriptionOutcome.INVALID_AI_RESPONSE, malformed.outcome)

        val missingCuesArray = TranslationAiService.parseYouTubeTranscriptionPayload(
            rawJson = """{"summary": "oops"}""",
            windowStartSec = 35,
            windowEndSec = 75,
        )
        assertEquals(TranscriptionOutcome.INVALID_AI_RESPONSE, missingCuesArray.outcome)
    }

    @Test
    fun youtubeParserFlagsAmbiguousOutOfBoundsTimestampsAndPreservesPartialValidCues() {
        // All cues way out of window bounds -> INVALID_OR_AMBIGUOUS_TIMESTAMPS (NOT verified silence)
        val allOutOfBounds = TranslationAiService.parseYouTubeTranscriptionPayload(
            rawJson = """
                {
                  "noSpeechConfirmed": false,
                  "cues": [
                    {"startSec": 5.0, "endSec": 7.0, "sourceText": "Wrong opening line echoed", "naturalTranslation": "Wrong opening line echoed"}
                  ]
                }
            """.trimIndent(),
            windowStartSec = 80,
            windowEndSec = 120,
            priorWindowNormalizedTexts = setOf("wrongopeninglineechoed"),
        )
        assertEquals(TranscriptionOutcome.INVALID_OR_AMBIGUOUS_TIMESTAMPS, allOutOfBounds.outcome)
        assertTrue(allOutOfBounds.cues.isEmpty())

        // Mix of valid in-window cues + corrupt/invalid cues -> PARTIAL_SUCCESS salvaging the valid ones
        val partialPayload = TranslationAiService.parseYouTubeTranscriptionPayload(
            rawJson = """
                {
                  "noSpeechConfirmed": false,
                  "cues": [
                    {"startSec": 82.0, "endSec": 85.0, "sourceText": "Valid line inside window", "naturalTranslation": "Valid line inside window"},
                    {"startSec": "bad", "endSec": 89.0, "sourceText": "Corrupt timestamp line", "naturalTranslation": "Corrupt timestamp line"},
                    {"startSec": 250.0, "endSec": 255.0, "sourceText": "Out of bounds line", "naturalTranslation": "Out of bounds line"}
                  ]
                }
            """.trimIndent(),
            windowStartSec = 80,
            windowEndSec = 120,
        )
        assertEquals(TranscriptionOutcome.PARTIAL_SUCCESS, partialPayload.outcome)
        assertEquals(1, partialPayload.cues.size)
        assertEquals("Valid line inside window", partialPayload.cues.first().sourceText)
        assertTrue(partialPayload.invalidCueCount > 0 || partialPayload.outOfBoundsCueCount > 0)
    }

    @Test
    fun audioParserDistinguishesDialogueVerifiedSilenceEmptyUnconfirmedAndInvalidJson() {
        val silent = TranslationAiService.parseAudioTranscriptionPayload(
            rawJson = """{"noSpeechConfirmed": true, "cues": []}""",
            chunkStartMs = 0L,
        )
        assertEquals(TranscriptionOutcome.VERIFIED_SILENCE, silent.outcome)
        assertTrue(silent.noSpeechConfirmed)

        val unconfirmed = TranslationAiService.parseAudioTranscriptionPayload(
            rawJson = """{"cues": []}""",
            chunkStartMs = 0L,
        )
        assertEquals(TranscriptionOutcome.EMPTY_UNCONFIRMED, unconfirmed.outcome)

        val invalid = TranslationAiService.parseAudioTranscriptionPayload(
            rawJson = "not-json",
            chunkStartMs = 0L,
        )
        assertEquals(TranscriptionOutcome.INVALID_AI_RESPONSE, invalid.outcome)

        val ok = TranslationAiService.parseAudioTranscriptionPayload(
            rawJson = """
                {
                  "noSpeechConfirmed": false,
                  "cues": [
                    {"startOffsetSec": 1.2, "endOffsetSec": 3.4, "sourceText": "Hello there", "naturalTranslation": "Hello there", "speakerSlot": "A"}
                  ]
                }
            """.trimIndent(),
            chunkStartMs = 10_000L,
        )
        assertEquals(TranscriptionOutcome.DIALOGUE_DETECTED, ok.outcome)
        assertEquals(1, ok.cues.size)
        assertEquals(11_200L, ok.cues.first().startMs)
    }

    // =========================================================================
    // 3. RESILIENT SEGMENT EXECUTOR FAILURE ISOLATION & CLARIFICATION RETRY
    // =========================================================================

    @Test
    fun executorClarifiesAndIsolatesAmbiguousTimestampsInsteadOfMarkingVerifiedSilence() = runBlocking {
        val heartbeat = ScoutHeartbeatService(probeFlashLite = { Result.success(10L) })
        val executor = ResilientYouTubeSegmentExecutor(heartbeat)
        var clarificationRequested = false

        val outcome = executor.executeSegmentDetailed(
            startSec = 40,
            endSec = 50, // leaf slice (10s) so clarification retry triggers directly
            initialTier = ScoutExecutionTier.LITE_AI,
        ) { _, _, _, isClarification ->
            if (isClarification) {
                clarificationRequested = true
            }
            Result.success(
                AiYouTubeTranscriptionResult(
                    cues = emptyList(),
                    outcome = TranscriptionOutcome.INVALID_OR_AMBIGUOUS_TIMESTAMPS,
                    diagnosticNote = "Echoed wrong window",
                )
            )
        }

        assertTrue(clarificationRequested)
        val completed = outcome as SegmentExecutionOutcome.Completed
        assertTrue("Ambiguous timestamps must NEVER become verified silence", completed.verifiedSilentRanges.isEmpty())
        assertTrue(completed.verifiedDialogueRanges.isEmpty())
        assertEquals(listOf(40 to 50), completed.failedRanges)
        assertEquals(TranscriptionOutcome.INVALID_OR_AMBIGUOUS_TIMESTAMPS, completed.failedDetails.first().outcome)
        assertTrue(completed.invalidTimestampCount >= 1)
    }

    @Test
    fun executorBisectsFailurePreservesValidHalfAndMarksOnlyFailingSubSlice() = runBlocking {
        val heartbeat = ScoutHeartbeatService(probeFlashLite = { Result.success(10L) })
        val executor = ResilientYouTubeSegmentExecutor(heartbeat)

        val outcome = executor.executeSegmentDetailed(
            startSec = 40,
            endSec = 80,
            initialTier = ScoutExecutionTier.LITE_AI,
        ) { ws, we, _, _ ->
            when {
                ws == 40 && we == 80 -> Result.success(
                    AiYouTubeTranscriptionResult(
                        cues = emptyList(),
                        outcome = TranscriptionOutcome.INVALID_AI_RESPONSE,
                        diagnosticNote = "Truncated 40s JSON",
                    )
                )
                ws == 40 && we == 60 -> Result.success(
                    AiYouTubeTranscriptionResult(
                        cues = listOf(makeAiCue(42_000L, 45_000L, "First half recovered cleanly")),
                        outcome = TranscriptionOutcome.DIALOGUE_DETECTED,
                    )
                )
                ws == 60 && we == 70 -> Result.success(
                    AiYouTubeTranscriptionResult(
                        cues = emptyList(),
                        outcome = TranscriptionOutcome.VERIFIED_SILENCE,
                        noSpeechConfirmed = true,
                    )
                )
                ws == 70 && we == 80 -> Result.success(
                    AiYouTubeTranscriptionResult(
                        cues = emptyList(),
                        outcome = TranscriptionOutcome.EMPTY_UNCONFIRMED,
                        diagnosticNote = "Model omitted speech flag",
                    )
                )
                else -> Result.success(
                    AiYouTubeTranscriptionResult(
                        cues = emptyList(),
                        outcome = TranscriptionOutcome.EMPTY_UNCONFIRMED,
                    )
                )
            }
        }

        val completed = outcome as SegmentExecutionOutcome.Completed
        assertTrue(completed.usedBisection)
        assertEquals(1, completed.cues.size)
        assertEquals(listOf(40 to 60), completed.verifiedDialogueRanges)
        assertEquals(listOf(60 to 70), completed.verifiedSilentRanges)
        assertEquals(listOf(70 to 80), completed.failedRanges)
    }

    @Test
    fun executorPreservesSalvagedCuesOnPartialSuccessLeafSlice() = runBlocking {
        val heartbeat = ScoutHeartbeatService(probeFlashLite = { Result.success(10L) })
        val executor = ResilientYouTubeSegmentExecutor(heartbeat)

        val outcome = executor.executeSegmentDetailed(
            startSec = 10,
            endSec = 20,
            initialTier = ScoutExecutionTier.LITE_AI,
        ) { _, _, _, _ ->
            Result.success(
                AiYouTubeTranscriptionResult(
                    cues = listOf(makeAiCue(12_000L, 14_500L, "Salvaged valid line")),
                    outcome = TranscriptionOutcome.PARTIAL_SUCCESS,
                    invalidCueCount = 1,
                    diagnosticNote = "1 corrupt cue discarded",
                )
            )
        }

        val completed = outcome as SegmentExecutionOutcome.Completed
        assertEquals(1, completed.cues.size)
        assertEquals(listOf(10 to 20), completed.partialRanges)
        assertEquals(listOf(10 to 20), completed.failedRanges)
        assertTrue(completed.verifiedDialogueRanges.isEmpty())
        assertTrue(completed.verifiedSilentRanges.isEmpty())
    }

    // =========================================================================
    // 4. COVERAGE LEDGER, GAP HONESTY, PERSISTENCE & LEGACY V1 MIGRATION
    // =========================================================================

    @Test
    fun ledgerNeverBridgesFailedMiddleGapAndStopsContinuousWatermarkAtHole() {
        val ledger = TranscriptCoverageLedger(initialDurationMs = 120_000L)
        ledger.recordOutcome(0L, 40_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 6)
        ledger.recordOutcome(40_000L, 80_000L, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT, failureReason = "Timeout", incrementRetry = true)
        ledger.recordOutcome(80_000L, 120_000L, CoverageStatus.VERIFIED_SILENCE, SubtitleSourceKind.AI_DRAFT)

        val summary = ledger.summary()
        assertFalse(summary.isFullyVerified)
        assertEquals(40_000L, summary.continuousVerifiedUpToMs)
        assertEquals(120_000L, summary.furthestAnalyzedMs)
        assertEquals(1, summary.failedIntervalCount)
        assertEquals(
            listOf(TimeWindowRange(0L, 40_000L), TimeWindowRange(80_000L, 120_000L)),
            summary.bufferedRanges,
        )
        assertEquals(
            listOf(TimeWindowRange(40_000L, 80_000L)),
            summary.failedRanges,
        )
    }

    @Test
    fun ledgerFinalEofSegmentFailurePreventsFullCompletionClaim() {
        val ledger = TranscriptCoverageLedger(initialDurationMs = 100_000L)
        ledger.recordOutcome(0L, 80_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 12)
        ledger.recordOutcome(80_000L, 100_000L, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT, failureReason = "EOF network drop", incrementRetry = true)

        assertFalse(ledger.isFullyVerified())
        assertEquals(80_000L, ledger.continuousVerifiedUpToMs())
        assertEquals(1, ledger.summary().unresolvedGapCount)
    }

    @Test
    fun ledgerRespectsPlayheadPriorityAndBoundedRetryAttempts() {
        val ledger = TranscriptCoverageLedger(initialDurationMs = 180_000L)
        ledger.recordOutcome(0L, 35_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 5)
        ledger.recordOutcome(35_000L, 75_000L, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT, incrementRetry = true)

        // User seeks to 120s -> nextPriorityInterval must jump to playhead region first
        val atPlayhead = ledger.nextPriorityInterval(playheadMs = 120_000L, maxRetryAttempts = 3)
        assertNotNull(atPlayhead)
        assertTrue(120_000L in atPlayhead!!.startMs until atPlayhead.endMs)

        // Exhaust retries on [35s, 75s) and verify bounded retry stops re-selecting it forever
        ledger.recordOutcome(75_000L, 180_000L, CoverageStatus.VERIFIED_SILENCE, SubtitleSourceKind.AI_DRAFT)
        ledger.recordOutcome(35_000L, 75_000L, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT, incrementRetry = true)
        ledger.recordOutcome(35_000L, 75_000L, CoverageStatus.FAILED, SubtitleSourceKind.AI_DRAFT, incrementRetry = true)

        val afterCeiling = ledger.nextPriorityInterval(playheadMs = 40_000L, maxRetryAttempts = 3)
        assertNull("Retry ceiling must halt infinite retry loops", afterCeiling)
        assertFalse("Unhealed failed slice must still block isFullyVerified()", ledger.isFullyVerified())
    }

    @Test
    fun ledgerV2RoundTripAndInterruptedInProgressRecovery() {
        val ledger = TranscriptCoverageLedger(initialDurationMs = 120_000L)
        ledger.recordOutcome(0L, 40_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 4)
        ledger.markInProgress(40_000L, 80_000L) // simulated app kill mid-request
        ledger.recordOutcome(80_000L, 120_000L, CoverageStatus.VERIFIED_SILENCE, SubtitleSourceKind.AI_DRAFT)

        val cached = CachedVideoTranscript(
            canonicalKey = "yt_abc123_english",
            title = "Test Episode",
            durationMs = 120_000L,
            sourceLanguage = "Japanese",
            targetLanguage = "English",
            subtitleSource = SubtitleSourceKind.AI_DRAFT,
            speakers = listOf(Speaker("spk_a", "Speaker A", accentIndex = 0)),
            shots = emptyList(),
            dialogueEvents = listOf(makeEvent("c1", 5_000L, 8_000L, "Hello")),
            scoutedUpToMs = ledger.continuousVerifiedUpToMs(),
            cacheVersion = CachedVideoTranscript.CURRENT_CACHE_VERSION,
            coverageIntervals = ledger.intervalsSnapshot(),
        )

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(CachedVideoTranscript::class.java)
        val serialized = adapter.toJson(cached)
        val deserialized = adapter.fromJson(serialized)
        assertNotNull(deserialized)

        val restoredIntervals = TranscriptCoverageLedger.restoreOrMigrate(deserialized!!, 120_000L)
        val restoredLedger = TranscriptCoverageLedger(120_000L, restoredIntervals)

        assertFalse(restoredLedger.isFullyVerified())
        assertEquals(40_000L, restoredLedger.continuousVerifiedUpToMs())
        // Interrupted IN_PROGRESS [40s, 80s) must be reset to PENDING so reopening resumes it immediately
        val resumeNext = restoredLedger.nextPriorityInterval(playheadMs = 45_000L)
        assertNotNull(resumeNext)
        assertEquals(40_000L, resumeNext!!.startMs)
        assertEquals(80_000L, resumeNext.endMs)
        assertEquals(CoverageStatus.PENDING, resumeNext.status)
    }

    @Test
    fun legacyV1CacheMigrationPreservesCuesWithoutFabricatingVerifiedCoverage() {
        val legacyJson = """
            {
              "canonicalKey": "yt_legacy_english",
              "title": "Legacy Video",
              "durationMs": 120000,
              "sourceLanguage": "English",
              "targetLanguage": "English",
              "subtitleSource": "AI_DRAFT",
              "speakers": [],
              "shots": [],
              "dialogueEvents": [
                {
                  "id": "old_1",
                  "startTimeMs": 5000,
                  "endTimeMs": 8000,
                  "sourceText": "Saved legacy dialogue",
                  "translatedText": "Saved legacy dialogue",
                  "speakerConfidence": 0.8,
                  "faceConfidence": 0.0,
                  "shotId": "shot_1",
                  "dialogueType": "SPOKEN",
                  "placementMode": "AUTO"
                }
              ],
              "scoutedUpToMs": 115000
            }
        """.trimIndent()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val legacy = moshi.adapter(CachedVideoTranscript::class.java).fromJson(legacyJson)
        assertNotNull(legacy)
        assertEquals(1, legacy!!.cacheVersion)
        assertTrue(legacy.coverageIntervals.isEmpty())
        assertEquals(1, legacy.dialogueEvents.size)

        val migratedIntervals = TranscriptCoverageLedger.restoreOrMigrate(legacy, 120_000L)
        val migratedLedger = TranscriptCoverageLedger(120_000L, migratedIntervals)

        assertFalse("Legacy v1 cache with inflated scoutedUpToMs must NOT claim full verification", migratedLedger.isFullyVerified())
        assertEquals(0L, migratedLedger.continuousVerifiedUpToMs())
        assertTrue(migratedIntervals.any { it.status == CoverageStatus.PARTIAL_RECOVERY })
        assertTrue(migratedIntervals.any { it.status == CoverageStatus.LEGACY_UNVERIFIED })
        assertNotNull(migratedLedger.nextPriorityInterval(playheadMs = 50_000L))
    }

    @Test
    fun authoritativeSourcePrecedenceBlocksLowerAuthorityAiOverwrite() {
        val ledger = TranscriptCoverageLedger()
        val uploadedEvents = listOf(makeEvent("u1", 10_000L, 14_000L, "Authoritative SRT line"))
        ledger.markAuthoritativeTrack(
            durationMs = 60_000L,
            events = uploadedEvents,
            source = SubtitleSourceKind.UPLOADED_FILE,
        )
        assertTrue(ledger.isFullyVerified())

        // Lower-authority AI failure must NOT downgrade authoritative UPLOADED_FILE intervals
        ledger.recordOutcome(
            startMs = 0L,
            endMs = 30_000L,
            status = CoverageStatus.FAILED,
            source = SubtitleSourceKind.AI_DRAFT,
            failureReason = "Stale AI worker error",
            incrementRetry = true,
        )

        assertTrue(ledger.isFullyVerified())
        assertEquals(0, ledger.summary().failedIntervalCount)
        assertTrue(ledger.intervalsSnapshot().all { it.source == SubtitleSourceKind.UPLOADED_FILE })
    }

    @Test
    fun outOfOrderParallelCompletionsConvergeToFullVerificationOnlyWhenAllSlicesVerified() {
        val ledger = TranscriptCoverageLedger(initialDurationMs = 120_000L)
        // Workers complete tail first, then opening, leaving middle unverified
        ledger.recordOutcome(80_000L, 120_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 3)
        ledger.recordOutcome(0L, 40_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 4)
        assertFalse(ledger.isFullyVerified())
        assertEquals(40_000L, ledger.continuousVerifiedUpToMs())

        // Middle slice completes as verified silence -> entire video becomes 100% verified
        ledger.recordOutcome(40_000L, 80_000L, CoverageStatus.VERIFIED_SILENCE, SubtitleSourceKind.AI_DRAFT)
        assertTrue(ledger.isFullyVerified())
        assertEquals(120_000L, ledger.continuousVerifiedUpToMs())
        assertEquals(listOf(TimeWindowRange(0L, 120_000L)), ledger.bufferedRanges())
        assertEquals(0, ledger.summary().unresolvedGapCount)
    }
}
