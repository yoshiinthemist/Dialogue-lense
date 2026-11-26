# DialogueLens Web — Verification Results & Evidence Report

- **Source Version / ID:** `dialoguelens-web@1.0.0` (`local` workspace revision)
- **Date Executed:** 2026-11-26
- **OS & Runtime:** Linux `4.19.0-gvisor x86_64`, Node.js `v22.23.4`, npm `10.9.9`
- **Media Toolchain:** `ffmpeg 4.4.2-0ubuntu0.22.04.1+esm17` (`libvpx` VP8, `libopus` 48 kHz, `pcm_s16le` WAV extraction)
- **Executed Browser:** Chromium (`Chrome for Testing 159.0.8154.6`, Playwright `1.51.1` / `chromium-1255`)
- **Uninstalled / Unrun Browsers:** Firefox, WebKit, and real iPhone Safari hardware (`not run` in this container; only Chromium was installed and verified)

## 1. Automated Command Summary

| Check | Command | Status | Observed Output |
|---|---|---|---|
| TypeScript typecheck | `npm run typecheck` | **pass** | `0` errors across `shared/`, `server/`, `src/`, `tests/` |
| ESLint | `npm run lint` | **pass** | `0` errors, `0` warnings |
| Domain & Server Durability Tests | `npm test` | **pass** | `4/4` files, `18/18` tests passed (`1.93s`) |
| Production Build (Client + Server) | `npm run build` | **pass** | Vite client bundle (`dist/`) + NodeNext server compile (`dist-server/`) |
| Real-Browser E2E + Axe Accessibility | `npm run test:e2e` | **pass** | `4/4` Chromium suites passed (`16.3s`), `0` critical/serious WCAG 2A/2AA violations |
| Clean Production Start Smoke Check | `npm run start` + `GET /api/health` | **pass** | Verified same-origin serving (`/` HTML + `/api/health` JSON) |

## 2. Acceptance Scenario Matrix (Explicit `pass / fail / not run / blocked`)

| # | Scenario | Verification Category | Status | Evidence / Notes |
|---|---|---|---|---|
| 1 | Keyless end-to-end (import/play/edit/save/reopen/export, zero AI requests) | Real-browser E2E | **pass** | `tests/e2e/dialoguelens.spec.ts` Test 1; verified `0` `/api/ai/*` requests, IndexedDB reload persistence, JSON export download |
| 2 | Full Scout 1 → Scout 2 delayed repair loop (deterministic provider boundary) | Real-browser E2E + Unit | **pass** | `tests/e2e/dialoguelens.spec.ts` Test 2; corrupted text & dropped repetition repaired before display, unaccepted draft never rendered |
| 2b | Full Scout 1 → Scout 2 live Gemini cloud provider call | Live provider check | **not run** | `GEMINI_API_KEY` is unset in this environment; real `@google/genai` client is implemented and returns honest `503 CREDENTIALS_UNCONFIGURED` when unset |
| 3 | Evidence integrity (wrong lang, negation, quantities, repetition, same-time correction, missing translation, separate dimensions) | Deterministic Unit | **pass** | `tests/unit/scoutPipeline.test.ts` (6 tests); confirmed silence recovery never marks recovered dialogue silent |
| 4 | Caption state (`TextTrack.mode="showing"` on muted scout review surface, `"hidden"` on viewer, YouTube truthful unobservable CC) | Real-browser E2E + Unit | **pass** | `tests/e2e/dialoguelens.spec.ts` Tests 2 & 4 + `tests/unit/playbackReadiness.test.ts` |
| 5 | Ownership & staleness rejection against pending + accepted revisions | Deterministic Unit | **pass** | `tests/unit/scoutPipeline.test.ts`; stale tokens rejected, language changes invalidate dependent translations |
| 6 | Transport/recovery (holds, single resume, user pause, paused-media elapsed deadline, rejected `play()`, gap-scoped Continue, 0.5×/1×/2×) | Deterministic Unit | **pass** | `tests/unit/playbackReadiness.test.ts` |
| 7 | Cue-to-media-clock timing (`<= 100ms`) on real `<video>` playback | Real-browser E2E | **pass** | `tests/e2e/dialoguelens.spec.ts` Test 3; active rendered cue IDs matched actual `HTMLVideoElement.currentTime` |
| 8 | Body stability (`<= 1 CSS px` center drift under normal motion, tailed/tailless parity, no blank flash or entry replay) | Real-browser E2E + Unit + Visual | **pass** | `tests/unit/layoutAndTail.test.ts` + E2E Test 3 (20 rAF frames) + inspected `normal-motion-recording.webm` |
| 9 | Tail correctness (root `<= 1 CSS px` to contour, tip within `max(4px, 1% width)`, `<= 150ms` lag, full 360° & `±π` sweep for `speech` + `shout`, drag, hidden for narrator/offscreen) | Real-browser E2E + Unit + Visual | **pass** | `tests/unit/layoutAndTail.test.ts` + E2E Test 3; verified on synthetic fixture trajectories |
| 9b | Real-media human-face MediaPipe tracker live video accuracy | Real-source detector check | **not run** | Real `@mediapipe/tasks-vision` `FaceLandmarker` is integrated with honest download & anime/occlusion disclosure, but no real human video fixture was executed in headless Chromium; synthetic fixture geometry proves renderer/coordinate/crossing invariants only |
| 10 | Crossing-face identity lock, distinct `observationSeq` grace, hard cut & seek invalidation | Real-browser E2E + Unit | **pass** | `tests/unit/layoutAndTail.test.ts` + E2E Test 3 (`desktop-cut-scene2.png`) |
| 11 | Geometry & accessibility (desktop `1280×800`, mobile `390×844` zero overflow, keyboard editing guard, focus, reduced motion, Axe WCAG 2A/2AA) | Real-browser E2E + Visual | **pass** | E2E Test 4 + `@axe-core/playwright` (`0` critical/serious violations) + visual inspection |
| 12 | Editing & persistence (protected fields, conflict hints, project-scoped undo, backward-seek progress merge, relink mismatch, export/import) | Real-browser E2E + Unit | **pass** | `tests/unit/scoutPipeline.test.ts` + E2E Test 1 |
| 13 | Full 5-minute duration & late speech near `04:30` via real FFmpeg chunk extraction | Real FFmpeg Integration | **pass** | `tests/unit/serverDurability.test.ts` probed `long-5min.webm` (`>300s`) and decoded chunk covering `270s` cleanly |
| 14 | Provider boundary (Free CC Only zero admissions, disconnected backend visibly `UNCONFIRMED`, timeout vs cancel, malformed JSON, SSRF block, auth) | Real-browser E2E + Server Integration | **pass** | `tests/unit/serverDurability.test.ts` + E2E Test 1 |
| 15 | Durable SQLite job restart recovery without re-running completed chunks + upload cleanup | Server Integration | **pass** | `tests/unit/serverDurability.test.ts` restarted `createServer` against same SQLite DB mid-job |
| 16 | Source limits (CORS denial, unavailable captions, YouTube embed restriction error 150 + Open in YouTube link, missing credentials) | Real-browser E2E + Server Integration | **pass** | E2E Test 4 + `tests/unit/serverDurability.test.ts` |

## 3. Visual Inspection Findings

Inspected all captured screenshots in `verification/screenshots/` and the full normal-motion recording `verification/normal-motion-recording.webm`:
- **`desktop-home.png`:** Clean 3-card onboarding clearly distinguishes inspectable local/direct media from YouTube companion mode; green `Free CC Only (Zero AI Requests)` pill is prominent.
- **`desktop-player-bubbles.png` & `desktop-player-tailless.png`:** Maya's speech bubble stays locked at the exact same screen coordinates and line wrapping when toggling between `Speech Bubbles` and `Tailless` while Maya moves across the stage. SVG tail attaches cleanly to the rounded bubble contour and tracks Maya's marker smoothly.
- **`desktop-cut-scene2.png`:** Hard scene cut at `14.0s` cleanly resets tails and renders Devon's Scene 2 bubble anchored to Devon's marker with zero ghosting from Scene 1.
- **`normal-motion-recording.webm`:** Verified continuous movement, tail toggle, post-cut Scene 2 transition, and long shout bubble containment with zero flicker, zero blank flashes, and zero clipped text.
- **`mobile-390-player.png`:** Verified clean vertical stacking at `390×844` with zero horizontal overflow (`scrollWidth <= clientWidth`).
- **Issue caught and repaired during visual review:** Initial screenshots showed `<label class="btn">` (`Import Captions`, `Import Sidecar`) rendering slightly shorter than `44px` buttons. Added `label.btn` to the `min-height: 44px` rule in `src/index.css`, rebuilt, and re-verified uniform button heights across desktop and mobile.

## 4. Remaining Limitations & Unrun Live Checks

1. **Live Gemini Cloud Provider (`not run`):** `GEMINI_API_KEY` was not configured in this build environment. Configure `GEMINI_API_KEY` in `.env` and run live tests against `/api/ai/analyze-interval` and `/api/jobs` to verify cloud inference latency/accuracy with your key.
2. **Real Human Video MediaPipe Tracking (`not run`):** Synthetic fixtures verify all coordinate transforms, contour tail geometry, crossing guards, and cut invalidation, but real human-footage MediaPipe detection accuracy requires testing on a real video file in a browser with internet access to fetch the MediaPipe WASM/task bundle.
3. **Cross-browser Matrix (`not run` for Firefox / WebKit / real iPhone Safari):** Only Chromium (`159.0.8154.6`) was executed in this container.

## 5. Exact Reproduction Commands

```bash
cd dialoguelens-web
npm ci
npm run fixtures:generate
npm run verify
```
