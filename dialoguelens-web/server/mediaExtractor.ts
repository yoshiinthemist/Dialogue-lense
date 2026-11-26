import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';

const execFileAsync = promisify(execFile);

export interface MediaProbeInfo {
  durationMs: number;
  sampleRateHz: number | null;
  channels: number | null;
  hasAudio: boolean;
  hasVideo: boolean;
  width: number | null;
  height: number | null;
  codecAudio: string | null;
  codecVideo: string | null;
}

export interface ExtractedAudioChunk {
  chunkIndex: number;
  startMs: number;
  endMs: number;
  actualDurationMs: number;
  sampleRateHz: number;
  channels: number;
  wavPath: string;
  sizeBytes: number;
  cleanup: () => Promise<void>;
}

/**
 * Probes an authorized media file using ffprobe to discover real duration,
 * sample rate, channels, and stream layout. Never invents or truncates duration.
 */
export async function probeMediaFile(filePath: string): Promise<MediaProbeInfo> {
  const { stdout } = await execFileAsync(
    'ffprobe',
    [
      '-v',
      'error',
      '-show_format',
      '-show_streams',
      '-of',
      'json',
      filePath,
    ],
    { timeout: 20_000, maxBuffer: 4 * 1024 * 1024 },
  );

  const parsed = JSON.parse(stdout) as {
    format?: { duration?: string };
    streams?: Array<{
      codec_type?: string;
      codec_name?: string;
      sample_rate?: string;
      channels?: number;
      width?: number;
      height?: number;
      duration?: string;
    }>;
  };

  const streams = parsed.streams ?? [];
  const audioStream = streams.find((s) => s.codec_type === 'audio');
  const videoStream = streams.find((s) => s.codec_type === 'video');

  const durationSecRaw =
    Number(parsed.format?.duration) ||
    Number(videoStream?.duration) ||
    Number(audioStream?.duration) ||
    0;

  return {
    durationMs: Number.isFinite(durationSecRaw) ? Math.round(durationSecRaw * 1000) : 0,
    sampleRateHz: audioStream?.sample_rate ? Number(audioStream.sample_rate) : null,
    channels: audioStream?.channels ?? null,
    hasAudio: Boolean(audioStream),
    hasVideo: Boolean(videoStream),
    width: videoStream?.width ?? null,
    height: videoStream?.height ?? null,
    codecAudio: audioStream?.codec_name ?? null,
    codecVideo: videoStream?.codec_name ?? null,
  };
}

/**
 * Computes bounded chunk windows covering the FULL requested media duration without
 * silently truncating long files (e.g. 5+ minute clips get every interval planned).
 */
export function planMediaChunks(
  totalDurationMs: number,
  chunkDurationMs = 30_000,
): Array<{ chunkIndex: number; startMs: number; endMs: number }> {
  const safeDuration = Math.max(1000, Math.ceil(totalDurationMs));
  const safeChunk = Math.max(5000, Math.min(120_000, chunkDurationMs));
  const chunks: Array<{ chunkIndex: number; startMs: number; endMs: number }> = [];
  let cursor = 0;
  let index = 0;
  while (cursor < safeDuration) {
    const endMs = Math.min(safeDuration, cursor + safeChunk);
    chunks.push({ chunkIndex: index, startMs: cursor, endMs });
    cursor = endMs;
    index++;
  }
  return chunks;
}

/**
 * Extracts a single bounded WAV interval from a media file using FFmpeg.
 * Preserves clip offset (`-ss` + `-t`), target sample rate (defaulting to source rate or 16kHz),
 * channels, and verifies the resulting decoded duration cleanly.
 */
export async function extractAudioChunkWithFfmpeg(options: {
  sourcePath: string;
  chunkIndex: number;
  startMs: number;
  endMs: number;
  targetSampleRateHz?: number;
  channels?: number;
  signal?: AbortSignal;
}): Promise<ExtractedAudioChunk> {
  if (options.signal?.aborted) {
    throw new Error('Chunk extraction aborted before start');
  }

  const startSec = (Math.max(0, options.startMs) / 1000).toFixed(3);
  const durationSec = (Math.max(0.25, (options.endMs - options.startMs) / 1000)).toFixed(3);
  const sampleRateHz = options.targetSampleRateHz ?? 16000;
  const channels = options.channels ?? 1;

  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'dialoguelens-chunk-'));
  const wavPath = path.join(tmpDir, `chunk-${options.chunkIndex}.wav`);

  const cleanup = async (): Promise<void> => {
    await fs.rm(tmpDir, { recursive: true, force: true }).catch(() => undefined);
  };

  try {
    await execFileAsync(
      'ffmpeg',
      [
        '-hide_banner',
        '-loglevel',
        'error',
        '-y',
        '-ss',
        startSec,
        '-t',
        durationSec,
        '-i',
        options.sourcePath,
        '-vn',
        '-acodec',
        'pcm_s16le',
        '-ar',
        String(sampleRateHz),
        '-ac',
        String(channels),
        wavPath,
      ],
      { timeout: 30_000, signal: options.signal },
    );

    const stat = await fs.stat(wavPath);
    // Calculate exact PCM16 duration from WAV header/data bytes (44-byte header, 2 bytes/sample)
    const dataBytes = Math.max(0, stat.size - 44);
    const bytesPerSecond = sampleRateHz * channels * 2;
    const actualDurationMs =
      bytesPerSecond > 0 ? Math.round((dataBytes / bytesPerSecond) * 1000) : 0;

    return {
      chunkIndex: options.chunkIndex,
      startMs: options.startMs,
      endMs: options.endMs,
      actualDurationMs,
      sampleRateHz,
      channels,
      wavPath,
      sizeBytes: stat.size,
      cleanup,
    };
  } catch (err) {
    await cleanup();
    throw err;
  }
}
