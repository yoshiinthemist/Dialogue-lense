import fs from 'node:fs/promises';
import crypto from 'node:crypto';
import { ProviderError, convertProviderResponseToDraft, } from './geminiProvider.js';
import { extractAudioChunkWithFfmpeg } from './mediaExtractor.js';
import { runScout2IndependentReview } from '../shared/scoutPipeline.js';
/**
 * Durable SQLite-backed background worker for uploaded media analysis jobs.
 * - Recovers expired leases after restart without re-running already-completed chunks
 * - Checks PolicyCoordinator immediately before EVERY provider dispatch and retry
 * - Respects Free CC Only and per-job cancellation immediately via AbortController
 * - Preserves partial chunk successes and records real gaps for failed chunks
 * - Cleans up uploaded temporary media files once a job terminates (completed/cancelled/failed)
 */
export class DurableJobWorker {
    options;
    workerId;
    pollIntervalMs;
    leaseDurationMs;
    timer = null;
    isProcessing = false;
    stopped = false;
    constructor(options) {
        this.options = options;
        this.workerId = options.workerId ?? `worker-${crypto.randomUUID().slice(0, 8)}`;
        this.pollIntervalMs = options.pollIntervalMs ?? 350;
        this.leaseDurationMs = options.leaseDurationMs ?? 30_000;
    }
    start() {
        this.stopped = false;
        const recovered = this.options.db.recoverStaleLeasesOnStartup();
        this.scheduleNextTick();
        return recovered;
    }
    async stop() {
        this.stopped = true;
        if (this.timer) {
            clearTimeout(this.timer);
            this.timer = null;
        }
        // Wait briefly if a tick is actively running
        const deadline = Date.now() + 3000;
        while (this.isProcessing && Date.now() < deadline) {
            await new Promise((r) => setTimeout(r, 40));
        }
    }
    /** Executes a single worker poll cycle (useful for deterministic server tests). */
    async runOnce() {
        if (this.isProcessing)
            return false;
        const job = this.options.db.acquireNextLeasedJob(this.workerId, this.leaseDurationMs);
        if (!job)
            return false;
        this.isProcessing = true;
        try {
            await this.processLeasedJob(job);
            return true;
        }
        finally {
            this.isProcessing = false;
        }
    }
    scheduleNextTick() {
        if (this.stopped)
            return;
        this.timer = setTimeout(async () => {
            try {
                await this.runOnce();
            }
            catch {
                // Keep worker loop resilient
            }
            finally {
                this.scheduleNextTick();
            }
        }, this.pollIntervalMs);
    }
    async processLeasedJob(job) {
        const abortKey = `job:${job.id}`;
        const controller = this.options.policy.registerAbortController(abortKey);
        try {
            const upload = job.uploadId ? this.options.db.getUpload(job.uploadId) : null;
            const chunks = this.options.db.getJobChunks(job.id);
            for (const chunk of chunks) {
                if (this.stopped || controller.signal.aborted) {
                    this.options.db.updateChunkResult(chunk.id, {
                        status: 'cancelled',
                        errorMessage: 'Job cancelled before chunk dispatch',
                    });
                    continue;
                }
                // Skip already-completed chunks on restart recovery (idempotent!)
                if (chunk.status === 'completed') {
                    continue;
                }
                // Check DB status in case job was cancelled while running
                const freshJob = this.options.db.getJob(job.id);
                if (!freshJob || freshJob.status === 'cancelling' || freshJob.status === 'cancelled') {
                    this.options.db.updateChunkResult(chunk.id, {
                        status: 'cancelled',
                        errorMessage: 'Cancelled by policy or user',
                    });
                    continue;
                }
                // Re-check policy & budget IMMEDIATELY before EVERY chunk attempt
                const admission = this.options.policy.checkProviderAdmission(job.policyRevisionAtCreation);
                if (!admission.allowed) {
                    this.options.db.updateChunkResult(chunk.id, {
                        status: admission.code === 'free_cc_only' || admission.code === 'stale_policy_revision' ? 'cancelled' : 'failed',
                        errorMessage: admission.reason,
                    });
                    if (admission.code === 'free_cc_only' || admission.code === 'stale_policy_revision') {
                        this.options.db.finalizeJobProgress(job.id, 'cancelled', admission.reason);
                        await this.cleanupUploadIfPresent(job.uploadId);
                        return;
                    }
                    continue;
                }
                this.options.db.renewLease(job.id, this.workerId, this.leaseDurationMs);
                await this.processSingleChunk(job, chunk, upload?.storagePath ?? null, controller.signal);
            }
            const finalJob = this.options.db.finalizeJobProgress(job.id);
            if (finalJob &&
                (finalJob.status === 'completed' ||
                    finalJob.status === 'cancelled' ||
                    finalJob.status === 'failed')) {
                await this.cleanupUploadIfPresent(job.uploadId);
            }
        }
        finally {
            this.options.policy.releaseAbortController(abortKey);
        }
    }
    async processSingleChunk(job, chunk, sourcePath, signal) {
        this.options.db.updateChunkResult(chunk.id, {
            status: 'running',
            incrementAttempts: true,
        });
        if (this.options.testChunkDelayMs && this.options.testChunkDelayMs > 0) {
            await new Promise((resolve) => setTimeout(resolve, this.options.testChunkDelayMs));
        }
        let extractedCleanup = null;
        try {
            let audioBase64;
            let sampleRateHz = null;
            let channels = null;
            if (sourcePath) {
                const extracted = await extractAudioChunkWithFfmpeg({
                    sourcePath,
                    chunkIndex: chunk.chunkIndex,
                    startMs: chunk.startMs,
                    endMs: chunk.endMs,
                    signal,
                });
                extractedCleanup = extracted.cleanup;
                sampleRateHz = extracted.sampleRateHz;
                channels = extracted.channels;
                const wavBytes = await fs.readFile(extracted.wavPath);
                audioBase64 = wavBytes.toString('base64');
            }
            // Check policy right before actual provider call (after FFmpeg extraction finished!)
            const preCallAdmission = this.options.policy.checkProviderAdmission(job.policyRevisionAtCreation);
            if (!preCallAdmission.allowed) {
                this.options.db.updateChunkResult(chunk.id, {
                    status: preCallAdmission.code === 'free_cc_only' ? 'cancelled' : 'failed',
                    sampleRateHz,
                    channels,
                    errorMessage: preCallAdmission.reason,
                });
                return;
            }
            this.options.db.incrementSessionRequests(job.id, `chunk:${chunk.chunkIndex}:scout1`);
            const token = {
                projectId: job.projectId,
                mediaId: job.uploadId ?? job.id,
                evidenceRevision: 1,
                configRevision: job.policyRevisionAtCreation,
                taskId: `${job.id}:chunk-${chunk.chunkIndex}`,
                intervalStartMs: chunk.startMs,
                intervalEndMs: chunk.endMs,
                draftRevision: chunk.attempts + 1,
                attempt: chunk.attempts + 1,
                viewerGeneration: 1,
            };
            const scout1Result = await this.options.provider.analyzeInterval({
                stage: 'scout1',
                token,
                sourceLanguage: 'en',
                targetLanguage: 'en',
                evidenceCues: [],
                glossary: [],
                audioBase64,
                audioMimeType: audioBase64 ? 'audio/wav' : undefined,
            }, signal);
            this.options.db.recordProviderOutcome(true);
            const draft = convertProviderResponseToDraft(scout1Result.parsed, token, 'en', Boolean(audioBase64), false);
            // Run deterministic Scout 2 review check on the returned draft
            const { outcome } = runScout2IndependentReview({
                draft,
                originalEvidenceCues: [],
                projectSpeakers: [],
                expectedSourceLanguage: 'en',
                targetLanguage: 'en',
                clipDurationMs: job.durationMs,
                providerFailedCueIds: scout1Result.parsed.failedCueIds,
            });
            this.options.db.updateChunkResult(chunk.id, {
                status: outcome.failedCueIds.length > 0 ? 'partial' : 'completed',
                sampleRateHz,
                channels,
                resultJson: JSON.stringify({
                    modelUsed: scout1Result.modelUsed,
                    latencyMs: scout1Result.latencyMs,
                    outcome,
                }),
                errorMessage: null,
            });
        }
        catch (err) {
            const isCancelled = signal.aborted || (err instanceof ProviderError && err.code === 'CANCELLED');
            const message = err instanceof Error ? err.message : String(err);
            if (!isCancelled) {
                this.options.db.recordProviderOutcome(false, message, this.options.policy.getConfig().circuitFailureThreshold);
            }
            this.options.db.updateChunkResult(chunk.id, {
                status: isCancelled ? 'cancelled' : 'failed',
                errorMessage: message,
            });
        }
        finally {
            if (extractedCleanup) {
                await extractedCleanup();
            }
        }
    }
    async cleanupUploadIfPresent(uploadId) {
        if (!uploadId)
            return;
        const upload = this.options.db.getUpload(uploadId);
        if (!upload || upload.cleanedUpAtMs)
            return;
        await fs.rm(upload.storagePath, { force: true }).catch(() => undefined);
        this.options.db.markUploadCleanedUp(uploadId);
    }
}
