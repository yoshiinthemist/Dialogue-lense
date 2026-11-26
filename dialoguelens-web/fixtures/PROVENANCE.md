# DialogueLens Web Fixtures — Provenance & Licensing

All media, subtitle, and ground-truth expectation files in `fixtures/` are **100% self-authored and deterministically generated locally** via `node fixtures/generateFixtures.mjs` using FFmpeg (`libvpx` VP8 video + `libopus` 16 kHz audio) and are released under **CC0 1.0 Universal (Public Domain Dedication)**.

## Included Fixtures

| File | Specs | Scenarios Covered |
|---|---|---|
| `main-dialogue.webm` | 640×360, 28.0s, 24 fps VP8, 16 kHz Opus | Two moving visual speaker markers (Maya & Devon) crossing around 6–8.5s, hard scene cut at 14.0s, confirmed silence at 11.5–13.5s, audible speech bursts |
| `main-dialogue.vtt` / `.srt` | WebVTT + SRT | Two speakers, negation ("Do not open..."), quantity ("3 minutes", "console 2"), legitimate repetition ("Wait." ×2), same-time correction at 14.4s, overlapping speech (17.2–20.4s), off-screen speech, narration, and long shout bubble |
| `main-dialogue.expected.json` | Independent ground truth | Exact mathematical trajectory formulas and expected cues for independent verification |
| `portrait-9x16.webm` | 360×640, 6.0s, 20 fps VP8, 16 kHz Opus | Portrait aspect-ratio letterboxing & coordinate-transform verification |
| `ultrawide-21x9.webm` | 840×360, 6.0s, 20 fps VP8, 16 kHz Opus | Ultrawide aspect-ratio letterboxing & coordinate-transform verification |
| `long-5min.webm` + `long-5min.vtt` | 320×180, 305.0s (5:05), VP8/Opus | Full-duration chunked extraction & late speech near 04:30 (268–278s) proving zero 3-minute truncation |

## Important Honesty Note on Real Human Face Footage

Because these generated fixtures use synthetic geometric visual speakers rather than recorded human faces, they **deterministically verify** the media clock, coordinate transform, crossing-face identity lock, contour-attached SVG tail geometry, cut/seek invalidation, and FFmpeg chunking. They do **not** measure real-world human face-landmark accuracy of `@mediapipe/tasks-vision` on live human video or anime; that real-camera/real-footage detector check is explicitly tracked and disclosed separately in `verification/RESULTS.md`.

## Regeneration Command

```bash
npm run fixtures:generate
```
