import React, { useEffect, useRef, useState } from 'react';
import {
  buildYouTubeCaptionStatus,
  buildYouTubeEmbedConfig,
  describeYouTubePlayerError,
  type YouTubeEmbedErrorInfo,
} from '../../shared/youtubeAdapterLogic.js';
import type { ProjectSnapshot } from '../../shared/types.js';

declare global {
  interface Window {
    YT?: {
      Player: new (
        element: HTMLElement | string,
        options: {
          videoId: string;
          playerVars: Record<string, string | number>;
          events?: {
            onReady?: (ev: { target: YTPlayerInstance }) => void;
            onStateChange?: (ev: { data: number; target: YTPlayerInstance }) => void;
            onError?: (ev: { data: number }) => void;
          };
        },
      ) => YTPlayerInstance;
    };
    onYouTubeIframeAPIReady?: () => void;
  }
}

interface YTPlayerInstance {
  getCurrentTime: () => number;
  getDuration: () => number;
  getPlayerState: () => number;
  seekTo: (seconds: number, allowSeekAhead: boolean) => void;
  destroy: () => void;
}

export interface YouTubeStageProps {
  project: ProjectSnapshot;
  onObservedTimeChange: (timeMs: number) => void;
  onImportSidecarFile: (file: File) => void;
  /** Optional simulated embed error code strictly for deterministic E2E error-handling verification. */
  injectedEmbedErrorCode?: number | null;
}

/**
 * Official YouTube Companion Stage.
 * - Uses official IFrame API parameters including `cc_load_policy=1`, `cc_lang_pref`, and `hl`.
 * - NEVER places bubbles, tails, or controls over the YouTube player rectangle.
 * - NEVER gates ordinary viewing on captions, AI configuration, analysis, or payment.
 * - Reports truthful "CC requested; activation not observable" status and native fullscreen exclusion.
 * - Synchronizes the adjacent transcript panel strictly to observed `player.getCurrentTime()`.
 */
export const YouTubeStage: React.FC<YouTubeStageProps> = ({
  project,
  onObservedTimeChange,
  onImportSidecarFile,
  injectedEmbedErrorCode,
}) => {
  const videoId = project.source.youtubeVideoId ?? '';
  const hasImported = project.referenceTracks.length > 0;
  const origin = typeof window !== 'undefined' ? window.location.origin : undefined;
  const embedConfig = buildYouTubeEmbedConfig(videoId, project.settings.sourceLanguage, origin);
  const ccStatus = buildYouTubeCaptionStatus(project.settings.sourceLanguage, hasImported);

  const playerHostRef = useRef<HTMLDivElement | null>(null);
  const playerRef = useRef<YTPlayerInstance | null>(null);
  const [embedError, setEmbedError] = useState<YouTubeEmbedErrorInfo | null>(null);
  const [observedSec, setObservedSec] = useState(0);
  const [apiLoaded, setApiLoaded] = useState(false);

  useEffect(() => {
    if (injectedEmbedErrorCode) {
      setEmbedError(describeYouTubePlayerError(injectedEmbedErrorCode, videoId));
    } else {
      setEmbedError(null);
    }
  }, [injectedEmbedErrorCode, videoId]);

  const onTimeRef = useRef(onObservedTimeChange);
  onTimeRef.current = onObservedTimeChange;

  useEffect(() => {
    if (!videoId || injectedEmbedErrorCode) return;

    let cancelled = false;
    let pollTimer: number | null = null;
    const playerVars = buildYouTubeEmbedConfig(
      videoId,
      project.settings.sourceLanguage,
      typeof window !== 'undefined' ? window.location.origin : undefined,
    ).playerVars;

    const mountPlayer = () => {
      if (cancelled || !playerHostRef.current || !window.YT?.Player) return;
      playerHostRef.current.innerHTML = '';
      const targetDiv = document.createElement('div');
      playerHostRef.current.appendChild(targetDiv);

      const player = new window.YT.Player(targetDiv, {
        videoId,
        playerVars: playerVars as Record<string, string | number>,
        events: {
          onReady: () => {
            if (cancelled) return;
            setApiLoaded(true);
            pollTimer = window.setInterval(() => {
              try {
                const t = player.getCurrentTime();
                if (Number.isFinite(t) && t >= 0) {
                  setObservedSec(t);
                  onTimeRef.current(Math.round(t * 1000));
                }
              } catch {
                // Ignore transient iframe unload errors
              }
            }, 250);
          },
          onError: (ev) => {
            if (!cancelled) {
              setEmbedError(describeYouTubePlayerError(ev.data, videoId));
            }
          },
        },
      });
      playerRef.current = player;
    };

    if (window.YT?.Player) {
      mountPlayer();
    } else {
      const prevReady = window.onYouTubeIframeAPIReady;
      window.onYouTubeIframeAPIReady = () => {
        prevReady?.();
        mountPlayer();
      };
      if (!document.querySelector('script[src="https://www.youtube.com/iframe_api"]')) {
        const script = document.createElement('script');
        script.src = 'https://www.youtube.com/iframe_api';
        script.async = true;
        document.head.appendChild(script);
      }
    }

    return () => {
      cancelled = true;
      if (pollTimer !== null) window.clearInterval(pollTimer);
      try {
        playerRef.current?.destroy();
      } catch {
        // ignore
      }
      playerRef.current = null;
    };
  }, [videoId, project.settings.sourceLanguage, injectedEmbedErrorCode]);

  return (
    <div data-testid="youtube-companion-stage">
      {embedError ? (
        <div className="notice-box error" role="alert" data-testid="youtube-embed-error">
          <strong>{embedError.title}</strong>
          <p style={{ margin: '6px 0 10px' }}>{embedError.message}</p>
          <a
            href={embedError.watchUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-primary btn-link"
            data-testid="open-in-youtube-link"
          >
            Open in YouTube
          </a>
        </div>
      ) : (
        <div
          className="youtube-embed-box"
          data-testid="youtube-player-rect"
          data-embed-url={embedConfig.embedUrl}
          data-cc-load-policy={String(embedConfig.playerVars.cc_load_policy)}
        >
          {/* Fallback official iframe rendered immediately while IFrame API initializes */}
          <div ref={playerHostRef} style={{ width: '100%', height: '100%' }}>
            <iframe
              title={`YouTube player ${videoId}`}
              src={embedConfig.embedUrl}
              allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
              allowFullScreen
            />
          </div>
        </div>
      )}

      {/* All custom controls and disclosures strictly OUTSIDE the YouTube player rectangle */}
      <div className="card" style={{ marginTop: 10 }} data-testid="youtube-caption-status">
        <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 8 }}>
          <div>
            <strong>YouTube Companion Mode</strong>
            <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 2 }}>
              {ccStatus.statusSummary}
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
              {ccStatus.fullscreenNotice} · Observed player time: {observedSec.toFixed(1)}s{' '}
              {apiLoaded ? '(IFrame API synced)' : '(waiting for IFrame API)'}
            </div>
          </div>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
            <label className="btn" style={{ cursor: 'pointer' }}>
              Import Sidecar (.vtt / .srt)
              <input
                type="file"
                accept=".vtt,.srt,text/vtt,text/plain"
                style={{ display: 'none' }}
                data-testid="youtube-stage-sidecar-input"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) onImportSidecarFile(f);
                  e.target.value = '';
                }}
              />
            </label>
            <a
              href={embedConfig.watchUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn-link"
            >
              Open in YouTube
            </a>
          </div>
        </div>
      </div>
    </div>
  );
};
