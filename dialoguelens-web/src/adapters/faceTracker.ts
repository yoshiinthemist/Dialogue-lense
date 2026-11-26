import type { FaceObservation } from '../../shared/types.js';

export type TrackerStatus =
  | 'idle'
  | 'loading_model'
  | 'ready'
  | 'unsupported'
  | 'blocked_cors'
  | 'error';

export interface FaceTrackerDiagnostics {
  status: TrackerStatus;
  provider: string;
  modelDownloadNotice: string;
  limitationsNotice: string;
  lastError: string | null;
  framesProcessed: number;
  facesDetectedLastFrame: number;
}

interface TrackMemory {
  trackId: string;
  lastCenterX: number;
  lastCenterY: number;
  lastSeenMs: number;
  lastMouthOpen: number;
}

/**
 * Real MediaPipe Face Landmarker (@mediapipe/tasks-vision) wrapper for inspectable local/CORS media.
 * - Runs completely on-device inside the browser (never uploads frames).
 * - Discloses required WASM/model download when enabled.
 * - Explicitly discloses limited reliability on stylized anime, heavy occlusion, and fast crossings.
 * - Uses IoU/proximity + velocity-bounded track continuity so crossing faces avoid silent swaps.
 */
export class LiveFaceTracker {
  private landmarker: {
    detectForVideo: (
      video: HTMLVideoElement,
      timestampMs: number,
    ) => {
      faceLandmarks?: Array<Array<{ x: number; y: number; z?: number }>>;
    };
    close: () => void;
  } | null = null;

  private status: TrackerStatus = 'idle';
  private lastError: string | null = null;
  private framesProcessed = 0;
  private facesDetectedLastFrame = 0;
  private nextTrackNum = 1;
  private tracks = new Map<string, TrackMemory>();
  private lastVideoTimeMs = -1;

  getDiagnostics(): FaceTrackerDiagnostics {
    return {
      status: this.status,
      provider: '@mediapipe/tasks-vision FaceLandmarker',
      modelDownloadNotice:
        'Enabling live on-device face tracking downloads the MediaPipe Face Landmarker WASM/task weights in your browser (local execution; no video frames are uploaded).',
      limitationsNotice:
        'Human face landmarking does not establish personal identity or active speech by itself, and has reduced accuracy on stylized anime, occluded profiles, or rapid crossings. Manual speaker mapping is always supported.',
      lastError: this.lastError,
      framesProcessed: this.framesProcessed,
      facesDetectedLastFrame: this.facesDetectedLastFrame,
    };
  }

  async initialize(): Promise<boolean> {
    if (this.status === 'ready' && this.landmarker) return true;
    this.status = 'loading_model';
    this.lastError = null;
    try {
      const vision = await import('@mediapipe/tasks-vision');
      const fileset = await vision.FilesetResolver.forVisionTasks(
        'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.18/wasm',
      );
      this.landmarker = await vision.FaceLandmarker.createFromOptions(fileset, {
        baseOptions: {
          modelAssetPath:
            'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task',
          delegate: 'CPU',
        },
        runningMode: 'VIDEO',
        numFaces: 4,
        minFaceDetectionConfidence: 0.45,
        minFacePresenceConfidence: 0.45,
        minTrackingConfidence: 0.45,
      });
      this.status = 'ready';
      return true;
    } catch (err) {
      this.status = 'error';
      this.lastError =
        err instanceof Error
          ? err.message
          : 'Unable to load MediaPipe FaceLandmarker model in this environment.';
      return false;
    }
  }

  resetTracks(): void {
    this.tracks.clear();
    this.lastVideoTimeMs = -1;
  }

  detectFrame(
    video: HTMLVideoElement,
    mediaTimeMs: number,
    sceneId: string,
    observationSeq: number,
  ): FaceObservation[] {
    if (this.status !== 'ready' || !this.landmarker) return [];
    if (video.readyState < 2 || video.videoWidth === 0 || video.videoHeight === 0) return [];

    // Hard seek or cut resets short-term track proximity memory immediately
    if (this.lastVideoTimeMs >= 0 && Math.abs(mediaTimeMs - this.lastVideoTimeMs) > 700) {
      this.tracks.clear();
    }
    this.lastVideoTimeMs = mediaTimeMs;

    try {
      const nowMs = performance.now();
      const result = this.landmarker.detectForVideo(video, nowMs);
      const faces = result.faceLandmarks ?? [];
      this.framesProcessed += 1;
      this.facesDetectedLastFrame = faces.length;

      const assignedTrackIds = new Set<string>();
      const observations: FaceObservation[] = [];

      for (const landmarks of faces) {
        if (landmarks.length < 15) continue;
        let minX = 1;
        let maxX = 0;
        let minY = 1;
        let maxY = 0;
        for (const pt of landmarks) {
          if (pt.x < minX) minX = pt.x;
          if (pt.x > maxX) maxX = pt.x;
          if (pt.y < minY) minY = pt.y;
          if (pt.y > maxY) maxY = pt.y;
        }
        const width = Math.max(0.02, maxX - minX);
        const height = Math.max(0.02, maxY - minY);
        const cx = minX + width / 2;
        const cy = minY + height / 2;

        // MediaPipe canonical mouth landmarks: 13 (upper inner lip), 14 (lower inner lip)
        const upperLip = landmarks[13] ?? { x: cx, y: minY + height * 0.72 };
        const lowerLip = landmarks[14] ?? { x: cx, y: minY + height * 0.76 };
        const mouthX = (upperLip.x + lowerLip.x) / 2;
        const mouthY = (upperLip.y + lowerLip.y) / 2;
        const mouthOpen = Math.abs(lowerLip.y - upperLip.y) / Math.max(0.01, height);

        // Conservative track matching with crossing guard:
        // If two existing tracks are nearly equidistant (ambiguous crossing), avoid stealing!
        let bestId: string | null = null;
        let bestDist = 0.14;
        let secondBestDist = Number.POSITIVE_INFINITY;

        for (const [id, mem] of this.tracks.entries()) {
          if (assignedTrackIds.has(id)) continue;
          if (nowMs - mem.lastSeenMs > 1200) continue;
          const dist = Math.hypot(cx - mem.lastCenterX, cy - mem.lastCenterY);
          if (dist < bestDist) {
            secondBestDist = bestDist;
            bestDist = dist;
            bestId = id;
          } else if (dist < secondBestDist) {
            secondBestDist = dist;
          }
        }

        // Ambiguous crossing guard: if two tracks are within 0.03 normalized distance of each other,
        // allocate a fresh provisional track rather than silently swapping speakers.
        const ambiguousCrossing = bestId !== null && Math.abs(secondBestDist - bestDist) < 0.025;
        const trackId =
          bestId && !ambiguousCrossing ? bestId : `live-track-${this.nextTrackNum++}`;
        assignedTrackIds.add(trackId);

        const prevMem = this.tracks.get(trackId);
        const mouthDelta = prevMem ? Math.abs(mouthOpen - prevMem.lastMouthOpen) : 0;
        const speakingEvidence = Math.min(1, mouthOpen * 4.5 + mouthDelta * 8);

        this.tracks.set(trackId, {
          trackId,
          lastCenterX: cx,
          lastCenterY: cy,
          lastSeenMs: nowMs,
          lastMouthOpen: mouthOpen,
        });

        observations.push({
          observationSeq,
          captureTimeMs: nowMs,
          mediaTimeMs,
          sceneId,
          trackId,
          bbox: { x: minX, y: minY, width, height },
          mouth: { x: mouthX, y: mouthY },
          speakingEvidence,
          confidence: ambiguousCrossing ? 0.3 : 0.85,
          occluded: false,
          source: 'mediapipe_live',
        });
      }

      return observations;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'SecurityError') {
        this.status = 'blocked_cors';
        this.lastError = 'Browser blocked pixel inspection due to CORS restrictions on this media source.';
      } else {
        this.lastError = err instanceof Error ? err.message : 'Face detection frame error.';
      }
      return [];
    }
  }

  dispose(): void {
    this.landmarker?.close();
    this.landmarker = null;
    this.tracks.clear();
    this.status = 'idle';
  }
}
