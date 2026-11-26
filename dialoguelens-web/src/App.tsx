import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  CURRENT_PROJECT_SCHEMA_VERSION,
  DEFAULT_USER_SETTINGS,
  createDefaultSpeakers,
  type BackendPolicyState,
  type FreeModeAckStatus,
  type NormalizedPoint,
  type ProjectSnapshot,
} from '../shared/types.js';
import {
  exportTimelineToSrt,
  exportTimelineToWebVtt,
  parseSubtitleContent,
} from '../shared/subtitleParser.js';
import {
  buildExportedProjectBundle,
  parseImportedProjectBundle,
  projectRepository,
  verifyRelinkedFileCompatibility,
} from './storage/projectDb.js';
import {
  buildBundledSampleSource,
  buildLocalFileSource,
  buildYouTubeSource,
  probeDirectMediaUrl,
} from './adapters/sourceAdapters.js';
import { LiveFaceTracker, type FaceTrackerDiagnostics } from './adapters/faceTracker.js';
import { SessionController, type InjectedTestFault } from './controllers/sessionController.js';
import { PresentationController } from './controllers/presentationController.js';
import { HomeView } from './components/HomeView.js';
import { PlayerStage } from './components/PlayerStage.js';
import { YouTubeStage } from './components/YouTubeStage.js';
import { TranscriptEditorPanel } from './components/TranscriptEditorPanel.js';
import { SettingsPanel } from './components/SettingsPanel.js';
import { InspectorDrawer } from './components/InspectorDrawer.js';
import type { ReadinessSnapshot } from '../shared/playbackReadiness.js';

type ActiveTab = 'home' | 'player' | 'settings';

declare global {
  interface Window {
    __DIALOGUELENS_DEBUG__?: {
      getProject: () => ProjectSnapshot | null;
      getReadiness: () => ReadinessSnapshot;
      getStageTimings: () => ReturnType<SessionController['getStageTimings']>;
      getAiRequestsAttempted: () => number;
      getPresentationSnapshot: () => ReturnType<PresentationController['getSnapshot']>;
      setInjectedFault: (fault: InjectedTestFault | null) => void;
      setYouTubeEmbedError: (code: number | null) => void;
      prepareWindow: (startMs: number, endMs: number, force?: boolean) => Promise<boolean>;
      toggleScoutSurface: (show: boolean) => void;
    };
  }
}

function downloadBlobFile(content: string, fileName: string, mimeType: string): void {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<ActiveTab>('home');
  const [project, setProject] = useState<ProjectSnapshot | null>(null);
  const [recentProjects, setRecentProjects] = useState<ProjectSnapshot[]>([]);
  const [selectedCueId, setSelectedCueId] = useState<string | null>(null);
  const [mediaTimeMs, setMediaTimeMs] = useState(0);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [storageBanner, setStorageBanner] = useState<string | null>(null);
  const [showInspector, setShowInspector] = useState(false);
  const [showScoutSurface, setShowScoutSurface] = useState(false);
  const [scoutLogs, setScoutLogs] = useState<string[]>([]);
  const [liveTrackerEnabled, setLiveTrackerEnabled] = useState(false);
  const [injectedYtError, setInjectedYtError] = useState<number | null>(null);

  const objectUrlsRef = useRef<Set<string>>(new Set());
  const presentationRef = useRef<PresentationController>(new PresentationController());
  const liveTrackerRef = useRef<LiveFaceTracker>(new LiveFaceTracker());
  const [trackerDiagnostics, setTrackerDiagnostics] = useState<FaceTrackerDiagnostics>(() =>
    liveTrackerRef.current.getDiagnostics(),
  );

  const [readiness, setReadiness] = useState<ReadinessSnapshot>({
    phase: 'IDLE',
    userWantsPlaying: false,
    isHoldingMedia: false,
    autoResumeCountForCurrentHold: 0,
    holdStartedElapsedMs: null,
    deadlineMs: 8000,
    deadlineElapsedMs: 0,
    activeGap: null,
    waivedGaps: [],
    playbackRate: 1,
    requiredLeadMs: 6000,
    observedProcessingMs: 0,
    lastPlaybackRejection: null,
    statusMessage: 'Ready.',
  });

  const [policy, setPolicy] = useState<BackendPolicyState>({
    policyRevision: 0,
    freeCcOnly: true,
    credentialsConfigured: false,
    providerName: 'Google Gemini (@google/genai)',
    scout1Model: 'gemini-2.5-flash',
    scout2Model: 'gemini-2.5-pro',
    activeJobCount: 0,
    cancellingJobCount: 0,
    sessionRequestsUsed: 0,
    sessionRequestBudget: 40,
    circuitOpen: false,
  });
  const [freeModeAck, setFreeModeAck] = useState<FreeModeAckStatus>('confirmed_free');

  const session = useMemo(
    () =>
      new SessionController({
        onProjectUpdated: (updated) => {
          setProject(updated);
          void projectRepository
            .saveProject(updated)
            .then(() => {
              setStorageBanner(null);
              return projectRepository.listProjects();
            })
            .then((list) => setRecentProjects(list))
            .catch((err) => {
              setStorageBanner(
                err instanceof Error
                  ? err.message
                  : 'Browser storage save failed. Export a portable JSON backup.',
              );
            });
        },
        onReadinessUpdated: (r) => setReadiness(r),
        onPolicyUpdated: (p, ack) => {
          setPolicy(p);
          setFreeModeAck(ack);
        },
        onScoutLog: (entry) => {
          setScoutLogs((prev) => [...prev.slice(-49), `[${new Date().toLocaleTimeString()}] ${entry}`]);
        },
      }),
    [],
  );

  useEffect(() => {
    void session.refreshBackendPolicy();
    void projectRepository.listProjects().then(setRecentProjects).catch(() => undefined);

    const unsubscribe = projectRepository.onExternalUpdate((changedId) => {
      void projectRepository.listProjects().then(setRecentProjects).catch(() => undefined);
      const cur = session.getProject();
      if (cur && cur.id === changedId) {
        void projectRepository.getProject(changedId).then((fresh) => {
          if (fresh && fresh.contentRevision > cur.contentRevision) {
            session.loadProject(fresh);
          }
        });
      }
    });

    const tracker = liveTrackerRef.current;
    const objectUrls = objectUrlsRef.current;

    return () => {
      unsubscribe();
      session.dispose();
      tracker.dispose();
      for (const u of objectUrls) {
        URL.revokeObjectURL(u);
      }
    };
  }, [session]);

  // Expose read-only diagnostic hooks for Playwright verification
  useEffect(() => {
    window.__DIALOGUELENS_DEBUG__ = {
      getProject: () => session.getProject(),
      getReadiness: () => session.getReadiness(),
      getStageTimings: () => session.getStageTimings(),
      getAiRequestsAttempted: () => session.getAiRequestsAttempted(),
      getPresentationSnapshot: () => presentationRef.current.getSnapshot(),
      setInjectedFault: (fault) => session.setInjectedTestFault(fault),
      setYouTubeEmbedError: (code) => setInjectedYtError(code),
      prepareWindow: (startMs, endMs, force) => session.prepareWindow(startMs, endMs, force),
      toggleScoutSurface: (show) => setShowScoutSurface(show),
    };
    return () => {
      delete window.__DIALOGUELENS_DEBUG__;
    };
  }, [session]);

  // Accessible keyboard shortcuts strictly guarded against text inputs!
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const tag = target?.tagName?.toLowerCase();
      if (
        tag === 'input' ||
        tag === 'textarea' ||
        tag === 'select' ||
        target?.isContentEditable
      ) {
        return;
      }
      if (activeTab !== 'player' || !project || project.source.kind === 'youtube') return;

      if (e.key === ' ') {
        e.preventDefault();
        if (readiness.userWantsPlaying) {
          session.requestPause();
        } else {
          void session.requestPlay();
        }
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        void session.requestSeek(Math.max(0, mediaTimeMs - 5000));
      } else if (e.key === 'ArrowRight') {
        e.preventDefault();
        void session.requestSeek(mediaTimeMs + 5000);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activeTab, project, readiness.userWantsPlaying, mediaTimeMs, session]);

  const attachSubtitleTextToSession = useCallback(
    async (file: File, targetProj: ProjectSnapshot) => {
      const text = await file.text();
      const isSrt = file.name.toLowerCase().endsWith('.srt');
      const parsed = parseSubtitleContent(text, {
        mediaId: targetProj.source.id,
        trackId: `imported:${file.name}:${Date.now()}`,
        label: `${file.name} (Imported)`,
        language: targetProj.settings.sourceLanguage,
        origin: isSrt ? 'imported_srt' : 'imported_vtt',
        isAutomatic: false,
        sourceRevision: targetProj.evidenceRevision + 1,
        clipDurationMs: targetProj.source.durationMs,
        visibleObservationConfirmed: false,
      });
      session.attachReferenceTrack(parsed.track);
      const dur = targetProj.source.durationMs ?? 28_000;
      await session.prepareWindow(0, Math.min(8000, dur));
    },
    [session],
  );

  const handleOpenSampleFixture = useCallback(async () => {
    setStatusError(null);
    presentationRef.current.resetAll();
    const projectId = `proj-sample-${Date.now()}`;
    const source = buildBundledSampleSource();
    const newProj: ProjectSnapshot = {
      schemaVersion: CURRENT_PROJECT_SCHEMA_VERSION,
      id: projectId,
      title: source.label,
      createdAtMs: Date.now(),
      updatedAtMs: Date.now(),
      contentRevision: 1,
      evidenceRevision: 1,
      configRevision: 1,
      source,
      referenceTracks: [],
      activeReferenceTrackId: null,
      speakers: createDefaultSpeakers(projectId),
      dialogueEvents: [],
      coverage: [],
      settings: { ...DEFAULT_USER_SETTINGS },
      progress: { positionMs: 0, updatedAtMs: Date.now(), progressSeq: 1, isIntentionalSeek: false },
    };

    session.loadProject(newProj);
    setSelectedCueId(null);
    setActiveTab('player');

    try {
      const resp = await fetch('/fixtures/main-dialogue.vtt');
      if (resp.ok) {
        const vttText = await resp.text();
        const parsed = parseSubtitleContent(vttText, {
          mediaId: source.id,
          trackId: 'fixture:main-dialogue-vtt',
          label: 'Sample Reference (WebVTT)',
          language: 'en',
          origin: 'imported_vtt',
          isAutomatic: false,
          sourceRevision: 1,
          clipDurationMs: 28_000,
          visibleObservationConfirmed: false,
        });
        session.attachReferenceTrack(parsed.track);
        // Prepare ONLY the opening 0–8s window initially in Prepare ahead mode!
        await session.prepareWindow(0, 8000);
      }
    } catch {
      setStatusError('Could not fetch sample WebVTT fixture.');
    }
  }, [session]);

  const handleOpenLocalFile = useCallback(
    async (videoFile: File, subtitleFile?: File) => {
      setStatusError(null);
      presentationRef.current.resetAll();
      const { descriptor, objectUrl } = buildLocalFileSource(videoFile);
      objectUrlsRef.current.add(objectUrl);

      const projectId = `proj-local-${Date.now()}`;
      const newProj: ProjectSnapshot = {
        schemaVersion: CURRENT_PROJECT_SCHEMA_VERSION,
        id: projectId,
        title: videoFile.name,
        createdAtMs: Date.now(),
        updatedAtMs: Date.now(),
        contentRevision: 1,
        evidenceRevision: 1,
        configRevision: 1,
        source: descriptor,
        referenceTracks: [],
        activeReferenceTrackId: null,
        speakers: createDefaultSpeakers(projectId),
        dialogueEvents: [],
        coverage: [],
        settings: { ...DEFAULT_USER_SETTINGS },
        progress: { positionMs: 0, updatedAtMs: Date.now(), progressSeq: 1, isIntentionalSeek: false },
      };

      session.loadProject(newProj);
      setSelectedCueId(null);
      setActiveTab('player');

      if (subtitleFile) {
        await attachSubtitleTextToSession(subtitleFile, newProj);
      }
    },
    [session, attachSubtitleTextToSession],
  );

  const handleOpenDirectUrl = useCallback(
    async (rawUrl: string) => {
      setStatusError(null);
      presentationRef.current.resetAll();
      try {
        const descriptor = await probeDirectMediaUrl(rawUrl);
        const projectId = `proj-direct-${Date.now()}`;
        const newProj: ProjectSnapshot = {
          schemaVersion: CURRENT_PROJECT_SCHEMA_VERSION,
          id: projectId,
          title: descriptor.label,
          createdAtMs: Date.now(),
          updatedAtMs: Date.now(),
          contentRevision: 1,
          evidenceRevision: 1,
          configRevision: 1,
          source: descriptor,
          referenceTracks: [],
          activeReferenceTrackId: null,
          speakers: createDefaultSpeakers(projectId),
          dialogueEvents: [],
          coverage: [],
          settings: { ...DEFAULT_USER_SETTINGS },
          progress: { positionMs: 0, updatedAtMs: Date.now(), progressSeq: 1, isIntentionalSeek: false },
        };
        session.loadProject(newProj);
        setSelectedCueId(null);
        setActiveTab('player');
      } catch (err) {
        setStatusError(err instanceof Error ? err.message : 'Unable to probe direct media URL.');
      }
    },
    [session],
  );

  const handleOpenYouTubeUrl = useCallback(
    async (rawUrl: string, subtitleFile?: File) => {
      setStatusError(null);
      setInjectedYtError(null);
      const descriptor = buildYouTubeSource(rawUrl, Boolean(subtitleFile));
      if (!descriptor) {
        setStatusError('That does not look like a valid YouTube video URL.');
        return;
      }

      const projectId = `proj-yt-${Date.now()}`;
      const newProj: ProjectSnapshot = {
        schemaVersion: CURRENT_PROJECT_SCHEMA_VERSION,
        id: projectId,
        title: descriptor.label,
        createdAtMs: Date.now(),
        updatedAtMs: Date.now(),
        contentRevision: 1,
        evidenceRevision: 1,
        configRevision: 1,
        source: descriptor,
        referenceTracks: [],
        activeReferenceTrackId: null,
        speakers: createDefaultSpeakers(projectId),
        dialogueEvents: [],
        coverage: [],
        settings: { ...DEFAULT_USER_SETTINGS },
        progress: { positionMs: 0, updatedAtMs: Date.now(), progressSeq: 1, isIntentionalSeek: false },
      };

      session.loadProject(newProj);
      setSelectedCueId(null);
      setActiveTab('player');

      if (subtitleFile) {
        await attachSubtitleTextToSession(subtitleFile, newProj);
      }
    },
    [session, attachSubtitleTextToSession],
  );

  const handleReopenProject = useCallback(
    (saved: ProjectSnapshot) => {
      setStatusError(null);
      presentationRef.current.resetAll();
      session.loadProject(saved);
      setMediaTimeMs(saved.progress.positionMs);
      setSelectedCueId(null);
      setActiveTab('player');
    },
    [session],
  );

  const handleRelinkFile = useCallback(
    (file: File) => {
      const cur = session.getProject();
      if (!cur) return;
      const check = verifyRelinkedFileCompatibility(cur.source, file);
      if (!check.compatible) {
        setStatusError(
          `Relink verification failed: ${check.mismatchReasons.join(' ')} Select the original file or start a new project.`,
        );
        return;
      }
      setStatusError(check.warnings.length > 0 ? check.warnings.join(' ') : null);
      const objectUrl = URL.createObjectURL(file);
      objectUrlsRef.current.add(objectUrl);

      const relinked: ProjectSnapshot = {
        ...cur,
        source: {
          ...cur.source,
          url: objectUrl,
          requiresRelink: false,
        },
        updatedAtMs: Date.now(),
      };
      session.loadProject(relinked);
    },
    [session],
  );

  const handleImportProjectJson = useCallback(
    async (file: File) => {
      setStatusError(null);
      try {
        const text = await file.text();
        const imported = parseImportedProjectBundle(text);
        await projectRepository.saveProject(imported);
        const list = await projectRepository.listProjects();
        setRecentProjects(list);
        session.loadProject(imported);
        setActiveTab('player');
      } catch (err) {
        setStatusError(err instanceof Error ? err.message : 'Failed to import project JSON.');
      }
    },
    [session],
  );

  const handleExportJson = useCallback(() => {
    if (!project) return;
    const bundle = buildExportedProjectBundle(project);
    downloadBlobFile(
      JSON.stringify(bundle, null, 2),
      `${project.id}.dialoguelens.json`,
      'application/json',
    );
  }, [project]);

  const handleExportVtt = useCallback(() => {
    if (!project) return;
    const labels = new Map(project.speakers.map((s) => [s.id, s.label]));
    const vtt = exportTimelineToWebVtt(
      project.dialogueEvents,
      labels,
      project.settings.textDisplayMode,
    );
    downloadBlobFile(vtt, `${project.id}.vtt`, 'text/vtt');
  }, [project]);

  const handleExportSrt = useCallback(() => {
    if (!project) return;
    const labels = new Map(project.speakers.map((s) => [s.id, s.label]));
    const srt = exportTimelineToSrt(
      project.dialogueEvents,
      labels,
      project.settings.textDisplayMode,
    );
    downloadBlobFile(srt, `${project.id}.srt`, 'text/plain');
  }, [project]);

  const handleToggleLiveTracker = useCallback(async (enabled: boolean) => {
    if (enabled) {
      const ok = await liveTrackerRef.current.initialize();
      setLiveTrackerEnabled(ok);
    } else {
      liveTrackerRef.current.resetTracks();
      setLiveTrackerEnabled(false);
    }
    setTrackerDiagnostics(liveTrackerRef.current.getDiagnostics());
  }, []);

  const handleMediaTimeUpdate = useCallback(
    (timeMs: number, durMs: number) => {
      setMediaTimeMs(timeMs);
      const cur = session.getProject();
      if (cur && durMs > 0 && cur.source.durationMs !== durMs) {
        cur.source.durationMs = durMs;
      }
      // Save periodic playback progress cleanly
      if (cur && Math.abs(timeMs - cur.progress.positionMs) >= 1500) {
        session.recordPlaybackPosition(timeMs, false);
        void projectRepository.saveProgressOnly(cur.id, cur.progress).catch(() => undefined);
      }
    },
    [session],
  );

  const handleCorsDetected = useCallback(
    (allowed: boolean) => {
      const cur = session.getProject();
      if (!cur || cur.source.kind !== 'direct_url') return;
      const nextCap = allowed ? 'supported' : 'blocked';
      if (cur.source.capabilities.frameInspection !== nextCap) {
        cur.source.capabilities.frameInspection = nextCap;
        cur.source.capabilities.audioExtraction = nextCap;
        setProject({ ...cur });
      }
    },
    [session],
  );

  const rootClasses = [
    'app-shell',
    project?.settings.highContrast ? 'high-contrast' : '',
    project?.settings.motionPreference === 'reduced' ? 'reduced-motion' : '',
  ]
    .filter(Boolean)
    .join(' ');

  const statusPillClass =
    freeModeAck === 'confirmed_free'
      ? 'free-confirmed'
      : freeModeAck === 'stopping_unconfirmed'
        ? 'stopping-unconfirmed'
        : freeModeAck === 'ai_enabled'
          ? 'ai-active'
          : 'unconfigured';

  const statusPillLabel =
    freeModeAck === 'confirmed_free'
      ? 'Free CC Only (Zero AI Requests)'
      : freeModeAck === 'stopping_unconfirmed'
        ? 'Stopping AI · Backend Unconfirmed'
        : freeModeAck === 'ai_enabled'
          ? `AI Ready (${policy.sessionRequestsUsed}/${policy.sessionRequestBudget})`
          : 'AI Unconfigured (Keyless Mode)';

  return (
    <div
      className={rootClasses}
      style={{ '--font-scale': String(project?.settings.fontSizeScale ?? 1) } as React.CSSProperties}
    >
      <header className="top-header">
        <div className="brand-group">
          <div className="brand-mark" aria-hidden="true">
            DL
          </div>
          <div>
            <h1 className="brand-title">DialogueLens Web</h1>
          </div>
        </div>

        <nav className="nav-tabs" aria-label="Main navigation">
          <button
            type="button"
            className={`btn ${activeTab === 'home' ? 'btn-active' : ''}`}
            data-testid="nav-home-btn"
            onClick={() => setActiveTab('home')}
          >
            Home
          </button>
          <button
            type="button"
            className={`btn ${activeTab === 'player' ? 'btn-active' : ''}`}
            data-testid="nav-player-btn"
            disabled={!project}
            onClick={() => setActiveTab('player')}
          >
            Player &amp; Editor
          </button>
          <button
            type="button"
            className={`btn ${activeTab === 'settings' ? 'btn-active' : ''}`}
            data-testid="nav-settings-btn"
            disabled={!project}
            onClick={() => setActiveTab('settings')}
          >
            Settings
          </button>
          {project && (
            <button
              type="button"
              className={`btn ${showInspector ? 'btn-active' : ''}`}
              data-testid="nav-inspector-btn"
              onClick={() => setShowInspector((s) => !s)}
            >
              Inspector
            </button>
          )}
        </nav>

        <div className={`status-pill ${statusPillClass}`} data-testid="header-policy-pill">
          {statusPillLabel}
        </div>
      </header>

      <main className="main-container">
        {storageBanner && (
          <div className="notice-box error" role="alert" style={{ marginBottom: 12 }}>
            {storageBanner}
          </div>
        )}

        {activeTab === 'home' && (
          <HomeView
            recentProjects={recentProjects}
            onOpenSampleFixture={() => void handleOpenSampleFixture()}
            onOpenLocalFile={(v, s) => void handleOpenLocalFile(v, s)}
            onOpenDirectUrl={handleOpenDirectUrl}
            onOpenYouTubeUrl={(u, s) => void handleOpenYouTubeUrl(u, s)}
            onReopenProject={handleReopenProject}
            onDeleteProject={(id) => {
              void projectRepository
                .deleteProject(id)
                .then(() => projectRepository.listProjects())
                .then(setRecentProjects);
            }}
            onImportProjectJson={(f) => void handleImportProjectJson(f)}
            statusError={statusError}
          />
        )}

        {activeTab === 'player' && project && (
          <>
            {statusError && (
              <div className="notice-box warn" role="alert" style={{ marginBottom: 12 }}>
                {statusError}
              </div>
            )}
            <div className="workspace-grid">
              <div>
                {project.source.kind === 'youtube' ? (
                  <YouTubeStage
                    project={project}
                    onObservedTimeChange={(ms) => setMediaTimeMs(ms)}
                    onImportSidecarFile={(f) => void attachSubtitleTextToSession(f, project)}
                    injectedEmbedErrorCode={injectedYtError}
                  />
                ) : (
                  <PlayerStage
                    project={project}
                    readiness={readiness}
                    session={session}
                    presentation={presentationRef.current}
                    liveTracker={liveTrackerRef.current}
                    liveTrackerEnabled={liveTrackerEnabled}
                    onMediaTimeUpdate={handleMediaTimeUpdate}
                    onSelectCue={(id) => setSelectedCueId(id)}
                    onDragAnchorCommit={(cueId: string, anchor: NormalizedPoint) => {
                      session.editCue(cueId, { manualAnchor: anchor });
                    }}
                    onRelinkFile={handleRelinkFile}
                    onImportSubtitleFile={(f) => void attachSubtitleTextToSession(f, project)}
                    onCorsAccessDetected={handleCorsDetected}
                    showScoutSurface={showScoutSurface}
                  />
                )}
              </div>

              <TranscriptEditorPanel
                project={project}
                session={session}
                mediaTimeMs={mediaTimeMs}
                selectedCueId={selectedCueId}
                onSelectCue={setSelectedCueId}
                onJumpToTime={(ms) => {
                  setMediaTimeMs(ms);
                  if (project.source.kind !== 'youtube') {
                    void session.requestSeek(ms);
                  }
                }}
                onExportJson={handleExportJson}
                onExportVtt={handleExportVtt}
                onExportSrt={handleExportSrt}
              />
            </div>

            {showInspector && (
              <InspectorDrawer
                project={project}
                readiness={readiness}
                policy={policy}
                scoutLogs={scoutLogs}
                stageTimings={session.getStageTimings()}
                showScoutSurface={showScoutSurface}
                onToggleScoutSurface={() => setShowScoutSurface((s) => !s)}
                onClose={() => setShowInspector(false)}
              />
            )}
          </>
        )}

        {activeTab === 'settings' && project && (
          <SettingsPanel
            project={project}
            session={session}
            policy={policy}
            freeModeAck={freeModeAck}
            trackerDiagnostics={trackerDiagnostics}
            liveTrackerEnabled={liveTrackerEnabled}
            onToggleLiveTracker={handleToggleLiveTracker}
          />
        )}
      </main>
    </div>
  );
};
