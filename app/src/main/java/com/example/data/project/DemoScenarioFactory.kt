package com.example.data.project

import com.example.data.model.AudioVoiceFeatures
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.FaceSample
import com.example.data.model.FaceTrack
import com.example.data.model.GlossaryEntry
import com.example.data.model.MediaTrackInfo
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.ProcessingQualityMode
import com.example.data.model.Shot
import com.example.data.model.Speaker
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.TranslationMode
import com.example.data.model.VideoSource
import com.example.data.model.VideoSourceKind

object DemoScenarioFactory {

    const val DEMO_PROJECT_ID = "demo_shinjuku_rain"
    const val SCENE_TWO_SHOT = "two_shot"
    const val SCENE_CLOSEUP_A = "closeup_a"

    fun createDemoProject(): DialogueLensProject {
        val speakers = listOf(
            Speaker(
                id = "spk_a",
                defaultLabel = "Speaker A",
                customName = "Kenji",
                accentIndex = 0,
                colorRemembered = true,
                learnedFaceTrackIds = listOf("ft_shot1_a", "ft_shot2_a", "ft_shot3_a"),
                preferredScreenSideX = 0.25f,
                voicePitchHz = 124f,
                voiceBrightness = 0.36f,
            ),
            Speaker(
                id = "spk_b",
                defaultLabel = "Speaker B",
                customName = "Sora",
                accentIndex = 1,
                colorRemembered = true,
                learnedFaceTrackIds = listOf("ft_shot1_b", "ft_shot3_b"),
                preferredScreenSideX = 0.74f,
                voicePitchHz = 218f,
                voiceBrightness = 0.68f,
            ),
        )

        val shots = listOf(
            Shot(
                id = "shot_1",
                startMs = 0L,
                endMs = 8_000L,
                cutType = "ESTABLISHING_TWO_SHOT",
                demoSceneKey = SCENE_TWO_SHOT,
                sceneLuminance = 0.34f,
                focalExclusionZones = listOf(
                    // Protect glowing Edison pendant lamp between speakers
                    NormalizedRect(0.41f, 0.00f, 0.59f, 0.34f),
                    // Protect café window Japanese signage on the upper right
                    NormalizedRect(0.84f, 0.13f, 0.98f, 0.38f),
                ),
            ),
            Shot(
                id = "shot_2",
                startMs = 8_000L,
                endMs = 14_500L,
                cutType = "HARD_CUT_CLOSEUP",
                demoSceneKey = SCENE_CLOSEUP_A,
                sceneLuminance = 0.24f,
                focalExclusionZones = listOf(
                    // Protect bright vertical neon sign behind Kenji
                    NormalizedRect(0.55f, 0.00f, 0.65f, 0.25f),
                    // Protect Sora's over-the-shoulder profile on the right edge
                    NormalizedRect(0.89f, 0.10f, 1.00f, 0.99f),
                ),
            ),
            Shot(
                id = "shot_3",
                startMs = 14_500L,
                endMs = 24_000L,
                cutType = "HARD_CUT_TWO_SHOT",
                demoSceneKey = SCENE_TWO_SHOT,
                sceneLuminance = 0.34f,
                focalExclusionZones = listOf(
                    NormalizedRect(0.41f, 0.00f, 0.59f, 0.34f),
                    NormalizedRect(0.84f, 0.13f, 0.98f, 0.38f),
                ),
            ),
        )

        // Normalized face boxes matched to img_demo_two_shot and img_demo_closeup_a
        val faceTracks = listOf(
            FaceTrack(
                id = "ft_shot1_a",
                shotId = "shot_1",
                speakerId = "spk_a",
                trackingTag = 101,
                samples = listOf(
                    FaceSample(400L, NormalizedRect(0.18f, 0.25f, 0.32f, 0.49f), mouthActivity = 0.84f, headYawDeg = 32f),
                    FaceSample(2_000L, NormalizedRect(0.185f, 0.252f, 0.325f, 0.492f), mouthActivity = 0.91f, headYawDeg = 30f),
                    FaceSample(3_600L, NormalizedRect(0.18f, 0.25f, 0.32f, 0.49f), mouthActivity = 0.22f, headYawDeg = 31f),
                    FaceSample(6_000L, NormalizedRect(0.182f, 0.25f, 0.322f, 0.49f), mouthActivity = 0.08f, headYawDeg = 33f),
                ),
            ),
            FaceTrack(
                id = "ft_shot1_b",
                shotId = "shot_1",
                speakerId = "spk_b",
                trackingTag = 102,
                samples = listOf(
                    FaceSample(400L, NormalizedRect(0.68f, 0.29f, 0.80f, 0.51f), mouthActivity = 0.09f, headYawDeg = -34f),
                    FaceSample(3_800L, NormalizedRect(0.682f, 0.288f, 0.802f, 0.508f), mouthActivity = 0.82f, headYawDeg = -32f),
                    FaceSample(5_800L, NormalizedRect(0.678f, 0.292f, 0.798f, 0.512f), mouthActivity = 0.92f, headYawDeg = -30f),
                    FaceSample(7_700L, NormalizedRect(0.68f, 0.29f, 0.80f, 0.51f), mouthActivity = 0.16f, headYawDeg = -31f),
                ),
            ),
            FaceTrack(
                id = "ft_shot2_a",
                shotId = "shot_2",
                speakerId = "spk_a",
                trackingTag = 201,
                samples = listOf(
                    FaceSample(8_200L, NormalizedRect(0.33f, 0.19f, 0.50f, 0.49f), mouthActivity = 0.88f, headYawDeg = 22f),
                    FaceSample(10_200L, NormalizedRect(0.335f, 0.188f, 0.505f, 0.488f), mouthActivity = 0.86f, headYawDeg = 20f),
                    FaceSample(12_500L, NormalizedRect(0.33f, 0.192f, 0.50f, 0.492f), mouthActivity = 0.11f, headYawDeg = 24f),
                    FaceSample(14_200L, NormalizedRect(0.332f, 0.19f, 0.502f, 0.49f), mouthActivity = 0.08f, headYawDeg = 25f),
                ),
            ),
            FaceTrack(
                id = "ft_shot3_a",
                shotId = "shot_3",
                speakerId = "spk_a",
                trackingTag = 301,
                samples = listOf(
                    FaceSample(14_800L, NormalizedRect(0.18f, 0.25f, 0.32f, 0.49f), mouthActivity = 0.24f, headYawDeg = 31f),
                    FaceSample(18_500L, NormalizedRect(0.184f, 0.25f, 0.324f, 0.49f), mouthActivity = 0.28f, headYawDeg = 30f),
                    FaceSample(21_800L, NormalizedRect(0.18f, 0.25f, 0.32f, 0.49f), mouthActivity = 0.90f, headYawDeg = 29f),
                ),
            ),
            FaceTrack(
                id = "ft_shot3_b",
                shotId = "shot_3",
                speakerId = "spk_b",
                trackingTag = 302,
                samples = listOf(
                    FaceSample(14_800L, NormalizedRect(0.68f, 0.29f, 0.80f, 0.51f), mouthActivity = 0.86f, headYawDeg = -33f),
                    FaceSample(18_500L, NormalizedRect(0.678f, 0.29f, 0.798f, 0.51f), mouthActivity = 0.81f, headYawDeg = -32f),
                    FaceSample(21_800L, NormalizedRect(0.68f, 0.29f, 0.80f, 0.51f), mouthActivity = 0.12f, headYawDeg = -34f),
                ),
            ),
        )

        val events = listOf(
            DialogueEvent(
                id = "demo_evt_1",
                startTimeMs = 400L,
                endTimeMs = 3_600L,
                sourceText = "雨が強くなってきたね。傘は持ってきてないの？",
                translatedText = "Rain's picking up. Didn't you bring an umbrella?",
                literalText = "The rain has become strong, hasn't it? You haven't brought an umbrella?",
                romanization = "Ame ga tsuyoku natte kita ne. Kasa wa motte kite nai no?",
                speakerId = "spk_a",
                speakerConfidence = 0.94f,
                faceTrackId = "ft_shot1_a",
                faceConfidence = 0.96f,
                shotId = "shot_1",
                dialogueType = DialogueType.SPOKEN,
                audioFeatures = AudioVoiceFeatures(124f, 0.36f, 0.44f, "voice_1", peakAmplitude = 0.52f, emphasisScore = 0.24f),
                contextCueReason = "Opens conversation with question",
                attributionSummary = "Lip sync 88% · Voice 124Hz · Opens Q&A",
                culturalNote = "「強くなってきた」 uses ～てきた to express a gradual shift toward the present; 「〜ないの？」 adds gentle surprise.",
            ),
            DialogueEvent(
                id = "demo_evt_2",
                startTimeMs = 3_900L,
                endTimeMs = 7_600L,
                sourceText = "駅に置いてきちゃった。それより、封筒は？",
                translatedText = "Left it at the station. More importantly—the envelope?",
                literalText = "I accidentally left it at the station. Aside from that, what about the envelope?",
                romanization = "Eki ni oite kichatta. Sore yori, fūtō wa?",
                speakerId = "spk_b",
                speakerConfidence = 0.91f,
                faceTrackId = "ft_shot1_b",
                faceConfidence = 0.94f,
                shotId = "shot_1",
                dialogueType = DialogueType.SPOKEN,
                audioFeatures = AudioVoiceFeatures(218f, 0.68f, 0.66f, "voice_2", peakAmplitude = 0.74f, emphasisScore = 0.52f),
                contextCueReason = "Answers Kenji's umbrella question",
                attributionSummary = "Lip sync 87% · Voice 218Hz · Answers prior question",
                culturalNote = "「～ちゃった」 (from ～てしまった) conveys casual regret over an unintentional slip.",
            ),
            DialogueEvent(
                id = "demo_evt_3",
                startTimeMs = 8_300L,
                endTimeMs = 11_200L,
                sourceText = "落ち着いて。今ここで話すことじゃない。",
                translatedText = "Stay calm. This isn't the place to talk about it.",
                literalText = "Calm down. It is not something to speak about here right now.",
                romanization = "Ochitsuite. Ima koko de hanasu koto ja nai.",
                speakerId = "spk_a",
                speakerConfidence = 0.93f,
                faceTrackId = "ft_shot2_a",
                faceConfidence = 0.97f,
                shotId = "shot_2",
                dialogueType = DialogueType.SPOKEN,
                audioFeatures = AudioVoiceFeatures(122f, 0.35f, 0.48f, "voice_1", peakAmplitude = 0.55f, emphasisScore = 0.28f),
                contextCueReason = "Responds to envelope inquiry",
                attributionSummary = "Lip sync 87% · Voice 122Hz · Answers prior question",
            ),
            DialogueEvent(
                id = "demo_evt_4",
                startTimeMs = 11_500L,
                endTimeMs = 14_200L,
                sourceText = "時間がないの。電車が来る前に決めて！",
                translatedText = "We're out of time. Decide before the train arrives!",
                literalText = "There is no time. Decide before the train comes!",
                romanization = "Jikan ga nai no. Densha ga kuru mae ni kimete!",
                speakerId = "spk_b",
                speakerConfidence = 0.86f,
                faceTrackId = null,
                faceConfidence = 0.0f,
                shotId = "shot_2",
                dialogueType = DialogueType.OFF_SCREEN_RIGHT,
                placementMode = PlacementMode.EDGE_DOCKED,
                audioFeatures = AudioVoiceFeatures(236f, 0.82f, 0.91f, "voice_2", peakAmplitude = 0.95f, emphasisScore = 0.86f, isShouted = true),
                contextCueReason = "Urgent shouted rebuttal to Kenji's stall",
                attributionSummary = "Off-screen · High emphasis 236Hz matches Sora",
            ),
            // Intentionally low-confidence / uncertain line to demonstrate confidence fallback + 1-tap correction learning
            DialogueEvent(
                id = "demo_evt_5",
                startTimeMs = 14_800L,
                endTimeMs = 17_600L,
                sourceText = "ポケットの中にまだあるんでしょう？",
                translatedText = "It's still inside your coat pocket, isn't it?",
                literalText = "It is still inside the pocket, right?",
                romanization = "Poketto no naka ni mada aru n deshō?",
                speakerId = "spk_a",
                speakerConfidence = 0.36f,
                faceTrackId = "ft_shot3_a",
                faceConfidence = 0.52f,
                shotId = "shot_3",
                dialogueType = DialogueType.SPOKEN,
                placementMode = PlacementMode.NEUTRAL_FALLBACK,
                audioFeatures = AudioVoiceFeatures(214f, 0.65f, 0.42f, "voice_2"),
                contextCueReason = "Quiet voice under rain noise",
                attributionSummary = "Low audio SNR · Needs confirmation",
            ),
            DialogueEvent(
                id = "demo_evt_6",
                startTimeMs = 17_900L,
                endTimeMs = 20_600L,
                sourceText = "まさか…誰かに見られた？",
                translatedText = "Wait—did someone see you?",
                literalText = "No way... were you seen by someone?",
                romanization = "Masaka... dareka ni mirareta?",
                speakerId = "spk_a",
                speakerConfidence = 0.41f,
                faceTrackId = "ft_shot3_a",
                faceConfidence = 0.55f,
                shotId = "shot_3",
                dialogueType = DialogueType.SPOKEN,
                placementMode = PlacementMode.NEUTRAL_FALLBACK,
                audioFeatures = AudioVoiceFeatures(216f, 0.66f, 0.45f, "voice_2"),
                contextCueReason = "Follow-up worry in softer voice",
                attributionSummary = "Overlapping rain · Needs confirmation",
            ),
            DialogueEvent(
                id = "demo_evt_7",
                startTimeMs = 20_900L,
                endTimeMs = 23_700L,
                sourceText = "大丈夫、ここには誰もいない。",
                translatedText = "Relax, nobody followed me here.",
                literalText = "It is okay, nobody is here.",
                romanization = "Daijōbu, koko ni wa dare mo inai.",
                speakerId = "spk_a",
                speakerConfidence = 0.89f,
                faceTrackId = "ft_shot3_a",
                faceConfidence = 0.93f,
                shotId = "shot_3",
                dialogueType = DialogueType.SPOKEN,
                audioFeatures = AudioVoiceFeatures(125f, 0.37f, 0.82f, "voice_1"),
                contextCueReason = "Reassuring answer to concern",
                attributionSummary = "Lip sync 90% · Voice 125Hz · Answers question",
            ),
            DialogueEvent(
                id = "demo_evt_8",
                startTimeMs = 21_200L,
                endTimeMs = 23_700L,
                sourceText = "[雨音がガラス窓を叩く]",
                translatedText = "Rain patters softly against the glass",
                literalText = "Sound of rain tapping the glass window",
                romanization = "Amaoto ga garasu-mado o tataku",
                speakerId = null,
                speakerConfidence = 0.95f,
                faceTrackId = null,
                faceConfidence = 0f,
                shotId = "shot_3",
                dialogueType = DialogueType.SOUND_DESCRIPTION,
                placementMode = PlacementMode.EDGE_DOCKED,
                attributionSummary = "Ambient sound description",
            ),
        )

        return DialogueLensProject(
            id = DEMO_PROJECT_ID,
            title = "Shinjuku Rain (Demo Scene)",
            createdAtEpochMs = System.currentTimeMillis(),
            sourceLanguage = "Japanese",
            targetLanguage = "English",
            translationMode = TranslationMode.NATURAL,
            subtitleSource = SubtitleSourceKind.BUILT_IN_DEMO,
            qualityMode = ProcessingQualityMode.BALANCED,
            videoSource = VideoSource(
                uriString = "demo://shinjuku_rain",
                displayName = "Shinjuku Rain — 2-Speaker Café Scene",
                kind = VideoSourceKind.DEMO_SCENARIO,
                durationMs = 24_000L,
                width = 1920,
                height = 1080,
                audioTracks = listOf(MediaTrackInfo(0, "audio/aac", "ja", "Japanese Stereo (Demo)")),
                embeddedSubtitleTracks = listOf(MediaTrackInfo(0, "text/x-srt", "ja", "Japanese Dialogue")),
            ),
            shots = shots,
            speakers = speakers,
            faceTracks = faceTracks,
            dialogueEvents = events,
            textExclusionZones = emptyList(),
            styleSettings = BalloonStyleSettings(),
            glossary = listOf(
                GlossaryEntry("健二 / Kenji", "Kenji", "Main character · calm casual register"),
                GlossaryEntry("ソラ / Sora", "Sora", "Main character · urgent direct tone"),
                GlossaryEntry("封筒", "the envelope", "Key plot object across scenes"),
            ),
            audioWaveformPeaks = List(64) { idx ->
                val tMs = (idx.toFloat() / 64f) * 24_000f
                when {
                    tMs in 400f..3600f -> 0.42f + ((idx * 17) % 10) * 0.025f
                    tMs in 3900f..7600f -> 0.60f + ((idx * 13) % 10) * 0.03f
                    tMs in 8300f..11200f -> 0.46f + ((idx * 19) % 10) * 0.02f
                    tMs in 11500f..14200f -> 0.92f // Shouted urgent line peak
                    tMs in 14800f..20600f -> 0.34f + ((idx * 7) % 8) * 0.02f
                    tMs in 20900f..23700f -> 0.68f + ((idx * 11) % 9) * 0.025f
                    else -> 0.14f
                }
            },
        )
    }
}
