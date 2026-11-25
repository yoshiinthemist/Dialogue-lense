package com.example

import com.example.data.ai.AiTranslatedLine
import com.example.data.ai.TranslationAiService
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.HealBeacon
import com.example.data.model.HealBeaconStatus
import com.example.data.model.HealProblemHint
import com.example.data.model.HealScope
import com.example.data.model.NormalizedPoint
import com.example.data.model.PlacementMode
import com.example.data.model.QuotaSaverMode
import com.example.data.model.Shot
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.HealBeaconEngine
import com.example.domain.analysis.LookaheadWindowPlanner
import com.example.domain.analysis.PlaybackClockAnchor
import com.example.domain.analysis.SubtitleTimelineMatcher
import com.example.domain.analysis.TimeWindowRange
import com.example.domain.analysis.TranscriptCoverageLedger
import com.example.domain.analysis.TranscriptionOutcome
import com.example.domain.attribution.SpeakerAttributionEngine
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.playback.PlaybackReadinessController
import com.example.domain.playback.PlaybackReadinessState
import com.example.domain.sentinel.SentinelPriorityTier
import com.example.domain.sentinel.SentinelTaskKind
import com.example.domain.sentinel.SentinelWorkItem
import com.example.domain.sentinel.SentinelWorkforceCoordinator
import com.example.data.subtitle.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic regression suite for DialogueLens — Operation Golden Video.
 *
 * Verifies general, media-agnostic reliability guarantees across:
 * - Defect 3: Silent 40-second gap prevention (epistemic coverage ledger enforcement)
 * - Defect 4: Cross-window sequence echo rejection (even with differing speaker slots or absolute timestamps)
 * - Defect 5: Authoritative window-boundary utterance stitching without harming intentional repeats or overlaps
 * - Defect 7: Strict forward monotonicity of PlaybackClockAnchor & flicker-free SubtitleTimelineMatcher
 * - Defect 8: Continuous-lead buffering & seeking across all 5 failure modes + speed scaling
 * - Defect 9: Reading-comfort pacing (15-20 CPS expansion + monolithic cue clause splitting)
 * - Defect 10: Spoken dialogue preservation when speaker identity is uncertain
 * - Defect 11: Targeted Sentinel anomaly queue & localized non-destructive repair
 */
class OperationGoldenVideoRegressionTest {

    private fun cue(
        id: String,
        startMs: Long,
        endMs: Long,
        text: String,
        speakerId: String? = "spk_a",
        confidence: Float = 0.88f,
        anchor: NormalizedPoint? = null,
    ): DialogueEvent = DialogueEvent(
        id = id,
        startTimeMs = startMs,
        endTimeMs = endMs,
        sourceText = text,
        translatedText = text,
        speakerId = speakerId,
        speakerConfidence = confidence,
        faceTrackId = null,
        faceConfidence = 0f,
        shotId = "shot_1",
        dialogueType = DialogueType.SPOKEN,
        anchorPosition = anchor,
    )

    // =========================================================================
    // DEFECT 3 — Entire 40-Second Dialogue Gap (05:15.000–05:55.000 pattern)
    // =========================================================================

    @Test
    fun emptyOrEchoRejectedVerifiedDialogueWindowCannotDisappearSilently() {
        val durationMs = 1_094_500L // ~18m 14.5s
        val ledger = TranscriptCoverageLedger(initialDurationMs = durationMs)

        // Prior windows verified up to 05:15.000 (315_000ms)
        ledger.recordOutcome(0L, 35_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 12)
        for (startSec in 35 until 315 step 40) {
            ledger.recordOutcome(
                startMs = startSec * 1000L,
                endMs = (startSec + 40) * 1000L,
                status = CoverageStatus.VERIFIED_DIALOGUE,
                source = SubtitleSourceKind.AI_DRAFT,
                dialogueCount = 14,
            )
        }
        // Subsequent window [05:55, 06:35) verified
        ledger.recordOutcome(355_000L, 395_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 15)

        // Simulate window [315_000, 355_000) running and attempting to commit VERIFIED_DIALOGUE with 0 cues
        // (e.g., after echo filtering or empty parse rejected all cues)
        ledger.markInProgress(315_000L, 355_000L)
        ledger.recordOutcome(
            startMs = 315_000L,
            endMs = 355_000L,
            status = CoverageStatus.VERIFIED_DIALOGUE,
            source = SubtitleSourceKind.AI_DRAFT,
            dialogueCount = 0,
        )

        // Epistemic ledger guard MUST downgrade zero-cue VERIFIED_DIALOGUE to FAILED
        assertEquals(CoverageStatus.FAILED, ledger.coverageStatusAt(330_000L))
        assertTrue(ledger.hasFailedIntervalAt(330_000L))
        assertEquals(315_000L, ledger.continuousVerifiedUpToMs())
        assertEquals(1, ledger.failedRanges().size)
        assertEquals(315_000L, ledger.failedRanges().first().startMs)
        assertEquals(355_000L, ledger.failedRanges().first().endMs)

        // Priority scheduler MUST re-select the failed 40-second window for recovery
        val next = ledger.nextPriorityInterval(playheadMs = 310_000L, maxRetryAttempts = 3)
        assertNotNull("Failed 40s window must remain eligible for recovery", next)
        assertEquals(315_000L, next!!.startMs)
        assertEquals(355_000L, next.endMs)
    }

    // =========================================================================
    // DEFECT 4 — Earlier Dialogue Sequence Echoing at Later Timestamps
    // =========================================================================

    @Test
    fun crossWindowSequenceEchoRejectedEvenWithDifferentSpeakerAssignmentsOrParaphrasing() {
        val earlierSequence = listOf(
            cue("e1", 166_800L, 179_000L, "Democratic politicians have been debating the childcare funding bill all week.", speakerId = "spk_a"),
            cue("e2", 180_300L, 189_500L, "They highlighted the mayor's accomplishments on neighborhood pothole repairs.", speakerId = "spk_b"),
            cue("e3", 190_200L, 194_800L, "The universal childcare funding package passed committee last night.", speakerId = "spk_a"),
            cue("e4", 195_000L, 198_000L, "And road crews already finished forty blocks of pothole repairs.", speakerId = "spk_b"),
            cue("e5", 198_200L, 203_000L, "Voters want to see whether those budget promises hold up.", speakerId = "spk_a"),
        )

        // Later window [235s, 275s) echoes the exact earlier sequence with swapped speakers & mild wording variance
        val echoedLaterWindow = listOf(
            cue("late_1", 249_500L, 259_000L, "Democratic politicians have been debating the childcare funding bill all week", speakerId = "spk_c"),
            cue("late_2", 260_000L, 269_500L, "They highlighted the mayor's accomplishments on pothole repairs.", speakerId = "spk_a"),
            cue("late_3", 270_000L, 272_400L, "The universal childcare funding package passed committee last night", speakerId = "spk_b"),
            cue("late_4", 272_500L, 274_900L, "Road crews already finished forty blocks of pothole repairs.", speakerId = "spk_c"),
        )

        val filtered = LookaheadWindowPlanner.filterEchoedOpeningCues(
            existing = earlierSequence,
            incoming = echoedLaterWindow,
            windowStartMs = 235_000L,
        )
        assertTrue(
            "Ordered multi-cue cross-window sequence echo must be rejected even with different speakers",
            filtered.isEmpty(),
        )

        // Meanwhile, an isolated legitimate repeated refrain inside genuinely fresh dialogue MUST survive
        val genuineFreshWindow = listOf(
            cue("fresh_1", 240_000L, 245_000L, "Now let's switch to the transit committee hearing from this morning.", speakerId = "spk_b"),
            cue("fresh_2", 246_000L, 250_000L, "Voters want to see whether those budget promises hold up.", speakerId = "spk_a"),
            cue("fresh_3", 251_000L, 257_000L, "Subway signal upgrades are scheduled to start next quarter.", speakerId = "spk_b"),
        )
        val preserved = LookaheadWindowPlanner.filterEchoedOpeningCues(
            existing = earlierSequence,
            incoming = genuineFreshWindow,
            windowStartMs = 235_000L,
        )
        assertEquals("Legitimate isolated repeated refrain in fresh dialogue must be preserved", 3, preserved.size)
    }

    @Test
    fun translationAiParserRejectsAbsoluteTimestampEchoFromPriorWindow() {
        val priorTexts = setOf(
            "democratic politicians have been debating the childcare funding bill all week",
            "they highlighted the mayors accomplishments on neighborhood pothole repairs",
            "the universal childcare funding package passed committee last night",
        )
        val echoedAbsoluteJson = """
            {
              "noSpeechConfirmed": false,
              "cues": [
                {"startSec": 249.5, "endSec": 259.0, "speakerSlot": "A", "sourceText": "Democratic politicians have been debating the childcare funding bill all week.", "translatedText": "Democratic politicians have been debating the childcare funding bill all week."},
                {"startSec": 260.0, "endSec": 269.5, "speakerSlot": "B", "sourceText": "They highlighted the mayor's accomplishments on neighborhood pothole repairs.", "translatedText": "They highlighted the mayor's accomplishments on neighborhood pothole repairs."},
                {"startSec": 270.0, "endSec": 274.5, "speakerSlot": "A", "sourceText": "The universal childcare funding package passed committee last night.", "translatedText": "The universal childcare funding package passed committee last night."}
              ]
            }
        """.trimIndent()

        val parsed = TranslationAiService.parseYouTubeTranscriptionPayload(
            rawJson = echoedAbsoluteJson,
            windowStartSec = 235,
            windowEndSec = 275,
            priorWindowNormalizedTexts = priorTexts,
        )
        assertEquals(TranscriptionOutcome.INVALID_OR_AMBIGUOUS_TIMESTAMPS, parsed.outcome)
        assertTrue(parsed.cues.isEmpty())
    }

    // =========================================================================
    // DEFECT 5 — Analysis Window Stitching (03:15, 12:35, 16:35, 17:15 patterns)
    // =========================================================================

    @Test
    fun boundaryStitchingEliminatesExactDuplicatesAndTrimsOverlapContinuationsWhilePreservingIntentionalRepeats() {
        // Case A: Exact short boundary repeat (12:35 = 755_000ms)
        val existing1235 = listOf(
            cue("c229", 752_800L, 755_000L, "Exactly right.", speakerId = "spk_a"),
        )
        val incoming1235 = listOf(
            cue("c230", 755_000L, 756_400L, "Exactly right.", speakerId = "spk_a"),
            cue("c231", 756_600L, 760_000L, "That brings us to the next question.", speakerId = "spk_a"),
        )
        val merged1235 = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existing1235,
            incoming = incoming1235,
            windowStartMs = 755_000L,
            windowEndMs = 795_000L,
        )
        assertEquals(2, merged1235.events.size)
        assertFalse(merged1235.events.any { it.id == "c230" })

        // Case B: Trailing phrase repeated across boundary (16:35 = 995_000ms)
        val existing1635 = listOf(
            cue("c302", 990_200L, 995_000L, "We reviewed every single ballot county by county across the state.", speakerId = "spk_a"),
        )
        val incoming1635 = listOf(
            cue("c303", 995_000L, 997_200L, "county by county across the state.", speakerId = "spk_a"),
            cue("c304", 997_400L, 1_001_000L, "And the certified numbers matched.", speakerId = "spk_a"),
        )
        val merged1635 = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existing1635,
            incoming = incoming1635,
            windowStartMs = 995_000L,
            windowEndMs = 1_035_000L,
        )
        assertEquals(2, merged1635.events.size)
        assertFalse(merged1635.events.any { it.id == "c303" })

        // Case C: Substantial prefix continuation across boundary (03:15 = 195_000ms & 17:15 = 1_035_000ms)
        val existing1715 = listOf(
            cue("c314", 1_030_000L, 1_035_000L, "If you look at the turnout numbers from the primary election", speakerId = "spk_b"),
        )
        val incoming1715 = listOf(
            cue("c315", 1_035_000L, 1_040_500L, "from the primary election you can see younger voters showed up early.", speakerId = "spk_b"),
        )
        val merged1715 = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existing1715,
            incoming = incoming1715,
            windowStartMs = 1_035_000L,
            windowEndMs = 1_075_000L,
        )
        assertEquals(2, merged1715.events.size)
        val stitched = merged1715.events.first { it.id == "c315" }
        assertEquals("you can see younger voters showed up early.", stitched.sourceText)
        assertEquals("you can see younger voters showed up early.", stitched.translatedText)

        // Case D: Simultaneous distinct speakers at boundary MUST be preserved
        val existingOverlap = listOf(
            cue("ov_1", 193_000L, 195_000L, "Wait a second.", speakerId = "spk_a"),
        )
        val incomingOverlap = listOf(
            cue("ov_2", 195_000L, 196_800L, "Wait a second.", speakerId = "spk_b"),
        )
        val mergedOverlap = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existingOverlap,
            incoming = incomingOverlap,
            windowStartMs = 195_000L,
            windowEndMs = 235_000L,
        )
        assertEquals("Simultaneous/distinct speaker boundary line must be preserved", 2, mergedOverlap.events.size)

        // Case E: Intentional non-boundary repetition mid-window MUST be preserved
        val existingMid = listOf(
            cue("mid_1", 205_000L, 207_000L, "Never again.", speakerId = "spk_a"),
        )
        val incomingMid = listOf(
            cue("mid_2", 215_000L, 217_000L, "Never again.", speakerId = "spk_a"),
        )
        val mergedMid = LookaheadWindowPlanner.mergeWindowDialogueEventsWithReport(
            existing = existingMid,
            incoming = incomingMid,
            windowStartMs = 195_000L,
            windowEndMs = 235_000L,
        )
        assertEquals("Intentional mid-window repetition must never be deleted", 2, mergedMid.events.size)
    }

    // =========================================================================
    // DEFECT 7 — Backward Presentation Clock Movement (36ms Reversal Reproduction)
    // =========================================================================

    @Test
    fun playbackClockRemainsStrictlyMonotonicUnder340msHeartbeatLagWithZeroSubtitleFlicker() {
        val anchor = PlaybackClockAnchor()
        val matcher = SubtitleTimelineMatcher()
        val syntheticEvents = listOf(
            cue("c_edge", 10_320L, 13_000L, "Synthetic edge cue", speakerId = "spk_a"),
        )

        var nowNs = 1_000_000_000L
        anchor.reset(positionMs = 10_000L, speed = 1.0f, playing = true, nowNs = nowNs)
        anchor.reconcileRemoteSample(reportedPosMs = 10_000L, speed = 1.0f, playing = true, nowNs = nowNs)

        var prevPresMs = 10_000L
        var backwardReversals = 0
        var activeCountTransitions = 0
        var wasActive = false
        val onsetErrorsMs = mutableListOf<Long>()

        // Advance smoothly at 16ms frames past 10_320ms to 10_340ms (22 frames = 352ms -> clamped at 320ms -> 10_320ms,
        // plus one gentle heartbeat at +160ms so we reach exactly 10_340ms with leadAhead = 340ms)
        nowNs += 160_000_000L
        anchor.reconcileRemoteSample(reportedPosMs = 10_160L, speed = 1.0f, playing = true, nowNs = nowNs)

        // Advance 180ms forward to 10_340ms -> cue at 10_320ms activates
        for (step in 1..12) {
            nowNs += 15_000_000L
            val presMs = anchor.sampleInterpolatedMs(nowNs = nowNs)
            if (presMs < prevPresMs) backwardReversals++
            prevPresMs = presMs
            val active = matcher.matchActiveEvents(syntheticEvents, presMs).isNotEmpty()
            if (active != wasActive) {
                activeCountTransitions++
                if (active) onsetErrorsMs.add(abs(presMs - 10_320L))
                wasActive = active
            }
        }
        assertTrue("Cue should be active once presentation clock reaches >= 10_320ms", wasActive)
        assertTrue("Presentation clock should have advanced to ~10_340ms", prevPresMs >= 10_330L)

        // Inject the exact version-27 failure condition: lagged remote heartbeat trailing by 340ms (10_000ms vs 10_340ms)
        // Previously: (340 - 260) * 0.45 = 36ms backward jump -> 10_304ms -> cue disappeared & reappeared!
        anchor.reconcileRemoteSample(reportedPosMs = 10_000L, speed = 1.0f, playing = true, nowNs = nowNs)
        val afterLagPresMs = anchor.sampleInterpolatedMs(nowNs = nowNs)
        if (afterLagPresMs < prevPresMs) backwardReversals++
        assertTrue(
            "Presentation clock MUST NOT step backward on 340ms remote heartbeat lag (prev=$prevPresMs, after=$afterLagPresMs)",
            afterLagPresMs >= prevPresMs,
        )
        val activeAfterLag = matcher.matchActiveEvents(syntheticEvents, afterLagPresMs).isNotEmpty()
        assertTrue("Subtitle must remain continuously active without disappear/reappear flicker", activeAfterLag)

        // While authoritative remote catches up over subsequent ticks, clock stays frozen without stepping back
        for (catchUpPos in listOf(10_120L, 10_240L, 10_360L, 10_520L)) {
            nowNs += 120_000_000L
            anchor.reconcileRemoteSample(reportedPosMs = catchUpPos, speed = 1.0f, playing = true, nowNs = nowNs)
            val presMs = anchor.sampleInterpolatedMs(nowNs = nowNs)
            if (presMs < prevPresMs) backwardReversals++
            prevPresMs = presMs
            val active = matcher.matchActiveEvents(syntheticEvents, presMs).isNotEmpty()
            if (active != wasActive) {
                activeCountTransitions++
                wasActive = active
            }
        }

        assertEquals("Zero backward presentation clock reversals allowed during forward playback", 0, backwardReversals)
        assertEquals("Cue must transition inactive->active exactly once (no flicker)", 1, activeCountTransitions)
        assertTrue("Subtitle onset accuracy must stay well under 50ms", onsetErrorsMs.all { it <= 50L })

        // Verify stall clamping: if heartbeats stop completely for 5 seconds, clock freezes within MAX_REMOTE_EXTRAPOLATION_MS
        val beforeStallMs = anchor.sampleInterpolatedMs(nowNs = nowNs)
        nowNs += 5_000_000_000L
        val duringStallMs = anchor.sampleInterpolatedMs(nowNs = nowNs)
        assertTrue(
            "Clock must freeze during heartbeat stall instead of extrapolating indefinitely",
            duringStallMs - beforeStallMs <= PlaybackClockAnchor.MAX_REMOTE_EXTRAPOLATION_MS + 10L,
        )
        assertTrue(anchor.diagnostics().isMediaStalled)

        // Verify genuine backward seek (> 380ms) resets cleanly immediately
        nowNs += 100_000_000L
        anchor.reconcileRemoteSample(reportedPosMs = 2_000L, speed = 1.0f, playing = true, nowNs = nowNs)
        assertEquals(2_000L, anchor.sampleInterpolatedMs(nowNs = nowNs))
        assertTrue(anchor.diagnostics().hardDiscontinuityResets >= 1)
    }

    // =========================================================================
    // DEFECT 8 — All 5 Buffering & Seeking Weaknesses + Speed Scaling
    // =========================================================================

    @Test
    fun bufferingAndSeekingGuardCoversAllFiveConfirmedWeaknessesAndSpeedScaling() {
        val strayEvent = listOf(cue("stray", 50_000L, 52_000L, "Stray cue"))

        // 1. Zero coverage (bufferedRanges empty) MUST wait
        assertTrue(
            LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 5_000L,
                totalDurationMs = 120_000L,
                bufferedRanges = emptyList(),
                events = emptyList(),
                isScoutActive = true,
            ),
        )

        // 2. Unverified region with a single stray cue MUST still wait
        assertTrue(
            LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 45_000L,
                totalDurationMs = 120_000L,
                bufferedRanges = listOf(TimeWindowRange(0L, 35_000L)),
                events = strayEvent,
                isScoutActive = true,
            ),
        )

        // 3. Only 100ms left before unverified boundary MUST trigger waiting (insufficient contiguous lead)
        assertTrue(
            LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 34_900L,
                totalDurationMs = 120_000L,
                bufferedRanges = listOf(TimeWindowRange(0L, 35_000L)),
                events = emptyList(),
                isScoutActive = true,
                playbackSpeed = 1.0f,
            ),
        )

        // 4. Short video (< 30s) with unverified coverage MUST wait (not bypassed!)
        assertTrue(
            LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 2_000L,
                totalDurationMs = 18_000L,
                bufferedRanges = emptyList(),
                events = emptyList(),
                isScoutActive = true,
            ),
        )

        // 5. Near-end unverified region (1.5s before end) MUST wait when not verified
        assertTrue(
            LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 118_500L,
                totalDurationMs = 120_000L,
                bufferedRanges = listOf(TimeWindowRange(0L, 115_000L)),
                events = emptyList(),
                isScoutActive = true,
            ),
        )

        // Speed scaling: 3.0s contiguous lead is sufficient at 1.0x (needs 2.0s) but NOT at 2.0x (needs 4.0s)
        assertFalse(
            LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 32_000L,
                totalDurationMs = 120_000L,
                bufferedRanges = listOf(TimeWindowRange(0L, 35_000L)),
                events = emptyList(),
                isScoutActive = true,
                playbackSpeed = 1.0f,
            ),
        )
        assertTrue(
            LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 32_000L,
                totalDurationMs = 120_000L,
                bufferedRanges = listOf(TimeWindowRange(0L, 35_000L)),
                events = emptyList(),
                isScoutActive = true,
                playbackSpeed = 2.0f,
            ),
        )

        // Verify PlaybackReadinessController handles repeated seeks & recovers automatically once verified
        val ledger = TranscriptCoverageLedger(initialDurationMs = 300_000L)
        val controller = PlaybackReadinessController()
        controller.resetForMedia(0L, 300_000L, requiresCoverageGate = true, playbackSpeed = 1.5f)
        controller.onUserPlayRequested(0L, ledger, nowEpochMs = 1_000L)
        val seekSnap = controller.onUserSeekRequested(180_000L, ledger, nowEpochMs = 2_000L)
        assertEquals(PlaybackReadinessState.BUFFERING_FOR_SEEK, seekSnap.state)
        ledger.recordOutcome(180_000L, 195_000L, CoverageStatus.VERIFIED_DIALOGUE, SubtitleSourceKind.AI_DRAFT, dialogueCount = 5)
        val resumed = controller.evaluateCoverage(ledger, nowEpochMs = 4_000L)
        assertEquals(PlaybackReadinessState.READY_TO_AUTO_RESUME, resumed.state)
        assertEquals(180_000L, resumed.pendingAutoResume?.targetPositionMs)
    }

    // =========================================================================
    // DEFECT 9 — Reading Pace (~15–20 CPS) & Natural Clause Splitting
    // =========================================================================

    @Test
    fun readingPacingExpandsRapidCuesIntoAvailableSilenceAndSplitsDenseMonolithicBlocks() {
        // Rapid 45 CPS cue (45 chars in 1000ms) followed by 3 seconds of silence
        val rapidCue = cue(
            id = "rapid_1",
            startMs = 5_000L,
            endMs = 6_000L,
            text = "The committee announced three major reforms today.", // 50 chars -> 50 CPS!
        )
        val nextCue = cue(
            id = "next_1",
            startMs = 10_000L,
            endMs = 13_000L,
            text = "First is housing.",
        )

        val paced = SubtitleParser.applyReadingPacingGuardrails(listOf(rapidCue, nextCue))
        val extendedRapid = paced.first { it.id == "rapid_1" }
        val durationSec = (extendedRapid.endTimeMs - extendedRapid.startTimeMs) / 1000.0
        val cps = extendedRapid.translatedText.length / durationSec
        assertTrue("Rapid cue should be gently paced down to <= 20 CPS (was $cps CPS)", cps <= 20.0)
        assertTrue("Extended cue must leave breathing room before next cue", extendedRapid.endTimeMs <= nextCue.startTimeMs - 60L)

        // Monolithic dense block (> 88 chars, > 23 CPS, >= 2400ms) should split naturally at clause boundary
        val denseBlock = cue(
            id = "dense_1",
            startMs = 20_000L,
            endMs = 23_200L,
            text = "We inspected every bridge across the northern district, and engineers confirmed all structural repairs are now fully funded for spring.",
        )
        val splitResult = SubtitleParser.applyReadingPacingGuardrails(listOf(denseBlock))
        assertEquals("Dense monolithic cue must split cleanly into 2 readable chunks", 2, splitResult.size)
        val joinedWords = splitResult.joinToString(" ") { it.translatedText }.replace(Regex("\\s+"), " ").trim()
        assertEquals(denseBlock.translatedText, joinedWords)
        assertTrue(splitResult[0].endTimeMs <= splitResult[1].startTimeMs)
    }

    // =========================================================================
    // DEFECT 10 & 11 — Speaker Preservation & Targeted Sentinel Queue + Safe Repair
    // =========================================================================

    @Test
    fun uncertainSpeakerNeverDropsDialogueAndSentinelSafelyRepairsTargetedIntervals() {
        val attributionEngine = SpeakerAttributionEngine()
        val uncertainEvent = cue(
            id = "uncertain_1",
            startMs = 5_000L,
            endMs = 8_000L,
            text = "We should verify the numbers before publishing.",
            speakerId = null,
            confidence = 0.25f,
        )
        val (speakers, attributed) = attributionEngine.assignSpeakers(
            events = listOf(uncertainEvent),
            shots = listOf(Shot("shot_1", 0L, 60_000L)),
            faceTracks = emptyList(),
            existingSpeakers = emptyList(),
            turnHints = mapOf(
                "uncertain_1" to AiTranslatedLine(
                    id = "uncertain_1",
                    naturalTranslation = uncertainEvent.sourceText,
                    literalTranslation = "",
                    romanization = "",
                    dialogueTypeHint = DialogueType.SPOKEN,
                    speakerTurnHint = "UNCERTAIN",
                    suggestedSpeakerSlot = "UNCERTAIN",
                ),
            ),
        )
        assertEquals("Uncertain speaker must NEVER cause spoken dialogue to be dropped", 1, attributed.size)
        assertEquals(uncertainEvent.sourceText, attributed.first().sourceText)
        assertTrue("Fallback speaker roster must remain valid", speakers.isNotEmpty())

        // Verify layout renders uncertain/neutral cue cleanly within visible bounds without dropping it
        val layoutEngine = BalloonLayoutEngine()
        val project = DialogueLensProject(
            id = "proj_golden_test",
            title = "Golden Verification",
            createdAtEpochMs = 1_000L,
            sourceLanguage = "en",
            targetLanguage = "en",
            videoSource = VideoSource(
                kind = VideoSourceKind.YOUTUBE_COMPLIANT,
                uriString = "https://www.youtube.com/watch?v=RzagPoxaolQ",
                displayName = "Golden Verification",
                durationMs = 1_094_500L,
                width = 1920,
                height = 1080,
            ),
            shots = listOf(Shot("shot_1", 0L, 1_094_500L)),
            speakers = speakers,
            faceTracks = emptyList(),
            dialogueEvents = attributed.map { it.copy(placementMode = PlacementMode.NEUTRAL_FALLBACK) },
        )
        val placed = layoutEngine.computeLayout(project, currentMs = 6_000L, liveFaces = emptyList())
        assertEquals(1, placed.size)
        val box = placed.first().rect
        assertTrue(box.left >= 0f && box.right <= 1f && box.top >= 0f && box.bottom <= 1f)

        // Verify Sentinel queues targeted inspection intervals without overlap collision & preserves untouched cues on repair
        val coordinator = SentinelWorkforceCoordinator()
        coordinator.updateRuntimeConditions(
            quotaMode = QuotaSaverMode.PRO_ACCURACY,
            isPlaybackBuffering = false,
            contiguousPlayheadLeadMs = 60_000L,
        )
        val targetRegions = listOf(
            "opening_00_20" to (0L to 20_000L),
            "echo_src_0246_0322" to (166_000L to 202_000L),
            "echo_dst_0351_0441" to (231_000L to 281_000L),
            "gap_0515_0555" to (315_000L to 355_000L),
            "stitch_1233_1237" to (753_000L to 757_000L),
            "stitch_1630_1636" to (990_000L to 996_000L),
            "stitch_1710_1719" to (1_030_000L to 1_039_000L),
        )
        targetRegions.forEach { (id, range) ->
            coordinator.enqueue(
                SentinelWorkItem(
                    id = id,
                    tier = SentinelPriorityTier.USER_DIRECTED_REPAIR,
                    kind = SentinelTaskKind.USER_HEAL_BEACON,
                    startMs = range.first,
                    endMs = range.second,
                ),
            )
        }
        val firstClaim = coordinator.claimNextWork(SentinelWorkforceCoordinator.WORKER_1_ID)
        assertNotNull(firstClaim)
        assertEquals(2, coordinator.maxAllowedWorkers())

        // Localized Sentinel repair on [315_000, 355_000) must leave untouched cues before/after completely intact
        val preGap = cue("cue_92", 310_000L, 315_000L, "Last line before gap.", speakerId = speakers.first().id)
        val postGap = cue("cue_93", 355_000L, 360_000L, "First line after gap.", speakerId = speakers.first().id)
        val gapBeacon = HealBeacon(
            id = "hb_gap_0515",
            timestampMs = 335_000L,
            windowStartMs = 315_000L,
            windowEndMs = 355_000L,
            status = HealBeaconStatus.HEALING,
            scope = HealScope.ADAPTIVE,
            problemHint = HealProblemHint.MISSING_DIALOGUE,
            repairRevision = 1,
        )
        val gapProject = project.copy(
            dialogueEvents = listOf(preGap, postGap),
            healBeacons = listOf(gapBeacon),
        )
        val repairResult = HealBeaconEngine.applyValidatedLocalizedRepair(
            project = gapProject,
            beaconId = "hb_gap_0515",
            candidateEvents = listOf(
                cue("recovered_1", 318_000L, 324_000L, "Recovered authorized dialogue line.", speakerId = speakers.first().id),
            ),
            expectedRepairRevision = 1,
        )
        assertTrue(repairResult.applied)
        assertTrue(repairResult.updatedProject.dialogueEvents.any { it.id == "cue_92" })
        assertTrue(repairResult.updatedProject.dialogueEvents.any { it.id == "cue_93" })
        assertTrue(repairResult.updatedProject.dialogueEvents.any { it.id == "recovered_1" })
    }
}
