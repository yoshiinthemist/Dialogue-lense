import { describe, expect, it } from 'vitest';
import {
  parseSubtitleContent,
  exportTimelineToWebVtt,
  exportTimelineToSrt,
} from '../../shared/subtitleParser.js';
import {
  TimelineAcceptanceStore,
  runScout1EvidenceDraft,
  runScout2IndependentReview,
} from '../../shared/scoutPipeline.js';
import {
  CURRENT_PROJECT_SCHEMA_VERSION,
  DEFAULT_USER_SETTINGS,
  createDefaultSpeakers,
  type ProjectSnapshot,
} from '../../shared/types.js';
import {
  buildExportedProjectBundle,
  mergeProjectSnapshots,
  migrateProjectSnapshot,
  parseImportedProjectBundle,
  verifyRelinkedFileCompatibility,
} from '../../src/storage/projectDb.js';

describe('Subtitle Parser & Evidence Integrity (Scenario 3)', () => {
  it('handles rolling captions, preserves legitimate repetition, and applies same-time corrections', () => {
    const vtt = `WEBVTT

00:00:01.000 --> 00:00:03.000
Hello there
How are you?

00:00:03.020 --> 00:00:05.000
How are you?
I am fine.

00:00:06.000 --> 00:00:07.000
Wait.

00:00:07.500 --> 00:00:08.500
Wait.

00:00:10.000 --> 00:00:12.000
The door is open.

00:00:10.000 --> 00:00:12.000
The door is locked!
`;

    const parsed = parseSubtitleContent(vtt, {
      mediaId: 'm1',
      trackId: 't1',
      label: 'Test VTT',
      language: 'en',
      origin: 'imported_vtt',
      isAutomatic: false,
      clipDurationMs: 20_000,
    });

    const texts = parsed.track.cues.map((c) => c.text);
    // Rolling prefix trimmed from cue 2
    expect(texts[1]).toBe('I am fine.');
    // Legitimate repetition preserved
    expect(texts.filter((t) => t === 'Wait.')).toHaveLength(2);
    // Same-time correction replaced earlier version cleanly
    expect(texts).toContain('The door is locked!');
    expect(texts).not.toContain('The door is open.');
  });

  it('Scout 2 catches wrong language, negation flips, quantity drift, dropped dialogue, and keeps quality dimensions separate', () => {
    const vtt = `WEBVTT

00:00:01.000 --> 00:00:04.000
Maya: Do not open the vault before 3 minutes have passed.

00:00:05.000 --> 00:00:07.000
Devon: Bring the spare key from console 2 right now.
`;
    const parsed = parseSubtitleContent(vtt, {
      mediaId: 'media-1',
      trackId: 'ref-1',
      label: 'Ref',
      language: 'en',
      origin: 'imported_vtt',
      isAutomatic: false,
      clipDurationMs: 15_000,
    });

    const token = {
      projectId: 'p1',
      mediaId: 'media-1',
      evidenceRevision: 1,
      configRevision: 1,
      taskId: 'task-1',
      intervalStartMs: 0,
      intervalEndMs: 8000,
      draftRevision: 1,
      attempt: 1,
      viewerGeneration: 1,
    };

    // Scout 1 drafts with injected defects: flipped negation + wrong quantity + dropped cue 2 + hallucinated cue 3
    const draft = runScout1EvidenceDraft({
      token,
      evidenceCues: parsed.track.cues,
      sourceLanguage: 'en',
      targetLanguage: 'es',
      hasAudioEvidence: false,
      hasVisualEvidence: false,
      existingSpeakers: createDefaultSpeakers('p1'),
    });

    draft.cues = [
      {
        ...draft.cues[0]!,
        text: 'Open the vault before 30 minutes have passed.', // flipped negation & quantity!
      },
      {
        id: 'hallucinated-cue',
        startMs: 7100,
        endMs: 7900,
        text: 'Hallucinated unsupported line with no audio or caption evidence.',
        sourceLanguage: 'en',
        targetLanguage: 'es',
        localSpeakerSlot: 'slot-unknown',
        speakerRole: 'unknown',
        styleVariant: 'speech',
        sceneId: 'scene-1',
        evidenceCueIds: [],
        uncertaintyNotes: [],
      },
    ];

    const { outcome } = runScout2IndependentReview({
      draft,
      originalEvidenceCues: parsed.track.cues,
      projectSpeakers: createDefaultSpeakers('p1'),
      expectedSourceLanguage: 'en',
      targetLanguage: 'es',
      clipDurationMs: 15_000,
    });

    // Repaired cue 1 restored exact negation & quantity
    const cue1 = outcome.acceptedCues.find((c) => c.startMs === 1000);
    expect(cue1?.originalText).toBe('Do not open the vault before 3 minutes have passed.');
    // Missing cue 2 was recovered from reference evidence!
    const cue2 = outcome.acceptedCues.find((c) => c.startMs === 5000);
    expect(cue2?.originalText).toBe('Bring the spare key from console 2 right now.');
    // Hallucinated line rejected
    expect(outcome.acceptedCues.some((c) => c.id === 'hallucinated-cue')).toBe(false);
    // Reference alignment CANNOT certify audio, translation, or attribution!
    expect(cue1?.quality.referenceAlignment).toBe('supported');
    expect(cue1?.quality.audioSupport).toBe('unavailable');
    expect(cue1?.quality.translationReview).toBe('unverified');
    expect(cue1?.translationStatus).toBe('missing_translation');
    expect(outcome.confirmedSilence).toBe(false);
  });

  it('never certifies silence when Scout 1 reports silence but Scout 2 recovers dialogue from reference evidence', () => {
    const parsed = parseSubtitleContent(
      `WEBVTT\n\n00:00:02.000 --> 00:00:04.000\nHidden spoken line recovered by Scout 2.\n`,
      {
        mediaId: 'm1',
        trackId: 't1',
        label: 'Ref',
        language: 'en',
        origin: 'imported_vtt',
      },
    );
    const token = {
      projectId: 'p1',
      mediaId: 'm1',
      evidenceRevision: 1,
      configRevision: 1,
      taskId: 'silent-recovery',
      intervalStartMs: 0,
      intervalEndMs: 8000,
      draftRevision: 1,
      attempt: 1,
      viewerGeneration: 1,
    };

    const silentDraft = {
      token,
      createdAtMs: Date.now(),
      producer: 'local_evidence' as const,
      cues: [],
      hasAudioEvidence: true,
      hasVisualEvidence: false,
      confirmedSilence: true,
      notes: ['Incorrectly classified as silent'],
    };

    const { outcome } = runScout2IndependentReview({
      draft: silentDraft,
      originalEvidenceCues: parsed.track.cues,
      projectSpeakers: createDefaultSpeakers('p1'),
      expectedSourceLanguage: 'en',
      targetLanguage: 'en',
      clipDurationMs: 10_000,
    });

    expect(outcome.acceptedCues).toHaveLength(1);
    expect(outcome.confirmedSilence).toBe(false);
    expect(outcome.issues.some((i) => i.code === 'missing_dialogue')).toBe(true);
  });
});

describe('Ownership, Staleness, Protected Edits & Positive-Link Propagation (Scenarios 5 & 12)', () => {
  it('rejects stale revisions against pending & accepted revisions and protects user edits during repair', () => {
    const speakers = createDefaultSpeakers('p1');
    const store = new TimelineAcceptanceStore({
      projectId: 'p1',
      mediaId: 'm1',
      evidenceRevision: 1,
      configRevision: 1,
      acceptedRevision: 0,
      viewerGeneration: 1,
      pendingDraftRevision: 0,
      events: [],
      speakers,
      coverage: [],
    });

    const staleToken = store.beginNewPendingDraft(0, 8000, 'task-old', 1);
    const newerToken = store.beginNewPendingDraft(0, 8000, 'task-new', 1);

    const parsed = parseSubtitleContent(
      `WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nMaya: Original line.\n`,
      { mediaId: 'm1', trackId: 't1', label: 'Ref', language: 'en', origin: 'imported_vtt' },
    );

    const staleDraft = runScout1EvidenceDraft({
      token: staleToken,
      evidenceCues: parsed.track.cues,
      sourceLanguage: 'en',
      targetLanguage: 'en',
      hasAudioEvidence: false,
      hasVisualEvidence: false,
      existingSpeakers: speakers,
    });
    const staleReview = runScout2IndependentReview({
      draft: staleDraft,
      originalEvidenceCues: parsed.track.cues,
      projectSpeakers: speakers,
      expectedSourceLanguage: 'en',
      targetLanguage: 'en',
      clipDurationMs: 10_000,
    });

    // Stale token rejected relative to newer pending revision!
    const staleDecision = store.commitReviewedOutcome(staleReview.outcome, staleReview.newSpeakers);
    expect(staleDecision.accepted).toBe(false);

    // Newer token commits cleanly
    const newerDraft = runScout1EvidenceDraft({
      token: newerToken,
      evidenceCues: parsed.track.cues,
      sourceLanguage: 'en',
      targetLanguage: 'en',
      hasAudioEvidence: false,
      hasVisualEvidence: false,
      existingSpeakers: speakers,
    });
    const newerReview = runScout2IndependentReview({
      draft: newerDraft,
      originalEvidenceCues: parsed.track.cues,
      projectSpeakers: speakers,
      expectedSourceLanguage: 'en',
      targetLanguage: 'en',
      clipDurationMs: 10_000,
    });
    const okDecision = store.commitReviewedOutcome(newerReview.outcome, newerReview.newSpeakers);
    expect(okDecision.accepted).toBe(true);

    // User edits text & pins manual anchor
    const cueId = store.getSnapshot().events[0]!.id;
    store.applyUserEdit(cueId, {
      originalText: 'User curated line.',
      manualAnchor: { x: 0.35, y: 0.25 },
    });

    // Later Scout 2 repair cannot overwrite protected text or manual anchor, but records a conflict hint!
    const repairToken = store.beginNewPendingDraft(0, 8000, 'task-repair', 2);
    const repairDraft = runScout1EvidenceDraft({
      token: repairToken,
      evidenceCues: parsed.track.cues,
      sourceLanguage: 'en',
      targetLanguage: 'en',
      hasAudioEvidence: false,
      hasVisualEvidence: false,
      existingSpeakers: store.getSnapshot().speakers,
    });
    const repairReview = runScout2IndependentReview({
      draft: repairDraft,
      originalEvidenceCues: parsed.track.cues,
      projectSpeakers: store.getSnapshot().speakers,
      expectedSourceLanguage: 'en',
      targetLanguage: 'en',
      clipDurationMs: 10_000,
    });
    const repairDecision = store.commitReviewedOutcome(repairReview.outcome, repairReview.newSpeakers);
    expect(repairDecision.accepted).toBe(true);
    expect(repairDecision.conflictsRecorded).toBeGreaterThanOrEqual(1);

    const updatedCue = store.getSnapshot().events[0]!;
    expect(updatedCue.originalText).toBe('User curated line.');
    expect(updatedCue.manualAnchor).toEqual({ x: 0.35, y: 0.25 });
    expect(updatedCue.conflictHints.length).toBeGreaterThanOrEqual(1);
  });

  it('never propagates speaker corrections between two unknown speakers, only across positive linked tracks', () => {
    const speakers = [
      ...createDefaultSpeakers('p1'),
      {
        id: 'p1:spk-maya',
        projectId: 'p1',
        label: 'Maya',
        color: '#2563EB',
        role: 'person' as const,
        isUserNamed: true,
        linkedTrackIds: ['track-maya'],
      },
    ];

    const baseCue = {
      projectId: 'p1',
      mediaId: 'm1',
      sourceLanguage: 'en',
      targetLanguage: 'en',
      translationStatus: 'not_needed' as const,
      styleVariant: 'speech' as const,
      sceneId: 'scene-1',
      evidenceCueIds: [],
      evidenceRevision: 1,
      acceptedRevision: 1,
      quality: {
        referenceAlignment: 'supported' as const,
        audioSupport: 'unavailable' as const,
        translationReview: 'supported' as const,
        attribution: 'unverified' as const,
        presentation: 'needs_review' as const,
      },
      protectedFields: { text: false, timing: false, speaker: false, anchor: false },
      uncertaintyNotes: [],
      conflictHints: [],
    };

    const store = new TimelineAcceptanceStore({
      projectId: 'p1',
      mediaId: 'm1',
      evidenceRevision: 1,
      configRevision: 1,
      acceptedRevision: 1,
      viewerGeneration: 1,
      pendingDraftRevision: 1,
      speakers,
      coverage: [],
      events: [
        { ...baseCue, id: 'unk-1', startMs: 1000, endMs: 2000, originalText: 'Unknown 1', speakerId: 'p1:spk-unknown', speakerRole: 'unknown' },
        { ...baseCue, id: 'unk-2', startMs: 2500, endMs: 3500, originalText: 'Unknown 2', speakerId: 'p1:spk-unknown', speakerRole: 'unknown' },
        { ...baseCue, id: 'trk-1', startMs: 4000, endMs: 5000, originalText: 'Linked 1', speakerId: 'p1:spk-unknown', speakerRole: 'person', linkedVisualTrackId: 'track-maya' },
        { ...baseCue, id: 'trk-2', startMs: 5500, endMs: 6500, originalText: 'Linked 2', speakerId: 'p1:spk-unknown', speakerRole: 'person', linkedVisualTrackId: 'track-maya' },
      ],
    });

    // Editing an unknown cue with propagate=true MUST NOT propagate to other unknown cues
    const resUnknown = store.applyUserEdit('unk-1', {
      speakerId: 'p1:spk-maya',
      propagateSpeakerByPositiveLink: true,
    });
    expect(resUnknown.propagatedCount).toBe(0);
    expect(store.getSnapshot().events.find((e) => e.id === 'unk-2')?.speakerId).toBe('p1:spk-unknown');

    // Editing a positively linked track cue DOES propagate to its positively linked sibling
    const resLinked = store.applyUserEdit('trk-1', {
      speakerId: 'p1:spk-maya',
      propagateSpeakerByPositiveLink: true,
    });
    expect(resLinked.propagatedCount).toBe(1);
    expect(store.getSnapshot().events.find((e) => e.id === 'trk-2')?.speakerId).toBe('p1:spk-maya');
  });

  it('merges concurrent progress/content saves preserving intentional backward seeks and round-trips exports safely', () => {
    const base: ProjectSnapshot = {
      schemaVersion: CURRENT_PROJECT_SCHEMA_VERSION,
      id: 'proj-merge',
      title: 'Merge Test',
      createdAtMs: 1000,
      updatedAtMs: 2000,
      contentRevision: 5,
      evidenceRevision: 1,
      configRevision: 1,
      source: {
        id: 'local:test.webm',
        kind: 'local',
        label: 'test.webm',
        url: 'blob:http://localhost/secret-temp',
        fileName: 'test.webm',
        fileSize: 12345,
        durationMs: 28_000,
        capabilities: {
          playback: 'supported',
          captionActivation: 'supported',
          readableCaptionEvidence: 'supported',
          audioExtraction: 'supported',
          frameInspection: 'supported',
          seeking: 'supported',
          permittedOverlayPlacement: 'supported',
          notes: [],
        },
      },
      referenceTracks: [],
      activeReferenceTrackId: null,
      speakers: createDefaultSpeakers('proj-merge'),
      dialogueEvents: [],
      coverage: [],
      settings: { ...DEFAULT_USER_SETTINGS },
      progress: { positionMs: 18_000, updatedAtMs: 2000, progressSeq: 4, isIntentionalSeek: false },
    };

    // Newer content revision (6) saved with older progressSeq (4), racing against backward seek to 3_000ms (progressSeq 5)
    const contentSave: ProjectSnapshot = { ...base, title: 'Updated Title', contentRevision: 6 };
    const backwardSeekSave: ProjectSnapshot = {
      ...base,
      progress: { positionMs: 3_000, updatedAtMs: 2500, progressSeq: 5, isIntentionalSeek: true },
    };

    const merged = mergeProjectSnapshots(contentSave, backwardSeekSave);
    expect(merged.title).toBe('Updated Title'); // Kept newer content!
    expect(merged.progress.positionMs).toBe(3_000); // Preserved intentional backward seek!

    // Relink mismatch check
    const badRelink = verifyRelinkedFileCompatibility(base.source, {
      name: 'other.webm',
      size: 99999,
      lastModified: 1,
      type: 'video/webm',
    });
    expect(badRelink.compatible).toBe(false);

    // Export strips blob: URLs and round-trips cleanly
    const bundle = buildExportedProjectBundle(merged);
    expect(bundle.project.source.url).toBeUndefined();
    const reimported = parseImportedProjectBundle(JSON.stringify(bundle));
    expect(reimported.title).toBe('Updated Title');
    expect(reimported.progress.positionMs).toBe(3_000);

    // Schema v1 migration preserves data
    const v1Migrated = migrateProjectSnapshot({ ...reimported, schemaVersion: 1 });
    expect(v1Migrated?.schemaVersion).toBe(CURRENT_PROJECT_SCHEMA_VERSION);

    // Exporters produce valid VTT and SRT
    const vtt = exportTimelineToWebVtt([], new Map());
    expect(vtt.startsWith('WEBVTT')).toBe(true);
    expect(typeof exportTimelineToSrt([], new Map())).toBe('string');
  });
});
