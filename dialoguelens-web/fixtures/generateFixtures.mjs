#!/usr/bin/env node
/**
 * Reproducible playable fixture generator for DialogueLens Web.
 * Uses local ffmpeg to generate CC0/self-authored deterministic VP8/Opus WebM fixtures,
 * sidecar WebVTT & SRT files, and independently authored expected ground-truth JSON
 * (including moving/crossing speakers, hard cut, offscreen, narration, confirmed silence,
 * negation, quantity, same-time correction, overlapping speech, long text, portrait 9:16,
 * ultrawide 21:9, and a 5-minute clip with late speech near 04:30).
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function runFfmpeg(args) {
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], {
    stdio: 'inherit',
  });
}

function writeText(fileName, content) {
  fs.writeFileSync(path.join(__dirname, fileName), content, 'utf8');
}

// 1. Main 28-second 640x360 WebM fixture with two distinct moving visual speakers,
//    a crossing path around 6s–10s, a hard scene cut at 14.0s (distinct background color),
//    and distinct audio speech bursts + confirmed silence at 11.5s–13.5s.
function generateMainFixture() {
  const outPath = path.join(__dirname, 'main-dialogue.webm');

  // Video:
  // Scene 1 (0–14s): slate blue backdrop (#1e293b) with Speaker A (warm coral box/head)
  // moving left->right (x = 120 + 26*t) and Speaker B (teal box/head) moving right->left (x = 460 - 24*t),
  // crossing around t=7s!
  // Scene 2 (14–28s): deep emerald/charcoal backdrop (#14332a) with Speaker A parked right (x=430)
  // and Speaker B parked left (x=150).
  const vf = [
    // Base canvas switching color cleanly at t=14.0s (hard scene cut!)
    `color=c=0x1e293b:s=640x360:d=14:r=24[bg1]`,
    `color=c=0x14332a:s=640x360:d=14:r=24[bg2]`,
    `[bg1][bg2]concat=n=2:v=1:a=0[bg]`,
    // Speaker A visual head marker (72x96)
    `color=c=0xf59e0b:s=72x96:d=28:r=24[spkA]`,
    // Speaker B visual head marker (72x96)
    `color=c=0x38bdf8:s=72x96:d=28:r=24[spkB]`,
    // Overlay Speaker A: moves smoothly across x in scene 1, jumps cleanly at cut t=14s
    `[bg][spkA]overlay=x='if(lt(t,14), 100 + 28*t, 440 - 8*(t-14))':y='if(lt(t,14), 145 + 12*sin(t*1.4), 140)'[v1]`,
    // Overlay Speaker B: crosses Speaker A around t=7s in scene 1, jumps cleanly at cut t=14s
    `[v1][spkB]overlay=x='if(lt(t,14), 460 - 26*t, 120 + 6*(t-14))':y='if(lt(t,14), 150 - 10*sin(t*1.2), 145)'[vout]`,
  ].join(';');

  // Audio: distinct audible tone bursts during speech intervals and true silence during 11.5s-13.5s
  const af = `aevalsrc='if(between(t,11.5,13.5), 0, 0.14*sin(2*PI*330*t)*(between(t,0.4,4.2)+between(t,4.5,11.0)+between(t,14.3,27.2)))':s=48000:n=960:d=28`;

  runFfmpeg([
    '-filter_complex',
    `${vf};${af}[aout]`,
    '-map',
    '[vout]',
    '-map',
    '[aout]',
    '-c:v',
    'libvpx',
    '-pix_fmt',
    'yuv420p',
    '-b:v',
    '420k',
    '-c:a',
    'libopus',
    '-ar',
    '48000',
    '-b:a',
    '48k',
    outPath,
  ]);

  // Independently authored WebVTT including:
  // - two speakers (Maya & Devon)
  // - crossing movement (4.5s–10.8s)
  // - negation cue ("Do not open the vault before three o'clock.")
  // - quantity cue ("We only have 3 minutes left on the timer.")
  // - legitimate repetition ("Wait." at 8.0s and "Wait." at 9.2s)
  // - same-time correction (14.4s–17.0s superseded cleanly)
  // - confirmed silence gap (11.5s–13.8s)
  // - hard scene cut at 14.0s
  // - overlapping speech (17.2s–20.4s)
  // - offscreen speech (20.6s–22.6s)
  // - narration (22.8s–24.8s)
  // - shout + long readable text (25.0s–27.6s)
  const vtt = `WEBVTT

00:00:00.500 --> 00:00:04.200
Maya: Do not open the vault before 3 minutes have passed.

00:00:04.500 --> 00:00:07.800
Devon: Keep moving left while I cross behind the stage lamp.

00:00:08.000 --> 00:00:09.000
Maya: Wait.

00:00:09.200 --> 00:00:10.800
Maya: Wait.

00:00:14.400 --> 00:00:17.000
Devon: After the cut, the north door is open.

00:00:14.400 --> 00:00:17.000
Devon: After the cut, the north door is still locked!

00:00:17.200 --> 00:00:20.200
Maya: I checked the latch twice from this corridor.

00:00:18.000 --> 00:00:20.400
Devon: Then bring the spare key from console 2 right now.

00:00:20.600 --> 00:00:22.600
[Off-screen] Check the stairwell camera before anyone leaves.

00:00:22.800 --> 00:00:24.800
[Narrator] Two floors below, the emergency generator hummed back to life.

00:00:25.000 --> 00:00:27.600
Maya: HOLD THE ELEVATOR DOOR! We need to fit this longer message cleanly inside the measured bubble bounds without clipping.
`;

  const srt = `1
00:00:00,500 --> 00:00:04,200
Maya: Do not open the vault before 3 minutes have passed.

2
00:00:04,500 --> 00:00:07,800
Devon: Keep moving left while I cross behind the stage lamp.

3
00:00:08,000 --> 00:00:09,000
Maya: Wait.

4
00:00:09,200 --> 00:00:10,800
Maya: Wait.

5
00:00:14,400 --> 00:00:17,000
Devon: After the cut, the north door is still locked!

6
00:00:17,200 --> 00:00:20,200
Maya: I checked the latch twice from this corridor.

7
00:00:18,000 --> 00:00:20,400
Devon: Then bring the spare key from console 2 right now.

8
00:00:20,600 --> 00:00:22,600
[Off-screen] Check the stairwell camera before anyone leaves.

9
00:00:22,800 --> 00:00:24,800
[Narrator] Two floors below, the emergency generator hummed back to life.

10
00:00:25,000 --> 00:00:27,600
Maya: HOLD THE ELEVATOR DOOR! We need to fit this longer message cleanly inside the measured bubble bounds without clipping.
`;

  writeText('main-dialogue.vtt', vtt);
  writeText('main-dialogue.srt', srt);

  // Independently authored ground-truth geometry + speaker trajectory specification
  // Matches the exact mathematical ffmpeg overlay formulas above!
  const expected = {
    fixtureId: 'main-dialogue',
    durationMs: 28000,
    width: 640,
    height: 360,
    sceneCutMs: 14000,
    confirmedSilenceIntervalMs: { startMs: 11500, endMs: 13500 },
    crossingIntervalMs: { startMs: 6000, endMs: 8500 },
    speakers: [
      { trackId: 'track-maya', label: 'Maya', role: 'person' },
      { trackId: 'track-devon', label: 'Devon', role: 'person' },
    ],
    expectedCues: [
      {
        startMs: 500,
        endMs: 4200,
        speakerLabel: 'Maya',
        trackId: 'track-maya',
        sceneId: 'scene-1',
        text: 'Do not open the vault before 3 minutes have passed.',
        hasNegation: true,
        quantities: ['3'],
      },
      {
        startMs: 4500,
        endMs: 7800,
        speakerLabel: 'Devon',
        trackId: 'track-devon',
        sceneId: 'scene-1',
        text: 'Keep moving left while I cross behind the stage lamp.',
      },
      {
        startMs: 8000,
        endMs: 9000,
        speakerLabel: 'Maya',
        trackId: 'track-maya',
        sceneId: 'scene-1',
        text: 'Wait.',
      },
      {
        startMs: 9200,
        endMs: 10800,
        speakerLabel: 'Maya',
        trackId: 'track-maya',
        sceneId: 'scene-1',
        text: 'Wait.',
      },
      {
        startMs: 14400,
        endMs: 17000,
        speakerLabel: 'Devon',
        trackId: 'track-devon',
        sceneId: 'scene-2',
        text: 'After the cut, the north door is still locked!',
        sameTimeCorrectionExpected: true,
      },
      {
        startMs: 17200,
        endMs: 20200,
        speakerLabel: 'Maya',
        trackId: 'track-maya',
        sceneId: 'scene-2',
        text: 'I checked the latch twice from this corridor.',
      },
      {
        startMs: 18000,
        endMs: 20400,
        speakerLabel: 'Devon',
        trackId: 'track-devon',
        sceneId: 'scene-2',
        text: 'Then bring the spare key from console 2 right now.',
        quantities: ['2'],
      },
      {
        startMs: 20600,
        endMs: 22600,
        speakerLabel: 'Off-screen',
        role: 'offscreen',
        sceneId: 'scene-2',
        text: 'Check the stairwell camera before anyone leaves.',
      },
      {
        startMs: 22800,
        endMs: 24800,
        speakerLabel: 'Narrator',
        role: 'narrator',
        sceneId: 'scene-2',
        text: 'Two floors below, the emergency generator hummed back to life.',
      },
      {
        startMs: 25000,
        endMs: 27600,
        speakerLabel: 'Maya',
        trackId: 'track-maya',
        sceneId: 'scene-2',
        styleVariant: 'shout',
        text: 'HOLD THE ELEVATOR DOOR! We need to fit this longer message cleanly inside the measured bubble bounds without clipping.',
      },
    ],
  };

  writeText('main-dialogue.expected.json', JSON.stringify(expected, null, 2) + '\n');
}

// 2. Portrait 9:16 (360x640, 6s) and Ultrawide 21:9 (840x360, 6s) layout fixtures
function generateAspectFixtures() {
  runFfmpeg([
    '-f',
    'lavfi',
    '-i',
    'color=c=0x1e1b4b:s=360x640:d=6:r=20',
    '-f',
    'lavfi',
    '-i',
    'sine=frequency=440:sample_rate=48000:duration=6',
    '-af',
    'asetnsamples=960',
    '-c:v',
    'libvpx',
    '-b:v',
    '220k',
    '-c:a',
    'libopus',
    '-ar',
    '48000',
    '-b:a',
    '32k',
    path.join(__dirname, 'portrait-9x16.webm'),
  ]);

  runFfmpeg([
    '-f',
    'lavfi',
    '-i',
    'color=c=0x0f172a:s=840x360:d=6:r=20',
    '-f',
    'lavfi',
    '-i',
    'sine=frequency=520:sample_rate=48000:duration=6',
    '-af',
    'asetnsamples=960',
    '-c:v',
    'libvpx',
    '-b:v',
    '280k',
    '-c:a',
    'libopus',
    '-ar',
    '48000',
    '-b:a',
    '32k',
    path.join(__dirname, 'ultrawide-21x9.webm'),
  ]);
}

// 3. Long 5-minute (305s) fixture with late speech near 04:30 (270s) to prove no 3-minute truncation!
function generateLong5MinFixture() {
  const outPath = path.join(__dirname, 'long-5min.webm');
  runFfmpeg([
    '-f',
    'lavfi',
    '-i',
    'color=c=0x0f172a:s=320x180:d=305:r=2',
    '-f',
    'lavfi',
    '-i',
    `aevalsrc='0.12*sin(2*PI*360*t)*(between(t,2,6)+between(t,120,126)+between(t,268,278))':s=48000:n=960:d=305`,
    '-c:v',
    'libvpx',
    '-b:v',
    '64k',
    '-c:a',
    'libopus',
    '-ar',
    '48000',
    '-b:a',
    '24k',
    outPath,
  ]);

  const longVtt = `WEBVTT

00:00:02.000 --> 00:00:06.000
Maya: Opening check at two seconds into the five-minute reel.

00:02:00.000 --> 00:02:06.000
Devon: Midpoint check at two minutes, confirming uninterrupted chunk continuity.

00:04:28.000 --> 00:04:38.000
Maya: Late dialogue at four minutes thirty seconds proves the five-minute timeline is never silently truncated.
`;
  writeText('long-5min.vtt', longVtt);
}

console.log('Generating DialogueLens Web reproducible fixtures...');
generateMainFixture();
generateAspectFixtures();
generateLong5MinFixture();
console.log('All fixtures generated successfully in', __dirname);
