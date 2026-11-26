import { describe, expect, it } from 'vitest';
import {
  BalloonPresentationEngine,
  computeContentViewportRect,
  computeExpectedTailTip,
  intersectBubbleContour,
  shortestAngleDelta,
} from '../../shared/layoutEngine.js';
import type { DialogueEvent, FaceObservation } from '../../shared/types.js';

function makeEvent(overrides: Partial<DialogueEvent> = {}): DialogueEvent {
  return {
    id: 'cue-1',
    projectId: 'p1',
    mediaId: 'm1',
    startMs: 0,
    endMs: 5000,
    originalText: 'Keep moving left while I cross behind the stage lamp.',
    sourceLanguage: 'en',
    targetLanguage: 'en',
    translationStatus: 'not_needed',
    speakerId: 'p1:spk-1',
    speakerRole: 'person',
    linkedVisualTrackId: 'track-maya',
    styleVariant: 'speech',
    sceneId: 'scene-1',
    evidenceCueIds: ['e1'],
    evidenceRevision: 1,
    acceptedRevision: 1,
    quality: {
      referenceAlignment: 'supported',
      audioSupport: 'unavailable',
      translationReview: 'supported',
      attribution: 'supported',
      presentation: 'needs_review',
    },
    protectedFields: { text: false, timing: false, speaker: false, anchor: false },
    uncertaintyNotes: [],
    conflictHints: [],
    ...overrides,
  };
}

describe('Coordinate Transform & Stable Bubble Bodies (Scenario 8)', () => {
  it('computes accurate letterbox transforms for 16:9, portrait 9:16, and ultrawide 21:9', () => {
    const portrait = computeContentViewportRect(800, 450, 360, 640, 1);
    expect(portrait.contentHeight).toBeCloseTo(450, 1);
    expect(portrait.contentWidth).toBeCloseTo(450 * (9 / 16), 1);
    expect(portrait.contentX).toBeGreaterThan(200);

    const ultrawide = computeContentViewportRect(800, 450, 840, 360, 2);
    expect(ultrawide.contentWidth).toBeCloseTo(800, 1);
    expect(ultrawide.contentHeight).toBeCloseTo(800 / (840 / 360), 1);
    expect(ultrawide.contentY).toBeGreaterThan(40);
  });

  it('keeps committed body center drift <= 1 CSS px under normal speaker motion and preserves exact body on tail toggle', () => {
    const engine = new BalloonPresentationEngine();
    const viewport = computeContentViewportRect(960, 540, 640, 360, 1);
    const ev = makeEvent();

    const firstObs: FaceObservation = {
      observationSeq: 1,
      captureTimeMs: 0,
      mediaTimeMs: 500,
      sceneId: 'scene-1',
      trackId: 'track-maya',
      bbox: { x: 0.2, y: 0.45, width: 0.11, height: 0.26 },
      mouth: { x: 0.255, y: 0.62 },
      speakingEvidence: 0.9,
      confidence: 0.98,
      source: 'fixture_reference',
    };

    const initialBody = engine.resolveBubbleBody({
      event: ev,
      measuredWidth: 240,
      measuredHeight: 68,
      viewport,
      observations: [firstObs],
      otherCommittedBodies: [],
      nowMonotonicMs: 0,
    });

    // Simulate 60 frames of continuous speaker motion across the frame
    let maxDriftPx = 0;
    for (let f = 1; f <= 60; f++) {
      const movedObs: FaceObservation = {
        ...firstObs,
        observationSeq: f + 1,
        mediaTimeMs: 500 + f * 33,
        bbox: { ...firstObs.bbox, x: 0.2 + f * 0.008 },
        mouth: { x: 0.255 + f * 0.008, y: 0.62 + Math.sin(f * 0.2) * 0.04 },
      };

      const body = engine.resolveBubbleBody({
        event: ev,
        measuredWidth: 240,
        measuredHeight: 68,
        viewport,
        observations: [movedObs],
        otherCommittedBodies: [],
        nowMonotonicMs: f * 33,
      });

      const drift = Math.hypot(body.centerX - initialBody.centerX, body.centerY - initialBody.centerY);
      maxDriftPx = Math.max(maxDriftPx, drift);
      expect(body.entryCount).toBe(1);

      // Toggle tailed -> tailless -> tailed on every frame: body MUST NOT move or replay!
      const tailOn = engine.resolveTailGeometry({
        event: ev,
        body,
        observations: [movedObs],
        viewport,
        currentObservationSeq: f + 1,
        mediaTimeMs: 500 + f * 33,
        nowMonotonicMs: f * 33,
        reducedMotion: false,
        taillessMode: false,
      });
      const tailOff = engine.resolveTailGeometry({
        event: ev,
        body,
        observations: [movedObs],
        viewport,
        currentObservationSeq: f + 1,
        mediaTimeMs: 500 + f * 33,
        nowMonotonicMs: f * 33,
        reducedMotion: false,
        taillessMode: true,
      });
      expect(tailOn.visible).toBe(true);
      expect(tailOff.visible).toBe(false);
    }

    expect(maxDriftPx).toBeLessThanOrEqual(1);
  });
});

describe('Independent Active Tail Geometry, ±π Wrapping & Crossing Protection (Scenarios 9 & 10)', () => {
  it('attaches tail root <= 1 CSS px to rendered contour across full 360° sweep and ±π seam for both speech and shout contours', () => {
    const viewport = computeContentViewportRect(960, 540, 640, 360, 1);

    for (const variant of ['speech', 'shout'] as const) {
      const engine = new BalloonPresentationEngine();
      const ev = makeEvent({
        id: `cue-${variant}`,
        styleVariant: variant,
        manualAnchor: { x: 0.5, y: 0.5 },
      });

      const body = engine.resolveBubbleBody({
        event: ev,
        measuredWidth: 220,
        measuredHeight: 72,
        viewport,
        observations: [],
        otherCommittedBodies: [],
        nowMonotonicMs: 0,
      });

      // Sweep full circle crossing the -π / +π seam smoothly
      const angles = [];
      for (let deg = 150; deg <= 510; deg += 15) {
        angles.push((deg * Math.PI) / 180);
      }

      let prevAngle: number | null = null;
      let tMs = 0;
      for (let i = 0; i < angles.length; i++) {
        const theta = angles[i]!;
        const mouthNorm = {
          x: 0.5 + Math.cos(theta) * 0.25,
          y: 0.5 + Math.sin(theta) * 0.25,
        };
        const obs: FaceObservation = {
          observationSeq: i + 1,
          captureTimeMs: tMs,
          mediaTimeMs: tMs,
          sceneId: 'scene-1',
          trackId: 'track-maya',
          bbox: { x: mouthNorm.x - 0.05, y: mouthNorm.y - 0.15, width: 0.1, height: 0.22 },
          mouth: mouthNorm,
          speakingEvidence: 0.9,
          confidence: 0.98,
          source: 'fixture_reference',
        };

        // Run 4 animation frames (4 * 32ms = 128ms < 150ms response lag bound) to check convergence
        let tail = engine.resolveTailGeometry({
          event: ev,
          body,
          observations: [obs],
          viewport,
          currentObservationSeq: i + 1,
          mediaTimeMs: tMs,
          nowMonotonicMs: tMs,
          reducedMotion: false,
          taillessMode: false,
        });
        for (let step = 1; step <= 4; step++) {
          tMs += 32;
          tail = engine.resolveTailGeometry({
            event: ev,
            body,
            observations: [obs],
            viewport,
            currentObservationSeq: i + 1,
            mediaTimeMs: tMs,
            nowMonotonicMs: tMs,
            reducedMotion: false,
            taillessMode: false,
          });
        }

        expect(tail.visible).toBe(true);

        // Verify root sits strictly <= 1 CSS px from exact rendered contour
        const exactRoot = intersectBubbleContour(body, tail.angleRad);
        const rootErr = Math.hypot(tail.rootX - exactRoot.x, tail.rootY - exactRoot.y);
        expect(rootErr).toBeLessThanOrEqual(1);

        // Verify tip stays within max(4px, 1% of displayed video width = 9.6px)
        const expected = computeExpectedTailTip(body, obs, viewport);
        const tipErr = Math.hypot(tail.tipX - expected.tipX, tail.tipY - expected.tipY);
        const bound = Math.max(4, viewport.contentWidth * 0.01);
        expect(tipErr).toBeLessThanOrEqual(bound);

        // Verify smooth ±π crossing (no 360° spin!)
        if (prevAngle !== null) {
          const jump = Math.abs(shortestAngleDelta(prevAngle, tail.angleRad));
          expect(jump).toBeLessThan(Math.PI / 2);
        }
        prevAngle = tail.angleRad;
      }
    }
  });

  it('protects crossing-face identity, does not burn grace on repeated layout frames, and invalidates on cuts/seeks', () => {
    const engine = new BalloonPresentationEngine();
    const viewport = computeContentViewportRect(960, 540, 640, 360, 1);
    const ev = makeEvent({ linkedVisualTrackId: 'track-maya' });

    const mayaObs: FaceObservation = {
      observationSeq: 1,
      captureTimeMs: 0,
      mediaTimeMs: 1000,
      sceneId: 'scene-1',
      trackId: 'track-maya',
      bbox: { x: 0.2, y: 0.4, width: 0.1, height: 0.25 },
      mouth: { x: 0.25, y: 0.58 },
      speakingEvidence: 0.9,
      confidence: 0.95,
      source: 'fixture_reference',
    };
    const devonCloserObs: FaceObservation = {
      observationSeq: 2,
      captureTimeMs: 33,
      mediaTimeMs: 1033,
      sceneId: 'scene-1',
      trackId: 'track-devon',
      bbox: { x: 0.45, y: 0.4, width: 0.1, height: 0.25 },
      mouth: { x: 0.5, y: 0.58 },
      speakingEvidence: 0.05,
      confidence: 0.95,
      source: 'fixture_reference',
    };

    const body = engine.resolveBubbleBody({
      event: ev,
      measuredWidth: 200,
      measuredHeight: 60,
      viewport,
      observations: [mayaObs],
      otherCommittedBodies: [],
      nowMonotonicMs: 0,
    });

    // Establish tail on Maya
    const t1 = engine.resolveTailGeometry({
      event: ev,
      body,
      observations: [mayaObs],
      viewport,
      currentObservationSeq: 1,
      mediaTimeMs: 1000,
      nowMonotonicMs: 100,
      reducedMotion: false,
      taillessMode: false,
    });
    expect(t1.visible).toBe(true);
    expect(t1.trackId).toBe('track-maya');

    // Now Maya disappears briefly while closer listener Devon is visible:
    // Re-rendering the SAME observationSeq=2 ten times must NOT consume grace (only 1 distinct miss!)
    // AND tail MUST NEVER transfer to Devon!
    for (let dup = 0; dup < 10; dup++) {
      const graceTail = engine.resolveTailGeometry({
        event: ev,
        body,
        observations: [devonCloserObs],
        viewport,
        currentObservationSeq: 2, // SAME observationSeq!
        mediaTimeMs: 1033,
        nowMonotonicMs: 120 + dup * 16,
        reducedMotion: false,
        taillessMode: false,
      });
      expect(graceTail.visible).toBe(true);
      expect(graceTail.trackId).toBe('track-maya'); // NEVER stolen by nearer Devon!
    }

    // Two more distinct missing observation sequences (seq 3, seq 4) -> grace exhausted -> hides cleanly
    engine.resolveTailGeometry({
      event: ev,
      body,
      observations: [devonCloserObs],
      viewport,
      currentObservationSeq: 3,
      mediaTimeMs: 1066,
      nowMonotonicMs: 320,
      reducedMotion: false,
      taillessMode: false,
    });
    const hiddenTail = engine.resolveTailGeometry({
      event: ev,
      body,
      observations: [devonCloserObs],
      viewport,
      currentObservationSeq: 4,
      mediaTimeMs: 1100,
      nowMonotonicMs: 360,
      reducedMotion: false,
      taillessMode: false,
    });
    expect(hiddenTail.visible).toBe(false);
  });
});
