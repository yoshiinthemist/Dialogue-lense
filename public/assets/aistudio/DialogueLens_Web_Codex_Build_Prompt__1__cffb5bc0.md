# DialogueLens Web — complete Codex build prompt

**Purpose:** Build a new web application from scratch. This is an implementation and acceptance contract, not a claim that an application has already been built or tested.

## 1. Your assignment

Act as the lead engineer responsible for delivering **DialogueLens Web**, a complete, usable application for watching video with accurate, contextual dialogue and stable speech bubbles.

Complete one implementation run covering research, design, implementation, integration, browser testing, repairs, and handoff. Internal iterations are expected. Prioritize correctness and completeness over speed. Use available official documentation, mature libraries, tools, and specialist agents where they improve the result. Resolve ordinary technical choices yourself and keep concise progress notes and a resumable checkpoint.

Deliver working software. A scaffold, visual mockup, architecture document, isolated rendering demo, or interface backed only by simulated AI does not satisfy this task. Test doubles belong in explicitly labeled fixtures and tests. Implement the real AI integration even if live credentials are unavailable; report the exact live checks you could not execute.

Start in a new web project. If an Android repository or historical specification is present, use it as product/reference material and keep its application intact. Do not carry Android platform APIs or old patch layers into the web architecture. If earlier visual references are available, inspect them: Josh considered the first large prompt's bubble behavior the closest to the intended result. Do not claim to have reproduced that historical build without identifying it.

Honor the environment's actual permissions, credentials, execution limits, and spending authorization. Broad development latitude does not authorize unlimited runtime API spending or new paid services. Finish unblocked work before reporting a concrete blocker. Do not weaken acceptance criteria to obtain a green report.

## 2. Product outcome and supported sources

For inspectable media that permits overlays, the user opens a video and presses Play. Scout 1 prepares upcoming dialogue using accessible original captions and permitted audio/video evidence. Scout 2 checks that exact draft against original evidence, repairs supported errors, and submits a reviewed result. A single acceptance boundary publishes the timeline. The user sees readable dialogue whose body stays in place while an evidenced tail follows the speaker. YouTube provides the companion-panel experience defined below; explain this distinction in onboarding.

The two scouts are coordinated logical processing stages. They are not two uncontrolled agents, two hidden YouTube players, or two permanent background browser processes.

Implement these source modes deliberately:

| Source | Required experience | Evidence and presentation boundary |
|---|---|---|
| Local video | Full player, imported/exposed captions, stable bubbles, editing, local tracking, and optional AI analysis | Use actual browser-supported media. Upload media for server analysis only after the user enables that operation. |
| Authorized direct media URL | Playback and the same enhancements when the source grants the needed access | Discover caption, audio, frame, seeking, and CORS capabilities separately. A playable URL does not establish analysis access. |
| YouTube URL | Visible official embedded player, requested native CC, and a synchronized dialogue/transcript panel beside or below the player | Keep all custom bubbles, tails, and controls outside the player rectangle. Use imported captions or a genuinely implemented authorized caption source. No automatic access to arbitrary public-video transcripts, audio, or frames is assumed. |

For YouTube, use the documented IFrame API and supported caption/language parameters, including `cc_load_policy=1` where applicable. Distinguish **CC requested**, **CC activation observed**, and **caption text acquired**. Its documented API does not expose reliable active-track/CC-state readback: use "CC requested; activation not observable" when appropriate and do not invent a getter. A parameter or loaded caption module does not independently verify the displayed words. Imported subtitles remain labeled imported; they are not automatically native-verified. Official caption downloads require authorization and permission to edit the video. Owner-authorized download integration is optional; sidecar import is required.

Do not gate ordinary YouTube viewing on uploading captions, configuring AI, completing analysis, or making a payment. Do not cover its player, hide its branding/controls, run background scout players, scrape private endpoints, or use copied browser credentials to manufacture unsupported access. Synchronize the adjacent panel to observed player time, including after seeks; do not assume requested seek time is actual playback time. Native fullscreen may exclude the adjacent panel; make this behavior clear.

For every adapter, report actual capabilities: playback; caption activation; readable caption evidence; audio; frames; seeking; permitted overlay placement. Distinguish loading, unavailable, inaccessible, blocked, absent, and temporarily inactive evidence. Unsupported enhancements must not turn an otherwise playable source into a broken player. Arbitrary streaming-site support and burned-in video export are outside this release; do not add dead controls for them.

## 3. One coherent implementation

Use TypeScript throughout, a React/Vite client, a small same-origin Node/Fastify backend, shared domain modules, IndexedDB for browser project data, and SQLite for durable server jobs. Use compatible current stable versions, check their official documentation, and commit the lockfile. Equivalent mature libraries are acceptable when a concrete compatibility reason warrants a documented substitution.

Keep this one repository and a simple deployment. Do not introduce microservices or competing stores for the same authority.

| Responsibility | Single authoritative owner |
|---|---|
| Source activation, playback intent, seeks, lifecycle | Session controller through source adapters |
| Evidence, drafts, review outcomes, coverage, scheduling | Shared domain/pipeline coordinator |
| Accepted project content and protected edits | Transactional timeline repository |
| Actual bubble measurements, placement, tail motion | Presentation controller |
| Provider admission, secrets, durable analysis jobs | Backend |

React renders accepted snapshots and controls. Workers return results through their assigned boundaries; they do not mutate visible dialogue or issue playback commands. Avoid sending every tracking frame through the entire React application state.

Use Web Workers for bounded local computation where appropriate; DOM/media operations stay behind the appropriate source/presentation interface. Use established parsers, media processing, storage, validation, and tracking libraries. Implement a real face/landmark tracker for inspectable media, such as a currently supported MediaPipe solution. Face detection alone does not establish identity or active speech.

AI is optional to **use**, not optional to **implement**. Integrate at least one real, currently documented provider supporting the audio/visual evidence needed for transcription, translation, contextual review, and speaker candidates. Use configured compatible credentials if present; otherwise choose and fully implement one provider and document its setup. Pin/configure model identifiers and validate structured responses. Do not invent API methods or silently substitute canned responses when credentials are absent.

Extract bounded, decodable media intervals with actual timestamps. A documented server-side FFmpeg path is acceptable for authorized uploads. Preserve sample rates, duration, channel handling, clip offsets, and real partial coverage. Process the requested duration in chunks; do not silently truncate long videos or certify omitted middle intervals. Release media buffers and object URLs when no longer needed.

## 4. Evidence, scout hierarchy, and accepted content

### Scout 1: prepare from accessible original evidence

Discover available caption tracks, select the intended source language, and enable native CC for the scout's media pass wherever the source supports it. Keep activation separate from acquiring cue text. For controlled HTML media, `TextTrack.mode="showing"` displays native captions, while `"hidden"` permits cue consumption without native rendering. Any actual local/direct scout playback pass must use a muted, inspectable review surface with native captions showing; include a visible-caption test. A data-only pass may use readable cues directly, but must not claim that visibly rendered captions were observed. The viewer may use a hidden readable track while displaying accepted bubbles. Viewer display preferences must not disable the scout's evidence feed or force duplicate text over the bubbles.

Retain original cues as immutable evidence with source/media identity, language, track, timestamps, provenance, source revision, and measured coverage. Validate finite, ordered cue times against actual supplied media/clip bounds, and keep source timing distinct from readability/layout decisions. Preserve authorship/automatic-transcription and translation provenance separately. Handle rolling captions, legitimate repeated speech, same-time text corrections, selected-track changes, and intervals with no active cue. Do not repeatedly toggle a healthy track or call missing text confirmed silence.

Use available captions first, then additional permitted evidence where needed. Build candidate dialogue, timing, speaker identities local to the project, scene context, speaker-location observations, and explicit uncertainty. Keep model speaker slots local to their request until supported continuity links them. Names in descriptions and a nearby face are suggestions, not proof.

### Scout 2: review and repair

Read the exact Scout 1 draft **and the original evidence**, not just Scout 1's explanation. Check both unsupported generated content and supported dialogue missing from the draft. Check timing, language, negation, quantities, names, repetitions, and translation separately. Reusing the original caption track permits reference checking; it does not become independent audio verification.

Assess speaker identity, active speech, and location independently. Use real temporal voice/diarization evidence and visual speech/track evidence when available. Match them through supported continuity, not nearest-face heuristics or the model's confidence score alone. Maintain unknown/offscreen/narrator states. Keep real-world personal identity recognition outside scope; track project-local speakers.

Include live production code for speaker association and tail tracking. Do not pass attribution tests by making every cue unknown. Also do not force associations for animation, occlusion, crossing faces, or weak evidence: disclose limited detector coverage and support manual corrections. A human-face detector must not be advertised as reliable on all anime styles.

Review presentation using the interval's observations and actual layout constraints. Recheck repaired placement when runtime measurements become available. Predicted layout review alone cannot establish that the browser actually rendered stable bubbles.

### Acceptance and revisions

Use explicit transitions such as evidence → draft → reviewed result → accepted revision. Record quality per cue/subinterval and per dimension: reference alignment, audio support, translation review, attribution, and presentation. Avoid one universal `verified` Boolean. One correct cue cannot certify its unsupported neighbors.

Every task/result carries immutable project/media identity, evidence/configuration revision, task ID, interval, draft revision, attempt, and cancellation context. Validate at receiving and publication boundaries. Reject stale results relative to both pending and already accepted revisions. Never restamp old work with the current session.

Separate durable analysis validity from current viewer generation. Compatible old-seek analysis may remain useful as cached evidence; old frames, tail state, or transport commands cannot mutate the current seek/project. Language or source-text changes invalidate dependent translations. Model/cost-mode changes alone should not discard reusable evidence.

Commit accepted text, timing, attribution, coverage, evidence references, and revision transactionally. Publish one coherent snapshot to display, save, and export. User edits are authoritative revisions with field-specific protection for text, timing, identity, and body placement. Repairs can identify conflicts but cannot silently overwrite those fields. Correction propagation requires a positive supported identity link; two unknown speakers are not a match.

When only some cue IDs succeed, accept those valid results and retain bounded recovery for missing/failed IDs. A failed or empty response does not complete a window. An imported or locked caption source may still contain gaps worth diagnosing.

## 5. Preparation, playback, and recovery

Prioritize the current/seek interval and upcoming review. Adapt preparation lead to playback rate and measured processing time, with bounded concurrency. Do not claim fixed real-time performance for every device/provider.

For analyzable sources, offer **Prepare fully** and **Prepare ahead**. The first prioritizes complete requested coverage before enhanced playback; the second prepares a sufficient opening window and continues ahead. Preparation progress must come from actual processed/accepted coverage, with unresolved intervals visible.

One controller owns actual play/pause/seek commands and preserves user intent. Opening, seeking, catch-up, mid-playback gaps, and end-of-video tolerance use one readiness policy. An analysis hold must actually pause the appropriate local/direct player; a badge is insufficient. Handle rejected `play()` calls and autoplay permission honestly. YouTube's ordinary playback remains available under section 2.

Bound preparation and review deadlines using elapsed time, including when media time is paused. After the deadline, release usable evidence-backed captions as an explicit fallback if policy permits; unresolved attribution uses a neutral stable body without an unsupported tail. If no usable text exists, leave indefinite buffering, show the unavailable/partial state, and offer Retry or Continue for that gap. Continuing cannot waive every future gap, certify silence, or override user pause.

Corrections arriving during display update coherently. Never alternate revisions, replay the same cue's entrance, erase usable text during a retry, or flash stale dialogue after a seek.

## 6. Stable bubble bodies; independent active tails

Use measured HTML bubble bodies and SVG tails or an equally testable rendering approach. Provide a polished comic-bubble treatment with readable typography, consistent speaker colors/labels, careful padding, and restrained outlines. Include ordinary and expressive/shout shapes only when their geometry is fully implemented and tested. Avoid flashing decoration and exaggerated spring motion.

Speech-bubble and tailless modes share the **same committed body anchor, measured bounds, cue identity, and entry state**. Normal speaker motion changes the tail only. Switching tail visibility cannot reposition the body, alter wrapping, or restart entrance animation.

Reconsider a body anchor only for explicit dragging, a meaningful scene transition, changed viewport/content measurements, or persistent obstruction. Text corrections may require controlled remeasurement/reflow; freezing the anchor must not freeze the words. Tail obstruction alone must not pull the body around. Preserve a valid reading anchor through short tracking loss and through a scene cut when its placement remains suitable.

Anchor a tail to a supported speaker/track/scene association. Attach its root continuously to the actual body contour and its tip to fresh transformed speaker evidence, with mouth clearance and bounded reach. Use time-based smoothing with correct angle wrapping. Hide invalid/offscreen/uncertain attachments and reacquire deliberately. Never transfer a tail to a listener merely because that face becomes nearer.

Use one content-to-stage coordinate transform for live/stored observations, aspect ratio, object-fit/letterboxing, orientation, resize, and fullscreen. Keep bubble content inside actual measured bounds and safe areas. Invalidate and acknowledge new measurements even if the next dimensions equal the previous ones.

Keep media time, observation capture time/sequence, monotonic animation time, and elapsed deadline time distinct. Re-rendering one missing observation does not create repeated misses or refresh stale evidence. Cuts/seeks invalidate incompatible tail/animation state immediately. Do not animate through the previous scene's faces to reach a new anchor.

For accessible HTML video, use frame-associated timing where available, with a documented feature-detected fallback. The active cue follows actual media time. Test normal motion as well as reduced motion.

## 7. Finished application workflows

Build a calm responsive viewing workspace, with a clear information hierarchy and useful empty/error states.

- **Home:** local video selection, supported URL input, caption import, recent projects, and a clearly labeled playable sample. Validate capability before promising an enhancement.
- **Player:** large stage, familiar playback/seek/rate/volume/fullscreen controls, caption language, original/translated/bilingual text, bubble/tailless mode, readable progress, and a transcript beside the stage or in a mobile sheet. Keep developer diagnostics in an optional inspector.
- **Editor:** select/search cues, edit text/timing/speaker, split/merge cues, assign stable speaker labels/colors, drag or reset a body anchor, undo/redo within the current project, and repair a selected interval. Show repair changes without destroying protected edits.
- **Settings:** font size/contrast, sensible bubble sizing, motion preference, source/target language, glossary, captions-only/AI mode, preparation mode, and visible request/attempt budgets. Clearly identify features awaiting configured credentials.
- **Projects:** autosave and reopen accepted content, edits, unresolved intervals, source metadata, and latest intentional playback position. If the browser cannot retain access to a local file, provide an explicit relink flow that checks the source before reusing analysis.
- **Export/import:** portable versioned project JSON plus SRT and WebVTT from the accepted timeline. Round-trip edits and manual anchors; exclude provider secrets and private media blobs by default. Explain which source files must accompany the project.

All exposed controls must work. No placeholder navigation, fake completion percentages, misleading green verification badges, or unconditional success notifications.

Support keyboard operation, visible focus, accessible labels, selectable transcript text, approximately 44 CSS-pixel touch targets, and readable contrast. Playback shortcuts must not intercept text editing. Honor `prefers-reduced-motion`, avoid announcing every tail frame through live regions, and support mobile layouts without horizontal page overflow. In native fullscreen modes where custom overlays cannot render, fall back honestly rather than showing a false enabled state.

## 8. Persistence, jobs, budgets, and practical security

The captions-only path must work without provider credentials: real local playback, subtitle import/accessible text tracks, editing, manual speaker mapping, stable bubbles/tails where supported, project persistence, and exports. An active **Free CC Only** session makes **zero AI-provider requests**, including queued work, retries, hidden transcription, and cheaper-model fallbacks. Local tracking is permitted; disclose required model downloads separately.

Enforce current mode, session, ownership, quotas, circuit state, and budgets immediately before every provider attempt. For durable jobs, persist backend authorization/policy revisions and provide an acknowledged disable/cancel operation. A switch to Free CC Only blocks new local AI work immediately; show stopping/cancellation-unconfirmed until the backend has acknowledged disabling its active work. Do not falsely report the mode fully active while an unreachable backend may still run authorized jobs. After acknowledgement, permit zero new provider admissions or retry dispatches; previously dispatched requests may finish or incur charges. The captions workflow remains available during this transition. Request in-flight cancellation where supported and reject obsolete publication. Distinguish request timeout from parent cancellation. Apply finite attempts/time/request budgets to partial failures as well as complete failures.

For jobs that must survive tab closure/server restart, persist required uploaded inputs and SQLite job state. Implement leases, bounded retries, idempotent result acceptance, status, cancellation, cleanup, and restart recovery. A browser object URL is not durable server input. Browser-only jobs checkpoint and resume after the page returns; do not rely on immortal workers. Document the backend's persistent storage requirement.

IndexedDB is the project authority; server jobs return evidence/results rather than maintain a conflicting accepted timeline. Serialize or reject competing tab writes. Merge playback progress and content revisions so neither overwrites the other; an intentional backward seek is newer progress, not a value to discard. Version schemas and preserve valid user data through migrations. Handle quota/storage failures and provide portable backup.

Keep provider keys on the server, out of `VITE_*`, browser storage, source control, logs, and exported projects. Before accepting uploaded media for AI processing, explain what leaves the device. Add input/schema/size limits, escaped caption rendering, safe file handling, bounded media processing, and cleanup. Any publicly reachable paid-provider/job endpoints require authenticated ownership, appropriate request protections, and rate/budget limits. Keep the local captions workflow usable without an account. Do not build an unrestricted URL proxy; if server fetching is implemented, validate destinations and redirects and prevent access to internal services.

## 9. Verification that proves the experience

Write and execute meaningful domain/integration tests and Playwright browser tests. Run the actual production code paths: source adapters, scheduler, repository, acceptance boundary, media element, and renderer. Mock external provider/network boundaries only where needed for deterministic tests, and label that evidence accurately.

Ship reproducible playable fixtures with provenance/licensing and independent expected results: two speakers, movement/crossings, a cut, offscreen speech, narration, confirmed silence, missing/wrong dialogue, wrong negation, a same-time correction, overlapping speech, long text, and portrait/ultrawide layouts. Add delayed/duplicated/stale results and a five-minute fixture with speech near 04:30. Synthetic evidence proves pipeline behavior; it does not prove a live detector or provider's real-world accuracy. Include at least one permitted real-media fixture exercising the actual tracker with moving attachments and a known speaker mapping. Verify automatic association separately from manual mapping. If the real-tracker check cannot run, explicitly mark tracking unverified and scope completion accordingly; injected observations cannot substitute for it. Execute live provider/automatic-attribution checks when credentials and access are available.

Use the actual media clock in browser tests. Do not replace video playback with a simulated slider. Include Chromium, Firefox, and WebKit projects where supported; record executed browser/OS/codec versions. Mobile emulation is not a real iPhone test. Fix genuine failures and distinguish unavailable infrastructure from passing coverage.

### Required acceptance scenarios

1. **Keyless end-to-end:** import local video/captions, watch, edit, save, reopen/relink, and export with no provider configuration and zero AI requests.
2. **Full scout loop:** in Prepare ahead mode, start real playback after only the opening window is accepted. While the viewer plays, Scout 1 drafts later windows and Scout 2 repairs incorrect/missing dialogue injected at the provider boundary. Record preparation, review, acceptance, and display times plus actual interval coverage. Deliberately delay review: repaired dialogue must arrive before display, or the specified hold/fallback must occur. Unaccepted drafts never appear. Correct dialogue displays once; unsupported content stays unverified. Exercise the same loop with the live provider when configured.
3. **Evidence integrity:** test wrong language, negation, quantities, repeated speech, source corrections at identical timestamps, missing translations, and partial responses. Source alignment cannot certify translation, identity, or unavailable audio.
4. **Caption state:** on controlled HTML media, enable an available track initially off; verify visible CC in scout playback and readable cues separately; retain it; distinguish no active cue from failure. Source/track changes and interruptions trigger bounded rechecks, not repeated toggling. YouTube tests verify supported request parameters and truthful unobservable-state handling, not fabricated activation readback.
5. **Ownership/staleness:** switch source, seek, change language, or accept a newer revision while old work completes. Old work cannot change live dialogue, tracks, placement, playback, or protected fields.
6. **Transport/recovery:** test real holds, one appropriate resume, user pause, deadlines while media time is stopped, rejected playback, exhausted gaps, and gap-scoped Continue. Test 0.5×, 1×, and 2× playback.
7. **Timing:** on supported local fixture media, cue transitions must track actual media time within 100 ms under the recorded test conditions. Report observed errors; exceeding this bound fails the criterion. Measure YouTube separately using its observable time/update limitations; never claim frame-exact iframe synchronization.
8. **Body stability:** under normal motion, sample actual rendered bounds each animation frame. With unchanged cue content, viewport, and valid placement, committed center drift is at most 1 CSS pixel. Tailed/tailless toggles preserve bounds and entry count; accepted text updates do not flash blank or alternate old/new revisions.
9. **Tail correctness:** measure actual drawn endpoints against independent fixture geometry, not merely the application's predicted target. For timely fixture observations, roots must meet the rendered contour within 1 CSS pixel and tips must remain within the greater of 4 CSS pixels or 1% of displayed-video width from the expected clearanced target, with at most 150 ms of presentation response lag. Check motion, full angular sweeps for every shipped contour, the ±π boundary, and manual body dragging that preserves the speaker binding. Expressive contours require these checks if shipped. Bodies stay locked during ordinary target motion. Unknown/offscreen cases hide tails correctly. The test cannot pass by suppressing all tails or dialogue. These are renderer acceptance bounds under declared fixture conditions, not a claim about universal detector accuracy.
10. **Cuts/crossings/freshness:** crossing faces cannot silently exchange identity; repeated layouts do not consume observation grace. Seek/cut invalidates old evidence and incompatible animation without dragging the body through the old scene.
11. **Geometry/accessibility:** verify desktop, 360–390 px mobile widths, landscape, large text, keyboard, focus, reduced motion, font loading, resize, and fullscreen fallbacks. Real content fits measured bounds; no essential controls are clipped. Include an accessibility scan and manual browser review.
12. **Editing/persistence:** protect text/timing/speaker/anchor through repair, undo, reload, and export/import. Project A's Undo cannot modify B. Concurrent progress/content saves preserve both latest values, including a backward seek. Handle relink mismatch and storage failure.
13. **Duration/coverage:** prepare through the long fixture and its late speech. Inject a failed middle chunk and repeated partial results; retain correct work, expose actual gaps, exhaust budgets, and permit later work/resume.
14. **Provider boundary:** assert zero requests in a Free CC Only session and zero new backend admissions/dispatches after an acknowledged switch during work. Test a disconnected backend: cancellation stays visibly unconfirmed and must not be presented as completed. Exercise retries, timeout versus cancellation, malformed provider output, circuit/budget exhaustion, and ownership rejection.
15. **Durability:** reload/close a client and restart the backend during an authorized durable job. Recover persisted work, avoid duplicate publication, honor cancellation, and clean temporary inputs. Test browser-local checkpoint/relink behavior separately.
16. **Source limits:** exercise CORS denial, unavailable captions, unsupported media, YouTube embed restrictions, and missing credentials. No unsupported source is shown as successfully analyzed. Enhancement failure never gates an otherwise playable YouTube embed. A genuinely blocked/private/removed embed produces an accurate error and an Open in YouTube link where appropriate.

Capture and inspect screenshots of key desktop/mobile states and a **normal-motion recording** containing movement, a tail-mode switch, a repair, and a cut. Static screenshots and zero diagnostic counters alone cannot prove freedom from flicker. Do not disable motion or relax assertions to pass the original bug. Use independently authored expectations and actual visible output, not tests that simply repeat the implementation's decisions.

Run type checking, linting, domain/integration tests, browser tests, and a clean production build/start smoke test. Include an automated workflow for reproducible checks. Maintain explicit **pass / fail / not run / blocked** results. Deterministic tests, real-source smoke tests, live-provider checks, and visual inspection are separate evidence categories.

## 10. Final handoff and definition of done

Deliver one source/release package named `DialogueLens_Web_<version>_<source-id>_handoff.zip`, using actual identifiers, or an equivalent retrievable immutable source revision plus the package. Include:

- Complete source, dependency lockfile, server implementation, database migrations, and production build artifacts where appropriate.
- Working `npm ci`, `npm run dev`, `npm run build`, `npm run start`, `npm test`, `npm run test:e2e`, and documented quality-check commands. Provide a practical Docker/deployment path with persistent backend storage.
- `README.md`, safe `.env.example`, and `HANDOFF.md` covering first launch, source capabilities, free mode, AI setup, privacy/upload behavior, storage, backups, browser limits, and unsupported features.
- Fixtures and their provenance, automated tests, actual build/test logs, selected Playwright traces/screenshots, and the reviewed normal-motion recording.
- `verification/RESULTS.md` identifying the tested source revision, environment, feature-to-test mapping, actual outcomes, live integration status, residual defects, and exact commands to reproduce them.

Exclude secrets, user uploads, private job databases, caches, and `node_modules` from the handoff package. Include safe fixtures, reproducible migrations, and dependency-install instructions instead.

Provide a live URL only if deployment was actually performed through an available authorized workflow. Otherwise provide the working local launch and production deployment instructions. Do not add a public deployment or paid service merely to claim completion.

The handoff is complete when the app is implemented through its real production paths, the required executable checks have passed in the stated supported environment, and visual evidence has been inspected. Missing live access must be identified specifically; it cannot be described as passing or fully verified. If a genuine blocker remains, deliver the usable work and exact continuation steps, with overall status clearly scoped.

End with a concise report: what works, how to launch it, what was actually tested, remaining limitations, and links to the artifact/evidence. Do not replace implementation with another plan.

## Reference documentation to recheck during implementation

These establish the web boundary and useful platform primitives. Check the current official documentation rather than relying on remembered APIs.

- [YouTube IFrame Player API](https://developers.google.com/youtube/iframe_api_reference)
- [YouTube embedded-player parameters](https://developers.google.com/youtube/player_parameters)
- [YouTube Required Minimum Functionality, including overlay rules](https://developers.google.com/youtube/terms/required-minimum-functionality)
- [YouTube caption download authorization](https://developers.google.com/youtube/data/reference/rest/v3/captions/download)
- [HTMLMediaElement textTracks](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/textTracks)
- [CORS and canvas access](https://developer.mozilla.org/en-US/docs/Web/HTML/How_to/CORS_enabled_image)
- [Video frame callbacks](https://developer.mozilla.org/en-US/docs/Web/API/HTMLVideoElement/requestVideoFrameCallback)
- [Vite setup](https://vite.dev/guide/) and [environment-variable exposure](https://vite.dev/guide/env-and-mode)
- [MediaPipe Face Landmarker](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker?tab=Web)
- [Playwright browser support](https://playwright.dev/docs/browsers) and [assertions](https://playwright.dev/docs/test-assertions)

**Specification prepared:** November 25, 2026. The architecture and acceptance targets above are requirements for the new build; they are not measurements of an existing web app.
