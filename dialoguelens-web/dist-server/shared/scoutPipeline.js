import { SPEAKER_PALETTE, } from './types.js';
const NEGATION_WORDS = new Set([
    'no',
    'not',
    "n't",
    'never',
    'none',
    'nothing',
    'nobody',
    'neither',
    'nowhere',
    'cannot',
    "can't",
    "won't",
    "don't",
    "doesn't",
    "didn't",
    "isn't",
    "aren't",
    "wasn't",
    "weren't",
    "shouldn't",
    "wouldn't",
    "couldn't",
    'without',
    'no',
    'nunca',
    'jamás',
    'ninguno',
    'ne',
    'pas',
    'nicht',
    'kein',
]);
const NUMBER_WORDS = {
    zero: '0',
    one: '1',
    two: '2',
    three: '3',
    four: '4',
    five: '5',
    six: '6',
    seven: '7',
    eight: '8',
    nine: '9',
    ten: '10',
    eleven: '11',
    twelve: '12',
    twenty: '20',
    thirty: '30',
    forty: '40',
    fifty: '50',
    hundred: '100',
    thousand: '1000',
};
export function normalizeForComparison(text) {
    return text
        .toLowerCase()
        .replace(/[^\p{L}\p{N}\s'-]/gu, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}
export function hasNegationPolarity(text) {
    const tokens = normalizeForComparison(text).split(' ');
    for (const token of tokens) {
        if (NEGATION_WORDS.has(token) || token.endsWith("n't")) {
            return true;
        }
    }
    return false;
}
export function extractQuantities(text) {
    const normalized = normalizeForComparison(text);
    const tokens = normalized.split(' ');
    const found = [];
    for (const token of tokens) {
        if (/^\d+(?:[.,]\d+)?$/.test(token)) {
            found.push(token);
        }
        else if (NUMBER_WORDS[token]) {
            found.push(NUMBER_WORDS[token]);
        }
    }
    return found.sort();
}
export function extractCapitalizedEntities(text) {
    // Capture proper-noun-like capitalized tokens not solely at index 0
    const matches = text.match(/(?<!^)(?<!\. )(?<![!?] )\b[A-Z][a-z]{2,}\b/g) ?? [];
    return [...new Set(matches.map((m) => m.toLowerCase()))].sort();
}
export function tokenOverlapRatio(a, b) {
    const setA = new Set(normalizeForComparison(a).split(' ').filter(Boolean));
    const setB = new Set(normalizeForComparison(b).split(' ').filter(Boolean));
    if (setA.size === 0 && setB.size === 0)
        return 1;
    if (setA.size === 0 || setB.size === 0)
        return 0;
    let shared = 0;
    for (const t of setA) {
        if (setB.has(t))
            shared++;
    }
    return shared / Math.max(setA.size, setB.size);
}
/**
 * Lightweight language heuristic strictly for obvious script/stopword mismatch detection
 * when captions declare a language tag (e.g. 'en' vs 'es'/'fr'/'ja').
 */
export function detectObviousLanguageMismatch(text, expectedLang) {
    const base = expectedLang.toLowerCase().split('-')[0] ?? 'en';
    const hasCjk = /[\u3040-\u30ff\u4e00-\u9fff]/.test(text);
    const hasCyrillic = /[\u0400-\u04ff]/.test(text);
    if (base === 'en' && (hasCjk || hasCyrillic))
        return true;
    if ((base === 'ja' || base === 'zh') && !hasCjk && /[a-zA-Z]{6,}/.test(text))
        return true;
    if (base === 'en') {
        const tokens = new Set(normalizeForComparison(text).split(' '));
        const spanishMarkers = ['está', 'pero', 'porque', 'cuando', 'nuestra', 'tiempo', 'necesitamos', 'hola'];
        const frenchMarkers = ['bonjour', 'mais', 'avec', 'pourquoi', 'nous', 'vous', 'toujours'];
        const esCount = spanishMarkers.filter((m) => tokens.has(m)).length;
        const frCount = frenchMarkers.filter((m) => tokens.has(m)).length;
        if (esCount >= 2 || frCount >= 2)
            return true;
    }
    return false;
}
/**
 * Scout 1 deterministic evidence-first drafter.
 * Uses accessible original evidence first, preserves original timestamps, keeps speaker
 * slots local until continuity links them, and declares explicit uncertainty.
 */
export function runScout1EvidenceDraft(input) {
    const intervalCues = input.evidenceCues
        .filter((cue) => cue.endMs > input.token.intervalStartMs &&
        cue.startMs < input.token.intervalEndMs &&
        Number.isFinite(cue.startMs) &&
        Number.isFinite(cue.endMs) &&
        cue.endMs > cue.startMs)
        .sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs || a.sequence - b.sequence);
    const draftCues = intervalCues.map((ev) => {
        const midMs = (ev.startMs + ev.endMs) / 2;
        const sceneId = input.sceneResolver ? input.sceneResolver(midMs) : 'scene-1';
        let text = ev.text.trim();
        let speakerRole = 'unknown';
        let localSpeakerSlot = 'slot-unknown';
        let styleVariant = 'speech';
        let linkedVisualTrackId;
        const uncertaintyNotes = [];
        // Explicit bracketed narration/offscreen cues from authored captions (never treated as verified identity)
        if (/^\[(?:narrator|narration)\]/i.test(text) || /^narrator:/i.test(text)) {
            speakerRole = 'narrator';
            localSpeakerSlot = 'slot-narrator';
            styleVariant = 'narration';
            text = text.replace(/^(\[(?:narrator|narration)\]|narrator:)\s*/i, '').trim();
        }
        else if (/^\[(?:off-?screen|voice\s*over|os)\]/i.test(text) || /^off-?screen:/i.test(text)) {
            speakerRole = 'offscreen';
            localSpeakerSlot = 'slot-offscreen';
            text = text.replace(/^(\[(?:off-?screen|voice\s*over|os)\]|off-?screen:)\s*/i, '').trim();
        }
        else {
            const prefixMatch = text.match(/^([A-Za-z][A-Za-z0-9 _-]{1,22}):\s+(.+)$/s);
            if (prefixMatch && prefixMatch[1] && prefixMatch[2]) {
                const candidateLabel = prefixMatch[1].trim();
                text = prefixMatch[2].trim();
                speakerRole = 'person';
                localSpeakerSlot = `slot-label:${candidateLabel.toLowerCase()}`;
                uncertaintyNotes.push(`Caption label "${candidateLabel}" is a cue-level hint awaiting continuity confirmation.`);
            }
        }
        if (text.endsWith('!') && (text === text.toUpperCase() || /!!/.test(text))) {
            styleVariant = 'shout';
        }
        // Check explicit supported continuity hints (from diarization / visual speech correlation)
        const hint = input.speakerTrackHints?.find((h) => midMs >= h.startMs && midMs <= h.endMs);
        if (hint && speakerRole !== 'narrator' && speakerRole !== 'offscreen') {
            speakerRole = hint.role;
            localSpeakerSlot = hint.localSpeakerSlot;
            linkedVisualTrackId = hint.linkedVisualTrackId;
        }
        if (speakerRole === 'unknown') {
            uncertaintyNotes.push('Speaker identity unconfirmed from captions alone.');
        }
        if (!input.hasAudioEvidence) {
            uncertaintyNotes.push('Audio evidence was not independently verified for this interval.');
        }
        const needsTranslation = input.targetLanguage.toLowerCase().split('-')[0] !==
            ev.language.toLowerCase().split('-')[0];
        return {
            id: `cue:${ev.id}`,
            startMs: ev.startMs,
            endMs: ev.endMs,
            text,
            translatedText: undefined,
            sourceLanguage: ev.language || input.sourceLanguage,
            targetLanguage: input.targetLanguage,
            localSpeakerSlot,
            speakerRole,
            linkedVisualTrackId,
            styleVariant,
            sceneId,
            evidenceCueIds: [ev.id],
            uncertaintyNotes: needsTranslation
                ? [...uncertaintyNotes, 'Translation not yet generated for target language.']
                : uncertaintyNotes,
        };
    });
    // Never call an empty caption interval "confirmed silence" unless audio actually confirmed silence.
    const confirmedSilence = intervalCues.length === 0 && input.hasAudioEvidence;
    return {
        token: { ...input.token },
        createdAtMs: Date.now(),
        producer: 'local_evidence',
        cues: draftCues,
        hasAudioEvidence: input.hasAudioEvidence,
        hasVisualEvidence: input.hasVisualEvidence,
        confirmedSilence,
        notes: intervalCues.length === 0
            ? [
                confirmedSilence
                    ? 'No dialogue detected with audio evidence present.'
                    : 'No active captions in interval; audio unverified so silence is not confirmed.',
            ]
            : [`Prepared ${draftCues.length} cue candidates from ${intervalCues.length} reference evidence cues.`],
    };
}
function stripSpeakerPrefix(raw) {
    return raw
        .replace(/^(\[(?:narrator|narration|off-?screen|voice\s*over|os)\]|narrator:|off-?screen:)\s*/i, '')
        .replace(/^([A-Za-z][A-Za-z0-9 _-]{1,22}):\s+/s, '')
        .trim();
}
function resolveProjectSpeaker(candidate, projectId, speakers) {
    const unknownId = `${projectId}:spk-unknown`;
    const narratorId = `${projectId}:spk-narrator`;
    const offscreenId = `${projectId}:spk-offscreen`;
    if (candidate.speakerRole === 'narrator') {
        return { speakerId: narratorId, role: 'narrator', attributionStatus: 'supported' };
    }
    if (candidate.speakerRole === 'offscreen') {
        return { speakerId: offscreenId, role: 'offscreen', attributionStatus: 'supported' };
    }
    // Positive linked visual track continuity takes priority
    if (candidate.linkedVisualTrackId) {
        const byTrack = speakers.find((s) => s.linkedTrackIds.includes(candidate.linkedVisualTrackId));
        if (byTrack) {
            return { speakerId: byTrack.id, role: byTrack.role, attributionStatus: 'supported' };
        }
    }
    // Explicit caption speaker label slot (project-local speaker, not real-world identity)
    if (candidate.localSpeakerSlot.startsWith('slot-label:')) {
        const labelKey = candidate.localSpeakerSlot.slice('slot-label:'.length).trim();
        const existing = speakers.find((s) => s.role === 'person' && s.label.toLowerCase() === labelKey.toLowerCase());
        if (existing) {
            return {
                speakerId: existing.id,
                role: 'person',
                attributionStatus: candidate.linkedVisualTrackId ? 'supported' : 'needs_review',
            };
        }
        const prettyLabel = labelKey
            .split(/\s+/)
            .map((p) => (p[0] ? p[0].toUpperCase() + p.slice(1) : p))
            .join(' ');
        const personCount = speakers.filter((s) => s.role === 'person').length;
        const created = {
            id: `${projectId}:spk-${personCount + 1}`,
            projectId,
            label: prettyLabel,
            color: SPEAKER_PALETTE[personCount % SPEAKER_PALETTE.length] ?? '#2563EB',
            role: 'person',
            isUserNamed: false,
            linkedTrackIds: candidate.linkedVisualTrackId ? [candidate.linkedVisualTrackId] : [],
        };
        return {
            speakerId: created.id,
            role: 'person',
            createdSpeaker: created,
            attributionStatus: candidate.linkedVisualTrackId ? 'supported' : 'needs_review',
        };
    }
    if (candidate.linkedVisualTrackId && candidate.speakerRole === 'person') {
        const personCount = speakers.filter((s) => s.role === 'person').length;
        const created = {
            id: `${projectId}:spk-${personCount + 1}`,
            projectId,
            label: `Speaker ${personCount + 1}`,
            color: SPEAKER_PALETTE[personCount % SPEAKER_PALETTE.length] ?? '#2563EB',
            role: 'person',
            isUserNamed: false,
            linkedTrackIds: [candidate.linkedVisualTrackId],
        };
        return {
            speakerId: created.id,
            role: 'person',
            createdSpeaker: created,
            attributionStatus: 'supported',
        };
    }
    return { speakerId: unknownId, role: 'unknown', attributionStatus: 'unverified' };
}
/**
 * Scout 2 independent reviewer.
 * Reads BOTH the exact Scout 1 draft AND the original immutable evidence cues.
 * Repairs missing dialogue, wrong negation, quantity/entity drifts, wrong language,
 * unsupported hallucinated lines, and keeps quality dimensions strictly separated.
 */
export function runScout2IndependentReview(input) {
    const { draft, originalEvidenceCues, expectedSourceLanguage, targetLanguage, clipDurationMs } = input;
    const intervalEvidence = originalEvidenceCues
        .filter((ev) => ev.endMs > draft.token.intervalStartMs &&
        ev.startMs < draft.token.intervalEndMs &&
        Number.isFinite(ev.startMs) &&
        Number.isFinite(ev.endMs) &&
        ev.endMs > ev.startMs)
        .sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs || a.sequence - b.sequence);
    const evidenceById = new Map(intervalEvidence.map((ev) => [ev.id, ev]));
    const issues = [];
    const acceptedCues = [];
    const failedCueIds = new Set(input.providerFailedCueIds ?? []);
    const workingSpeakers = [...input.projectSpeakers];
    const newSpeakers = [];
    const coveredEvidenceIds = new Set();
    for (const candidate of draft.cues) {
        // 1. Validate finite, ordered timing inside clip bounds
        let startMs = candidate.startMs;
        let endMs = candidate.endMs;
        if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs) {
            issues.push({
                cueId: candidate.id,
                code: 'timing_out_of_bounds',
                severity: 'error',
                message: 'Dropped cue with invalid or unordered timestamps.',
                repaired: true,
            });
            failedCueIds.add(candidate.id);
            continue;
        }
        if (clipDurationMs && clipDurationMs > 0 && endMs > clipDurationMs + 250) {
            endMs = Math.min(endMs, Math.ceil(clipDurationMs));
            issues.push({
                cueId: candidate.id,
                code: 'timing_out_of_bounds',
                severity: 'warning',
                message: 'Clamped cue end timestamp to actual media duration.',
                repaired: true,
            });
        }
        // 2. Match against immutable original evidence (by id first, then timestamp overlap)
        let matchedEvidence;
        for (const evId of candidate.evidenceCueIds) {
            const exact = evidenceById.get(evId);
            if (exact) {
                matchedEvidence = exact;
                break;
            }
        }
        if (!matchedEvidence) {
            matchedEvidence = intervalEvidence.find((ev) => !coveredEvidenceIds.has(ev.id) &&
                Math.abs(ev.startMs - startMs) <= 600 &&
                Math.abs(ev.endMs - endMs) <= 800);
        }
        // 3. Check for unsupported generated content when there is NO audio evidence backing it
        if (!matchedEvidence && !draft.hasAudioEvidence) {
            issues.push({
                cueId: candidate.id,
                code: 'unsupported_generated_line',
                severity: 'error',
                message: `Rejected unsupported draft line "${candidate.text}" with neither reference caption nor audio backing.`,
                repaired: true,
            });
            continue;
        }
        let finalOriginalText = candidate.text.trim();
        const referenceAlignment = matchedEvidence
            ? 'supported'
            : 'unverified';
        if (matchedEvidence) {
            coveredEvidenceIds.add(matchedEvidence.id);
            const refText = stripSpeakerPrefix(matchedEvidence.text);
            // Keep original reference timestamps authoritative
            startMs = matchedEvidence.startMs;
            endMs = matchedEvidence.endMs;
            // Check wrong language
            if (detectObviousLanguageMismatch(finalOriginalText, matchedEvidence.language || expectedSourceLanguage)) {
                issues.push({
                    cueId: candidate.id,
                    code: 'wrong_language',
                    severity: 'error',
                    message: `Draft text appeared in the wrong language; restored original evidence text.`,
                    repaired: true,
                });
                finalOriginalText = refText;
            }
            // Check negation polarity flip!
            if (hasNegationPolarity(refText) !== hasNegationPolarity(finalOriginalText)) {
                issues.push({
                    cueId: candidate.id,
                    code: 'negation_flip',
                    severity: 'error',
                    message: `Detected negation polarity change ("${finalOriginalText}" vs "${refText}"); restored reference wording.`,
                    repaired: true,
                });
                finalOriginalText = refText;
            }
            // Check quantity drift (e.g., "3 minutes" changed to "30 minutes")
            const refNums = extractQuantities(refText);
            const draftNums = extractQuantities(finalOriginalText);
            if (refNums.join(',') !== draftNums.join(',')) {
                issues.push({
                    cueId: candidate.id,
                    code: 'quantity_mismatch',
                    severity: 'error',
                    message: `Detected quantity drift (${draftNums.join(', ') || 'none'} vs ${refNums.join(', ') || 'none'}); restored reference wording.`,
                    repaired: true,
                });
                finalOriginalText = refText;
            }
            // Check proper entity drift
            const refEntities = extractCapitalizedEntities(refText);
            const draftEntities = extractCapitalizedEntities(finalOriginalText);
            if (refEntities.length > 0 &&
                refEntities.some((e) => !draftEntities.includes(e))) {
                issues.push({
                    cueId: candidate.id,
                    code: 'entity_mismatch',
                    severity: 'error',
                    message: `Detected name/entity drift against reference evidence; restored reference wording.`,
                    repaired: true,
                });
                finalOriginalText = refText;
            }
            // Check severe general text divergence from reference
            if (tokenOverlapRatio(refText, finalOriginalText) < 0.65) {
                issues.push({
                    cueId: candidate.id,
                    code: 'same_time_correction',
                    severity: 'warning',
                    message: `Draft wording diverged from original reference cue; restored reference wording.`,
                    repaired: true,
                });
                finalOriginalText = refText;
            }
        }
        // 4. Translation review (strictly separate from reference alignment!)
        const sourceLang = (matchedEvidence?.language || candidate.sourceLanguage || expectedSourceLanguage)
            .toLowerCase()
            .split('-')[0] || 'en';
        const targetLang = (targetLanguage || sourceLang).toLowerCase().split('-')[0] || sourceLang;
        const needsTranslation = sourceLang !== targetLang;
        let translatedText = candidate.translatedText?.trim() || undefined;
        let translationStatus = 'not_needed';
        let translationReview = 'supported';
        if (needsTranslation) {
            if (!translatedText || normalizeForComparison(translatedText) === normalizeForComparison(finalOriginalText)) {
                translationStatus = 'missing_translation';
                translationReview = 'unverified';
                translatedText = undefined;
                issues.push({
                    cueId: candidate.id,
                    code: 'missing_translation',
                    severity: 'warning',
                    message: `Missing reviewed translation from ${sourceLang} to ${targetLang}; displaying original text clearly marked untranslated.`,
                    repaired: false,
                });
            }
            else {
                translationStatus = 'translated';
                translationReview = draft.producer === 'local_evidence' ? 'needs_review' : 'supported';
            }
        }
        // 5. Speaker continuity & attribution check
        const resolved = resolveProjectSpeaker(candidate, draft.token.projectId, workingSpeakers);
        if (resolved.createdSpeaker) {
            workingSpeakers.push(resolved.createdSpeaker);
            newSpeakers.push(resolved.createdSpeaker);
        }
        // Reusing reference captions DOES NOT certify independent audio verification
        const audioSupport = draft.hasAudioEvidence
            ? 'supported'
            : 'unavailable';
        const quality = {
            referenceAlignment,
            audioSupport,
            translationReview,
            attribution: resolved.attributionStatus,
            // Predicted layout review alone cannot prove rendered browser stability;
            // presentation starts as needs_review until runtime presentation confirms measured fit.
            presentation: 'needs_review',
        };
        acceptedCues.push({
            id: matchedEvidence ? `cue:${matchedEvidence.id}` : candidate.id,
            projectId: draft.token.projectId,
            mediaId: draft.token.mediaId,
            startMs,
            endMs,
            originalText: finalOriginalText,
            translatedText,
            sourceLanguage: sourceLang,
            targetLanguage: targetLang,
            translationStatus,
            speakerId: resolved.speakerId,
            speakerRole: resolved.role,
            linkedVisualTrackId: candidate.linkedVisualTrackId,
            styleVariant: candidate.styleVariant,
            sceneId: candidate.sceneId,
            evidenceCueIds: matchedEvidence ? [matchedEvidence.id] : candidate.evidenceCueIds,
            evidenceRevision: draft.token.evidenceRevision,
            acceptedRevision: draft.token.draftRevision,
            quality,
            protectedFields: {
                text: false,
                timing: false,
                speaker: false,
                anchor: false,
            },
            uncertaintyNotes: [...candidate.uncertaintyNotes],
            conflictHints: [],
        });
    }
    // 6. Recover ANY original evidence cues missing from Scout 1's draft (including legitimate repetitions
    // or intervals Scout 1 mistakenly called silent!).
    for (const ev of intervalEvidence) {
        if (coveredEvidenceIds.has(ev.id))
            continue;
        const cleanText = stripSpeakerPrefix(ev.text);
        const isNarration = /^(\[(?:narrator|narration)\]|narrator:)/i.test(ev.text);
        const isOffscreen = /^(\[(?:off-?screen|voice\s*over|os)\]|off-?screen:)/i.test(ev.text);
        issues.push({
            cueId: `cue:${ev.id}`,
            code: draft.confirmedSilence ? 'missing_dialogue' : 'repetition_dropped',
            severity: 'error',
            message: `Scout 2 recovered dialogue missing from Scout 1 draft at ${ev.startMs}ms: "${cleanText}".`,
            repaired: true,
        });
        const sourceLang = (ev.language || expectedSourceLanguage).toLowerCase().split('-')[0] || 'en';
        const targetLang = (targetLanguage || sourceLang).toLowerCase().split('-')[0] || sourceLang;
        const needsTranslation = sourceLang !== targetLang;
        const speakerId = isNarration
            ? `${draft.token.projectId}:spk-narrator`
            : isOffscreen
                ? `${draft.token.projectId}:spk-offscreen`
                : `${draft.token.projectId}:spk-unknown`;
        const speakerRole = isNarration
            ? 'narrator'
            : isOffscreen
                ? 'offscreen'
                : 'unknown';
        acceptedCues.push({
            id: `cue:${ev.id}`,
            projectId: draft.token.projectId,
            mediaId: draft.token.mediaId,
            startMs: ev.startMs,
            endMs: ev.endMs,
            originalText: cleanText,
            sourceLanguage: sourceLang,
            targetLanguage: targetLang,
            translationStatus: needsTranslation ? 'missing_translation' : 'not_needed',
            speakerId,
            speakerRole,
            styleVariant: isNarration ? 'narration' : 'speech',
            sceneId: 'scene-1',
            evidenceCueIds: [ev.id],
            evidenceRevision: draft.token.evidenceRevision,
            acceptedRevision: draft.token.draftRevision,
            quality: {
                referenceAlignment: 'supported',
                audioSupport: draft.hasAudioEvidence ? 'supported' : 'unavailable',
                translationReview: needsTranslation ? 'unverified' : 'supported',
                attribution: speakerRole === 'unknown' ? 'unverified' : 'supported',
                presentation: 'needs_review',
            },
            protectedFields: {
                text: false,
                timing: false,
                speaker: false,
                anchor: false,
            },
            uncertaintyNotes: ['Recovered directly by Scout 2 from original reference evidence.'],
            conflictHints: [],
        });
    }
    acceptedCues.sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs || a.id.localeCompare(b.id));
    // CRITICAL: Never certify silence if spoken dialogue was recovered!
    const confirmedSilence = acceptedCues.length === 0 && draft.confirmedSilence && draft.hasAudioEvidence;
    const intervalStatus = failedCueIds.size > 0
        ? acceptedCues.length > 0
            ? 'reviewed_partial'
            : 'failed'
        : 'reviewed_complete';
    return {
        outcome: {
            token: { ...draft.token },
            reviewedAtMs: Date.now(),
            reviewer: 'local_deterministic',
            draftRevisionReviewed: draft.token.draftRevision,
            acceptedCues,
            failedCueIds: [...failedCueIds],
            issues,
            intervalStatus,
            confirmedSilence,
            notes: [
                `Scout 2 reviewed ${draft.cues.length} draft cues against ${intervalEvidence.length} reference cues; accepted ${acceptedCues.length}, flagged ${issues.length} issues.`,
            ],
        },
        newSpeakers,
    };
}
/**
 * Single authoritative transactional acceptance boundary:
 * evidence -> draft -> reviewed -> accepted.
 * Rejects stale/mismatched tokens against BOTH pending and accepted revisions,
 * protects user-edited fields while recording visible conflict hints, and supports
 * positive-link-only speaker correction propagation.
 */
export class TimelineAcceptanceStore {
    state;
    constructor(initial) {
        this.state = {
            ...initial,
            events: initial.events.map((e) => ({ ...e, protectedFields: { ...e.protectedFields }, quality: { ...e.quality } })),
            speakers: initial.speakers.map((s) => ({ ...s, linkedTrackIds: [...s.linkedTrackIds] })),
            coverage: initial.coverage.map((c) => ({ ...c, failedCueIds: [...c.failedCueIds] })),
        };
    }
    getSnapshot() {
        return {
            ...this.state,
            events: this.state.events.map((e) => ({
                ...e,
                protectedFields: { ...e.protectedFields },
                quality: { ...e.quality },
                uncertaintyNotes: [...e.uncertaintyNotes],
                conflictHints: [...e.conflictHints],
                manualAnchor: e.manualAnchor ? { ...e.manualAnchor } : undefined,
            })),
            speakers: this.state.speakers.map((s) => ({ ...s, linkedTrackIds: [...s.linkedTrackIds] })),
            coverage: this.state.coverage.map((c) => ({ ...c, failedCueIds: [...c.failedCueIds] })),
        };
    }
    beginNewPendingDraft(intervalStartMs, intervalEndMs, taskId, attempt = 1) {
        this.state.pendingDraftRevision += 1;
        return {
            projectId: this.state.projectId,
            mediaId: this.state.mediaId,
            evidenceRevision: this.state.evidenceRevision,
            configRevision: this.state.configRevision,
            taskId,
            intervalStartMs,
            intervalEndMs,
            draftRevision: this.state.pendingDraftRevision,
            attempt,
            viewerGeneration: this.state.viewerGeneration,
        };
    }
    bumpViewerGeneration() {
        this.state.viewerGeneration += 1;
        return this.state.viewerGeneration;
    }
    bumpConfigRevision(invalidateTranslations) {
        this.state.configRevision += 1;
        if (invalidateTranslations) {
            this.state.events = this.state.events.map((ev) => ({
                ...ev,
                translationStatus: ev.sourceLanguage === ev.targetLanguage ? 'not_needed' : 'stale',
                quality: {
                    ...ev.quality,
                    translationReview: ev.sourceLanguage === ev.targetLanguage ? 'supported' : 'needs_review',
                },
            }));
        }
    }
    bumpEvidenceRevision() {
        this.state.evidenceRevision += 1;
    }
    /**
     * Validates ownership token against immutable project/media/evidence/config identity
     * AND rejects stale revisions relative to both pending and already-accepted revisions.
     */
    validateToken(token, allowOlderViewerGenerationForDurableEvidence = true) {
        if (token.projectId !== this.state.projectId) {
            return { valid: false, reason: 'Stale token: projectId mismatch' };
        }
        if (token.mediaId !== this.state.mediaId) {
            return { valid: false, reason: 'Stale token: mediaId mismatch' };
        }
        if (token.evidenceRevision !== this.state.evidenceRevision) {
            return { valid: false, reason: 'Stale token: evidenceRevision superseded' };
        }
        if (token.configRevision !== this.state.configRevision) {
            return { valid: false, reason: 'Stale token: configRevision superseded' };
        }
        if (!allowOlderViewerGenerationForDurableEvidence && token.viewerGeneration !== this.state.viewerGeneration) {
            return { valid: false, reason: 'Stale token: viewerGeneration superseded' };
        }
        if (token.draftRevision < this.state.acceptedRevision) {
            return { valid: false, reason: 'Stale token: draftRevision older than already-accepted revision' };
        }
        if (token.draftRevision < this.state.pendingDraftRevision) {
            return { valid: false, reason: 'Stale token: draftRevision superseded by newer pending revision' };
        }
        return { valid: true, reason: 'ok' };
    }
    commitReviewedOutcome(outcome, addedSpeakers = []) {
        const check = this.validateToken(outcome.token, true);
        if (!check.valid) {
            return {
                accepted: false,
                reason: check.reason,
                revision: this.state.acceptedRevision,
                conflictsRecorded: 0,
                acceptedCueCount: 0,
                failedCueIds: outcome.failedCueIds,
            };
        }
        // Merge new project-local speakers idempotently
        for (const spk of addedSpeakers) {
            if (!this.state.speakers.some((s) => s.id === spk.id)) {
                this.state.speakers.push({ ...spk, linkedTrackIds: [...spk.linkedTrackIds] });
            }
        }
        const nextRevision = Math.max(this.state.acceptedRevision + 1, outcome.token.draftRevision);
        const existingById = new Map(this.state.events.map((e) => [e.id, e]));
        let conflictsRecorded = 0;
        const intervalStart = outcome.token.intervalStartMs;
        const intervalEnd = outcome.token.intervalEndMs;
        // Keep events outside this interval OR protected user events inside this interval
        const retainedEvents = [];
        const incomingIds = new Set(outcome.acceptedCues.map((c) => c.id));
        for (const existing of this.state.events) {
            const inInterval = existing.endMs > intervalStart && existing.startMs < intervalEnd;
            if (!inInterval) {
                retainedEvents.push(existing);
                continue;
            }
            // If an incoming reviewed update replaces it, handle in merge loop below.
            if (incomingIds.has(existing.id)) {
                continue;
            }
            // Never silently erase user-protected cues or usable text when a retry fails/partially succeeds
            const hasUserProtection = existing.protectedFields.text ||
                existing.protectedFields.timing ||
                existing.protectedFields.speaker ||
                existing.protectedFields.anchor;
            if (hasUserProtection || outcome.intervalStatus === 'failed' || outcome.intervalStatus === 'reviewed_partial') {
                retainedEvents.push(existing);
            }
        }
        for (const incoming of outcome.acceptedCues) {
            const prior = existingById.get(incoming.id);
            if (!prior) {
                retainedEvents.push({
                    ...incoming,
                    acceptedRevision: nextRevision,
                });
                continue;
            }
            const conflictHints = [...prior.conflictHints];
            let originalText = incoming.originalText;
            let translatedText = incoming.translatedText;
            let startMs = incoming.startMs;
            let endMs = incoming.endMs;
            let speakerId = incoming.speakerId;
            let speakerRole = incoming.speakerRole;
            let manualAnchor = prior.manualAnchor;
            if (prior.protectedFields.text) {
                if (prior.originalText !== incoming.originalText) {
                    conflictsRecorded++;
                    conflictHints.push({
                        id: `conflict:${nextRevision}:text:${incoming.id}`,
                        field: 'text',
                        proposedValue: incoming.originalText,
                        reason: 'Scout 2 proposed updated wording, preserved user-edited text.',
                        scoutRevision: nextRevision,
                    });
                }
                originalText = prior.originalText;
                translatedText = prior.translatedText ?? incoming.translatedText;
            }
            if (prior.protectedFields.timing) {
                if (prior.startMs !== incoming.startMs || prior.endMs !== incoming.endMs) {
                    conflictsRecorded++;
                    conflictHints.push({
                        id: `conflict:${nextRevision}:timing:${incoming.id}`,
                        field: 'timing',
                        proposedValue: `${incoming.startMs}–${incoming.endMs} ms`,
                        reason: 'Scout 2 proposed adjusted timestamps, preserved user-edited timing.',
                        scoutRevision: nextRevision,
                    });
                }
                startMs = prior.startMs;
                endMs = prior.endMs;
            }
            if (prior.protectedFields.speaker) {
                if (prior.speakerId !== incoming.speakerId) {
                    conflictsRecorded++;
                    conflictHints.push({
                        id: `conflict:${nextRevision}:speaker:${incoming.id}`,
                        field: 'speaker',
                        proposedValue: incoming.speakerId,
                        reason: 'Scout 2 proposed a different speaker, preserved user-assigned speaker.',
                        scoutRevision: nextRevision,
                    });
                }
                speakerId = prior.speakerId;
                speakerRole = prior.speakerRole;
            }
            if (prior.protectedFields.anchor) {
                manualAnchor = prior.manualAnchor;
            }
            retainedEvents.push({
                ...incoming,
                startMs,
                endMs,
                originalText,
                translatedText,
                speakerId,
                speakerRole,
                manualAnchor,
                acceptedRevision: nextRevision,
                protectedFields: { ...prior.protectedFields },
                conflictHints,
            });
        }
        retainedEvents.sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs || a.id.localeCompare(b.id));
        this.state.events = retainedEvents;
        this.state.acceptedRevision = nextRevision;
        // Update coverage ledger transactionally in the same commit
        const covStatus = outcome.intervalStatus === 'failed'
            ? 'failed'
            : outcome.intervalStatus === 'reviewed_partial'
                ? 'partial'
                : outcome.intervalStatus === 'fallback_evidence'
                    ? 'fallback_captions'
                    : outcome.confirmedSilence && outcome.acceptedCues.length === 0
                        ? 'confirmed_silence'
                        : 'accepted';
        this.upsertCoverage({
            startMs: intervalStart,
            endMs: intervalEnd,
            status: covStatus,
            evidenceRevision: outcome.token.evidenceRevision,
            acceptedRevision: nextRevision,
            failedCueIds: [...outcome.failedCueIds],
            attempts: outcome.token.attempt,
            updatedAtMs: Date.now(),
        });
        return {
            accepted: true,
            reason: 'Committed reviewed outcome transactionally.',
            revision: nextRevision,
            conflictsRecorded,
            acceptedCueCount: outcome.acceptedCues.length,
            failedCueIds: outcome.failedCueIds,
        };
    }
    /**
     * Authoritative user edit with field-specific protection.
     * Optional positive-link-only speaker propagation NEVER propagates across unknown speakers.
     */
    applyUserEdit(cueId, patch) {
        const idx = this.state.events.findIndex((e) => e.id === cueId);
        if (idx === -1) {
            return { updated: false, propagatedCount: 0, revision: this.state.acceptedRevision };
        }
        const target = this.state.events[idx];
        const nextRevision = this.state.acceptedRevision + 1;
        const protectedFields = { ...target.protectedFields };
        let nextOriginal = target.originalText;
        let nextTranslated = target.translatedText;
        if (patch.originalText !== undefined) {
            nextOriginal = patch.originalText;
            protectedFields.text = true;
        }
        if (patch.translatedText !== undefined) {
            nextTranslated = patch.translatedText;
            protectedFields.text = true;
        }
        let nextStart = target.startMs;
        let nextEnd = target.endMs;
        if (patch.startMs !== undefined && Number.isFinite(patch.startMs)) {
            nextStart = Math.max(0, patch.startMs);
            protectedFields.timing = true;
        }
        if (patch.endMs !== undefined && Number.isFinite(patch.endMs) && patch.endMs > nextStart) {
            nextEnd = patch.endMs;
            protectedFields.timing = true;
        }
        let nextSpeakerId = target.speakerId;
        let nextSpeakerRole = target.speakerRole;
        if (patch.speakerId !== undefined) {
            nextSpeakerId = patch.speakerId;
            const found = this.state.speakers.find((s) => s.id === patch.speakerId);
            nextSpeakerRole = patch.speakerRole ?? found?.role ?? target.speakerRole;
            protectedFields.speaker = true;
        }
        let nextAnchor = target.manualAnchor;
        if (patch.manualAnchor !== undefined) {
            if (patch.manualAnchor === null) {
                nextAnchor = undefined;
                protectedFields.anchor = false;
            }
            else {
                nextAnchor = {
                    x: Math.min(1, Math.max(0, patch.manualAnchor.x)),
                    y: Math.min(1, Math.max(0, patch.manualAnchor.y)),
                };
                protectedFields.anchor = true;
            }
        }
        this.state.events[idx] = {
            ...target,
            originalText: nextOriginal,
            translatedText: nextTranslated,
            startMs: nextStart,
            endMs: nextEnd,
            speakerId: nextSpeakerId,
            speakerRole: nextSpeakerRole,
            manualAnchor: nextAnchor,
            protectedFields,
            acceptedRevision: nextRevision,
            quality: {
                ...target.quality,
                referenceAlignment: protectedFields.text ? 'user_authored' : target.quality.referenceAlignment,
                attribution: protectedFields.speaker ? 'user_authored' : target.quality.attribution,
            },
        };
        // Positive-link-only propagation: ONLY propagate to other cues sharing the exact same
        // supported linkedVisualTrackId AND never match two unknown speakers!
        let propagatedCount = 0;
        if (patch.propagateSpeakerByPositiveLink &&
            patch.speakerId &&
            target.linkedVisualTrackId &&
            target.speakerRole !== 'unknown') {
            const positiveTrackId = target.linkedVisualTrackId;
            this.state.events = this.state.events.map((ev) => {
                if (ev.id !== cueId &&
                    !ev.protectedFields.speaker &&
                    ev.linkedVisualTrackId === positiveTrackId) {
                    propagatedCount++;
                    return {
                        ...ev,
                        speakerId: nextSpeakerId,
                        speakerRole: nextSpeakerRole,
                        acceptedRevision: nextRevision,
                        quality: { ...ev.quality, attribution: 'supported' },
                    };
                }
                return ev;
            });
        }
        this.state.events.sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs || a.id.localeCompare(b.id));
        this.state.acceptedRevision = nextRevision;
        return { updated: true, propagatedCount, revision: nextRevision };
    }
    /**
     * Release explicit fallback captions after a preparation/review deadline expires.
     * Uses neutral attribution without unsupported tails and records fallback_captions coverage.
     */
    commitDeadlineFallback(token, referenceTrack) {
        const check = this.validateToken(token, true);
        if (!check.valid) {
            return {
                accepted: false,
                reason: check.reason,
                revision: this.state.acceptedRevision,
                conflictsRecorded: 0,
                acceptedCueCount: 0,
                failedCueIds: [],
            };
        }
        const cues = (referenceTrack?.cues ?? []).filter((c) => c.endMs > token.intervalStartMs && c.startMs < token.intervalEndMs);
        if (cues.length === 0) {
            this.upsertCoverage({
                startMs: token.intervalStartMs,
                endMs: token.intervalEndMs,
                status: 'unavailable',
                evidenceRevision: token.evidenceRevision,
                acceptedRevision: this.state.acceptedRevision,
                failedCueIds: [],
                attempts: token.attempt,
                lastError: 'Deadline exceeded with no usable caption evidence.',
                updatedAtMs: Date.now(),
            });
            return {
                accepted: false,
                reason: 'No usable fallback evidence available in interval.',
                revision: this.state.acceptedRevision,
                conflictsRecorded: 0,
                acceptedCueCount: 0,
                failedCueIds: [],
            };
        }
        const fallbackEvents = cues.map((ev) => ({
            id: `cue:${ev.id}`,
            projectId: token.projectId,
            mediaId: token.mediaId,
            startMs: ev.startMs,
            endMs: ev.endMs,
            originalText: stripSpeakerPrefix(ev.text),
            sourceLanguage: ev.language,
            targetLanguage: ev.language,
            translationStatus: 'not_needed',
            speakerId: `${token.projectId}:spk-unknown`,
            speakerRole: 'unknown',
            styleVariant: 'speech',
            sceneId: 'scene-1',
            evidenceCueIds: [ev.id],
            evidenceRevision: token.evidenceRevision,
            acceptedRevision: token.draftRevision,
            quality: {
                referenceAlignment: 'supported',
                audioSupport: 'unavailable',
                translationReview: 'unverified',
                attribution: 'unverified',
                presentation: 'needs_review',
            },
            protectedFields: { text: false, timing: false, speaker: false, anchor: false },
            uncertaintyNotes: ['Released as explicit evidence-backed fallback after review deadline.'],
            conflictHints: [],
        }));
        return this.commitReviewedOutcome({
            token,
            reviewedAtMs: Date.now(),
            reviewer: 'local_deterministic',
            draftRevisionReviewed: token.draftRevision,
            acceptedCues: fallbackEvents,
            failedCueIds: [],
            issues: [],
            intervalStatus: 'fallback_evidence',
            confirmedSilence: false,
            notes: ['Explicit fallback released after elapsed deadline.'],
        });
    }
    upsertCoverage(next) {
        const idx = this.state.coverage.findIndex((c) => c.startMs === next.startMs && c.endMs === next.endMs);
        if (idx >= 0) {
            this.state.coverage[idx] = next;
        }
        else {
            this.state.coverage.push(next);
            this.state.coverage.sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);
        }
    }
}
