/// <reference lib="webworker" />
import {
  runScout1EvidenceDraft,
  runScout2IndependentReview,
} from '../../shared/scoutPipeline.js';
import type {
  DraftInterval,
  EvidenceCue,
  ReviewOutcome,
  SpeakerProfile,
  SpeakerRole,
  TaskOwnershipToken,
} from '../../shared/types.js';

export interface ScoutWorkerRequest {
  requestId: string;
  token: TaskOwnershipToken;
  evidenceCues: EvidenceCue[];
  projectSpeakers: SpeakerProfile[];
  sourceLanguage: string;
  targetLanguage: string;
  clipDurationMs: number | null;
  hasAudioEvidence: boolean;
  hasVisualEvidence: boolean;
  sceneCutMs?: number | null;
  speakerTrackHints?: Array<{
    startMs: number;
    endMs: number;
    localSpeakerSlot: string;
    role: SpeakerRole;
    linkedVisualTrackId?: string;
  }>;
  /** Optional injected draft defects strictly for automated E2E/regression scenarios. */
  injectedDefect?: {
    delayReviewMs?: number;
    corruptFirstCueText?: string;
    dropSecondCue?: boolean;
  };
}

export interface ScoutWorkerResponse {
  requestId: string;
  draft: DraftInterval;
  outcome: ReviewOutcome;
  newSpeakers: SpeakerProfile[];
  preparedAtMs: number;
  reviewedAtMs: number;
}

self.onmessage = async (event: MessageEvent<ScoutWorkerRequest>) => {
  const req = event.data;
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

  // Apply explicit test-injected boundary defects if provided (never used in normal playback)
  if (req.injectedDefect?.corruptFirstCueText && draft.cues[0]) {
    draft.cues[0] = { ...draft.cues[0], text: req.injectedDefect.corruptFirstCueText };
  }
  if (req.injectedDefect?.dropSecondCue && draft.cues.length > 1) {
    draft.cues = draft.cues.filter((_, idx) => idx !== 1);
  }
  const preparedAtMs = Date.now();

  if (req.injectedDefect?.delayReviewMs && req.injectedDefect.delayReviewMs > 0) {
    await new Promise((resolve) => setTimeout(resolve, req.injectedDefect?.delayReviewMs));
  }

  const { outcome, newSpeakers } = runScout2IndependentReview({
    draft,
    originalEvidenceCues: req.evidenceCues,
    projectSpeakers: req.projectSpeakers,
    expectedSourceLanguage: req.sourceLanguage,
    targetLanguage: req.targetLanguage,
    clipDurationMs: req.clipDurationMs,
  });

  const response: ScoutWorkerResponse = {
    requestId: req.requestId,
    draft,
    outcome,
    newSpeakers,
    preparedAtMs,
    reviewedAtMs: Date.now(),
  };
  self.postMessage(response);
};
