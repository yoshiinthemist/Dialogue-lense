import Fastify, { type FastifyInstance } from 'fastify';
import multipart from '@fastify/multipart';
import fastifyStatic from '@fastify/static';
import crypto from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { z } from 'zod';
import { DurableJobDatabase } from './db.js';
import { PolicyCoordinator, validateSafeExternalUrl } from './policy.js';
import {
  GeminiDialogueProvider,
  ProviderError,
  convertProviderResponseToDraft,
  type ProviderTransport,
} from './geminiProvider.js';
import { DurableJobWorker } from './jobWorker.js';
import { planMediaChunks, probeMediaFile } from './mediaExtractor.js';
import { runScout2IndependentReview } from '../shared/scoutPipeline.js';
import type { AnalysisJobRecord, EvidenceCue, JobChunkRecord, SpeakerProfile } from '../shared/types.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
// Works cleanly both when running via `tsx server/index.ts` and built `dist-server/server/index.js`
const PROJECT_ROOT = fs.existsSync(path.resolve(__dirname, '../package.json'))
  ? path.resolve(__dirname, '..')
  : path.resolve(__dirname, '../..');

export interface ServerOptions {
  port?: number;
  host?: string;
  dbPath?: string;
  uploadDir?: string;
  apiToken?: string;
  geminiApiKey?: string;
  scout1Model?: string;
  scout2Model?: string;
  sessionRequestBudget?: number;
  circuitFailureThreshold?: number;
  requestTimeoutMs?: number;
  maxUploadBytes?: number;
  customProviderTransport?: ProviderTransport;
  autoStartWorker?: boolean;
  testChunkDelayMs?: number;
}

export interface ServerHandle {
  app: FastifyInstance;
  db: DurableJobDatabase;
  policy: PolicyCoordinator;
  worker: DurableJobWorker;
  close: () => Promise<void>;
}

const FreeModeBodySchema = z.object({
  freeCcOnly: z.boolean(),
});

const AnalyzeIntervalBodySchema = z.object({
  projectId: z.string().min(1),
  mediaId: z.string().min(1),
  evidenceRevision: z.number().int().nonnegative(),
  configRevision: z.number().int().nonnegative(),
  taskId: z.string().min(1),
  intervalStartMs: z.number().finite().nonnegative(),
  intervalEndMs: z.number().finite().positive(),
  draftRevision: z.number().int().positive(),
  attempt: z.number().int().positive(),
  viewerGeneration: z.number().int().nonnegative(),
  sourceLanguage: z.string().min(2).max(16),
  targetLanguage: z.string().min(2).max(16),
  clipDurationMs: z.number().finite().positive().nullable().default(null),
  evidenceCues: z.array(z.any()).default([]),
  glossary: z
    .array(
      z.object({
        sourceTerm: z.string(),
        preferredTranslation: z.string(),
        notes: z.string().optional(),
      }),
    )
    .default([]),
  speakers: z.array(z.any()).default([]),
});

const CreateJobBodySchema = z.object({
  projectId: z.string().min(1),
  uploadId: z.string().min(1).optional(),
  sourceLabel: z.string().min(1).default('Uploaded media'),
  durationMs: z.number().finite().positive().optional(),
  chunkDurationMs: z.number().int().min(5000).max(120_000).default(30_000),
});

const ProbeUrlBodySchema = z.object({
  url: z.string().url(),
});

export async function createServer(options: ServerOptions = {}): Promise<ServerHandle> {
  const dbPath =
    options.dbPath ??
    process.env.DATABASE_PATH ??
    path.join(PROJECT_ROOT, 'data', 'dialoguelens.sqlite');
  const uploadDir =
    options.uploadDir ??
    process.env.UPLOAD_DIR ??
    path.join(PROJECT_ROOT, 'data', 'uploads');
  const apiToken = options.apiToken ?? process.env.DIALOGUELENS_API_TOKEN ?? '';
  const geminiApiKey = options.geminiApiKey ?? process.env.GEMINI_API_KEY ?? '';
  const scout1Model = options.scout1Model ?? process.env.GEMINI_MODEL_SCOUT1 ?? 'gemini-2.5-flash';
  const scout2Model = options.scout2Model ?? process.env.GEMINI_MODEL_SCOUT2 ?? 'gemini-2.5-pro';
  const sessionRequestBudget =
    options.sessionRequestBudget ?? Number(process.env.AI_SESSION_REQUEST_BUDGET ?? 40);
  const circuitFailureThreshold = options.circuitFailureThreshold ?? 4;
  const maxUploadBytes =
    options.maxUploadBytes ?? Number(process.env.MAX_UPLOAD_MB ?? 250) * 1024 * 1024;

  await fsp.mkdir(uploadDir, { recursive: true });

  const db = new DurableJobDatabase(dbPath);
  const provider = new GeminiDialogueProvider({
    apiKey: geminiApiKey,
    scout1Model,
    scout2Model,
    requestTimeoutMs: options.requestTimeoutMs ?? 20_000,
    customTransport: options.customProviderTransport,
  });

  const policy = new PolicyCoordinator(db, {
    apiToken,
    sessionRequestBudget: Number.isFinite(sessionRequestBudget) ? sessionRequestBudget : 40,
    circuitFailureThreshold,
    scout1Model,
    scout2Model,
    providerName: 'Google Gemini (@google/genai)',
    credentialsConfigured: provider.isConfigured(),
  });

  const worker = new DurableJobWorker({
    db,
    policy,
    provider,
    testChunkDelayMs: options.testChunkDelayMs,
  });

  const app = Fastify({
    logger: false,
    bodyLimit: 4 * 1024 * 1024,
  });

  await app.register(multipart, {
    limits: {
      fileSize: maxUploadBytes,
      files: 1,
    },
  });

  // Static serving for reproducible fixtures
  const fixturesDir = path.join(PROJECT_ROOT, 'fixtures');
  if (fs.existsSync(fixturesDir)) {
    await app.register(fastifyStatic, {
      root: fixturesDir,
      prefix: '/fixtures/',
      decorateReply: false,
    });
  }

  // Static serving for built Vite client (production same-origin)
  const distDir = path.join(PROJECT_ROOT, 'dist');
  if (fs.existsSync(distDir)) {
    await app.register(fastifyStatic, {
      root: distDir,
      prefix: '/',
      decorateReply: true,
    });
  }

  // Health & version endpoint
  app.get('/api/health', async () => {
    const policyState = policy.getPublicPolicyState();
    return {
      ok: true,
      service: 'dialoguelens-web',
      version: '1.0.0',
      policyRevision: policyState.policyRevision,
      freeCcOnly: policyState.freeCcOnly,
      credentialsConfigured: policyState.credentialsConfigured,
      providerName: policyState.providerName,
      scout1Model: policyState.scout1Model,
      scout2Model: policyState.scout2Model,
    };
  });

  // Read authoritative backend policy state
  app.get('/api/policy', async () => {
    return policy.getPublicPolicyState();
  });

  // Authoritative acknowledged Free CC Only switch
  app.post('/api/policy/free-mode', async (req, reply) => {
    const auth = policy.verifyOwnerToken(req.headers.authorization);
    if (!auth.ok) {
      return reply.code(401).send({ error: 'Unauthorized API token.' });
    }
    const parsed = FreeModeBodySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'Invalid freeCcOnly payload.' });
    }
    const updated = policy.setFreeCcOnly(parsed.data.freeCcOnly);
    return {
      acknowledged: true,
      policy: updated,
    };
  });

  // Reset session budget / circuit breaker (explicit user action)
  app.post('/api/policy/reset-budget', async (req, reply) => {
    const auth = policy.verifyOwnerToken(req.headers.authorization);
    if (!auth.ok) {
      return reply.code(401).send({ error: 'Unauthorized API token.' });
    }
    db.resetBudgetAndCircuit();
    return { acknowledged: true, policy: policy.getPublicPolicyState() };
  });

  // Safe external URL capability probe (strictly SSRF-guarded, HEAD only, no unrestricted proxy!)
  app.post('/api/probe-url', async (req, reply) => {
    const parsed = ProbeUrlBodySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'Valid HTTP/HTTPS URL required.' });
    }
    const validated = await validateSafeExternalUrl(parsed.data.url);
    if (!validated.ok || !validated.url) {
      return reply.code(400).send({ error: validated.reason ?? 'Blocked URL.' });
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 6000);
    try {
      const response = await fetch(validated.url.toString(), {
        method: 'HEAD',
        redirect: 'manual',
        signal: controller.signal,
      });

      if (response.status >= 300 && response.status < 400) {
        const location = response.headers.get('location');
        if (location) {
          const nextUrl = new URL(location, validated.url).toString();
          const nextCheck = await validateSafeExternalUrl(nextUrl);
          if (!nextCheck.ok) {
            return reply.code(400).send({
              error: `Redirect target blocked: ${nextCheck.reason}`,
            });
          }
        }
      }

      return {
        reachable: response.ok,
        status: response.status,
        contentType: response.headers.get('content-type') ?? null,
        acceptRanges: response.headers.get('accept-ranges') ?? null,
        accessControlAllowOrigin: response.headers.get('access-control-allow-origin') ?? null,
      };
    } catch (err) {
      return reply.code(502).send({
        reachable: false,
        error: err instanceof Error ? err.message : 'HEAD probe failed',
      });
    } finally {
      clearTimeout(timeout);
    }
  });

  // Synchronous interval AI Scout 1 + Scout 2 endpoint (strictly gated by policy/credentials/budget)
  app.post('/api/ai/analyze-interval', async (req, reply) => {
    const auth = policy.verifyOwnerToken(req.headers.authorization);
    if (!auth.ok) {
      return reply.code(401).send({ error: 'Unauthorized API token.' });
    }

    const parsed = AnalyzeIntervalBodySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({
        error: 'Invalid analyze-interval request payload.',
        details: parsed.error.issues,
      });
    }

    const admission = policy.checkProviderAdmission();
    if (!admission.allowed) {
      const status =
        admission.code === 'free_cc_only'
          ? 403
          : admission.code === 'credentials_unconfigured'
            ? 503
            : admission.code === 'budget_exhausted' || admission.code === 'circuit_open'
              ? 429
              : 409;
      return reply.code(status).send({
        code: admission.code,
        error: admission.reason,
        policyRevision: admission.policyRevision,
      });
    }

    const body = parsed.data;
    const reqKey = `req:${body.taskId}:${crypto.randomUUID().slice(0, 8)}`;
    const abortCtrl = policy.registerAbortController(reqKey);

    try {
      db.incrementSessionRequests(null, `analyze-interval:${body.taskId}:scout1`);
      const token = {
        projectId: body.projectId,
        mediaId: body.mediaId,
        evidenceRevision: body.evidenceRevision,
        configRevision: body.configRevision,
        taskId: body.taskId,
        intervalStartMs: body.intervalStartMs,
        intervalEndMs: body.intervalEndMs,
        draftRevision: body.draftRevision,
        attempt: body.attempt,
        viewerGeneration: body.viewerGeneration,
      };

      const scout1Res = await provider.analyzeInterval(
        {
          stage: 'scout1',
          token,
          sourceLanguage: body.sourceLanguage,
          targetLanguage: body.targetLanguage,
          evidenceCues: body.evidenceCues as EvidenceCue[],
          glossary: body.glossary,
          existingSpeakers: body.speakers as SpeakerProfile[],
        },
        abortCtrl.signal,
      );

      // Re-check policy BEFORE Scout 2 dispatch (in case Free CC Only was enabled mid-flight)
      const secondAdmission = policy.checkProviderAdmission(admission.policyRevision);
      if (!secondAdmission.allowed) {
        return reply.code(409).send({
          code: secondAdmission.code,
          error: secondAdmission.reason,
          policyRevision: secondAdmission.policyRevision,
        });
      }

      const draft = convertProviderResponseToDraft(
        scout1Res.parsed,
        token,
        body.targetLanguage,
        false,
        false,
      );

      db.recordProviderOutcome(true);

      // Independent Scout 2 review against original evidence cues
      const { outcome, newSpeakers } = runScout2IndependentReview({
        draft,
        originalEvidenceCues: body.evidenceCues as EvidenceCue[],
        projectSpeakers: body.speakers as SpeakerProfile[],
        expectedSourceLanguage: body.sourceLanguage,
        targetLanguage: body.targetLanguage,
        clipDurationMs: body.clipDurationMs,
        providerFailedCueIds: scout1Res.parsed.failedCueIds,
      });

      return {
        modelUsed: scout1Res.modelUsed,
        latencyMs: scout1Res.latencyMs,
        policyRevision: admission.policyRevision,
        draft,
        outcome,
        newSpeakers,
      };
    } catch (err) {
      if (err instanceof ProviderError) {
        if (err.code !== 'CANCELLED') {
          db.recordProviderOutcome(false, err.message, policy.getConfig().circuitFailureThreshold);
        }
        const statusCode =
          err.code === 'CREDENTIALS_UNCONFIGURED'
            ? 503
            : err.code === 'CANCELLED'
              ? 499
              : err.code === 'TIMEOUT'
                ? 504
                : 502;
        return reply.code(statusCode).send({
          code: err.code,
          error: err.message,
        });
      }
      return reply.code(500).send({
        code: 'INTERNAL_ERROR',
        error: err instanceof Error ? err.message : 'Unexpected server error',
      });
    } finally {
      policy.releaseAbortController(reqKey);
    }
  });

  // Upload authorized local media for durable server analysis (only after explicit user consent)
  app.post('/api/uploads', async (req, reply) => {
    const auth = policy.verifyOwnerToken(req.headers.authorization);
    if (!auth.ok) {
      return reply.code(401).send({ error: 'Unauthorized API token.' });
    }
    const admission = policy.checkProviderAdmission();
    if (admission.code === 'free_cc_only') {
      return reply.code(403).send({
        code: admission.code,
        error: 'Cannot upload media for AI analysis while Free CC Only mode is active.',
      });
    }

    const file = await req.file();
    if (!file) {
      return reply.code(400).send({ error: 'Multipart media file field is required.' });
    }

    const projectIdField = file.fields.projectId;
    const projectId =
      projectIdField && 'value' in projectIdField && typeof projectIdField.value === 'string'
        ? projectIdField.value
        : 'default-project';

    const uploadId = `upl-${crypto.randomUUID()}`;
    const safeExt = path.extname(file.filename || '.webm').replace(/[^a-zA-Z0-9.]/g, '') || '.bin';
    const storagePath = path.join(uploadDir, `${uploadId}${safeExt}`);

    const writeStream = fs.createWriteStream(storagePath);
    let sizeBytes = 0;
    try {
      for await (const chunk of file.file) {
        sizeBytes += chunk.length;
        if (sizeBytes > maxUploadBytes) {
          writeStream.destroy();
          await fsp.rm(storagePath, { force: true });
          return reply.code(413).send({ error: 'Uploaded file exceeds server limit.' });
        }
        writeStream.write(chunk);
      }
      await new Promise<void>((resolve, reject) => {
        writeStream.end(() => resolve());
        writeStream.on('error', reject);
      });

      const probe = await probeMediaFile(storagePath).catch(() => null);

      db.insertUpload({
        id: uploadId,
        projectId,
        ownerTokenHash: auth.ownerHash,
        fileName: path.basename(file.filename || 'media'),
        mimeType: file.mimetype || 'application/octet-stream',
        sizeBytes,
        storagePath,
        createdAtMs: Date.now(),
        cleanedUpAtMs: null,
      });

      return reply.code(201).send({
        uploadId,
        fileName: path.basename(file.filename || 'media'),
        sizeBytes,
        probe,
      });
    } catch (err) {
      await fsp.rm(storagePath, { force: true }).catch(() => undefined);
      return reply.code(500).send({
        error: err instanceof Error ? err.message : 'Upload processing failed.',
      });
    }
  });

  // Create durable chunked analysis job
  app.post('/api/jobs', async (req, reply) => {
    const auth = policy.verifyOwnerToken(req.headers.authorization);
    if (!auth.ok) {
      return reply.code(401).send({ error: 'Unauthorized API token.' });
    }

    const admission = policy.checkProviderAdmission();
    if (!admission.allowed) {
      const code =
        admission.code === 'free_cc_only'
          ? 403
          : admission.code === 'credentials_unconfigured'
            ? 503
            : 429;
      return reply.code(code).send({
        code: admission.code,
        error: admission.reason,
        policyRevision: admission.policyRevision,
      });
    }

    const parsed = CreateJobBodySchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ error: 'Invalid job request.', details: parsed.error.issues });
    }

    let durationMs = parsed.data.durationMs ?? 30_000;
    if (parsed.data.uploadId) {
      const upload = db.getUpload(parsed.data.uploadId);
      if (!upload) {
        return reply.code(404).send({ error: 'Referenced uploadId not found.' });
      }
      if (upload.ownerTokenHash !== auth.ownerHash) {
        return reply.code(403).send({ error: 'Upload ownership mismatch.' });
      }
      const probe = await probeMediaFile(upload.storagePath).catch(() => null);
      if (probe && probe.durationMs > 0) {
        durationMs = probe.durationMs;
      }
    }

    const planned = planMediaChunks(durationMs, parsed.data.chunkDurationMs);
    const jobId = `job-${crypto.randomUUID()}`;
    const now = Date.now();

    const job: AnalysisJobRecord = {
      id: jobId,
      projectId: parsed.data.projectId,
      ownerTokenHash: auth.ownerHash,
      status: 'queued',
      policyRevisionAtCreation: admission.policyRevision,
      uploadId: parsed.data.uploadId ?? null,
      sourceLabel: parsed.data.sourceLabel,
      durationMs,
      chunkDurationMs: parsed.data.chunkDurationMs,
      totalChunks: planned.length,
      completedChunks: 0,
      failedChunks: 0,
      attempts: 0,
      maxAttempts: 3,
      leaseOwner: null,
      leaseExpiresAtMs: null,
      errorMessage: null,
      createdAtMs: now,
      updatedAtMs: now,
    };

    const chunks: JobChunkRecord[] = planned.map((p) => ({
      id: `${jobId}:chunk-${p.chunkIndex}`,
      jobId,
      chunkIndex: p.chunkIndex,
      startMs: p.startMs,
      endMs: p.endMs,
      sampleRateHz: null,
      channels: null,
      status: 'pending',
      attempts: 0,
      resultJson: null,
      errorMessage: null,
      updatedAtMs: now,
    }));

    db.createJobWithChunks(job, chunks);

    return reply.code(201).send({
      job,
      chunks,
    });
  });

  // List jobs for authenticated owner
  app.get('/api/jobs', async (req, reply) => {
    const auth = policy.verifyOwnerToken(req.headers.authorization);
    if (!auth.ok) {
      return reply.code(401).send({ error: 'Unauthorized API token.' });
    }
    const query = req.query as { projectId?: string };
    const jobs = db
      .listJobs(query.projectId)
      .filter((j) => j.ownerTokenHash === auth.ownerHash);
    return { jobs };
  });

  // Get durable job status + chunks
  app.get('/api/jobs/:id', async (req, reply) => {
    const auth = policy.verifyOwnerToken(req.headers.authorization);
    if (!auth.ok) {
      return reply.code(401).send({ error: 'Unauthorized API token.' });
    }
    const { id } = req.params as { id: string };
    const job = db.getJob(id);
    if (!job) {
      return reply.code(404).send({ error: 'Job not found.' });
    }
    if (job.ownerTokenHash !== auth.ownerHash) {
      return reply.code(403).send({ error: 'Job ownership mismatch.' });
    }
    const chunks = db.getJobChunks(id);
    return { job, chunks };
  });

  // Cancel durable job
  app.post('/api/jobs/:id/cancel', async (req, reply) => {
    const auth = policy.verifyOwnerToken(req.headers.authorization);
    if (!auth.ok) {
      return reply.code(401).send({ error: 'Unauthorized API token.' });
    }
    const { id } = req.params as { id: string };
    const existing = db.getJob(id);
    if (!existing) {
      return reply.code(404).send({ error: 'Job not found.' });
    }
    if (existing.ownerTokenHash !== auth.ownerHash) {
      return reply.code(403).send({ error: 'Job ownership mismatch.' });
    }
    const updated = db.requestCancelJob(id, 'Cancelled by client request');
    policy.abortKey(`job:${id}`, 'Cancelled by client request');
    return { acknowledged: true, job: updated };
  });

  // SPA fallback for client routes when dist/ exists
  app.setNotFoundHandler((req, reply) => {
    if (req.url.startsWith('/api/') || req.url.startsWith('/fixtures/')) {
      return reply.code(404).send({ error: 'Not found' });
    }
    const indexHtml = path.join(distDir, 'index.html');
    if (fs.existsSync(indexHtml)) {
      return reply.type('text/html').send(fs.readFileSync(indexHtml));
    }
    return reply.code(404).send({ error: 'Client build not found; run npm run build first.' });
  });

  if (options.autoStartWorker !== false) {
    worker.start();
  }

  return {
    app,
    db,
    policy,
    worker,
    close: async () => {
      await worker.stop();
      await app.close();
      db.close();
    },
  };
}

// Run directly when executed as main entrypoint
const isMainModule =
  typeof process.argv[1] === 'string' &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (isMainModule) {
  const port = Number(process.env.PORT ?? 8787);
  const host = process.env.HOST ?? '127.0.0.1';
  createServer({ port, host })
    .then(async (handle) => {
      await handle.app.listen({ port, host });
      console.log(`DialogueLens Web server listening at http://${host}:${port}`);
      const shutdown = async () => {
        await handle.close();
        process.exit(0);
      };
      process.on('SIGINT', shutdown);
      process.on('SIGTERM', shutdown);
    })
    .catch((err) => {
      console.error('Failed to start DialogueLens Web server:', err);
      process.exit(1);
    });
}
