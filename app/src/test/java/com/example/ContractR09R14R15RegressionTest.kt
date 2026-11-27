package com.example

import com.example.data.model.BalloonStyleSettings
import com.example.data.model.CaptionAccessibilityContrast
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.ScoutCaptureHealthState
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind
import com.example.data.model.YouTubePlayerErrorKind
import com.example.data.model.classifyYouTubeErrorCode
import com.example.domain.analysis.AudioAnalyzer
import com.example.domain.analysis.LiveFaceObservation
import com.example.domain.analysis.RedirectDecision
import com.example.domain.analysis.WebNavigationGuard
import com.example.domain.layout.BalloonLayoutEngine
import com.example.domain.layout.TailDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Targeted unit regression suite verifying R09, R14, R15, A03, A05, A07, and A09 invariants.
 */
class ContractR09R14R15RegressionTest {

    private fun testProject(
        events: List<DialogueEvent> = emptyList(),
        speakers: List<Speaker> = listOf(
            Speaker(
                id = "spk_a",
                defaultLabel = "Speaker A",
                customName = "Elena Vance",
                accentIndex = 0,
                isIdentityConfirmed = true,
                nameRevealedAtMs = 30_000L,
            )
        ),
        style: BalloonStyleSettings = BalloonStyleSettings(),
    ): DialogueLensProject = DialogueLensProject(
        id = "proj_r09_14_15",
        title = "Regression Test Fixture",
        createdAtEpochMs = 1_000L,
        sourceLanguage = "English",
        targetLanguage = "English",
        videoSource = VideoSource(
            uriString = "https://youtu.be/RzagPoxaolQ",
            displayName = "Regression Fixture",
            kind = VideoSourceKind.YOUTUBE_COMPLIANT,
            durationMs = 240_000L,
            width = 1920,
            height = 1080,
            youtubeVideoId = "RzagPoxaolQ",
        ),
        shots = listOf(Shot("shot_1", 0L, 240_000L)),
        speakers = speakers,
        faceTracks = emptyList(),
        dialogueEvents = events,
        styleSettings = style,
    )

    private fun buildPcm16Wav(
        sampleRateHz: Int = 16_000,
        durationMs: Int = 400,
        amplitude: Float = 0.35f,
    ): ByteArray {
        val sampleCount = (sampleRateHz * durationMs) / 1000
        val dataBytes = sampleCount * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray(Charsets.US_ASCII))
        hdr.putInt(36 + dataBytes)
        hdr.put("WAVE".toByteArray(Charsets.US_ASCII))
        hdr.put("fmt ".toByteArray(Charsets.US_ASCII))
        hdr.putInt(16) // PCM fmt chunk size
        hdr.putShort(1) // PCM format
        hdr.putShort(1) // mono
        hdr.putInt(sampleRateHz)
        hdr.putInt(sampleRateHz * 2) // byteRate
        hdr.putShort(2) // blockAlign
        hdr.putShort(16) // bitsPerSample
        hdr.put("data".toByteArray(Charsets.US_ASCII))
        hdr.putInt(dataBytes)
        out.write(hdr.array())

        val samples = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val s = if (amplitude <= 0f) {
                0
            } else {
                val theta = 2.0 * PI * 440.0 * i / sampleRateHz
                (sin(theta) * amplitude * 32767.0).roundToInt().coerceIn(-32767, 32767)
            }
            samples.putShort(s.toShort())
        }
        out.write(samples.array())
        return out.toByteArray()
    }

    @Test
    fun r09_fourExclusiveCaptionModesAndLegacyMigrationWorkDeterministically() {
        assertEquals(
            listOf(
                CaptionDisplayMode.OFF,
                CaptionDisplayMode.CLASSIC,
                CaptionDisplayMode.BALLOONS,
                CaptionDisplayMode.BUBBLES_WITH_TAILS,
            ),
            CaptionDisplayMode.primaryModes,
        )
        assertEquals(CaptionDisplayMode.OFF, CaptionDisplayMode.migrateLegacy(CaptionDisplayMode.OFF, true))
        assertEquals(CaptionDisplayMode.CLASSIC, CaptionDisplayMode.migrateLegacy(CaptionDisplayMode.CLASSIC, true))
        assertEquals(CaptionDisplayMode.BALLOONS, CaptionDisplayMode.migrateLegacy(CaptionDisplayMode.FIXED_ZONES, true))
        assertEquals(CaptionDisplayMode.BALLOONS, CaptionDisplayMode.migrateLegacy(CaptionDisplayMode.BOTH, false))
        assertEquals(CaptionDisplayMode.BUBBLES_WITH_TAILS, CaptionDisplayMode.migrateLegacy(CaptionDisplayMode.BOTH, true))

        assertFalse(CaptionDisplayMode.OFF.shouldShowBubbles)
        assertFalse(CaptionDisplayMode.OFF.shouldShowClassic)
        assertFalse(CaptionDisplayMode.CLASSIC.shouldShowBubbles)
        assertTrue(CaptionDisplayMode.CLASSIC.shouldShowClassic)
        assertTrue(CaptionDisplayMode.BALLOONS.shouldShowBubbles)
        assertFalse(CaptionDisplayMode.BALLOONS.shouldShowTails)
        assertTrue(CaptionDisplayMode.BUBBLES_WITH_TAILS.shouldShowBubbles)
        assertTrue(CaptionDisplayMode.BUBBLES_WITH_TAILS.shouldShowTails)
    }

    @Test
    fun r09_balloonLayoutEnforcesHalfOpenBoundariesSharedAnchorAndFullCircleTailSweep() {
        val engine = BalloonLayoutEngine()
        val cue1 = DialogueEvent(
            id = "rec_1",
            stableCueKey = "utterance_alpha",
            startTimeMs = 1_000L,
            endTimeMs = 5_000L,
            sourceText = "First sentence right before boundary.",
            translatedText = "First sentence right before boundary.",
            speakerId = "spk_a",
            speakerConfidence = 0.94f,
            faceTrackId = null,
            faceConfidence = 0.88f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            anchorPosition = NormalizedPoint(0.50f, 0.50f),
            placementMode = PlacementMode.AUTO,
        )
        val cue2 = cue1.copy(
            id = "rec_2",
            stableCueKey = "utterance_beta",
            startTimeMs = 5_000L,
            endTimeMs = 9_000L,
            sourceText = "Second sentence starting exactly at boundary.",
            translatedText = "Second sentence starting exactly at boundary.",
        )
        val project = testProject(events = listOf(cue1, cue2))

        // Half-open [start, end): at 5_000L ONLY cue2 should be active (no boundary double-bubble)
        val atBoundary = engine.computeLayout(project, currentMs = 5_000L, displayMode = CaptionDisplayMode.BALLOONS)
        assertEquals(1, atBoundary.size)
        assertEquals("utterance_beta", atBoundary.single().event.canonicalIdentityKey)

        // Check full-circle mouth sweep around committed body anchor on cue1 (t = 2_000ms)
        val tailless = engine.computeLayout(
            project = project,
            currentMs = 2_000L,
            liveFaces = listOf(
                LiveFaceObservation(
                    trackingId = 1,
                    boundingBox = NormalizedRect(0.42f, 0.36f, 0.58f, 0.64f),
                    mouthPoint = NormalizedPoint(0.50f, 0.54f),
                    headYawDeg = 0f,
                    capturedAtMs = 2_000L,
                )
            ),
            hasLiveVisionSignal = true,
            displayMode = CaptionDisplayMode.BALLOONS,
            detectorFrameId = 1L,
        ).single()
        assertNull("BALLOONS must never emit a tailTarget", tailless.tailTarget)

        val tailed = engine.computeLayout(
            project = project,
            currentMs = 2_100L,
            liveFaces = listOf(
                LiveFaceObservation(
                    trackingId = 1,
                    boundingBox = NormalizedRect(0.42f, 0.36f, 0.58f, 0.64f),
                    mouthPoint = NormalizedPoint(0.50f, 0.54f),
                    headYawDeg = 0f,
                    capturedAtMs = 2_100L,
                )
            ),
            hasLiveVisionSignal = true,
            displayMode = CaptionDisplayMode.BUBBLES_WITH_TAILS,
            detectorFrameId = 2L,
        ).single()
        assertNotNull("BUBBLES_WITH_TAILS must emit a tailTarget", tailed.tailTarget)
        assertEquals(tailless.rect.left, tailed.rect.left, 0.001f)
        assertEquals(tailless.rect.top, tailed.rect.top, 0.001f)

        // New event-record ID with same canonicalIdentityKey preserves committed body anchor
        val revisedRecord = cue1.copy(
            id = "rec_1_revised_uuid",
            stableCueKey = "utterance_alpha",
            translatedText = "First sentence right before boundary!",
        )
        val revisedLayout = engine.computeLayout(
            project = project.copy(dialogueEvents = listOf(revisedRecord)),
            currentMs = 2_200L,
            liveFaces = listOf(
                LiveFaceObservation(
                    trackingId = 1,
                    boundingBox = NormalizedRect(0.42f, 0.36f, 0.58f, 0.64f),
                    mouthPoint = NormalizedPoint(0.50f, 0.54f),
                    headYawDeg = 0f,
                    capturedAtMs = 2_200L,
                )
            ),
            hasLiveVisionSignal = true,
            displayMode = CaptionDisplayMode.BUBBLES_WITH_TAILS,
            detectorFrameId = 3L,
        ).single()
        assertEquals(tailed.rect.left, revisedLayout.rect.left, 0.001f)
        assertEquals(tailed.rect.top, revisedLayout.rect.top, 0.001f)
    }

    @Test
    fun r14_speakerNameRevealRespectsViewerClockAndBackwardSeeksWhileUserNamedPersists() {
        val autoDiscovered = Speaker(
            id = "spk_auto",
            defaultLabel = "Speaker A",
            customName = "Dr. Lin",
            accentIndex = 0,
            isIdentityConfirmed = true,
            nameRevealedAtMs = 45_000L,
            isUserNamed = false,
            revealProvenance = "transcript intro at 45s",
        )
        assertEquals("Speaker A", autoDiscovered.visibleDisplayNameAt(10_000L))
        assertFalse(autoDiscovered.hasVisibleCharacterNameAt(10_000L))

        assertEquals("Dr. Lin", autoDiscovered.visibleDisplayNameAt(45_000L))
        assertTrue(autoDiscovered.hasVisibleCharacterNameAt(45_000L))

        // Backward seek before nameRevealedAtMs re-hides unintroduced automatic name
        assertEquals("Speaker A", autoDiscovered.visibleDisplayNameAt(20_000L))
        assertFalse(autoDiscovered.hasVisibleCharacterNameAt(20_000L))

        // User-supplied correction always stays visible regardless of viewer time
        val userNamed = autoDiscovered.copy(isUserNamed = true)
        assertEquals("Dr. Lin", userNamed.visibleDisplayNameAt(2_000L))
        assertTrue(userNamed.hasVisibleCharacterNameAt(2_000L))
    }

    @Test
    fun r14_accessibilityContrastRatioChecksCompositedSurfaceAgainstBrightAndMidBackdrops() {
        val ratioWhiteOnObsidian = CaptionAccessibilityContrast.contrastRatio(0xFFF8FAFC, 0xFF0A0F18)
        assertTrue("Expected >= 4.5:1 for white ink on obsidian, got $ratioWhiteOnObsidian", ratioWhiteOnObsidian >= 4.5f)

        assertTrue(
            CaptionAccessibilityContrast.meetsMinimumTextContrast(
                fgArgb = 0xFFF8FAFC,
                bgContainerArgb = 0xFF0F172A,
                bgAlpha = 0.86f,
                backdropArgb = 0xFFFFFFFF,
            )
        )
        // Very low alpha light gray ink over bright white backdrop fails >= 4.5:1
        assertFalse(
            CaptionAccessibilityContrast.meetsMinimumTextContrast(
                fgArgb = 0xFF94A3B8,
                bgContainerArgb = 0xFF1E293B,
                bgAlpha = 0.20f,
                backdropArgb = 0xFFFFFFFF,
            )
        )
    }

    @Test
    fun r15_pcmWavAudioInspectionClassifiesUsableSpeechSilenceAndCorruptPayloadsHonestly() {
        // 1. Usable speech-energy 16kHz PCM-16 WAV
        val speechWav = buildPcm16Wav(sampleRateHz = 16_000, durationMs = 400, amplitude = 0.35f)
        val speechResult = AudioAnalyzer.inspectPcmWavBytes(speechWav)
        assertEquals(ScoutCaptureHealthState.USABLE_DECODED_INPUT, speechResult.healthState)
        assertEquals(16_000, speechResult.sampleRateHz)
        assertEquals(6_400, speechResult.decodedSamplesCount)
        assertTrue("Expected nonzero RMS energy, got ${speechResult.rmsEnergy}", speechResult.rmsEnergy > 0.1f)

        // 2. Confirmed digital silence 8kHz PCM-16 WAV
        val silentWav = buildPcm16Wav(sampleRateHz = 8_000, durationMs = 400, amplitude = 0f)
        val silentResult = AudioAnalyzer.inspectPcmWavBytes(silentWav)
        assertEquals(ScoutCaptureHealthState.CONFIRMED_SILENCE, silentResult.healthState)
        assertEquals(8_000, silentResult.sampleRateHz)
        assertEquals(3_200, silentResult.decodedSamplesCount)

        // 3. Truncated / corrupt header must classify as DECODE_FAILED, never CONFIRMED_SILENCE
        val truncated = speechWav.copyOf(20)
        val corruptResult = AudioAnalyzer.inspectPcmWavBytes(truncated)
        assertEquals(ScoutCaptureHealthState.DECODE_FAILED, corruptResult.healthState)
        assertEquals(0, corruptResult.decodedSamplesCount)
    }

    @Test
    fun r07_r11_youtubeErrorClassificationAndWebNavigationGuardDistinguishCauses() {
        assertEquals(YouTubePlayerErrorKind.CLIENT_IDENTITY_153, classifyYouTubeErrorCode(153))
        assertEquals(YouTubePlayerErrorKind.EMBEDDING_DENIED_101_150, classifyYouTubeErrorCode(101))
        assertEquals(YouTubePlayerErrorKind.EMBEDDING_DENIED_101_150, classifyYouTubeErrorCode(150))
        assertEquals(YouTubePlayerErrorKind.INVALID_PARAM_2, classifyYouTubeErrorCode(2))
        assertEquals(YouTubePlayerErrorKind.HTML5_ERROR_5, classifyYouTubeErrorCode(5))
        assertEquals(YouTubePlayerErrorKind.VIDEO_NOT_FOUND_100, classifyYouTubeErrorCode(100))
        assertEquals(YouTubePlayerErrorKind.UNKNOWN, classifyYouTubeErrorCode(999))

        // Shared WebNavigationGuard: ad host blocked, temporarily allowed host permitted, intent:// blocked
        val adDecision = WebNavigationGuard.evaluateTopFrameNavigation(
            seedUrl = "https://cinema.example.org/watch/1",
            targetUrl = "https://ads.popads.net/popunder?id=1",
            hasUserGesture = true,
        )
        assertTrue(adDecision is RedirectDecision.Block)

        val tempAllowedDecision = WebNavigationGuard.evaluateTopFrameNavigation(
            seedUrl = "https://cinema.example.org/watch/1",
            targetUrl = "https://auth.partnercdn.net/embed/player",
            temporarilyAllowedHosts = setOf("auth.partnercdn.net"),
        )
        assertEquals(RedirectDecision.Allow, tempAllowedDecision)

        val externalSchemeDecision = WebNavigationGuard.evaluateTopFrameNavigation(
            seedUrl = "https://cinema.example.org/watch/1",
            targetUrl = "intent://launch#Intent;end",
            hasUserGesture = true,
        )
        assertTrue(externalSchemeDecision is RedirectDecision.Block)
    }
}
