package com.example

import com.example.data.model.ConfidenceTier
import com.example.data.model.DialogueType
import com.example.data.model.PlacementMode
import com.example.data.project.DemoScenarioFactory
import com.example.data.subtitle.SubtitleParser
import com.example.domain.attribution.SpeakerAttributionEngine
import com.example.domain.layout.BalloonLayoutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueLensEngineTest {

    @Test
    fun srtAndVttParsingExtractsTimingAndNarration() {
        val srt = """
            1
            00:00:01,250 --> 00:00:03,800
            雨が強くなってきたね。

            2
            00:00:04,100 --> 00:00:06,500
            [Narrator] The rain never stopped.
        """.trimIndent()

        val cues = SubtitleParser.parse(srt, "scene.srt")
        assertEquals(2, cues.size)
        assertEquals(1250L, cues[0].startTimeMs)
        assertEquals(3800L, cues[0].endTimeMs)
        assertEquals(DialogueType.SPOKEN, cues[0].inferredType)
        assertEquals(DialogueType.NARRATION, cues[1].inferredType)
    }

    @Test
    fun balloonLayoutAvoidsFacesAndFallsBackOnLowConfidence() {
        val project = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        // High confidence line (Speaker A in Shot 1)
        val shot1Balloons = engine.computeLayout(project, 1500L)
        assertEquals(1, shot1Balloons.size)
        val b1 = shot1Balloons.first()
        assertFalse(b1.isAmbiguousFallback)
        assertEquals(PlacementMode.AUTO, b1.placementMode)

        // Ensure placed balloon does not overlap Speaker A's face box
        val faceA = project.faceTracks.first { it.id == "ft_shot1_a" }.sampleAt(1500L)!!.boundingBox
        assertFalse(b1.rect.intersects(faceA))

        // Off-screen speaker in Shot 2
        val offScreenBalloons = engine.computeLayout(project, 12_500L)
        assertEquals(1, offScreenBalloons.size)
        assertTrue(offScreenBalloons.first().isOffScreenEdge)
        assertEquals(DialogueType.OFF_SCREEN_RIGHT, offScreenBalloons.first().edgeSide)

        // Low-confidence line in Shot 3 falls back gracefully instead of guessing wrong face
        val uncertainBalloons = engine.computeLayout(project, 15_500L)
        assertEquals(1, uncertainBalloons.size)
        assertTrue(uncertainBalloons.first().isAmbiguousFallback)
        assertEquals(PlacementMode.NEUTRAL_FALLBACK, uncertainBalloons.first().placementMode)
    }

    @Test
    fun userCorrectionLocksEventAndRefinesSubsequentUncertainLines() {
        val project = DemoScenarioFactory.createDemoProject()
        val attribution = SpeakerAttributionEngine()

        val beforeEvt5 = project.dialogueEvents.first { it.id == "demo_evt_5" }
        val beforeEvt6 = project.dialogueEvents.first { it.id == "demo_evt_6" }
        assertEquals(ConfidenceTier.LOW, beforeEvt5.confidenceTier)
        assertEquals(ConfidenceTier.LOW, beforeEvt6.confidenceTier)

        // User corrects demo_evt_5 to Speaker B (spk_b)
        val corrected = attribution.applyUserCorrection(
            project = project,
            eventId = "demo_evt_5",
            newSpeakerId = "spk_b",
        )

        val afterEvt5 = corrected.dialogueEvents.first { it.id == "demo_evt_5" }
        val afterEvt6 = corrected.dialogueEvents.first { it.id == "demo_evt_6" }

        assertEquals("spk_b", afterEvt5.speakerId)
        assertTrue(afterEvt5.lockedByUser)
        assertEquals(ConfidenceTier.HIGH, afterEvt5.confidenceTier)

        // Subsequent uncertain event demo_evt_6 should be refined to spk_b with high confidence!
        assertEquals("spk_b", afterEvt6.speakerId)
        assertEquals(ConfidenceTier.HIGH, afterEvt6.confidenceTier)
        assertNotNull(corrected.corrections.firstOrNull())
    }

    @Test
    fun tailTargetsSpeakerMouthAndBubbleScalesToTextLength() {
        val project = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        // Shot 1, Speaker A (facing right, headYawDeg > 0)
        val shot1Balloons = engine.computeLayout(project, 1500L)
        val balloon = shot1Balloons.first()
        val faceSample = project.faceTracks.first { it.id == "ft_shot1_a" }.sampleAt(1500L)!!
        val target = balloon.tailTarget!!

        // Mouth is ~70% down the face box (below eyes) and shifted toward facing direction
        val expectedMouthY = faceSample.boundingBox.top + faceSample.boundingBox.height * 0.70f
        assertEquals(expectedMouthY, target.y, 0.005f)
        assertTrue(target.x > faceSample.boundingBox.centerX)

        // Short line produces noticeably smaller bounding width than a long line
        val shortProject = project.copy(
            dialogueEvents = listOf(
                project.dialogueEvents.first().copy(
                    id = "short_evt",
                    translatedText = "Wait!",
                    sourceText = "待って！",
                )
            )
        )
        val longProject = project.copy(
            dialogueEvents = listOf(
                project.dialogueEvents.first().copy(
                    id = "long_evt",
                    translatedText = "Wait at the ticket gate until the express train leaves the station.",
                    sourceText = "急行電車が駅を出るまで改札口の前で待っていてください。",
                )
            )
        )

        val shortRect = BalloonLayoutEngine().computeLayout(shortProject, 1500L).first().rect
        val longRect = BalloonLayoutEngine().computeLayout(longProject, 1500L).first().rect
        assertTrue(shortRect.width < longRect.width)
        assertTrue(shortRect.width < 0.22f)
    }

    @Test
    fun balloonsClearSpeakerSilhouetteAndTorsoForVisibility() {
        val project = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        // Check Shot 1 (2 speakers) and Shot 2 (close-up)
        val shot1Balloons = engine.computeLayout(project, 1500L)
        val b1 = shot1Balloons.first().rect
        val faceA = project.faceTracks.first { it.id == "ft_shot1_a" }.sampleAt(1500L)!!.boundingBox
        val torsoA = com.example.data.model.NormalizedRect(
            left = (faceA.left - faceA.width * 0.42f).coerceIn(0f, 1f),
            top = faceA.bottom,
            right = (faceA.right + faceA.width * 0.42f).coerceIn(0f, 1f),
            bottom = (faceA.bottom + faceA.height * 1.45f).coerceIn(0f, 1f),
        )
        assertFalse(b1.intersects(faceA))
        assertFalse(b1.intersects(torsoA))

        val shot2Balloons = engine.computeLayout(project, 9500L)
        val b2 = shot2Balloons.first().rect
        val faceCloseup = project.faceTracks.first { it.id == "ft_shot2_a" }.sampleAt(9500L)!!.boundingBox
        assertFalse(b2.intersects(faceCloseup))
    }

    @Test
    fun conversationalContextAndAudioIdentifyOffScreenAndAddressedSpeakers() {
        val base = DemoScenarioFactory.createDemoProject()
        val engine = SpeakerAttributionEngine()

        // Two events in a shot with NO visible faces (off-screen) or neutral mouth cues:
        // Event 1: Kenji's pitch (~124Hz) asks a direct question
        // Event 2: Vocative "Kenji, don't move!" + Sora's higher pitch (~218Hz) -> must assign to Sora (spk_b)
        val evt1 = com.example.data.model.DialogueEvent(
            id = "ctx_1",
            startTimeMs = 1000L,
            endTimeMs = 3000L,
            sourceText = "健二「傘は持ってきた？」",
            translatedText = "Did you bring your umbrella?",
            speakerId = null,
            speakerConfidence = 0.5f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_off",
            audioFeatures = com.example.data.model.AudioVoiceFeatures(
                pitchHz = 124f,
                spectralBrightness = 0.36f,
                speechEnergy = 0.8f,
                voiceClusterId = "voice_1",
            ),
        )
        val evt2 = com.example.data.model.DialogueEvent(
            id = "ctx_2",
            startTimeMs = 3400L,
            endTimeMs = 5400L,
            sourceText = "健二、ちょっと待って！",
            translatedText = "Kenji, wait a second!",
            speakerId = null,
            speakerConfidence = 0.5f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_off",
            audioFeatures = com.example.data.model.AudioVoiceFeatures(
                pitchHz = 219f,
                spectralBrightness = 0.69f,
                speechEnergy = 0.82f,
                voiceClusterId = "voice_2",
            ),
        )

        val (_, assigned) = engine.assignSpeakers(
            events = listOf(evt1, evt2),
            shots = listOf(com.example.data.model.Shot("shot_off", 0L, 6000L)),
            faceTracks = emptyList(),
            existingSpeakers = base.speakers,
        )

        assertEquals("spk_a", assigned[0].speakerId)
        assertEquals("spk_b", assigned[1].speakerId)
        assertTrue(assigned[1].speakerConfidence >= 0.70f)
        assertTrue(assigned[1].attributionSummary.contains("219Hz"))
    }

    @Test
    fun balloonsAvoidShotFocalPropsAndOtsSilhouette() {
        val project = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        // Shot 1: Edison pendant lamp focal exclusion zone must remain clear
        val shot1 = project.shots.first { it.id == "shot_1" }
        assertTrue(shot1.focalExclusionZones.isNotEmpty())
        val evt1Rect = engine.computeLayout(project, 1500L).first().rect
        val evt2Rect = engine.computeLayout(project, 4800L).first().rect
        shot1.focalExclusionZones.forEach { lampZone ->
            assertFalse("Shot 1 Speaker A balloon overlapped lamp focal zone", evt1Rect.intersects(lampZone))
            assertFalse("Shot 1 Speaker B balloon overlapped lamp focal zone", evt2Rect.intersects(lampZone))
        }

        // Shot 2: Neon sign and Sora OTS silhouette must remain unobstructed
        val shot2 = project.shots.first { it.id == "shot_2" }
        assertTrue(shot2.focalExclusionZones.size >= 2)
        val closeupBalloon = engine.computeLayout(project, 9500L).first().rect
        val offScreenBalloon = engine.computeLayout(project, 12_500L).first()
        shot2.focalExclusionZones.forEach { focal ->
            assertFalse("Shot 2 close-up overlapped focal prop/OTS", closeupBalloon.intersects(focal))
            assertFalse("Shot 2 off-screen balloon overlapped focal prop/OTS", offScreenBalloon.rect.intersects(focal))
        }
        assertTrue(offScreenBalloon.isOffScreenEdge)
        assertTrue(offScreenBalloon.rect.top < 0.35f)
    }

    @Test
    fun audioAnalysisDetectsShoutedDialogueAndSurfacesOnPlacedBalloon() {
        val calm = com.example.data.model.AudioVoiceFeatures(
            pitchHz = 132f,
            spectralBrightness = 0.38f,
            speechEnergy = 0.36f,
            peakAmplitude = 0.44f,
        )
        val normal = com.example.data.model.AudioVoiceFeatures(
            pitchHz = 210f,
            spectralBrightness = 0.50f,
            speechEnergy = 0.48f,
            peakAmplitude = 0.56f,
        )
        val shout = com.example.data.model.AudioVoiceFeatures(
            pitchHz = 264f,
            spectralBrightness = 0.84f,
            speechEnergy = 0.89f,
            peakAmplitude = 0.95f,
        )

        val analyzed = com.example.domain.analysis.AudioAnalyzer.detectVocalEmphasis(
            mapOf("calm" to calm, "normal" to normal, "shout" to shout)
        )

        assertFalse(analyzed["calm"]!!.isShouted)
        assertFalse(analyzed["normal"]!!.isShouted)
        assertTrue(analyzed["shout"]!!.isShouted)
        assertTrue(analyzed["shout"]!!.emphasisScore >= 0.66f)

        // Demo project urgent shouted line (demo_evt_4 at 12_500ms) surfaces isShouted on PlacedBalloon
        val demo = DemoScenarioFactory.createDemoProject()
        val shoutBalloon = BalloonLayoutEngine().computeLayout(demo, 12_500L).first()
        assertTrue(shoutBalloon.isShouted)
        assertTrue(shoutBalloon.emphasisScore >= 0.66f)
    }

    @Test
    fun namedMainCharactersEstablishColorOnFirstSpeakAndRememberAcrossScenes() {
        val engine = SpeakerAttributionEngine()
        val speakersEp1 = listOf(
            com.example.data.model.Speaker(id = "spk_a", defaultLabel = "Speaker A", customName = "Detective", accentIndex = 0),
            com.example.data.model.Speaker(id = "spk_b", defaultLabel = "Speaker B", customName = "Haru", accentIndex = 0), // intentional collision on default 0
        )
        val eventsEp1 = listOf(
            com.example.data.model.DialogueEvent(
                id = "e1", startTimeMs = 1000L, endTimeMs = 2500L,
                sourceText = "Who is there?", translatedText = "Who is there?",
                speakerId = "spk_a", speakerConfidence = 0.9f, faceTrackId = null, faceConfidence = 0f, shotId = "s1",
            ),
            com.example.data.model.DialogueEvent(
                id = "e2", startTimeMs = 3000L, endTimeMs = 4500L,
                sourceText = "It's me!", translatedText = "It's me!",
                speakerId = "spk_b", speakerConfidence = 0.9f, faceTrackId = null, faceConfidence = 0f, shotId = "s1",
            ),
        )

        val (reconciledEp1, memory) = engine.reconcileCharacterColors(speakersEp1, eventsEp1, emptyMap())
        val detectiveColor = reconciledEp1.first { it.id == "spk_a" }.accentIndex
        val haruColor = reconciledEp1.first { it.id == "spk_b" }.accentIndex
        assertTrue(detectiveColor != haruColor)
        assertEquals(detectiveColor, memory["detective"])
        assertEquals(haruColor, memory["haru"])

        // Episode 2: Haru appears alone as Speaker A slot — must remember Haru's original color!
        val speakersEp2 = listOf(
            com.example.data.model.Speaker(id = "spk_a", defaultLabel = "Speaker A", customName = "Haru", accentIndex = 0),
        )
        val (reconciledEp2, _) = engine.reconcileCharacterColors(speakersEp2, emptyList(), memory)
        assertEquals(haruColor, reconciledEp2.first().accentIndex)
        assertTrue(reconciledEp2.first().colorRemembered)
    }

    @Test
    fun parsesProvidedYouTubeTestLinkCleanly() {
        val id = com.example.ui.setup.extractYouTubeId("https://youtu.be/3AFJtcP2cp0?is=Fd7HSQvVvb3qyMVC")
        assertEquals("3AFJtcP2cp0", id)
    }

    @Test
    fun smartTranscriptParserHandlesPlainLinesAndStructuredSrt() {
        val plainTranscript = """
            Kenji: Wait at the gate.
            [footsteps echo]
            I will be right back!
        """.trimIndent()

        val cues = SubtitleParser.parseSmartTranscript(plainTranscript, fallbackTotalMs = 9000L)
        assertEquals(3, cues.size)
        assertTrue(cues.first().startTimeMs >= 0L)
        assertEquals(DialogueType.SOUND_DESCRIPTION, cues[1].inferredType)

        val srt = """
            1
            00:00:01,000 --> 00:00:02,500
            Hello there
        """.trimIndent()
        val srtCues = SubtitleParser.parseSmartTranscript(srt, fallbackTotalMs = 10_000L)
        assertEquals(1, srtCues.size)
        assertEquals(1000L, srtCues.first().startTimeMs)
        assertEquals(2500L, srtCues.first().endTimeMs)
    }

    @Test
    fun exportAssIncludesSpeakerStylesSpatialPosShoutEmphasisAndBottomSfx() {
        val demo = DemoScenarioFactory.createDemoProject()
        val ass = SubtitleParser.exportAss(demo)

        assertTrue(ass.contains("[V4+ Styles]"))
        assertTrue(ass.contains("Style: Speaker_spk_a"))
        assertTrue(ass.contains("\\pos("))
        // Demo shout event uses \b1 and thicker \bord
        assertTrue(ass.contains("\\b1\\bord4.2"))
        // Demo SFX cue is bottom-aligned \an2
        assertTrue(ass.contains("{\\an2\\i1}"))
    }

    @Test
    fun readingPacingGuardrailsExtendMicroCuesWithoutCollidingAndComputeCps() {
        val e1 = com.example.data.model.DialogueEvent(
            id = "e1",
            startTimeMs = 1000L,
            endTimeMs = 1400L, // only 400ms
            sourceText = "待って",
            translatedText = "Hold on right there!",
            speakerId = null,
            speakerConfidence = 0.5f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "s1",
        )
        val e2 = com.example.data.model.DialogueEvent(
            id = "e2",
            startTimeMs = 3000L,
            endTimeMs = 5000L,
            sourceText = "わかった",
            translatedText = "Okay.",
            speakerId = null,
            speakerConfidence = 0.5f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "s1",
        )

        val paced = SubtitleParser.applyReadingPacingGuardrails(listOf(e1, e2))
        assertEquals(2, paced.size)
        assertTrue((paced[0].endTimeMs - paced[0].startTimeMs) >= 1050L)
        assertTrue(paced[0].endTimeMs <= paced[1].startTimeMs - 60L)

        val fastEvent = com.example.data.model.DialogueEvent(
            id = "cps_test",
            startTimeMs = 1000L,
            endTimeMs = 1500L,
            sourceText = "テスト",
            translatedText = "This sentence is way too fast to read!",
            speakerId = null,
            speakerConfidence = 0.5f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "s1",
        )
        assertTrue(fastEvent.charactersPerSecond > 22f)
    }

    @Test
    fun sceneLuminancePropagatesAndTailTargetSmoothingDampsJitter() {
        val base = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        // Bright scene in shot 1
        val brightProject = base.copy(
            shots = base.shots.map { if (it.id == "shot_1") it.copy(sceneLuminance = 0.88f) else it }
        )
        val frame1 = engine.computeLayout(brightProject, 1500L)
        assertEquals(0.88f, frame1.first().sceneLuminance, 0.01f)

        // Slightly shift face box center in next frame and confirm tail target moves smoothly (damped)
        val originalTarget = frame1.first().tailTarget!!
        val shiftedTracks = brightProject.faceTracks.map { track ->
            if (track.id == "ft_shot1_a") {
                track.copy(
                    samples = track.samples.map { s ->
                        s.copy(
                            boundingBox = s.boundingBox.copy(
                                left = (s.boundingBox.left + 0.08f).coerceAtMost(0.8f),
                                right = (s.boundingBox.right + 0.08f).coerceAtMost(0.95f),
                            )
                        )
                    }
                )
            } else track
        }
        val frame2 = engine.computeLayout(brightProject.copy(faceTracks = shiftedTracks), 1600L)
        val dampedTarget = frame2.first().tailTarget!!
        // Should move partway toward new mouth (+0.08f * 0.45f = +0.036f), not jump the full +0.08f
        val deltaX = dampedTarget.x - originalTarget.x
        assertTrue(deltaX > 0.01f && deltaX < 0.06f)
    }

    @Test
    fun fallbackAndSpecialDialogueModesNeverOverlapVisibleFaces() {
        val base = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        // Shot 3 uncertain lines (demo_evt_5 & demo_evt_6 use NEUTRAL_FALLBACK) + both speakers visible
        val shot3Balloons = engine.computeLayout(base, 15_200L)
        assertTrue(shot3Balloons.isNotEmpty())
        val faceA = base.faceTracks.first { it.id == "ft_shot3_a" }.sampleAt(15_200L)!!.boundingBox
        val faceB = base.faceTracks.first { it.id == "ft_shot3_b" }.sampleAt(15_200L)!!.boundingBox
        shot3Balloons.forEach { placed ->
            assertFalse("Fallback balloon overlapped Speaker A face", placed.rect.intersects(faceA))
            assertFalse("Fallback balloon overlapped Speaker B face", placed.rect.intersects(faceB))
        }
    }

    @Test
    fun microFaceTrackingJitterDoesNotWigglePlacedBalloonCenter() {
        val base = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        val frame1 = engine.computeLayout(base, 1500L).first().rect
        // Perturb face box by ~1.2% (typical frame-to-frame ML Kit jitter)
        val microJitterTracks = base.faceTracks.map { track ->
            if (track.id == "ft_shot1_a") {
                track.copy(
                    samples = track.samples.map { s ->
                        s.copy(
                            boundingBox = s.boundingBox.copy(
                                left = s.boundingBox.left + 0.012f,
                                right = s.boundingBox.right + 0.012f,
                                top = s.boundingBox.top + 0.008f,
                                bottom = s.boundingBox.bottom + 0.008f,
                            )
                        )
                    }
                )
            } else track
        }
        val frame2 = engine.computeLayout(base.copy(faceTracks = microJitterTracks), 1600L).first().rect
        assertEquals(frame1.centerX, frame2.centerX, 0.0005f)
        assertEquals(frame1.centerY, frame2.centerY, 0.0005f)
    }

    @Test
    fun lipReadingSafeZoneProtectsMouthAndChinZone() {
        val base = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        // 1. Existing demo close-up (Shot 2, ft_shot2_a at 9500ms) with lipReadingSafeZone = true
        val closeupProject = base.copy(
            styleSettings = base.styleSettings.copy(lipReadingSafeZone = true)
        )
        val closeupBalloon = engine.computeLayout(closeupProject, 9500L).first().rect
        val closeupFace = closeupProject.faceTracks.first { it.id == "ft_shot2_a" }.sampleAt(9500L)!!.boundingBox
        val closeupLipCorridor = com.example.data.model.NormalizedRect(
            left = (closeupFace.left - 0.055f).coerceIn(0f, 1f),
            top = (closeupFace.top + closeupFace.height * 0.45f).coerceIn(0f, 1f),
            right = (closeupFace.right + 0.055f).coerceIn(0f, 1f),
            bottom = (closeupFace.bottom + closeupFace.height * 0.65f).coerceAtMost(1f),
        )
        assertFalse("Close-up balloon overlapped lip/chin corridor", closeupBalloon.intersects(closeupLipCorridor))

        // 2. Tight high-frame close-up where speaker's head touches top border (forcing side-air instead of below chin)
        val highFaceTracks = closeupProject.faceTracks.map { track ->
            if (track.id == "ft_shot2_a") {
                track.copy(
                    samples = track.samples.map { s ->
                        s.copy(
                            boundingBox = com.example.data.model.NormalizedRect(
                                left = 0.34f,
                                top = 0.06f,
                                right = 0.66f,
                                bottom = 0.56f,
                            )
                        )
                    }
                )
            } else track
        }
        val constrainedBalloon = BalloonLayoutEngine()
            .computeLayout(closeupProject.copy(faceTracks = highFaceTracks), 9500L)
            .first()
            .rect
        val constrainedLipCorridor = com.example.data.model.NormalizedRect(
            left = 0.285f,
            top = 0.285f,
            right = 0.715f,
            bottom = 0.885f,
        )
        assertFalse("Constrained close-up fell into chin/lip safe zone", constrainedBalloon.intersects(constrainedLipCorridor))
    }

    @Test
    fun sfxSubtitlesMoveAwayFromSketchBubblesAndSpeakerFaces() {
        val base = DemoScenarioFactory.createDemoProject()
        val engine = BalloonLayoutEngine()

        // Create a scene where:
        // 1) A spoken sketch bubble is manually anchored right at bottom-center (0.50f, 0.87f)
        // 2) An SFX note ("[rain patters loudly]") starts BEFORE the spoken line so chronological order tests bubble-first placement
        // 3) A speaker face occupies bottom-left/lower-center area too
        val bottomBubble = com.example.data.model.DialogueEvent(
            id = "bottom_sketch_bubble",
            startTimeMs = 1500L,
            endTimeMs = 3500L,
            sourceText = "下にある吹き出しです。",
            translatedText = "I am a sketch bubble at the bottom center.",
            speakerId = "spk_a",
            speakerConfidence = 0.95f,
            faceTrackId = "ft_shot1_a",
            faceConfidence = 0.95f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.MANUAL_OVERRIDE,
            anchorPosition = com.example.data.model.NormalizedPoint(0.50f, 0.87f),
        )
        val earlierSfx = com.example.data.model.DialogueEvent(
            id = "sfx_blocking_test",
            startTimeMs = 1000L,
            endTimeMs = 3500L,
            sourceText = "[雷鳴が轟く]",
            translatedText = "[thunder rumbles overhead]",
            speakerId = null,
            speakerConfidence = 0.95f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            dialogueType = DialogueType.SOUND_DESCRIPTION,
            placementMode = PlacementMode.EDGE_DOCKED,
        )

        val testProject = base.copy(
            dialogueEvents = listOf(earlierSfx, bottomBubble),
        )

        val placed = engine.computeLayout(testProject, 2000L)
        assertEquals(2, placed.size)
        val bubbleRect = placed.first { it.event.id == "bottom_sketch_bubble" }.rect
        val sfxRect = placed.first { it.event.id == "sfx_blocking_test" }.rect

        assertFalse("SFX note overlapped sketch bubble!", sfxRect.intersects(bubbleRect))

        val faceA = testProject.faceTracks.first { it.id == "ft_shot1_a" }.sampleAt(2000L)!!.boundingBox
        val faceB = testProject.faceTracks.first { it.id == "ft_shot1_b" }.sampleAt(2000L)!!.boundingBox
        assertFalse("SFX note overlapped Speaker A face!", sfxRect.intersects(faceA))
        assertFalse("SFX note overlapped Speaker B face!", sfxRect.intersects(faceB))
    }

    @Test
    fun extractsSharedStreamUrlsAndParsesCapturedHtml5TextTrackJson() {
        val chromeShare = "Check out this scene https://vimeo.com/123456789?autoplay=1!"
        val extractedWeb = com.example.ui.state.extractFirstHttpUrl(chromeShare)
        assertEquals("https://vimeo.com/123456789?autoplay=1!", extractedWeb)

        val ytShare = "Great clip: https://youtu.be/3AFJtcP2cp0?si=abc"
        val extractedYt = com.example.ui.state.extractFirstHttpUrl(ytShare)
        assertEquals("https://youtu.be/3AFJtcP2cp0?si=abc", extractedYt)
        assertEquals("3AFJtcP2cp0", com.example.ui.setup.extractYouTubeId(extractedYt!!))
        assertEquals("https://archive.org/details/sample", com.example.ui.setup.normalizeWebStreamUrl("archive.org/details/sample"))

        val json = """
            [
              {"startMs": 1000, "endMs": 2800, "text": "Stay right behind me."},
              {"startMs": 3000, "endMs": 4500, "text": "[heavy rain pouring outside]"}
            ]
        """.trimIndent()
        val parsed = com.example.ui.state.parseCapturedWebTrackJson(json)
        assertEquals(2, parsed.size)
        assertEquals(DialogueType.SPOKEN, parsed[0].dialogueType)
        assertEquals("Stay right behind me.", parsed[0].translatedText)
        assertEquals(DialogueType.SOUND_DESCRIPTION, parsed[1].dialogueType)
        assertEquals(PlacementMode.EDGE_DOCKED, parsed[1].placementMode)
    }

    @Test
    fun parsesYouTubeTimedTextXmlAndSelectsBestCaptionTrack() {
        val sampleXml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <transcript>
                <text start="1.25" dur="2.5">It&#39;s really happening &amp; we&quot;re live!</text>
                <text start="4.10" dur="1.8">[laughter and applause]</text>
                <text start="6.50" dur="2.0">♪ gentle piano intro ♪</text>
            </transcript>
        """.trimIndent()
        val cues = com.example.data.subtitle.YouTubeCaptionFetcher.parseTimedTextXml(sampleXml)
        assertEquals(3, cues.size)
        assertEquals(1250L, cues[0].startTimeMs)
        assertEquals(3750L, cues[0].endTimeMs)
        assertEquals("It's really happening & we\"re live!", cues[0].text)
        assertEquals(DialogueType.SPOKEN, cues[0].inferredType)
        assertEquals(DialogueType.SOUND_DESCRIPTION, cues[1].inferredType)
        assertEquals(DialogueType.SOUND_DESCRIPTION, cues[2].inferredType)

        val tracks = listOf(
            com.example.data.subtitle.YouTubeCaptionFetcher.CaptionTrackMeta("https://example.com/en-asr", "en", kind = "asr"),
            com.example.data.subtitle.YouTubeCaptionFetcher.CaptionTrackMeta("https://example.com/ja", "ja", kind = ""),
            com.example.data.subtitle.YouTubeCaptionFetcher.CaptionTrackMeta("https://example.com/en-manual", "en-US", kind = ""),
        )
        val pickedEn = com.example.data.subtitle.YouTubeCaptionFetcher.selectBestCaptionTrack(
            tracks = tracks,
            preferredSourceLang = "Auto-Detect",
            preferredTargetLang = "English",
        )
        assertEquals("https://example.com/en-manual", pickedEn?.baseUrl)

        val pickedJa = com.example.data.subtitle.YouTubeCaptionFetcher.selectBestCaptionTrack(
            tracks = tracks,
            preferredSourceLang = "Japanese",
            preferredTargetLang = "English",
        )
        assertEquals("https://example.com/ja", pickedJa?.baseUrl)
    }

    @Test
    fun schedulesSevenSecondLookaheadWindowsAndKeepsScoutAheadOfViewer() {
        val planner = com.example.domain.analysis.LookaheadWindowPlanner
        // 1. During 7s intro, Viewer is held at 0 while Scout plays forward to 7s
        val introCmd = planner.computeScoutSyncCommand(
            viewerPosMs = 0L,
            scoutPosMs = 1_200L,
            durationMs = 90_000L,
            isViewerPlaying = false,
            isScoutPlaying = false,
            introRemainingMs = 5_500L,
        )
        assertEquals(com.example.domain.analysis.ScoutSyncCommand.PlayForward, introCmd)

        // Once Scout finishes its initial 7s intro lead while intro is still ending, Scout pauses cleanly
        val introLeadReached = planner.computeScoutSyncCommand(
            viewerPosMs = 0L,
            scoutPosMs = 7_100L,
            durationMs = 90_000L,
            isViewerPlaying = false,
            isScoutPlaying = true,
            introRemainingMs = 500L,
        )
        assertEquals(com.example.domain.analysis.ScoutSyncCommand.PauseWithLead, introLeadReached)

        // 2. If Viewer jumps to 40s, Scout seeks immediately to 47s (+7s ahead) and prioritizes [35s..42s]/[42s..49s]
        val seekCmd = planner.computeScoutSyncCommand(
            viewerPosMs = 40_000L,
            scoutPosMs = 12_000L,
            durationMs = 90_000L,
            isViewerPlaying = true,
            isScoutPlaying = true,
            introRemainingMs = 0L,
        )
        assertEquals(com.example.domain.analysis.ScoutSyncCommand.SeekAndPlay(47_000L), seekCmd)

        val nextWin = planner.nextPriorityWindow(
            viewerPosMs = 40_000L,
            durationMs = 90_000L,
            completedWindowStarts = setOf(0L, 7_000L),
        )
        assertEquals(35_000L, nextWin?.startMs)
        assertEquals(42_000L, nextWin?.endMs)

        // 3. Merging a newly transcribed 7s window preserves user-locked lines and deduplicates boundaries
        val lockedEvt = com.example.data.model.DialogueEvent(
            id = "locked_1",
            startTimeMs = 7_200L,
            endTimeMs = 9_100L,
            sourceText = "User edited line",
            translatedText = "User edited line",
            speakerId = "spk_1",
            speakerConfidence = 1.0f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            lockedByUser = true,
        )
        val scoutLine = com.example.data.model.DialogueEvent(
            id = "scout_1",
            startTimeMs = 11_000L,
            endTimeMs = 13_400L,
            sourceText = "Who said that?",
            translatedText = "Who said that?",
            speakerId = "spk_2",
            speakerConfidence = 0.85f,
            faceTrackId = "face_2",
            faceConfidence = 0.8f,
            shotId = "shot_1",
        )
        val merged = planner.mergeWindowDialogueEvents(
            existing = listOf(lockedEvt),
            incoming = listOf(
                lockedEvt.copy(id = "dup_near_locked", startTimeMs = 7_350L, lockedByUser = false),
                scoutLine,
            ),
            windowStartMs = 7_000L,
            windowEndMs = 14_000L,
        )
        assertEquals(2, merged.size)
        assertEquals("locked_1", merged[0].id)
        assertEquals("scout_1", merged[1].id)
    }

    @Test
    fun parsesYouTubeJson3Srv3AndSrv1CaptionPayloads() {
        val json3 = """
            {
              "events": [
                { "tStartMs": 1200, "dDurationMs": 2600, "segs": [ { "utf8": "Hello " }, { "utf8": "world!" } ] },
                { "tStartMs": 4200, "dDurationMs": 1800, "segs": [ { "utf8": "[Applause]" } ] }
              ]
            }
        """.trimIndent()
        val parsedJson = com.example.data.subtitle.YouTubeCaptionFetcher.parseCaptionPayload(json3)
        assertEquals(2, parsedJson.size)
        assertEquals("Hello world!", parsedJson[0].text)
        assertEquals(1200L, parsedJson[0].startTimeMs)
        assertEquals(DialogueType.SOUND_DESCRIPTION, parsedJson[1].inferredType)

        val srv3 = """
            <?xml version="1.0" encoding="utf-8" ?>
            <timedtext format="3">
              <body>
                <p t="2400" d="3100"><s>First </s><s>sentence</s></p>
                <p t="6000" d="1500">[Music]</p>
              </body>
            </timedtext>
        """.trimIndent()
        val parsedSrv3 = com.example.data.subtitle.YouTubeCaptionFetcher.parseCaptionPayload(srv3)
        assertEquals(2, parsedSrv3.size)
        assertEquals("First sentence", parsedSrv3[0].text)
        assertEquals(2400L, parsedSrv3[0].startTimeMs)
        assertEquals(5500L, parsedSrv3[0].endTimeMs)
        assertEquals(DialogueType.SOUND_DESCRIPTION, parsedSrv3[1].inferredType)

        val srv1 = """
            <transcript>
              <text start="1.5" dur="2.0">Classic format &amp; check</text>
            </transcript>
        """.trimIndent()
        val parsedSrv1 = com.example.data.subtitle.YouTubeCaptionFetcher.parseCaptionPayload(srv1)
        assertEquals(1, parsedSrv1.size)
        assertEquals("Classic format & check", parsedSrv1[0].text)
        assertEquals(1500L, parsedSrv1[0].startTimeMs)
    }

    @Test
    fun youtubeDialogueWithoutFaceTracksRendersStyledAlternatingSpeakerBalloons() {
        val base = DemoScenarioFactory.createDemoProject()
        val attribution = SpeakerAttributionEngine()
        val engine = BalloonLayoutEngine()

        val e1 = com.example.data.model.DialogueEvent(
            id = "yt_1",
            startTimeMs = 1000L,
            endTimeMs = 3000L,
            sourceText = "What changed your mind?",
            translatedText = "What changed your mind?",
            speakerId = null,
            speakerConfidence = 0.45f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
        )
        val e2 = com.example.data.model.DialogueEvent(
            id = "yt_2",
            startTimeMs = 3200L,
            endTimeMs = 5400L,
            sourceText = ">> Honestly it was the healthcare bill.",
            translatedText = ">> Honestly it was the healthcare bill.",
            speakerId = null,
            speakerConfidence = 0.45f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
        )

        val (speakers, assigned) = attribution.assignSpeakers(
            events = listOf(e1, e2),
            shots = listOf(com.example.data.model.Shot("shot_1", 0L, 10_000L)),
            faceTracks = emptyList(),
            existingSpeakers = base.speakers,
        )

        assertEquals(DialogueType.SPOKEN, assigned[0].dialogueType)
        assertEquals(DialogueType.SPOKEN, assigned[1].dialogueType)
        assertTrue(assigned[0].confidenceTier != ConfidenceTier.LOW)
        assertTrue(assigned[1].confidenceTier != ConfidenceTier.LOW)
        assertTrue(assigned[0].speakerId != assigned[1].speakerId)

        val ytProject = base.copy(
            faceTracks = emptyList(),
            speakers = speakers,
            dialogueEvents = assigned,
        )
        val b1 = engine.computeLayout(ytProject, 1500L).first()
        val b2 = engine.computeLayout(ytProject, 4000L).first()
        assertFalse(b1.isAmbiguousFallback)
        assertFalse(b2.isAmbiguousFallback)
        assertTrue(kotlin.math.abs(b1.rect.centerX - b2.rect.centerX) > 0.20f)
    }

    @Test
    fun smartAdaptiveBufferReleasesEarlyAtFiveSecondsWhenReadyAndHoldsUpToSevenWhenBuffering() {
        val planner = com.example.domain.analysis.LookaheadWindowPlanner
        val base = DemoScenarioFactory.createDemoProject()

        // 1. Placeholder-only YouTube cue counts as NOT having real subtitles
        val placeholderProj = base.copy(
            dialogueEvents = listOf(
                base.dialogueEvents.first().copy(
                    id = "placeholder_1",
                    startTimeMs = 1_000L,
                    endTimeMs = 5_000L,
                    sourceText = "Embedded YouTube Player active — attach SRT/VTT or tap + Line in Timeline",
                )
            )
        )
        assertFalse(planner.hasRealSubtitles(placeholderProj.dialogueEvents))
        assertFalse(planner.isOpeningWindowReady(placeholderProj, setOf("placeholder_1")))

        // 2. Real opening cues: check readiness across 0..12s (locked OR refined IDs)
        val realOpeningProj = base.copy(
            dialogueEvents = listOf(
                base.dialogueEvents[0].copy(id = "open_1", startTimeMs = 1_000L, endTimeMs = 3_500L, lockedByUser = false),
                base.dialogueEvents[1].copy(id = "open_2", startTimeMs = 4_000L, endTimeMs = 8_000L, lockedByUser = true),
                base.dialogueEvents[2].copy(id = "later_1", startTimeMs = 24_000L, endTimeMs = 28_000L, lockedByUser = false),
            )
        )
        assertTrue(planner.hasRealSubtitles(realOpeningProj.dialogueEvents))
        assertFalse(planner.isOpeningWindowReady(realOpeningProj, emptySet()))
        assertTrue(planner.isOpeningWindowReady(realOpeningProj, setOf("open_1")))

        // 3. Minimum 3.0s Smart Pre-Roll hold even when already ready at 2.9s
        assertFalse(
            planner.shouldAutoReleaseAdaptiveIntro(
                elapsedMs = 2_900L,
                scoutPosMs = 6_500L,
                hasRealSubtitles = true,
                openingWindowRefined = true,
            )
        )

        // 4. Releases right at 3.0s once opening cues + Scout lead are ready
        assertTrue(
            planner.shouldAutoReleaseAdaptiveIntro(
                elapsedMs = 3_000L,
                scoutPosMs = 5_100L,
                hasRealSubtitles = true,
                openingWindowRefined = true,
            )
        )

        // 5. Holds between 3.0s and 17.9s while still waiting on opening attribution or Scout lead,
        //    then always releases at 18.0s Smart Pre-Roll ceiling
        assertFalse(
            planner.shouldAutoReleaseAdaptiveIntro(
                elapsedMs = 10_200L,
                scoutPosMs = 5_800L,
                hasRealSubtitles = true,
                openingWindowRefined = false,
            )
        )
        assertTrue(
            planner.shouldAutoReleaseAdaptiveIntro(
                elapsedMs = 18_000L,
                scoutPosMs = 2_000L,
                hasRealSubtitles = false,
                openingWindowRefined = false,
            )
        )

        // 6. Live AI hint suggesting a 3rd speaker ("C") dynamically adds Speaker C while preserving locked lines
        val attribution = SpeakerAttributionEngine()
        val lockedLine = realOpeningProj.dialogueEvents[0].copy(
            id = "locked_open",
            speakerId = "spk_a",
            lockedByUser = true,
        )
        val thirdHostLine = com.example.data.model.DialogueEvent(
            id = "host_c",
            startTimeMs = 4_000L,
            endTimeMs = 6_800L,
            sourceText = "Actually, let me weigh in on that.",
            translatedText = "Actually, let me weigh in on that.",
            speakerId = null,
            speakerConfidence = 0.48f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            lockedByUser = false,
        )
        val hints = mapOf(
            "host_c" to com.example.data.ai.AiTranslatedLine(
                id = "host_c",
                naturalTranslation = thirdHostLine.translatedText,
                literalTranslation = "",
                romanization = "",
                dialogueTypeHint = DialogueType.SPOKEN,
                speakerTurnHint = "ALTERNATE_SPEAKER",
                suggestedSpeakerSlot = "C",
            )
        )
        val (expandedSpeakers, refined) = attribution.assignSpeakers(
            events = listOf(lockedLine, thirdHostLine),
            shots = realOpeningProj.shots,
            faceTracks = emptyList(),
            existingSpeakers = realOpeningProj.speakers.take(2),
            turnHints = hints,
        )
        assertTrue(expandedSpeakers.any { it.id == "spk_c" })
        assertEquals("spk_a", refined.first { it.id == "locked_open" }.speakerId)
        assertEquals("spk_c", refined.first { it.id == "host_c" }.speakerId)
    }

    @Test
    fun balloonLayoutTracksPerSpeakerLineOrdinalsAndSkipsSoundDescriptions() {
        val base = DemoScenarioFactory.createDemoProject()
        val sfx = com.example.data.model.DialogueEvent(
            id = "ord_sfx",
            startTimeMs = 500L,
            endTimeMs = 20_000L,
            sourceText = "[traffic hum]",
            translatedText = "[traffic hum]",
            speakerId = "spk_a",
            speakerConfidence = 0.9f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            dialogueType = DialogueType.SOUND_DESCRIPTION,
            placementMode = PlacementMode.EDGE_DOCKED,
        )
        val spokenEvents = (1..11).map { idx ->
            com.example.data.model.DialogueEvent(
                id = "ord_a_$idx",
                startTimeMs = idx * 1_000L,
                endTimeMs = idx * 1_000L + 900L,
                sourceText = "Line $idx",
                translatedText = "Line $idx",
                speakerId = "spk_a",
                speakerConfidence = 0.95f,
                faceTrackId = "ft_shot1_a",
                faceConfidence = 0.95f,
                shotId = "shot_1",
                dialogueType = DialogueType.SPOKEN,
            )
        }
        val otherSpeakerLine = com.example.data.model.DialogueEvent(
            id = "ord_b_1",
            startTimeMs = 11_100L,
            endTimeMs = 11_900L,
            sourceText = "First reply",
            translatedText = "First reply",
            speakerId = "spk_b",
            speakerConfidence = 0.95f,
            faceTrackId = "ft_shot1_b",
            faceConfidence = 0.95f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
        )

        val project = base.copy(dialogueEvents = listOf(sfx) + spokenEvents + otherSpeakerLine)
        val engine = BalloonLayoutEngine()

        // Line 1 of Speaker A -> ordinal 1 (SFX skipped), inside 1..10
        val atLine1 = engine.computeLayout(project, 1_200L)
        assertEquals(1, atLine1.first { it.event.id == "ord_a_1" }.speakerLineOrdinal)
        assertEquals(0, atLine1.first { it.event.id == "ord_sfx" }.speakerLineOrdinal)

        // Line 10 of Speaker A -> ordinal 10
        val atLine10 = engine.computeLayout(project, 10_200L)
        assertEquals(10, atLine10.first { it.event.id == "ord_a_10" }.speakerLineOrdinal)

        // Line 11 of Speaker A -> ordinal 11 (> 10, hides label in AUTO_FIRST_10), while Speaker B's 1st line -> ordinal 1
        val atLine11 = engine.computeLayout(project, 11_200L)
        assertEquals(11, atLine11.first { it.event.id == "ord_a_11" }.speakerLineOrdinal)
        assertEquals(1, atLine11.first { it.event.id == "ord_b_1" }.speakerLineOrdinal)
    }

    @Test
    fun lookaheadPlannerResetsScoutOnLargeOvershootDuringIntroAndPausedStart() {
        val planner = com.example.domain.analysis.LookaheadWindowPlanner

        // Intro overshoot (+2592.2s bug) -> immediate SeekAndPlay(0L)
        val introOvershoot = planner.computeScoutSyncCommand(
            viewerPosMs = 0L,
            scoutPosMs = 2_592_200L,
            durationMs = 3_878_000L,
            isViewerPlaying = false,
            isScoutPlaying = true,
            introRemainingMs = 5_000L,
        )
        assertEquals(com.example.domain.analysis.ScoutSyncCommand.SeekAndPlay(0L), introOvershoot)

        // Paused at 0L overshoot -> immediate SeekAndPlay(0L)
        val pausedOvershoot = planner.computeScoutSyncCommand(
            viewerPosMs = 0L,
            scoutPosMs = 2_592_200L,
            durationMs = 3_878_000L,
            isViewerPlaying = false,
            isScoutPlaying = false,
            introRemainingMs = 0L,
        )
        assertEquals(com.example.domain.analysis.ScoutSyncCommand.SeekAndPlay(0L), pausedOvershoot)
    }

    @Test
    fun liveCastMergeUpgradesMovieCreditRolePlaceholderAndRespectsLockedSpeaker() {
        val base = DemoScenarioFactory.createDemoProject()
        val projWithRoleAndLock = base.copy(
            speakers = listOf(
                com.example.data.model.Speaker(
                    id = "spk_a",
                    defaultLabel = "Speaker A",
                    customName = "Civilian Woman 1",
                    accentIndex = 0,
                    isRolePlaceholder = true,
                    isLocked = false,
                ),
                com.example.data.model.Speaker(
                    id = "spk_b",
                    defaultLabel = "Speaker B",
                    customName = "David",
                    accentIndex = 1,
                    isRolePlaceholder = false,
                    isLocked = true,
                ),
            )
        )

        val assignments = listOf(
            com.example.data.ai.AiCastSlotAssignment(
                slot = "A",
                displayName = "Maya",
                isRolePlaceholder = false,
                isVisuallyConfirmed = true,
            ),
            com.example.data.ai.AiCastSlotAssignment(
                slot = "B",
                displayName = "Hot Dog Vendor",
                isRolePlaceholder = true,
                isVisuallyConfirmed = true,
            ),
        )

        val (merged, _) = com.example.ui.state.mergeCastSlotAssignments(
            project = projWithRoleAndLock,
            assignments = assignments,
            rememberedColors = emptyMap(),
            summary = "Identified Maya and David from scene dialogue",
            groundingSources = listOf("YouTube Video Metadata"),
        )

        val spkA = merged.speakers.first { it.id == "spk_a" }
        val spkB = merged.speakers.first { it.id == "spk_b" }
        assertEquals("Maya", spkA.displayName)
        assertFalse(spkA.isRolePlaceholder)
        // User-locked speaker must never be overwritten
        assertEquals("David", spkB.displayName)
        assertTrue(spkB.isLocked)
        assertEquals("Identified Maya and David from scene dialogue", merged.castDiscoverySummary)
    }

    @Test
    fun youTubeCaptionFetcherExtractsChannelNameAndDescription() {
        val fakePlayerMap: Map<String, Any?> = mapOf(
            "videoDetails" to mapOf(
                "title" to "Street Stand Scene",
                "author" to "Cinema Studio Channel",
                "shortDescription" to "Featuring Maya and Hot Dog Vendor outside the station.",
                "lengthSeconds" to "145",
            )
        )
        val meta = com.example.data.subtitle.YouTubeCaptionFetcher.extractMetaFromPlayerMap(fakePlayerMap)
        assertEquals("Street Stand Scene", meta.title)
        assertEquals("Cinema Studio Channel", meta.channelName)
        assertEquals("Featuring Maya and Hot Dog Vendor outside the station.", meta.description)
        assertEquals(145_000L, meta.durationMs)
    }

    @Test
    fun speakerPalettePrioritizesPrimarySecondaryThenUnusedAndReservesWhite() {
        val hexes = com.example.data.model.SpeakerColorOrder.PALETTE_HEX_COLORS
        assertTrue(hexes.size >= 8)
        // Primary: Red, Blue, Yellow
        assertEquals(0xFFEF4444L, hexes[0])
        assertEquals(0xFF3B82F6L, hexes[1])
        assertEquals(0xFFFACC15L, hexes[2])
        // Secondary: Green, Orange, Purple
        assertEquals(0xFF22C55EL, hexes[3])
        assertEquals(0xFFF97316L, hexes[4])
        assertEquals(0xFFA855F7L, hexes[5])
        // Distinct unused: Cyan, Magenta, ...
        assertEquals(0xFF06B6D4L, hexes[6])
        assertEquals(0xFFD946EFL, hexes[7])
        // White must NEVER be in the speaker palette
        assertFalse(hexes.contains(com.example.data.model.SpeakerColorOrder.NON_SPEAKER_WHITE_HEX))
        assertFalse(hexes.contains(com.example.data.model.SpeakerColorOrder.UNKNOWN_SPEAKER_GRAY_HEX))
        assertEquals(hexes.size, hexes.distinct().size)
    }

    @Test
    fun reconcileCharacterColorsAssignsPrimaryBeforeSecondaryBeforeDistinctUnused() {
        val engine = SpeakerAttributionEngine()
        val unknownFirst = com.example.data.model.Speaker(id = "spk_a", defaultLabel = "Speaker A", accentIndex = 0)
        val castNames = listOf("Maya", "Kenji", "Hot Dog Vendor", "Officer Park", "Sora", "Chef Lin", "Conductor", "Civilian Woman 1")
        val discovered = castNames.mapIndexed { i, name ->
            val letter = ('b' + i)
            com.example.data.model.Speaker(
                id = "spk_$letter",
                defaultLabel = "Speaker ${letter.uppercaseChar()}",
                customName = name,
                accentIndex = 7, // intentionally scrambled default index
            )
        }
        val events = discovered.mapIndexed { i, spk ->
            com.example.data.model.DialogueEvent(
                id = "seq_$i",
                startTimeMs = (i + 1) * 1000L,
                endTimeMs = (i + 1) * 1000L + 800L,
                sourceText = "Line $i",
                translatedText = "Line $i",
                speakerId = spk.id,
                speakerConfidence = 0.88f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "shot_1",
            )
        }

        val (reconciled, _) = engine.reconcileCharacterColors(
            speakers = listOf(unknownFirst) + discovered,
            events = events,
            rememberedColors = emptyMap(),
        )

        // Unknown Speaker A did NOT reserve index 0; first 3 discovered speakers get Primary 0, 1, 2
        discovered.forEachIndexed { i, spk ->
            val updated = reconciled.first { it.id == spk.id }
            assertEquals(i, updated.accentIndex)
            assertTrue(updated.colorRemembered)
        }
    }

    @Test
    fun unknownSpeakerBubblesStayDottedGrayUntilDiscoveredOrManuallyAssigned() {
        val base = DemoScenarioFactory.createDemoProject()
        val attribution = SpeakerAttributionEngine()
        val layoutEngine = BalloonLayoutEngine()

        val unnamedA = com.example.data.model.Speaker(id = "spk_a", defaultLabel = "Speaker A", accentIndex = 0)
        val unnamedB = com.example.data.model.Speaker(id = "spk_b", defaultLabel = "Speaker B", accentIndex = 1)
        val spokenUnknownEvt = com.example.data.model.DialogueEvent(
            id = "unk_1",
            startTimeMs = 1_000L,
            endTimeMs = 2_500L,
            sourceText = "Who brought the umbrella?",
            translatedText = "Who brought the umbrella?",
            speakerId = "spk_b",
            speakerConfidence = 0.72f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
        )
        val narrationEvt = spokenUnknownEvt.copy(
            id = "narr_1",
            startTimeMs = 3_000L,
            endTimeMs = 4_500L,
            sourceText = "Rain pours over the station.",
            translatedText = "Rain pours over the station.",
            speakerId = null,
            dialogueType = DialogueType.NARRATION,
        )

        val projUnknown = base.copy(
            speakers = listOf(unnamedA, unnamedB),
            faceTracks = emptyList(),
            dialogueEvents = listOf(spokenUnknownEvt, narrationEvt),
        )

        // 1. Unnamed speaker spoken bubble -> unknown (dotted gray); Narration -> non-speaker-specific (white)
        val unknownPlaced = layoutEngine.computeLayout(projUnknown, 1_500L).first()
        assertTrue(unknownPlaced.isUnknownSpeaker)
        assertFalse(com.example.data.model.isBubbleNonSpeakerSpecific(unknownPlaced.event, projUnknown.styleSettings))

        val narrPlaced = layoutEngine.computeLayout(projUnknown, 3_500L).first()
        assertFalse(narrPlaced.isUnknownSpeaker)
        assertTrue(com.example.data.model.isBubbleNonSpeakerSpecific(narrPlaced.event, projUnknown.styleSettings))

        // 2. Manual assignment by user resolves unknown state & assigns first primary color (0 = Red)
        val manuallyAssignedProj = attribution.applyUserCorrection(
            project = projUnknown,
            eventId = "unk_1",
            newSpeakerId = "spk_b",
        )
        val manualPlaced = layoutEngine.computeLayout(manuallyAssignedProj, 1_500L).first()
        assertFalse(manualPlaced.isUnknownSpeaker)
        assertEquals(0, manualPlaced.speaker?.accentIndex)

        // 3. Discovery (naming as Hot Dog Vendor) resolves unknown state & assigns primary color
        val discoveredProj = projUnknown.copy(
            speakers = listOf(
                unnamedA,
                unnamedB.copy(customName = "Hot Dog Vendor", isRolePlaceholder = true),
            )
        )
        val (reconciledDiscovered, _) = attribution.reconcileCharacterColors(
            speakers = discoveredProj.speakers,
            events = discoveredProj.dialogueEvents,
            rememberedColors = emptyMap(),
        )
        val discoveredPlaced = layoutEngine.computeLayout(
            discoveredProj.copy(speakers = reconciledDiscovered),
            1_500L,
        ).first()
        assertFalse(discoveredPlaced.isUnknownSpeaker)
        assertEquals(0, discoveredPlaced.speaker?.accentIndex)
    }

    @Test
    fun scoutSpatialAnchorPlacesYouTubeBubbleNearSpeakerWithTail() {
        val base = DemoScenarioFactory.createDemoProject()
        val layoutEngine = BalloonLayoutEngine()

        val speakerLeft = com.example.data.model.Speaker(
            id = "spk_a",
            defaultLabel = "Speaker A",
            customName = "Elena",
            accentIndex = 0,
            colorRemembered = true,
            preferredScreenSideX = 0.22f,
        )
        val speakerRight = com.example.data.model.Speaker(
            id = "spk_b",
            defaultLabel = "Speaker B",
            customName = "Devon",
            accentIndex = 1,
            colorRemembered = true,
            preferredScreenSideX = 0.78f,
        )
        val speakerUnknown = com.example.data.model.Speaker(
            id = "spk_c",
            defaultLabel = "Speaker C",
            accentIndex = 2,
        )

        val leftEvt = com.example.data.model.DialogueEvent(
            id = "yt_left",
            startTimeMs = 1_000L,
            endTimeMs = 3_500L,
            sourceText = "Look over here by the window!",
            translatedText = "Look over here by the window!",
            speakerId = "spk_a",
            speakerConfidence = 0.88f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "yt_scout",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(0.22f, 0.36f),
        )
        val rightEvt = com.example.data.model.DialogueEvent(
            id = "yt_right",
            startTimeMs = 4_000L,
            endTimeMs = 6_500L,
            sourceText = "I see the train coming from the east.",
            translatedText = "I see the train coming from the east.",
            speakerId = "spk_b",
            speakerConfidence = 0.86f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "yt_scout",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(0.80f, 0.38f),
        )
        val unknownEvt = com.example.data.model.DialogueEvent(
            id = "yt_unknown",
            startTimeMs = 7_000L,
            endTimeMs = 9_000L,
            sourceText = "Wait for me!",
            translatedText = "Wait for me!",
            speakerId = "spk_c",
            speakerConfidence = 0.80f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "yt_scout",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(0.50f, 0.40f),
        )

        val offScreenLeftEvt = com.example.data.model.DialogueEvent(
            id = "yt_off_left",
            startTimeMs = 10_000L,
            endTimeMs = 12_000L,
            sourceText = "Calling from the hallway!",
            translatedText = "Calling from the hallway!",
            speakerId = "spk_a",
            speakerConfidence = 0.84f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "yt_scout",
            dialogueType = DialogueType.OFF_SCREEN_LEFT,
            placementMode = PlacementMode.EDGE_DOCKED,
        )

        val attribution = SpeakerAttributionEngine()
        val (attrSpeakers, attrEvents) = attribution.assignSpeakers(
            events = listOf(leftEvt, rightEvt, unknownEvt, offScreenLeftEvt),
            shots = listOf(com.example.data.model.Shot("yt_scout", 0L, 15_000L, cutType = "EMBEDDED_YOUTUBE")),
            faceTracks = emptyList(),
            existingSpeakers = listOf(speakerLeft, speakerRight, speakerUnknown),
            turnHints = mapOf(
                "yt_left" to com.example.data.ai.AiTranslatedLine(
                    id = "yt_left",
                    naturalTranslation = leftEvt.translatedText,
                    literalTranslation = "",
                    romanization = "",
                    dialogueTypeHint = DialogueType.SPOKEN,
                    speakerTurnHint = "UNCERTAIN",
                    suggestedSpeakerSlot = "A",
                ),
                "yt_right" to com.example.data.ai.AiTranslatedLine(
                    id = "yt_right",
                    naturalTranslation = rightEvt.translatedText,
                    literalTranslation = "",
                    romanization = "",
                    dialogueTypeHint = DialogueType.SPOKEN,
                    speakerTurnHint = "UNCERTAIN",
                    suggestedSpeakerSlot = "B",
                ),
                "yt_unknown" to com.example.data.ai.AiTranslatedLine(
                    id = "yt_unknown",
                    naturalTranslation = unknownEvt.translatedText,
                    literalTranslation = "",
                    romanization = "",
                    dialogueTypeHint = DialogueType.SPOKEN,
                    speakerTurnHint = "UNCERTAIN",
                    suggestedSpeakerSlot = "C",
                ),
                "yt_off_left" to com.example.data.ai.AiTranslatedLine(
                    id = "yt_off_left",
                    naturalTranslation = offScreenLeftEvt.translatedText,
                    literalTranslation = "",
                    romanization = "",
                    dialogueTypeHint = DialogueType.OFF_SCREEN_LEFT,
                    speakerTurnHint = "UNCERTAIN",
                    suggestedSpeakerSlot = "A",
                ),
            ),
        )
        assertEquals(PlacementMode.AUTO, attrEvents.first { it.id == "yt_left" }.placementMode)
        assertEquals(PlacementMode.AUTO, attrEvents.first { it.id == "yt_right" }.placementMode)
        assertEquals(PlacementMode.EDGE_DOCKED, attrEvents.first { it.id == "yt_off_left" }.placementMode)

        val proj = base.copy(
            speakers = attrSpeakers,
            faceTracks = emptyList(),
            dialogueEvents = attrEvents,
        )

        val leftPlaced = layoutEngine.computeLayout(proj, 2_000L).first()
        assertEquals(PlacementMode.AUTO, leftPlaced.placementMode)
        assertNotNull(leftPlaced.tailTarget)
        assertTrue("Left speaker bubble should sit on left half", leftPlaced.rect.centerX < 0.55f)
        assertFalse(leftPlaced.isUnknownSpeaker)

        val rightPlaced = layoutEngine.computeLayout(proj, 5_000L).first()
        assertEquals(PlacementMode.AUTO, rightPlaced.placementMode)
        assertNotNull(rightPlaced.tailTarget)
        assertTrue("Right speaker bubble should sit on right half", rightPlaced.rect.centerX > 0.45f)
        assertFalse(rightPlaced.isUnknownSpeaker)

        val unknownPlaced = layoutEngine.computeLayout(proj, 8_000L).first()
        assertTrue("Unnamed speaker stays dotted gray until identified", unknownPlaced.isUnknownSpeaker)
        org.junit.Assert.assertNull("Unknown speaker bubble should not point a directional tail", unknownPlaced.tailTarget)

        val offLeftPlaced = layoutEngine.computeLayout(proj, 11_000L).first()
        assertEquals(PlacementMode.EDGE_DOCKED, offLeftPlaced.placementMode)
        assertTrue(offLeftPlaced.isOffScreenEdge)
        assertEquals(DialogueType.OFF_SCREEN_LEFT, offLeftPlaced.edgeSide)
    }

    @Test
    fun runningSpeakerGlossaryTracksMidVideoRenameHistoryAndEightSlots() {
        val spokenA = com.example.data.model.DialogueEvent(
            id = "e_a",
            startTimeMs = 12_000L,
            endTimeMs = 14_000L,
            sourceText = "Fresh dogs right here!",
            translatedText = "Fresh dogs right here!",
            speakerId = "spk_a",
            speakerConfidence = 0.85f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "yt_scout",
            dialogueType = DialogueType.SPOKEN,
        )
        val spokenH = spokenA.copy(
            id = "e_h",
            startTimeMs = 24_000L,
            endTimeMs = 26_000L,
            sourceText = "Tickets please!",
            translatedText = "Tickets please!",
            speakerId = "spk_h",
        )
        val base = DemoScenarioFactory.createDemoProject().copy(
            speakers = emptyList(),
            dialogueEvents = listOf(spokenA, spokenH),
        )
        val attribution = SpeakerAttributionEngine()

        // First discovery: role placeholder in slot A and slot H (8th slot)
        val initialAssignments = listOf(
            com.example.data.ai.AiCastSlotAssignment(
                slot = "A",
                displayName = "Hot Dog Vendor",
                isRolePlaceholder = true,
                isVisuallyConfirmed = true,
                rationale = "Standing behind street cart in apron",
                visualVoiceNotes = "Red baseball cap, warm baritone voice",
            ),
            com.example.data.ai.AiCastSlotAssignment(
                slot = "H",
                displayName = "Conductor",
                isRolePlaceholder = true,
                isVisuallyConfirmed = true,
                rationale = "Checks tickets in carriage",
                visualVoiceNotes = "Navy uniform, crisp tenor",
            ),
        )
        val (afterInitial, mem1) = com.example.ui.state.mergeCastSlotAssignments(
            project = base,
            assignments = initialAssignments,
            rememberedColors = emptyMap(),
            reconcileColors = attribution::reconcileCharacterColors,
            discoveryTimestampMs = 12_000L,
        )

        assertEquals(8, afterInitial.speakers.size)
        val slotAInitial = afterInitial.speakers[0]
        val slotHInitial = afterInitial.speakers[7]
        assertEquals("Hot Dog Vendor", slotAInitial.displayName)
        assertTrue(slotAInitial.isRolePlaceholder)
        assertEquals(12_000L, slotAInitial.firstAppearedMs)
        assertEquals("Red baseball cap, warm baritone voice", slotAInitial.visualVoiceNotes)
        assertEquals("Standing behind street cart in apron", slotAInitial.discoveryReason)
        assertEquals(0, slotAInitial.accentIndex) // Primary color #1 (Red)
        assertEquals("Conductor", slotHInitial.displayName)
        assertEquals(1, slotHInitial.accentIndex) // Primary color #2 (Blue)

        // Mid-video discovery: another character calls Hot Dog Vendor "Marcus"
        val renameAssignments = listOf(
            com.example.data.ai.AiCastSlotAssignment(
                slot = "A",
                displayName = "Marcus",
                isRolePlaceholder = false,
                isVisuallyConfirmed = true,
                rationale = "Addressed by name: 'Thanks Marcus!'",
                visualVoiceNotes = "Red baseball cap, warm baritone voice",
            )
        )
        val (afterRename, _) = com.example.ui.state.mergeCastSlotAssignments(
            project = afterInitial,
            assignments = renameAssignments,
            rememberedColors = mem1,
            reconcileColors = attribution::reconcileCharacterColors,
            discoveryTimestampMs = 64_000L,
        )

        val slotARenamed = afterRename.speakers[0]
        assertEquals("Marcus", slotARenamed.displayName)
        assertFalse(slotARenamed.isRolePlaceholder)
        assertEquals(listOf("Hot Dog Vendor"), slotARenamed.previousNames)
        assertEquals(12_000L, slotARenamed.firstAppearedMs)
        assertEquals("Addressed by name: 'Thanks Marcus!'", slotARenamed.discoveryReason)
        assertEquals("Red baseball cap, warm baritone voice", slotARenamed.visualVoiceNotes)
        assertEquals(0, slotARenamed.accentIndex) // Keeps same primary color slot
    }

    @Test
    fun captionDisplayModePrimaryPickerStreamlinesToThreeAndMigratesBoth() {
        assertEquals(
            listOf(
                com.example.data.model.CaptionDisplayMode.BALLOONS,
                com.example.data.model.CaptionDisplayMode.CLASSIC,
                com.example.data.model.CaptionDisplayMode.OFF,
            ),
            com.example.data.model.CaptionDisplayMode.primaryModes,
        )
        assertEquals(
            com.example.data.model.CaptionDisplayMode.BALLOONS,
            com.example.data.model.CaptionDisplayMode.BOTH.normalizedPrimaryMode(),
        )
        assertEquals(
            com.example.data.model.CaptionDisplayMode.CLASSIC,
            com.example.data.model.CaptionDisplayMode.CLASSIC.normalizedPrimaryMode(),
        )
        assertEquals(
            com.example.data.model.CaptionDisplayMode.OFF,
            com.example.data.model.CaptionDisplayMode.OFF.normalizedPrimaryMode(),
        )
    }

    @Test
    fun scoutBubbleAvoidsSpeakerFaceBoxForCenterAndTwoShotFraming() {
        val base = DemoScenarioFactory.createDemoProject()
        val layoutEngine = BalloonLayoutEngine()

        val host = com.example.data.model.Speaker(
            id = "spk_a",
            defaultLabel = "Speaker A",
            customName = "Roy Wood Jr.",
            accentIndex = 0,
            colorRemembered = true,
        )
        val panelist = com.example.data.model.Speaker(
            id = "spk_b",
            defaultLabel = "Speaker B",
            customName = "Michael Ian Black",
            accentIndex = 2,
            colorRemembered = true,
        )
        val centerHostEvt = com.example.data.model.DialogueEvent(
            id = "center_host",
            startTimeMs = 1_000L,
            endTimeMs = 4_000L,
            sourceText = "FAA outage forces airports to go back to analog landing assistance.",
            translatedText = "FAA outage forces airports to go back to analog landing assistance.",
            speakerId = "spk_a",
            speakerConfidence = 0.90f,
            faceTrackId = null,
            faceConfidence = 0.80f,
            shotId = "yt_1",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(0.50f, 0.28f),
        )
        val rightPanelistEvt = centerHostEvt.copy(
            id = "right_panelist",
            startTimeMs = 5_000L,
            endTimeMs = 8_000L,
            sourceText = "What did you do to charm him? Why does he like you?",
            translatedText = "What did you do to charm him? Why does he like you?",
            speakerId = "spk_b",
            anchorPosition = com.example.data.model.NormalizedPoint(0.75f, 0.28f),
        )

        val proj = base.copy(
            speakers = listOf(host, panelist),
            faceTracks = emptyList(),
            dialogueEvents = listOf(centerHostEvt, rightPanelistEvt),
        )

        val hostPlaced = layoutEngine.computeLayout(proj, 2_000L).first()
        val hostHeadBox = com.example.data.model.NormalizedRect(0.37f, 0.15f, 0.63f, 0.43f)
        assertEquals(
            "Center host speech bubble must not cover speaker's face",
            0f,
            hostPlaced.rect.intersectionArea(hostHeadBox),
            0.001f,
        )
        assertNotNull(hostPlaced.tailTarget)

        val panelPlaced = layoutEngine.computeLayout(proj, 6_000L).first()
        val panelHeadBox = com.example.data.model.NormalizedRect(0.62f, 0.15f, 0.88f, 0.43f)
        assertEquals(
            "Right panelist speech bubble must not cover speaker's face",
            0f,
            panelPlaced.rect.intersectionArea(panelHeadBox),
            0.001f,
        )
        assertTrue("Right panelist bubble stays on right half", panelPlaced.rect.centerX > 0.48f)
    }

    @Test
    fun uncertainScoutClipSpeakerStaysUnknownInsteadOfMisapplyingExistingCastSlot() {
        val attribution = SpeakerAttributionEngine()
        val host = com.example.data.model.Speaker(
            id = "spk_a",
            defaultLabel = "Speaker A",
            customName = "Roy Wood Jr.",
            accentIndex = 0,
            colorRemembered = true,
        )
        val scooterDriver = com.example.data.model.Speaker(
            id = "spk_b",
            defaultLabel = "Speaker B",
            customName = "Scooter Driver",
            isRolePlaceholder = true,
            accentIndex = 1,
            colorRemembered = true,
        )
        val hostLine = com.example.data.model.DialogueEvent(
            id = "e_host",
            startTimeMs = 1_000L,
            endTimeMs = 3_000L,
            sourceText = "Let's watch this clip.",
            translatedText = "Let's watch this clip.",
            speakerId = null,
            speakerConfidence = 0.85f,
            faceTrackId = null,
            faceConfidence = 0.78f,
            shotId = "shot_1",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(0.50f, 0.28f),
        )
        val clipUnknownLine = hostLine.copy(
            id = "e_clip_uncertain",
            startTimeMs = 4_000L,
            endTimeMs = 6_500L,
            sourceText = "People are like, 'Oh, is that Democratic socialism?'",
            translatedText = "People are like, 'Oh, is that Democratic socialism?'",
            speakerConfidence = 0.42f,
            anchorPosition = com.example.data.model.NormalizedPoint(0.28f, 0.30f),
        )
        val ninthSpeakerLine = hostLine.copy(
            id = "e_slot_i",
            startTimeMs = 8_000L,
            endTimeMs = 10_000L,
            sourceText = "Welcome to the show.",
            translatedText = "Welcome to the show.",
            speakerConfidence = 0.86f,
        )

        val (speakersOut, eventsOut) = attribution.assignSpeakers(
            events = listOf(hostLine, clipUnknownLine, ninthSpeakerLine),
            shots = listOf(com.example.data.model.Shot("shot_1", 0L, 12_000L, cutType = "EMBEDDED_YOUTUBE")),
            faceTracks = emptyList(),
            existingSpeakers = listOf(host, scooterDriver),
            turnHints = mapOf(
                "e_host" to com.example.data.ai.AiTranslatedLine(
                    id = "e_host",
                    naturalTranslation = hostLine.translatedText,
                    literalTranslation = "",
                    romanization = "",
                    dialogueTypeHint = DialogueType.SPOKEN,
                    speakerTurnHint = "UNCERTAIN",
                    suggestedSpeakerSlot = "A",
                ),
                "e_clip_uncertain" to com.example.data.ai.AiTranslatedLine(
                    id = "e_clip_uncertain",
                    naturalTranslation = clipUnknownLine.translatedText,
                    literalTranslation = "",
                    romanization = "",
                    dialogueTypeHint = DialogueType.SPOKEN,
                    speakerTurnHint = "UNCERTAIN",
                    suggestedSpeakerSlot = "UNCERTAIN",
                ),
                "e_slot_i" to com.example.data.ai.AiTranslatedLine(
                    id = "e_slot_i",
                    naturalTranslation = ninthSpeakerLine.translatedText,
                    literalTranslation = "",
                    romanization = "",
                    dialogueTypeHint = DialogueType.SPOKEN,
                    speakerTurnHint = "UNCERTAIN",
                    suggestedSpeakerSlot = "I",
                ),
            ),
        )

        assertEquals("spk_a", eventsOut.first { it.id == "e_host" }.speakerId)
        org.junit.Assert.assertNull(
            "UNCERTAIN clip voice must stay unassigned (dotted gray) rather than misapplying Scooter Driver",
            eventsOut.first { it.id == "e_clip_uncertain" }.speakerId,
        )
        assertEquals("spk_i", eventsOut.first { it.id == "e_slot_i" }.speakerId)
        assertTrue("Roster expands cleanly to include 9th speaker slot I", speakersOut.size >= 9)
    }

    @Test
    fun unconfirmedScoutSpeakerStaysDottedGrayThenRetroactivelyUpdatesAllVideoCuesOnConfirmation() {
        val attribution = SpeakerAttributionEngine()
        val layoutEngine = BalloonLayoutEngine()
        val baseProject = DemoScenarioFactory.createDemoProject().copy(
            speakers = emptyList(),
            faceTracks = emptyList(),
            shots = listOf(com.example.data.model.Shot("shot_1", 0L, 60_000L, cutType = "EMBEDDED_YOUTUBE")),
            dialogueEvents = listOf(
                com.example.data.model.DialogueEvent(
                    id = "cue_1",
                    startTimeMs = 1_000L,
                    endTimeMs = 3_500L,
                    sourceText = "Let's look at the latest numbers.",
                    translatedText = "Let's look at the latest numbers.",
                    speakerId = "spk_b",
                    speakerConfidence = 0.48f,
                    faceTrackId = null,
                    faceConfidence = 0.72f,
                    shotId = "shot_1",
                    dialogueType = DialogueType.SPOKEN,
                    placementMode = PlacementMode.NEUTRAL_FALLBACK,
                    anchorPosition = com.example.data.model.NormalizedPoint(0.72f, 0.30f),
                ),
                com.example.data.model.DialogueEvent(
                    id = "cue_2",
                    startTimeMs = 14_000L,
                    endTimeMs = 17_000L,
                    sourceText = "That's the exact trend we saw last week.",
                    translatedText = "That's the exact trend we saw last week.",
                    speakerId = "spk_b",
                    speakerConfidence = 0.75f,
                    faceTrackId = null,
                    faceConfidence = 0.80f,
                    shotId = "shot_1",
                    dialogueType = DialogueType.SPOKEN,
                    placementMode = PlacementMode.AUTO,
                    anchorPosition = com.example.data.model.NormalizedPoint(0.74f, 0.32f),
                ),
            ),
        )

        // Step 1: Unconfirmed Scout observation — must stay dotted gray and NOT consume Primary color 0
        val (unconfirmedProject, memoryAfterStep1) = com.example.ui.state.mergeCastSlotAssignments(
            project = baseProject,
            assignments = listOf(
                com.example.data.ai.AiCastSlotAssignment(
                    slot = "B",
                    displayName = "Panelist",
                    rationale = "Sitting at right desk, no chyron shown yet",
                    isRolePlaceholder = true,
                    visualVoiceNotes = "Brown blazer, right panel desk",
                    isVisuallyConfirmed = false,
                ),
            ),
            rememberedColors = emptyMap(),
            reconcileColors = { spks, evts, mem -> attribution.reconcileCharacterColors(spks, evts, mem) },
            discoveryTimestampMs = 1_000L,
        )

        val spkBUnconfirmed = unconfirmedProject.speakers.first { it.id == "spk_b" }
        assertFalse("Unconfirmed Scout speaker must remain unconfirmed", spkBUnconfirmed.isConfirmedCharacter)
        assertTrue(
            "Unconfirmed Scout speaker cues must render dotted gray",
            com.example.data.model.isBubbleSpeakerUnknown(unconfirmedProject.dialogueEvents.first(), spkBUnconfirmed),
        )
        assertFalse("Unconfirmed speaker must NOT claim a remembered Primary color", spkBUnconfirmed.colorRemembered)
        assertTrue(
            "Visual/voice notes must be preserved privately while unconfirmed",
            spkBUnconfirmed.visualVoiceNotes.contains("Brown blazer"),
        )
        val unconfirmedBalloons = layoutEngine.computeLayout(unconfirmedProject, 2_000L)
        assertEquals(1, unconfirmedBalloons.size)
        assertTrue("Placed balloon stays dotted gray before visual confirmation", unconfirmedBalloons.first().isUnknownSpeaker)

        // Step 2: Later window visually confirms the speaker via lower-third chyron -> retroactive update!
        val (confirmedProject, memoryAfterStep2) = com.example.ui.state.mergeCastSlotAssignments(
            project = unconfirmedProject,
            assignments = listOf(
                com.example.data.ai.AiCastSlotAssignment(
                    slot = "B",
                    displayName = "Michael Ian Black",
                    rationale = "Lower-third chyron visibly reads Michael Ian Black",
                    isRolePlaceholder = false,
                    visualVoiceNotes = "Brown blazer, right panel desk",
                    isVisuallyConfirmed = true,
                ),
            ),
            rememberedColors = memoryAfterStep1,
            reconcileColors = { spks, evts, mem -> attribution.reconcileCharacterColors(spks, evts, mem) },
            discoveryTimestampMs = 15_000L,
        )

        val spkBConfirmed = confirmedProject.speakers.first { it.id == "spk_b" }
        assertTrue("Speaker must become confirmed once visually verified", spkBConfirmed.isConfirmedCharacter)
        assertEquals("Michael Ian Black", spkBConfirmed.displayName)
        assertEquals("First confirmed character claims Primary color 0", 0, spkBConfirmed.accentIndex)
        assertTrue("Confirmed speaker reserves remembered color", spkBConfirmed.colorRemembered)
        assertEquals(0, memoryAfterStep2["michael ian black"])

        // Verify ALL earlier cues were retroactively upgraded across the user's video data
        val cue1After = confirmedProject.dialogueEvents.first { it.id == "cue_1" }
        val cue2After = confirmedProject.dialogueEvents.first { it.id == "cue_2" }
        assertTrue("Earlier cue 1 confidence retroactively boosted", cue1After.speakerConfidence >= 0.90f)
        assertEquals("Earlier cue 1 promoted to AUTO placement", PlacementMode.AUTO, cue1After.placementMode)
        assertTrue("Earlier cue 2 confidence retroactively boosted", cue2After.speakerConfidence >= 0.90f)
        assertFalse(
            "Cue 1 is no longer unknown after visual confirmation",
            com.example.data.model.isBubbleSpeakerUnknown(cue1After, spkBConfirmed),
        )
        val confirmedBalloons = layoutEngine.computeLayout(confirmedProject, 2_000L)
        assertEquals(1, confirmedBalloons.size)
        assertFalse("Earlier bubble now renders in confirmed speaker color", confirmedBalloons.first().isUnknownSpeaker)
    }

    @Test
    fun cutawayOffScreenCueInheritsSpeakerSideMemoryAndRemovesPointerTail() {
        val spkLeft = com.example.data.model.Speaker(
            id = "spk_a",
            defaultLabel = "Speaker A",
            customName = "Roy Wood Jr.",
            accentIndex = 0,
            colorRemembered = true,
            isIdentityConfirmed = true,
            preferredScreenSideX = 0.22f,
        )
        val spkRight = com.example.data.model.Speaker(
            id = "spk_b",
            defaultLabel = "Speaker B",
            customName = "Amber Ruffin",
            accentIndex = 1,
            colorRemembered = true,
            isIdentityConfirmed = true,
            preferredScreenSideX = 0.78f,
        )
        val cutawayLeft = com.example.data.model.DialogueEvent(
            id = "cut_left",
            startTimeMs = 10_000L,
            endTimeMs = 13_000L,
            sourceText = "And meanwhile on Capitol Hill...",
            translatedText = "And meanwhile on Capitol Hill...",
            speakerId = "spk_a",
            speakerConfidence = 0.92f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_broll",
            dialogueType = DialogueType.OFF_SCREEN_BEHIND,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(0.55f, 0.35f),
        )
        val cutawayRight = cutawayLeft.copy(
            id = "cut_right",
            startTimeMs = 14_000L,
            endTimeMs = 17_000L,
            sourceText = "Wait until you see the next clip!",
            translatedText = "Wait until you see the next clip!",
            speakerId = "spk_b",
        )

        val resolved = com.example.ui.state.applyCutawayOffScreenSideMemory(
            events = listOf(cutawayLeft, cutawayRight),
            speakers = listOf(spkLeft, spkRight),
        )
        val outLeft = resolved.first { it.id == "cut_left" }
        val outRight = resolved.first { it.id == "cut_right" }
        assertEquals("Left speaker cutaway routes to OFF_SCREEN_LEFT", DialogueType.OFF_SCREEN_LEFT, outLeft.dialogueType)
        assertEquals("Right speaker cutaway routes to OFF_SCREEN_RIGHT", DialogueType.OFF_SCREEN_RIGHT, outRight.dialogueType)
        org.junit.Assert.assertNull("Stale anchor cleared on left cutaway", outLeft.anchorPosition)
        org.junit.Assert.assertNull("Stale anchor cleared on right cutaway", outRight.anchorPosition)
        assertEquals(PlacementMode.EDGE_DOCKED, outLeft.placementMode)
        assertEquals(PlacementMode.EDGE_DOCKED, outRight.placementMode)

        // Verify BalloonLayoutEngine safety net also routes OFF_SCREEN_BEHIND -> OFF_SCREEN_LEFT with zero pointer tail
        val project = DemoScenarioFactory.createDemoProject().copy(
            speakers = listOf(spkLeft, spkRight),
            faceTracks = emptyList(),
            shots = listOf(com.example.data.model.Shot("shot_broll", 0L, 30_000L, cutType = "EMBEDDED_YOUTUBE")),
            dialogueEvents = listOf(cutawayLeft),
        )
        val balloons = BalloonLayoutEngine().computeLayout(project, 11_000L)
        assertEquals(1, balloons.size)
        val placed = balloons.first()
        assertTrue("Cutaway renders as off-screen edge bubble", placed.isOffScreenEdge)
        assertEquals("Layout safety net docks cutaway to left edge", DialogueType.OFF_SCREEN_LEFT, placed.edgeSide)
        org.junit.Assert.assertNull("Off-screen cutaway must have NO false pointer tail", placed.tailTarget)
        assertTrue("Left-docked cutaway stays on left half of frame", placed.rect.centerX < 0.42f)
    }

    @Test
    fun continuousPerimeterTailGlidesSmoothlyAcrossAllOctantsAndCorners() {
        val bodyRect = androidx.compose.ui.geometry.Rect(0f, 0f, 220f, 68f)
        val octantDegrees = listOf(0.0, 45.0, 90.0, 135.0, 180.0, -135.0, -90.0, -45.0)
        octantDegrees.forEach { deg ->
            val rad = Math.toRadians(deg).toFloat()
            val geom = com.example.ui.player.computePerimeterTailGeometry(
                bodyRect = bodyRect,
                cornerRadius = 18f,
                aimAngleRad = rad,
                targetDistanceFromCenterPx = 150f,
                halfThroatPx = 12f,
                mouthClearancePx = 10f,
                minTailReachPx = 14f,
                maxTailReachPx = 56f,
            )
            // Tip must project cleanly outside the bubble body along the intended aim ray
            val dx = geom.tipPoint.x - geom.baseCenter.x
            val dy = geom.tipPoint.y - geom.baseCenter.y
            val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            assertTrue("Tail tip must have positive reach at ${deg}deg", len >= 13.5f)
            val dot = (dx / len) * kotlin.math.cos(rad) + (dy / len) * kotlin.math.sin(rad)
            assertTrue("Tail tip must point along aim ray at ${deg}deg (dot=$dot)", dot > 0.95f)
            assertFalse("Tail tip must lie outside bodyRect at ${deg}deg", bodyRect.contains(geom.tipPoint))

            // Throat opening must have distinct start/end points forming an open bleed along the border
            val throatSpan = kotlin.math.hypot(
                (geom.throatEndPoint.x - geom.throatStartPoint.x).toDouble(),
                (geom.throatEndPoint.y - geom.throatStartPoint.y).toDouble(),
            )
            assertTrue("Open throat width must be substantial at ${deg}deg (span=$throatSpan)", throatSpan > 10.0)
        }

        // Shortest-arc wrap across +PI / -PI boundary (170deg -> -170deg must glide +20deg, not -340deg)
        val fromRad = Math.toRadians(170.0).toFloat()
        val toRad = Math.toRadians(-170.0).toFloat()
        val delta = com.example.ui.player.shortestAngularDeltaRad(fromRad, toRad)
        val expected = Math.toRadians(20.0).toFloat()
        assertEquals(expected, delta, 0.001f)
    }

    @Test
    fun deadSpaceHuntingAvoidsBystanderFacesChyronsAndHonorsPreferredZone() {
        val host = com.example.data.model.Speaker(
            id = "spk_host",
            defaultLabel = "Speaker A",
            customName = "Host",
            accentIndex = 0,
            colorRemembered = true,
            isIdentityConfirmed = true,
        )
        val bystanderFace = com.example.data.model.NormalizedRect(0.05f, 0.04f, 0.36f, 0.45f)
        val lowerChyron = com.example.data.model.NormalizedRect(0.10f, 0.72f, 0.90f, 0.94f)
        val event = com.example.data.model.DialogueEvent(
            id = "dead_space_1",
            startTimeMs = 1_000L,
            endTimeMs = 4_000L,
            sourceText = "Welcome back to the desk tonight.",
            translatedText = "Welcome back to the desk tonight.",
            speakerId = host.id,
            speakerConfidence = 0.96f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_studio",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(0.50f, 0.36f),
            preferredDeadSpaceZone = com.example.data.model.DeadSpaceZone.TOP_RIGHT,
            avoidBoxes = listOf(bystanderFace, lowerChyron),
        )
        val project = DemoScenarioFactory.createDemoProject().copy(
            speakers = listOf(host),
            faceTracks = emptyList(),
            shots = listOf(com.example.data.model.Shot("shot_studio", 0L, 10_000L, cutType = "EMBEDDED_YOUTUBE")),
            dialogueEvents = listOf(event),
        )

        val placed = BalloonLayoutEngine().computeLayout(project, 2_000L)
        assertEquals(1, placed.size)
        val balloon = placed.first()

        assertEquals(
            "Bubble must have zero overlap with bystander face avoidBox",
            0f,
            balloon.rect.intersectionArea(bystanderFace),
            0.0001f,
        )
        assertEquals(
            "Bubble must have zero overlap with lower-third chyron avoidBox",
            0f,
            balloon.rect.intersectionArea(lowerChyron),
            0.0001f,
        )
        assertTrue(
            "Bubble should hunt preferred TOP_RIGHT dead space (centerX=${balloon.rect.centerX})",
            balloon.rect.centerX > 0.55f,
        )
        assertTrue(
            "Bubble should stay in upper frame dead space (centerY=${balloon.rect.centerY})",
            balloon.rect.centerY < 0.40f,
        )
        assertNotNull("Confirmed spoken bubble must retain tailTarget pointing to mouth", balloon.tailTarget)
    }

    @Test
    fun tailLineOfSightCheckRejectsBubblePocketsWhoseTailCrossesBystanderFace() {
        val centerBystander = com.example.data.model.NormalizedRect(0.36f, 0.12f, 0.60f, 0.72f)
        // Direct segment test: slicing straight across the bystander vs staying cleanly to the right
        assertTrue(
            "Segment slicing across bystander face must be detected",
            com.example.domain.layout.segmentIntersectsRect(
                p1 = com.example.data.model.NormalizedPoint(0.18f, 0.22f),
                p2 = com.example.data.model.NormalizedPoint(0.80f, 0.40f),
                rect = centerBystander,
            ),
        )
        assertFalse(
            "Segment entirely to the right of bystander face must pass clear",
            com.example.domain.layout.segmentIntersectsRect(
                p1 = com.example.data.model.NormalizedPoint(0.76f, 0.14f),
                p2 = com.example.data.model.NormalizedPoint(0.80f, 0.40f),
                rect = centerBystander,
            ),
        )

        val rightSpeaker = com.example.data.model.Speaker(
            id = "spk_right",
            defaultLabel = "Speaker B",
            customName = "Panelist",
            accentIndex = 1,
            colorRemembered = true,
            isIdentityConfirmed = true,
        )
        val event = com.example.data.model.DialogueEvent(
            id = "los_1",
            startTimeMs = 2_000L,
            endTimeMs = 5_000L,
            sourceText = "That is a huge point.",
            translatedText = "That is a huge point.",
            speakerId = rightSpeaker.id,
            speakerConfidence = 0.94f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_panel",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(0.80f, 0.40f),
            preferredDeadSpaceZone = com.example.data.model.DeadSpaceZone.TOP_LEFT,
            avoidBoxes = listOf(centerBystander),
        )
        val project = DemoScenarioFactory.createDemoProject().copy(
            speakers = listOf(rightSpeaker),
            faceTracks = emptyList(),
            shots = listOf(com.example.data.model.Shot("shot_panel", 0L, 10_000L, cutType = "EMBEDDED_YOUTUBE")),
            dialogueEvents = listOf(event),
        )

        val placed = BalloonLayoutEngine().computeLayout(project, 3_000L)
        assertEquals(1, placed.size)
        val balloon = placed.first()
        val tail = balloon.tailTarget
        assertNotNull("Confirmed panelist bubble must have a tailTarget", tail)
        assertFalse(
            "Tail ray from bubble center to speaker mouth must NOT slice across bystander face (rect=${balloon.rect})",
            com.example.domain.layout.segmentIntersectsRect(
                p1 = com.example.data.model.NormalizedPoint(balloon.rect.centerX, balloon.rect.centerY),
                p2 = tail!!,
                rect = centerBystander,
            ),
        )
        assertTrue(
            "Bubble must choose right-side clear pocket instead of TOP_LEFT across bystander (centerX=${balloon.rect.centerX})",
            balloon.rect.centerX > 0.56f,
        )
    }

    @Test
    fun elasticComicTailProportionallyTapersThroatAndDampsCurvatureAtLongReach() {
        val bodyRect = androidx.compose.ui.geometry.Rect(0f, 0f, 220f, 68f)
        val aimRad = Math.toRadians(68.0).toFloat()

        val shortGeom = com.example.ui.player.computePerimeterTailGeometry(
            bodyRect = bodyRect,
            cornerRadius = 18f,
            aimAngleRad = aimRad,
            targetDistanceFromCenterPx = 62f,
            halfThroatPx = 11f,
            mouthClearancePx = 10f,
            minTailReachPx = 13f,
            maxTailReachPx = 104f,
        )
        val longGeom = com.example.ui.player.computePerimeterTailGeometry(
            bodyRect = bodyRect,
            cornerRadius = 18f,
            aimAngleRad = aimRad,
            targetDistanceFromCenterPx = 165f,
            halfThroatPx = 11f,
            mouthClearancePx = 10f,
            minTailReachPx = 13f,
            maxTailReachPx = 104f,
        )

        assertTrue(
            "Long dead-space reach must stretch elastically well beyond short reach (short=${shortGeom.tailLengthPx}, long=${longGeom.tailLengthPx})",
            longGeom.tailLengthPx > shortGeom.tailLengthPx + 40f,
        )
        assertTrue(
            "Long elastic tail must proportionally taper throat narrower than short reach (short=${shortGeom.effectiveHalfThroatPx}, long=${longGeom.effectiveHalfThroatPx})",
            longGeom.effectiveHalfThroatPx < shortGeom.effectiveHalfThroatPx,
        )
        assertTrue(
            "Tapered throat must stay above minimum readable floor (5.2f, got=${longGeom.effectiveHalfThroatPx})",
            longGeom.effectiveHalfThroatPx >= 5.2f,
        )
    }

    @Test
    fun unfetteredFreeRoamingGridMovesBubbleAcrossScreenToAvoidForeheadAndBystanders() {
        val speaker = com.example.data.model.Speaker(
            id = "spk_roam",
            defaultLabel = "Speaker A",
            customName = "Roy Wood Jr.",
            accentIndex = 0,
            colorRemembered = true,
            isIdentityConfirmed = true,
        )
        // Speaker mouth sits center-top; forehead/hair exclusion (cy-0.21..cy+0.13) and flanking avoidBoxes
        // block all immediate left/right/above pockets, forcing the 7x5 free-roaming grid to place cleanly in lower open frame.
        val mouthX = 0.50f
        val mouthY = 0.30f
        val expandedHeadGuard = com.example.data.model.NormalizedRect(
            left = mouthX - 0.145f,
            top = mouthY - 0.21f,
            right = mouthX + 0.145f,
            bottom = mouthY + 0.13f,
        )
        val leftBystander = com.example.data.model.NormalizedRect(0.02f, 0.02f, 0.38f, 0.56f)
        val rightBystander = com.example.data.model.NormalizedRect(0.62f, 0.02f, 0.98f, 0.56f)
        val event = com.example.data.model.DialogueEvent(
            id = "roam_1",
            startTimeMs = 1_000L,
            endTimeMs = 4_500L,
            sourceText = "Let me break down what happened on the floor.",
            translatedText = "Let me break down what happened on the floor.",
            speakerId = speaker.id,
            speakerConfidence = 0.94f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_roam",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            anchorPosition = com.example.data.model.NormalizedPoint(mouthX, mouthY),
            avoidBoxes = listOf(leftBystander, rightBystander),
        )
        val project = DemoScenarioFactory.createDemoProject().copy(
            speakers = listOf(speaker),
            faceTracks = emptyList(),
            shots = listOf(com.example.data.model.Shot("shot_roam", 0L, 10_000L, cutType = "EMBEDDED_YOUTUBE")),
            dialogueEvents = listOf(event),
        )

        val placed = BalloonLayoutEngine().computeLayout(project, 2_000L)
        assertEquals(1, placed.size)
        val balloon = placed.first()
        assertEquals(0f, balloon.rect.intersectionArea(expandedHeadGuard), 0.0001f)
        assertEquals(0f, balloon.rect.intersectionArea(leftBystander), 0.0001f)
        assertEquals(0f, balloon.rect.intersectionArea(rightBystander), 0.0001f)
        assertTrue(
            "Free-roaming grid should move freely into open lower frame when upper pockets are blocked (centerY=${balloon.rect.centerY})",
            balloon.rect.centerY > 0.52f,
        )
    }

    @Test
    fun quotaErrorDetectorFlagsCreditsDepletedAnd429WhileIgnoringTransientTimeouts() {
        val depleted = RuntimeException("Your prepayment credits are depleted. Please go to AI Studio.")
        val wrapped429 = IllegalStateException("Backend failed", RuntimeException("HTTP 429 RESOURCE_EXHAUSTED: quota exceeded"))
        val timeout = java.util.concurrent.TimeoutException("Timed out waiting for response")
        val forbidden = IllegalStateException("HTTP 403 App Check token rejected")

        assertTrue(com.example.data.ai.TranslationAiService.isQuotaOrCreditExhaustedError(depleted))
        assertTrue(com.example.data.ai.TranslationAiService.isQuotaOrCreditExhaustedError(wrapped429))
        assertFalse(com.example.data.ai.TranslationAiService.isQuotaOrCreditExhaustedError(timeout))
        assertFalse(com.example.data.ai.TranslationAiService.isQuotaOrCreditExhaustedError(forbidden))
        assertFalse(com.example.data.ai.TranslationAiService.isQuotaOrCreditExhaustedError(null))
    }

    @Test
    fun quotaSaverDefaultsAndCacheKeysAreDeterministic() {
        val uiDefaults = com.example.ui.state.DialogueLensUiState()
        assertFalse(uiDefaults.cloudDialogueConfigured)
        assertEquals(com.example.data.model.QuotaSaverMode.ZERO_CLOUD, uiDefaults.quotaSaverMode)

        val ytKey1 = com.example.data.project.ProjectStore.canonicalYouTubeCacheKey("dQw4w9WgXcQ", "English")
        val ytKey2 = com.example.data.project.ProjectStore.canonicalYouTubeCacheKey("dQw4w9WgXcQ", "english")
        val ytKeyOther = com.example.data.project.ProjectStore.canonicalYouTubeCacheKey("dQw4w9WgXcQ", "Japanese")
        assertEquals("yt_dQw4w9WgXcQ_english", ytKey1)
        assertEquals(ytKey1, ytKey2)
        assertTrue(ytKey1 != ytKeyOther)

        val lineKey1 = com.example.data.project.ProjectStore.buildLineTranslationCacheKey("  Hello   world! ", "English", "Spanish")
        val lineKey2 = com.example.data.project.ProjectStore.buildLineTranslationCacheKey("hello world!", "english", "spanish")
        val lineKeyFrench = com.example.data.project.ProjectStore.buildLineTranslationCacheKey("hello world!", "english", "french")
        assertEquals(lineKey1, lineKey2)
        assertTrue(lineKey1 != lineKeyFrench)
    }

    @Test
    fun scoutHeartbeatServiceUsesExponentialBackoffRecoversAndTripsImmediatelyOnQuota() = kotlinx.coroutines.runBlocking {
        // 1. Exponential backoff sequence: 1200 -> 2400 -> 4800 -> capped at maxDelayMs
        assertEquals(1_200L, com.example.data.ai.ScoutHeartbeatService.computeBackoffDelayMs(attempt = 1, baseDelayMs = 1_200L, maxDelayMs = 16_000L))
        assertEquals(2_400L, com.example.data.ai.ScoutHeartbeatService.computeBackoffDelayMs(attempt = 2, baseDelayMs = 1_200L, maxDelayMs = 16_000L))
        assertEquals(4_800L, com.example.data.ai.ScoutHeartbeatService.computeBackoffDelayMs(attempt = 3, baseDelayMs = 1_200L, maxDelayMs = 16_000L))
        assertEquals(16_000L, com.example.data.ai.ScoutHeartbeatService.computeBackoffDelayMs(attempt = 6, baseDelayMs = 1_200L, maxDelayMs = 16_000L))

        val recordedSleeps = mutableListOf<Long>()
        val scheduledAttempts = mutableListOf<Int>()
        val service = com.example.data.ai.ScoutHeartbeatService(
            sleepFn = { ms -> recordedSleeps.add(ms) },
            baseDelayMs = 100L,
            maxDelayMs = 800L,
        )

        // 2. Two transient stream failures followed by automatic recovery on 3rd try
        var callCount = 0
        val recoveredResult = service.executeWithBackoffRetry(
            maxRetries = 3,
            onRetryScheduled = { attempt, _, _, _ -> scheduledAttempts.add(attempt) },
        ) {
            callCount++
            if (callCount < 3) {
                Result.failure(java.io.IOException("Stream packet dropped"))
            } else {
                Result.success("recovered-payload")
            }
        }
        assertTrue(recoveredResult.isSuccess)
        assertEquals("recovered-payload", recoveredResult.getOrNull())
        assertEquals(listOf(1, 2), scheduledAttempts)
        assertEquals(listOf(100L, 200L), recordedSleeps)
        val healthySnap = service.snapshot.value
        assertEquals(com.example.data.ai.ScoutHeartbeatStatus.HEALTHY, healthySnap.status)
        assertEquals(1, healthySnap.totalRecoveries)
        assertEquals(0, healthySnap.consecutiveFailures)

        // 3. Quota exhaustion must NOT burn backoff retries — breaks circuit immediately
        val sleepCountBeforeQuota = recordedSleeps.size
        var quotaCalls = 0
        val quotaResult = service.executeWithBackoffRetry<String>(maxRetries = 3) {
            quotaCalls++
            Result.failure(IllegalStateException("Your prepayment credits are depleted (429)"))
        }
        assertTrue(quotaResult.isFailure)
        assertEquals(1, quotaCalls)
        assertEquals(sleepCountBeforeQuota, recordedSleeps.size)
        assertEquals(com.example.data.ai.ScoutHeartbeatStatus.QUOTA_EXHAUSTED, service.snapshot.value.status)
    }

    @Test
    fun deadSpaceHeatmapAnchorsMovingSpeakerBubbleOntoLowestDensityScreenEdge() {
        val speaker = com.example.data.model.Speaker(
            id = "spk_move",
            defaultLabel = "Speaker A",
            customName = "Dana",
            accentIndex = 0,
            colorRemembered = true,
            isIdentityConfirmed = true,
        )
        // Moving speaker shifts from top-left toward center while bystanders/chyrons crowd TOP, LEFT, and BOTTOM,
        // leaving the RIGHT edge as the lowest-density screen buffer.
        val movingTrack = com.example.data.model.FaceTrack(
            id = "ft_move",
            shotId = "shot_move",
            speakerId = speaker.id,
            samples = listOf(
                com.example.data.model.FaceSample(
                    timeMs = 1_000L,
                    boundingBox = com.example.data.model.NormalizedRect(0.14f, 0.14f, 0.34f, 0.44f),
                    mouthActivity = 0.85f,
                    headYawDeg = 8f,
                ),
                com.example.data.model.FaceSample(
                    timeMs = 1_500L,
                    boundingBox = com.example.data.model.NormalizedRect(0.26f, 0.16f, 0.48f, 0.48f),
                    mouthActivity = 0.90f,
                    headYawDeg = 10f,
                ),
            ),
        )
        val topCrowdTrack = com.example.data.model.FaceTrack(
            id = "ft_top_crowd",
            shotId = "shot_move",
            speakerId = null,
            samples = listOf(
                com.example.data.model.FaceSample(
                    timeMs = 1_000L,
                    boundingBox = com.example.data.model.NormalizedRect(0.46f, 0.05f, 0.68f, 0.34f),
                    mouthActivity = 0.1f,
                ),
                com.example.data.model.FaceSample(
                    timeMs = 1_500L,
                    boundingBox = com.example.data.model.NormalizedRect(0.46f, 0.05f, 0.68f, 0.34f),
                    mouthActivity = 0.1f,
                ),
            ),
        )
        val leftObstacle = com.example.data.model.NormalizedRect(0.02f, 0.06f, 0.24f, 0.78f)
        val bottomChyron = com.example.data.model.NormalizedRect(0.06f, 0.72f, 0.74f, 0.95f)
        val event = com.example.data.model.DialogueEvent(
            id = "move_evt",
            startTimeMs = 500L,
            endTimeMs = 4_000L,
            sourceText = "Let's step over to the whiteboard!",
            translatedText = "Let's step over to the whiteboard!",
            speakerId = speaker.id,
            speakerConfidence = 0.95f,
            faceTrackId = movingTrack.id,
            faceConfidence = 0.92f,
            shotId = "shot_move",
            dialogueType = DialogueType.SPOKEN,
            placementMode = PlacementMode.AUTO,
            avoidBoxes = listOf(leftObstacle, bottomChyron),
        )
        val project = DemoScenarioFactory.createDemoProject().copy(
            speakers = listOf(speaker),
            faceTracks = listOf(movingTrack, topCrowdTrack),
            shots = listOf(com.example.data.model.Shot("shot_move", 0L, 10_000L, cutType = "CONTINUOUS")),
            dialogueEvents = listOf(event),
        )

        val engine = BalloonLayoutEngine()
        // Initial frame + moving frame (>= 0.024 delta on movingTrack)
        val frame1 = engine.computeLayout(project, 1_000L)
        assertEquals(1, frame1.size)
        val frame2 = engine.computeLayout(project, 1_500L)
        assertEquals(1, frame2.size)
        val placed2 = frame2.first()

        assertEquals(
            "Lowest-density screen edge must resolve to RIGHT when TOP/LEFT/BOTTOM are occupied",
            com.example.domain.layout.ScreenEdge.RIGHT,
            placed2.lowestDensityEdge,
        )
        assertTrue(
            "Moving-speaker bubble must anchor toward lowest-density RIGHT edge (rect=${placed2.rect})",
            placed2.rect.centerX > 0.62f,
        )
        assertEquals(0f, placed2.rect.intersectionArea(movingTrack.sampleAt(1_500L)!!.boundingBox), 0.0001f)
        assertEquals(0f, placed2.rect.intersectionArea(topCrowdTrack.sampleAt(1_500L)!!.boundingBox), 0.0001f)
    }

    @Test
    fun springTailContinuitySmoothlyInterpolatesTipAcrossEdgeShift() {
        val bodyRect = androidx.compose.ui.geometry.Rect(0f, 0f, 220f, 68f)
        val startMouth = androidx.compose.ui.geometry.Offset(-60f, 150f)
        val endMouth = androidx.compose.ui.geometry.Offset(290f, 145f)

        val samples = listOf(0f, 0.25f, 0.50f, 0.75f, 1f).map { t ->
            val tipWorld = androidx.compose.ui.geometry.Offset(
                x = startMouth.x + (endMouth.x - startMouth.x) * t,
                y = startMouth.y + (endMouth.y - startMouth.y) * t,
            )
            val aimRad = kotlin.math.atan2(
                (tipWorld.y - bodyRect.center.y).toDouble(),
                (tipWorld.x - bodyRect.center.x).toDouble(),
            ).toFloat()
            val dist = kotlin.math.hypot(
                (tipWorld.x - bodyRect.center.x).toDouble(),
                (tipWorld.y - bodyRect.center.y).toDouble(),
            ).toFloat()
            com.example.ui.player.computePerimeterTailGeometry(
                bodyRect = bodyRect,
                cornerRadius = 18f,
                aimAngleRad = aimRad,
                targetDistanceFromCenterPx = dist,
                halfThroatPx = 11f,
                mouthClearancePx = 12f,
                minTailReachPx = 13f,
                maxTailReachPx = 104f,
            )
        }

        // Tip X progresses monotonically without discontinuities, and every step preserves a connected open throat
        for (i in 0 until samples.size - 1) {
            assertTrue(
                "Interpolated tail tip X should progress smoothly from left to right (${samples[i].tipPoint.x} -> ${samples[i + 1].tipPoint.x})",
                samples[i + 1].tipPoint.x > samples[i].tipPoint.x,
            )
            assertFalse("Interpolated tail tip stays outside bubble body", bodyRect.contains(samples[i].tipPoint))
            assertTrue("Open throat stays connected (> 8px)", samples[i].effectiveHalfThroatPx >= 5.2f)
        }
        assertFalse("Final tail tip stays outside bubble body", bodyRect.contains(samples.last().tipPoint))
    }

    @Test
    fun contentVerifiedCaptionLockNeverStallsEmptyOrPlaceholderYouTubeTracks() {
        val planner = com.example.domain.analysis.LookaheadWindowPlanner
        val demo = DemoScenarioFactory.createDemoProject()

        // Regression guard: fresh YouTube project tagged EMBEDDED_TRACK with 0 cues MUST NOT lock pipeline!
        assertFalse(
            planner.hasLoadedSubtitlesLocked(
                subtitleSource = com.example.data.model.SubtitleSourceKind.EMBEDDED_TRACK,
                events = emptyList(),
            )
        )

        // Placeholder-only cue must also leave Tier 1 / Tier 2 unlocked
        val placeholderEvent = demo.dialogueEvents.first().copy(
            sourceText = "Embedded YouTube Player active — attach SRT/VTT or tap + Line in Timeline",
            translatedText = "Embedded YouTube Player active — attach SRT/VTT or tap + Line in Timeline",
        )
        assertFalse(
            planner.hasLoadedSubtitlesLocked(
                subtitleSource = com.example.data.model.SubtitleSourceKind.EMBEDDED_TRACK,
                events = listOf(placeholderEvent),
            )
        )

        // Real cues with EMBEDDED_TRACK or UPLOADED_FILE lock properly; AI_DRAFT stays unlocked for Free CC override
        assertTrue(
            planner.hasLoadedSubtitlesLocked(
                subtitleSource = com.example.data.model.SubtitleSourceKind.EMBEDDED_TRACK,
                events = demo.dialogueEvents,
            )
        )
        assertTrue(
            planner.hasLoadedSubtitlesLocked(
                subtitleSource = com.example.data.model.SubtitleSourceKind.UPLOADED_FILE,
                events = demo.dialogueEvents,
            )
        )
        assertFalse(
            planner.hasLoadedSubtitlesLocked(
                subtitleSource = com.example.data.model.SubtitleSourceKind.AI_DRAFT,
                events = demo.dialogueEvents,
            )
        )
    }

    @Test
    fun exportVttAndSpeakerPrefixedSrtFormatCleanlyForHomeScreenDownload() {
        val demo = DemoScenarioFactory.createDemoProject()
        val vtt = SubtitleParser.exportFormatted(
            project = demo,
            format = SubtitleParser.SubtitleExportFormat.VTT,
            includeSpeakerPrefixes = true,
        )
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains(" --> "))
        // VTT uses dot-millisecond format (HH:MM:SS.mmm)
        assertTrue(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} --> \\d{2}:\\d{2}:\\d{2}\\.\\d{3}").containsMatchIn(vtt))

        val srt = SubtitleParser.exportFormatted(
            project = demo,
            format = SubtitleParser.SubtitleExportFormat.SRT,
            includeSpeakerPrefixes = true,
        )
        assertTrue(Regex("\\d{2}:\\d{2}:\\d{2},\\d{3} --> \\d{2}:\\d{2}:\\d{2},\\d{3}").containsMatchIn(srt))
        assertTrue(srt.contains("[Kenji]") || srt.contains("[Sora]"))

        assertEquals(
            "Mayor_Mamdanis_Advice.srt",
            SubtitleParser.suggestedExportFilename("Mayor Mamdani's Advice!", SubtitleParser.SubtitleExportFormat.SRT),
        )
        assertEquals(
            "Mayor_Mamdanis_Advice.vtt",
            SubtitleParser.suggestedExportFilename("Mayor Mamdani's Advice!", SubtitleParser.SubtitleExportFormat.VTT),
        )
    }

    @Test
    fun sequentialPreGenWindowPlannerCoversFullTimelineWithoutGaps() {
        val windows = com.example.domain.analysis.LookaheadWindowPlanner.buildSequentialPreGenWindows(
            durationSec = 250,
            openingWindowSec = 60,
            rollingWindowSec = 90,
        )
        assertEquals(listOf(0 to 60, 60 to 150, 150 to 240, 240 to 250), windows)

        val uiDefaults = com.example.ui.state.DialogueLensUiState()
        assertEquals(com.example.ui.state.SubtitlePrepState.Idle, uiDefaults.subtitlePrepState)
        assertEquals(SubtitleParser.SubtitleExportFormat.SRT, uiDefaults.selectedPrepFormat)
    }

    @Test
    fun nativeCaptionPolicyPreservesSourceBeforeSelectingAnAvailableFallbackTier() {
        val planner = com.example.domain.analysis.LookaheadWindowPlanner
        val pro = com.example.data.model.QuotaSaverMode.PRO_ACCURACY
        val free = com.example.data.model.QuotaSaverMode.ZERO_CLOUD
        val draft = com.example.data.model.SubtitleSourceKind.AI_DRAFT
        val embedded = com.example.data.model.SubtitleSourceKind.EMBEDDED_TRACK
        val cue = DemoScenarioFactory.createDemoProject().dialogueEvents.first()
        assertTrue(planner.needsNativeTrack(draft, emptyList(), fullyVerified = false))
        assertTrue(planner.needsNativeTrack(draft, listOf(cue), fullyVerified = false))
        assertFalse(planner.needsNativeTrack(draft, listOf(cue), fullyVerified = true))
        assertFalse(planner.needsNativeTrack(embedded, listOf(cue), fullyVerified = false))
        assertTrue(planner.needsNativeTrack(embedded, emptyList(), fullyVerified = false))
        assertEquals(pro, planner.effectiveQuotaMode(pro, cloudConfigured = true))
        assertEquals(free, planner.effectiveQuotaMode(pro, cloudConfigured = false))
        assertEquals(free, planner.effectiveQuotaMode(free, cloudConfigured = true))
        val tier = planner.initialExecutionTier(pro, quotaCircuitBreakerTripped = false)
        assertEquals(com.example.domain.analysis.ScoutExecutionTier.BEST_AI, tier)
        val lite = planner.nextFallbackTierOnQuotaError(tier)
        assertEquals(com.example.domain.analysis.ScoutExecutionTier.LITE_AI, lite)
        assertEquals(com.example.domain.analysis.ScoutExecutionTier.FREE_LOCAL, planner.nextFallbackTierOnQuotaError(lite))
    }

    @Test
    fun nativeCaptionsKeepVerifiedCuesAndManualCorrectionsWithTheirOriginalIds() {
        val template = DemoScenarioFactory.createDemoProject().dialogueEvents.first()
        fun line(id: String, start: Long, locked: Boolean = false) =
            template.copy(id = id, startTimeMs = start, endTimeMs = start + 500L, lockedByUser = locked)
        val old = listOf(line("verified", 0L), line("locked", 600L, true), line("edited", 1_200L), line("draft", 1_800L))
        val native = old.map { it.copy(id = "cc-" + it.id) } + line("new", 3_000L)
        val merged = com.example.domain.analysis.LookaheadWindowPlanner.mergeNativeTrack(
            old, native, listOf(com.example.data.model.CoverageInterval(0L, 500L, com.example.data.model.CoverageStatus.VERIFIED_DIALOGUE)),
            setOf("edited"),
        )
        assertEquals(listOf("verified", "locked", "edited", "cc-draft", "new"), merged.map { it.id })
    }

    @Test
    fun captionAccessHonorsRestrictionsAndBindsFullTracksToTheirVideo() {
        val fetcher = com.example.data.subtitle.YouTubeCaptionFetcher
        val result = fetcher.extractMetaFromPlayerMap(mapOf("playabilityStatus" to mapOf("status" to "LOGIN_REQUIRED")))
        assertTrue(result.restricted)
        assertTrue(result.tracks.isEmpty())
        val full = "https://www.youtube.com/api/timedtext?v=abc&lang=en"
        assertTrue(fetcher.isWholeTrackForVideo(full, "abc"))
        assertFalse(fetcher.isWholeTrackForVideo(full, "other"))
        assertFalse(fetcher.isWholeTrackForVideo(full + "&sq=1", "abc"))
        assertFalse(fetcher.isPublicCaptionUrl("https://youtube.com.user.example/api/timedtext"))
    }

    @Test
    fun lookaheadWindowPlannerCoversLongEpisodesAndPrioritizesSeekTargets() {
        // 18:15 (1095s) episode with default 35s opening + 40s rolling windows covers entire timeline without stopping at 2:30
        val windows = com.example.domain.analysis.LookaheadWindowPlanner.buildSequentialPreGenWindows(durationSec = 1095)
        assertEquals(0 to 35, windows.first())
        assertEquals(1095, windows.last().second)
        assertTrue(windows.size >= 27)
        // Verify contiguous coverage with zero gaps
        for (i in 1 until windows.size) {
            assertEquals(windows[i - 1].second, windows[i].first)
        }

        // Seeking forward to 10:15 (615s) immediately prioritizes the window containing 615s, then forward, then earlier backfill
        val ordered = com.example.domain.analysis.LookaheadWindowPlanner.buildLookaheadPriorityOrder(
            windows = windows,
            completedStarts = setOf(0, 35, 75),
            inFlightStarts = setOf(115),
            playheadSec = 615,
        )
        val firstWin = ordered.first()
        assertTrue(615 in firstWin.first until firstWin.second)
        // Subsequent items advance forward monotonically until reaching video end before backfilling < 615s
        val secondWin = ordered[1]
        assertTrue(secondWin.first > firstWin.first)
        assertTrue(ordered.none { it.first in setOf(0, 35, 75, 115) })
    }

    @Test
    fun resilientSegmentExecutorRecoversFromUnexpectedServerFaultViaBisectionAndIsolatesGaps() = kotlinx.coroutines.runBlocking {
        val heartbeat = com.example.data.ai.ScoutHeartbeatService(probeFlashLite = { Result.success(12L) })
        val executor = com.example.domain.analysis.ResilientYouTubeSegmentExecutor(heartbeat)

        // Simulate 02:30–03:10 (150s..190s) failing with "Something unexpected happened." on full 40s window and 170s..180s leaf,
        // rescued by Flash-Lite on 170s..180s, and clean on remaining bisected slices!
        val seenCalls = mutableListOf<Triple<Int, Int, Boolean>>()
        val outcome = executor.executeSegment(
            startSec = 150,
            endSec = 190,
            initialTier = com.example.domain.analysis.ScoutExecutionTier.BEST_AI,
        ) { s, e, preferLite ->
            seenCalls.add(Triple(s, e, preferLite))
            if ((e - s) > 20) {
                Result.failure(IllegalStateException("Something unexpected happened."))
            } else if (s == 170 && e == 190) {
                Result.failure(IllegalStateException("Something unexpected happened."))
            } else if (s == 170 && e == 180 && !preferLite) {
                Result.failure(IllegalStateException("Something unexpected happened."))
            } else {
                Result.success(
                    com.example.data.ai.AiYouTubeTranscriptionResult(
                        cues = listOf(
                            com.example.data.ai.AiTranscribedCue(
                                startMs = s * 1000L + 400L,
                                endMs = s * 1000L + 2200L,
                                sourceText = "Recovered slice $s-$e",
                                naturalTranslation = "Recovered slice $s-$e",
                                literalTranslation = "Recovered slice $s-$e",
                                romanization = "",
                                dialogueType = DialogueType.SPOKEN,
                                suggestedSpeakerSlot = "A",
                                contextReason = "",
                                culturalNote = "",
                            )
                        )
                    )
                )
            }
        }

        assertTrue(outcome is com.example.domain.analysis.SegmentExecutionOutcome.Completed)
        val completed = outcome as com.example.domain.analysis.SegmentExecutionOutcome.Completed
        assertTrue(completed.usedBisection)
        assertTrue(completed.failedRanges.isEmpty())
        assertEquals(3, completed.cues.size)
        assertTrue(completed.cues.any { it.sourceText.contains("150-170") })
        assertTrue(completed.cues.any { it.sourceText.contains("170-180") })
        assertTrue(completed.cues.any { it.sourceText.contains("180-190") })
        // Leaf slice 170..180 fell back to Flash-Lite without permanently downgrading later windows
        assertTrue(seenCalls.any { it.first == 170 && it.second == 180 && it.third })
        assertFalse(completed.downgradedToLite)
    }

    @Test
    fun liveMlKitMouthTrackingMovesTailSmoothlyWithoutDeadzoneFreezeAndDetectsCutaways() {
        val engine = BalloonLayoutEngine()
        val project = com.example.data.model.DialogueLensProject(
            id = "live_track_test",
            title = "Live Track",
            createdAtEpochMs = 0L,
            sourceLanguage = "English",
            targetLanguage = "English",
            videoSource = com.example.data.model.VideoSource(
                uriString = "https://youtu.be/test",
                displayName = "YT",
                kind = com.example.data.model.VideoSourceKind.YOUTUBE_COMPLIANT,
                durationMs = 30_000L,
                width = 1920,
                height = 1080,
            ),
            shots = listOf(com.example.data.model.Shot("shot_1", 0L, 30_000L, cutType = "EMBEDDED_YOUTUBE")),
            speakers = listOf(
                com.example.data.model.Speaker(
                    id = "spk_a",
                    defaultLabel = "Speaker A",
                    customName = "Roy Wood Jr.",
                    accentIndex = 0,
                    isIdentityConfirmed = true,
                ),
            ),
            faceTracks = emptyList(),
            dialogueEvents = listOf(
                com.example.data.model.DialogueEvent(
                    id = "evt_live",
                    startTimeMs = 1_000L,
                    endTimeMs = 5_000L,
                    sourceText = "Welcome back!",
                    translatedText = "Welcome back!",
                    speakerId = "spk_a",
                    speakerConfidence = 0.90f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "shot_1",
                    dialogueType = DialogueType.SPOKEN,
                    anchorPosition = com.example.data.model.NormalizedPoint(0.26f, 0.38f),
                    placementMode = PlacementMode.AUTO,
                )
            ),
        )

        // Frame 1: live ML Kit face slightly right of initial cue anchor (0.28f mouth vs 0.26f static anchor)
        val liveFrame1 = listOf(
            com.example.domain.analysis.LiveFaceObservation(
                trackingId = 7,
                boundingBox = com.example.data.model.NormalizedRect(0.21f, 0.22f, 0.35f, 0.48f),
                mouthPoint = com.example.data.model.NormalizedPoint(0.28f, 0.41f),
                headYawDeg = -4f,
                capturedAtMs = 1_500L,
            )
        )
        val b1 = engine.computeLayout(project, 1_500L, liveFaces = liveFrame1).first()
        assertFalse(b1.isOffScreenEdge)
        assertNotNull(b1.tailTarget)
        assertTrue(kotlin.math.abs(b1.tailTarget!!.x - 0.28f) < 0.03f)

        // Frame 2: subtle +0.008 head movement must update tail target directly (no 0.025 deadzone freeze)
        val liveFrame2 = listOf(
            com.example.domain.analysis.LiveFaceObservation(
                trackingId = 7,
                boundingBox = com.example.data.model.NormalizedRect(0.218f, 0.22f, 0.358f, 0.48f),
                mouthPoint = com.example.data.model.NormalizedPoint(0.288f, 0.412f),
                headYawDeg = 2f,
                capturedAtMs = 1_660L,
            )
        )
        val b2 = engine.computeLayout(project, 1_660L, liveFaces = liveFrame2).first()
        assertNotNull(b2.tailTarget)
        assertTrue(b2.tailTarget!!.x > b1.tailTarget!!.x)

        // Frame 3: camera cuts away to silent listener on far right (0.78f); tail must NOT snap onto wrong person!
        val cutawayFrame = listOf(
            com.example.domain.analysis.LiveFaceObservation(
                trackingId = 99,
                boundingBox = com.example.data.model.NormalizedRect(0.70f, 0.20f, 0.86f, 0.48f),
                mouthPoint = com.example.data.model.NormalizedPoint(0.78f, 0.40f),
                headYawDeg = 12f,
                capturedAtMs = 2_000L,
            )
        )
        val bCutaway = engine.computeLayout(project, 2_000L, liveFaces = cutawayFrame).first()
        assertTrue(bCutaway.isOffScreenEdge)
        assertEquals(PlacementMode.EDGE_DOCKED, bCutaway.placementMode)
        assertEquals(DialogueType.OFF_SCREEN_LEFT, bCutaway.edgeSide)
    }

    @Test
    fun exportTranslationsSupportsSrtVttAssWithTranslationModeAndSpeakerToggle() {
        val project = com.example.data.model.DialogueLensProject(
            id = "export_modes_test",
            title = "Tokyo Station Scene",
            createdAtEpochMs = 0L,
            sourceLanguage = "Japanese",
            targetLanguage = "English",
            videoSource = com.example.data.model.VideoSource(
                uriString = "https://youtu.be/test",
                displayName = "Scene",
                kind = com.example.data.model.VideoSourceKind.YOUTUBE_COMPLIANT,
                durationMs = 10_000L,
                width = 1920,
                height = 1080,
            ),
            shots = listOf(com.example.data.model.Shot("shot_1", 0L, 10_000L)),
            speakers = listOf(
                com.example.data.model.Speaker(
                    id = "spk_kenji",
                    defaultLabel = "Speaker A",
                    customName = "Kenji",
                    accentIndex = 0,
                    isIdentityConfirmed = true,
                )
            ),
            faceTracks = emptyList(),
            dialogueEvents = listOf(
                com.example.data.model.DialogueEvent(
                    id = "evt_1",
                    startTimeMs = 1_200L,
                    endTimeMs = 3_600L,
                    sourceText = "雨が強くなってきたね。",
                    translatedText = "The rain is picking up.",
                    literalText = "Rain has become strong, right.",
                    romanization = "Ame ga tsuyoku natte kita ne.",
                    speakerId = "spk_kenji",
                    speakerConfidence = 0.92f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "shot_1",
                )
            ),
        )

        val srtLiteralNoSpeaker = SubtitleParser.exportFormatted(
            project = project,
            format = SubtitleParser.SubtitleExportFormat.SRT,
            includeSpeakerPrefixes = false,
            translationMode = com.example.data.model.TranslationMode.LITERAL,
        )
        assertTrue(srtLiteralNoSpeaker.contains("Rain has become strong, right."))
        assertFalse(srtLiteralNoSpeaker.contains("[Kenji]"))

        val vttLearningWithSpeaker = SubtitleParser.exportFormatted(
            project = project,
            format = SubtitleParser.SubtitleExportFormat.VTT,
            includeSpeakerPrefixes = true,
            translationMode = com.example.data.model.TranslationMode.LEARNING,
        )
        assertTrue(vttLearningWithSpeaker.startsWith("WEBVTT\n\n"))
        assertTrue(vttLearningWithSpeaker.contains("[Kenji] The rain is picking up."))
        assertTrue(vttLearningWithSpeaker.contains("(Ame ga tsuyoku natte kita ne.)"))

        val assLiteral = SubtitleParser.exportFormatted(
            project = project,
            format = SubtitleParser.SubtitleExportFormat.ASS,
            includeSpeakerPrefixes = true,
            translationMode = com.example.data.model.TranslationMode.LITERAL,
        )
        assertTrue(assLiteral.contains("[Script Info]"))
        assertTrue(assLiteral.contains("Style: Speaker_spk_kenji"))
        assertTrue(assLiteral.contains("Rain has become strong, right."))

        val preview = SubtitleParser.exportPreview(
            project = project,
            format = SubtitleParser.SubtitleExportFormat.SRT,
            includeSpeakerPrefixes = true,
            translationMode = com.example.data.model.TranslationMode.NATURAL,
            maxCues = 1,
        )
        assertTrue(preview.contains("[Kenji] The rain is picking up."))
    }

    @Test
    fun durationScaledLeadTimeWorkersAndCatchUpBufferPauseBehaveProperly() {
        val longDurationMs = 1_094_400L // 18:14.4
        assertEquals(3, com.example.domain.analysis.LookaheadWindowPlanner.recommendedOpeningLeadWindows(longDurationMs))
        assertEquals(42_000L, com.example.domain.analysis.LookaheadWindowPlanner.recommendedIntroMaxDurationMs(longDurationMs))
        assertEquals(4, com.example.domain.analysis.LookaheadWindowPlanner.recommendedWorkerCount(longDurationMs))

        assertEquals(1, com.example.domain.analysis.LookaheadWindowPlanner.recommendedOpeningLeadWindows(90_000L))
        assertEquals(2, com.example.domain.analysis.LookaheadWindowPlanner.recommendedOpeningLeadWindows(300_000L))
        assertEquals(3, com.example.domain.analysis.LookaheadWindowPlanner.recommendedWorkerCount(300_000L))

        // Long 18m video holds adaptive intro until all 3 opening lead windows complete (or 42s max ceiling hits)
        assertFalse(
            com.example.domain.analysis.LookaheadWindowPlanner.shouldAutoReleaseAdaptiveIntro(
                elapsedMs = 16_000L,
                scoutPosMs = 12_000L,
                hasRealSubtitles = true,
                openingWindowRefined = true,
                durationMs = longDurationMs,
                completedOpeningWindows = 1,
                requiredOpeningWindows = 3,
            )
        )
        assertTrue(
            com.example.domain.analysis.LookaheadWindowPlanner.shouldAutoReleaseAdaptiveIntro(
                elapsedMs = 16_000L,
                scoutPosMs = 12_000L,
                hasRealSubtitles = true,
                openingWindowRefined = true,
                durationMs = longDurationMs,
                completedOpeningWindows = 3,
                requiredOpeningWindows = 3,
            )
        )

        // Playhead entering an unbuffered gap while Scout is active triggers brief catch-up wait only when no cue covers it
        val buffered = listOf(com.example.domain.analysis.TimeWindowRange(0L, 150_000L))
        val events = listOf(
            com.example.data.model.DialogueEvent(
                id = "e1",
                startTimeMs = 140_000L,
                endTimeMs = 145_000L,
                sourceText = "Line before gap",
                translatedText = "Line before gap",
                speakerId = "spk_a",
                speakerConfidence = 0.90f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            )
        )
        assertFalse(
            com.example.domain.analysis.LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 142_000L,
                totalDurationMs = longDurationMs,
                bufferedRanges = buffered,
                events = events,
                isScoutActive = true,
            )
        )
        assertTrue(
            com.example.domain.analysis.LookaheadWindowPlanner.isPlayheadWaitingOnUnbufferedWindow(
                playheadMs = 152_000L,
                totalDurationMs = longDurationMs,
                bufferedRanges = buffered,
                events = events,
                isScoutActive = true,
            )
        )
    }

    @Test
    fun chunkRelativeTimestampOffsetAndLookbackSeamMergePreventSkippedSubtitles() {
        // 0-based timestamps returned for window 155..195s get shifted by +155.0s instead of dropped
        val shifted = com.example.data.ai.TranslationAiService.resolveYouTubeTimestampOffsetSec(
            rawStartSecs = listOf(1.2, 5.8, 14.4, 28.0),
            windowStartSec = 155,
            windowEndSec = 195,
        )
        assertEquals(155.0, shifted, 0.001)

        // Already-absolute timestamps remain unshifted
        val unshifted = com.example.data.ai.TranslationAiService.resolveYouTubeTimestampOffsetSec(
            rawStartSecs = listOf(156.2, 161.0, 179.4),
            windowStartSec = 155,
            windowEndSec = 195,
        )
        assertEquals(0.0, unshifted, 0.001)

        // Lookback merge rescues missed pre-boundary cue (113.5s) while deduplicating punctuation variants at seam (114.8s)
        val existing = listOf(
            com.example.data.model.DialogueEvent(
                id = "win1_end",
                startTimeMs = 114_400L,
                endTimeMs = 115_100L,
                sourceText = "Welcome to the show!",
                translatedText = "Welcome to the show!",
                speakerId = "spk_a",
                speakerConfidence = 0.90f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            )
        )
        val incoming = listOf(
            com.example.data.model.DialogueEvent(
                id = "lookback_rescue",
                startTimeMs = 111_500L,
                endTimeMs = 113_000L,
                sourceText = "Before we start,",
                translatedText = "Before we start,",
                speakerId = "spk_a",
                speakerConfidence = 0.90f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            ),
            com.example.data.model.DialogueEvent(
                id = "dup_seam",
                startTimeMs = 114_800L,
                endTimeMs = 115_300L,
                sourceText = "Welcome to the show",
                translatedText = "Welcome to the show",
                speakerId = "spk_a",
                speakerConfidence = 0.90f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            ),
            com.example.data.model.DialogueEvent(
                id = "win2_next",
                startTimeMs = 116_000L,
                endTimeMs = 119_000L,
                sourceText = "Here is our panel tonight.",
                translatedText = "Here is our panel tonight.",
                speakerId = "spk_a",
                speakerConfidence = 0.90f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            ),
        )
        val merged = com.example.domain.analysis.LookaheadWindowPlanner.mergeWindowDialogueEvents(
            existing = existing,
            incoming = incoming,
            windowStartMs = 115_000L,
            windowEndMs = 155_000L,
        )
        assertEquals(3, merged.size)
        assertTrue(merged.any { it.id == "lookback_rescue" })
        assertTrue(merged.any { it.id == "win1_end" })
        assertTrue(merged.any { it.id == "win2_next" })
        assertFalse(merged.any { it.id == "dup_seam" })
    }

    @Test
    fun mouthMatchingOverridesChestBiasHeadGuardProtectsFrameZeroAndCenterCutawayAvoidsSilentBystander() {
        val engine = BalloonLayoutEngine()
        val shot = com.example.data.model.Shot("shot_studio", 0L, 30_000L, cutType = "EMBEDDED_YOUTUBE")
        val centerHost = com.example.data.model.Speaker(
            id = "spk_host",
            defaultLabel = "Speaker A",
            customName = "Roy Wood Jr.",
            accentIndex = 0,
            isIdentityConfirmed = true,
            preferredScreenSideX = 0.50f,
        )
        val chestBiasedCue = com.example.data.model.DialogueEvent(
            id = "evt_chest_anchor",
            startTimeMs = 1_000L,
            endTimeMs = 5_000L,
            sourceText = "Tonight on the show!",
            translatedText = "Tonight on the show!",
            speakerId = "spk_host",
            speakerConfidence = 0.92f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "shot_studio",
            dialogueType = DialogueType.SPOKEN,
            // Chest-level Gemini anchor (y=0.50) even though speaker's lips are higher (y=0.31)
            anchorPosition = com.example.data.model.NormalizedPoint(0.50f, 0.50f),
            placementMode = PlacementMode.AUTO,
        )
        val project = com.example.data.model.DialogueLensProject(
            id = "alignment_safety_test",
            title = "Studio Test",
            createdAtEpochMs = 0L,
            sourceLanguage = "English",
            targetLanguage = "English",
            videoSource = com.example.data.model.VideoSource(
                uriString = "https://youtu.be/test",
                displayName = "YT",
                kind = com.example.data.model.VideoSourceKind.YOUTUBE_COMPLIANT,
                durationMs = 30_000L,
                width = 1920,
                height = 1080,
            ),
            shots = listOf(shot),
            speakers = listOf(centerHost),
            faceTracks = emptyList(),
            dialogueEvents = listOf(chestBiasedCue),
        )

        // 1. Frame 0 without ML Kit: upperPortraitHeadGuard prevents bubble from covering speaker head column [0.365..0.635] x [0.05..0.40]
        val frameZeroBalloon = engine.computeLayout(project, 1_500L, liveFaces = emptyList()).first()
        val headColumn = com.example.data.model.NormalizedRect(0.38f, 0.08f, 0.62f, 0.36f)
        assertEquals(0f, frameZeroBalloon.rect.intersectionArea(headColumn), 0.001f)

        // 2. Live ML Kit detects actual face above the chest-biased anchor -> tail MUST snap to lips at y=0.31f!
        val hostFace = com.example.domain.analysis.LiveFaceObservation(
            trackingId = 10,
            boundingBox = com.example.data.model.NormalizedRect(0.43f, 0.14f, 0.57f, 0.36f),
            mouthPoint = com.example.data.model.NormalizedPoint(0.50f, 0.31f),
            headYawDeg = 0f,
            capturedAtMs = 1_800L,
        )
        val liveMatchedBalloon = engine.computeLayout(project, 1_800L, liveFaces = listOf(hostFace)).first()
        assertFalse(liveMatchedBalloon.isOffScreenEdge)
        assertNotNull(liveMatchedBalloon.tailTarget)
        assertTrue(liveMatchedBalloon.tailTarget!!.y < 0.35f)

        // 3. Cutaway where only a silent right panelist (0.82f) is on camera while center host (0.50f) is speaking -> MUST dock off-screen
        engine.clearSmoothing()
        val silentPanelistOnly = com.example.domain.analysis.LiveFaceObservation(
            trackingId = 22,
            boundingBox = com.example.data.model.NormalizedRect(0.75f, 0.18f, 0.89f, 0.44f),
            mouthPoint = com.example.data.model.NormalizedPoint(0.82f, 0.38f),
            headYawDeg = -8f,
            capturedAtMs = 2_200L,
        )
        val cutawayBalloon = engine.computeLayout(project, 2_200L, liveFaces = listOf(silentPanelistOnly)).first()
        assertTrue(cutawayBalloon.isOffScreenEdge)
        assertEquals(DialogueType.OFF_SCREEN_BEHIND, cutawayBalloon.edgeSide)
    }

    @Test
    fun webNavigationGuardBlocksPopupsAndCrossSiteRedirectsAndAutoSyncsStuckScout() {
        val seed = "https://missav.ws/en/abc-123"
        assertEquals("missav.ws", com.example.domain.analysis.WebNavigationGuard.extractHost(seed))
        assertEquals("bbc.co.uk", com.example.domain.analysis.WebNavigationGuard.registrableDomain("www.news.bbc.co.uk"))

        // Same-site navigation stays allowed
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.evaluateTopFrameNavigation(
                seedUrl = seed,
                targetUrl = "https://m.missav.ws/en/abc-123?page=2",
                isRedirect = false,
                hasUserGesture = true,
            ) is com.example.domain.analysis.RedirectDecision.Allow
        )

        // Known cam/ad redirects blocked even when click hijack sets hasUserGesture = true
        val adBlock = com.example.domain.analysis.WebNavigationGuard.evaluateTopFrameNavigation(
            seedUrl = seed,
            targetUrl = "https://stripchat.com/landing?utm_source=pop",
            isRedirect = false,
            hasUserGesture = true,
        )
        assertTrue(adBlock is com.example.domain.analysis.RedirectDecision.Block)

        // External app schemes (intent://, market://) blocked immediately
        val schemeBlock = com.example.domain.analysis.WebNavigationGuard.evaluateTopFrameNavigation(
            seedUrl = seed,
            targetUrl = "intent://details?id=com.ad#Intent;end",
        )
        assertTrue(schemeBlock is com.example.domain.analysis.RedirectDecision.Block)

        // Foreign cross-site redirect blocked by default, allowed ONLY with temporary Allow Once whitelist
        val mirrorUrl = "https://cdn-mirror-player.net/watch/abc-123"
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.evaluateTopFrameNavigation(
                seedUrl = seed,
                targetUrl = mirrorUrl,
                isRedirect = true,
                hasUserGesture = true,
            ) is com.example.domain.analysis.RedirectDecision.Block
        )
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.evaluateTopFrameNavigation(
                seedUrl = seed,
                targetUrl = mirrorUrl,
                isRedirect = true,
                hasUserGesture = true,
                temporarilyAllowedHosts = setOf("cdn-mirror-player.net"),
            ) is com.example.domain.analysis.RedirectDecision.Allow
        )

        // Viewer playing cleanly triggers auto-sync when Scout landed on an ad or stalled without <video>
        val healthyViewer = com.example.domain.analysis.WebNavigationState(
            currentUrl = seed,
            currentHost = "missav.ws",
            hasActiveVideo = true,
            isAdRedirectSuspected = false,
        )
        val stuckScout = com.example.domain.analysis.WebNavigationState(
            currentUrl = "https://stripchat.com/landing",
            currentHost = "stripchat.com",
            hasActiveVideo = false,
            isAdRedirectSuspected = true,
        )
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.shouldAutoSyncScoutFromViewer(
                viewerNav = healthyViewer,
                scoutNav = stuckScout,
                scoutPosMs = 0L,
                viewerPosMs = 2_400L,
                lastAutoSyncAtMs = 0L,
                nowMs = 15_000L,
            )
        )
        // Cooldown prevents thrashing
        assertFalse(
            com.example.domain.analysis.WebNavigationGuard.shouldAutoSyncScoutFromViewer(
                viewerNav = healthyViewer,
                scoutNav = stuckScout,
                scoutPosMs = 0L,
                viewerPosMs = 2_600L,
                lastAutoSyncAtMs = 12_000L,
                nowMs = 15_000L,
            )
        )

        // Third-party ad/popunder scripts and /go/ redirectors are blocked while video CDN & first-party assets pass
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.shouldBlockSubresource(
                seedUrl = seed,
                resourceUrl = "https://a.exosrv.com/popunder-v3.js",
            )
        )
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.shouldBlockSubresource(
                seedUrl = seed,
                resourceUrl = "https://cdn.xlivrdr.com/bundle.js?zone_id=1",
            )
        )
        assertFalse(
            com.example.domain.analysis.WebNavigationGuard.shouldBlockSubresource(
                seedUrl = seed,
                resourceUrl = "https://missav.ws/static/player.js",
            )
        )
        assertFalse(
            com.example.domain.analysis.WebNavigationGuard.shouldBlockSubresource(
                seedUrl = seed,
                resourceUrl = "https://surrit.com/hls/720p/video.m3u8",
            )
        )
        assertTrue(com.example.domain.analysis.WebNavigationGuard.isLikelyRedirectorUrl("https://missav.ws/go/strip?aff=12"))
        assertFalse(com.example.domain.analysis.WebNavigationGuard.isLikelyRedirectorUrl(seed))
    }

    @Test
    fun timelineCoalescingTimestampOverlapFixAndWorkerSlotReconciliationWorkTogether() {
        // 1. Contiguous windows coalesce into ONE smooth tube, while unhealed failed slice creates an explicit gap until healed
        val rawWindows = listOf(
            com.example.domain.analysis.TimeWindowRange(0L, 35_000L),
            com.example.domain.analysis.TimeWindowRange(35_000L, 75_000L),
            com.example.domain.analysis.TimeWindowRange(75_000L, 115_000L),
            com.example.domain.analysis.TimeWindowRange(115_000L, 155_000L),
        )
        val mergedTube = com.example.domain.analysis.LookaheadWindowPlanner.coalesceBufferedRanges(rawWindows)
        assertEquals(1, mergedTube.size)
        assertEquals(0L, mergedTube.first().startMs)
        assertEquals(155_000L, mergedTube.first().endMs)

        val withHole = com.example.domain.analysis.LookaheadWindowPlanner.subtractFailedSlices(
            completedRanges = rawWindows,
            failedSlices = listOf(com.example.domain.analysis.TimeWindowRange(75_000L, 95_000L)),
        )
        assertEquals(2, withHole.size)
        assertEquals(0L, withHole[0].startMs)
        assertEquals(75_000L, withHole[0].endMs)
        assertEquals(95_000L, withHole[1].startMs)
        assertEquals(155_000L, withHole[1].endMs)

        // 2. Window 35..75s with 0-based timestamps 1.2..38.0s must shift by +35.0s even though 38.0 overlaps [33..79]
        val secondWindowOffset = com.example.data.ai.TranslationAiService.resolveYouTubeTimestampOffsetSec(
            rawStartSecs = listOf(1.2, 7.5, 16.0, 24.5, 38.0),
            windowStartSec = 35,
            windowEndSec = 75,
        )
        assertEquals(35.0, secondWindowOffset, 0.001)

        // 3. Window merge drops spillover cues whose startTimeMs >= windowEndMs
        val spilloverMerged = com.example.domain.analysis.LookaheadWindowPlanner.mergeWindowDialogueEvents(
            existing = emptyList(),
            incoming = listOf(
                com.example.data.model.DialogueEvent(
                    id = "inside",
                    startTimeMs = 71_000L,
                    endTimeMs = 74_200L,
                    sourceText = "Inside cue",
                    translatedText = "Inside cue",
                    speakerId = "spk_a",
                    speakerConfidence = 0.90f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "s1",
                ),
                com.example.data.model.DialogueEvent(
                    id = "spill",
                    startTimeMs = 75_400L,
                    endTimeMs = 78_000L,
                    sourceText = "Spilled into next section",
                    translatedText = "Spilled into next section",
                    speakerId = "spk_a",
                    speakerConfidence = 0.90f,
                    faceTrackId = null,
                    faceConfidence = 0f,
                    shotId = "s1",
                ),
            ),
            windowStartMs = 35_000L,
            windowEndMs = 75_000L,
        )
        assertEquals(listOf("inside"), spilloverMerged.map { it.id })

        // 4. Parallel worker speaker slot reconciliation aligns Roy Wood Jr. -> A and moves colliding newcomer Michael Ian Black -> C
        val existingRoster = listOf(
            com.example.data.model.Speaker(
                id = "spk_a",
                defaultLabel = "Speaker A",
                customName = "Roy Wood Jr.",
                visualVoiceNotes = "Host at center desk, low voice",
                accentIndex = 0,
                isIdentityConfirmed = true,
            ),
            com.example.data.model.Speaker(
                id = "spk_b",
                defaultLabel = "Speaker B",
                customName = "Amber Ruffin",
                accentIndex = 1,
                isIdentityConfirmed = true,
            ),
        )
        val incomingAssignments = listOf(
            com.example.data.ai.AiCastSlotAssignment(
                slot = "A",
                displayName = "Michael Ian Black",
                isRolePlaceholder = false,
                isVisuallyConfirmed = true,
            ),
            com.example.data.ai.AiCastSlotAssignment(
                slot = "B",
                displayName = "Roy Wood Jr.",
                visualVoiceNotes = "Host at center desk, low voice",
                isRolePlaceholder = false,
                isVisuallyConfirmed = true,
            ),
        )
        val incomingCues = listOf(
            com.example.data.ai.AiTranscribedCue(
                startMs = 146_000L,
                endMs = 149_000L,
                sourceText = "Uh, Democrats winning and doing a good job, maybe?",
                naturalTranslation = "Uh, Democrats winning and doing a good job, maybe?",
                literalTranslation = "",
                romanization = "",
                dialogueType = DialogueType.SPOKEN,
                suggestedSpeakerSlot = "B",
                contextReason = "Host speaking on camera",
                culturalNote = "",
            ),
            com.example.data.ai.AiTranscribedCue(
                startMs = 150_000L,
                endMs = 153_000L,
                sourceText = "That's one theory.",
                naturalTranslation = "That's one theory.",
                literalTranslation = "",
                romanization = "",
                dialogueType = DialogueType.SPOKEN,
                suggestedSpeakerSlot = "A",
                contextReason = "Panelist speaking",
                culturalNote = "",
            ),
        )

        val (reconciledCues, reconciledUpdates, remap) = com.example.ui.state.reconcileWindowOutcomeSpeakersAndCues(
            existingSpeakers = existingRoster,
            cues = incomingCues,
            speakerUpdates = incomingAssignments,
        )
        assertEquals("A", remap["B"])
        assertEquals("C", remap["A"])
        assertEquals("A", reconciledCues[0].suggestedSpeakerSlot)
        assertEquals("C", reconciledCues[1].suggestedSpeakerSlot)
        assertEquals(setOf("A", "C"), reconciledUpdates.map { it.slot }.toSet())
        assertEquals("Roy Wood Jr.", reconciledUpdates.first { it.slot == "A" }.displayName)
        assertEquals("Michael Ian Black", reconciledUpdates.first { it.slot == "C" }.displayName)
    }

    @Test
    fun subtitleTimelineMatcherAndPlaybackClockEliminateSkippingAndDrift() {
        val matcher = com.example.domain.analysis.SubtitleTimelineMatcher(frameToleranceMs = 18L)
        val longSfx = com.example.data.model.DialogueEvent(
            id = "sfx_bg",
            startTimeMs = 0L,
            endTimeMs = 20_000L,
            sourceText = "[ambient rain]",
            translatedText = "[ambient rain]",
            speakerId = null,
            speakerConfidence = 0.9f,
            faceTrackId = null,
            faceConfidence = 0f,
            shotId = "s1",
            dialogueType = DialogueType.SOUND_DESCRIPTION,
        )
        val spokenCues = (0 until 12).map { idx ->
            val start = 500L + idx * 1_000L
            com.example.data.model.DialogueEvent(
                id = "cue_$idx",
                startTimeMs = start,
                endTimeMs = start + 850L,
                sourceText = "Line $idx",
                translatedText = "Line $idx",
                speakerId = "spk_a",
                speakerConfidence = 0.9f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            )
        }
        val allEvents = listOf(longSfx) + spokenCues

        // 1. Sequential 60fps (16ms) sweep hits EVERY cue cleanly with zero skipped cues
        val seenCueIds = linkedSetOf<String>()
        var t = 0L
        while (t <= 12_500L) {
            val active = matcher.matchActiveEvents(allEvents, t)
            active.forEach { seenCueIds.add(it.id) }
            t += 16L
        }
        assertTrue(seenCueIds.contains("sfx_bg"))
        assertEquals((0 until 12).map { "cue_$it" }.toSet(), seenCueIds.filter { it.startsWith("cue_") }.toSet())

        // 2. Boundary tolerance (+/-18ms) catches edge frames without dropping cue visibility
        val edgeOnly = listOf(spokenCues.first()) // 500..1350ms
        val edgeMatcher = com.example.domain.analysis.SubtitleTimelineMatcher(frameToleranceMs = 18L)
        assertEquals(listOf("cue_0"), edgeMatcher.matchActiveEvents(edgeOnly, 488L).map { it.id })
        assertEquals(listOf("cue_0"), edgeMatcher.matchActiveEvents(edgeOnly, 1362L).map { it.id })
        assertTrue(edgeMatcher.matchActiveEvents(edgeOnly, 1400L).isEmpty())

        // 3. Jumping forward & seeking backward still preserves long overlapping SFX cue AND target spoken cue
        val jumpForward = matcher.matchActiveEvents(allEvents, 9_700L).map { it.id }.toSet()
        assertEquals(setOf("sfx_bg", "cue_9"), jumpForward)
        val seekBack = matcher.matchActiveEvents(allEvents, 2_750L).map { it.id }.toSet()
        assertEquals(setOf("sfx_bg", "cue_2"), seekBack)

        // 4. PlaybackClockAnchor fills short ExoPlayer plateaus (<=48ms) and interpolates Web/YouTube smoothly at 1.5x
        val clock = com.example.domain.analysis.PlaybackClockAnchor()
        val t0 = 1_000_000_000L
        clock.reset(positionMs = 5_000L, speed = 1.0f, playing = true, nowNs = t0)
        assertEquals(5_000L, clock.sampleLocalExoPosition(5_000L, 1.0f, true, 60_000L, nowNs = t0))
        assertEquals(5_016L, clock.sampleLocalExoPosition(5_000L, 1.0f, true, 60_000L, nowNs = t0 + 16_000_000L))
        assertEquals(5_045L, clock.sampleLocalExoPosition(5_045L, 1.0f, true, 60_000L, nowNs = t0 + 48_000_000L))

        clock.reset(positionMs = 10_000L, speed = 1.5f, playing = true, nowNs = t0)
        assertEquals(10_045L, clock.sampleInterpolatedMs(60_000L, nowNs = t0 + 30_000_000L))
        clock.reconcileRemoteSample(reportedPosMs = 10_075L, speed = 1.5f, playing = true, nowNs = t0 + 50_000_000L)
        assertEquals(10_075L, clock.sampleInterpolatedMs(60_000L, nowNs = t0 + 50_000_000L))
    }

    @Test
    fun hardenedScoutAndYouTubeGuardsBlockCrossSiteFramesAndExternalRedirects() {
        val seed = "https://missav.ws/en/demo-clip"

        // 1. Strict Scout subframe shield blocks unknown cross-site ad iframes while keeping same-site & video embeds
        val unknownIframe = com.example.domain.analysis.WebNavigationGuard.evaluateSubframeRequest(
            seedUrl = seed,
            subframeUrl = "https://mystery-tracker.xyz/frame.html",
            strictScoutSubframes = true,
        )
        assertTrue(unknownIframe is com.example.domain.analysis.RedirectDecision.Block)

        val videoEmbedIframe = com.example.domain.analysis.WebNavigationGuard.evaluateSubframeRequest(
            seedUrl = seed,
            subframeUrl = "https://embed.streamtape.com/e/xyz123",
            strictScoutSubframes = true,
        )
        assertTrue(videoEmbedIframe is com.example.domain.analysis.RedirectDecision.Allow)

        // 2. YouTube WebView domain isolation allows app origin + YouTube/Google CDNs and blocks everything else
        val appOrigin = "com.aistudio.dialoguelens.vkpzmx"
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.evaluateYouTubeNavigation(
                targetUrl = "https://$appOrigin/",
                appOriginHost = appOrigin,
            ) is com.example.domain.analysis.RedirectDecision.Allow
        )
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.evaluateYouTubeNavigation(
                targetUrl = "https://m.youtube.com/watch?v=3AFJtcP2cp0",
                appOriginHost = appOrigin,
            ) is com.example.domain.analysis.RedirectDecision.Allow
        )
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.evaluateYouTubeNavigation(
                targetUrl = "https://rr3---sn-a5mlrnls.googlevideo.com/videoplayback",
                appOriginHost = appOrigin,
            ) is com.example.domain.analysis.RedirectDecision.Allow
        )
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.evaluateYouTubeNavigation(
                targetUrl = "intent://watch?v=123#Intent;end",
                appOriginHost = appOrigin,
            ) is com.example.domain.analysis.RedirectDecision.Block
        )
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.evaluateYouTubeNavigation(
                targetUrl = "https://promo-redirect.example.com/offer",
                appOriginHost = appOrigin,
            ) is com.example.domain.analysis.RedirectDecision.Block
        )
    }

    @Test
    fun webMediaSniffingAndHlsParserSupportStreamsWithSessionHeaders() {
        // 1. Sniffer accepts HLS playlists, progressive MP4/WebM, and tokenized CDN video requests
        assertTrue(com.example.domain.analysis.WebNavigationGuard.isHlsPlaylistUrl("https://cdn.example.net/hls/master.m3u8?ttl=123"))
        assertTrue(com.example.domain.analysis.WebNavigationGuard.isLikelyPlayableMediaUrl("https://cdn.example.net/hls/master.m3u8?ttl=123"))
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.isLikelyPlayableMediaUrl(
                url = "https://video-edge.examplecdn.com/key=abc123/stream",
                secFetchDest = "video",
            )
        )
        assertTrue(
            com.example.domain.analysis.WebNavigationGuard.isLikelyPlayableMediaUrl(
                url = "https://cdn.example.net/media?mime=video/mp4",
            )
        )

        // Rejects individual transport segments, captions, thumbnails, and ad domains
        assertFalse(com.example.domain.analysis.WebNavigationGuard.isLikelyPlayableMediaUrl("https://cdn.example.net/hls/seg-001.ts"))
        assertFalse(com.example.domain.analysis.WebNavigationGuard.isLikelyPlayableMediaUrl("https://cdn.example.net/preview_480p.mp4"))
        assertFalse(com.example.domain.analysis.WebNavigationGuard.isLikelyPlayableMediaUrl("https://cdn.example.net/captions.vtt"))
        assertFalse(com.example.domain.analysis.WebNavigationGuard.isLikelyPlayableMediaUrl("https://a.exosrv.com/video.mp4"))

        // Request headers carry Cookie, Referer, Origin, and User-Agent
        val sniffed = com.example.domain.analysis.SniffedWebMediaStream(
            url = "https://cdn.example.net/hls/master.m3u8",
            referer = "https://example-cinema.org/watch/42",
            cookieHeader = "session=xyz",
            userAgent = "CustomUA/1.0",
            origin = "https://example-cinema.org",
            isHlsPlaylist = true,
        )
        val headers = sniffed.toRequestHeaders()
        assertEquals("https://example-cinema.org/watch/42", headers["Referer"])
        assertEquals("session=xyz", headers["Cookie"])
        assertEquals("CustomUA/1.0", headers["User-Agent"])
        assertEquals("https://example-cinema.org", headers["Origin"])

        // 2. HLS Master Playlist picker selects dedicated AUDIO rendition or lowest-bandwidth stream
        val masterWithAudio = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio/eng.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,AUDIO="aac"
            video/1080p.m3u8
        """.trimIndent()
        assertEquals(
            "https://cdn.example.net/hls/audio/eng.m3u8",
            com.example.domain.analysis.AudioAnalyzer.pickBestHlsMasterVariantUri(
                playlistText = masterWithAudio,
                baseUrl = "https://cdn.example.net/hls/master.m3u8",
            )
        )

        val masterBandwidthOnly = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3200000
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=450000
            360p/index.m3u8
        """.trimIndent()
        assertEquals(
            "https://cdn.example.net/hls/360p/index.m3u8",
            com.example.domain.analysis.AudioAnalyzer.pickBestHlsMasterVariantUri(
                playlistText = masterBandwidthOnly,
                baseUrl = "https://cdn.example.net/hls/master.m3u8",
            )
        )

        // 3. HLS Media Playlist parser computes exact cumulative segment windows and EXT-X-MAP init segment
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.000,
            seg0.m4s
            #EXTINF:5.500,
            seg1.m4s
            #EXTINF:4.500,
            seg2.m4s
        """.trimIndent()
        val index = com.example.domain.analysis.AudioAnalyzer.parseHlsMediaPlaylist(
            playlistText = mediaPlaylist,
            baseUrl = "https://cdn.example.net/hls/360p/index.m3u8",
        )
        assertNotNull(index)
        assertEquals(3, index!!.segments.size)
        assertEquals(16_000L, index.totalDurationMs)
        assertEquals("https://cdn.example.net/hls/360p/init.mp4", index.segments[1].initMapUrl)
        assertEquals(6_000L, index.segments[1].startMs)
        assertEquals(11_500L, index.segments[1].endMs)
    }

    @Test
    fun youTubeSubtitleEchoAndDuplicateSuppressionWorksAcrossBoundariesAndRollingAsr() {
        // 1. Echoed opening dialogue re-emitted on a later chunk (starts >= 35s) is rejected by resolveYouTubeTimestampOffsetSec
        val offsetNaN = com.example.data.ai.TranslationAiService.resolveYouTubeTimestampOffsetSec(
            rawStartSecs = listOf(1.5, 4.2, 8.0),
            windowStartSec = 75,
            windowEndSec = 115,
            rawCueTexts = listOf(
                "Welcome back to the Friday night show!",
                "Tonight we have an incredible panel joining us.",
                "Thank you for having me here tonight.",
            ),
            priorWindowNormalizedTexts = setOf(
                "welcomebacktothefridaynightshow",
                "tonightwehaveanincrediblepaneljoiningus",
                "thankyouforhavingmeheretonight",
            ),
        )
        assertTrue(offsetNaN.isNaN())

        // 2. filterEchoedOpeningCues rejects earlier-chunk lines replayed inside a later window even when shifted
        val earlyEvents = listOf(
            com.example.data.model.DialogueEvent(
                id = "e1",
                startTimeMs = 3_000L,
                endTimeMs = 6_000L,
                sourceText = "Welcome back to the Friday night show!",
                translatedText = "Welcome back to the Friday night show!",
                speakerId = "spk_a",
                speakerConfidence = 0.9f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            )
        )
        val echoedLate = listOf(
            com.example.data.model.DialogueEvent(
                id = "late_echo",
                startTimeMs = 76_000L,
                endTimeMs = 79_000L,
                sourceText = "Welcome back to the Friday night show",
                translatedText = "Welcome back to the Friday night show",
                speakerId = "spk_a",
                speakerConfidence = 0.9f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            ),
            com.example.data.model.DialogueEvent(
                id = "late_fresh",
                startTimeMs = 80_000L,
                endTimeMs = 83_000L,
                sourceText = "Wait until you see this next clip.",
                translatedText = "Wait until you see this next clip.",
                speakerId = "spk_b",
                speakerConfidence = 0.9f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "s1",
            ),
        )
        val filteredLate = com.example.domain.analysis.LookaheadWindowPlanner.filterEchoedOpeningCues(
            existing = earlyEvents,
            incoming = echoedLate,
            windowStartMs = 75_000L,
        )
        assertEquals(listOf("late_fresh"), filteredLate.map { it.id })

        // 3. ResilientYouTubeSegmentExecutor deduplicates & clamps sub-slice boundary duplicates
        val dupSubSliceCues = listOf(
            com.example.data.ai.AiTranscribedCue(
                startMs = 54_000L,
                endMs = 57_000L,
                sourceText = "Democrats winning and doing a good job",
                naturalTranslation = "Democrats winning and doing a good job",
                literalTranslation = "",
                romanization = "",
                dialogueType = DialogueType.SPOKEN,
                suggestedSpeakerSlot = "A",
                contextReason = "",
                culturalNote = "",
            ),
            com.example.data.ai.AiTranscribedCue(
                startMs = 56_000L,
                endMs = 59_000L,
                sourceText = "Democrats winning and doing a good job!",
                naturalTranslation = "Democrats winning and doing a good job!",
                literalTranslation = "",
                romanization = "",
                dialogueType = DialogueType.SPOKEN,
                suggestedSpeakerSlot = "A",
                contextReason = "",
                culturalNote = "",
            ),
            com.example.data.ai.AiTranscribedCue(
                startMs = 76_000L, // out of [35s, 75s) bounds -> dropped
                endMs = 78_000L,
                sourceText = "Outside window",
                naturalTranslation = "Outside window",
                literalTranslation = "",
                romanization = "",
                dialogueType = DialogueType.SPOKEN,
                suggestedSpeakerSlot = "B",
                contextReason = "",
                culturalNote = "",
            ),
        )
        val clampedAndDeduped = com.example.domain.analysis.ResilientYouTubeSegmentExecutor.deduplicateAndClampCues(
            cues = dupSubSliceCues,
            windowStartMs = 35_000L,
            windowEndMs = 75_000L,
        )
        assertEquals(1, clampedAndDeduped.size)

        // 4. YouTubeCaptionFetcher rolling ASR deduplication trims prefix-extensions and suffix/head overlaps
        val rollingCues = listOf(
            com.example.data.subtitle.RawSubtitleCue(1_000L, 2_500L, "Hello everyone"),
            com.example.data.subtitle.RawSubtitleCue(1_800L, 3_600L, "Hello everyone welcome back"),
            com.example.data.subtitle.RawSubtitleCue(3_200L, 5_000L, "welcome back to the show"),
        )
        val dedupedRolling = com.example.data.subtitle.YouTubeCaptionFetcher.mergeRollingDuplicates(rollingCues)
        assertEquals(listOf("Hello everyone", "welcome back", "to the show"), dedupedRolling.map { it.text })
    }
}




