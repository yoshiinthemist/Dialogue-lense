# DialogueLens — Google AI Studio regression repair, v6

**Prepared for Josh · 27 November 2026 · Native Android only**

This is the complete next implementation prompt. Attach this file to Google AI Studio and ask it to execute the repair. It replaces the earlier broad repair instructions for this update; it preserves the product decisions below.

**Audit status:** Both new recordings were examined across their full duration using chronological frame sampling and denser inspection of important transitions. Audio tracks were decoded for signal analysis. Current production source was inspected, and small diagnostic probes were executed. No production app code was changed, no APK was installed, and no Android device test was performed during this audit. The proposed repairs still need implementation and validation.

---

# Begin implementation prompt

You are the senior Android engineer responsible for repairing the existing DialogueLens application. Implement the following changes in the current Kotlin/Compose project. Carry the work through code changes, meaningful regression tests, an installable candidate, and an honest verification report. A plan, new status messages, or passing isolated helper tests does not complete this task.

Preserve the visual design and the working bubble behavior. Concentrate on routing, playback acknowledgements, scout execution, accepted-caption publication, and tail geometry. Consolidate competing production paths where necessary. Do not add another independent controller over the existing controllers, rebuild this as a web app, or expand the feature set.

## 1. Establish what is actually being tested

The audit examined repository `yoshiinthemist/Dialogue-lense` at commit:

`7df5c0a6ef264e183379730913eef2c7ceabcc32`

That source declares **version 34.0 / versionCode 34**. Read the actual current checkout before editing; if it is newer, map these findings to its equivalent functions and verify whether each defect remains.

The supplied general-media recording visibly shows **v33.0** in Studio. That badge is bound to `BuildConfig.VERSION_NAME` in both the inspected v33 and v34 source. The YouTube recording never exposes its version, but its caption toolbar has the older order. Do not report either recording as a confirmed device test of this v34 commit.

Record application ID, version name/code, source commit, and APK SHA-256 for the candidate. If the build environment lacks Git metadata, create a reproducible source manifest/digest and retain the upstream baseline; “workspace-snapshot” alone is insufficient. Verify the installed artifact matches the candidate before live testing. Preserve existing user projects and edits during upgrade.

The current handoff reports 190 passing unit/Robolectric tests while marking actual installation, live YouTube, and WebView/capture checks blocked. Those are separate results. Do not convert a blocked phone test into a pass.

## 2. Recorded evidence to reproduce

The recordings are:

- General media: `screen-20261127-050814-1795784444527.mp4`, approximately 7 minutes 30 seconds.
- YouTube: `screen-20261127-051539-1795785036510.mp4`, approximately 5 minutes 3 seconds.

“Recording time” below means elapsed time in the screen recording. “Media time” means the app's displayed video position.

| Evidence | Observed failure |
|---|---|
| Web recording, opening and approximately 171–174 seconds | The in-player import sheet receives a non-YouTube URL but displays an empty “YouTube Video ID” and creates a blank YouTube project. |
| Same web URL, approximately 189–191 seconds | The full launcher correctly recognizes Embedded Web Video and opens Web Cinema. Entry points disagree. |
| Web recording, approximately 228–255 seconds | Scout Inspector repeatedly cycles around 7–9 seconds while native media is initially unready. |
| Web recording, approximately 335 seconds | The preparation bar reaches 02:55–03:00 / 98% while the viewer remains black at native 00:00 / 00:00. Scheduled work is not proof of acquired or verified coverage. |
| Web recording, approximately 417–435 seconds | The user clears a “Continue to Video” interstitial. Moving video eventually appears inside Inspector, but returning to Watch still shows a black viewer and preparation/unavailable dialogs. |
| YouTube media 00:45.0, 01:30.1, 02:15.1 | Automatic preparation holds occur. Each recorded exit follows a user tap on main Play. Automatic recovery is not demonstrated. Media 03:00 passes normally; the later 03:45.7 pause is user initiated. |
| YouTube media approximately 00:32 and 01:19–01:21 | Two app bubbles repeat the same utterance or an overlapping fragment. The transcript and overlay can disagree. |
| YouTube recording, beginning onward | The app reports restoring 166 cached bubbles with 0% verified. This is a restored-cache test, not a clean run. |
| YouTube media approximately 03:52–03:56 | A clean “Zohran Mamdani” line is replaced by “ZORAN MOM DANNI.” The source's written lower third supports the clean spelling. Investigate competing revisions and provenance; do not hard-code this name. |

The web recording's entire recorded audio track is silent. This does not establish why audio was absent. It cannot verify transcription or translation. The YouTube audio was not independently transcribed in this audit.

The new footage does not prove an ad stole the selected video element, and it does not establish a measured long-tail length. Those concerns have separate current-source findings below. Preserve the user's requirement for visible, short, stubby tails.

## 3. Fix source routing before launching any analysis

**Confirmed current defect:** `MediaImportAndInfoSheets.kt` takes `resolvedMedia?.youtubeVideoId`, then selects YouTube UI/actions when the value is non-null. `ResolvedMediaSource` uses an empty string for non-YouTube sources. An empty string therefore activates the YouTube branch. `StartLauncherScreen.kt` filters blank IDs and behaves differently.

Create one validated source-resolution/activation path used by the launcher, in-player plus/import sheet, Android share entry, and relevant project reopening paths. Route by resolved provider kind and validated source identity. Reject empty/invalid YouTube IDs before creating a YouTube project. Validate the YouTube host; an arbitrary eleven-character segment in another website's URL is not a YouTube ID.

Preserve distinct routing for YouTube, embedded web media, direct media, and local files. Never start caption jobs for a source that failed activation. Cover both entry points with the same non-YouTube URL in a production-path regression test.

## 4. Make Scout 2 executable after deferral and while playback is held

**Confirmed current control-flow problems:**

- `stageAndReviewIntervalThroughReleaseGate` attempts required review inline. A blocked claim returns without an independent required-review drain in the inspected production path.
- Generic `claimNextWork` can remove `SCOUT2_REQUIRED_REVIEW` work, but its ViewModel patrol caller executes only `CHAPTER_BOUNDARY_PATROL`.
- The review deadline is checked through `updateTimeAndLayout`, while the YouTube/web ticker skips that method during several held/paused states.
- Pending drafts can then apply backpressure to Scout 1.

Repair the existing coordinator and worker dispatch. Every claimed task must reach a handler for its actual type. A deferred required review must remain pending and execute when its blocker clears, without retranscribing the interval merely to trigger another inline attempt. Do not mark a task completed or idle as a substitute for executing it.

Service review deadlines from a lifecycle-aware, bounded worker or timer independent of viewer-clock advancement. User Pause must preserve the frozen viewer clock while eligible scout review/repair continues. Deadline expiry must produce a valid terminal outcome: reviewed release, explicitly identified evidence-backed fallback, or unavailable. It must not manufacture speech, mark unverified work verified, or leave coverage stranded `IN_PROGRESS`.

Use source/session/seek generation, interval identity, and revision checks on every completion, retry, and fallback. Cancel stale work and reject late writes. Preserve quota limits, cancellation, bounded retries, and the cap on pending work.

## 5. A play request must remain pending until playback is acknowledged

**Confirmed current defects:**

- ViewModel auto-resume consumes its directive and marks playing before the player acknowledges playback.
- The gated readiness path can retain `PLAYING` while an actual player sample says paused.
- YouTube's injected `dlPlay` returns “ok” immediately after `playVideo()`; the Compose layer treats that as handled.
- Web playback marks a command handled before media readiness/success. Its two-second play-grace rule can report playing when the element is still paused or its play promise rejected.

Separate user intent, readiness, command dispatch, and observed transport state. Retain a pending command with its source/element/session/seek/command generation until the matching player acknowledges the outcome. “JavaScript executed” and “page finished loading” are not playback acknowledgements.

An early Play request must survive delayed creation of the intended media element. Retry only while that intent remains current, with a bounded failure outcome. A later user Pause, source change, or seek invalidates the old request; a late callback must never restart playback against current intent.

Drive the authoritative playhead and UI state from acknowledged player state/position. A short clock extrapolation must be anchored to genuine samples and stop on pause, buffering, loss, or contradiction. Do not advance through a black/unready source based on scheduled work or the last requested seek.

Instrument the recorded 45-second holds before changing timing constants. Current YouTube planning uses a 35-second opening and 40-second rolling interval; the exact recorded 45-second cadence is not explained by that fact. Resolve review starvation, publication gaps, and acknowledgement failures rather than hiding them by disabling verification or adding more prebuffer time.

Healthy, prepared playback must not stop merely because a scout batch ends. Preserve bounded, truthful fallback behavior when evidence is unavailable. Off/watch without captions must not be held by caption readiness. Preserve Watch Now's current buffering-episode bypass: a retry or callback must not immediately reimpose that same hold. Define and test its reset on genuine recovery, source change, seek, and caption-mode change; do not let bypass leak into unrelated playback. Show media-loading failures as media-loading failures, rather than repeatedly covering an unready player with caption-preparation dialogs.

## 6. Publish one accepted caption timeline, including restored cache

**Confirmed current defect:** `mergeReleasedIntervalPreservingLocks` removes old events only when their start time lies inside the repaired interval. An old cue that starts before that interval and extends into it survives alongside its replacement. This ViewModel merge occurs after release-store review, so canonicalization inside the store does not protect the final UI publication.

Use one authoritative accepted timeline and one atomic publication path for overlay, transcript, persistence, and readiness coverage. Reconcile restored cache through that same path. Eliminate later append/merge paths that can reintroduce superseded variants.

A minimal fixture is an existing 28–34 second cue, repair window 30–35 seconds, and a replacement 30–34 second cue for the same speech. At 31 seconds the current rule retains both. The repair must use logical utterance/source-span lineage and revision ownership, not start time alone.

Handle full and split cues, normalized wording variants, translation/source variants, producer retries, and old cache without duplicating one utterance. Preserve genuine simultaneous speakers, later repeated phrases, meaningful SFX, and explicit user edits. Do not fix this with a global one-bubble limit, a text-only deduplicator, or deletion of every overlap.

Preserve cache provenance and schema/generation information. Unverified cached text must not become independent verification evidence for itself. An unreviewed or stale arrival must not overwrite a better supported accepted correction. New evidence may correct an accepted line through the same explicit review/revision process.

The bubble body should retain its stable anchor and identity through accepted text repairs where feasible. Overlay and transcript must expose the same accepted revision. Test both clean imports and upgrades/restored projects; clearing the user's cache is not the production fix.

## 7. Commit to the intended web player and reconcile physical scout control

**Confirmed current defect:** `findPrimaryVideo()` repeatedly reranks candidates. A newly ready autoplay element can outscore a previously selected, temporarily unready main video. Executing the extracted production selector against a mocked DOM reproduced this switch. It was a code probe, not an observed ad takeover in the recording.

Use candidate ranking for discovery. After selection, bind commands and evidence to the committed document, frame, media element, and content identity. Do not switch simply because an ad starts, another element gains readiness, or the main video buffers. Handle legitimate element replacement and playlist/source changes explicitly, with invalidation and a validated new selection.

Include originating identity/generation in progress, caption, frame, and audio callbacks. Do not attach a late callback to whatever URL happens to be current when it arrives. Detect ad/content discontinuities rather than merging their clocks or words.

Verify evidence timing as well as identity. A requested seek, assigned currentTime, or seek command acknowledgement does not prove decoded frames/audio reached the requested interval. Handle rejected or clamped seeks and old frames after a seek. Count acquisition/review only when the relevant timed caption, frame, or audio evidence actually covers the claimed interval for that media generation.

Keep necessary user actions and legitimate navigation working: interstitial Continue buttons, native Play, permitted source redirects, and recovery after element replacement. Handle nuisance popups without disabling the controls that reach the intended video. If a source cannot be accessed or captured, expose that capability outcome and bounded recovery.

Viewer and scout are separate playback instances. Successfully clearing an interstitial or playing inside Inspector does not prove Watch is playing. Synchronize source/session identity and required page state where supported, then independently verify viewer readiness.

**Additional current conflict:** `handleScoutWebPlaybackUpdate` still delegates physical scout transport to `computeScoutSyncCommand` with a fixed approximately seven-second lead and rewind threshold, while the coordinator schedules farther intervals. Make physical scout transport serve the coordinator's currently claimed work and source capabilities. Remove or reconcile the competing legacy command path. Do not make the scout seek blindly to the end of the horizon.

Retain a maximum horizon of **180 seconds of media time beyond the acknowledged viewer position**, capped by duration. During user Pause, freeze that horizon, let eligible acquisition/review/repair finish inside it, then idle. Prioritize near-term gaps and repairs; do not claim completed coverage for requested windows lacking acquired evidence.

## 8. Implement the agreed four modes and short tails consistently

The four viewer modes are:

1. **Off:** no viewer caption layer.
2. **Classic:** one bottom caption presentation.
3. **Bubbles:** stable bubble bodies without tails.
4. **Bubbles with tails:** the same stable bodies with short pointy tails directed toward the matched speaker's mouth.

Prevent overlapping native/app caption presentation. Scout-side native CC preferences must not accidentally toggle the viewer's chosen mode.

Use **12 dp nominal tail reach and an 18 dp hard maximum**, measured outward from the bubble perimeter, as the concrete design target for this repair. These values implement the user's “short and stubby” request; they are a specification, not a measurement from the recordings. The tail aims toward the mouth without reaching across the frame to touch it. Mouth distance must not lengthen it beyond the target. Shorten further for clearance when needed.

Current normal and shout paths allow 28 dp, while collision logic uses a separate normalized reach. Use one density-aware geometry result for rendering, collision decisions, and bounds. Keep the body anchored; smooth the tail direction with hysteresis. Switching between tailless and tailed modes must not restart body placement.

There is also an angle-dependent defect in `buildJitteredShoutPath`: it inserts a tail only when one of 32 sampled outline angles is within 0.08 radians of the aim. At a 5.625-degree midpoint, the nearest sample is about 0.098 radians away, so no tail is inserted despite a valid target. Repair path construction so legitimate target directions do not disappear between sampled vertices. Test this without relying on one screenshot or one bubble style.

Draw tails for confidently matched, visible speaking targets. For a genuinely uncertain/offscreen target, preserve readable captions and an appropriate speaker label; do not invent a face association to force a tail. Log the specific suppression reason for debugging. A colored offscreen arrow badge is not a rendered speech tail.

## 9. Preserve caption evidence, translation, and accessibility

Scout 1 must enable available native CC on its analysis pass and actually verify whether usable timed captions are obtainable. “CC requested” is not “caption evidence acquired.” Use supported source capabilities; do not claim access to inaccessible cross-origin captions, audio, or future frames.

When captions are absent but authorized usable audio exists, transcribe the original language first and translate that source-language text for display. Retain source text, timing, provenance, and translation linkage. Scout 2 checks independent source evidence and attribution; reviewing Scout 1's wording alone is not independent confirmation. Camera focus alone is not proof of who is speaking. Use available voice, face, and mouth evidence with explicit uncertainty, and preserve confirmed speaker identity across cuts and corrections.

Silence, missing audio, unsupported capture, loading, and failed acquisition are distinct outcomes. Verify three separate requirements: viewer audio follows the user's mute choice, scout playback remains inaudible, and the analysis input contains usable audio when transcription is attempted. Never infer speech from an unavailable input or label missing data as confirmed silence.

Keep Free CC Only free of new AI requests, including queued work after mode changes. Preserve user speaker corrections, explicit Pause, meaningful sound captions, readable speaker labels beyond color, font/accessibility settings, and the existing working visual design. Scope resource use to useful, bounded work.

## 10. Required regression proof

Exercise real production entry/publication/dispatch paths. Add focused tests for the concrete defects; do not add helper-only tests that mirror an implementation while bypassing the failing integration.

| Test | Required outcome |
|---|---|
| Source import parity | Same web URL through launcher, plus sheet, and supported share path creates the same correct source kind. Empty/malformed YouTube IDs cannot create projects. Valid YouTube, direct media, and local files retain correct routes. |
| Deferred review while held | Block Scout 2 temporarily, stage a draft, clear the blocker, and keep the viewer paused. Review drains or reaches an explicit safe deadline outcome without another transcription or Play tap. Patrol cannot consume the review silently. |
| Pending transport | Delay/omit a YouTube playing event; create web media after an early Play; reject web play; then acknowledge a valid command. No false playing/clock progress. A late acknowledgement after user Pause cannot resume. Test Off and Watch Now bypass/reset semantics independently. |
| Restored-cache replacement | Restore crossing/split/legacy cues, apply successive repairs, and check canonical overlay/transcript/persistence. One utterance appears once; genuine concurrent speakers, repeated later phrases, and user locks survive. |
| Web ownership | A controlled page adds a ready ad-like element while the selected main element is unready/buffering. Selection stays correct. Test interstitial recovery, document/element replacement, and stale callbacks. Reject false acquisition after rejected/clamped seeks or when currentTime updates before decoded evidence reaches the target. |
| Paused scout horizon | Pause, process eligible later intervals within the frozen 180-second horizon, repair, then idle. No repeated seven-second rewind loop. Seek/source change invalidates obsolete work. |
| Tail geometry and rendering | Sweep target angles, mouth distances, densities, ordinary/shout styles, and orientation. Assert perimeter reach never exceeds 18 dp, required tails actually render, body locks remain stable, and valid midpoint angles do not lose tails. |
| Real YouTube | Test the exact URL below on the identified candidate, both a clean project and restored cache. Capture the known duplicate/name examples and 00:45, 01:30, 02:15, 03:00 boundaries, plus actual planner boundaries. Complete a continuous full-video pass in one run and report every app hold/manual intervention. |
| Real web player | Exercise the supplied general-media entry path where accessible, plus the controlled interstitial/ad fixture. Prove Watch itself advances frames and media time after recovery; Inspector playback alone does not pass. Separately verify viewer mute/unmute, inaudible scout output, and usable analysis evidence. |

**Required YouTube URL:**  
https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_

Under healthy test conditions with playable prepared coverage, require zero app-initiated stops caused solely by chunk boundaries. Do not suppress failures, premark coverage verified, hard-code this video's dialogue, or count a user Play tap as automatic recovery.

Capture source/session/element IDs, command sequence and observed acknowledgement, interval/review status, accepted timeline revision, hold reason, and cache provenance in a concise diagnostic trace. Keep this engineering detail out of the normal viewing interface.

## 11. Final handoff

Deliver:

- Implemented source changes and a short root-cause-to-fix summary identifying the production paths changed or retired.
- An installable APK with application ID, version, source identity/manifest, and SHA-256.
- Regression results separated into **passed, failed, and blocked**, with commands or evidence for each.
- A short before/after recording or timestamped device evidence for uninterrupted YouTube playback, duplicate removal, short tails, and web viewer recovery.
- Remaining capability limits and genuinely unfinished work.

If Google AI Studio cannot run an emulator, install the app, access the live source, or execute a required capture path, complete the available code/build/tests and mark those live checks **blocked**. Provide the exact candidate and shortest remaining validation steps. Do not claim the user experience is fixed until the relevant runtime behavior is demonstrated.

# End implementation prompt

---

## Audit sources and verification basis

All source links below are pinned to the audited commit; later edits may move the named functions.

- [Build identity](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/build.gradle.kts) and [version badge binding](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/ui/DialogueLensScreen.kt).
- [Import sheet](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/ui/setup/MediaImportAndInfoSheets.kt), [source adapter](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/domain/media/MediaSourceAdapter.kt), and [launcher](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/ui/setup/StartLauncherScreen.kt).
- [ViewModel production integration](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt): `mergeReleasedIntervalPreservingLocks`, `stageAndReviewIntervalThroughReleaseGate`, auto-resume, `maybeRunProactiveChapterPatrol`, `updateTimeAndLayout`, cache restore, and `handleScoutWebPlaybackUpdate`.
- [Readiness controller](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/domain/playback/PlaybackReadinessController.kt), [workforce coordinator](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/domain/sentinel/SentinelWorkforceCoordinator.kt), and [lookahead planner](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/domain/analysis/LookaheadWindowPlanner.kt).
- [YouTube bridge](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/ui/player/YouTubeEmbeddedPlayer.kt) and [web player bridge](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/ui/player/WebStreamPlayer.kt).
- [Bubble renderer](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/ui/player/SpeechBalloonView.kt) and [layout engine](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/app/src/main/java/com/example/domain/layout/BalloonLayoutEngine.kt).
- [Existing handoff and reported verification limits](https://github.com/yoshiinthemist/Dialogue-lense/blob/7df5c0a6ef264e183379730913eef2c7ceabcc32/HANDOFF.md).

**Diagnostic probes actually executed during this audit:** unchanged extracted web selector functions switched from a committed-intent main element to a ready ad-like element in a mocked DOM; unchanged web play/sync functions reported playing after delayed media/rejected play; unchanged YouTube `dlPlay` returned “ok” while the fake player's observed state remained paused. Separately labeled source-rule models reproduced the crossing-cue merge and the shout-tail midpoint-angle omission. These are bounded source investigations, not Android rendering, device, or live-service tests.

**Recording coverage:** YouTube: 292 chronological samples across the full clip plus 129 dense transition frames. Web: 258 inspected samples, comprising a full-clip five-second survey and denser critical stretches. Original presentation timestamps were used. Audio signal analysis corroborated YouTube pauses and established the silent recorded web track; it did not validate spoken wording, speaker identity, or translation.

