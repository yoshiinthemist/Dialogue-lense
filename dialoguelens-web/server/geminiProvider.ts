import { GoogleGenAI } from '@google/genai';
import { z } from 'zod';
import type {
  DraftCueCandidate,
  DraftInterval,
  EvidenceCue,
  GlossaryEntry,
  SpeakerProfile,
  TaskOwnershipToken,
} from '../shared/types.js';

export const ProviderCueSchema = z.object({
  id: z.string().min(1),
  startMs: z.number().finite().nonnegative(),
  endMs: z.number().finite().positive(),
  text: z.string().min(1),
  translatedText: z.string().optional(),
  sourceLanguage: z.string().min(2).max(16),
  localSpeakerSlot: z.string().min(1),
  speakerRole: z.enum(['person', 'narrator', 'offscreen', 'unknown']),
  styleVariant: z.enum(['speech', 'shout', 'narration']).default('speech'),
  sceneId: z.string().default('scene-1'),
  evidenceCueIds: z.array(z.string()).default([]),
  uncertaintyNotes: z.array(z.string()).default([]),
});

export const ProviderDraftResponseSchema = z.object({
  cues: z.array(ProviderCueSchema),
  failedCueIds: z.array(z.string()).default([]),
  confirmedSilence: z.boolean().default(false),
  notes: z.array(z.string()).default([]),
});

export type ProviderDraftResponse = z.infer<typeof ProviderDraftResponseSchema>;

export interface ProviderAnalysisRequest {
  stage: 'scout1' | 'scout2';
  token: TaskOwnershipToken;
  sourceLanguage: string;
  targetLanguage: string;
  evidenceCues: EvidenceCue[];
  glossary: GlossaryEntry[];
  priorDraft?: DraftInterval;
  audioBase64?: string;
  audioMimeType?: string;
  existingSpeakers?: SpeakerProfile[];
}

export class ProviderError extends Error {
  constructor(
    public readonly code:
      | 'CREDENTIALS_UNCONFIGURED'
      | 'TIMEOUT'
      | 'CANCELLED'
      | 'MALFORMED_OUTPUT'
      | 'PROVIDER_FAILED',
    message: string,
  ) {
    super(message);
    this.name = 'ProviderError';
  }
}

export interface ProviderTransport {
  generateJson(params: {
    model: string;
    prompt: string;
    audioBase64?: string;
    audioMimeType?: string;
    signal: AbortSignal;
  }): Promise<string>;
}

export interface GeminiProviderOptions {
  apiKey?: string;
  scout1Model: string;
  scout2Model: string;
  requestTimeoutMs?: number;
  /** Injectable strictly for deterministic automated tests; production uses real @google/genai. */
  customTransport?: ProviderTransport;
}

/**
 * Real Google Gemini provider implementation using @google/genai.
 * - Uses pinned model identifiers (`scout1Model`, `scout2Model`)
 * - Validates structured JSON strictly with Zod
 * - Separates per-request timeout from parent AbortSignal cancellation
 * - NEVER fabricates fake/canned responses when GEMINI_API_KEY is unset!
 */
export class GeminiDialogueProvider {
  private readonly client: GoogleGenAI | null;
  private readonly scout1Model: string;
  private readonly scout2Model: string;
  private readonly requestTimeoutMs: number;
  private readonly customTransport?: ProviderTransport;

  constructor(options: GeminiProviderOptions) {
    const key = options.apiKey?.trim() ?? '';
    this.client = key ? new GoogleGenAI({ apiKey: key }) : null;
    this.scout1Model = options.scout1Model;
    this.scout2Model = options.scout2Model;
    this.requestTimeoutMs = options.requestTimeoutMs ?? 20_000;
    this.customTransport = options.customTransport;
  }

  isConfigured(): boolean {
    return Boolean(this.client || this.customTransport);
  }

  async analyzeInterval(
    request: ProviderAnalysisRequest,
    parentSignal?: AbortSignal,
  ): Promise<{
    parsed: ProviderDraftResponse;
    modelUsed: string;
    latencyMs: number;
  }> {
    if (!this.client && !this.customTransport) {
      throw new ProviderError(
        'CREDENTIALS_UNCONFIGURED',
        'GEMINI_API_KEY is not configured on the server. AI analysis is unavailable until configured.',
      );
    }

    if (parentSignal?.aborted) {
      throw new ProviderError('CANCELLED', 'Parent request or policy cancelled before provider dispatch.');
    }

    const model = request.stage === 'scout1' ? this.scout1Model : this.scout2Model;
    const prompt = buildStructuredPrompt(request);

    // Separate per-request timeout controller from parent cancellation signal
    const combinedController = new AbortController();
    let timedOut = false;

    const onParentAbort = (): void => {
      combinedController.abort(parentSignal?.reason ?? new Error('Parent cancelled'));
    };
    parentSignal?.addEventListener('abort', onParentAbort, { once: true });

    const timeoutHandle = setTimeout(() => {
      timedOut = true;
      combinedController.abort(new Error(`Provider request timed out after ${this.requestTimeoutMs}ms`));
    }, this.requestTimeoutMs);

    const startedAt = Date.now();
    try {
      const rawText = this.customTransport
        ? await this.customTransport.generateJson({
            model,
            prompt,
            audioBase64: request.audioBase64,
            audioMimeType: request.audioMimeType,
            signal: combinedController.signal,
          })
        : await this.callRealGeminiApi(model, prompt, request.audioBase64, request.audioMimeType, combinedController.signal);

      let jsonValue: unknown;
      try {
        jsonValue = JSON.parse(rawText);
      } catch {
        throw new ProviderError('MALFORMED_OUTPUT', 'Provider returned non-JSON response payload.');
      }

      const validated = ProviderDraftResponseSchema.safeParse(jsonValue);
      if (!validated.success) {
        throw new ProviderError(
          'MALFORMED_OUTPUT',
          `Provider JSON failed schema validation: ${validated.error.issues[0]?.message ?? 'invalid schema'}`,
        );
      }

      return {
        parsed: validated.data,
        modelUsed: model,
        latencyMs: Date.now() - startedAt,
      };
    } catch (err) {
      if (err instanceof ProviderError) {
        throw err;
      }
      if (parentSignal?.aborted) {
        throw new ProviderError('CANCELLED', 'Provider request was cancelled by parent signal.');
      }
      if (timedOut) {
        throw new ProviderError(
          'TIMEOUT',
          `Provider request timed out after ${this.requestTimeoutMs}ms.`,
        );
      }
      const msg = err instanceof Error ? err.message : String(err);
      throw new ProviderError('PROVIDER_FAILED', `Gemini API request failed: ${msg}`);
    } finally {
      clearTimeout(timeoutHandle);
      parentSignal?.removeEventListener('abort', onParentAbort);
    }
  }

  private async callRealGeminiApi(
    model: string,
    prompt: string,
    audioBase64: string | undefined,
    audioMimeType: string | undefined,
    signal: AbortSignal,
  ): Promise<string> {
    if (!this.client) {
      throw new ProviderError('CREDENTIALS_UNCONFIGURED', 'Gemini client is not initialized.');
    }

    const parts: Array<
      | { text: string }
      | { inlineData: { mimeType: string; data: string } }
    > = [{ text: prompt }];

    if (audioBase64 && audioMimeType) {
      parts.push({
        inlineData: {
          mimeType: audioMimeType,
          data: audioBase64,
        },
      });
    }

    // Race SDK call with AbortSignal so timeouts and Free CC Only aborts unblock immediately
    const sdkPromise = this.client.models.generateContent({
      model,
      contents: [{ role: 'user', parts }],
      config: {
        responseMimeType: 'application/json',
        temperature: 0.1,
      },
    });

    const abortPromise = new Promise<never>((_, reject) => {
      if (signal.aborted) {
        reject(signal.reason ?? new Error('Aborted'));
        return;
      }
      signal.addEventListener(
        'abort',
        () => reject(signal.reason ?? new Error('Aborted')),
        { once: true },
      );
    });

    const response = await Promise.race([sdkPromise, abortPromise]);
    const text = response.text;
    if (!text) {
      throw new ProviderError('MALFORMED_OUTPUT', 'Gemini returned an empty response body.');
    }
    return text;
  }
}

function buildStructuredPrompt(req: ProviderAnalysisRequest): string {
  const glossaryLines =
    req.glossary.length > 0
      ? `Glossary preferences:\n${req.glossary.map((g) => `- "${g.sourceTerm}" -> "${g.preferredTranslation}"`).join('\n')}`
      : 'No glossary overrides.';

  const evidenceSummary = JSON.stringify(
    req.evidenceCues.map((c) => ({
      id: c.id,
      startMs: c.startMs,
      endMs: c.endMs,
      language: c.language,
      origin: c.origin,
      text: c.text,
    })),
  );

  const stageInstruction =
    req.stage === 'scout1'
      ? `You are Scout 1 (evidence-first dialogue drafter). Analyze interval [${req.token.intervalStartMs}ms, ${req.token.intervalEndMs}ms]. Preserve original timestamps, never invent unsupported dialogue, keep speaker slots local (e.g. slot-1, slot-2, slot-unknown, slot-narrator, slot-offscreen), and record explicit uncertainty notes.`
      : `You are Scout 2 (independent reviewer). Compare Scout 1's exact draft against the original immutable reference evidence and audio (if attached). Repair missing dialogue, negation flips, quantity/name errors, wrong language, dropped repetitions, and missing translations. Prior draft: ${JSON.stringify(req.priorDraft?.cues ?? [])}`;

  return [
    stageInstruction,
    `Source language: ${req.sourceLanguage}. Target language: ${req.targetLanguage}.`,
    glossaryLines,
    `Original reference evidence cues: ${evidenceSummary}`,
    `Return ONLY valid JSON matching: { "cues": [{ "id", "startMs", "endMs", "text", "translatedText"?, "sourceLanguage", "localSpeakerSlot", "speakerRole": "person"|"narrator"|"offscreen"|"unknown", "styleVariant": "speech"|"shout"|"narration", "sceneId", "evidenceCueIds": string[], "uncertaintyNotes": string[] }], "failedCueIds": string[], "confirmedSilence": boolean, "notes": string[] }`,
  ].join('\n\n');
}

export function convertProviderResponseToDraft(
  response: ProviderDraftResponse,
  token: TaskOwnershipToken,
  targetLanguage: string,
  hasAudioEvidence: boolean,
  hasVisualEvidence: boolean,
): DraftInterval {
  const cues: DraftCueCandidate[] = response.cues.map((c) => ({
    id: c.id,
    startMs: c.startMs,
    endMs: c.endMs,
    text: c.text,
    translatedText: c.translatedText,
    sourceLanguage: c.sourceLanguage,
    targetLanguage,
    localSpeakerSlot: c.localSpeakerSlot,
    speakerRole: c.speakerRole,
    styleVariant: c.styleVariant,
    sceneId: c.sceneId,
    evidenceCueIds: c.evidenceCueIds,
    uncertaintyNotes: c.uncertaintyNotes,
  }));

  return {
    token: { ...token },
    createdAtMs: Date.now(),
    producer: 'gemini_scout1',
    cues,
    hasAudioEvidence,
    hasVisualEvidence,
    confirmedSilence: response.confirmedSilence && cues.length === 0 && hasAudioEvidence,
    notes: response.notes,
  };
}
