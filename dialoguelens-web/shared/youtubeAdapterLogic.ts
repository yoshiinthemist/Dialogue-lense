import type { SourceCapabilities } from './types.js';

export interface YouTubeEmbedConfig {
  videoId: string;
  embedUrl: string;
  watchUrl: string;
  playerVars: {
    autoplay: 0;
    controls: 1;
    cc_load_policy: 1;
    cc_lang_pref: string;
    hl: string;
    rel: 0;
    modestbranding: 1;
    enablejsapi: 1;
    origin?: string;
  };
}

export interface YouTubeCaptionStatus {
  ccRequested: boolean;
  requestedLanguage: string;
  ccActivationObserved: 'unobservable';
  captionTextAcquired: boolean;
  captionSourceLabel: 'none' | 'imported_sidecar' | 'authorized_owner_download';
  statusSummary: string;
  fullscreenNotice: string;
}

const YOUTUBE_ID_REGEX = /^[a-zA-Z0-9_-]{11}$/;

/**
 * Parses official YouTube watch, short (youtu.be), embed, and shorts URLs.
 * Returns null for non-YouTube or invalid URLs.
 */
export function parseYouTubeVideoId(input: string): string | null {
  const trimmed = input.trim();
  if (YOUTUBE_ID_REGEX.test(trimmed)) {
    return trimmed;
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }

  const host = parsed.hostname.replace(/^www\./, '').toLowerCase();
  if (host === 'youtu.be') {
    const candidate = parsed.pathname.split('/').filter(Boolean)[0] ?? '';
    return YOUTUBE_ID_REGEX.test(candidate) ? candidate : null;
  }

  if (host === 'youtube.com' || host === 'm.youtube.com' || host === 'youtube-nocookie.com') {
    if (parsed.pathname === '/watch') {
      const v = parsed.searchParams.get('v') ?? '';
      return YOUTUBE_ID_REGEX.test(v) ? v : null;
    }
    const parts = parsed.pathname.split('/').filter(Boolean);
    if ((parts[0] === 'embed' || parts[0] === 'shorts' || parts[0] === 'live') && parts[1]) {
      return YOUTUBE_ID_REGEX.test(parts[1]) ? parts[1] : null;
    }
  }

  return null;
}

/**
 * Builds official YouTube IFrame player parameters with cc_load_policy=1
 * and language preferences. Never constructs private caption scraping URLs.
 */
export function buildYouTubeEmbedConfig(
  videoId: string,
  language = 'en',
  origin?: string,
): YouTubeEmbedConfig {
  const safeLang = /^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(language) ? language : 'en';
  const playerVars: YouTubeEmbedConfig['playerVars'] = {
    autoplay: 0,
    controls: 1,
    cc_load_policy: 1,
    cc_lang_pref: safeLang,
    hl: safeLang,
    rel: 0,
    modestbranding: 1,
    enablejsapi: 1,
    ...(origin ? { origin } : {}),
  };

  const query = new URLSearchParams({
    autoplay: String(playerVars.autoplay),
    controls: String(playerVars.controls),
    cc_load_policy: String(playerVars.cc_load_policy),
    cc_lang_pref: playerVars.cc_lang_pref,
    hl: playerVars.hl,
    rel: String(playerVars.rel),
    modestbranding: String(playerVars.modestbranding),
    enablejsapi: String(playerVars.enablejsapi),
  });
  if (origin) {
    query.set('origin', origin);
  }

  return {
    videoId,
    embedUrl: `https://www.youtube.com/embed/${videoId}?${query.toString()}`,
    watchUrl: `https://www.youtube.com/watch?v=${videoId}`,
    playerVars,
  };
}

/**
 * Truthful YouTube capability declaration per YouTube IFrame API & Required Minimum Functionality:
 * - Overlays over the player rectangle are strictly disallowed (companion panel only).
 * - Native CC is requested via cc_load_policy=1, but activation is NOT reliably observable.
 * - No audio, video frames, or arbitrary public caption text are assumed accessible.
 */
export function getYouTubeCapabilities(hasImportedSidecar: boolean): SourceCapabilities {
  return {
    playback: 'supported',
    captionActivation: 'unobservable',
    readableCaptionEvidence: hasImportedSidecar ? 'supported' : 'unavailable',
    audioExtraction: 'blocked',
    frameInspection: 'blocked',
    seeking: 'supported',
    permittedOverlayPlacement: 'blocked',
    notes: [
      'YouTube plays inside the official embedded player with all custom UI placed beside or below the player rectangle.',
      'Native CC is requested via cc_load_policy=1, but the IFrame API does not provide reliable CC activation readback ("CC requested; activation not observable").',
      'Audio extraction, video frame inspection, and overlays on top of the YouTube player are disabled by policy.',
      hasImportedSidecar
        ? 'Imported sidecar subtitles are active in the companion transcript panel (labeled imported, not native-verified).'
        : 'Import an SRT/WebVTT sidecar file if you want synchronized dialogue in the adjacent companion panel. Ordinary YouTube viewing works without captions or AI.',
    ],
  };
}

export function buildYouTubeCaptionStatus(
  requestedLanguage: string,
  hasImportedSidecar: boolean,
): YouTubeCaptionStatus {
  return {
    ccRequested: true,
    requestedLanguage,
    ccActivationObserved: 'unobservable',
    captionTextAcquired: hasImportedSidecar,
    captionSourceLabel: hasImportedSidecar ? 'imported_sidecar' : 'none',
    statusSummary: hasImportedSidecar
      ? 'CC requested; activation not observable · Companion panel uses imported sidecar subtitles (not native-verified)'
      : 'CC requested; activation not observable · No caption text acquired (import sidecar SRT/VTT optional)',
    fullscreenNotice:
      'Native YouTube fullscreen displays only the embedded player and excludes the adjacent DialogueLens companion panel.',
  };
}

export interface YouTubeEmbedErrorInfo {
  code: number;
  title: string;
  message: string;
  watchUrl: string;
}

export function describeYouTubePlayerError(code: number, videoId: string): YouTubeEmbedErrorInfo {
  const watchUrl = `https://www.youtube.com/watch?v=${videoId}`;
  switch (code) {
    case 2:
      return {
        code,
        title: 'Invalid YouTube video ID',
        message: 'YouTube rejected this video identifier. Verify the URL and try again.',
        watchUrl,
      };
    case 5:
      return {
        code,
        title: 'HTML5 player error',
        message: 'The embedded YouTube player could not decode this video in the current browser.',
        watchUrl,
      };
    case 100:
      return {
        code,
        title: 'Video unavailable or private',
        message: 'This YouTube video was removed or marked private and cannot be played here.',
        watchUrl,
      };
    case 101:
    case 150:
      return {
        code,
        title: 'Embedding restricted by owner',
        message: 'The video owner disabled embedded playback on external sites. You can watch it directly on YouTube.',
        watchUrl,
      };
    default:
      return {
        code,
        title: 'YouTube playback error',
        message: `The official YouTube player reported error code ${code}.`,
        watchUrl,
      };
  }
}
