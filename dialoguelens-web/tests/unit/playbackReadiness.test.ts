import { describe, expect, it } from 'vitest';
import {
  PlaybackReadinessController,
  computeAdaptiveLeadMs,
} from '../../shared/playbackReadiness.js';
import {
  buildYouTubeCaptionStatus,
  buildYouTubeEmbedConfig,
  describeYouTubePlayerError,
  getYouTubeCapabilities,
  parseYouTubeVideoId,
} from '../../shared/youtubeAdapterLogic.js';
import type { CoverageInterval } from '../../shared/types.js';

describe('PlaybackReadinessController (Scenario 6)', () => {
  it('holds media on uncovered interval, performs single auto-resume when accepted, and preserves explicit user pause', () => {
    const ctrl = new PlaybackReadinessController(5000);
    ctrl.onUserRequestPlay(1000);

    // Uncovered window -> real pause hold
    const holdAction = ctrl.evaluate({
      mediaTimeMs: 500,
      durationMs: 28_000,
      playbackRate: 1,
      elapsedNowMs: 1200,
      preparationMode: 'prepare_ahead',
      coverage: [],
      hasFallbackCaptionsForGap: false,
      allowFallbackOnDeadline: true,
    });
    expect(holdAction.command).toBe('pause');
    expect(ctrl.getSnapshot().phase).toBe('HOLDING_FOR_COVERAGE');

    // Coverage arrives -> single auto-resume
    const covered: CoverageInterval[] = [
      {
        startMs: 0,
        endMs: 8000,
        status: 'accepted',
        evidenceRevision: 1,
        acceptedRevision: 1,
        failedCueIds: [],
        attempts: 1,
        updatedAtMs: 1500,
      },
    ];
    const resume1 = ctrl.evaluate({
      mediaTimeMs: 500,
      durationMs: 28_000,
      playbackRate: 1,
      elapsedNowMs: 1600,
      preparationMode: 'prepare_ahead',
      coverage: covered,
      hasFallbackCaptionsForGap: false,
      allowFallbackOnDeadline: true,
    });
    expect(resume1.command).toBe('play');

    // Second tick while playing -> no duplicate play command
    const resume2 = ctrl.evaluate({
      mediaTimeMs: 900,
      durationMs: 28_000,
      playbackRate: 1,
      elapsedNowMs: 2000,
      preparationMode: 'prepare_ahead',
      coverage: covered,
      hasFallbackCaptionsForGap: false,
      allowFallbackOnDeadline: true,
    });
    expect(resume2.command).toBe('none');

    // User pauses -> controller MUST NEVER override user pause
    ctrl.onUserRequestPause();
    const afterPause = ctrl.evaluate({
      mediaTimeMs: 900,
      durationMs: 28_000,
      playbackRate: 1,
      elapsedNowMs: 2500,
      preparationMode: 'prepare_ahead',
      coverage: covered,
      hasFallbackCaptionsForGap: false,
      allowFallbackOnDeadline: true,
    });
    expect(afterPause.command).toBe('none');
    expect(ctrl.getSnapshot().phase).toBe('PAUSED_BY_USER');
  });

  it('tracks elapsed deadline even while media time is paused, releases fallback or offers gap-scoped Continue without waiving future gaps', () => {
    const ctrl = new PlaybackReadinessController(2000);
    ctrl.onUserRequestPlay(10_000);

    // Media clock stays frozen at 8500ms while elapsed time advances past 2000ms deadline
    ctrl.evaluate({
      mediaTimeMs: 8500,
      durationMs: 28_000,
      playbackRate: 1,
      elapsedNowMs: 10_500,
      preparationMode: 'prepare_ahead',
      coverage: [],
      hasFallbackCaptionsForGap: false,
      allowFallbackOnDeadline: true,
    });

    const expired = ctrl.evaluate({
      mediaTimeMs: 8500, // unchanged media time!
      durationMs: 28_000,
      playbackRate: 1,
      elapsedNowMs: 12_100, // elapsed > 2000ms
      preparationMode: 'prepare_ahead',
      coverage: [],
      hasFallbackCaptionsForGap: false,
      allowFallbackOnDeadline: true,
    });
    expect(expired.command).toBe('pause');
    expect(ctrl.getSnapshot().phase).toBe('GAP_UNAVAILABLE');

    // Gap-scoped Continue waives ONLY [8000, 16000], NOT future gap [16000, 24000]
    const cont = ctrl.continuePastCurrentGap();
    expect(cont.command).toBe('play');

    // Inside waived gap [8000, 16000] -> plays cleanly
    const inWaived = ctrl.evaluate({
      mediaTimeMs: 12_000,
      durationMs: 28_000,
      playbackRate: 1,
      elapsedNowMs: 13_000,
      preparationMode: 'prepare_ahead',
      coverage: [],
      hasFallbackCaptionsForGap: false,
      allowFallbackOnDeadline: true,
    });
    expect(inWaived.command).toBe('none');

    // Entering future uncovered gap [16000, 24000] -> STILL holds (previous Continue did NOT waive future gaps!)
    const futureGap = ctrl.evaluate({
      mediaTimeMs: 17_000,
      durationMs: 28_000,
      playbackRate: 1,
      elapsedNowMs: 13_500,
      preparationMode: 'prepare_ahead',
      coverage: [],
      hasFallbackCaptionsForGap: false,
      allowFallbackOnDeadline: true,
    });
    expect(futureGap.command).toBe('pause');
    expect(ctrl.getSnapshot().phase).toBe('HOLDING_FOR_COVERAGE');
  });

  it('scales preparation lead for 0.5x, 1x, and 2x rates and handles rejected play() honestly', () => {
    const leadHalf = computeAdaptiveLeadMs(0.5, 500);
    const leadNormal = computeAdaptiveLeadMs(1, 500);
    const leadDouble = computeAdaptiveLeadMs(2, 500);
    expect(leadHalf).toBeLessThan(leadNormal);
    expect(leadNormal).toBeLessThan(leadDouble);

    const ctrl = new PlaybackReadinessController(4000);
    ctrl.onUserRequestPlay(0);
    ctrl.onPlayRejected('NotAllowedError');
    expect(ctrl.getSnapshot().phase).toBe('PAUSED_BY_USER');
    expect(ctrl.getSnapshot().userWantsPlaying).toBe(false);
    expect(ctrl.getSnapshot().lastPlaybackRejection).toContain('NotAllowedError');
  });
});

describe('YouTube Adapter Honesty & Parameters (Scenarios 4 & 16)', () => {
  it('parses official URLs, builds cc_load_policy=1, and reports truthful unobservable CC & blocked overlay boundaries', () => {
    expect(parseYouTubeVideoId('https://youtu.be/RzagPoxaolQ?is=test')).toBe('RzagPoxaolQ');
    expect(parseYouTubeVideoId('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(parseYouTubeVideoId('https://example.com/not-youtube')).toBeNull();

    const cfg = buildYouTubeEmbedConfig('RzagPoxaolQ', 'en', 'http://127.0.0.1:8787');
    expect(cfg.playerVars.cc_load_policy).toBe(1);
    expect(cfg.embedUrl).toContain('cc_load_policy=1');
    expect(cfg.embedUrl).toContain('cc_lang_pref=en');

    const caps = getYouTubeCapabilities(false);
    expect(caps.playback).toBe('supported');
    expect(caps.captionActivation).toBe('unobservable');
    expect(caps.permittedOverlayPlacement).toBe('blocked');
    expect(caps.audioExtraction).toBe('blocked');

    const status = buildYouTubeCaptionStatus('en', false);
    expect(status.ccRequested).toBe(true);
    expect(status.ccActivationObserved).toBe('unobservable');
    expect(status.statusSummary).toContain('CC requested; activation not observable');

    const err150 = describeYouTubePlayerError(150, 'RzagPoxaolQ');
    expect(err150.watchUrl).toBe('https://www.youtube.com/watch?v=RzagPoxaolQ');
    expect(err150.title).toContain('Embedding restricted');
  });
});
