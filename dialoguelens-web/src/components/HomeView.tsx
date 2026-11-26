import React, { useState } from 'react';
import type { ProjectSnapshot } from '../../shared/types.js';

export interface HomeViewProps {
  recentProjects: ProjectSnapshot[];
  onOpenSampleFixture: () => void;
  onOpenLocalFile: (videoFile: File, subtitleFile?: File) => void;
  onOpenDirectUrl: (url: string) => Promise<void>;
  onOpenYouTubeUrl: (url: string, subtitleFile?: File) => void;
  onReopenProject: (project: ProjectSnapshot) => void;
  onDeleteProject: (projectId: string) => void;
  onImportProjectJson: (file: File) => void;
  statusError: string | null;
}

export const HomeView: React.FC<HomeViewProps> = ({
  recentProjects,
  onOpenSampleFixture,
  onOpenLocalFile,
  onOpenDirectUrl,
  onOpenYouTubeUrl,
  onReopenProject,
  onDeleteProject,
  onImportProjectJson,
  statusError,
}) => {
  const [selectedVideo, setSelectedVideo] = useState<File | null>(null);
  const [selectedSubtitle, setSelectedSubtitle] = useState<File | null>(null);
  const [directUrl, setDirectUrl] = useState('');
  const [youtubeUrl, setYoutubeUrl] = useState('');
  const [ytSubtitle, setYtSubtitle] = useState<File | null>(null);
  const [busyUrl, setBusyUrl] = useState(false);

  const handleDirectSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!directUrl.trim()) return;
    setBusyUrl(true);
    try {
      await onOpenDirectUrl(directUrl.trim());
    } finally {
      setBusyUrl(false);
    }
  };

  const handleYoutubeSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!youtubeUrl.trim()) return;
    onOpenYouTubeUrl(youtubeUrl.trim(), ytSubtitle ?? undefined);
  };

  return (
    <section aria-label="Open media workspace">
      <div className="card">
        <h2 style={{ margin: '0 0 8px' }}>Watch video with stable, speaker-linked dialogue</h2>
        <p style={{ margin: '0 0 12px', color: 'var(--text-secondary)', maxWidth: 780 }}>
          <strong>Inspectable local or CORS-permitted direct videos</strong> support full bubble
          overlays, active speaker tails, caption import, editing, and optional AI review.{' '}
          <strong>YouTube URLs</strong> play in the official embedded player with native CC
          requested and a synchronized companion transcript beside or below the player (never
          covering the YouTube player).
        </p>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
          <button
            type="button"
            className="btn btn-primary"
            data-testid="open-sample-btn"
            onClick={onOpenSampleFixture}
          >
            Open Playable Sample (28s Fixture)
          </button>

          <label className="btn" style={{ cursor: 'pointer' }}>
            Import Project JSON
            <input
              type="file"
              accept=".json,application/json"
              style={{ display: 'none' }}
              data-testid="import-project-json-input"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) onImportProjectJson(file);
                e.target.value = '';
              }}
            />
          </label>
        </div>

        {statusError && (
          <div className="notice-box error" role="alert" style={{ marginTop: 12 }}>
            {statusError}
          </div>
        )}
      </div>

      <div className="home-grid">
        {/* Local video */}
        <div className="card">
          <h3 style={{ margin: '0 0 8px' }}>1. Local Video (Full Overlay &amp; Editing)</h3>
          <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--text-secondary)' }}>
            Works completely offline in Free CC Only mode with zero AI requests. Media stays on
            your device unless you explicitly enable AI upload.
          </p>
          <div className="form-field">
            <label htmlFor="local-video-input">Video file (WebM / MP4)</label>
            <input
              id="local-video-input"
              data-testid="local-video-input"
              type="file"
              accept="video/*"
              className="text-input"
              onChange={(e) => setSelectedVideo(e.target.files?.[0] ?? null)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="local-subtitle-input">Optional sidecar captions (.vtt / .srt)</label>
            <input
              id="local-subtitle-input"
              data-testid="local-subtitle-input"
              type="file"
              accept=".vtt,.srt,text/vtt,text/plain"
              className="text-input"
              onChange={(e) => setSelectedSubtitle(e.target.files?.[0] ?? null)}
            />
          </div>
          <button
            type="button"
            className="btn btn-primary"
            data-testid="open-local-video-btn"
            disabled={!selectedVideo}
            onClick={() => {
              if (selectedVideo) onOpenLocalFile(selectedVideo, selectedSubtitle ?? undefined);
            }}
          >
            Open Local Video
          </button>
        </div>

        {/* YouTube URL */}
        <form className="card" onSubmit={handleYoutubeSubmit}>
          <h3 style={{ margin: '0 0 8px' }}>2. YouTube URL (Companion Panel Mode)</h3>
          <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--text-secondary)' }}>
            Plays immediately in the official IFrame player with <code>cc_load_policy=1</code>. No
            captions, AI setup, or payment required to watch. Optional sidecar import populates the
            adjacent transcript panel.
          </p>
          <div className="form-field">
            <label htmlFor="youtube-url-input">YouTube watch or youtu.be URL</label>
            <input
              id="youtube-url-input"
              data-testid="youtube-url-input"
              type="url"
              placeholder="https://youtu.be/dQw4w9WgXcQ"
              className="text-input"
              value={youtubeUrl}
              onChange={(e) => setYoutubeUrl(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="youtube-sub-input">Optional sidecar subtitles (.vtt / .srt)</label>
            <input
              id="youtube-sub-input"
              data-testid="youtube-subtitle-input"
              type="file"
              accept=".vtt,.srt,text/vtt,text/plain"
              className="text-input"
              onChange={(e) => setYtSubtitle(e.target.files?.[0] ?? null)}
            />
          </div>
          <button
            type="submit"
            className="btn btn-primary"
            data-testid="open-youtube-btn"
            disabled={!youtubeUrl.trim()}
          >
            Open YouTube Companion
          </button>
        </form>

        {/* Authorized Direct Media URL */}
        <form className="card" onSubmit={handleDirectSubmit}>
          <h3 style={{ margin: '0 0 8px' }}>3. Authorized Direct Media URL</h3>
          <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--text-secondary)' }}>
            Probes playback, seeking, and CORS frame/audio inspection separately. Cross-origin URLs
            without CORS remain playable while honestly marking frame analysis blocked.
          </p>
          <div className="form-field">
            <label htmlFor="direct-url-input">Direct HTTP(S) WebM/MP4 URL</label>
            <input
              id="direct-url-input"
              data-testid="direct-url-input"
              type="url"
              placeholder="https://example.com/clip.webm"
              className="text-input"
              value={directUrl}
              onChange={(e) => setDirectUrl(e.target.value)}
            />
          </div>
          <button
            type="submit"
            className="btn btn-primary"
            data-testid="open-direct-url-btn"
            disabled={!directUrl.trim() || busyUrl}
          >
            {busyUrl ? 'Checking capabilities…' : 'Check & Open Direct URL'}
          </button>
        </form>
      </div>

      {/* Recent Projects */}
      <div className="card" style={{ marginTop: 16 }}>
        <h3 style={{ margin: '0 0 10px' }}>Recent Projects (Saved in IndexedDB)</h3>
        {recentProjects.length === 0 ? (
          <p style={{ margin: 0, color: 'var(--text-secondary)', fontSize: 14 }}>
            No saved projects yet. Open the playable sample or a local video to start.
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {recentProjects.map((proj) => (
              <div
                key={proj.id}
                data-testid={`recent-project-${proj.id}`}
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 10,
                  padding: '10px 12px',
                  borderRadius: 8,
                  border: '1px solid var(--border-subtle)',
                }}
              >
                <div>
                  <strong>{proj.title}</strong>
                  <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                    Source: {proj.source.kind} · {proj.dialogueEvents.length} cues · Last position:{' '}
                    {(proj.progress.positionMs / 1000).toFixed(1)}s · Rev {proj.contentRevision}
                    {proj.source.requiresRelink ? ' · Local file relink required' : ''}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={() => onReopenProject(proj)}
                  >
                    Reopen
                  </button>
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={() => onDeleteProject(proj.id)}
                  >
                    Delete
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
};
