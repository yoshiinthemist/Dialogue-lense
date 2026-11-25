package com.example.domain.analysis

import com.example.data.model.ChapterSource
import com.example.data.model.SubtitleSourceKind
import com.example.data.model.ChapterInspection
import com.example.data.model.CoverageInterval
import com.example.data.model.CoverageStatus
import com.example.data.model.DialogueLensProject
import com.example.data.model.HealScope
import com.example.data.model.VideoChapter

/** Local inspection of actual chapter metadata and prepared transcript evidence. No media seeking. */
object ChapterPatrol {
    data class Result(val inspection: ChapterInspection, val window: HealWindow)

    fun resolveChapters(project: DialogueLensProject, coverage: List<CoverageInterval>): List<VideoChapter> {
        val explicit = project.chapters.filter { it.source != ChapterSource.INFERRED_EDITORIAL }
        if (explicit.isNotEmpty()) return explicit
        return HierarchicalContextEngine.inferEditorialChapters(project.dialogueEvents, project.videoSource.durationMs) { start, end ->
            if (project.subtitleSource != SubtitleSourceKind.AI_DRAFT) true else {
                var verifiedEnd = start
                for (interval in coverage) {
                    if (interval.endMs <= verifiedEnd) continue
                    if (!interval.silenceConfirmed || interval.startMs > verifiedEnd) break
                    verifiedEnd = interval.endMs
                    if (verifiedEnd >= end) break
                }
                verifiedEnd >= end
            }
        }
    }

    fun window(chapter: VideoChapter, durationMs: Long) = HealWindow(
        (chapter.startMs - 10_000L).coerceAtLeast(0L),
        minOf(chapter.startMs + 15_000L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE),
        HealScope.ADAPTIVE,
    )

    fun nextInspection(
        project: DialogueLensProject, coverage: List<CoverageInterval>, positionMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Result? {
        val boundaries = project.chapters.asSequence()
            .filter { ch -> ch.startMs > 0 && project.chapterInspections.none {
                it.chapterId == ch.id && it.boundaryMs == ch.startMs
            } }
            .sortedWith(compareBy<VideoChapter> { it.startMs < positionMs }.thenBy { kotlin.math.abs(it.startMs - positionMs) })
        for (chapter in boundaries) {
            val region = window(chapter, project.videoSource.durationMs)
            if (region.endMs <= region.startMs) continue
            val spans = coverage.filter { it.startMs < region.endMs && it.endMs > region.startMs }
            val failed = spans.any { it.status == CoverageStatus.FAILED || it.status == CoverageStatus.PARTIAL_RECOVERY }
            var verifiedEnd = region.startMs
            for (span in spans.sortedBy { it.startMs }) {
                if (!span.verified || span.startMs > verifiedEnd) break
                verifiedEnd = maxOf(verifiedEnd, span.endMs)
            }
            // No cues or a gap in the Scout's work alone is no evidence of missing speech.
            if (!failed && verifiedEnd < region.endMs) continue
            val cues = project.dialogueEvents.filter { it.startTimeMs < region.endMs && it.endTimeMs > region.startMs }
                .sortedBy { it.startTimeMs }
            val finding = when {
                failed -> "Dialogue analysis was incomplete at this transition."
                cues.any { e -> e.anchorPosition?.let { it.x !in 0f..1f || it.y !in 0f..1f } == true } ->
                    "A speaker pointer falls outside the video."
                cues.zipWithNext().any { (a,b) ->
                    a.speakerId != null && a.speakerId == b.speakerId &&
                        minOf(a.endTimeMs, b.endTimeMs) - maxOf(a.startTimeMs, b.startTimeMs) >= 1_200L &&
                        a.sourceText.isNotBlank() && a.sourceText.trim().equals(b.sourceText.trim(), ignoreCase = true)
                } -> "The same speaker has overlapping copies of a line."
                else -> ""
            }
            return Result(ChapterInspection(chapter.id, chapter.startMs, nowEpochMs, finding), region)
        }
        return null
    }
}
