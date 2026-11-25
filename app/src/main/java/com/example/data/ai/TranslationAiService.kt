package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.DeadSpaceZone
import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueType
import com.example.data.model.GlossaryEntry
import com.example.data.model.NormalizedRect
import com.example.data.model.Speaker
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.math.roundToInt

private const val TAG = "TranslationAiService"

data class AiTranslatedLine(
    val id: String,
    val naturalTranslation: String,
    val literalTranslation: String,
    val romanization: String,
    val dialogueTypeHint: DialogueType,
    val speakerTurnHint: String,
    val suggestedSpeakerSlot: String = "UNCERTAIN",
    val contextReason: String = "",
    val culturalNote: String = "",
)

data class AiTranscribedCue(
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val naturalTranslation: String,
    val literalTranslation: String,
    val romanization: String,
    val dialogueType: DialogueType,
    val suggestedSpeakerSlot: String,
    val contextReason: String,
    val culturalNote: String,
    val speakerPosition: String = "CENTER",
    val anchorX: Float? = null,
    val anchorY: Float? = null,
    val deadSpaceZone: DeadSpaceZone = DeadSpaceZone.AUTO,
    val avoidBoxes: List<NormalizedRect> = emptyList(),
)

data class AiCastSlotAssignment(
    val slot: String,
    val displayName: String,
    val isRolePlaceholder: Boolean,
    val rationale: String = "",
    val visualVoiceNotes: String = "",
    val isVisuallyConfirmed: Boolean = true,
)

data class AiCastDiscoveryResult(
    val assignments: List<AiCastSlotAssignment> = emptyList(),
    val summary: String = "",
    val groundingQueries: List<String> = emptyList(),
    val groundingSources: List<String> = emptyList(),
)

data class AiYouTubeTranscriptionResult(
    val cues: List<AiTranscribedCue> = emptyList(),
    val speakerUpdates: List<AiCastSlotAssignment> = emptyList(),
    val summary: String = "",
)

class TranslationAiService {

    private val speakerUpdatesProperty = Schema.array(
        items = Schema.obj(
            properties = mapOf(
                "slot" to Schema.enumeration(
                    values = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"),
                    description = "Speaker slot (A-L) matching session roster"
                ),
                "displayName" to Schema.string(
                    description = "Real name when spoken/shown/implied, OR concise 1-3 word role (max 22 chars, e.g., 'Scooter Driver', 'Radio Host', 'News Anchor')"
                ),
                "isRolePlaceholder" to Schema.boolean(
                    description = "True if descriptive role/unconfirmed appearance, false if confirmed real character/person name"
                ),
                "isVisuallyConfirmed" to Schema.boolean(
                    description = "True ONLY when the speaker's real name is visibly shown on a lower-third/chyron/title card, explicitly spoken/addressed in dialogue, or unmistakably visually confirmed on camera. NEVER guess from video title alone; set false if unconfirmed so the speaker stays dotted gray until confirmed."
                ),
                "rationale" to Schema.string(
                    description = "Brief 3-8 word reason from on-screen visuals, chyron, or spoken dialogue clues"
                ),
                "visualVoiceNotes" to Schema.string(
                    description = "Concise visual/voice clue for tracking across cuts (e.g. 'Man in brown blazer on right, baritone voice')"
                ),
            ),
        ),
        description = "Newly spotted speakers (isVisuallyConfirmed=false until name is proven on screen/audio) or mid-video real-name visual confirmations"
    )

    private val translationResponseSchema = Schema.obj(
        properties = mapOf(
            "lines" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(description = "Original event id"),
                        "naturalTranslation" to Schema.string(
                            description = "Concise, natural subtitle translation preserving tone, register, honorifics, and dialogue continuity"
                        ),
                        "literalTranslation" to Schema.string(
                            description = "Closer word-for-word translation for language learners"
                        ),
                        "romanization" to Schema.string(
                            description = "Optional phonetic romanization or pronunciation guide if non-Latin script, otherwise empty string"
                        ),
                        "dialogueType" to Schema.enumeration(
                            values = listOf(
                                "SPOKEN",
                                "OFF_SCREEN_BEHIND",
                                "VOICE_OVER",
                                "NARRATION",
                                "INTERNAL_MONOLOGUE",
                                "SOUND_DESCRIPTION"
                            ),
                            description = "Inferred dialogue presentation category"
                        ),
                        "speakerSlot" to Schema.enumeration(
                            values = listOf("A", "B", "C", "D", "E", "F", "G", "H", "UNCERTAIN"),
                            description = "Most likely speaker slot (A-H) inferred from conversational context, direct address, Q&A flow, register, and acoustic voice cues"
                        ),
                        "turnHint" to Schema.enumeration(
                            values = listOf("SAME_SPEAKER", "ALTERNATE_SPEAKER", "UNCERTAIN"),
                            description = "Conversational turn-taking hint relative to previous line"
                        ),
                        "contextReason" to Schema.string(
                            description = "Concise 4-8 word explanation of the conversational/audio clue identifying the speaker"
                        ),
                        "culturalNote" to Schema.string(
                            description = "Optional 1-sentence note on idiom, honorific nuance, or wordplay (empty string if straightforward)"
                        ),
                    ),
                ),
                description = "Translated and speaker-attributed dialogue lines in matching order"
            ),
            "speakerUpdates" to speakerUpdatesProperty,
        )
    )

    private val audioTranscriptionSchema = Schema.obj(
        properties = mapOf(
            "cues" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "startOffsetSec" to Schema.double(description = "Start offset in seconds within this audio chunk (0.0 based)"),
                        "endOffsetSec" to Schema.double(description = "End offset in seconds within this audio chunk"),
                        "sourceText" to Schema.string(description = "Verbatim spoken transcription or [SFX description] in source language"),
                        "naturalTranslation" to Schema.string(description = "Concise natural translation sized for speech balloons"),
                        "literalTranslation" to Schema.string(description = "Literal learner translation"),
                        "romanization" to Schema.string(description = "Phonetic romanization if non-Latin, else empty string"),
                        "dialogueType" to Schema.enumeration(
                            values = listOf("SPOKEN", "OFF_SCREEN_BEHIND", "VOICE_OVER", "NARRATION", "SOUND_DESCRIPTION"),
                            description = "Dialogue or ambient sound classification"
                        ),
                        "speakerSlot" to Schema.enumeration(
                            values = listOf("A", "B", "C", "D", "UNCERTAIN"),
                            description = "Consistent speaker label across lines in this clip"
                        ),
                        "contextReason" to Schema.string(description = "Short 4-8 word acoustic/context clue"),
                        "culturalNote" to Schema.string(description = "Optional 1-sentence cultural/idiom note, else empty string"),
                    ),
                ),
                description = "Chronological timed speech & SFX cues extracted from the audio"
            )
        )
    )

    private val speakerContextSchema = Schema.obj(
        properties = mapOf(
            "lines" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(description = "Original event id"),
                        "speakerSlot" to Schema.enumeration(
                            values = listOf("A", "B", "C", "D", "E", "F", "G", "H", "UNCERTAIN"),
                            description = "Likely speaker slot matching roster (A-H) using visual keyframes, conversational context, and acoustic pitch/voice cues"
                        ),
                        "turnHint" to Schema.enumeration(
                            values = listOf("SAME_SPEAKER", "ALTERNATE_SPEAKER", "UNCERTAIN"),
                            description = "Conversational turn relationship to prior line"
                        ),
                        "dialogueType" to Schema.enumeration(
                            values = listOf(
                                "SPOKEN",
                                "OFF_SCREEN_BEHIND",
                                "VOICE_OVER",
                                "NARRATION",
                                "INTERNAL_MONOLOGUE",
                                "SOUND_DESCRIPTION"
                            ),
                            description = "Dialogue presentation type"
                        ),
                        "contextReason" to Schema.string(
                            description = "Short 4-8 word reason explaining visual, conversation & audio cues"
                        ),
                    ),
                ),
                description = "Speaker attribution reasoning per dialogue event"
            ),
            "speakerUpdates" to speakerUpdatesProperty,
        )
    )

    private val castRosterSchema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(
                description = "Concise 1-sentence summary of who is speaking or credited in this video"
            ),
            "assignments" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "slot" to Schema.enumeration(
                            values = listOf("A", "B", "C", "D", "E", "F", "G", "H"),
                            description = "Speaker slot letter"
                        ),
                        "displayName" to Schema.string(
                            description = "Real name when identified, OR concise movie-credit role (e.g., Host, Hot Dog Vendor, Civilian Woman 1) when unnamed"
                        ),
                        "isRolePlaceholder" to Schema.boolean(
                            description = "True if generic movie-credit role, false if real name/handle"
                        ),
                        "rationale" to Schema.string(
                            description = "Brief 3-8 word explanation from metadata, search, or scene clues"
                        ),
                        "visualVoiceNotes" to Schema.string(
                            description = "Optional concise visual or voice trait"
                        ),
                    ),
                ),
                description = "Cast names or movie-credit roles mapped onto slots A-H"
            ),
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = translationResponseSchema
                temperature = 0.25f
            }
        )
    }

    private val liteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = translationResponseSchema
                temperature = 0.2f
            }
        )
    }

    private val audioAsrModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = audioTranscriptionSchema
                temperature = 0.2f
            }
        )
    }

    private val liteAudioAsrModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = audioTranscriptionSchema
                temperature = 0.2f
            }
        )
    }

    private val contextAttributionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = speakerContextSchema
                temperature = 0.2f
            }
        )
    }

    private val liteContextAttributionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = speakerContextSchema
                temperature = 0.2f
            }
        )
    }

    private val searchGroundedCastModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            tools = listOf(Tool.googleSearch()),
            generationConfig = generationConfig {
                temperature = 0.2f
            }
        )
    }

    private val castRosterModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = castRosterSchema
                temperature = 0.2f
            }
        )
    }

    private val youtubeVideoTranscriptionSchema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(
                description = "Concise 1-sentence overview of the speakers and scene in this video segment"
            ),
            "speakerUpdates" to speakerUpdatesProperty,
            "cues" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "startSec" to Schema.double(
                            description = "Absolute start timestamp in seconds within the full YouTube video (e.g. 12.5)"
                        ),
                        "endSec" to Schema.double(
                            description = "Absolute end timestamp in seconds within the full YouTube video (e.g. 15.2)"
                        ),
                        "sourceText" to Schema.string(
                            description = "Verbatim spoken line or concise [SFX / music] description in the spoken language"
                        ),
                        "naturalTranslation" to Schema.string(
                            description = "Concise natural line in the target language sized for a comic speech balloon"
                        ),
                        "literalTranslation" to Schema.string(
                            description = "Closer literal translation if translating, else empty string"
                        ),
                        "romanization" to Schema.string(
                            description = "Phonetic romanization if non-Latin script, else empty string"
                        ),
                        "dialogueType" to Schema.enumeration(
                            values = listOf(
                                "SPOKEN",
                                "OFF_SCREEN_LEFT",
                                "OFF_SCREEN_RIGHT",
                                "OFF_SCREEN_BEHIND",
                                "VOICE_OVER",
                                "NARRATION",
                                "SOUND_DESCRIPTION",
                            ),
                            description = "Dialogue or ambient sound classification"
                        ),
                        "speakerSlot" to Schema.enumeration(
                            values = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "UNCERTAIN"),
                            description = "Consistent speaker slot letter matching voice and on-screen person, or UNCERTAIN if brief clip voice / unclear"
                        ),
                        "speakerPosition" to Schema.enumeration(
                            values = listOf(
                                "LEFT",
                                "CENTER_LEFT",
                                "CENTER",
                                "CENTER_RIGHT",
                                "RIGHT",
                                "OFF_SCREEN_LEFT",
                                "OFF_SCREEN_RIGHT",
                                "OFF_SCREEN_BEHIND",
                                "VOICE_OVER",
                            ),
                            description = "Where the active speaker appears on screen during this line"
                        ),
                        "anchorX" to Schema.double(
                            description = "Normalized horizontal screen position of the speaking person's MOUTH center (0.12=far left, 0.50=center, 0.88=far right)"
                        ),
                        "anchorY" to Schema.double(
                            description = "Normalized vertical screen position of the speaking person's LIPS/MOUTH (0.16..0.34 in normal studio/desk/waist-up shots, 0.35..0.52 only if head is vertically centered/close-up, NEVER chest/tie/shoulders)"
                        ),
                        "deadSpaceZone" to Schema.enumeration(
                            values = listOf(
                                "AUTO",
                                "TOP_LEFT",
                                "TOP_CENTER",
                                "TOP_RIGHT",
                                "MID_LEFT",
                                "MID_RIGHT",
                                "BOTTOM_LEFT",
                                "BOTTOM_CENTER",
                                "BOTTOM_RIGHT",
                            ),
                            description = "Cleanest open background dead-space pocket (empty wall, sky, open margin) near the speaker with ZERO faces, hands, or graphics"
                        ),
                        "avoidBoxes" to Schema.array(
                            items = Schema.obj(
                                properties = mapOf(
                                    "left" to Schema.double(description = "Normalized left edge 0..1"),
                                    "top" to Schema.double(description = "Normalized top edge 0..1"),
                                    "right" to Schema.double(description = "Normalized right edge 0..1"),
                                    "bottom" to Schema.double(description = "Normalized bottom edge 0..1"),
                                ),
                            ),
                            description = "Optional boxes for OTHER visible faces (non-speakers) or on-screen chyrons/lower-thirds to avoid covering"
                        ),
                        "contextReason" to Schema.string(
                            description = "Short 3-8 word visual/audio clue for speaker or delivery"
                        ),
                        "culturalNote" to Schema.string(
                            description = "Optional 1-sentence note on idiom or nuance, else empty string"
                        ),
                    ),
                ),
                description = "Chronological timed dialogue & SFX cues for the requested YouTube video window"
            ),
        )
    )

    private val youtubeVideoModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = youtubeVideoTranscriptionSchema
                temperature = 0.2f
            }
        )
    }

    private val liteYoutubeVideoModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = youtubeVideoTranscriptionSchema
                temperature = 0.2f
            }
        )
    }

    private val liteHeartbeatPingModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                maxOutputTokens = 8
                temperature = 0.0f
            }
        )
    }

    suspend fun pingFlashLiteHealth(): Result<Long> {
        val startedMs = android.os.SystemClock.elapsedRealtime()
        return try {
            withTimeout(10_000L) {
                liteHeartbeatPingModel.generateContent("OK")
            }
            val elapsed = (android.os.SystemClock.elapsedRealtime() - startedMs).coerceAtLeast(1L)
            Result.success(elapsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Flash-Lite heartbeat ping failed", e)
            Result.failure(e)
        }
    }

    suspend fun transcribeYouTubeVideoWindow(
        videoId: String,
        windowStartSec: Int,
        windowEndSec: Int,
        totalDurationSec: Int,
        sourceLanguage: String,
        targetLanguage: String,
        videoContext: String = "",
        knownSpeakers: List<Speaker> = emptyList(),
        glossary: List<GlossaryEntry> = emptyList(),
        preferLiteModel: Boolean = true,
        priorWindowNormalizedTexts: Set<String> = emptySet(),
    ): Result<AiYouTubeTranscriptionResult> {
        val cleanId = videoId.replace(Regex("[^0-9A-Za-z_-]"), "")
        if (cleanId.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid YouTube video ID."))
        }
        return try {
            val isSameLanguage = sourceLanguage.equals("Auto-Detect", ignoreCase = true) ||
                sourceLanguage.equals(targetLanguage, ignoreCase = true)
            val rosterBlock = if (knownSpeakers.isNotEmpty()) {
                buildString {
                    appendLine("Current Running Speaker Glossary (STRICTLY reuse the same slot letter for returning speakers; NEVER reuse an occupied slot for a different person!):")
                    knownSpeakers.take(12).forEachIndexed { idx, spk ->
                        val slot = ('A' + idx).toString()
                        val tag = when {
                            !spk.isConfirmedCharacter -> " [UNCONFIRMED dotted gray — set isVisuallyConfirmed=true ONLY when real name is visibly shown or spoken for this exact person]"
                            spk.isRolePlaceholder -> " [placeholder role — UPGRADE with isVisuallyConfirmed=true if real name is visibly shown or spoken]"
                            else -> " [CONFIRMED]"
                        }
                        val notes = if (spk.visualVoiceNotes.isNotBlank()) " · ${spk.visualVoiceNotes}" else ""
                        val side = spk.preferredScreenSideX?.let { x ->
                            when {
                                x < 0.42f -> " · usually LEFT"
                                x > 0.58f -> " · usually RIGHT"
                                else -> " · usually CENTER"
                            }
                        }.orEmpty()
                        appendLine("  Slot $slot: ${spk.displayName}$tag$side$notes")
                    }
                    val nextUnused = if (knownSpeakers.size < 12) ('A' + knownSpeakers.size).toString() else "NONE (use UNCERTAIN for extra brief voices)"
                    appendLine("  Next unused slot for a brand-new speaker: $nextUnused")
                }
            } else ""
            val glossaryBlock = if (glossary.isNotEmpty()) {
                buildString {
                    appendLine("Glossary terms to keep consistent:")
                    glossary.forEach { g -> appendLine("  - ${g.term} -> ${g.translation}") }
                }
            } else ""

            val taskInstruction = if (isSameLanguage) {
                "Watch and listen carefully to this YouTube video from ${windowStartSec}s to ${windowEndSec}s (total length ~${totalDurationSec}s). Absorb every spoken word, who said it, where their mouth is on screen, shot cutaways, and notable sound/music cues in $targetLanguage for spatial speech balloons."
            } else {
                "Watch and listen carefully to this YouTube video from ${windowStartSec}s to ${windowEndSec}s (total length ~${totalDurationSec}s). Absorb every spoken word (source hint: $sourceLanguage), translate naturally into $targetLanguage, identify who is speaking, where their mouth is on screen, and shot cutaways for spatial speech balloons."
            }

            val promptText = buildString {
                appendLine(taskInstruction)
                if (videoContext.isNotBlank()) {
                    appendLine("Video metadata (CONTEXT ONLY — NEVER blindly attach title celebrity names to unconfirmed faces!): $videoContext")
                }
                if (rosterBlock.isNotBlank()) append(rosterBlock)
                if (glossaryBlock.isNotBlank()) append(glossaryBlock)
                appendLine("Rules:")
                appendLine("1. Output EVERY spoken sentence, short interjection, and cross-talk turn occurring STRICTLY between ${windowStartSec}s and ${windowEndSec}s using accurate ABSOLUTE video timestamps in seconds (between ${windowStartSec}.0 and ${windowEndSec}.0 — never reset timestamps to 0, and NEVER repeat, echo, or transcribe dialogue from earlier minutes or later sections outside ${windowStartSec}s–${windowEndSec}s). Do NOT skip rapid replies or duplicate any line.")
                appendLine("2. Break speech into crisp, compact 1-2 line speech-balloon cues (ideally 6-14 words, 1.4s to 4.0s each) matching exact speech timing with no missing stretches.")
                appendLine("3. STRICT SPEAKER CONSISTENCY: Match each line to speakerSlot (A-L) by face/lip movement and voice timbre. NEVER assign a new person (such as someone in an embedded news/podcast/video clip) to a slot already occupied by a different person! Either assign them the next unused slot letter (up to L) and register them in speakerUpdates, OR output speakerSlot=UNCERTAIN if they only speak briefly or all 12 slots are taken.")
                appendLine("4. ACCURATE LIPS/MOUTH POSITION & DEAD SPACE: Report the speaking person's actual LIPS/MOUTH center via speakerPosition AND anchorX (0.12..0.88) and anchorY (typically 0.18..0.38 in seated panel/medium shots; NEVER report chest/suit/tie coordinates below the chin) ONLY when you visually confirm THAT person's lips are moving in sync with those words. Also set deadSpaceZone to the cleanest unobstructed background pocket (empty wall/margin/upper corner) that avoids ALL faces, hair/foreheads, hands, and on-screen graphics, and populate avoidBoxes with bounding boxes for ALL visible heads/faces (speaker + bystanders) and lower-third chyrons/text banners so bubbles never clip anyone.")
                appendLine("5. STRICT VISUAL CONFIRMATION (NO MISATTRIBUTIONS!): In speakerUpdates, set isVisuallyConfirmed=true and isRolePlaceholder=false ONLY when you have DIRECT in-video proof of that specific person's real name (an on-screen lower-third/chyron/title card, explicit spoken introduction/address like 'Welcome Michael' or 'I'm Roy', or unmistakable visual match). NEVER guess or stamp a celebrity name from the video title onto a panelist/host who is NOT that person! Until visually confirmed, set isVisuallyConfirmed=false, isRolePlaceholder=true, give a brief appearance/role note in visualVoiceNotes (e.g. 'Man in brown blazer, right seat') so the app keeps them dotted gray until visually confirmed in this or a later window.")
                appendLine("6. Mark non-speech sound/music cues (e.g. '[applause]', '[♪ upbeat synth music]') with dialogueType=SOUND_DESCRIPTION and speakerSlot=UNCERTAIN.")
                appendLine("7. CUTAWAY & REACTION-SHOT CONTINUITY (CRITICAL!): Whenever the camera shows a host or panelist LISTENING SILENTLY, smiling, nodding, or holding cards while a DIFFERENT person's voice speaks off-screen (or during B-roll), NEVER output dialogueType=SPOKEN or point anchorX/anchorY at the silent listener! SPLIT at the exact cut timestamp, KEEP the actual voice's speakerSlot, and tag the cutaway portion as OFF_SCREEN_LEFT, OFF_SCREEN_RIGHT, or OFF_SCREEN_BEHIND.")
                appendLine("8. If there is no new speech or significant sound in ${windowStartSec}s–${windowEndSec}s, return an empty cues array.")
            }

            val youtubeWatchUrl = "https://www.youtube.com/watch?v=$cleanId"
            val requestContent = content {
                fileData(youtubeWatchUrl, "video/mp4")
                text(promptText)
            }

            val activeYtModel = if (preferLiteModel) liteYoutubeVideoModel else youtubeVideoModel
            val response = withTimeout(75_000L) {
                activeYtModel.generateContent(requestContent)
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Gemini returned an empty transcript response."))

            val root = parseJsonObjectLenient(rawJson)
            val summary = root.optString("summary", "").trim()
            val speakerUpdates = parseSpeakerUpdates(root)
            val cuesJson = root.optJSONArray("cues")
            val hardWindowStartMs = windowStartSec.toLong().coerceAtLeast(0L) * 1000L
            val hardWindowEndMs = windowEndSec.toLong().coerceAtLeast(windowStartSec + 1L) * 1000L
            val minWindowMs = (windowStartSec.toLong() - 1L).coerceAtLeast(0L) * 1000L
            val maxWindowMs = (hardWindowEndMs - 1L).coerceAtLeast(minWindowMs)

            // Detect whether Gemini accidentally emitted 0-based chunk-relative timestamps or echoed opening dialogue
            val rawStartSecs = mutableListOf<Double>()
            val rawCueTexts = mutableListOf<String>()
            if (cuesJson != null) {
                for (i in 0 until cuesJson.length()) {
                    val obj = cuesJson.optJSONObject(i) ?: continue
                    val s = obj.optDouble("startSec", -1.0)
                    if (s >= 0.0 && !s.isNaN()) rawStartSecs.add(s)
                    val t = obj.optString("sourceText", "").trim()
                    if (t.isNotBlank()) rawCueTexts.add(t)
                }
            }
            val windowSpanSec = (windowEndSec - windowStartSec).coerceAtLeast(10).toDouble()
            val timestampOffsetSec = resolveYouTubeTimestampOffsetSec(
                rawStartSecs = rawStartSecs,
                windowStartSec = windowStartSec,
                windowEndSec = windowEndSec,
                windowSpanSec = windowSpanSec,
                rawCueTexts = rawCueTexts,
                priorWindowNormalizedTexts = priorWindowNormalizedTexts,
            )
            if (timestampOffsetSec.isNaN()) {
                return Result.success(
                    AiYouTubeTranscriptionResult(
                        cues = emptyList(),
                        speakerUpdates = speakerUpdates,
                        summary = summary,
                    )
                )
            }

            val seenNormalizedInChunk = mutableListOf<Pair<Long, String>>()
            val parsedCues = buildList {
                if (cuesJson != null) {
                    for (i in 0 until cuesJson.length()) {
                        val obj = cuesJson.optJSONObject(i) ?: continue
                        val src = obj.optString("sourceText", "").trim()
                        if (src.isBlank()) continue
                        val rawStartSec = obj.optDouble("startSec", -1.0)
                        if (rawStartSec < 0.0 || rawStartSec.isNaN()) continue
                        val absStartSec = rawStartSec + timestampOffsetSec
                        val rawStartMs = (absStartSec * 1000.0).toLong().coerceAtLeast(0L)
                        if (rawStartMs < minWindowMs || rawStartMs > maxWindowMs) continue
                        val startMs = rawStartMs.coerceIn(hardWindowStartMs, (hardWindowEndMs - 450L).coerceAtLeast(hardWindowStartMs))
                        val normSrc = src.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
                        if (normSrc.isNotBlank() && seenNormalizedInChunk.any { (prevMs, prevNorm) ->
                                prevNorm == normSrc && kotlin.math.abs(prevMs - startMs) < 16_000L
                            }
                        ) {
                            continue
                        }
                        if (normSrc.isNotBlank()) seenNormalizedInChunk.add(startMs to normSrc)
                        val rawEndSec = obj.optDouble("endSec", rawStartSec + 2.2)
                        val absEndSec = rawEndSec + timestampOffsetSec
                        val endMs = (absEndSec * 1000.0).toLong()
                            .coerceAtLeast(startMs + 500L)
                            .coerceAtMost(startMs + 9_500L)
                            .coerceAtMost(hardWindowEndMs)
                        val typeName = obj.optString("dialogueType", "SPOKEN")
                        val rawPos = obj.optString("speakerPosition", "CENTER").trim().uppercase()
                        var dialogueType = DialogueType.entries.firstOrNull { it.name == typeName }
                            ?: DialogueType.SPOKEN
                        if (dialogueType == DialogueType.SPOKEN) {
                            when (rawPos) {
                                "OFF_SCREEN_LEFT" -> dialogueType = DialogueType.OFF_SCREEN_LEFT
                                "OFF_SCREEN_RIGHT" -> dialogueType = DialogueType.OFF_SCREEN_RIGHT
                                "OFF_SCREEN_BEHIND" -> dialogueType = DialogueType.OFF_SCREEN_BEHIND
                                "VOICE_OVER" -> dialogueType = DialogueType.VOICE_OVER
                            }
                        }
                        val rawX = obj.optDouble("anchorX", Double.NaN)
                        val rawY = obj.optDouble("anchorY", Double.NaN)
                        val resolvedX = when {
                            !rawX.isNaN() && rawX in 0.02..0.98 -> rawX.toFloat().coerceIn(0.12f, 0.88f)
                            rawPos == "LEFT" -> 0.22f
                            rawPos == "CENTER_LEFT" -> 0.35f
                            rawPos == "CENTER_RIGHT" -> 0.65f
                            rawPos == "RIGHT" -> 0.78f
                            rawPos == "CENTER" -> 0.50f
                            else -> null
                        }
                        val resolvedY = if (!rawY.isNaN() && rawY in 0.04..0.95) {
                            rawY.toFloat().coerceIn(0.14f, 0.78f)
                        } else if (resolvedX != null) {
                            0.36f
                        } else {
                            null
                        }
                        val rawDeadSpace = obj.optString("deadSpaceZone", "AUTO").trim().uppercase()
                        val resolvedDeadSpace = DeadSpaceZone.entries.firstOrNull { it.name == rawDeadSpace }
                            ?: DeadSpaceZone.AUTO
                        val parsedAvoidBoxes = buildList {
                            val arr = obj.optJSONArray("avoidBoxes")
                            if (arr != null) {
                                for (bIdx in 0 until arr.length().coerceAtMost(6)) {
                                    val bObj = arr.optJSONObject(bIdx) ?: continue
                                    val l = bObj.optDouble("left", Double.NaN)
                                    val t = bObj.optDouble("top", Double.NaN)
                                    val r = bObj.optDouble("right", Double.NaN)
                                    val b = bObj.optDouble("bottom", Double.NaN)
                                    if (!l.isNaN() && !t.isNaN() && !r.isNaN() && !b.isNaN() && r > l && b > t) {
                                        val rect = NormalizedRect(
                                            left = l.toFloat().coerceIn(0f, 1f),
                                            top = t.toFloat().coerceIn(0f, 1f),
                                            right = r.toFloat().coerceIn(0f, 1f),
                                            bottom = b.toFloat().coerceIn(0f, 1f),
                                        )
                                        if (rect.width >= 0.04f && rect.height >= 0.04f && rect.width <= 0.85f && rect.height <= 0.75f) {
                                            add(rect)
                                        }
                                    }
                                }
                            }
                        }
                        val natural = obj.optString("naturalTranslation", src).trim().ifBlank { src }
                        add(
                            AiTranscribedCue(
                                startMs = startMs,
                                endMs = endMs,
                                sourceText = src,
                                naturalTranslation = natural,
                                literalTranslation = obj.optString("literalTranslation", "").trim(),
                                romanization = obj.optString("romanization", "").trim(),
                                dialogueType = dialogueType,
                                suggestedSpeakerSlot = obj.optString("speakerSlot", "UNCERTAIN").trim(),
                                contextReason = obj.optString("contextReason", "").trim(),
                                culturalNote = obj.optString("culturalNote", "").trim(),
                                speakerPosition = rawPos,
                                anchorX = resolvedX,
                                anchorY = resolvedY,
                                deadSpaceZone = resolvedDeadSpace,
                                avoidBoxes = parsedAvoidBoxes,
                            )
                        )
                    }
                }
            }.sortedBy { it.startMs }

            Result.success(
                AiYouTubeTranscriptionResult(
                    cues = parsedCues,
                    speakerUpdates = speakerUpdates,
                    summary = summary,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "YouTube video AI transcription failed ($cleanId ${windowStartSec}s-${windowEndSec}s)", e)
            Result.failure(e)
        }
    }

    suspend fun discoverCastAndRoles(
        title: String,
        channelName: String,
        description: String,
        transcriptSample: List<DialogueEvent>,
        speakers: List<Speaker>,
        shotKeyframes: List<Pair<String, Bitmap>> = emptyList(),
    ): Result<AiCastDiscoveryResult> {
        if (title.isBlank() && channelName.isBlank() && description.isBlank() && transcriptSample.isEmpty()) {
            return Result.success(AiCastDiscoveryResult())
        }
        return try {
            val sampleText = transcriptSample.take(16).joinToString("\n") { evt ->
                "  [${evt.startTimeMs / 1000}s] (${evt.speakerId ?: "?"}) ${evt.sourceText}"
            }
            val slotHints = speakers.take(4).mapIndexed { idx, spk ->
                "Slot ${('A' + idx)}: current=\"${spk.displayName}\", locked=${spk.isLocked}"
            }.joinToString("\n")

            var groundedNotes = ""
            val queries = mutableListOf<String>()
            val sources = mutableListOf<String>()

            if (title.isNotBlank() || channelName.isNotBlank() || description.isNotBlank()) {
                val researchPrompt = buildString {
                    appendLine("Identify the real people, hosts, guests, interviewers, actors, or named characters in this video using Google Search and the metadata below.")
                    if (title.isNotBlank()) appendLine("Title: $title")
                    if (channelName.isNotBlank()) appendLine("Channel/Creator: $channelName")
                    if (description.isNotBlank()) appendLine("Description excerpt: ${description.take(650)}")
                    if (sampleText.isNotBlank()) {
                        appendLine("Opening transcript lines:")
                        appendLine(sampleText)
                    }
                    appendLine("List likely speakers/participants and any role context clearly.")
                }
                val researchContent = content {
                    shotKeyframes.take(3).forEach { (shotId, bmp) ->
                        text("Scene frame ($shotId):")
                        image(bmp)
                    }
                    text(researchPrompt)
                }
                val searchRes = runCatching {
                    withTimeout(18_000L) {
                        searchGroundedCastModel.generateContent(researchContent)
                    }
                }.getOrNull()
                if (searchRes != null) {
                    groundedNotes = searchRes.text.orEmpty().trim()
                    val gm = searchRes.candidates.firstOrNull()?.groundingMetadata
                    gm?.webSearchQueries?.let { queries.addAll(it.filter { q -> q.isNotBlank() }) }
                    gm?.groundingChunks?.forEach { chunk ->
                        val web = chunk.web
                        val label = listOfNotNull(
                            web?.title?.takeIf { it.isNotBlank() },
                            web?.uri?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (label.isNotBlank()) sources.add(label)
                    }
                }
            }

            val extractionPrompt = buildString {
                appendLine("Map slots A, B, C, D to speaker names inspired by movie cast credits.")
                if (title.isNotBlank()) appendLine("Video Title: $title")
                if (channelName.isNotBlank()) appendLine("Channel/Creator: $channelName")
                if (description.isNotBlank()) appendLine("Description: ${description.take(600)}")
                if (groundedNotes.isNotBlank()) {
                    appendLine("Web Search & Context Findings:")
                    appendLine(groundedNotes.take(1500))
                }
                if (slotHints.isNotBlank()) {
                    appendLine("Current Slots:")
                    appendLine(slotHints)
                }
                if (sampleText.isNotBlank()) {
                    appendLine("Dialogue Lines:")
                    appendLine(sampleText)
                }
                appendLine("Rules:")
                appendLine("1. If a real name/channel host/character name is confidently known from title, metadata, search, dialogue intro ('I'm Maya'), or nameplate visible in a frame, use that concise name (1-3 words max) and set isRolePlaceholder=false.")
                appendLine("2. If the speaker is unnamed or incidental, take inspiration from movie cast credits and assign a crisp descriptive role such as 'Host', 'Hot Dog Vendor', 'Civilian Woman 1', 'Interviewer', 'Security Guard', 'Caller' and set isRolePlaceholder=true.")
                appendLine("3. Never output 'Speaker A', 'Speaker B', or 'Unknown'. Always give each active slot either a real name or a movie-credit role.")
            }
            val extractionContent = content {
                shotKeyframes.take(3).forEach { (shotId, bmp) ->
                    text("Scene frame ($shotId):")
                    image(bmp)
                }
                text(extractionPrompt)
            }
            val rosterResponse = withTimeout(18_000L) {
                castRosterModel.generateContent(extractionContent)
            }
            val rawJson = rosterResponse.text
                ?: return Result.success(
                    AiCastDiscoveryResult(
                        groundingQueries = queries.distinct(),
                        groundingSources = sources.distinct().take(6),
                    )
                )

            val root = JSONObject(rawJson)
            val summary = root.optString("summary", "").trim()
            val arr = root.optJSONArray("assignments")
            val assignments = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val slot = obj.optString("slot", "").trim()
                        val displayName = obj.optString("displayName", "").trim()
                        if (slot in listOf("A", "B", "C", "D") && displayName.isNotBlank()) {
                            add(
                                AiCastSlotAssignment(
                                    slot = slot,
                                    displayName = displayName.take(28),
                                    isRolePlaceholder = obj.optBoolean("isRolePlaceholder", false),
                                    rationale = obj.optString("rationale", "").trim(),
                                )
                            )
                        }
                    }
                }
            }
            Result.success(
                AiCastDiscoveryResult(
                    assignments = assignments,
                    summary = summary,
                    groundingQueries = queries.distinct(),
                    groundingSources = sources.distinct().take(6),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Cast discovery failed", e)
            Result.failure(e)
        }
    }

    suspend fun transcribeAndTranslateAudio(
        wavBytes: ByteArray,
        chunkStartMs: Long,
        sourceLanguage: String,
        targetLanguage: String,
        glossary: List<GlossaryEntry> = emptyList(),
        audioMimeType: String = "audio/wav",
        preferLiteModel: Boolean = true,
    ): Result<List<AiTranscribedCue>> {
        if (wavBytes.isEmpty()) return Result.success(emptyList())
        return try {
            val glossaryBlock = if (glossary.isNotEmpty()) {
                buildString {
                    appendLine("Follow these consistent glossary terms:")
                    glossary.forEach { g -> appendLine("  - ${g.term} -> ${g.translation}") }
                }
            } else ""

            val isSameLanguageEnhancement = sourceLanguage.equals(targetLanguage, ignoreCase = true)
            val taskLine = if (isSameLanguageEnhancement) {
                "Refine, tighten, and annotate all spoken dialogue and ambient sound cues cleanly in $targetLanguage for Deaf/hard-of-hearing viewers."
            } else {
                "Transcribe all spoken dialogue and significant sound effects from this audio clip and translate into $targetLanguage."
            }
            val promptText = """
                $taskLine
                Source language hint: $sourceLanguage
                Target output language: $targetLanguage
                $glossaryBlock
                Rules:
                - Segment into crisp 1-2 line subtitle cues with accurate startOffsetSec and endOffsetSec relative to start of this audio chunk (0.0s).
                - Keep naturalTranslation concise and evocative for Deaf/hard-of-hearing cinema viewers reading inside spatial speech balloons.
                - Use SOUND_DESCRIPTION for impactful ambient SFX, music shifts, and emotional vocal non-speech sounds (e.g. "[rain patters softly]", "[♪ tense strings swell]", "[door slams]", "[sighs quietly]") so viewers feel the sonic atmosphere.
                - Prefix softly murmured lines with calm natural wording and flag clear off-screen voices as OFF_SCREEN_BEHIND.
                - Assign consistent speakerSlot (A/B/C/D) by vocal timbre & turn-taking.
                - Include a brief culturalNote only if there is an idiom, honorific nuance, or slang worth noting.
            """.trimIndent()

            val promptContent = content {
                inlineData(wavBytes, audioMimeType)
                text(promptText)
            }

            val activeAudioModel = if (preferLiteModel) liteAudioAsrModel else audioAsrModel
            val response = withTimeout(60_000L) {
                activeAudioModel.generateContent(promptContent)
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Audio transcription returned an empty response."))

            val root = JSONObject(rawJson)
            val cuesJson = root.getJSONArray("cues")
            val parsed = buildList {
                for (i in 0 until cuesJson.length()) {
                    val obj = cuesJson.getJSONObject(i)
                    val sMs = chunkStartMs + (obj.optDouble("startOffsetSec", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                    val eMs = (chunkStartMs + (obj.optDouble("endOffsetSec", 1.5) * 1000.0).toLong())
                        .coerceAtLeast(sMs + 950L)
                    val typeName = obj.optString("dialogueType", "SPOKEN")
                    val dialogueType = DialogueType.entries.firstOrNull { it.name == typeName } ?: DialogueType.SPOKEN
                    val src = obj.optString("sourceText", "").trim()
                    if (src.isNotBlank()) {
                        add(
                            AiTranscribedCue(
                                startMs = sMs,
                                endMs = eMs,
                                sourceText = src,
                                naturalTranslation = obj.optString("naturalTranslation", src).ifBlank { src },
                                literalTranslation = obj.optString("literalTranslation", ""),
                                romanization = obj.optString("romanization", ""),
                                dialogueType = dialogueType,
                                suggestedSpeakerSlot = obj.optString("speakerSlot", "UNCERTAIN"),
                                contextReason = obj.optString("contextReason", ""),
                                culturalNote = obj.optString("culturalNote", ""),
                            )
                        )
                    }
                }
            }
            Result.success(parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio transcription failed", e)
            Result.failure(e)
        }
    }

    suspend fun translateBatch(
        events: List<DialogueEvent>,
        sourceLanguage: String,
        targetLanguage: String,
        speakers: List<Speaker> = emptyList(),
        glossary: List<GlossaryEntry> = emptyList(),
        priorContextLines: List<DialogueEvent> = emptyList(),
        videoContext: String = "",
        preferLiteModel: Boolean = true,
        onDiscoveredSpeakerUpdates: (List<AiCastSlotAssignment>) -> Unit = {},
    ): Result<List<AiTranslatedLine>> {
        if (events.isEmpty()) return Result.success(emptyList())
        return try {
            val isSameLanguage = sourceLanguage.equals(targetLanguage, ignoreCase = true)
            val payload = buildString {
                if (isSameLanguage) {
                    appendLine("You are refining and enriching $targetLanguage film/video subtitles into crisp, Deaf-friendly spatial manga speech balloons with speaker attribution, off-screen flags, and [SFX / music] cues.")
                } else {
                    appendLine("You are translating video dialogue into $targetLanguage for spatial manga speech balloons and attributing speakers using conversational context + audio cues.")
                }
                appendLine("Source language: $sourceLanguage")
                appendLine("Target language: $targetLanguage")
                if (videoContext.isNotBlank()) {
                    appendLine("Video Metadata Context: $videoContext")
                }
                if (speakers.isNotEmpty()) {
                    appendLine("Known session speaker roster:")
                    speakers.forEachIndexed { idx, spk ->
                        val slot = ('A' + idx).toString()
                        val voiceNote = spk.voicePitchHz?.let { " | typical pitch ~${it.roundToInt()}Hz" } ?: ""
                        val roleTag = if (spk.isRolePlaceholder) " [movie-credit role, upgrade if real name spoken]" else ""
                        appendLine("  Slot $slot: ${spk.displayName}$voiceNote$roleTag")
                    }
                }
                if (glossary.isNotEmpty()) {
                    appendLine("Series Glossary (MUST stay consistent):")
                    glossary.forEach { entry ->
                        val note = if (entry.note.isNotBlank()) " (${entry.note})" else ""
                        appendLine("  - ${entry.term} => ${entry.translation}$note")
                    }
                }
                if (priorContextLines.isNotEmpty()) {
                    appendLine("Preceding lines for story continuity (DO NOT re-emit these, only use for tone & context):")
                    priorContextLines.forEach { prev ->
                        appendLine("  [Prior ${prev.speakerId ?: "?"}] ${prev.sourceText} -> ${prev.translatedText}")
                    }
                }
                appendLine("Rules:")
                appendLine("1. Preserve tone, speaker personality, honorifics, humor, and sentence continuity across lines.")
                appendLine("2. Keep naturalTranslation concise enough to read comfortably in a tight speech balloon.")
                appendLine("3. Provide literalTranslation and romanization (empty string if source already uses Latin script).")
                appendLine("4. Infer speakerSlot (A/B/C/D) & turnHint using conversational Q&A pairs, direct-name address (calling 'Kenji' means the OTHER speaker is speaking), speech register/pronouns, and acoustic voice pitch/cluster cues.")
                appendLine("5. Put a crisp 4-8 word clue in contextReason and an optional 1-sentence culturalNote if there is an idiom/honorific/slang nuance.")
                appendLine("6. If dialogue reveals a real name or better movie-credit role (e.g., 'Hot Dog Vendor', 'Civilian Woman 1', or a spoken name) for a generic/placeholder slot, emit an item in speakerUpdates.")
                appendLine()
                appendLine("Dialogue segments to translate:")
                events.forEachIndexed { index, evt ->
                    appendLine(formatEventLine(index, evt))
                }
            }

            val activeTranslationModel = if (preferLiteModel) liteModel else model
            val response = withTimeout(20_000L) {
                activeTranslationModel.generateContent(payload)
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Translation model returned an empty response."))

            val root = JSONObject(rawJson)
            val updates = parseSpeakerUpdates(root)
            if (updates.isNotEmpty()) {
                onDiscoveredSpeakerUpdates(updates)
            }
            val linesJson = root.getJSONArray("lines")
            val parsed = buildList {
                for (i in 0 until linesJson.length()) {
                    val obj = linesJson.getJSONObject(i)
                    val typeName = obj.optString("dialogueType", "SPOKEN")
                    val dialogueType = DialogueType.entries.firstOrNull { it.name == typeName }
                        ?: DialogueType.SPOKEN
                    add(
                        AiTranslatedLine(
                            id = obj.getString("id"),
                            naturalTranslation = obj.getString("naturalTranslation"),
                            literalTranslation = obj.optString("literalTranslation", ""),
                            romanization = obj.optString("romanization", ""),
                            dialogueTypeHint = dialogueType,
                            speakerTurnHint = obj.optString("turnHint", "UNCERTAIN"),
                            suggestedSpeakerSlot = obj.optString("speakerSlot", "UNCERTAIN"),
                            contextReason = obj.optString("contextReason", ""),
                            culturalNote = obj.optString("culturalNote", ""),
                        )
                    )
                }
            }
            Result.success(parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Translation batch failed", e)
            Result.failure(e)
        }
    }

    suspend fun analyzeConversationSpeakers(
        events: List<DialogueEvent>,
        speakers: List<Speaker>,
        shotKeyframes: List<Pair<String, Bitmap>> = emptyList(),
        videoContext: String = "",
        preferLiteModel: Boolean = true,
        onDiscoveredSpeakerUpdates: (List<AiCastSlotAssignment>) -> Unit = {},
    ): Result<List<AiTranslatedLine>> {
        if (events.isEmpty()) return Result.success(emptyList())
        return try {
            val payload = buildString {
                appendLine("Analyze this conversation transcript together with per-line acoustic audio metadata and representative video shot frames to determine who is speaking at each turn.")
                if (videoContext.isNotBlank()) {
                    appendLine("Video Metadata Context: $videoContext")
                }
                if (speakers.isNotEmpty()) {
                    appendLine("Session speaker roster:")
                    speakers.forEachIndexed { idx, spk ->
                        val slot = ('A' + idx).toString()
                        val voiceNote = spk.voicePitchHz?.let { " | typical voice pitch ~${it.roundToInt()}Hz" } ?: ""
                        val roleTag = if (spk.isRolePlaceholder) " [movie-credit role, upgrade if real name seen/spoken]" else ""
                        appendLine("  Slot $slot: ${spk.displayName}$voiceNote$roleTag")
                    }
                }
                appendLine("Reasoning guidelines:")
                appendLine("- Use question/answer adjacency, vocative direct address (if speaker A's name is called, the speaker is usually B), tone shifts, and continued incomplete clauses.")
                appendLine("- Fuse visual cues from the attached shot frames (screen left/right positions, expression, visible nameplates) with vocal pitch Hz, spectral brightness, and acoustic voiceClusterId consistency.")
                appendLine("- Respect existing locked anchors if provided.")
                appendLine("- If frames or dialogue reveal a real name or clearer movie-credit role (e.g. 'Hot Dog Vendor', 'Civilian Woman 1'), add it to speakerUpdates.")
                appendLine()
                appendLine("Dialogue sequence:")
                events.forEachIndexed { index, evt ->
                    appendLine(formatEventLine(index, evt))
                }
            }

            val multimodalContent = content {
                shotKeyframes.forEach { (shotId, bmp) ->
                    text("Keyframe for $shotId:")
                    image(bmp)
                }
                text(payload)
            }

            val activeContextModel = if (preferLiteModel) liteContextAttributionModel else contextAttributionModel
            val response = withTimeout(20_000L) {
                activeContextModel.generateContent(multimodalContent)
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Context attribution returned an empty response."))

            val root = JSONObject(rawJson)
            val updates = parseSpeakerUpdates(root)
            if (updates.isNotEmpty()) {
                onDiscoveredSpeakerUpdates(updates)
            }
            val linesJson = root.getJSONArray("lines")
            val byId = events.associateBy { it.id }
            val parsed = buildList {
                for (i in 0 until linesJson.length()) {
                    val obj = linesJson.getJSONObject(i)
                    val id = obj.getString("id")
                    val orig = byId[id]
                    val typeName = obj.optString("dialogueType", orig?.dialogueType?.name ?: "SPOKEN")
                    val dialogueType = DialogueType.entries.firstOrNull { it.name == typeName }
                        ?: (orig?.dialogueType ?: DialogueType.SPOKEN)
                    add(
                        AiTranslatedLine(
                            id = id,
                            naturalTranslation = orig?.translatedText.orEmpty(),
                            literalTranslation = orig?.literalText.orEmpty(),
                            romanization = orig?.romanization.orEmpty(),
                            dialogueTypeHint = dialogueType,
                            speakerTurnHint = obj.optString("turnHint", "UNCERTAIN"),
                            suggestedSpeakerSlot = obj.optString("speakerSlot", "UNCERTAIN"),
                            contextReason = obj.optString("contextReason", ""),
                        )
                    )
                }
            }
            Result.success(parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Context speaker attribution failed", e)
            Result.failure(e)
        }
    }

    private fun parseSpeakerUpdates(root: JSONObject): List<AiCastSlotAssignment> {
        val arr = root.optJSONArray("speakerUpdates") ?: return emptyList()
        val validSlots = setOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val slot = obj.optString("slot", "").trim().uppercase()
                val name = obj.optString("displayName", "").trim()
                val isRole = obj.optBoolean("isRolePlaceholder", false)
                val visuallyConfirmed = if (obj.has("isVisuallyConfirmed")) {
                    obj.optBoolean("isVisuallyConfirmed", false)
                } else {
                    !isRole
                }
                val notes = obj.optString("visualVoiceNotes", "").trim().take(80)
                if (slot in validSlots && (name.isNotBlank() || notes.isNotBlank())) {
                    add(
                        AiCastSlotAssignment(
                            slot = slot,
                            displayName = cleanWordBoundaryName(name, 26),
                            isRolePlaceholder = isRole,
                            rationale = obj.optString("rationale", "").trim(),
                            visualVoiceNotes = notes,
                            isVisuallyConfirmed = visuallyConfirmed,
                        )
                    )
                }
            }
        }
    }

    private fun cleanWordBoundaryName(raw: String, maxChars: Int): String {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        if (trimmed.length <= maxChars) return trimmed
        val cut = trimmed.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace >= 8) cut.substring(0, lastSpace).trimEnd(',', '-', ':', '/') else cut.trim()
    }

    private fun formatEventLine(index: Int, evt: DialogueEvent): String {
        val audioDesc = evt.audioFeatures?.let { af ->
            val pitchText = if (af.pitchHz > 0f) "${af.pitchHz.roundToInt()}Hz" else "unpitched"
            val cluster = af.voiceClusterId ?: "none"
            val shoutFlag = if (af.isShouted) ", SHOUTED" else ""
            " | audio=(pitch=$pitchText, brightness=${(af.spectralBrightness * 100).roundToInt()}%, energy=${(af.speechEnergy * 100).roundToInt()}%, cluster=$cluster$shoutFlag)"
        } ?: ""
        val lockNote = if (evt.lockedByUser && evt.speakerId != null) " | userLocked=${evt.speakerId}" else ""
        return "${index + 1}. id=${evt.id} | shot=${evt.shotId} | [${evt.startTimeMs}ms-${evt.endTimeMs}ms] | type=${evt.dialogueType.name}$audioDesc$lockNote | src=${evt.sourceText} | tr=${evt.translatedText}"
    }

    private fun parseJsonObjectLenient(raw: String): JSONObject {
        val trimmed = raw.trim()
        val unwrapped = if (trimmed.startsWith("```")) {
            trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        } else {
            trimmed
        }
        return try {
            JSONObject(unwrapped)
        } catch (_: Exception) {
            val firstBrace = unwrapped.indexOf('{')
            val lastBrace = unwrapped.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                JSONObject(unwrapped.substring(firstBrace, lastBrace + 1))
            } else {
                JSONObject(unwrapped)
            }
        }
    }

    companion object {
        fun isQuotaOrCreditExhaustedError(error: Throwable?): Boolean {
            if (error == null) return false
            val combined = buildString {
                var cur: Throwable? = error
                var depth = 0
                while (cur != null && depth < 5) {
                    append(cur.javaClass.simpleName)
                    append(' ')
                    append(cur.message.orEmpty())
                    append(' ')
                    cur = cur.cause
                    depth++
                }
            }.lowercase()
            return combined.contains("prepayment credits") ||
                combined.contains("credits are depleted") ||
                combined.contains("resource_exhausted") ||
                combined.contains("quota") ||
                combined.contains("rate limit") ||
                combined.contains("billing") ||
                Regex("\\b429\\b").containsMatchIn(combined)
        }

        fun isAppCheckTransientError(error: Throwable?): Boolean {
            if (error == null) return false
            val msg = buildString {
                var cur: Throwable? = error
                var depth = 0
                while (cur != null && depth < 4) {
                    append(cur.message.orEmpty()).append(' ')
                    cur = cur.cause
                    depth++
                }
            }.lowercase()
            return msg.contains("app check") ||
                msg.contains("token is invalid") ||
                msg.contains("attestation") ||
                msg.contains("play integrity") ||
                Regex("\\b403\\b").containsMatchIn(msg)
        }

        /**
         * Detects when the model emitted 0-based chunk-relative seconds (e.g. 0..40s) for a later
         * video window (`windowStartSec >= 15`) and returns `windowStartSec.toDouble()` to shift them
         * back to absolute video timeline coordinates instead of silently dropping the entire chunk.
         */
        fun resolveYouTubeTimestampOffsetSec(
            rawStartSecs: List<Double>,
            windowStartSec: Int,
            windowEndSec: Int,
            windowSpanSec: Double = (windowEndSec - windowStartSec).coerceAtLeast(10).toDouble(),
            rawCueTexts: List<String> = emptyList(),
            priorWindowNormalizedTexts: Set<String> = emptySet(),
        ): Double {
            if (windowStartSec < 15 || rawStartSecs.isEmpty()) return 0.0
            val absMatches = rawStartSecs.count { it in (windowStartSec - 1.5)..(windowEndSec + 2.0) }
            val relMatches = rawStartSecs.count { it in 0.0..(windowSpanSec + 6.0) }
            val strictlyPreWindow = rawStartSecs.count { it < (windowStartSec - 2.0) && it in 0.0..(windowSpanSec + 6.0) }
            val majorityRelative = windowStartSec >= 20 &&
                strictlyPreWindow > absMatches &&
                relMatches >= ((rawStartSecs.size * 6) / 10).coerceAtLeast(2)
            val shouldShift = (absMatches == 0 && relMatches > 0) || majorityRelative
            if (shouldShift && priorWindowNormalizedTexts.isNotEmpty() && rawCueTexts.isNotEmpty()) {
                val echoedMatches = rawCueTexts.count { txt ->
                    val norm = txt.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
                    norm.length >= 12 && norm in priorWindowNormalizedTexts
                }
                if (echoedMatches >= 2 || (echoedMatches * 10 >= rawCueTexts.size * 4)) {
                    return Double.NaN
                }
            }
            return if (shouldShift) {
                windowStartSec.toDouble()
            } else {
                0.0
            }
        }
    }
}

