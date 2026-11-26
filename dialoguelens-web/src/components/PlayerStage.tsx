import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  exportTimelineToWebVtt,
  selectEventText,
} from '../../shared/subtitleParser.js';
import type {
  DialogueEvent,
  FaceObservation,
  NormalizedPoint,
  ProjectSnapshot,
} from '../../shared/types.js';
import type { ReadinessSnapshot } from '../../shared/playbackReadiness.js';
import type { PresentationController } from '../controllers/presentationController.js';
import type { SessionController } from '../controllers/sessionController.js';
import {
  evaluateMainFixtureObservations,
  testVideoFrameCanvasAccess,
} from '../adapters/sourceAdapters.js';
import type { LiveFaceTracker } from '../adapters/faceTracker.js';

export interface PlayerStageProps {
  project: ProjectSnapshot;
  readiness: ReadinessSnapshot;
  session: SessionController;
  presentation: PresentationController;
  liveTracker: LiveFaceTracker;
  liveTrackerEnabled: boolean;
  onMediaTimeUpdate: (timeMs: number, durationMs: number) => void;
  onSelectCue: (cueId: string) => void;
  onDragAnchorCommit: (cueId: string, anchor: NormalizedPoint) => void;
  onRelinkFile: (file: File) => void;
  onImportSubtitleFile: (file: File) => void;
  onCorsAccessDetected: (allowed: boolean) => void;
  showScoutSurface: boolean;
}

declare global {
  interface Window {
    __DIALOGUELENS_TEST_OBSERVATIONS__?: FaceObservation[] | null;
  }
}

export const PlayerStage: React.FC<PlayerStageProps> = ({
  project,
  readiness,
  session,
  presentation,
  liveTracker,
  liveTrackerEnabled,
  onMediaTimeUpdate,
  onSelectCue,
  onDragAnchorCommit,
  onRelinkFile,
  onImportSubtitleFile,
  onCorsAccessDetected,
  showScoutSurface,
}) => {
  const stageRef = useRef<HTMLDivElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const scoutVideoRef = useRef<HTMLVideoElement | null>(null);
  const bubbleNodesRef = useRef<Map<string, HTMLElement>>(new Map());
  const tailNodesRef = useRef<Map<string, SVGPolygonElement>>(new Map());
  const shoutNodesRef = useRef<Map<string, SVGPolygonElement>>(new Map());

  const [mediaTimeMs, setMediaTimeMs] = useState(project.progress.positionMs);
  const [durationMs, setDurationMs] = useState(project.source.durationMs ?? 0);
  const [isPlaying, setIsPlaying] = useState(false);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [fullscreenNotice, setFullscreenNotice] = useState<string | null>(null);
  const [videoLoadError, setVideoLoadError] = useState<string | null>(null);

  // Track active cues driven strictly by actual media clock
  const activeEvents = useMemo(() => {
    return project.dialogueEvents.filter(
      (ev) => mediaTimeMs >= ev.startMs && mediaTimeMs <= ev.endMs,
    );
  }, [project.dialogueEvents, mediaTimeMs]);

  const speakerMap = useMemo(() => {
    return new Map(project.speakers.map((s) => [s.id, s]));
  }, [project.speakers]);

  const speakerColorMap = useMemo(() => {
    return new Map(project.speakers.map((s) => [s.id, s.color]));
  }, [project.speakers]);

  // Build WebVTT blob URL for native HTML <track> element (hidden on viewer, showing on scout review surface!)
  const nativeTrackUrl = useMemo(() => {
    const activeTrack =
      project.referenceTracks.find((t) => t.id === project.activeReferenceTrackId) ??
      project.referenceTracks[0];
    if (!activeTrack || activeTrack.cues.length === 0) return null;
    const syntheticEvents: DialogueEvent[] = activeTrack.cues.map((c) => ({
      id: c.id,
      projectId: project.id,
      mediaId: project.source.id,
      startMs: c.startMs,
      endMs: c.endMs,
      originalText: c.text,
      sourceLanguage: c.language,
      targetLanguage: c.language,
      translationStatus: 'not_needed',
      speakerId: '',
      speakerRole: 'unknown',
      styleVariant: 'speech',
      sceneId: 'scene-1',
      evidenceCueIds: [c.id],
      evidenceRevision: c.sourceRevision,
      acceptedRevision: 1,
      quality: {
        referenceAlignment: 'supported',
        audioSupport: 'unavailable',
        translationReview: 'supported',
        attribution: 'unverified',
        presentation: 'needs_review',
      },
      protectedFields: { text: false, timing: false, speaker: false, anchor: false },
      uncertaintyNotes: [],
      conflictHints: [],
    }));
    const vtt = exportTimelineToWebVtt(syntheticEvents, new Map(), 'original');
    const blob = new Blob([vtt], { type: 'text/vtt' });
    return URL.createObjectURL(blob);
  }, [project.referenceTracks, project.activeReferenceTrackId, project.id, project.source.id]);

  useEffect(() => {
    return () => {
      if (nativeTrackUrl) URL.revokeObjectURL(nativeTrackUrl);
    };
  }, [nativeTrackUrl]);

  // Attach video elements to SessionController
  useEffect(() => {
    session.attachVideoElement(videoRef.current);
    session.attachScoutReviewVideoElement(scoutVideoRef.current);
    return () => {
      session.attachVideoElement(null);
      session.attachScoutReviewVideoElement(null);
    };
  }, [session, showScoutSurface, project.source.url]);

  // Sync HTML text tracks cleanly (scout review = "showing", viewer = "hidden")
  useEffect(() => {
    session.syncHtmlTextTracks(project.settings.sourceLanguage);
  }, [session, nativeTrackUrl, project.settings.sourceLanguage, showScoutSurface]);

  // ResizeObserver + fullscreen measurement invalidation (acknowledges every change even if dims match!)
  useEffect(() => {
    const stageEl = stageRef.current;
    const videoEl = videoRef.current;
    if (!stageEl) return;

    const measure = () => {
      const rect = stageEl.getBoundingClientRect();
      const natW = videoEl?.videoWidth || project.source.naturalWidth || 640;
      const natH = videoEl?.videoHeight || project.source.naturalHeight || 360;
      presentation.updateViewport(rect.width, rect.height, natW, natH);
    };

    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(stageEl);

    const onFullscreenChange = () => {
      measure();
      const fsEl = document.fullscreenElement;
      if (fsEl && fsEl === videoEl) {
        setFullscreenNotice(
          'Native browser video fullscreen hides custom HTML bubble overlays. Use stage fullscreen for bubble overlays.',
        );
      } else {
        setFullscreenNotice(null);
      }
    };
    document.addEventListener('fullscreenchange', onFullscreenChange);

    return () => {
      ro.disconnect();
      document.removeEventListener('fullscreenchange', onFullscreenChange);
    };
  }, [presentation, project.source.naturalWidth, project.source.naturalHeight]);

  // Authoritative rAF + requestVideoFrameCallback presentation & media-clock loop
  // Keeps high-frequency tail tracking & body placement OUT of full React state churn!
  const activeEventsRef = useRef<DialogueEvent[]>(activeEvents);
  activeEventsRef.current = activeEvents;
  const projectRef = useRef<ProjectSnapshot>(project);
  projectRef.current = project;
  const speakerColorsRef = useRef<Map<string, string>>(speakerColorMap);
  speakerColorsRef.current = speakerColorMap;
  const liveTrackerEnabledRef = useRef(liveTrackerEnabled);
  liveTrackerEnabledRef.current = liveTrackerEnabled;

  useEffect(() => {
    let rafId = 0;
    let rvfcId: number | null = null;
    let observationSeq = 0;
    let lastClockMs = -1;
    let lastFrameTimeMs = -1;
    let lastSceneId = 'scene-1';
    let lastReadinessCheckMs = 0;
    let lastLookaheadMs = 0;

    const videoEl = videoRef.current;

    const getReducedMotion = () => {
      const pref = projectRef.current.settings.motionPreference;
      if (pref === 'reduced') return true;
      if (pref === 'normal') return false;
      return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
    };

    const stepFrame = (nowMonotonicMs: number, frameMediaSec?: number) => {
      const vid = videoRef.current;
      const curProj = projectRef.current;

      const currentSec =
        frameMediaSec !== undefined
          ? frameMediaSec
          : vid && Number.isFinite(vid.currentTime)
            ? vid.currentTime
            : 0;
      const currentMs = Math.round(currentSec * 1000);
      const durMs =
        vid && Number.isFinite(vid.duration) && vid.duration > 0
          ? Math.round(vid.duration * 1000)
          : (curProj.source.durationMs ?? 0);

      // Update React active cue selection only when media time advances meaningfully (>= 25ms)
      // ensuring <= 100ms cue transition timing accuracy without spamming React!
      if (Math.abs(currentMs - lastClockMs) >= 25) {
        lastClockMs = currentMs;
        setMediaTimeMs(currentMs);
        onMediaTimeUpdate(currentMs, durMs);
      }

      // Keep scout review video roughly synced (muted) when open
      const scoutVid = scoutVideoRef.current;
      if (scoutVid && Math.abs(scoutVid.currentTime - currentSec) > 0.35) {
        try {
          scoutVid.currentTime = currentSec;
        } catch {
          // ignore if not yet seekable
        }
      }

      // Detect distinct video frame progress vs repeated layout frames (so duplicate frames don't bump seq!)
      const isFreshMediaFrame = Math.abs(currentMs - lastFrameTimeMs) >= 12 || observationSeq === 0;
      if (isFreshMediaFrame) {
        lastFrameTimeMs = currentMs;
        observationSeq += 1;
      }

      const sceneId =
        curProj.source.id === 'fixture:main-dialogue' && currentMs >= 14_000
          ? 'scene-2'
          : 'scene-1';
      if (sceneId !== lastSceneId) {
        lastSceneId = sceneId;
        presentation.invalidateForCutOrSeek('cut');
      }

      // Acquire observations:
      // 1. Explicit test harness override if set during automated tail-geometry sweep tests
      // 2. Fixture ground-truth trajectory for the bundled sample fixture
      // 3. Real MediaPipe FaceLandmarker when enabled on inspectable media
      let observations: FaceObservation[] = [];
      if (Array.isArray(window.__DIALOGUELENS_TEST_OBSERVATIONS__)) {
        observations = window.__DIALOGUELENS_TEST_OBSERVATIONS__;
      } else if (curProj.source.id === 'fixture:main-dialogue') {
        observations = evaluateMainFixtureObservations(currentMs, observationSeq);
      } else if (liveTrackerEnabledRef.current && vid && curProj.source.capabilities.frameInspection === 'supported') {
        observations = liveTracker.detectFrame(vid, currentMs, sceneId, observationSeq);
      }

      const currentActive = curProj.dialogueEvents.filter(
        (ev) => currentMs >= ev.startMs && currentMs <= ev.endMs,
      );
      if (currentActive.length > 0) {
        session.markCueDisplayed(currentMs);
      }

      presentation.renderFrameToDom({
        activeEvents: currentActive,
        observations,
        observationSeq,
        mediaTimeMs: currentMs,
        nowMonotonicMs,
        displayMode: curProj.settings.displayMode,
        reducedMotion: getReducedMotion(),
        bubbleNodes: bubbleNodesRef.current,
        tailPathNodes: tailNodesRef.current,
        shoutBgNodes: shoutNodesRef.current,
        speakerColors: speakerColorsRef.current,
      });

      // Evaluate playback readiness & elapsed deadlines (~120ms cadence even while paused!)
      if (nowMonotonicMs - lastReadinessCheckMs >= 120) {
        lastReadinessCheckMs = nowMonotonicMs;
        void session.evaluateReadinessTick(
          currentMs,
          durMs,
          vid?.playbackRate ?? 1,
          nowMonotonicMs,
        );
      }

      // Schedule lookahead windows periodically during playback
      if (vid && !vid.paused && nowMonotonicMs - lastLookaheadMs >= 800 && durMs > 0) {
        lastLookaheadMs = nowMonotonicMs;
        session.scheduleLookahead(currentMs, durMs, vid.playbackRate);
      }

      rafId = window.requestAnimationFrame((t) => stepFrame(t));
    };

    // Use requestVideoFrameCallback where supported to anchor media-time updates to decoded frames
    const vWithRvfc = videoEl as HTMLVideoElement & {
      requestVideoFrameCallback?: (
        cb: (now: number, meta: { mediaTime: number }) => void,
      ) => number;
      cancelVideoFrameCallback?: (id: number) => void;
    };

    const onVideoFrame = (now: number, meta: { mediaTime: number }) => {
      const ms = Math.round(meta.mediaTime * 1000);
      if (Math.abs(ms - lastClockMs) >= 16) {
        lastClockMs = ms;
        setMediaTimeMs(ms);
        onMediaTimeUpdate(ms, durationMs);
      }
      if (vWithRvfc?.requestVideoFrameCallback) {
        rvfcId = vWithRvfc.requestVideoFrameCallback(onVideoFrame);
      }
    };

    if (vWithRvfc?.requestVideoFrameCallback) {
      rvfcId = vWithRvfc.requestVideoFrameCallback(onVideoFrame);
    }
    rafId = window.requestAnimationFrame((t) => stepFrame(t));

    return () => {
      window.cancelAnimationFrame(rafId);
      if (rvfcId !== null && vWithRvfc?.cancelVideoFrameCallback) {
        vWithRvfc.cancelVideoFrameCallback(rvfcId);
      }
    };
  }, [presentation, session, liveTracker, durationMs, onMediaTimeUpdate]);

  // Manual drag handler on speech bubble bodies preserving speaker binding
  const startBubbleDrag = (cueId: string, e: React.PointerEvent<HTMLDivElement>) => {
    const stageEl = stageRef.current;
    const bodyEl = bubbleNodesRef.current.get(cueId);
    if (!stageEl || !bodyEl) return;
    e.stopPropagation();
    onSelectCue(cueId);

    const stageRect = stageEl.getBoundingClientRect();
    const startX = e.clientX;
    const startY = e.clientY;
    const initCenterX = Number(bodyEl.dataset.centerX ?? stageRect.width / 2);
    const initCenterY = Number(bodyEl.dataset.centerY ?? stageRect.height / 2);
    let moved = false;

    const onMove = (ev: PointerEvent) => {
      const dx = ev.clientX - startX;
      const dy = ev.clientY - startY;
      if (Math.hypot(dx, dy) > 3) {
        moved = true;
      }
      if (!moved) return;
      const nextCenterX = initCenterX + dx;
      const nextCenterY = initCenterY + dy;
      const norm = presentation.convertStageDragToNormalizedAnchor(nextCenterX, nextCenterY);
      onDragAnchorCommit(cueId, norm);
    };

    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };

    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  const toggleStageFullscreen = async () => {
    const el = stageRef.current;
    if (!el) return;
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
      } else if (el.requestFullscreen) {
        await el.requestFullscreen();
      } else {
        setFullscreenNotice(
          'Stage fullscreen is unsupported in this browser; native video fullscreen excludes custom HTML overlays.',
        );
      }
    } catch {
      setFullscreenNotice(
        'Fullscreen request was blocked by the browser. Try clicking directly on the player.',
      );
    }
  };

  const isPortrait =
    (project.source.naturalWidth ?? 16) < (project.source.naturalHeight ?? 9);

  // Relink banner when reopening a saved local video after page reload
  if (project.source.requiresRelink || !project.source.url) {
    return (
      <div className="card" data-testid="relink-required-panel">
        <h3 style={{ margin: '0 0 8px' }}>Relink Local Video File</h3>
        <p style={{ margin: '0 0 12px', color: 'var(--text-secondary)' }}>
          Browsers do not retain direct filesystem access to local files across reloads. Select{' '}
          <strong>{project.source.fileName ?? project.source.label}</strong> to verify file
          compatibility and resume at {(project.progress.positionMs / 1000).toFixed(1)}s with all
          your saved edits and manual anchors intact.
        </p>
        <label className="btn btn-primary" style={{ cursor: 'pointer' }}>
          Select Local Video to Relink
          <input
            type="file"
            accept="video/*"
            style={{ display: 'none' }}
            data-testid="relink-file-input"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) onRelinkFile(f);
              e.target.value = '';
            }}
          />
        </label>
      </div>
    );
  }

  const coverageSegments = project.coverage.length > 0 ? project.coverage : [];

  return (
    <div>
      <div
        ref={stageRef}
        className={`video-stage-wrapper ${isPortrait ? 'portrait-stage' : ''}`}
        data-testid="video-stage"
      >
        <video
          ref={videoRef}
          data-testid="main-video-player"
          src={project.source.url}
          crossOrigin={project.source.kind === 'direct_url' ? 'anonymous' : undefined}
          playsInline
          preload="metadata"
          onLoadedMetadata={(e) => {
            const vid = e.currentTarget;
            setVideoLoadError(null);
            if (Number.isFinite(vid.duration) && vid.duration > 0) {
              const dMs = Math.round(vid.duration * 1000);
              setDurationMs(dMs);
              onMediaTimeUpdate(Math.round(vid.currentTime * 1000), dMs);
            }
            const rect = stageRef.current?.getBoundingClientRect();
            if (rect) {
              presentation.updateViewport(
                rect.width,
                rect.height,
                vid.videoWidth || 640,
                vid.videoHeight || 360,
              );
            }
            if (project.progress.positionMs > 0 && vid.currentTime < 0.2) {
              vid.currentTime = project.progress.positionMs / 1000;
            }
            session.syncHtmlTextTracks(project.settings.sourceLanguage);
          }}
          onLoadedData={(e) => {
            const canInspect = testVideoFrameCanvasAccess(e.currentTarget);
            onCorsAccessDetected(canInspect);
          }}
          onPlay={() => setIsPlaying(true)}
          onPause={() => setIsPlaying(false)}
          onSeeked={() => presentation.invalidateForCutOrSeek('seek')}
          onError={() => {
            setVideoLoadError(
              'The browser could not decode or fetch this media source. Check codec support or CORS headers.',
            );
          }}
        >
          {nativeTrackUrl && (
            <track
              kind="captions"
              label={`Reference (${project.settings.sourceLanguage})`}
              srcLang={project.settings.sourceLanguage}
              src={nativeTrackUrl}
            />
          )}
        </video>

        {/* Independent SVG contour-attached tails + shout starburst backgrounds */}
        <svg
          className="tail-svg-layer"
          aria-hidden="true"
          data-testid="bubble-tails-svg"
        >
          {activeEvents.map((ev) => (
            <React.Fragment key={`svg-${ev.id}`}>
              <polygon
                ref={(el) => {
                  if (el) shoutNodesRef.current.set(ev.id, el);
                  else shoutNodesRef.current.delete(ev.id);
                }}
                fill="#fef9c3"
                stroke="#b45309"
                strokeWidth="2.5"
                style={{ display: 'none' }}
                data-testid={`shout-contour-${ev.id}`}
              />
              <polygon
                ref={(el) => {
                  if (el) tailNodesRef.current.set(ev.id, el);
                  else tailNodesRef.current.delete(ev.id);
                }}
                fill={project.settings.highContrast ? '#ffffff' : 'rgba(255,255,255,0.96)'}
                stroke={speakerColorMap.get(ev.speakerId) ?? '#1e293b'}
                strokeWidth="2.5"
                strokeLinejoin="round"
                style={{ display: 'none' }}
                data-testid={`bubble-tail-${ev.id}`}
              />
            </React.Fragment>
          ))}
        </svg>

        {/* Measured HTML bubble bodies (exact same body in bubbles & tailless modes!) */}
        <div className="bubble-overlay-layer" data-testid="bubble-overlay-layer">
          {activeEvents.map((ev) => {
            const speaker = speakerMap.get(ev.speakerId);
            const displayText = selectEventText(ev, project.settings.textDisplayMode);
            const isMissingTranslation =
              project.settings.textDisplayMode !== 'original' &&
              ev.translationStatus === 'missing_translation';
            return (
              <div
                key={ev.id}
                ref={(el) => {
                  if (el) bubbleNodesRef.current.set(ev.id, el);
                  else bubbleNodesRef.current.delete(ev.id);
                }}
                data-testid={`bubble-body-${ev.id}`}
                data-cue-id={ev.id}
                data-speaker-id={ev.speakerId}
                data-variant={ev.styleVariant}
                className={`speech-bubble-body variant-${ev.styleVariant} size-${project.settings.bubbleSize}`}
                style={{
                  borderColor:
                    ev.styleVariant === 'narration'
                      ? '#94a3b8'
                      : (speaker?.color ?? '#1e293b'),
                }}
                onPointerDown={(e) => startBubbleDrag(ev.id, e)}
                title="Drag bubble to pin a manual body anchor"
              >
                <div className="bubble-speaker-tag">
                  <span
                    className="bubble-speaker-dot"
                    style={{ background: speaker?.color ?? '#475569' }}
                  />
                  <span>{speaker?.label ?? 'Speaker'}</span>
                  {ev.manualAnchor && <span aria-label="Pinned anchor">· Pinned</span>}
                </div>
                <p className="bubble-text-content">{displayText}</p>
                {isMissingTranslation && (
                  <span className="bubble-untranslated-tag">Untranslated original</span>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {videoLoadError && (
        <div className="notice-box error" role="alert" style={{ marginTop: 10 }}>
          {videoLoadError}
        </div>
      )}

      {fullscreenNotice && (
        <div className="notice-box warn" style={{ marginTop: 10 }}>
          {fullscreenNotice}
        </div>
      )}

      {/* Readiness Hold / Unavailable Gap Banner */}
      {(readiness.phase === 'HOLDING_FOR_COVERAGE' ||
        readiness.phase === 'GAP_UNAVAILABLE' ||
        readiness.lastPlaybackRejection) && (
        <div className="readiness-banner" role="status" data-testid="readiness-banner">
          <div>
            <strong>{readiness.phase.replace(/_/g, ' ')}</strong>: {readiness.statusMessage}
          </div>
          {readiness.phase === 'GAP_UNAVAILABLE' && (
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                type="button"
                className="btn"
                data-testid="gap-retry-btn"
                onClick={() => void session.retryCurrentGap()}
              >
                Retry Gap
              </button>
              <button
                type="button"
                className="btn btn-primary"
                data-testid="gap-continue-btn"
                onClick={() => void session.continuePastGap()}
              >
                Continue Past This Gap
              </button>
            </div>
          )}
        </div>
      )}

      {/* Playback Controls & Actual Coverage Progress */}
      <div className="transport-bar" aria-label="Video playback controls">
        <div>
          <input
            type="range"
            aria-label="Seek timeline"
            data-testid="timeline-scrubber"
            className="timeline-scrubber"
            min={0}
            max={Math.max(1000, durationMs)}
            value={Math.min(Math.max(1000, durationMs), mediaTimeMs)}
            onChange={(e) => {
              const targetMs = Number(e.target.value);
              setMediaTimeMs(targetMs);
              void session.requestSeek(targetMs);
            }}
          />
          {/* Actual Coverage Visualization */}
          <div
            className="coverage-strip"
            aria-label="Actual dialogue coverage"
            data-testid="coverage-strip"
          >
            {coverageSegments.map((seg) => {
              const widthPct =
                durationMs > 0
                  ? Math.max(2, ((seg.endMs - seg.startMs) / durationMs) * 100)
                  : 100 / coverageSegments.length;
              return (
                <div
                  key={`${seg.startMs}-${seg.endMs}`}
                  className={`coverage-segment ${seg.status}`}
                  style={{ width: `${widthPct}%` }}
                  title={`${(seg.startMs / 1000).toFixed(1)}s–${(seg.endMs / 1000).toFixed(1)}s: ${seg.status}`}
                />
              );
            })}
          </div>
        </div>

        <div className="transport-row">
          <button
            type="button"
            className="btn btn-primary"
            data-testid="play-pause-btn"
            onClick={() => {
              if (isPlaying) {
                session.requestPause();
              } else {
                void session.requestPlay();
              }
            }}
          >
            {isPlaying ? 'Pause' : 'Play'}
          </button>

          <button
            type="button"
            className="btn"
            data-testid="seek-back-btn"
            onClick={() => void session.requestSeek(Math.max(0, mediaTimeMs - 5000))}
          >
            -5s
          </button>

          <button
            type="button"
            className="btn"
            data-testid="seek-fwd-btn"
            onClick={() =>
              void session.requestSeek(Math.min(durationMs || mediaTimeMs + 5000, mediaTimeMs + 5000))
            }
          >
            +5s
          </button>

          <span
            data-testid="time-readout"
            style={{ fontVariantNumeric: 'tabular-nums', fontSize: 14, fontWeight: 600 }}
          >
            {(mediaTimeMs / 1000).toFixed(1)}s / {(durationMs / 1000).toFixed(1)}s
          </span>

          <select
            aria-label="Playback speed"
            data-testid="playback-rate-select"
            className="select-input"
            style={{ width: 'auto', minWidth: 80 }}
            value={playbackRate}
            onChange={(e) => {
              const rate = Number(e.target.value);
              setPlaybackRate(rate);
              if (videoRef.current) videoRef.current.playbackRate = rate;
            }}
          >
            <option value={0.5}>0.5×</option>
            <option value={1}>1×</option>
            <option value={1.5}>1.5×</option>
            <option value={2}>2×</option>
          </select>

          <button
            type="button"
            className="btn"
            data-testid="mute-btn"
            onClick={() => {
              const next = !muted;
              setMuted(next);
              if (videoRef.current) videoRef.current.muted = next;
            }}
          >
            {muted ? 'Unmute' : 'Mute'}
          </button>

          <input
            type="range"
            aria-label="Volume"
            min={0}
            max={1}
            step={0.05}
            value={muted ? 0 : volume}
            style={{ width: 84, minHeight: 36 }}
            onChange={(e) => {
              const v = Number(e.target.value);
              setVolume(v);
              setMuted(v === 0);
              if (videoRef.current) {
                videoRef.current.volume = v;
                videoRef.current.muted = v === 0;
              }
            }}
          />

          <button
            type="button"
            className={`btn ${project.settings.displayMode === 'bubbles' ? 'btn-active' : ''}`}
            data-testid="toggle-tail-mode-btn"
            onClick={() =>
              session.updateSettings({
                displayMode:
                  project.settings.displayMode === 'bubbles' ? 'tailless' : 'bubbles',
              })
            }
          >
            {project.settings.displayMode === 'bubbles' ? 'Mode: Speech Bubbles' : 'Mode: Tailless'}
          </button>

          <select
            aria-label="Dialogue text mode"
            data-testid="text-display-mode-select"
            className="select-input"
            style={{ width: 'auto', minWidth: 120 }}
            value={project.settings.textDisplayMode}
            onChange={(e) =>
              session.updateSettings({
                textDisplayMode: e.target.value as 'original' | 'translated' | 'bilingual',
              })
            }
          >
            <option value="original">Original</option>
            <option value="translated">Translated</option>
            <option value="bilingual">Bilingual</option>
          </select>

          <label className="btn" style={{ cursor: 'pointer' }}>
            Import Captions
            <input
              type="file"
              accept=".vtt,.srt,text/vtt,text/plain"
              style={{ display: 'none' }}
              data-testid="player-import-captions-input"
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) onImportSubtitleFile(f);
                e.target.value = '';
              }}
            />
          </label>

          <button
            type="button"
            className="btn"
            data-testid="fullscreen-btn"
            onClick={() => void toggleStageFullscreen()}
          >
            Fullscreen
          </button>
        </div>
      </div>

      {/* Muted Inspectable Scout Review Surface with Native CC Showing */}
      {showScoutSurface && (
        <div className="scout-review-surface" data-testid="scout-review-surface">
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
            Scout Inspectable Review Surface (Muted · Native CC Showing)
          </div>
          <video
            ref={scoutVideoRef}
            data-testid="scout-review-video"
            src={project.source.url}
            muted
            playsInline
            controls
            onLoadedMetadata={() => session.syncHtmlTextTracks(project.settings.sourceLanguage)}
          >
            {nativeTrackUrl && (
              <track
                kind="captions"
                label={`Scout Native CC (${project.settings.sourceLanguage})`}
                srcLang={project.settings.sourceLanguage}
                src={nativeTrackUrl}
                default
              />
            )}
          </video>
        </div>
      )}
    </div>
  );
};
