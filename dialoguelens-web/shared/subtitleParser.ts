import type { DialogueEvent, EvidenceCue, EvidenceOrigin, ReferenceTrack } from './types.js';

export interface ParsedSubtitleCue {
  startMs: number;
  endMs: number;
  text: string;
  speakerHint?: string;
}

export interface SubtitleParseOptions {
  mediaId: string;
  trackId: string;
  label: string;
  language: string;
  origin: EvidenceOrigin;
  isAutomatic?: boolean | null;
  sourceRevision?: number;
  clipDurationMs?: number | null;
  visibleObservationConfirmed?: boolean;
}

export interface SubtitleParseResult {
  track: ReferenceTrack;
  warnings: string[];
  droppedCount: number;
}

/**
 * Escapes raw HTML entities so untrusted caption text can never inject markup
 * if serialized into HTML/SVG or aria strings.
 */
export function escapeCaptionText(raw: string): string {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Strips WebVTT/SRT styling tags (e.g. <i>, <c.color>, <00:01.000>) safely while
 * extracting optional <v Speaker> voice tags as non-authoritative speaker hints.
 */
export function sanitizeSubtitleMarkup(raw: string): { text: string; speakerHint?: string } {
  let speakerHint: string | undefined;
  const voiceMatch = raw.match(/<v(?:\.[^>\s]+)?\s+([^>]+)>/i);
  if (voiceMatch && voiceMatch[1]) {
    speakerHint = voiceMatch[1].trim();
  }

  const stripped = raw
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .replace(/<\/?[^>]+>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .join('\n')
    .trim();

  return { text: stripped, speakerHint };
}

export function parseTimestampToMs(raw: string): number | null {
  const cleaned = raw.trim().replace(',', '.');
  const parts = cleaned.split(':');
  if (parts.length < 2 || parts.length > 3) return null;

  let hours = 0;
  let minutes = 0;
  let secondsPart = '';

  if (parts.length === 3) {
    hours = Number(parts[0]);
    minutes = Number(parts[1]);
    secondsPart = parts[2] ?? '';
  } else {
    minutes = Number(parts[0]);
    secondsPart = parts[1] ?? '';
  }

  const seconds = Number(secondsPart);
  if (
    !Number.isFinite(hours) ||
    !Number.isFinite(minutes) ||
    !Number.isFinite(seconds) ||
    hours < 0 ||
    minutes < 0 ||
    minutes >= 60 ||
    seconds < 0 ||
    seconds >= 60
  ) {
    return null;
  }

  const totalMs = Math.round(((hours * 60 + minutes) * 60 + seconds) * 1000);
  return Number.isFinite(totalMs) ? totalMs : null;
}

export function formatMsToWebVtt(ms: number): string {
  const clamped = Math.max(0, Math.round(ms));
  const hours = Math.floor(clamped / 3_600_000);
  const minutes = Math.floor((clamped % 3_600_000) / 60_000);
  const seconds = Math.floor((clamped % 60_000) / 1000);
  const millis = clamped % 1000;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}.${String(millis).padStart(3, '0')}`;
}

export function formatMsToSrt(ms: number): string {
  return formatMsToWebVtt(ms).replace('.', ',');
}

export function parseSubtitleContent(
  content: string,
  options: SubtitleParseOptions,
): SubtitleParseResult {
  const warnings: string[] = [];
  let droppedCount = 0;
  const normalized = content.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const blocks = normalized.split(/\n{2,}/);
  const parsedRaw: ParsedSubtitleCue[] = [];

  for (const block of blocks) {
    const lines = block
      .split('\n')
      .map((l) => l.trimEnd())
      .filter((l) => l.trim().length > 0);
    if (lines.length === 0) continue;

    const first = lines[0]?.trim() ?? '';
    if (
      first.startsWith('WEBVTT') ||
      first.startsWith('NOTE') ||
      first.startsWith('STYLE') ||
      first.startsWith('REGION')
    ) {
      continue;
    }

    const timingLineIdx = lines.findIndex((line) => line.includes('-->'));
    if (timingLineIdx === -1) continue;

    const timingLine = lines[timingLineIdx] ?? '';
    const arrowParts = timingLine.split('-->');
    if (arrowParts.length !== 2) {
      droppedCount++;
      warnings.push(`Skipped malformed cue timing: ${timingLine}`);
      continue;
    }

    const startRaw = arrowParts[0]?.trim() ?? '';
    const endRaw = (arrowParts[1]?.trim() ?? '').split(/\s+/)[0] ?? '';

    const startMs = parseTimestampToMs(startRaw);
    const endMs = parseTimestampToMs(endRaw);
    if (startMs === null || endMs === null || endMs <= startMs) {
      droppedCount++;
      warnings.push(`Skipped invalid/unordered cue timing: ${timingLine}`);
      continue;
    }

    if (
      options.clipDurationMs !== undefined &&
      options.clipDurationMs !== null &&
      Number.isFinite(options.clipDurationMs) &&
      options.clipDurationMs > 0
    ) {
      if (startMs >= options.clipDurationMs + 500) {
        droppedCount++;
        warnings.push(`Dropped cue starting beyond media duration (${startMs}ms > ${options.clipDurationMs}ms)`);
        continue;
      }
    }

    const bodyRaw = lines.slice(timingLineIdx + 1).join('\n');
    const { text, speakerHint } = sanitizeSubtitleMarkup(bodyRaw);
    if (!text) {
      droppedCount++;
      continue;
    }

    const boundedEndMs =
      options.clipDurationMs && options.clipDurationMs > 0
        ? Math.min(endMs, Math.ceil(options.clipDurationMs))
        : endMs;

    if (boundedEndMs <= startMs) {
      droppedCount++;
      continue;
    }

    parsedRaw.push({
      startMs,
      endMs: boundedEndMs,
      text,
      speakerHint,
    });
  }

  // Normalize rolling captions while preserving legitimate repeated speech & same-time corrections.
  const normalizedCues = normalizeRollingAndCorrections(parsedRaw);

  const sourceRevision = options.sourceRevision ?? 1;
  const evidenceCues: EvidenceCue[] = normalizedCues.map((cue, idx) => ({
    id: `${options.trackId}:cue-${idx + 1}`,
    trackId: options.trackId,
    mediaId: options.mediaId,
    startMs: cue.startMs,
    endMs: cue.endMs,
    text: cue.text,
    language: options.language,
    origin: options.origin,
    isAutomatic: options.isAutomatic ?? null,
    sourceRevision,
    observedVisibly: options.visibleObservationConfirmed ?? false,
    sequence: idx + 1,
  }));

  const measuredCoverageMs = computeMergedCoverageIntervals(evidenceCues);

  return {
    track: {
      id: options.trackId,
      mediaId: options.mediaId,
      label: options.label,
      language: options.language,
      origin: options.origin,
      isAutomatic: options.isAutomatic ?? null,
      sourceRevision,
      cues: evidenceCues,
      measuredCoverageMs,
      visibleObservationConfirmed: options.visibleObservationConfirmed ?? false,
    },
    warnings,
    droppedCount,
  };
}

/**
 * Handles:
 * 1. Rolling live captions where cue B immediately repeats line 2 of cue A as its line 1.
 * 2. Same-timestamp text corrections where a later cue at identical [startMs, endMs] supersedes an earlier typo.
 * 3. Preserves legitimate repeated speech separated in time (e.g. "Wait." at 3s and "Wait." at 5s).
 */
export function normalizeRollingAndCorrections(cues: ParsedSubtitleCue[]): ParsedSubtitleCue[] {
  const ordered = [...cues].sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);
  const result: ParsedSubtitleCue[] = [];

  for (const cue of ordered) {
    const prev = result[result.length - 1];
    if (!prev) {
      result.push({ ...cue });
      continue;
    }

    // Same-time text correction: exact same [startMs, endMs] with updated text replaces earlier version
    if (prev.startMs === cue.startMs && prev.endMs === cue.endMs) {
      if (prev.text !== cue.text) {
        result[result.length - 1] = { ...cue };
      }
      continue;
    }

    // Rolling caption overlap detection:
    // If the new cue starts while/immediately after prev ends (<= 80ms gap) AND is multi-line where
    // the first line of the new cue duplicates the last line of prev, trim the rolling prefix.
    const prevLines = prev.text.split('\n');
    const curLines = cue.text.split('\n');
    const contiguous = cue.startMs <= prev.endMs + 80;

    if (
      contiguous &&
      curLines.length >= 2 &&
      prevLines.length >= 1 &&
      curLines[0]?.trim().toLowerCase() === prevLines[prevLines.length - 1]?.trim().toLowerCase()
    ) {
      const uniqueRemainder = curLines.slice(1).join('\n').trim();
      if (uniqueRemainder.length > 0) {
        result.push({
          ...cue,
          text: uniqueRemainder,
        });
        continue;
      }
    }

    // Progressive single-line growth at identical startMs (ASR incremental prefix expansion):
    if (
      prev.startMs === cue.startMs &&
      cue.endMs >= prev.endMs &&
      cue.text.toLowerCase().startsWith(prev.text.toLowerCase()) &&
      cue.text.length > prev.text.length
    ) {
      result[result.length - 1] = { ...cue };
      continue;
    }

    // Legitimate repetition (same text at different times) is preserved intact!
    result.push({ ...cue });
  }

  return result;
}

export function computeMergedCoverageIntervals(
  cues: Array<{ startMs: number; endMs: number }>,
  mergeGapMs = 0,
): Array<{ startMs: number; endMs: number }> {
  if (cues.length === 0) return [];
  const sorted = [...cues]
    .filter((c) => Number.isFinite(c.startMs) && Number.isFinite(c.endMs) && c.endMs > c.startMs)
    .sort((a, b) => a.startMs - b.startMs);
  const merged: Array<{ startMs: number; endMs: number }> = [];
  for (const cue of sorted) {
    const last = merged[merged.length - 1];
    if (!last || cue.startMs > last.endMs + mergeGapMs) {
      merged.push({ startMs: cue.startMs, endMs: cue.endMs });
    } else {
      last.endMs = Math.max(last.endMs, cue.endMs);
    }
  }
  return merged;
}

export function exportTimelineToWebVtt(
  events: DialogueEvent[],
  speakerLabels: Map<string, string>,
  mode: 'original' | 'translated' | 'bilingual' = 'original',
): string {
  const ordered = [...events].sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);
  const lines: string[] = ['WEBVTT', ''];
  for (const ev of ordered) {
    lines.push(`${formatMsToWebVtt(ev.startMs)} --> ${formatMsToWebVtt(ev.endMs)}`);
    const speaker = speakerLabels.get(ev.speakerId);
    const body = selectEventText(ev, mode);
    if (speaker && ev.speakerRole !== 'unknown') {
      lines.push(`<v ${speaker}>${body}`);
    } else {
      lines.push(body);
    }
    lines.push('');
  }
  return lines.join('\n');
}

export function exportTimelineToSrt(
  events: DialogueEvent[],
  speakerLabels: Map<string, string>,
  mode: 'original' | 'translated' | 'bilingual' = 'original',
): string {
  const ordered = [...events].sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);
  const lines: string[] = [];
  ordered.forEach((ev, idx) => {
    lines.push(String(idx + 1));
    lines.push(`${formatMsToSrt(ev.startMs)} --> ${formatMsToSrt(ev.endMs)}`);
    const speaker = speakerLabels.get(ev.speakerId);
    const body = selectEventText(ev, mode);
    lines.push(speaker && ev.speakerRole !== 'unknown' ? `${speaker}: ${body}` : body);
    lines.push('');
  });
  return lines.join('\n');
}

export function selectEventText(
  event: DialogueEvent,
  mode: 'original' | 'translated' | 'bilingual',
): string {
  if (mode === 'translated') {
    if (event.translatedText && event.translatedText.trim().length > 0) {
      return event.translatedText;
    }
    return event.originalText;
  }
  if (mode === 'bilingual') {
    if (
      event.translatedText &&
      event.translatedText.trim().length > 0 &&
      event.translatedText.trim() !== event.originalText.trim()
    ) {
      return `${event.translatedText}\n${event.originalText}`;
    }
    return event.originalText;
  }
  return event.originalText;
}
