# DialogueLens Web — Engineering Handoff Guide

## 1. First Launch Checklist

1. Ensure Node.js `>= 22.13` and `ffmpeg`/`ffprobe` are installed.
2. From `dialoguelens-web/`:
   ```bash
   npm ci
   npm run build
   npm run start
   ```
3. Visit `http://127.0.0.1:8787` and click **Open Playable Sample (28s Fixture)** to immediately experience real WebM playback, Scout 1 → Scout 2 preparation, stable comic speech bubbles, contour-attached active tails, editing, and exports with zero API keys required.

## 2. Privacy, Uploads & Security Model

- **Local-first default:** Local videos, sidecar subtitles, edits, speaker mappings, and manual anchors stay in your browser's IndexedDB (`dialoguelens-web-db`).
- **Explicit upload disclosure:** Media is uploaded to `/api/uploads` only when the user explicitly turns off **Free CC Only** AND checks the upload privacy consent box in Settings. Uploaded temporary files are stored on the server only for the duration of FFmpeg chunk extraction and deleted automatically when the job finishes, fails terminally, or is cancelled.
- **Secret isolation:** `GEMINI_API_KEY` and `DIALOGUELENS_API_TOKEN` live exclusively in server environment variables. Never put secrets in `VITE_*` variables. Exported `.dialoguelens.json` bundles strip ephemeral `blob:` URLs and contain zero credentials or video binaries.
- **SSRF protection:** `/api/probe-url` performs HEAD-only capability probing and strictly blocks localhost, `.local`/`.internal`, loopback, private RFC1918, link-local, and cloud metadata (`169.254.169.254`) addresses and redirects.

## 3. Storage, Backups & Relinking

- **Browser project authority (IndexedDB):** Autosaves accepted timeline revisions, protected field flags (`text`, `timing`, `speaker`, `anchor`), speaker profiles, coverage ledger, and intentional playback position (using monotonic `progressSeq` so backward seeks are never overwritten by stale forward progress).
- **Relink flow:** Browsers revoke `blob:` object URLs across page reloads. Reopening a saved local-video project displays an explicit relink prompt that checks file size and duration compatibility before reusing prior timeline timestamps.
- **Portable backups:** Use **Export JSON**, **Export WebVTT**, or **Export SRT** in the Transcript panel. When sharing a `.dialoguelens.json` bundle for a local video, accompany it with the original local video file listed in `companionFilesNotice`.
- **Server durable storage:** SQLite (`DATABASE_PATH`, WAL mode) stores `schema_migrations`, `policy_state`, `uploads`, `analysis_jobs`, `job_chunks`, and `budget_events`. Mount `/app/data` to a persistent volume in Docker/production so jobs survive restarts cleanly.

## 4. Browser Support & Known Limitations

- **Supported tested environment:** Desktop & responsive mobile viewports on Chromium (Chrome for Testing / Playwright). Real iPhone Safari hardware and Firefox/WebKit native installs were not executed in this container (recorded honestly in `verification/RESULTS.md`).
- **Native fullscreen:** Entering native HTML `<video>` fullscreen or YouTube native fullscreen hides custom overlays/adjacent companion panels; the UI discloses this honestly and provides Stage Fullscreen for inspectable local/CORS videos.
- **On-device MediaPipe face tracking:** Requires downloading MediaPipe WASM/model weights when toggled on. Human face landmarking does not prove active speech or real-world personal identity and has reduced reliability on stylized anime, occluded faces, or rapid crossings.
- **Live Gemini API execution:** The real `@google/genai` client and Zod schema validation are implemented and tested against deterministic transport boundaries, while live cloud API calls require setting `GEMINI_API_KEY` in the deployment environment.
- **Intentionally unsupported features:** Arbitrary third-party streaming site scraping, YouTube player overlays, private caption scraping, and burned-in video rendering/export are excluded from this release.
