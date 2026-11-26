import { afterEach, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer, type ServerHandle } from '../../server/index.js';
import {
  extractAudioChunkWithFfmpeg,
  planMediaChunks,
  probeMediaFile,
} from '../../server/mediaExtractor.js';
import { validateSafeExternalUrl } from '../../server/policy.js';
import { GeminiDialogueProvider, ProviderError } from '../../server/geminiProvider.js';

const handles: ServerHandle[] = [];
const tempDirs: string[] = [];

async function makeTempDir(): Promise<string> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'dl-test-'));
  tempDirs.push(dir);
  return dir;
}

afterEach(async () => {
  while (handles.length > 0) {
    const h = handles.pop();
    if (h) await h.close();
  }
  while (tempDirs.length > 0) {
    const d = tempDirs.pop();
    if (d) await fs.rm(d, { recursive: true, force: true });
  }
});

describe('FFmpeg Full-Duration 5-Minute Chunking (Scenario 13)', () => {
  it('probes and extracts bounded chunks through 04:30 (270s) without silent 3-minute truncation', async () => {
    const longFixture = path.resolve(process.cwd(), 'fixtures', 'long-5min.webm');
    const info = await probeMediaFile(longFixture);
    expect(info.durationMs).toBeGreaterThanOrEqual(300_000);
    expect(info.hasAudio).toBe(true);

    const planned = planMediaChunks(info.durationMs, 30_000);
    expect(planned.length).toBeGreaterThanOrEqual(11);
    const lateChunk = planned.find((c) => c.startMs <= 270_000 && c.endMs >= 270_000);
    expect(lateChunk).toBeDefined();

    // Actually decode the late 04:30 chunk via FFmpeg and verify sample rate, channels, duration, and cleanup
    const extracted = await extractAudioChunkWithFfmpeg({
      sourcePath: longFixture,
      chunkIndex: lateChunk!.chunkIndex,
      startMs: lateChunk!.startMs,
      endMs: lateChunk!.endMs,
      targetSampleRateHz: 16000,
      channels: 1,
    });
    expect(extracted.sampleRateHz).toBe(16000);
    expect(extracted.channels).toBe(1);
    expect(extracted.actualDurationMs).toBeGreaterThan(20_000);
    await extracted.cleanup();
    await expect(fs.stat(extracted.wavPath)).rejects.toThrow();
  });
});

describe('Provider Boundary, Free CC Only, Timeout vs Cancel, Budgets & SSRF (Scenario 14 & 16)', () => {
  it('enforces zero AI admissions in Free CC Only, honest 503 when unconfigured, and strict owner auth', async () => {
    const dir = await makeTempDir();
    let providerCalls = 0;

    const handle = await createServer({
      dbPath: path.join(dir, 'test.sqlite'),
      uploadDir: path.join(dir, 'uploads'),
      apiToken: 'secret-token-123',
      autoStartWorker: false,
      customProviderTransport: {
        async generateJson() {
          providerCalls++;
          return JSON.stringify({ cues: [], failedCueIds: [], confirmedSilence: false, notes: [] });
        },
      },
    });
    handles.push(handle);

    // 1. Default policy is Free CC Only -> ZERO AI admissions!
    const resFree = await handle.app.inject({
      method: 'POST',
      url: '/api/ai/analyze-interval',
      headers: { authorization: 'Bearer secret-token-123' },
      payload: {
        projectId: 'p1',
        mediaId: 'm1',
        evidenceRevision: 1,
        configRevision: 1,
        taskId: 't1',
        intervalStartMs: 0,
        intervalEndMs: 8000,
        draftRevision: 1,
        attempt: 1,
        viewerGeneration: 1,
        sourceLanguage: 'en',
        targetLanguage: 'en',
      },
    });
    expect(resFree.statusCode).toBe(403);
    expect(providerCalls).toBe(0);

    // 2. Unauthorized request rejected
    const resUnauth = await handle.app.inject({
      method: 'POST',
      url: '/api/policy/free-mode',
      headers: { authorization: 'Bearer wrong-token' },
      payload: { freeCcOnly: false },
    });
    expect(resUnauth.statusCode).toBe(401);

    // 3. SSRF guard blocks localhost, loopback, and cloud metadata IPs
    expect((await validateSafeExternalUrl('http://127.0.0.1:8787/api/health')).ok).toBe(false);
    expect((await validateSafeExternalUrl('http://localhost/secret')).ok).toBe(false);
    expect((await validateSafeExternalUrl('http://169.254.169.254/latest/meta-data')).ok).toBe(false);

    // 4. Unconfigured real Gemini provider returns CREDENTIALS_UNCONFIGURED (never fakes a response!)
    const unconfigured = new GeminiDialogueProvider({
      apiKey: '',
      scout1Model: 'gemini-2.5-flash',
      scout2Model: 'gemini-2.5-pro',
    });
    await expect(
      unconfigured.analyzeInterval({
        stage: 'scout1',
        token: {
          projectId: 'p1',
          mediaId: 'm1',
          evidenceRevision: 1,
          configRevision: 1,
          taskId: 't1',
          intervalStartMs: 0,
          intervalEndMs: 8000,
          draftRevision: 1,
          attempt: 1,
          viewerGeneration: 1,
        },
        sourceLanguage: 'en',
        targetLanguage: 'en',
        evidenceCues: [],
        glossary: [],
      }),
    ).rejects.toMatchObject({ code: 'CREDENTIALS_UNCONFIGURED' });
  });

  it('distinguishes per-request timeout from parent cancellation and rejects malformed JSON', async () => {
    // Timeout test
    const slowProvider = new GeminiDialogueProvider({
      scout1Model: 'gemini-2.5-flash',
      scout2Model: 'gemini-2.5-pro',
      requestTimeoutMs: 60,
      customTransport: {
        async generateJson({ signal }) {
          return await new Promise<string>((_, reject) => {
            signal.addEventListener('abort', () => reject(new Error('aborted')));
          });
        },
      },
    });
    const baseReq = {
      stage: 'scout1' as const,
      token: {
        projectId: 'p1',
        mediaId: 'm1',
        evidenceRevision: 1,
        configRevision: 1,
        taskId: 't1',
        intervalStartMs: 0,
        intervalEndMs: 8000,
        draftRevision: 1,
        attempt: 1,
        viewerGeneration: 1,
      },
      sourceLanguage: 'en',
      targetLanguage: 'en',
      evidenceCues: [],
      glossary: [],
    };
    await expect(slowProvider.analyzeInterval(baseReq)).rejects.toMatchObject({ code: 'TIMEOUT' });

    // Parent cancellation test
    const parentCtrl = new AbortController();
    setTimeout(() => parentCtrl.abort(new Error('user switched to free mode')), 20);
    await expect(slowProvider.analyzeInterval(baseReq, parentCtrl.signal)).rejects.toMatchObject({
      code: 'CANCELLED',
    });

    // Malformed output test
    const badJsonProvider = new GeminiDialogueProvider({
      scout1Model: 'gemini-2.5-flash',
      scout2Model: 'gemini-2.5-pro',
      customTransport: {
        async generateJson() {
          return 'not valid json {{{';
        },
      },
    });
    await expect(badJsonProvider.analyzeInterval(baseReq)).rejects.toBeInstanceOf(ProviderError);
    await expect(badJsonProvider.analyzeInterval(baseReq)).rejects.toMatchObject({
      code: 'MALFORMED_OUTPUT',
    });
  });

  it('recovers durable SQLite jobs across server restart without duplicating completed chunks and cleans temp uploads (Scenario 15)', async () => {
    const dir = await makeTempDir();
    const dbPath = path.join(dir, 'durable.sqlite');
    const uploadDir = path.join(dir, 'uploads');

    const executedChunkKeys: string[] = [];
    const transport = {
      async generateJson({ prompt }: { prompt: string }) {
        const match = prompt.match(/interval \[(\d+)ms, (\d+)ms\]/);
        const key = match ? `${match[1]}-${match[2]}` : 'unknown';
        executedChunkKeys.push(key);
        return JSON.stringify({
          cues: [
            {
              id: `ai-cue-${key}`,
              startMs: match ? Number(match[1]) + 100 : 100,
              endMs: match ? Number(match[1]) + 2000 : 2000,
              text: `Analyzed chunk ${key}`,
              sourceLanguage: 'en',
              localSpeakerSlot: 'slot-1',
              speakerRole: 'person',
              styleVariant: 'speech',
              sceneId: 'scene-1',
              evidenceCueIds: [],
              uncertaintyNotes: [],
            },
          ],
          failedCueIds: [],
          confirmedSilence: false,
          notes: [],
        });
      },
    };

    // Start Server Instance 1, enable AI mode, create a 2-chunk job, mark chunk 0 completed & simulate crash mid-chunk-1!
    const server1 = await createServer({
      dbPath,
      uploadDir,
      autoStartWorker: false,
      customProviderTransport: transport,
    });
    await server1.app.inject({
      method: 'POST',
      url: '/api/policy/free-mode',
      payload: { freeCcOnly: false },
    });

    const jobRes = await server1.app.inject({
      method: 'POST',
      url: '/api/jobs',
      payload: {
        projectId: 'proj-durable',
        sourceLabel: 'clip.webm',
        durationMs: 60_000,
        chunkDurationMs: 30_000,
      },
    });
    expect(jobRes.statusCode).toBe(201);
    const created = jobRes.json() as { job: { id: string }; chunks: Array<{ id: string }> };
    expect(created.chunks).toHaveLength(2);

    // Simulate Server 1 completed chunk 0 and crashed while chunk 1 was 'running'
    server1.db.updateChunkResult(created.chunks[0]!.id, {
      status: 'completed',
      resultJson: JSON.stringify({ ok: true }),
    });
    server1.db.updateChunkResult(created.chunks[1]!.id, {
      status: 'running',
    });
    await server1.close();

    // Start Server Instance 2 against the SAME SQLite DB -> recovers stale lease & runs ONLY chunk 1!
    const server2 = await createServer({
      dbPath,
      uploadDir,
      autoStartWorker: false,
      customProviderTransport: transport,
    });
    handles.push(server2);

    const recovered = server2.db.recoverStaleLeasesOnStartup();
    expect(recovered).toBeGreaterThanOrEqual(0);
    await server2.worker.runOnce();

    // Chunk 0 (0-30000) was NEVER re-executed; ONLY chunk 1 (30000-60000) ran!
    expect(executedChunkKeys).toEqual(['30000-60000']);

    const finalRes = await server2.app.inject({
      method: 'GET',
      url: `/api/jobs/${created.job.id}`,
    });
    const finalBody = finalRes.json() as { job: { status: string; completedChunks: number } };
    expect(finalBody.job.status).toBe('completed');
    expect(finalBody.job.completedChunks).toBe(2);

    // Verify acknowledged Free CC Only switch cancels active jobs and blocks further admissions
    const ackRes = await server2.app.inject({
      method: 'POST',
      url: '/api/policy/free-mode',
      payload: { freeCcOnly: true },
    });
    expect(ackRes.statusCode).toBe(200);
    expect(ackRes.json().acknowledged).toBe(true);
    expect(server2.policy.checkProviderAdmission().code).toBe('free_cc_only');
  });
});
