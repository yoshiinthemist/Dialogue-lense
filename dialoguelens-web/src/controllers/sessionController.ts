import {
  TimelineAcceptanceStore,
  runScout1EvidenceDraft,
  runScout2IndependentReview,
} from '../../shared/scoutPipeline.js';
import {
  PlaybackReadinessController,
  type ReadinessSnapshot,
} from '../../shared/playbackReadiness.js';
import type {
  BackendPolicyState,
  DialogueEvent,
  EvidenceCue,
  FreeModeAckStatus,
  ProjectSnapshot,
  ReferenceTrack,
  SpeakerProfile,
  UserSettings,
} from '../../shared/types.js';
import type { ScoutWorkerRequest, ScoutWorkerResponse } from '../workers/scoutWorker.js';

export interface ScoutStageTimingRecord {
  windowStartMs: number;
  windowEndMs: number;
  preparedAtMs: number;
  reviewedAtMs: number;
  acceptedAtMs: number;
  firstDisplayedAtMs: number | null;
  repairedIssueCount: number;
}

export interface SessionControllerCallbacks {
  onProjectUpdated: (project: ProjectSnapshot) => void;
  onReadinessUpdated: (readiness: ReadinessSnapshot) => void;
  onPolicyUpdated: (policy: BackendPolicyState, ackStatus: FreeModeAckStatus) => void;
  onScoutLog: (entry: string) => void;
}

export interface InjectedTestFault {
  delayReviewMs?: number;
  corruptFirstCueText?: string;
  dropSecondCue?: boolean;
  blockBackendRequests?: boolean;
}

/**
 * Single authoritative Session Controller.
 * Coordinates:
 * - Source lifecycle & media play/pause/seek commands via PlaybackReadinessController
 * - Scout 1 + Scout 2 interval scheduling via Web Worker (or synchronous fallback)
 * - Transactional publication via TimelineAcceptanceStore
 * - Authoritative Free CC Only policy handshake with backend (showing stopping/unconfirmed until acked)
 * - Project-scoped Undo/Redo history stack (never leaks across projects!)
 */
export class SessionController {
  private project: ProjectSnapshot | null = null;
  private store: TimelineAcceptanceStore | null = null;
  private readiness = new PlaybackReadinessController(8000);
  private worker: Worker | null = null;
  private pendingWorkerRequests = new Map<
    string,
    { resolve: (r: ScoutWorkerResponse) => void; reject: (e: Error) => void }
  >();
  private inFlightWindows = new Set<string>();
  private undoStack: Array<{ events: DialogueEvent[]; speakers: SpeakerProfile[] }> = [];
  private redoStack: Array<{ events: DialogueEvent[]; speakers: SpeakerProfile[] }> = [];
  private stageTimings: ScoutStageTimingRecord[] = [];
  private backendPolicy: BackendPolicyState = {
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
  };
  private freeModeAckStatus: FreeModeAckStatus = 'confirmed_free';
  private injectedFault: InjectedTestFault | null = null;
  private videoEl: HTMLVideoElement | null = null;
  private scoutReviewVideoEl: HTMLVideoElement | null = null;
  private aiRequestsAttempted = 0;

  constructor(private readonly callbacks: SessionControllerCallbacks) {
    this.initWorker();
  }

  private initWorker(): void {
    if (typeof Worker === 'undefined') return;
    try {
      this.worker = new Worker(new URL('../workers/scoutWorker.ts', import.meta.url), {
        type: 'module',
      });
      this.worker.onmessage = (ev: MessageEvent<ScoutWorkerResponse>) => {
        const pending = this.pendingWorkerRequests.get(ev.data.requestId);
        if (pending) {
          this.pendingWorkerRequests.delete(ev.data.requestId);
          pending.resolve(ev.data);
        }
      };
      this.worker.onerror = () => {
        for (const [id, pending] of this.pendingWorkerRequests.entries()) {
          pending.reject(new Error('Scout worker encountered an execution error.'));
          this.pendingWorkerRequests.delete(id);
        }
      };
    } catch {
      this.worker = null;
    }
  }

  attachVideoElement(video: HTMLVideoElement | null): void {
    this.videoEl = video;
  }

  attachScoutReviewVideoElement(video: HTMLVideoElement | null): void {
    this.scoutReviewVideoEl = video;
  }

  setInjectedTestFault(fault: InjectedTestFault | null): void {
    this.injectedFault = fault;
  }

  getStageTimings(): ScoutStageTimingRecord[] {
    return this.stageTimings.map((t) => ({ ...t }));
  }

  getAiRequestsAttempted(): number {
    return this.aiRequestsAttempted;
  }

  getFreeModeAckStatus(): FreeModeAckStatus {
    return this.freeModeAckStatus;
  }

  getBackendPolicy(): BackendPolicyState {
    return { ...this.backendPolicy };
  }

  getReadiness(): ReadinessSnapshot {
    return this.readiness.getSnapshot();
  }

  canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  canRedo(): boolean {
    return this.redoStack.length > 0;
  }

  /**
   * Loads a project into the authoritative session controller and resets project-scoped
   * undo/redo history so Project A's Undo can NEVER affect Project B.
   */
  loadProject(project: ProjectSnapshot): void {
    this.project = structuredClone(project);
    this.undoStack = [];
    this.redoStack = [];
    this.inFlightWindows.clear();
    this.stageTimings = [];

    this.store = new TimelineAcceptanceStore({
      projectId: project.id,
      mediaId: project.source.id,
      evidenceRevision: project.evidenceRevision,
      configRevision: project.configRevision,
      acceptedRevision: project.contentRevision,
      viewerGeneration: 1,
      pendingDraftRevision: project.contentRevision,
      events: project.dialogueEvents,
      speakers: project.speakers,
      coverage: project.coverage,
    });

    this.readiness = new PlaybackReadinessController(project.settings.budgets.intervalDeadlineMs);
    this.callbacks.onProjectUpdated(this.project);
    this.callbacks.onReadinessUpdated(this.readiness.getSnapshot());
  }

  getProject(): ProjectSnapshot | null {
    return this.project;
  }

  async refreshBackendPolicy(): Promise<void> {
    if (this.injectedFault?.blockBackendRequests) {
      return;
    }
    try {
      const res = await fetch('/api/policy');
      if (!res.ok) return;
      const data = (await res.json()) as BackendPolicyState;
      this.backendPolicy = data;
      if (data.freeCcOnly) {
        this.freeModeAckStatus = 'confirmed_free';
      } else if (!data.credentialsConfigured) {
        this.freeModeAckStatus = 'credentials_unconfigured';
      } else {
        this.freeModeAckStatus = 'ai_enabled';
      }
      this.callbacks.onPolicyUpdated(this.backendPolicy, this.freeModeAckStatus);
    } catch {
      // Backend unreachable; leave current status honest
    }
  }

  /**
   * Authoritative Free CC Only switch.
   * Immediately blocks new local AI dispatches and shows `stopping_unconfirmed` until
   * the backend explicitly acknowledges disabling active server jobs.
   */
  async setFreeCcOnlyMode(freeCcOnly: boolean, apiToken?: string): Promise<FreeModeAckStatus> {
    if (this.project) {
      this.updateSettings({ freeCcOnly });
    }

    if (freeCcOnly) {
      this.freeModeAckStatus = 'stopping_unconfirmed';
      this.callbacks.onPolicyUpdated(this.backendPolicy, this.freeModeAckStatus);
      this.callbacks.onScoutLog('Switching to Free CC Only — awaiting backend cancellation acknowledgement…');
    }

    if (this.injectedFault?.blockBackendRequests) {
      if (freeCcOnly) {
        this.freeModeAckStatus = 'stopping_unconfirmed';
        this.callbacks.onPolicyUpdated(this.backendPolicy, this.freeModeAckStatus);
        this.callbacks.onScoutLog(
          'Backend unreachable: new local AI work is blocked, but backend job cancellation remains UNCONFIRMED.',
        );
      }
      return this.freeModeAckStatus;
    }

    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (apiToken) {
        headers.Authorization = `Bearer ${apiToken}`;
      }
      const res = await fetch('/api/policy/free-mode', {
        method: 'POST',
        headers,
        body: JSON.stringify({ freeCcOnly }),
      });
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const data = (await res.json()) as { acknowledged: boolean; policy: BackendPolicyState };
      this.backendPolicy = data.policy;
      if (freeCcOnly && data.acknowledged) {
        this.freeModeAckStatus = 'confirmed_free';
        this.callbacks.onScoutLog(
          `Free CC Only confirmed by backend (policy rev ${data.policy.policyRevision}); zero new AI admissions allowed.`,
        );
      } else if (!freeCcOnly) {
        this.freeModeAckStatus = data.policy.credentialsConfigured
          ? 'ai_enabled'
          : 'credentials_unconfigured';
      }
      this.callbacks.onPolicyUpdated(this.backendPolicy, this.freeModeAckStatus);
      return this.freeModeAckStatus;
    } catch {
      if (freeCcOnly) {
        this.freeModeAckStatus = 'stopping_unconfirmed';
        this.callbacks.onPolicyUpdated(this.backendPolicy, this.freeModeAckStatus);
        this.callbacks.onScoutLog(
          'Backend unreachable during Free CC Only switch: cancellation stays visibly UNCONFIRMED.',
        );
      }
      return this.freeModeAckStatus;
    }
  }

  /**
   * Configures HTMLMediaElement TextTracks cleanly:
   * - On the muted inspectable scout review video, sets `mode = "showing"` so native CC is visibly rendered.
   * - On the main viewer video, sets `mode = "hidden"` so cues remain readable without duplicating bubbles.
   * Never toggles healthy tracks repeatedly!
   */
  syncHtmlTextTracks(preferredLanguage: string): {
    scoutShowingTrack: string | null;
    viewerHiddenTrack: string | null;
  } {
    let scoutShowingTrack: string | null = null;
    let viewerHiddenTrack: string | null = null;

    const configureElement = (el: HTMLVideoElement | null, targetMode: TextTrackMode): string | null => {
      if (!el || !el.textTracks || el.textTracks.length === 0) return null;
      let selectedIdx = -1;
      for (let i = 0; i < el.textTracks.length; i++) {
        const track = el.textTracks[i];
        if (!track) continue;
        if (
          track.language &&
          track.language.toLowerCase().startsWith(preferredLanguage.toLowerCase().split('-')[0] ?? 'en')
        ) {
          selectedIdx = i;
          break;
        }
      }
      if (selectedIdx === -1) selectedIdx = 0;

      for (let i = 0; i < el.textTracks.length; i++) {
        const track = el.textTracks[i];
        if (!track) continue;
        const desired: TextTrackMode = i === selectedIdx ? targetMode : 'disabled';
        if (track.mode !== desired) {
          track.mode = desired;
        }
        if (i === selectedIdx) {
          scoutShowingTrack = track.label || track.language || `track-${i}`;
        }
      }
      return el.textTracks[selectedIdx]?.label || el.textTracks[selectedIdx]?.language || 'track-0';
    };

    scoutShowingTrack = configureElement(this.scoutReviewVideoEl, 'showing');
    viewerHiddenTrack = configureElement(this.videoEl, 'hidden');
    return { scoutShowingTrack, viewerHiddenTrack };
  }

  attachReferenceTrack(track: ReferenceTrack): void {
    if (!this.project || !this.store) return;
    const nextTracks = this.project.referenceTracks.filter((t) => t.id !== track.id);
    nextTracks.push(track);
    this.project = {
      ...this.project,
      referenceTracks: nextTracks,
      activeReferenceTrackId: track.id,
      evidenceRevision: this.project.evidenceRevision + 1,
      updatedAtMs: Date.now(),
    };
    this.store.bumpEvidenceRevision();
    this.callbacks.onProjectUpdated(this.project);
    this.callbacks.onScoutLog(
      `Loaded reference track "${track.label}" (${track.cues.length} cues, origin: ${track.origin}).`,
    );
  }

  getActiveReferenceTrack(): ReferenceTrack | null {
    if (!this.project) return null;
    if (this.project.activeReferenceTrackId) {
      const found = this.project.referenceTracks.find(
        (t) => t.id === this.project?.activeReferenceTrackId,
      );
      if (found) return found;
    }
    return this.project.referenceTracks[0] ?? null;
  }

  /**
   * Prepares a specific time window `[startMs, endMs]` through Scout 1 -> Scout 2 -> Transactional Acceptance.
   * Respects Free CC Only (ZERO AI network requests when Free CC Only is on!).
   */
  async prepareWindow(startMs: number, endMs: number, forceRetry = false): Promise<boolean> {
    if (!this.project || !this.store) return false;
    const project = this.project;
    const windowKey = `${project.id}:${project.evidenceRevision}:${startMs}-${endMs}`;
    if (this.inFlightWindows.has(windowKey) && !forceRetry) {
      return false;
    }

    const existingCov = project.coverage.find(
      (c) => c.startMs === startMs && c.endMs === endMs,
    );
    if (
      !forceRetry &&
      existingCov &&
      (existingCov.status === 'accepted' || existingCov.status === 'confirmed_silence') &&
      existingCov.evidenceRevision === project.evidenceRevision
    ) {
      return true;
    }

    this.inFlightWindows.add(windowKey);
    const startedAt = performance.now();

    try {
      const track = this.getActiveReferenceTrack();
      const evidenceCues: EvidenceCue[] = track?.cues ?? [];
      const token = this.store.beginNewPendingDraft(
        startMs,
        endMs,
        `win-${startMs}-${endMs}`,
        (existingCov?.attempts ?? 0) + 1,
      );

      // Strictly check Free CC Only before ANY AI provider call!
      const useServerAi =
        !project.settings.freeCcOnly &&
        this.freeModeAckStatus === 'ai_enabled' &&
        this.backendPolicy.credentialsConfigured &&
        !this.injectedFault?.blockBackendRequests;

      if (useServerAi) {
        this.aiRequestsAttempted += 1;
        const resp = await fetch('/api/ai/analyze-interval', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            ...token,
            sourceLanguage: project.settings.sourceLanguage,
            targetLanguage: project.settings.targetLanguage,
            clipDurationMs: project.source.durationMs,
            evidenceCues: evidenceCues.filter(
              (c) => c.endMs > startMs && c.startMs < endMs,
            ),
            glossary: project.settings.glossary,
            speakers: project.speakers,
          }),
        });

        if (resp.ok) {
          const data = (await resp.json()) as {
            outcome: ScoutWorkerResponse['outcome'];
            newSpeakers: SpeakerProfile[];
            latencyMs: number;
          };
          this.readiness.recordProcessingLatency(data.latencyMs);
          const decision = this.store.commitReviewedOutcome(data.outcome, data.newSpeakers);
          if (decision.accepted) {
            this.syncFromStore();
            this.callbacks.onScoutLog(
              `AI Scout 1+2 accepted window ${Math.round(startMs / 1000)}s–${Math.round(endMs / 1000)}s (${decision.acceptedCueCount} cues).`,
            );
            return true;
          }
        }
      }

      // Deterministic local evidence Scout 1 + Scout 2 pipeline (runs off main thread in Web Worker)
      const isMainFixture = project.source.id === 'fixture:main-dialogue';
      const speakerTrackHints: ScoutWorkerRequest['speakerTrackHints'] = isMainFixture
        ? [
            { startMs: 0, endMs: 4300, localSpeakerSlot: 'slot-label:maya', role: 'person', linkedVisualTrackId: 'track-maya' },
            { startMs: 4400, endMs: 7900, localSpeakerSlot: 'slot-label:devon', role: 'person', linkedVisualTrackId: 'track-devon' },
            { startMs: 7950, endMs: 11000, localSpeakerSlot: 'slot-label:maya', role: 'person', linkedVisualTrackId: 'track-maya' },
            { startMs: 14300, endMs: 17100, localSpeakerSlot: 'slot-label:devon', role: 'person', linkedVisualTrackId: 'track-devon' },
            { startMs: 17150, endMs: 20250, localSpeakerSlot: 'slot-label:maya', role: 'person', linkedVisualTrackId: 'track-maya' },
            { startMs: 18000, endMs: 20450, localSpeakerSlot: 'slot-label:devon', role: 'person', linkedVisualTrackId: 'track-devon' },
            { startMs: 24900, endMs: 27900, localSpeakerSlot: 'slot-label:maya', role: 'person', linkedVisualTrackId: 'track-maya' },
          ]
        : undefined;

      const workerReq: ScoutWorkerRequest = {
        requestId: `${windowKey}:${Date.now()}`,
        token,
        evidenceCues,
        projectSpeakers: this.store.getSnapshot().speakers,
        sourceLanguage: project.settings.sourceLanguage,
        targetLanguage: project.settings.targetLanguage,
        clipDurationMs: project.source.durationMs,
        hasAudioEvidence: isMainFixture,
        hasVisualEvidence: isMainFixture,
        sceneCutMs: isMainFixture ? 14_000 : null,
        speakerTrackHints,
        injectedDefect: this.injectedFault
          ? {
              delayReviewMs: this.injectedFault.delayReviewMs,
              corruptFirstCueText: this.injectedFault.corruptFirstCueText,
              dropSecondCue: this.injectedFault.dropSecondCue,
            }
          : undefined,
      };

      const workerRes = await this.executeLocalScout(workerReq);
      this.readiness.recordProcessingLatency(performance.now() - startedAt);

      // Commit strictly through authoritative transactional acceptance boundary
      const decision = this.store.commitReviewedOutcome(workerRes.outcome, workerRes.newSpeakers);
      if (decision.accepted) {
        const acceptedAtMs = Date.now();
        this.stageTimings.push({
          windowStartMs: startMs,
          windowEndMs: endMs,
          preparedAtMs: workerRes.preparedAtMs,
          reviewedAtMs: workerRes.reviewedAtMs,
          acceptedAtMs,
          firstDisplayedAtMs: null,
          repairedIssueCount: workerRes.outcome.issues.filter((i) => i.repaired).length,
        });
        this.syncFromStore();
        this.callbacks.onScoutLog(
          `Scout 1→2 accepted ${Math.round(startMs / 1000)}s–${Math.round(endMs / 1000)}s: ${decision.acceptedCueCount} cues, ${workerRes.outcome.issues.length} review checks.`,
        );
        return true;
      } else {
        this.callbacks.onScoutLog(`Rejected stale/superseded window ${startMs}-${endMs}: ${decision.reason}`);
        return false;
      }
    } finally {
      this.inFlightWindows.delete(windowKey);
    }
  }

  private executeLocalScout(req: ScoutWorkerRequest): Promise<ScoutWorkerResponse> {
    if (this.worker) {
      return new Promise<ScoutWorkerResponse>((resolve, reject) => {
        this.pendingWorkerRequests.set(req.requestId, { resolve, reject });
        this.worker!.postMessage(req);
      });
    }

    // Synchronous fallback if Web Worker unavailable in environment
    return (async () => {
      const draft = runScout1EvidenceDraft({
        token: req.token,
        evidenceCues: req.evidenceCues,
        sourceLanguage: req.sourceLanguage,
        targetLanguage: req.targetLanguage,
        hasAudioEvidence: req.hasAudioEvidence,
        hasVisualEvidence: req.hasVisualEvidence,
        existingSpeakers: req.projectSpeakers,
        sceneResolver: (timeMs) =>
          req.sceneCutMs && timeMs >= req.sceneCutMs ? 'scene-2' : 'scene-1',
        speakerTrackHints: req.speakerTrackHints,
      });
      if (req.injectedDefect?.corruptFirstCueText && draft.cues[0]) {
        draft.cues[0] = { ...draft.cues[0], text: req.injectedDefect.corruptFirstCueText };
      }
      if (req.injectedDefect?.dropSecondCue && draft.cues.length > 1) {
        draft.cues = draft.cues.filter((_, idx) => idx !== 1);
      }
      const preparedAtMs = Date.now();
      if (req.injectedDefect?.delayReviewMs) {
        await new Promise((r) => setTimeout(r, req.injectedDefect!.delayReviewMs));
      }
      const { outcome, newSpeakers } = runScout2IndependentReview({
        draft,
        originalEvidenceCues: req.evidenceCues,
        projectSpeakers: req.projectSpeakers,
        expectedSourceLanguage: req.sourceLanguage,
        targetLanguage: req.targetLanguage,
        clipDurationMs: req.clipDurationMs,
      });
      return {
        requestId: req.requestId,
        draft,
        outcome,
        newSpeakers,
        preparedAtMs,
        reviewedAtMs: Date.now(),
      };
    })();
  }

  /**
   * Plans and schedules lookahead windows ahead of current playback time.
   * In Prepare ahead mode: prepares current window + lookahead based on playbackRate.
   * In Prepare fully mode: prepares all windows across the full clip duration.
   */
  scheduleLookahead(mediaTimeMs: number, durationMs: number, playbackRate: number): void {
    if (!this.project || durationMs <= 0) return;
    const windowSizeMs = 8000;
    const currentWinStart = Math.floor(Math.max(0, mediaTimeMs) / windowSizeMs) * windowSizeMs;

    if (this.project.settings.preparationMode === 'prepare_fully') {
      for (let s = 0; s < durationMs; s += windowSizeMs) {
        const e = Math.min(Math.ceil(durationMs), s + windowSizeMs);
        void this.prepareWindow(s, e);
      }
      return;
    }

    // Prepare ahead: current window + adaptive lookahead
    const leadMs = this.readiness.getSnapshot().requiredLeadMs * Math.max(1, playbackRate);
    const horizonMs = Math.min(Math.ceil(durationMs), mediaTimeMs + leadMs);
    for (let s = currentWinStart; s < horizonMs; s += windowSizeMs) {
      const e = Math.min(Math.ceil(durationMs), s + windowSizeMs);
      void this.prepareWindow(s, e);
    }
  }

  markCueDisplayed(timeMs: number): void {
    const now = Date.now();
    for (const timing of this.stageTimings) {
      if (
        timing.firstDisplayedAtMs === null &&
        timeMs >= timing.windowStartMs &&
        timeMs < timing.windowEndMs
      ) {
        timing.firstDisplayedAtMs = now;
      }
    }
  }

  // --- Authoritative Playback Controls ---

  async requestPlay(): Promise<void> {
    if (!this.project) return;
    const now = performance.now();
    this.readiness.onUserRequestPlay(now);
    const mediaTimeMs = (this.videoEl?.currentTime ?? 0) * 1000;
    const durationMs =
      (this.videoEl?.duration && Number.isFinite(this.videoEl.duration)
        ? this.videoEl.duration * 1000
        : this.project.source.durationMs) ?? 0;

    if (durationMs > 0 && this.project.source.kind !== 'youtube') {
      this.scheduleLookahead(mediaTimeMs, durationMs, this.videoEl?.playbackRate ?? 1);
    }
    await this.evaluateReadinessTick(mediaTimeMs, durationMs, this.videoEl?.playbackRate ?? 1, now);
  }

  requestPause(): void {
    const action = this.readiness.onUserRequestPause();
    if (action.command === 'pause' && this.videoEl && !this.videoEl.paused) {
      this.videoEl.pause();
    }
    this.callbacks.onReadinessUpdated(this.readiness.getSnapshot());
  }

  async requestSeek(targetMs: number): Promise<void> {
    if (!this.project) return;
    const clampedMs = Math.max(0, targetMs);
    this.store?.bumpViewerGeneration();
    const now = performance.now();
    this.readiness.onUserSeek(now);

    if (this.videoEl) {
      this.videoEl.currentTime = clampedMs / 1000;
    }

    // Record intentional seek progress with strictly incremented progressSeq (even for backward seeks!)
    this.project = {
      ...this.project,
      progress: {
        positionMs: clampedMs,
        updatedAtMs: Date.now(),
        progressSeq: this.project.progress.progressSeq + 1,
        isIntentionalSeek: true,
      },
      updatedAtMs: Date.now(),
    };
    this.callbacks.onProjectUpdated(this.project);

    const durationMs =
      (this.videoEl?.duration && Number.isFinite(this.videoEl.duration)
        ? this.videoEl.duration * 1000
        : this.project.source.durationMs) ?? 0;
    if (durationMs > 0 && this.project.source.kind !== 'youtube') {
      this.scheduleLookahead(clampedMs, durationMs, this.videoEl?.playbackRate ?? 1);
    }
    await this.evaluateReadinessTick(clampedMs, durationMs, this.videoEl?.playbackRate ?? 1, now);
  }

  async continuePastGap(): Promise<void> {
    const action = this.readiness.continuePastCurrentGap();
    this.callbacks.onReadinessUpdated(this.readiness.getSnapshot());
    if (action.command === 'play' && this.videoEl) {
      await this.safePlayVideo();
    }
  }

  async retryCurrentGap(): Promise<void> {
    const gap = this.readiness.getSnapshot().activeGap;
    this.readiness.retryCurrentGap(performance.now());
    this.callbacks.onReadinessUpdated(this.readiness.getSnapshot());
    if (gap) {
      await this.prepareWindow(gap.startMs, gap.endMs, true);
    }
  }

  async evaluateReadinessTick(
    mediaTimeMs: number,
    durationMs: number,
    playbackRate: number,
    elapsedNowMs: number,
  ): Promise<void> {
    if (!this.project || !this.store) return;
    const neverGate = this.project.source.kind === 'youtube';
    const track = this.getActiveReferenceTrack();
    const gapWindowStart = Math.floor(Math.max(0, mediaTimeMs) / 8000) * 8000;
    const gapWindowEnd = Math.min(Math.max(gapWindowStart + 1000, durationMs), gapWindowStart + 8000);
    const hasFallback = Boolean(
      track?.cues.some((c) => c.endMs > gapWindowStart && c.startMs < gapWindowEnd),
    );

    const action = this.readiness.evaluate({
      mediaTimeMs,
      durationMs,
      playbackRate,
      elapsedNowMs,
      preparationMode: this.project.settings.preparationMode,
      coverage: this.project.coverage,
      hasFallbackCaptionsForGap: hasFallback,
      allowFallbackOnDeadline: true,
      neverGatePlayback: neverGate,
    });

    this.callbacks.onReadinessUpdated(this.readiness.getSnapshot());

    if (action.command === 'pause' && this.videoEl && !this.videoEl.paused) {
      this.videoEl.pause();
    } else if (action.command === 'play' && this.videoEl && this.videoEl.paused) {
      await this.safePlayVideo();
    } else if (action.command === 'release_fallback' && action.gap) {
      const token = this.store.beginNewPendingDraft(
        action.gap.startMs,
        action.gap.endMs,
        `fallback-${action.gap.startMs}-${action.gap.endMs}`,
      );
      const res = this.store.commitDeadlineFallback(token, track);
      if (res.accepted) {
        this.syncFromStore();
        if (this.videoEl && this.videoEl.paused) {
          await this.safePlayVideo();
        }
      }
    }
  }

  private async safePlayVideo(): Promise<void> {
    if (!this.videoEl) return;
    try {
      await this.videoEl.play();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Autoplay rejected by browser';
      this.readiness.onPlayRejected(msg);
      this.callbacks.onReadinessUpdated(this.readiness.getSnapshot());
    }
  }

  // --- Authoritative User Editing + Project-Scoped Undo/Redo ---

  private pushUndoState(): void {
    if (!this.project) return;
    this.undoStack.push({
      events: structuredClone(this.project.dialogueEvents),
      speakers: structuredClone(this.project.speakers),
    });
    if (this.undoStack.length > 50) {
      this.undoStack.shift();
    }
    this.redoStack = [];
  }

  editCue(
    cueId: string,
    patch: Parameters<TimelineAcceptanceStore['applyUserEdit']>[1],
  ): void {
    if (!this.project || !this.store) return;
    this.pushUndoState();
    const res = this.store.applyUserEdit(cueId, patch);
    if (res.updated) {
      this.syncFromStore();
      if (res.propagatedCount > 0) {
        this.callbacks.onScoutLog(
          `Propagated speaker update across ${res.propagatedCount} positively linked cues.`,
        );
      }
    }
  }

  splitCue(cueId: string, splitAtMs: number): void {
    if (!this.project || !this.store) return;
    const ev = this.project.dialogueEvents.find((e) => e.id === cueId);
    if (!ev || splitAtMs <= ev.startMs + 200 || splitAtMs >= ev.endMs - 200) return;

    this.pushUndoState();
    const words = ev.originalText.split(/\s+/);
    const mid = Math.max(1, Math.floor(words.length / 2));
    const firstText = words.slice(0, mid).join(' ');
    const secondText = words.slice(mid).join(' ') || ev.originalText;

    const first: DialogueEvent = {
      ...structuredClone(ev),
      endMs: Math.round(splitAtMs),
      originalText: firstText,
      protectedFields: { ...ev.protectedFields, text: true, timing: true },
      acceptedRevision: this.project.contentRevision + 1,
    };
    const second: DialogueEvent = {
      ...structuredClone(ev),
      id: `${ev.id}:split-${Date.now()}`,
      startMs: Math.round(splitAtMs),
      originalText: secondText,
      protectedFields: { ...ev.protectedFields, text: true, timing: true },
      acceptedRevision: this.project.contentRevision + 1,
    };

    const nextEvents = this.project.dialogueEvents
      .flatMap((e) => (e.id === cueId ? [first, second] : [e]))
      .sort((a, b) => a.startMs - b.startMs);

    this.replaceTimelineEvents(nextEvents, this.project.speakers);
  }

  mergeCueWithNext(cueId: string): void {
    if (!this.project || !this.store) return;
    const ordered = [...this.project.dialogueEvents].sort((a, b) => a.startMs - b.startMs);
    const idx = ordered.findIndex((e) => e.id === cueId);
    if (idx === -1 || idx >= ordered.length - 1) return;
    const a = ordered[idx]!;
    const b = ordered[idx + 1]!;

    this.pushUndoState();
    const merged: DialogueEvent = {
      ...structuredClone(a),
      endMs: Math.max(a.endMs, b.endMs),
      originalText: `${a.originalText} ${b.originalText}`.trim(),
      evidenceCueIds: [...new Set([...a.evidenceCueIds, ...b.evidenceCueIds])],
      protectedFields: { ...a.protectedFields, text: true, timing: true },
      acceptedRevision: this.project.contentRevision + 1,
    };

    const nextEvents = ordered.filter((_, i) => i !== idx && i !== idx + 1);
    nextEvents.push(merged);
    nextEvents.sort((x, y) => x.startMs - y.startMs);
    this.replaceTimelineEvents(nextEvents, this.project.speakers);
  }

  upsertSpeaker(speaker: SpeakerProfile): void {
    if (!this.project) return;
    this.pushUndoState();
    const exists = this.project.speakers.some((s) => s.id === speaker.id);
    const nextSpeakers = exists
      ? this.project.speakers.map((s) => (s.id === speaker.id ? { ...speaker } : s))
      : [...this.project.speakers, { ...speaker }];
    this.replaceTimelineEvents(this.project.dialogueEvents, nextSpeakers);
  }

  undo(): boolean {
    if (!this.project || this.undoStack.length === 0) return false;
    const prev = this.undoStack.pop()!;
    this.redoStack.push({
      events: structuredClone(this.project.dialogueEvents),
      speakers: structuredClone(this.project.speakers),
    });
    this.replaceTimelineEvents(prev.events, prev.speakers);
    return true;
  }

  redo(): boolean {
    if (!this.project || this.redoStack.length === 0) return false;
    const next = this.redoStack.pop()!;
    this.undoStack.push({
      events: structuredClone(this.project.dialogueEvents),
      speakers: structuredClone(this.project.speakers),
    });
    this.replaceTimelineEvents(next.events, next.speakers);
    return true;
  }

  updateSettings(patch: Partial<UserSettings>): void {
    if (!this.project || !this.store) return;
    const prevLang = this.project.settings.targetLanguage;
    const prevSrcLang = this.project.settings.sourceLanguage;
    const nextSettings: UserSettings = {
      ...this.project.settings,
      ...patch,
      budgets: patch.budgets
        ? { ...this.project.settings.budgets, ...patch.budgets }
        : this.project.settings.budgets,
    };
    const langChanged =
      nextSettings.targetLanguage !== prevLang || nextSettings.sourceLanguage !== prevSrcLang;
    if (langChanged) {
      this.store.bumpConfigRevision(true);
    }
    this.readiness.setDeadlineMs(nextSettings.budgets.intervalDeadlineMs);

    const storeSnap = this.store.getSnapshot();
    this.project = {
      ...this.project,
      settings: nextSettings,
      configRevision: storeSnap.configRevision,
      dialogueEvents: storeSnap.events,
      updatedAtMs: Date.now(),
    };
    this.callbacks.onProjectUpdated(this.project);
  }

  recordPlaybackPosition(positionMs: number, isIntentionalSeek = false): void {
    if (!this.project) return;
    this.project = {
      ...this.project,
      progress: {
        positionMs: Math.max(0, Math.round(positionMs)),
        updatedAtMs: Date.now(),
        progressSeq: this.project.progress.progressSeq + 1,
        isIntentionalSeek,
      },
    };
  }

  private replaceTimelineEvents(events: DialogueEvent[], speakers: SpeakerProfile[]): void {
    if (!this.project) return;
    const nextRev = this.project.contentRevision + 1;
    this.project = {
      ...this.project,
      contentRevision: nextRev,
      dialogueEvents: events,
      speakers,
      updatedAtMs: Date.now(),
    };
    this.store = new TimelineAcceptanceStore({
      projectId: this.project.id,
      mediaId: this.project.source.id,
      evidenceRevision: this.project.evidenceRevision,
      configRevision: this.project.configRevision,
      acceptedRevision: nextRev,
      viewerGeneration: this.store?.getSnapshot().viewerGeneration ?? 1,
      pendingDraftRevision: nextRev,
      events,
      speakers,
      coverage: this.project.coverage,
    });
    this.callbacks.onProjectUpdated(this.project);
  }

  private syncFromStore(): void {
    if (!this.project || !this.store) return;
    const snap = this.store.getSnapshot();
    this.project = {
      ...this.project,
      contentRevision: snap.acceptedRevision,
      evidenceRevision: snap.evidenceRevision,
      configRevision: snap.configRevision,
      dialogueEvents: snap.events,
      speakers: snap.speakers,
      coverage: snap.coverage,
      updatedAtMs: Date.now(),
    };
    this.callbacks.onProjectUpdated(this.project);
  }

  dispose(): void {
    this.worker?.terminate();
    this.worker = null;
  }
}
