import type { CoverageInterval, PreparationMode } from './types.js';

export type ReadinessPhase =
  | 'IDLE'
  | 'PREPARING'
  | 'HOLDING_FOR_COVERAGE'
  | 'READY_PAUSED'
  | 'PLAYING'
  | 'PAUSED_BY_USER'
  | 'SEEKING'
  | 'WATCHING_FALLBACK'
  | 'GAP_UNAVAILABLE';

export interface GapSpan {
  startMs: number;
  endMs: number;
}

export interface ReadinessSnapshot {
  phase: ReadinessPhase;
  userWantsPlaying: boolean;
  /** True when the controller has actively paused the player awaiting coverage. */
  isHoldingMedia: boolean;
  autoResumeCountForCurrentHold: number;
  holdStartedElapsedMs: number | null;
  deadlineMs: number;
  deadlineElapsedMs: number;
  activeGap: GapSpan | null;
  waivedGaps: GapSpan[];
  playbackRate: number;
  requiredLeadMs: number;
  observedProcessingMs: number;
  lastPlaybackRejection: string | null;
  statusMessage: string;
}

export interface ReadinessTickInput {
  mediaTimeMs: number;
  durationMs: number;
  playbackRate: number;
  elapsedNowMs: number;
  preparationMode: PreparationMode;
  coverage: CoverageInterval[];
  hasFallbackCaptionsForGap: boolean;
  allowFallbackOnDeadline: boolean;
  /** True for sources like YouTube where ordinary playback must never be gated by analysis holds. */
  neverGatePlayback?: boolean;
}

export interface ReadinessAction {
  command: 'none' | 'play' | 'pause' | 'release_fallback';
  reason: string;
  gap?: GapSpan;
}

const END_OF_VIDEO_TOLERANCE_MS = 450;

export function computeAdaptiveLeadMs(
  playbackRate: number,
  observedProcessingMs: number,
  baseLeadMs = 6000,
): number {
  const clampedRate = Math.max(0.5, Math.min(4, Number.isFinite(playbackRate) ? playbackRate : 1));
  const scaledBase = Math.round(baseLeadMs * clampedRate);
  const processingBuffer = Math.min(20_000, Math.round(Math.max(0, observedProcessingMs) * clampedRate * 1.25));
  return Math.max(3000, Math.min(30_000, Math.max(scaledBase, processingBuffer)));
}

export function isTimeCoveredByAcceptedCoverage(
  timeMs: number,
  coverage: CoverageInterval[],
  waivedGaps: GapSpan[],
): { covered: boolean; isFallback: boolean; interval?: CoverageInterval } {
  for (const waived of waivedGaps) {
    if (timeMs >= waived.startMs && timeMs < waived.endMs) {
      return { covered: true, isFallback: true };
    }
  }
  const match = coverage.find((c) => timeMs >= c.startMs && timeMs < c.endMs);
  if (!match) {
    return { covered: false, isFallback: false };
  }
  if (match.status === 'accepted' || match.status === 'confirmed_silence') {
    return { covered: true, isFallback: false, interval: match };
  }
  if (match.status === 'fallback_captions' || match.status === 'partial') {
    return { covered: true, isFallback: true, interval: match };
  }
  return { covered: false, isFallback: false, interval: match };
}

export function findUncoveredGapAt(
  timeMs: number,
  durationMs: number,
  coverage: CoverageInterval[],
  windowSizeMs = 8000,
): GapSpan {
  const startMs = Math.max(0, Math.floor(timeMs / windowSizeMs) * windowSizeMs);
  const endMs = Math.min(Math.max(startMs + 1000, durationMs), startMs + windowSizeMs);
  const existing = coverage.find((c) => timeMs >= c.startMs && timeMs < c.endMs);
  if (existing) {
    return { startMs: existing.startMs, endMs: existing.endMs };
  }
  return { startMs, endMs };
}

/**
 * Single authoritative playback readiness controller.
 * - Preserves explicit user pause intent at all times.
 * - Performs at most ONE auto-resume after a coverage hold is satisfied.
 * - Measures hold deadlines using monotonic elapsed time, even while media time is paused.
 * - Enforces gap-scoped Continue (only waives the specific gap, never future gaps or user pause).
 * - Handles rejected media.play() calls honestly.
 */
export class PlaybackReadinessController {
  private state: ReadinessSnapshot;

  constructor(deadlineMs = 8000) {
    this.state = {
      phase: 'IDLE',
      userWantsPlaying: false,
      isHoldingMedia: false,
      autoResumeCountForCurrentHold: 0,
      holdStartedElapsedMs: null,
      deadlineMs,
      deadlineElapsedMs: 0,
      activeGap: null,
      waivedGaps: [],
      playbackRate: 1,
      requiredLeadMs: computeAdaptiveLeadMs(1, 0),
      observedProcessingMs: 0,
      lastPlaybackRejection: null,
      statusMessage: 'Ready to play.',
    };
  }

  getSnapshot(): ReadinessSnapshot {
    return {
      ...this.state,
      activeGap: this.state.activeGap ? { ...this.state.activeGap } : null,
      waivedGaps: this.state.waivedGaps.map((g) => ({ ...g })),
    };
  }

  setDeadlineMs(deadlineMs: number): void {
    this.state.deadlineMs = Math.max(1000, deadlineMs);
  }

  recordProcessingLatency(ms: number): void {
    if (!Number.isFinite(ms) || ms < 0) return;
    this.state.observedProcessingMs =
      this.state.observedProcessingMs === 0
        ? ms
        : Math.round(this.state.observedProcessingMs * 0.6 + ms * 0.4);
    this.state.requiredLeadMs = computeAdaptiveLeadMs(
      this.state.playbackRate,
      this.state.observedProcessingMs,
    );
  }

  onUserRequestPlay(elapsedNowMs: number): void {
    this.state.userWantsPlaying = true;
    this.state.lastPlaybackRejection = null;
    if (this.state.phase === 'IDLE' || this.state.phase === 'PAUSED_BY_USER' || this.state.phase === 'READY_PAUSED') {
      this.state.phase = 'PREPARING';
      this.state.autoResumeCountForCurrentHold = 0;
      this.state.holdStartedElapsedMs = elapsedNowMs;
      this.state.deadlineElapsedMs = 0;
      this.state.statusMessage = 'Checking dialogue readiness…';
    }
  }

  onUserRequestPause(): ReadinessAction {
    this.state.userWantsPlaying = false;
    this.state.isHoldingMedia = false;
    this.state.holdStartedElapsedMs = null;
    this.state.deadlineElapsedMs = 0;
    this.state.autoResumeCountForCurrentHold = 0;
    this.state.phase = 'PAUSED_BY_USER';
    this.state.statusMessage = 'Paused by user.';
    return { command: 'pause', reason: 'Explicit user pause' };
  }

  onUserSeek(elapsedNowMs: number): void {
    this.state.holdStartedElapsedMs = this.state.userWantsPlaying ? elapsedNowMs : null;
    this.state.deadlineElapsedMs = 0;
    this.state.autoResumeCountForCurrentHold = 0;
    this.state.activeGap = null;
    this.state.phase = this.state.userWantsPlaying ? 'SEEKING' : 'PAUSED_BY_USER';
    this.state.statusMessage = this.state.userWantsPlaying
      ? 'Checking coverage at new position…'
      : 'Paused after seek.';
  }

  onPlayRejected(errorMessage: string): void {
    this.state.userWantsPlaying = false;
    this.state.isHoldingMedia = false;
    this.state.holdStartedElapsedMs = null;
    this.state.phase = 'PAUSED_BY_USER';
    this.state.lastPlaybackRejection = errorMessage;
    this.state.statusMessage = `Playback was blocked by browser autoplay policy (${errorMessage}). Press Play to start.`;
  }

  /**
   * Gap-scoped Continue: waives ONLY the currently blocking gap.
   * Never waives future gaps, never certifies silence, and NEVER overrides user pause!
   */
  continuePastCurrentGap(): ReadinessAction {
    if (!this.state.activeGap) {
      return { command: 'none', reason: 'No active gap to waive.' };
    }
    const gap = { ...this.state.activeGap };
    const alreadyWaived = this.state.waivedGaps.some(
      (g) => g.startMs === gap.startMs && g.endMs === gap.endMs,
    );
    if (!alreadyWaived) {
      this.state.waivedGaps.push(gap);
    }
    this.state.activeGap = null;
    this.state.holdStartedElapsedMs = null;
    this.state.deadlineElapsedMs = 0;
    this.state.isHoldingMedia = false;

    if (!this.state.userWantsPlaying) {
      this.state.phase = 'PAUSED_BY_USER';
      this.state.statusMessage = 'Gap skipped for this interval; playback remains paused by user.';
      return { command: 'none', reason: 'User pause preserved during gap continue.' };
    }

    this.state.phase = 'WATCHING_FALLBACK';
    this.state.statusMessage = `Continuing past gap ${Math.round(gap.startMs / 1000)}s–${Math.round(gap.endMs / 1000)}s (not certified silent).`;
    return { command: 'play', reason: 'User continued past specific gap', gap };
  }

  retryCurrentGap(elapsedNowMs: number): void {
    this.state.holdStartedElapsedMs = elapsedNowMs;
    this.state.deadlineElapsedMs = 0;
    this.state.autoResumeCountForCurrentHold = 0;
    if (this.state.userWantsPlaying) {
      this.state.phase = 'HOLDING_FOR_COVERAGE';
      this.state.statusMessage = 'Retrying dialogue preparation for this interval…';
    }
  }

  evaluate(input: ReadinessTickInput): ReadinessAction {
    this.state.playbackRate = input.playbackRate;
    this.state.requiredLeadMs = computeAdaptiveLeadMs(
      input.playbackRate,
      this.state.observedProcessingMs,
    );

    if (input.neverGatePlayback) {
      this.state.isHoldingMedia = false;
      this.state.activeGap = null;
      this.state.phase = this.state.userWantsPlaying ? 'PLAYING' : 'PAUSED_BY_USER';
      this.state.statusMessage = 'Playback uncontrolled by analysis gate.';
      return { command: 'none', reason: 'Source playback is never gated.' };
    }

    // Respect explicit user pause unconditionally
    if (!this.state.userWantsPlaying) {
      this.state.isHoldingMedia = false;
      if (this.state.phase !== 'IDLE' && this.state.phase !== 'READY_PAUSED') {
        this.state.phase = 'PAUSED_BY_USER';
      }
      return { command: 'none', reason: 'User intent is paused.' };
    }

    // End-of-video tolerance: never get stuck holding inside the final 450ms
    if (input.durationMs > 0 && input.mediaTimeMs >= input.durationMs - END_OF_VIDEO_TOLERANCE_MS) {
      this.state.isHoldingMedia = false;
      this.state.activeGap = null;
      this.state.holdStartedElapsedMs = null;
      this.state.phase = 'PLAYING';
      this.state.statusMessage = 'Near end of video.';
      return { command: 'none', reason: 'End of video tolerance.' };
    }

    // Prepare fully requires full requested duration coverage before initial play
    const fullRequired = input.preparationMode === 'prepare_fully' && input.durationMs > 0;
    const isFullyCovered = fullRequired
      ? isRangeFullyCovered(0, Math.max(0, input.durationMs - END_OF_VIDEO_TOLERANCE_MS), input.coverage, this.state.waivedGaps)
      : true;

    const currentCheck = isTimeCoveredByAcceptedCoverage(
      input.mediaTimeMs,
      input.coverage,
      this.state.waivedGaps,
    );

    const readyNow = currentCheck.covered && isFullyCovered;

    if (readyNow) {
      const wasHolding = this.state.isHoldingMedia || this.state.phase === 'PREPARING' || this.state.phase === 'SEEKING' || this.state.phase === 'HOLDING_FOR_COVERAGE';
      this.state.isHoldingMedia = false;
      this.state.activeGap = null;
      this.state.holdStartedElapsedMs = null;
      this.state.deadlineElapsedMs = 0;
      this.state.phase = currentCheck.isFallback ? 'WATCHING_FALLBACK' : 'PLAYING';
      this.state.statusMessage = currentCheck.isFallback
        ? 'Playing with fallback/partial captions.'
        : 'Playing with reviewed dialogue.';

      if (wasHolding && this.state.autoResumeCountForCurrentHold === 0) {
        this.state.autoResumeCountForCurrentHold = 1;
        return { command: 'play', reason: 'Coverage satisfied; single auto-resume.' };
      }
      return { command: 'none', reason: 'Coverage satisfied; continuing playback.' };
    }

    // Not ready -> enter or continue real playback hold
    if (this.state.holdStartedElapsedMs === null) {
      this.state.holdStartedElapsedMs = input.elapsedNowMs;
    }
    const elapsed = Math.max(0, input.elapsedNowMs - this.state.holdStartedElapsedMs);
    this.state.deadlineElapsedMs = elapsed;
    const gap = findUncoveredGapAt(input.mediaTimeMs, input.durationMs, input.coverage);
    this.state.activeGap = gap;

    // Deadline exceeded (measured in elapsed time even while media clock is paused!)
    if (elapsed >= this.state.deadlineMs) {
      if (input.hasFallbackCaptionsForGap && input.allowFallbackOnDeadline) {
        this.state.isHoldingMedia = false;
        this.state.phase = 'WATCHING_FALLBACK';
        this.state.statusMessage = 'Review deadline elapsed; released evidence-backed fallback captions.';
        return {
          command: 'release_fallback',
          reason: 'Elapsed deadline exceeded; releasing usable fallback captions.',
          gap,
        };
      }
      // Leave indefinite buffering; expose Retry / gap-scoped Continue
      this.state.isHoldingMedia = true;
      this.state.phase = 'GAP_UNAVAILABLE';
      this.state.statusMessage =
        'Dialogue for this interval is unavailable within deadline. Retry or Continue past this gap.';
      return { command: 'pause', reason: 'Deadline expired with no usable fallback text.', gap };
    }

    const alreadyHolding = this.state.isHoldingMedia;
    this.state.isHoldingMedia = true;
    this.state.phase = 'HOLDING_FOR_COVERAGE';
    this.state.statusMessage = fullRequired
      ? 'Preparing full requested coverage before playback…'
      : 'Holding playback while Scout 2 finishes reviewing this interval…';

    if (!alreadyHolding) {
      return { command: 'pause', reason: 'Holding player until interval coverage is accepted.', gap };
    }
    return { command: 'none', reason: 'Continuing hold within elapsed deadline.', gap };
  }
}

function isRangeFullyCovered(
  startMs: number,
  endMs: number,
  coverage: CoverageInterval[],
  waivedGaps: GapSpan[],
): boolean {
  if (endMs <= startMs) return true;
  const spans: Array<{ startMs: number; endMs: number }> = [
    ...coverage
      .filter(
        (c) =>
          c.status === 'accepted' ||
          c.status === 'confirmed_silence' ||
          c.status === 'fallback_captions' ||
          c.status === 'partial',
      )
      .map((c) => ({ startMs: c.startMs, endMs: c.endMs })),
    ...waivedGaps,
  ].sort((a, b) => a.startMs - b.startMs);

  let cursor = startMs;
  for (const span of spans) {
    if (span.endMs <= cursor) continue;
    if (span.startMs > cursor + 250) {
      return false;
    }
    cursor = Math.max(cursor, span.endMs);
    if (cursor >= endMs) return true;
  }
  return cursor >= endMs;
}
