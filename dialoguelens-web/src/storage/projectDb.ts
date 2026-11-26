import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import {
  CURRENT_PROJECT_SCHEMA_VERSION,
  DEFAULT_USER_SETTINGS,
  type ExportedProjectBundle,
  type MediaSourceDescriptor,
  type PlaybackProgressState,
  type ProjectSnapshot,
} from '../../shared/types.js';

interface DialogueLensDBSchema extends DBSchema {
  projects: {
    key: string;
    value: ProjectSnapshot;
    indexes: { 'by-updated': number };
  };
}

const DB_NAME = 'dialoguelens-web-db';
const DB_VERSION = 2;

export class StorageQuotaError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'StorageQuotaError';
  }
}

export class RevisionConflictError extends Error {
  constructor(
    message: string,
    public readonly storedProject: ProjectSnapshot,
  ) {
    super(message);
    this.name = 'RevisionConflictError';
  }
}

/**
 * Pure merge helper for concurrent playback progress and content saves.
 * CRITICAL: An intentional backward seek carries a higher `progressSeq` / `updatedAtMs`
 * and MUST NOT be discarded in favor of a larger stale `positionMs`!
 */
export function mergeProjectSnapshots(
  existing: ProjectSnapshot,
  incoming: ProjectSnapshot,
): ProjectSnapshot {
  const useIncomingContent = incoming.contentRevision >= existing.contentRevision;
  const contentBase = useIncomingContent ? incoming : existing;

  // Determine newer playback progress strictly by sequence/timestamp, NOT by max(positionMs)!
  let chosenProgress: PlaybackProgressState;
  if (incoming.progress.progressSeq > existing.progress.progressSeq) {
    chosenProgress = { ...incoming.progress };
  } else if (existing.progress.progressSeq > incoming.progress.progressSeq) {
    chosenProgress = { ...existing.progress };
  } else if (incoming.progress.updatedAtMs >= existing.progress.updatedAtMs) {
    chosenProgress = { ...incoming.progress };
  } else {
    chosenProgress = { ...existing.progress };
  }

  return {
    ...contentBase,
    progress: chosenProgress,
    updatedAtMs: Math.max(existing.updatedAtMs, incoming.updatedAtMs, Date.now()),
  };
}

export function migrateProjectSnapshot(raw: unknown): ProjectSnapshot | null {
  if (!raw || typeof raw !== 'object') return null;
  const rec = raw as Record<string, unknown>;
  if (typeof rec.id !== 'string' || !rec.source) return null;

  const version = typeof rec.schemaVersion === 'number' ? rec.schemaVersion : 1;
  const settings = {
    ...DEFAULT_USER_SETTINGS,
    ...((rec.settings as Record<string, unknown> | undefined) ?? {}),
    budgets: {
      ...DEFAULT_USER_SETTINGS.budgets,
      ...((((rec.settings as Record<string, unknown> | undefined)?.budgets as Record<string, unknown>) ?? {})),
    },
  };

  const rawProgress = rec.progress as Partial<PlaybackProgressState> | undefined;
  const progress: PlaybackProgressState = {
    positionMs: typeof rawProgress?.positionMs === 'number' && Number.isFinite(rawProgress.positionMs)
      ? Math.max(0, rawProgress.positionMs)
      : 0,
    updatedAtMs: typeof rawProgress?.updatedAtMs === 'number' ? rawProgress.updatedAtMs : Date.now(),
    progressSeq: typeof rawProgress?.progressSeq === 'number' ? rawProgress.progressSeq : 1,
    isIntentionalSeek: Boolean(rawProgress?.isIntentionalSeek),
  };

  // Schema v1 -> v2 migration preserves all user dialogue edits, speakers, anchors, and coverage
  return {
    schemaVersion: Math.max(version, CURRENT_PROJECT_SCHEMA_VERSION),
    id: rec.id,
    title: typeof rec.title === 'string' ? rec.title : 'Untitled Project',
    createdAtMs: typeof rec.createdAtMs === 'number' ? rec.createdAtMs : Date.now(),
    updatedAtMs: typeof rec.updatedAtMs === 'number' ? rec.updatedAtMs : Date.now(),
    contentRevision: typeof rec.contentRevision === 'number' ? rec.contentRevision : 1,
    evidenceRevision: typeof rec.evidenceRevision === 'number' ? rec.evidenceRevision : 1,
    configRevision: typeof rec.configRevision === 'number' ? rec.configRevision : 1,
    source: rec.source as MediaSourceDescriptor,
    referenceTracks: Array.isArray(rec.referenceTracks) ? rec.referenceTracks : [],
    activeReferenceTrackId:
      typeof rec.activeReferenceTrackId === 'string' ? rec.activeReferenceTrackId : null,
    speakers: Array.isArray(rec.speakers) ? rec.speakers : [],
    dialogueEvents: Array.isArray(rec.dialogueEvents) ? rec.dialogueEvents : [],
    coverage: Array.isArray(rec.coverage) ? rec.coverage : [],
    settings,
    progress,
  };
}

/**
 * Checks whether a newly selected local file matches a previously saved project's source
 * metadata before reusing existing analysis/timestamps.
 */
export function verifyRelinkedFileCompatibility(
  savedSource: MediaSourceDescriptor,
  candidateFile: { name: string; size: number; lastModified: number; type: string },
  candidateDurationMs?: number | null,
): { compatible: boolean; warnings: string[]; mismatchReasons: string[] } {
  const warnings: string[] = [];
  const mismatchReasons: string[] = [];

  if (savedSource.fileSize !== undefined && savedSource.fileSize !== candidateFile.size) {
    mismatchReasons.push(
      `File size mismatch (expected ${savedSource.fileSize} bytes, got ${candidateFile.size} bytes).`,
    );
  }
  if (
    savedSource.durationMs !== null &&
    candidateDurationMs !== undefined &&
    candidateDurationMs !== null &&
    Math.abs(savedSource.durationMs - candidateDurationMs) > 1500
  ) {
    mismatchReasons.push(
      `Duration mismatch (expected ~${Math.round(savedSource.durationMs / 1000)}s, got ~${Math.round(candidateDurationMs / 1000)}s).`,
    );
  }
  if (savedSource.fileName && savedSource.fileName !== candidateFile.name) {
    warnings.push(`Filename changed from "${savedSource.fileName}" to "${candidateFile.name}".`);
  }

  return {
    compatible: mismatchReasons.length === 0,
    warnings,
    mismatchReasons,
  };
}

/**
 * Portable JSON bundle serializer.
 * Explicitly strips browser object URLs, secrets, and private media blobs!
 */
export function buildExportedProjectBundle(project: ProjectSnapshot): ExportedProjectBundle {
  const sanitizedSource: MediaSourceDescriptor = {
    ...project.source,
    // Strip ephemeral blob: object URLs; keep non-secret metadata so relink can verify the local file
    url: project.source.url?.startsWith('blob:') ? undefined : project.source.url,
    requiresRelink: project.source.kind === 'local' && !project.source.url?.startsWith('/fixtures/'),
  };

  const companionNotice =
    project.source.kind === 'local'
      ? `Accompany this JSON file with your local video "${project.source.fileName ?? project.source.label}" (${project.source.fileSize ?? 'unknown'} bytes). Provider credentials and media binaries are intentionally excluded.`
      : project.source.kind === 'youtube'
        ? `Links to YouTube video ID "${project.source.youtubeVideoId ?? ''}". Sidecar/edited dialogue is stored inside this JSON. Provider credentials are intentionally excluded.`
        : `Links to direct media URL "${project.source.url ?? ''}". Provider credentials are intentionally excluded.`;

  return {
    format: 'dialoguelens-web-project',
    schemaVersion: CURRENT_PROJECT_SCHEMA_VERSION,
    exportedAtIso: new Date().toISOString(),
    companionFilesNotice: companionNotice,
    project: {
      ...project,
      source: sanitizedSource,
    },
  };
}

export function parseImportedProjectBundle(rawJson: string): ProjectSnapshot {
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawJson);
  } catch {
    throw new Error('Selected file is not valid JSON.');
  }
  const obj = parsed as Record<string, unknown>;
  const inner = obj?.format === 'dialoguelens-web-project' ? obj.project : obj;
  const migrated = migrateProjectSnapshot(inner);
  if (!migrated) {
    throw new Error('JSON file does not match a valid DialogueLens Web project schema.');
  }
  return migrated;
}

class ProjectRepository {
  private dbPromise: Promise<IDBPDatabase<DialogueLensDBSchema>> | null = null;
  private writeQueue: Promise<unknown> = Promise.resolve();
  private channel: BroadcastChannel | null = null;
  private listeners = new Set<(projectId: string) => void>();

  constructor() {
    if (typeof BroadcastChannel !== 'undefined') {
      this.channel = new BroadcastChannel('dialoguelens-project-sync');
      this.channel.onmessage = (event: MessageEvent<{ type?: string; projectId?: string }>) => {
        if (event.data?.type === 'project-updated' && event.data.projectId) {
          for (const cb of this.listeners) {
            cb(event.data.projectId);
          }
        }
      };
    }
  }

  onExternalUpdate(cb: (projectId: string) => void): () => void {
    this.listeners.add(cb);
    return () => this.listeners.delete(cb);
  }

  private getDb(): Promise<IDBPDatabase<DialogueLensDBSchema>> {
    if (!this.dbPromise) {
      this.dbPromise = openDB<DialogueLensDBSchema>(DB_NAME, DB_VERSION, {
        upgrade(db) {
          if (!db.objectStoreNames.contains('projects')) {
            const store = db.createObjectStore('projects', { keyPath: 'id' });
            store.createIndex('by-updated', 'updatedAtMs');
          }
        },
      });
    }
    return this.dbPromise;
  }

  /**
   * Serializes writes per tab and merges concurrent progress vs content updates
   * so neither overwrites the other (preserving intentional backward seeks).
   */
  saveProject(incoming: ProjectSnapshot): Promise<ProjectSnapshot> {
    const task = this.writeQueue.then(async () => {
      try {
        const db = await this.getDb();
        const tx = db.transaction('projects', 'readwrite');
        const store = tx.objectStore('projects');
        const existingRaw = await store.get(incoming.id);
        const existing = existingRaw ? migrateProjectSnapshot(existingRaw) : null;

        // Strip ephemeral blob URLs when storing local projects so reopen triggers clean relink
        const storableIncoming: ProjectSnapshot = {
          ...incoming,
          schemaVersion: CURRENT_PROJECT_SCHEMA_VERSION,
          source: {
            ...incoming.source,
            url: incoming.source.url?.startsWith('blob:') ? undefined : incoming.source.url,
            requiresRelink:
              incoming.source.kind === 'local' &&
              !incoming.source.url?.startsWith('/fixtures/'),
          },
        };

        const merged = existing
          ? mergeProjectSnapshots(existing, storableIncoming)
          : storableIncoming;

        await store.put(merged);
        await tx.done;

        this.channel?.postMessage({ type: 'project-updated', projectId: merged.id });
        return merged;
      } catch (err) {
        if (
          err instanceof DOMException &&
          (err.name === 'QuotaExceededError' || err.name === 'NS_ERROR_DOM_QUOTA_REACHED')
        ) {
          throw new StorageQuotaError(
            'Browser storage quota exceeded. Export a portable JSON backup of your project before clearing older projects.',
          );
        }
        throw err;
      }
    });

    this.writeQueue = task.catch(() => undefined);
    return task;
  }

  async saveProgressOnly(
    projectId: string,
    progress: PlaybackProgressState,
  ): Promise<ProjectSnapshot | null> {
    const existing = await this.getProject(projectId);
    if (!existing) return null;
    return this.saveProject({
      ...existing,
      progress,
    });
  }

  async getProject(projectId: string): Promise<ProjectSnapshot | null> {
    const db = await this.getDb();
    const raw = await db.get('projects', projectId);
    return raw ? migrateProjectSnapshot(raw) : null;
  }

  async listProjects(): Promise<ProjectSnapshot[]> {
    const db = await this.getDb();
    const all = await db.getAllFromIndex('projects', 'by-updated');
    return all
      .map((item) => migrateProjectSnapshot(item))
      .filter((item): item is ProjectSnapshot => item !== null)
      .sort((a, b) => b.updatedAtMs - a.updatedAtMs);
  }

  async deleteProject(projectId: string): Promise<void> {
    const db = await this.getDb();
    await db.delete('projects', projectId);
  }
}

export const projectRepository = new ProjectRepository();
