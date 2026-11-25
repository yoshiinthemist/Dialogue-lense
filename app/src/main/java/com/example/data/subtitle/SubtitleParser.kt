package com.example.data.subtitle

import com.example.data.model.DialogueEvent
import com.example.data.model.DialogueType
import com.example.data.model.TranslationMode
import java.util.Locale
import java.util.UUID

data class RawSubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val inferredType: DialogueType = DialogueType.SPOKEN,
)

object SubtitleParser {

    fun parse(content: String, fileName: String = ""): List<RawSubtitleCue> {
        val trimmed = content.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return emptyList()

        val lowerName = fileName.lowercase(Locale.US)
        return when {
            lowerName.endsWith(".vtt") || trimmed.startsWith("WEBVTT") -> parseSrtOrVtt(trimmed)
            lowerName.endsWith(".ass") || lowerName.endsWith(".ssa") ||
                trimmed.contains("[Events]", ignoreCase = true) -> parseAss(trimmed)
            else -> parseSrtOrVtt(trimmed)
        }
    }

    private fun parseSrtOrVtt(input: String): List<RawSubtitleCue> {
        val normalized = input.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = mutableListOf<RawSubtitleCue>()
        val timePattern = Regex(
            """(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})"""
        )

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (lines.first().startsWith("WEBVTT") || lines.first().startsWith("NOTE")) continue

            val timeLineIndex = lines.indexOfFirst { timePattern.containsMatchIn(it) }
            if (timeLineIndex == -1) continue

            val match = timePattern.find(lines[timeLineIndex]) ?: continue
            val startMs = toMillis(
                hours = match.groupValues[1].toLongOrNull() ?: 0L,
                minutes = match.groupValues[2].toLongOrNull() ?: 0L,
                seconds = match.groupValues[3].toLongOrNull() ?: 0L,
                millis = match.groupValues[4].toLongOrNull() ?: 0L,
            )
            val endMs = toMillis(
                hours = match.groupValues[5].toLongOrNull() ?: 0L,
                minutes = match.groupValues[6].toLongOrNull() ?: 0L,
                seconds = match.groupValues[7].toLongOrNull() ?: 0L,
                millis = match.groupValues[8].toLongOrNull() ?: 0L,
            )
            if (endMs <= startMs) continue

            val rawBody = lines.drop(timeLineIndex + 1).joinToString(" ")
            val cleaned = cleanTags(rawBody)
            if (cleaned.isNotBlank()) {
                cues.add(
                    RawSubtitleCue(
                        startTimeMs = startMs,
                        endTimeMs = endMs,
                        text = cleaned,
                        inferredType = inferDialogueType(rawBody, cleaned),
                    )
                )
            }
        }
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseAss(input: String): List<RawSubtitleCue> {
        val cues = mutableListOf<RawSubtitleCue>()
        val lines = input.replace("\r\n", "\n").lines()
        var formatFields: List<String> = emptyList()
        var inEvents = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("[Events]", ignoreCase = true)) {
                inEvents = true
                continue
            }
            if (trimmed.startsWith("[") && !trimmed.equals("[Events]", ignoreCase = true)) {
                inEvents = false
                continue
            }
            if (!inEvents) continue

            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                formatFields = trimmed.substringAfter(":").split(",").map { it.trim().lowercase(Locale.US) }
            } else if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = trimmed.substringAfter(":").trim()
                val colCount = if (formatFields.isNotEmpty()) formatFields.size else 10
                val parts = payload.split(",", limit = colCount)
                if (parts.size < 4) continue

                val startIdx = formatFields.indexOf("start").takeIf { it >= 0 } ?: 1
                val endIdx = formatFields.indexOf("end").takeIf { it >= 0 } ?: 2
                val styleIdx = formatFields.indexOf("style").takeIf { it >= 0 } ?: 3
                val textIdx = formatFields.indexOf("text").takeIf { it >= 0 } ?: (parts.lastIndex)

                val startMs = parseAssTimestamp(parts.getOrNull(startIdx).orEmpty()) ?: continue
                val endMs = parseAssTimestamp(parts.getOrNull(endIdx).orEmpty()) ?: continue
                if (endMs <= startMs) continue
                val style = parts.getOrNull(styleIdx).orEmpty()
                val rawText = parts.getOrNull(textIdx).orEmpty()
                    .replace("\\N", " ")
                    .replace("\\n", " ")
                    .replace(Regex("""\{[^}]*}"""), "")
                    .trim()

                if (rawText.isNotBlank()) {
                    val type = when {
                        style.contains("narr", ignoreCase = true) -> DialogueType.NARRATION
                        style.contains("sfx", ignoreCase = true) -> DialogueType.SOUND_DESCRIPTION
                        else -> inferDialogueType(rawText, rawText)
                    }
                    cues.add(RawSubtitleCue(startMs, endMs, rawText, type))
                }
            }
        }
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseAssTimestamp(raw: String): Long? {
        val m = Regex("""(\d+):(\d{2}):(\d{2})[.](\d{2})""").find(raw.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val s = m.groupValues[3].toLongOrNull() ?: 0L
        val cs = m.groupValues[4].toLongOrNull() ?: 0L
        return toMillis(h, min, s, cs * 10L)
    }

    private fun toMillis(hours: Long, minutes: Long, seconds: Long, millis: Long): Long =
        ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis

    private fun cleanTags(text: String): String =
        text.replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun inferDialogueType(rawBody: String, cleaned: String): DialogueType {
        val lower = cleaned.lowercase(Locale.US)
        return when {
            lower.contains("[narrator]") || lower.contains("(narrator)") ||
                lower.startsWith("narrator:") || lower.startsWith("[narration]") ->
                DialogueType.NARRATION
            lower.contains("(v.o.)") || lower.contains("[v.o.]") || lower.contains("voice-over") ->
                DialogueType.VOICE_OVER
            lower.contains("(off-screen)") || lower.contains("[off-screen]") ->
                DialogueType.OFF_SCREEN_BEHIND
            lower.startsWith("[") && lower.endsWith("]") -> DialogueType.SOUND_DESCRIPTION
            rawBody.trim().startsWith("<i>", ignoreCase = true) && rawBody.trim().endsWith("</i>", ignoreCase = true) ->
                DialogueType.INTERNAL_MONOLOGUE
            else -> DialogueType.SPOKEN
        }
    }

    fun parseSmartTranscript(
        content: String,
        fallbackTotalMs: Long = 180_000L,
    ): List<RawSubtitleCue> {
        val structured = parse(content)
        if (structured.isNotEmpty()) return structured

        val lines = content.replace("\r\n", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val totalSlotsDuration = lines.sumOf { line ->
            (1500L + line.length * 68L).coerceIn(1800L, 5200L)
        }.coerceAtLeast(1000L)

        val scale = if (totalSlotsDuration > fallbackTotalMs && fallbackTotalMs > 15_000L) {
            fallbackTotalMs.toFloat() / totalSlotsDuration.toFloat()
        } else {
            1f
        }

        val cues = mutableListOf<RawSubtitleCue>()
        var cursorMs = 500L
        for (line in lines) {
            val rawDur = (1500L + line.length * 68L).coerceIn(1800L, 5200L)
            val dur = (rawDur * scale).toLong().coerceAtLeast(1100L)
            val cleaned = cleanTags(line)
            if (cleaned.isNotBlank()) {
                cues.add(
                    RawSubtitleCue(
                        startTimeMs = cursorMs,
                        endTimeMs = cursorMs + dur,
                        text = cleaned,
                        inferredType = inferDialogueType(line, cleaned),
                    )
                )
            }
            cursorMs += dur + 220L
        }
        return cues
    }

    /** Imported captions and translated edits keep the source timestamps, including intentional overlaps. */
    fun inSourceTimeOrder(events: List<DialogueEvent>): List<DialogueEvent> =
        events.sortedBy { it.startTimeMs }

    /**
     * Ensures readable minimum dwell time (>= 1050ms unless constrained by next cue)
     * so rapid micro-cues don't flicker too quickly to read.
     */
    fun applyReadingPacingGuardrails(events: List<DialogueEvent>): List<DialogueEvent> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.startTimeMs }
        return sorted.mapIndexed { idx, evt ->
            val nextStart = sorted.getOrNull(idx + 1)?.startTimeMs ?: Long.MAX_VALUE
            val maxAllowedEnd = (nextStart - 60L).coerceAtLeast(evt.startTimeMs)
            val desiredMinEnd = evt.startTimeMs + 1050L
            val adjustedEnd = maxOf(evt.endTimeMs, minOf(desiredMinEnd, maxAllowedEnd))
            evt.copy(endTimeMs = adjustedEnd)
        }
    }

    enum class SubtitleExportFormat(
        val extension: String,
        val label: String,
        val mimeType: String,
    ) {
        SRT("srt", ".srt", "application/x-subrip"),
        VTT("vtt", ".vtt", "text/vtt"),
        ASS("ass", ".ass", "text/plain"),
    }

    fun exportSrt(
        events: List<DialogueEvent>,
        speakers: List<com.example.data.model.Speaker> = emptyList(),
        includeSpeakerPrefixes: Boolean = false,
        translationMode: TranslationMode = TranslationMode.NATURAL,
    ): String {
        val sb = StringBuilder()
        events.sortedBy { it.startTimeMs }.forEachIndexed { idx, event ->
            sb.append(idx + 1).append('\n')
            sb.append(formatSrtTime(event.startTimeMs))
                .append(" --> ")
                .append(formatSrtTime(event.endTimeMs))
                .append('\n')
            sb.append(formatExportLine(event, speakers, includeSpeakerPrefixes, translationMode))
                .append("\n\n")
        }
        return sb.toString()
    }

    fun exportVtt(
        events: List<DialogueEvent>,
        speakers: List<com.example.data.model.Speaker> = emptyList(),
        includeSpeakerPrefixes: Boolean = false,
        translationMode: TranslationMode = TranslationMode.NATURAL,
    ): String {
        val sb = StringBuilder("WEBVTT\n\n")
        events.sortedBy { it.startTimeMs }.forEachIndexed { idx, event ->
            sb.append(idx + 1).append('\n')
            sb.append(formatVttTime(event.startTimeMs))
                .append(" --> ")
                .append(formatVttTime(event.endTimeMs))
                .append('\n')
            sb.append(formatExportLine(event, speakers, includeSpeakerPrefixes, translationMode))
                .append("\n\n")
        }
        return sb.toString()
    }

    fun exportFormatted(
        project: com.example.data.model.DialogueLensProject,
        format: SubtitleExportFormat,
        includeSpeakerPrefixes: Boolean = true,
        translationMode: TranslationMode = project.translationMode,
    ): String {
        return when (format) {
            SubtitleExportFormat.SRT -> exportSrt(
                events = project.dialogueEvents,
                speakers = project.speakers,
                includeSpeakerPrefixes = includeSpeakerPrefixes,
                translationMode = translationMode,
            )
            SubtitleExportFormat.VTT -> exportVtt(
                events = project.dialogueEvents,
                speakers = project.speakers,
                includeSpeakerPrefixes = includeSpeakerPrefixes,
                translationMode = translationMode,
            )
            SubtitleExportFormat.ASS -> exportAss(
                if (translationMode == project.translationMode) {
                    project
                } else {
                    project.copy(translationMode = translationMode)
                }
            )
        }
    }

    fun exportPreview(
        project: com.example.data.model.DialogueLensProject,
        format: SubtitleExportFormat,
        includeSpeakerPrefixes: Boolean = true,
        translationMode: TranslationMode = project.translationMode,
        maxCues: Int = 2,
    ): String {
        val previewProject = project.copy(
            translationMode = translationMode,
            dialogueEvents = project.dialogueEvents.sortedBy { it.startTimeMs }.take(maxCues),
        )
        return exportFormatted(
            project = previewProject,
            format = format,
            includeSpeakerPrefixes = includeSpeakerPrefixes,
            translationMode = translationMode,
        ).trim()
    }

    fun suggestedExportFilename(title: String, format: SubtitleExportFormat): String {
        val clean = title
            .trim()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.')
            .take(44)
            .ifBlank { "dialoguelens_subtitles" }
        return "$clean.${format.extension}"
    }

    private fun formatExportLine(
        event: DialogueEvent,
        speakers: List<com.example.data.model.Speaker>,
        includeSpeakerPrefixes: Boolean,
        translationMode: TranslationMode = TranslationMode.NATURAL,
    ): String {
        val mainLine = event.activeText(translationMode).trim()
        val body = if (translationMode == TranslationMode.LEARNING &&
            event.romanization.isNotBlank() &&
            !mainLine.contains(event.romanization.trim(), ignoreCase = true)
        ) {
            "$mainLine\n(${event.romanization.trim()})"
        } else {
            mainLine
        }
        if (!includeSpeakerPrefixes || event.dialogueType == DialogueType.SOUND_DESCRIPTION) {
            return body
        }
        val spk = speakers.firstOrNull { it.id == event.speakerId } ?: return body
        val name = spk.displayName.trim()
        if (name.isBlank() ||
            (!spk.hasDistinctCharacterName && !spk.isLocked) ||
            body.startsWith("[") ||
            body.startsWith("$name:", ignoreCase = true)
        ) {
            return body
        }
        return "[$name] $body"
    }

    fun formatVttTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val h = clamped / 3_600_000L
        val m = (clamped % 3_600_000L) / 60_000L
        val s = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, millis)
    }

    /**
     * Exports a SubStation Alpha (.ass) script preserving spatial \pos(x,y) coordinates,
     * character outline colors, shout emphasis bolding, italic monologues, and bottom SFX styling.
     */
    fun exportAss(
        project: com.example.data.model.DialogueLensProject,
        layoutEngine: com.example.domain.layout.BalloonLayoutEngine = com.example.domain.layout.BalloonLayoutEngine(),
    ): String {
        val playW = project.videoSource.width.coerceAtLeast(1280)
        val playH = project.videoSource.height.coerceAtLeast(720)
        val assPaletteBgr = com.example.data.model.SpeakerColorOrder.PALETTE_HEX_COLORS.map { hex ->
            val r = (hex shr 16) and 0xFF
            val g = (hex shr 8) and 0xFF
            val b = hex and 0xFF
            String.format(Locale.US, "&H00%02X%02X%02X", b, g, r)
        }

        val sb = StringBuilder()
        sb.appendLine("[Script Info]")
        sb.appendLine("; Exported by DialogueLens")
        sb.appendLine("Title: ${project.title}")
        sb.appendLine("ScriptType: v4.00+")
        sb.appendLine("PlayResX: $playW")
        sb.appendLine("PlayResY: $playH")
        sb.appendLine("WrapStyle: 2")
        sb.appendLine("ScaledBorderAndShadow: yes")
        sb.appendLine()
        sb.appendLine("[V4+ Styles]")
        sb.appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")

        val baseFontSize = (playH * 0.042f).toInt().coerceIn(32, 64)
        project.speakers.forEach { spk ->
            val bgr = if (spk.hasDistinctCharacterName || spk.isLocked) {
                assPaletteBgr[spk.accentIndex % assPaletteBgr.size]
            } else {
                "&H00B8A394" // Gray (#94A3B8) for unknown/undiscovered speakers
            }
            sb.appendLine("Style: Speaker_${spk.id},Lexend,$baseFontSize,&H00F8FAFC,&H000000FF,$bgr,&H960A0F1A,0,0,0,0,100,100,0.4,0,3,2.2,1.5,5,28,28,24,1")
        }
        sb.appendLine("Style: DialogueDefault,Lexend,$baseFontSize,&H00F8FAFC,&H000000FF,&H00B8A394,&H960A0F1A,0,0,0,0,100,100,0.4,0,3,1.8,1.5,5,28,28,24,1")
        sb.appendLine("Style: NonSpeakerWhite,Lexend,$baseFontSize,&H00F8FAFC,&H000000FF,&H00FFFFFF,&H960A0F1A,0,0,0,0,100,100,0.4,0,3,2.0,1.5,5,28,28,24,1")
        sb.appendLine("Style: SFXBottom,Lexend,${(baseFontSize * 0.85f).toInt()},&H00E2E8F0,&H000000FF,&H00FFFFFF,&H80090E17,0,1,0,0,100,100,0.8,0,1,2.0,1.0,2,36,36,28,1")
        sb.appendLine()
        sb.appendLine("[Events]")
        sb.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

        layoutEngine.clearSmoothing()
        project.dialogueEvents.sortedBy { it.startTimeMs }.forEach { evt ->
            val midMs = (evt.startTimeMs + evt.endTimeMs) / 2L
            val placed = layoutEngine.computeLayout(project, midMs, project.styleSettings)
                .firstOrNull { it.event.id == evt.id }
            val startAss = formatAssTime(evt.startTimeMs)
            val endAss = formatAssTime(evt.endTimeMs)
            val rawText = evt.activeText(project.translationMode).replace("\n", "\\N")
            val spk = project.speakers.firstOrNull { it.id == evt.speakerId }
            val spkName = spk?.displayName ?: ""

            if (evt.dialogueType == DialogueType.SOUND_DESCRIPTION) {
                if (placed != null &&
                    (kotlin.math.abs(placed.rect.centerX - 0.50f) > 0.08f || placed.rect.centerY < 0.78f)
                ) {
                    val posX = (placed.rect.centerX * playW).toInt().coerceIn(80, playW - 80)
                    val posY = (placed.rect.centerY * playH).toInt().coerceIn(50, playH - 50)
                    sb.appendLine("Dialogue: 0,$startAss,$endAss,SFXBottom,SFX,0,0,0,,{\\an5\\pos($posX,$posY)\\i1}$rawText{\\i0}")
                } else {
                    sb.appendLine("Dialogue: 0,$startAss,$endAss,SFXBottom,SFX,0,0,0,,{\\an2\\i1}$rawText{\\i0}")
                }
            } else {
                val isUnknown = com.example.data.model.isBubbleSpeakerUnknown(evt, spk)
                val isNonSpeaker = com.example.data.model.isBubbleNonSpeakerSpecific(evt, project.styleSettings) && !isUnknown
                val styleName = when {
                    isUnknown -> "DialogueDefault"
                    isNonSpeaker -> "NonSpeakerWhite"
                    spk != null -> "Speaker_${spk.id}"
                    else -> "DialogueDefault"
                }
                val posX = ((placed?.rect?.centerX ?: 0.5f) * playW).toInt().coerceIn(80, playW - 80)
                val posY = ((placed?.rect?.centerY ?: 0.82f) * playH).toInt().coerceIn(60, playH - 60)
                val isShout = evt.audioFeatures?.isShouted == true || (evt.audioFeatures?.emphasisScore ?: 0f) >= 0.66f
                val isThought = evt.dialogueType == DialogueType.INTERNAL_MONOLOGUE || evt.dialogueType == DialogueType.NARRATION
                val tags = buildString {
                    append("\\an5\\pos($posX,$posY)")
                    if (isShout) append("\\b1\\bord4.2")
                    if (isThought) append("\\i1")
                }
                sb.appendLine("Dialogue: 1,$startAss,$endAss,$styleName,$spkName,0,0,0,,{$tags}$rawText")
            }
        }
        return sb.toString()
    }

    fun formatAssTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val h = clamped / 3_600_000L
        val m = (clamped % 3_600_000L) / 60_000L
        val s = (clamped % 60_000L) / 1_000L
        val cs = (clamped % 1_000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    fun formatSrtTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val h = clamped / 3_600_000L
        val m = (clamped % 3_600_000L) / 60_000L
        val s = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, millis)
    }

    fun formatCompactTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSec = clamped / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        val tenths = (clamped % 1000L) / 100L
        return String.format(Locale.US, "%02d:%02d.%01d", min, sec, tenths)
    }

    fun newEventId(): String = "evt_${UUID.randomUUID().toString().take(8)}"
}
