import {
  getYouTubeCapabilities,
  parseYouTubeVideoId,
} from '../../shared/youtubeAdapterLogic.js';
import type {
  FaceObservation,
  MediaSourceDescriptor,
  SourceCapabilities,
} from '../../shared/types.js';

export function buildLocalFileSource(file: File): {
  descriptor: MediaSourceDescriptor;
  objectUrl: string;
} {
  const objectUrl = URL.createObjectURL(file);
  const capabilities: SourceCapabilities = {
    playback: 'supported',
    captionActivation: 'supported',
    readableCaptionEvidence: 'supported',
    audioExtraction: 'supported',
    frameInspection: 'supported',
    seeking: 'supported',
    permittedOverlayPlacement: 'supported',
    notes: [
      'Local browser media supports full playback, imported/exposed captions, stable bubble overlays, local face tracking, and editing.',
      'Media stays strictly on your device unless you explicitly enable AI mode and approve server upload for AI analysis.',
    ],
  };

  return {
    objectUrl,
    descriptor: {
      id: `local:${file.name}:${file.size}:${file.lastModified}`,
      kind: 'local',
      label: file.name,
      url: objectUrl,
      fileName: file.name,
      fileSize: file.size,
      fileLastModified: file.lastModified,
      mimeType: file.type || 'video/webm',
      durationMs: null,
      capabilities,
      requiresRelink: false,
    },
  };
}

export function buildBundledSampleSource(): MediaSourceDescriptor {
  return {
    id: 'fixture:main-dialogue',
    kind: 'local',
    label: 'Sample: Vault Corridor Dialogue (28s Fixture)',
    url: '/fixtures/main-dialogue.webm',
    fileName: 'main-dialogue.webm',
    mimeType: 'video/webm',
    durationMs: 28_000,
    naturalWidth: 640,
    naturalHeight: 360,
    capabilities: {
      playback: 'supported',
      captionActivation: 'supported',
      readableCaptionEvidence: 'supported',
      audioExtraction: 'supported',
      frameInspection: 'supported',
      seeking: 'supported',
      permittedOverlayPlacement: 'supported',
      notes: [
        'Reproducible CC0 playable fixture demonstrating two moving/crossing speakers, hard scene cut at 14s, confirmed silence, negation, repetition, same-time correction, overlap, off-screen speech, narration, and shout bubbles.',
        'Uses synthetic visual markers (not live human face footage); live MediaPipe face tracking can also be tested on real local human videos.',
      ],
    },
    requiresRelink: false,
  };
}

export function buildYouTubeSource(rawInput: string, hasImportedSidecar = false): MediaSourceDescriptor | null {
  const videoId = parseYouTubeVideoId(rawInput);
  if (!videoId) return null;
  return {
    id: `youtube:${videoId}`,
    kind: 'youtube',
    label: `YouTube (${videoId})`,
    url: `https://www.youtube.com/watch?v=${videoId}`,
    youtubeVideoId: videoId,
    durationMs: null,
    capabilities: getYouTubeCapabilities(hasImportedSidecar),
  };
}

/**
 * Discovers Direct Media URL capabilities independently:
 * playback, seeking, CORS/canvas pixel readback, audio inspection, and captions.
 * A playable URL NEVER implies CORS frame/audio inspection access!
 */
export async function probeDirectMediaUrl(rawUrl: string): Promise<MediaSourceDescriptor> {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl.trim());
  } catch {
    throw new Error('Please enter a valid HTTP(S) media URL.');
  }

  const sameOrigin =
    typeof window !== 'undefined' && parsed.origin === window.location.origin;

  const notes: string[] = [];
  let corsReadable = sameOrigin;
  let playback: SourceCapabilities['playback'] = 'supported';
  let seeking: SourceCapabilities['seeking'] = 'supported';

  if (!sameOrigin) {
    try {
      const resp = await fetch('/api/probe-url', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: parsed.toString() }),
      });
      if (resp.ok) {
        const info = (await resp.json()) as {
          reachable: boolean;
          contentType: string | null;
          acceptRanges: string | null;
          accessControlAllowOrigin: string | null;
        };
        if (!info.reachable) {
          playback = 'unavailable';
          notes.push('Remote media URL returned a non-OK status during HEAD capability check.');
        }
        if (
          info.accessControlAllowOrigin === '*' ||
          (typeof window !== 'undefined' && info.accessControlAllowOrigin === window.location.origin)
        ) {
          corsReadable = true;
          notes.push('Remote media advertises compatible CORS headers for canvas frame inspection.');
        } else {
          corsReadable = false;
          notes.push(
            'Remote media does not expose matching CORS headers: playback may still work, but frame pixel inspection and browser audio extraction are blocked by browser CORS policy.',
          );
        }
        if (info.acceptRanges === 'none') {
          seeking = 'unavailable';
          notes.push('Server reports Accept-Ranges: none; seeking may be restricted.');
        }
      } else {
        const errBody = (await resp.json().catch(() => ({}))) as { error?: string };
        corsReadable = false;
        notes.push(
          errBody.error ??
            'Server HEAD capability check did not confirm CORS headers; assuming frame/audio inspection is blocked.',
        );
      }
    } catch {
      corsReadable = false;
      notes.push('Could not complete CORS pre-probe; frame inspection remains disabled until verified.');
    }
  } else {
    notes.push('Same-origin direct media URL supports full playback, frame inspection, and overlays.');
  }

  return {
    id: `direct:${parsed.toString()}`,
    kind: 'direct_url',
    label: parsed.pathname.split('/').filter(Boolean).pop() || parsed.hostname,
    url: parsed.toString(),
    durationMs: null,
    capabilities: {
      playback,
      captionActivation: 'supported',
      readableCaptionEvidence: 'supported',
      audioExtraction: corsReadable ? 'supported' : 'blocked',
      frameInspection: corsReadable ? 'supported' : 'blocked',
      seeking,
      permittedOverlayPlacement: 'supported',
      notes,
    },
  };
}

/**
 * Runtime canvas-readback CORS check on an actual loaded HTMLVideoElement.
 * Confirms whether the video pixels can genuinely be inspected without SecurityError.
 */
export function testVideoFrameCanvasAccess(video: HTMLVideoElement): boolean {
  if (video.readyState < 2 || video.videoWidth === 0 || video.videoHeight === 0) {
    return false;
  }
  try {
    const canvas = document.createElement('canvas');
    canvas.width = 4;
    canvas.height = 4;
    const ctx = canvas.getContext('2d');
    if (!ctx) return false;
    ctx.drawImage(video, 0, 0, 4, 4);
    ctx.getImageData(0, 0, 1, 1);
    return true;
  } catch {
    return false;
  }
}

/**
 * Deterministic ground-truth observation generator strictly matching the FFmpeg equations
 * of our CC0 sample fixture (`fixtures/main-dialogue.webm`).
 * Explicitly tagged `source: 'fixture_reference'` so synthetic fixture geometry is never
 * mislabeled as live MediaPipe human-face detection.
 */
export function evaluateMainFixtureObservations(
  mediaTimeMs: number,
  observationSeq: number,
): FaceObservation[] {
  const t = Math.max(0, mediaTimeMs / 1000);
  const w = 640;
  const h = 360;
  const boxW = 72 / w;
  const boxH = 96 / h;
  const sceneId = t < 14 ? 'scene-1' : 'scene-2';

  const spkAX = t < 14 ? 100 + 28 * t : 440 - 8 * (t - 14);
  const spkAY = t < 14 ? 145 + 12 * Math.sin(t * 1.4) : 140;

  const spkBX = t < 14 ? 460 - 26 * t : 120 + 6 * (t - 14);
  const spkBY = t < 14 ? 150 - 10 * Math.sin(t * 1.2) : 145;

  const mayaSpeaking =
    (t >= 0.5 && t <= 4.2) ||
    (t >= 8.0 && t <= 10.8) ||
    (t >= 17.2 && t <= 20.2) ||
    (t >= 25.0 && t <= 27.6);
  const devonSpeaking =
    (t >= 4.5 && t <= 7.8) ||
    (t >= 14.4 && t <= 17.0) ||
    (t >= 18.0 && t <= 20.4);

  return [
    {
      observationSeq,
      captureTimeMs: performance.now(),
      mediaTimeMs,
      sceneId,
      trackId: 'track-maya',
      bbox: { x: spkAX / w, y: spkAY / h, width: boxW, height: boxH },
      mouth: { x: (spkAX + 36) / w, y: (spkAY + 64) / h },
      speakingEvidence: mayaSpeaking ? 0.92 : 0.08,
      confidence: 0.98,
      source: 'fixture_reference',
    },
    {
      observationSeq,
      captureTimeMs: performance.now(),
      mediaTimeMs,
      sceneId,
      trackId: 'track-devon',
      bbox: { x: spkBX / w, y: spkBY / h, width: boxW, height: boxH },
      mouth: { x: (spkBX + 36) / w, y: (spkBY + 64) / h },
      speakingEvidence: devonSpeaking ? 0.91 : 0.07,
      confidence: 0.98,
      source: 'fixture_reference',
    },
  ];
}
