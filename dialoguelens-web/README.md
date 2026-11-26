# DialogueLens Web

DialogueLens Web is a self-contained TypeScript web application (React 19 + Vite 6 client, same-origin Node 22 + Fastify 5 backend, IndexedDB project authority, and durable SQLite server jobs) for watching video with accurate, contextual dialogue and stable speech bubbles.

## Architecture & Authoritative Ownership

| Responsibility | Single Authoritative Owner |
|---|---|
| Source activation, playback intent, seeks, lifecycle | `SessionController` (`src/controllers/sessionController.ts`) via source adapters |
| Evidence, Scout 1 drafting, Scout 2 review, coverage, scheduling | `shared/scoutPipeline.ts` + `src/workers/scoutWorker.ts` |
| Accepted timeline content, protected user edits, revisions | `TimelineAcceptanceStore` (`shared/scoutPipeline.ts`) persisted in IndexedDB (`src/storage/projectDb.ts`) |
| Measured HTML bubble bodies, stable placement, SVG contour tails | `PresentationController` (`src/controllers/presentationController.ts`) + `shared/layoutEngine.ts` |
| Provider admission, secrets, Free CC Only policy, durable jobs | Same-origin Fastify backend (`server/index.ts`, `server/policy.ts`, `server/db.ts`, `server/jobWorker.ts`) |

## Supported Sources & Honest Capability Boundaries

1. **Local Video:** Full HTML5 playback, imported WebVTT/SRT or exposed `TextTrack` captions, stable bubble bodies + contour-attached SVG tails, manual anchor dragging, cue editing, project-scoped Undo/Redo, on-device MediaPipe face tracking (optional; discloses WASM/model download and anime/occlusion limits), and optional server AI analysis only after explicit user upload consent.
2. **Authorized Direct Media URL:** Independently probes playback, seeking, and CORS/canvas readback. Cross-origin media without matching CORS headers remains playable while honestly marking frame inspection and browser audio extraction `blocked`.
3. **YouTube URL:** Official YouTube IFrame API embed with `cc_load_policy=1`, `cc_lang_pref`, and `hl`. All custom UI, controls, and synchronized transcript cues remain strictly beside or below the YouTube player rectangle (never overlaying or covering the player). Reports `"CC requested; activation not observable"` truthfully, never gates ordinary viewing on captions/AI/payment, supports optional sidecar SRT/VTT import, discloses native fullscreen exclusion, and provides an accurate error + **Open in YouTube** fallback when embedding is restricted.

Arbitrary streaming-site scraping and burned-in video export are outside this release and intentionally omitted.

## Quick Start (Local Development & Production)

### Prerequisites
- Node.js `>= 22.13.0` (uses built-in `node:sqlite`)
- `ffmpeg` and `ffprobe` on `PATH` (for fixture generation and server-side chunked media extraction)

### 1. Install dependencies & generate playable fixtures
```bash
cd dialoguelens-web
npm ci
npm run fixtures:generate
```

### 2. Development mode (hot client + backend)
```bash
npm run dev
```
- Client dev server: `http://127.0.0.1:5173` (proxies `/api` and `/fixtures` to `http://127.0.0.1:8787`)
- Backend server: `http://127.0.0.1:8787`

### 3. Production build & same-origin launch
```bash
npm run build
npm run start
```
Open `http://127.0.0.1:8787`.

### 4. Docker Deployment (with persistent SQLite & upload volume)
```bash
cp .env.example .env
# Optional: set GEMINI_API_KEY and DIALOGUELENS_API_TOKEN in .env
docker compose up --build
```
SQLite database and temporary uploads persist in the Docker volume mounted at `/app/data`.

## Free CC Only Mode vs Optional AI Setup

- **Captions-Only / Free CC Only (default):** Works completely without API keys and makes **zero AI provider requests** (including queued jobs, retries, and background fallbacks). Switching to Free CC Only blocks new local AI work immediately and shows **Stopping AI · Backend Unconfirmed** until the server acknowledges incrementing its persisted `policyRevision` and cancelling active jobs.
- **Optional Google Gemini Provider (`@google/genai`):** Copy `.env.example` to `.env` and set `GEMINI_API_KEY` on the server. Model identifiers are pinned via `GEMINI_MODEL_SCOUT1` (default `gemini-2.5-flash`) and `GEMINI_MODEL_SCOUT2` (default `gemini-2.5-pro`). Provider keys remain strictly on the server (never in `VITE_*`, browser storage, logs, or exported JSON bundles). When `GEMINI_API_KEY` is unset, AI endpoints return `503 CREDENTIALS_UNCONFIGURED` rather than fabricating mock AI output.

## Verification Commands

```bash
npm run typecheck    # Strict TypeScript check (client + shared + server)
npm run lint         # ESLint across shared, server, src, and tests
npm test             # Vitest unit, FFmpeg 5-min chunking, and SQLite durability/restart tests
npm run build        # Vite client bundle + NodeNext server compile
npm run test:e2e     # Playwright real-browser acceptance tests + Axe scan + normal-motion recording
npm run verify       # Complete sequential verification pipeline
```

See `verification/RESULTS.md` for recorded browser/OS/codec versions, exact execution outcomes, visual inspection notes, and live-provider scope.
