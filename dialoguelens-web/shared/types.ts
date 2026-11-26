export type SourceKind = 'local' | 'direct_url' | 'youtube';

export type CapabilityState =
  | 'supported'
  | 'loading'
  | 'unavailable'
  | 'inaccessible'
  | 'blocked'
  | 'absent'
  | 'temporarily_inactive'
  | 'unobservable';

export interface SourceCapabilities {
  playback: CapabilityState;
  captionActivation: CapabilityState;
  readableCaptionEvidence: CapabilityState;
  audioExtraction: CapabilityState;
  frameInspection: CapabilityState;
  seeking: CapabilityState;
  permittedOverlayPlacement: CapabilityState;
  notes: string[];
}

export interface MediaSourceDescriptor {
  id: string;
  kind: SourceKind;
  label: string;
  url?: string;
  youtubeVideoId?: string;
  fileName?: string;
  fileSize?: number;
  fileLastModified?: number;
  mimeType?: string;
  durationMs: number | null;
  naturalWidth?: number;
  naturalHeight?: number;
  capabilities: SourceCapabilities;
  requiresRelink?: boolean;
}

export type EvidenceOrigin =
  | 'imported_srt'
  | 'imported_vtt'
  | 'html_text_track_authored'
  | 'html_text_track_auto'
  | 'html_text_track_unknown'
  | 'ai_audio_transcript'
  | 'manual_user';

export interface EvidenceCue {
  id: string;
  trackId: string;
  mediaId: string;
  startMs: number;
  endMs: number;
  text: string;
  language: string;
  origin: EvidenceOrigin;
  isAutomatic: boolean | null;
  sourceRevision: number;
  translationOfCueId?: string;
  sourceLanguage?: string;
  observedVisibly?: boolean;
  sequence: number;
}

export interface ReferenceTrack {
  id: string;
  mediaId: string;
  label: string;
  language: string;
  origin: EvidenceOrigin;
  isAutomatic: boolean | null;
  sourceRevision: number;
  cues: EvidenceCue[];
  measuredCoverageMs: Array<{ startMs: number; endMs: number }>;
  visibleObservationConfirmed: boolean;
}

export type SpeakerRole = 'person' | 'narrator' | 'offscreen' | 'unknown';

export interface SpeakerProfile {
  id: string;
  projectId: string;
  label: string;
  color: string;
  role: SpeakerRole;
  isUserNamed: boolean;
  /** Positive visual/voice continuity anchors that belong to this project-local speaker. */
  linkedTrackIds: string[];
}

export type DimensionStatus =
  | 'supported'
  | 'needs_review'
  | 'unverified'
  | 'conflict'
  | 'unavailable'
  | 'user_authored';

export interface CueQualityDimensions {
  referenceAlignment: DimensionStatus;
  audioSupport: DimensionStatus;
  translationReview: DimensionStatus;
  attribution: DimensionStatus;
  presentation: DimensionStatus;
}

export interface ProtectedFields {
  text: boolean;
  timing: boolean;
  speaker: boolean;
  anchor: boolean;
}

export interface NormalizedPoint {
  /** Normalized [0..1] in source video content coordinates. */
  x: number;
  /** Normalized [0..1] in source video content coordinates. */
  y: number;
}

export type BubbleStyleVariant = 'speech' | 'shout' | 'narration';

export interface DialogueEvent {
  id: string;
  projectId: string;
  mediaId: string;
  startMs: number;
  endMs: number;
  /** Immutable original-language text captured from source or authored by user. */
  originalText: string;
  /** Display text in target language (or original if same language / untranslated). */
  translatedText?: string;
  sourceLanguage: string;
  targetLanguage: string;
  translationStatus: 'not_needed' | 'translated' | 'missing_translation' | 'stale';
  speakerId: string;
  speakerRole: SpeakerRole;
  /** Positive linked visual track id if supported; never inferred by nearest face alone. */
  linkedVisualTrackId?: string;
  styleVariant: BubbleStyleVariant;
  sceneId: string;
  evidenceCueIds: string[];
  evidenceRevision: number;
  acceptedRevision: number;
  quality: CueQualityDimensions;
  protectedFields: ProtectedFields;
  /** Optional manual body anchor override in normalized content coordinates. */
  manualAnchor?: NormalizedPoint;
  uncertaintyNotes: string[];
  conflictHints: RepairConflictHint[];
}

export interface RepairConflictHint {
  id: string;
  field: keyof ProtectedFields;
  proposedValue: string;
  reason: string;
  scoutRevision: number;
}

export interface TaskOwnershipToken {
  projectId: string;
  mediaId: string;
  evidenceRevision: number;
  configRevision: number;
  taskId: string;
  intervalStartMs: number;
  intervalEndMs: number;
  draftRevision: number;
  attempt: number;
  viewerGeneration: number;
}

export interface DraftCueCandidate {
  id: string;
  startMs: number;
  endMs: number;
  text: string;
  translatedText?: string;
  sourceLanguage: string;
  targetLanguage: string;
  /** Model-local speaker slot before continuity linking. */
  localSpeakerSlot: string;
  speakerRole: SpeakerRole;
  linkedVisualTrackId?: string;
  styleVariant: BubbleStyleVariant;
  sceneId: string;
  evidenceCueIds: string[];
  uncertaintyNotes: string[];
}

export interface DraftInterval {
  token: TaskOwnershipToken;
  createdAtMs: number;
  producer: 'local_evidence' | 'gemini_scout1' | 'test_double';
  cues: DraftCueCandidate[];
  hasAudioEvidence: boolean;
  hasVisualEvidence: boolean;
  confirmedSilence: boolean;
  notes: string[];
}

export interface ReviewIssue {
  cueId?: string;
  code:
    | 'missing_dialogue'
    | 'unsupported_generated_line'
    | 'wrong_language'
    | 'negation_flip'
    | 'quantity_mismatch'
    | 'entity_mismatch'
    | 'repetition_dropped'
    | 'same_time_correction'
    | 'missing_translation'
    | 'timing_out_of_bounds'
    | 'unsupported_speaker_claim'
    | 'layout_overflow';
  severity: 'error' | 'warning';
  message: string;
  repaired: boolean;
}

export interface ReviewOutcome {
  token: TaskOwnershipToken;
  reviewedAtMs: number;
  reviewer: 'local_deterministic' | 'gemini_scout2' | 'test_double';
  draftRevisionReviewed: number;
  acceptedCues: DialogueEvent[];
  failedCueIds: string[];
  issues: ReviewIssue[];
  intervalStatus: 'reviewed_complete' | 'reviewed_partial' | 'fallback_evidence' | 'failed';
  confirmedSilence: boolean;
  notes: string[];
}

export type CoverageStatus =
  | 'unprepared'
  | 'drafting'
  | 'reviewing'
  | 'accepted'
  | 'partial'
  | 'fallback_captions'
  | 'confirmed_silence'
  | 'unavailable'
  | 'failed';

export interface CoverageInterval {
  startMs: number;
  endMs: number;
  status: CoverageStatus;
  evidenceRevision: number;
  acceptedRevision: number;
  failedCueIds: string[];
  attempts: number;
  lastError?: string;
  updatedAtMs: number;
}

export interface FaceObservation {
  /** Distinct sequential capture counter; never re-incremented by duplicate layout frames. */
  observationSeq: number;
  captureTimeMs: number;
  mediaTimeMs: number;
  sceneId: string;
  trackId: string;
  /** Bounding box in normalized video content coordinates [0..1]. */
  bbox: { x: number; y: number; width: number; height: number };
  /** Mouth target in normalized video content coordinates [0..1]. */
  mouth: NormalizedPoint;
  /** Speaking evidence score from lip motion / diarization [0..1]. */
  speakingEvidence: number;
  /** Confidence of detector landmarking [0..1]. */
  confidence: number;
  occluded?: boolean;
  source: 'mediapipe_live' | 'fixture_reference' | 'manual_anchor';
}

export interface ContentViewportRect {
  stageWidth: number;
  stageHeight: number;
  contentX: number;
  contentY: number;
  contentWidth: number;
  contentHeight: number;
  scaleX: number;
  scaleY: number;
  measurementSeq: number;
}

export interface CommittedBubbleBody {
  cueId: string;
  speakerId: string;
  sceneId: string;
  /** Committed center in stage CSS px. */
  centerX: number;
  centerY: number;
  /** Measured DOM box dimensions in stage CSS px. */
  width: number;
  height: number;
  /** Top-left derived from center and measured bounds, clamped to safe stage area. */
  left: number;
  top: number;
  borderRadius: number;
  styleVariant: BubbleStyleVariant;
  isManualAnchor: boolean;
  entryCount: number;
  committedAtMs: number;
  textHash: string;
  viewportSeq: number;
}

export interface TailGeometry {
  cueId: string;
  visible: boolean;
  reason?: string;
  /** Root anchor on the rendered bubble contour (stage CSS px). */
  rootX: number;
  rootY: number;
  /** Base triangle vertices on the contour. */
  baseLeftX: number;
  baseLeftY: number;
  baseRightX: number;
  baseRightY: number;
  /** Tip near speaker mouth with clearance (stage CSS px). */
  tipX: number;
  tipY: number;
  /** Continuous smoothed angle in radians (unwrapped across ±π). */
  angleRad: number;
  /** Associated visual track id when visible. */
  trackId?: string;
  lastObservationSeq: number;
  lastUpdateMonotonicMs: number;
}

export type DisplayMode = 'bubbles' | 'tailless';
export type TextDisplayMode = 'original' | 'translated' | 'bilingual';
export type PreparationMode = 'prepare_ahead' | 'prepare_fully';
export type MotionPreference = 'system' | 'normal' | 'reduced';

export interface GlossaryEntry {
  sourceTerm: string;
  preferredTranslation: string;
  notes?: string;
}

export interface BudgetSettings {
  maxRequestsPerSession: number;
  maxAttemptsPerInterval: number;
  intervalDeadlineMs: number;
}

export interface UserSettings {
  displayMode: DisplayMode;
  textDisplayMode: TextDisplayMode;
  sourceLanguage: string;
  targetLanguage: string;
  fontSizeScale: number;
  highContrast: boolean;
  bubbleSize: 'compact' | 'medium' | 'large';
  motionPreference: MotionPreference;
  freeCcOnly: boolean;
  preparationMode: PreparationMode;
  uploadConsentGranted: boolean;
  glossary: GlossaryEntry[];
  budgets: BudgetSettings;
}

export type FreeModeAckStatus =
  | 'confirmed_free'
  | 'stopping_unconfirmed'
  | 'ai_enabled'
  | 'credentials_unconfigured';

export interface BackendPolicyState {
  policyRevision: number;
  freeCcOnly: boolean;
  credentialsConfigured: boolean;
  providerName: string;
  scout1Model: string;
  scout2Model: string;
  activeJobCount: number;
  cancellingJobCount: number;
  sessionRequestsUsed: number;
  sessionRequestBudget: number;
  circuitOpen: boolean;
  circuitReason?: string;
}

export interface PlaybackProgressState {
  positionMs: number;
  updatedAtMs: number;
  /** Monotonically incremented on explicit user seeks or playback progress commits. */
  progressSeq: number;
  isIntentionalSeek: boolean;
}

export interface ProjectSnapshot {
  schemaVersion: number;
  id: string;
  title: string;
  createdAtMs: number;
  updatedAtMs: number;
  contentRevision: number;
  evidenceRevision: number;
  configRevision: number;
  source: MediaSourceDescriptor;
  referenceTracks: ReferenceTrack[];
  activeReferenceTrackId: string | null;
  speakers: SpeakerProfile[];
  dialogueEvents: DialogueEvent[];
  coverage: CoverageInterval[];
  settings: UserSettings;
  progress: PlaybackProgressState;
}

export interface ExportedProjectBundle {
  format: 'dialoguelens-web-project';
  schemaVersion: number;
  exportedAtIso: string;
  companionFilesNotice: string;
  project: ProjectSnapshot;
}

export interface AnalysisJobRecord {
  id: string;
  projectId: string;
  ownerTokenHash: string;
  status:
    | 'queued'
    | 'extracting'
    | 'running'
    | 'completed'
    | 'partial'
    | 'cancelling'
    | 'cancelled'
    | 'failed';
  policyRevisionAtCreation: number;
  uploadId: string | null;
  sourceLabel: string;
  durationMs: number;
  chunkDurationMs: number;
  totalChunks: number;
  completedChunks: number;
  failedChunks: number;
  attempts: number;
  maxAttempts: number;
  leaseOwner: string | null;
  leaseExpiresAtMs: number | null;
  errorMessage: string | null;
  createdAtMs: number;
  updatedAtMs: number;
}

export interface JobChunkRecord {
  id: string;
  jobId: string;
  chunkIndex: number;
  startMs: number;
  endMs: number;
  sampleRateHz: number | null;
  channels: number | null;
  status: 'pending' | 'running' | 'completed' | 'partial' | 'failed' | 'cancelled';
  attempts: number;
  resultJson: string | null;
  errorMessage: string | null;
  updatedAtMs: number;
}

export const CURRENT_PROJECT_SCHEMA_VERSION = 2;

export const DEFAULT_USER_SETTINGS: UserSettings = {
  displayMode: 'bubbles',
  textDisplayMode: 'original',
  sourceLanguage: 'en',
  targetLanguage: 'en',
  fontSizeScale: 1,
  highContrast: false,
  bubbleSize: 'medium',
  motionPreference: 'system',
  freeCcOnly: true,
  preparationMode: 'prepare_ahead',
  uploadConsentGranted: false,
  glossary: [],
  budgets: {
    maxRequestsPerSession: 40,
    maxAttemptsPerInterval: 3,
    intervalDeadlineMs: 8000,
  },
};

export const SPEAKER_PALETTE = [
  '#2563EB',
  '#D97706',
  '#059669',
  '#9333EA',
  '#DC2626',
  '#0891B2',
  '#4F46E5',
  '#B45309',
] as const;

export function createDefaultSpeakers(projectId: string): SpeakerProfile[] {
  return [
    {
      id: `${projectId}:spk-unknown`,
      projectId,
      label: 'Unknown speaker',
      color: '#475569',
      role: 'unknown',
      isUserNamed: false,
      linkedTrackIds: [],
    },
    {
      id: `${projectId}:spk-narrator`,
      projectId,
      label: 'Narrator',
      color: '#334155',
      role: 'narrator',
      isUserNamed: false,
      linkedTrackIds: [],
    },
    {
      id: `${projectId}:spk-offscreen`,
      projectId,
      label: 'Off-screen',
      color: '#64748B',
      role: 'offscreen',
      isUserNamed: false,
      linkedTrackIds: [],
    },
  ];
}
