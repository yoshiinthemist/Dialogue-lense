import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import type { AnalysisJobRecord, JobChunkRecord } from '../shared/types.js';

export interface UploadRecord {
  id: string;
  projectId: string;
  ownerTokenHash: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  storagePath: string;
  createdAtMs: number;
  cleanedUpAtMs: number | null;
}

export interface PersistedPolicyRow {
  id: number;
  policyRevision: number;
  freeCcOnly: boolean;
  sessionRequestsUsed: number;
  circuitOpen: boolean;
  circuitReason: string | null;
  consecutiveFailures: number;
  updatedAtMs: number;
}

export class DurableJobDatabase {
  private readonly db: DatabaseSync;
  public readonly dbPath: string;

  constructor(dbPath: string) {
    this.dbPath = dbPath;
    const dir = path.dirname(dbPath);
    if (dir && dir !== '.' && !fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    this.db = new DatabaseSync(dbPath);
    this.db.exec('PRAGMA journal_mode = WAL;');
    this.db.exec('PRAGMA foreign_keys = ON;');
    this.db.exec('PRAGMA busy_timeout = 5000;');
    this.runMigrations();
  }

  close(): void {
    this.db.close();
  }

  private runMigrations(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version INTEGER PRIMARY KEY,
        applied_at_ms INTEGER NOT NULL
      );
    `);

    const applied = new Set<number>();
    const rows = this.db.prepare('SELECT version FROM schema_migrations').all() as Array<{
      version: number;
    }>;
    for (const r of rows) {
      applied.add(r.version);
    }

    if (!applied.has(1)) {
      this.db.exec(`
        CREATE TABLE IF NOT EXISTS policy_state (
          id INTEGER PRIMARY KEY CHECK (id = 1),
          policy_revision INTEGER NOT NULL DEFAULT 1,
          free_cc_only INTEGER NOT NULL DEFAULT 1,
          session_requests_used INTEGER NOT NULL DEFAULT 0,
          circuit_open INTEGER NOT NULL DEFAULT 0,
          circuit_reason TEXT,
          consecutive_failures INTEGER NOT NULL DEFAULT 0,
          updated_at_ms INTEGER NOT NULL
        );

        INSERT OR IGNORE INTO policy_state (
          id, policy_revision, free_cc_only, session_requests_used, circuit_open, consecutive_failures, updated_at_ms
        ) VALUES (1, 1, 1, 0, 0, 0, ${Date.now()});

        CREATE TABLE IF NOT EXISTS uploads (
          id TEXT PRIMARY KEY,
          project_id TEXT NOT NULL,
          owner_token_hash TEXT NOT NULL,
          file_name TEXT NOT NULL,
          mime_type TEXT NOT NULL,
          size_bytes INTEGER NOT NULL,
          storage_path TEXT NOT NULL,
          created_at_ms INTEGER NOT NULL,
          cleaned_up_at_ms INTEGER
        );

        CREATE TABLE IF NOT EXISTS analysis_jobs (
          id TEXT PRIMARY KEY,
          project_id TEXT NOT NULL,
          owner_token_hash TEXT NOT NULL,
          status TEXT NOT NULL,
          policy_revision_at_creation INTEGER NOT NULL,
          upload_id TEXT,
          source_label TEXT NOT NULL,
          duration_ms INTEGER NOT NULL,
          chunk_duration_ms INTEGER NOT NULL,
          total_chunks INTEGER NOT NULL,
          completed_chunks INTEGER NOT NULL DEFAULT 0,
          failed_chunks INTEGER NOT NULL DEFAULT 0,
          attempts INTEGER NOT NULL DEFAULT 0,
          max_attempts INTEGER NOT NULL DEFAULT 3,
          lease_owner TEXT,
          lease_expires_at_ms INTEGER,
          error_message TEXT,
          created_at_ms INTEGER NOT NULL,
          updated_at_ms INTEGER NOT NULL,
          FOREIGN KEY (upload_id) REFERENCES uploads(id)
        );

        CREATE TABLE IF NOT EXISTS job_chunks (
          id TEXT PRIMARY KEY,
          job_id TEXT NOT NULL,
          chunk_index INTEGER NOT NULL,
          start_ms INTEGER NOT NULL,
          end_ms INTEGER NOT NULL,
          sample_rate_hz INTEGER,
          channels INTEGER,
          status TEXT NOT NULL,
          attempts INTEGER NOT NULL DEFAULT 0,
          result_json TEXT,
          error_message TEXT,
          updated_at_ms INTEGER NOT NULL,
          UNIQUE(job_id, chunk_index),
          FOREIGN KEY (job_id) REFERENCES analysis_jobs(id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS budget_events (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          event_type TEXT NOT NULL,
          job_id TEXT,
          detail TEXT,
          created_at_ms INTEGER NOT NULL
        );

        INSERT INTO schema_migrations (version, applied_at_ms) VALUES (1, ${Date.now()});
      `);
    }
  }

  getPolicyState(): PersistedPolicyRow {
    const row = this.db.prepare('SELECT * FROM policy_state WHERE id = 1').get() as Record<
      string,
      unknown
    >;
    return {
      id: 1,
      policyRevision: Number(row.policy_revision),
      freeCcOnly: Boolean(row.free_cc_only),
      sessionRequestsUsed: Number(row.session_requests_used),
      circuitOpen: Boolean(row.circuit_open),
      circuitReason: row.circuit_reason ? String(row.circuit_reason) : null,
      consecutiveFailures: Number(row.consecutive_failures),
      updatedAtMs: Number(row.updated_at_ms),
    };
  }

  updatePolicyFreeMode(freeCcOnly: boolean): PersistedPolicyRow {
    const now = Date.now();
    this.db
      .prepare(
        `UPDATE policy_state
         SET free_cc_only = ?, policy_revision = policy_revision + 1, updated_at_ms = ?
         WHERE id = 1`,
      )
      .run(freeCcOnly ? 1 : 0, now);

    if (freeCcOnly) {
      // Mark all active/queued jobs as cancelling immediately so zero new admissions occur
      this.db
        .prepare(
          `UPDATE analysis_jobs
           SET status = 'cancelling', error_message = 'Cancelled by Free CC Only policy switch', updated_at_ms = ?
           WHERE status IN ('queued', 'extracting', 'running')`,
        )
        .run(now);
      this.db
        .prepare(
          `UPDATE job_chunks
           SET status = 'cancelled', updated_at_ms = ?
           WHERE status IN ('pending', 'running')
             AND job_id IN (SELECT id FROM analysis_jobs WHERE status = 'cancelling')`,
        )
        .run(now);
    }

    return this.getPolicyState();
  }

  incrementSessionRequests(jobId: string | null, detail: string): PersistedPolicyRow {
    const now = Date.now();
    this.db
      .prepare(
        `UPDATE policy_state SET session_requests_used = session_requests_used + 1, updated_at_ms = ? WHERE id = 1`,
      )
      .run(now);
    this.db
      .prepare(
        `INSERT INTO budget_events (event_type, job_id, detail, created_at_ms) VALUES ('provider_request', ?, ?, ?)`,
      )
      .run(jobId, detail, now);
    return this.getPolicyState();
  }

  recordProviderOutcome(success: boolean, failureReason?: string, circuitThreshold = 4): PersistedPolicyRow {
    const now = Date.now();
    if (success) {
      this.db
        .prepare(
          `UPDATE policy_state
           SET consecutive_failures = 0, circuit_open = 0, circuit_reason = NULL, updated_at_ms = ?
           WHERE id = 1`,
        )
        .run(now);
    } else {
      const current = this.getPolicyState();
      const nextFailures = current.consecutiveFailures + 1;
      const openCircuit = nextFailures >= circuitThreshold;
      this.db
        .prepare(
          `UPDATE policy_state
           SET consecutive_failures = ?, circuit_open = ?, circuit_reason = ?, updated_at_ms = ?
           WHERE id = 1`,
        )
        .run(
          nextFailures,
          openCircuit ? 1 : 0,
          openCircuit ? (failureReason ?? 'Repeated provider failures tripped circuit breaker') : null,
          now,
        );
    }
    return this.getPolicyState();
  }

  resetBudgetAndCircuit(): PersistedPolicyRow {
    const now = Date.now();
    this.db
      .prepare(
        `UPDATE policy_state
         SET session_requests_used = 0, consecutive_failures = 0, circuit_open = 0, circuit_reason = NULL, updated_at_ms = ?
         WHERE id = 1`,
      )
      .run(now);
    return this.getPolicyState();
  }

  insertUpload(upload: UploadRecord): void {
    this.db
      .prepare(
        `INSERT INTO uploads (id, project_id, owner_token_hash, file_name, mime_type, size_bytes, storage_path, created_at_ms, cleaned_up_at_ms)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        upload.id,
        upload.projectId,
        upload.ownerTokenHash,
        upload.fileName,
        upload.mimeType,
        upload.sizeBytes,
        upload.storagePath,
        upload.createdAtMs,
        upload.cleanedUpAtMs,
      );
  }

  getUpload(uploadId: string): UploadRecord | null {
    const row = this.db.prepare('SELECT * FROM uploads WHERE id = ?').get(uploadId) as
      | Record<string, unknown>
      | undefined;
    if (!row) return null;
    return {
      id: String(row.id),
      projectId: String(row.project_id),
      ownerTokenHash: String(row.owner_token_hash),
      fileName: String(row.file_name),
      mimeType: String(row.mime_type),
      sizeBytes: Number(row.size_bytes),
      storagePath: String(row.storage_path),
      createdAtMs: Number(row.created_at_ms),
      cleanedUpAtMs: row.cleaned_up_at_ms !== null ? Number(row.cleaned_up_at_ms) : null,
    };
  }

  markUploadCleanedUp(uploadId: string): void {
    this.db
      .prepare('UPDATE uploads SET cleaned_up_at_ms = ? WHERE id = ?')
      .run(Date.now(), uploadId);
  }

  createJobWithChunks(job: AnalysisJobRecord, chunks: JobChunkRecord[]): void {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      this.db
        .prepare(
          `INSERT INTO analysis_jobs (
            id, project_id, owner_token_hash, status, policy_revision_at_creation,
            upload_id, source_label, duration_ms, chunk_duration_ms,
            total_chunks, completed_chunks, failed_chunks, attempts, max_attempts,
            lease_owner, lease_expires_at_ms, error_message, created_at_ms, updated_at_ms
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
          job.id,
          job.projectId,
          job.ownerTokenHash,
          job.status,
          job.policyRevisionAtCreation,
          job.uploadId,
          job.sourceLabel,
          job.durationMs,
          job.chunkDurationMs,
          job.totalChunks,
          job.completedChunks,
          job.failedChunks,
          job.attempts,
          job.maxAttempts,
          job.leaseOwner,
          job.leaseExpiresAtMs,
          job.errorMessage,
          job.createdAtMs,
          job.updatedAtMs,
        );

      const insertChunk = this.db.prepare(
        `INSERT INTO job_chunks (
          id, job_id, chunk_index, start_ms, end_ms, sample_rate_hz, channels,
          status, attempts, result_json, error_message, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      );
      for (const ch of chunks) {
        insertChunk.run(
          ch.id,
          ch.jobId,
          ch.chunkIndex,
          ch.startMs,
          ch.endMs,
          ch.sampleRateHz,
          ch.channels,
          ch.status,
          ch.attempts,
          ch.resultJson,
          ch.errorMessage,
          ch.updatedAtMs,
        );
      }
      this.db.exec('COMMIT');
    } catch (err) {
      this.db.exec('ROLLBACK');
      throw err;
    }
  }

  getJob(jobId: string): AnalysisJobRecord | null {
    const row = this.db.prepare('SELECT * FROM analysis_jobs WHERE id = ?').get(jobId) as
      | Record<string, unknown>
      | undefined;
    return row ? mapJobRow(row) : null;
  }

  listJobs(projectId?: string): AnalysisJobRecord[] {
    const rows = (
      projectId
        ? this.db
            .prepare('SELECT * FROM analysis_jobs WHERE project_id = ? ORDER BY created_at_ms DESC')
            .all(projectId)
        : this.db.prepare('SELECT * FROM analysis_jobs ORDER BY created_at_ms DESC').all()
    ) as Array<Record<string, unknown>>;
    return rows.map(mapJobRow);
  }

  getJobChunks(jobId: string): JobChunkRecord[] {
    const rows = this.db
      .prepare('SELECT * FROM job_chunks WHERE job_id = ? ORDER BY chunk_index ASC')
      .all(jobId) as Array<Record<string, unknown>>;
    return rows.map(mapChunkRow);
  }

  countActiveAndCancellingJobs(): { active: number; cancelling: number } {
    const activeRow = this.db
      .prepare(`SELECT COUNT(*) as c FROM analysis_jobs WHERE status IN ('queued', 'extracting', 'running')`)
      .get() as { c: number };
    const cancellingRow = this.db
      .prepare(`SELECT COUNT(*) as c FROM analysis_jobs WHERE status = 'cancelling'`)
      .get() as { c: number };
    return { active: Number(activeRow.c), cancelling: Number(cancellingRow.c) };
  }

  acquireNextLeasedJob(workerId: string, leaseDurationMs: number): AnalysisJobRecord | null {
    const now = Date.now();
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const row = this.db
        .prepare(
          `SELECT * FROM analysis_jobs
           WHERE status IN ('queued', 'extracting', 'running')
             AND (lease_expires_at_ms IS NULL OR lease_expires_at_ms < ?)
           ORDER BY created_at_ms ASC
           LIMIT 1`,
        )
        .get(now) as Record<string, unknown> | undefined;

      if (!row) {
        this.db.exec('COMMIT');
        return null;
      }

      const jobId = String(row.id);
      this.db
        .prepare(
          `UPDATE analysis_jobs
           SET status = 'running', lease_owner = ?, lease_expires_at_ms = ?, attempts = attempts + 1, updated_at_ms = ?
           WHERE id = ?`,
        )
        .run(workerId, now + leaseDurationMs, now, jobId);

      const updated = this.db
        .prepare('SELECT * FROM analysis_jobs WHERE id = ?')
        .get(jobId) as Record<string, unknown>;
      this.db.exec('COMMIT');
      return mapJobRow(updated);
    } catch (err) {
      this.db.exec('ROLLBACK');
      throw err;
    }
  }

  renewLease(jobId: string, workerId: string, leaseDurationMs: number): void {
    const now = Date.now();
    this.db
      .prepare(
        `UPDATE analysis_jobs SET lease_expires_at_ms = ?, updated_at_ms = ?
         WHERE id = ? AND lease_owner = ?`,
      )
      .run(now + leaseDurationMs, now, jobId, workerId);
  }

  updateChunkResult(
    chunkId: string,
    patch: {
      status: JobChunkRecord['status'];
      sampleRateHz?: number | null;
      channels?: number | null;
      resultJson?: string | null;
      errorMessage?: string | null;
      incrementAttempts?: boolean;
    },
  ): void {
    const now = Date.now();
    const attemptExpr = patch.incrementAttempts ? 'attempts = attempts + 1,' : '';
    this.db
      .prepare(
        `UPDATE job_chunks
         SET status = ?,
             sample_rate_hz = COALESCE(?, sample_rate_hz),
             channels = COALESCE(?, channels),
             result_json = COALESCE(?, result_json),
             error_message = ?,
             ${attemptExpr}
             updated_at_ms = ?
         WHERE id = ?`,
      )
      .run(
        patch.status,
        patch.sampleRateHz ?? null,
        patch.channels ?? null,
        patch.resultJson ?? null,
        patch.errorMessage ?? null,
        now,
        chunkId,
      );
  }

  finalizeJobProgress(jobId: string, finalStatus?: AnalysisJobRecord['status'], errorMessage?: string | null): AnalysisJobRecord | null {
    const now = Date.now();
    const chunks = this.getJobChunks(jobId);
    const completed = chunks.filter((c) => c.status === 'completed').length;
    const failed = chunks.filter((c) => c.status === 'failed').length;
    const current = this.getJob(jobId);
    if (!current) return null;

    let status: AnalysisJobRecord['status'] = finalStatus ?? current.status;
    if (!finalStatus) {
      if (current.status === 'cancelling') {
        status = 'cancelled';
      } else if (completed === chunks.length) {
        status = 'completed';
      } else if (completed > 0 && failed > 0) {
        status = 'partial';
      } else if (failed === chunks.length) {
        status = 'failed';
      }
    }

    this.db
      .prepare(
        `UPDATE analysis_jobs
         SET status = ?, completed_chunks = ?, failed_chunks = ?, lease_owner = NULL, lease_expires_at_ms = NULL,
             error_message = COALESCE(?, error_message), updated_at_ms = ?
         WHERE id = ?`,
      )
      .run(status, completed, failed, errorMessage ?? null, now, jobId);

    return this.getJob(jobId);
  }

  requestCancelJob(jobId: string, reason = 'Cancelled by user'): AnalysisJobRecord | null {
    const now = Date.now();
    const job = this.getJob(jobId);
    if (!job) return null;
    if (job.status === 'completed' || job.status === 'cancelled' || job.status === 'failed') {
      return job;
    }
    const nextStatus = job.status === 'queued' ? 'cancelled' : 'cancelling';
    this.db
      .prepare(
        `UPDATE analysis_jobs SET status = ?, error_message = ?, updated_at_ms = ? WHERE id = ?`,
      )
      .run(nextStatus, reason, now, jobId);
    this.db
      .prepare(
        `UPDATE job_chunks SET status = 'cancelled', updated_at_ms = ?
         WHERE job_id = ? AND status IN ('pending', 'running')`,
      )
      .run(now, jobId);
    return this.getJob(jobId);
  }

  /**
   * Called on server startup to recover jobs whose leases expired during a crash/restart.
   * Pending chunks are preserved; already-completed chunks are NOT re-run.
   */
  recoverStaleLeasesOnStartup(): number {
    const now = Date.now();
    const res = this.db
      .prepare(
        `UPDATE analysis_jobs
         SET lease_owner = NULL, lease_expires_at_ms = NULL,
             status = CASE WHEN status = 'cancelling' THEN 'cancelled' ELSE 'queued' END,
             updated_at_ms = ?
         WHERE status IN ('extracting', 'running', 'cancelling')`,
      )
      .run(now);
    this.db
      .prepare(
        `UPDATE job_chunks SET status = 'pending', updated_at_ms = ?
         WHERE status = 'running' AND job_id IN (SELECT id FROM analysis_jobs WHERE status = 'queued')`,
      )
      .run(now);
    return Number(res.changes);
  }
}

function mapJobRow(row: Record<string, unknown>): AnalysisJobRecord {
  return {
    id: String(row.id),
    projectId: String(row.project_id),
    ownerTokenHash: String(row.owner_token_hash),
    status: String(row.status) as AnalysisJobRecord['status'],
    policyRevisionAtCreation: Number(row.policy_revision_at_creation),
    uploadId: row.upload_id ? String(row.upload_id) : null,
    sourceLabel: String(row.source_label),
    durationMs: Number(row.duration_ms),
    chunkDurationMs: Number(row.chunk_duration_ms),
    totalChunks: Number(row.total_chunks),
    completedChunks: Number(row.completed_chunks),
    failedChunks: Number(row.failed_chunks),
    attempts: Number(row.attempts),
    maxAttempts: Number(row.max_attempts),
    leaseOwner: row.lease_owner ? String(row.lease_owner) : null,
    leaseExpiresAtMs: row.lease_expires_at_ms !== null ? Number(row.lease_expires_at_ms) : null,
    errorMessage: row.error_message ? String(row.error_message) : null,
    createdAtMs: Number(row.created_at_ms),
    updatedAtMs: Number(row.updated_at_ms),
  };
}

function mapChunkRow(row: Record<string, unknown>): JobChunkRecord {
  return {
    id: String(row.id),
    jobId: String(row.job_id),
    chunkIndex: Number(row.chunk_index),
    startMs: Number(row.start_ms),
    endMs: Number(row.end_ms),
    sampleRateHz: row.sample_rate_hz !== null ? Number(row.sample_rate_hz) : null,
    channels: row.channels !== null ? Number(row.channels) : null,
    status: String(row.status) as JobChunkRecord['status'],
    attempts: Number(row.attempts),
    resultJson: row.result_json ? String(row.result_json) : null,
    errorMessage: row.error_message ? String(row.error_message) : null,
    updatedAtMs: Number(row.updated_at_ms),
  };
}
