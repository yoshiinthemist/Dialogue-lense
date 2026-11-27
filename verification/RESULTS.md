# DialogueLens — Verification Results (`AIStudio-Android-5`)

**Application ID:** `com.aistudio.dialoguelens.vkpzmx`  
**Namespace:** `com.example`  
**Version:** `34.0` (`versionCode = 34`, `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`)  
**Source Identity:** `workspace-snapshot` (pre-loaded Android `34.0` workspace + uncommitted repairs from this pass; `.git` metadata is absent in container)  
**Build Type & Signing:** `debug` signed with existing `debugConfig` (`debug.keystore` preserved)  
**Installable Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`167M`)  
**APK SHA-256:** `3b44e866a2c7ad15ffe90795d4cdc178493116bb240d5f04e7e0575ee9eabaf2`

---

## 1. Executed Build & Automated Verification Summary

| Check | Command / Tool | Outcome | Scope & Notes |
| --- | --- | --- | --- |
| Full Applet Compilation | `compile_applet` | **Pass** | Compiled full Kotlin/Compose Android project after all production edits. |
| Debug APK Assembly | `gradle assembleDebug --console=plain` | **Pass** | Built `app/build/outputs/apk/debug/app-debug.apk` (`3b44e866a2c7ad15ffe90795d4cdc178493116bb240d5f04e7e0575ee9eabaf2`). |
| Full JVM + Robolectric Unit Suite | `gradle :app:testDebugUnitTest --console=plain` | **Pass** (`190 / 190`) | **17 suites, 190 tests, 190 passed, 0 failures, 0 errors, 0 skipped.** |
| Android Emulator / ADB / Install-Upgrade | N/A in container | **Blocked** | AI Studio build container provides no Android Emulator and no ADB. |
| Live YouTube Video (`https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_`) | N/A in container | **Blocked** | Requires real Android device/emulator + WebView + network playback. |
| Live Android WebView `MediaRecorder` / `MediaCodec` | N/A in container | **Blocked** | Real Chromium WebView audio capture & output-mute hardware behavior unrun. |
| Deaf / Hard-of-Hearing Human Participant Evaluation (`R14`) | N/A in container | **Not run** | Requires consenting Deaf/HoH participants and real-device viewing setup. |

### Executed Test Suite Breakdown (`app/build/test-results/testDebugUnitTest/`)
- `ContractComplianceRobolectricTest`: **5 / 5 passed**
- `ContractR09R14R15RegressionTest`: **6 / 6 passed**
- `ContractR09R14R15RobolectricTest`: **4 / 4 passed**
- `DialogueLensAuditRepairsTest`: **12 / 12 passed**
- `DialogueLensEngineTest`: **65 / 65 passed**
- `DialogueLensStabilizationTest`: **4 / 4 passed**
- `DialogueLensV1OverhaulTest`: **16 / 16 passed**
- `DialogueLensV2OverhaulTest`: **13 / 13 passed**
- `DialogueLensViewModelAndRendererIntegrationRobolectricTest`: **4 / 4 passed**
- `ExampleRobolectricTest`: **2 / 2 passed**
- `ExampleUnitTest`: **1 / 1 passed**
- `OperationGoldenVideoRegressionTest`: **8 / 8 passed**
- `ProjectStoreLegacyApiExportRobolectricTest`: **1 / 1 passed**
- `RollingBoundarySchedulerTest`: **15 / 15 passed**
- `SpeechBalloonRendererStabilityRobolectricTest`: **1 / 1 passed**
- `TranscriptReliabilityTest`: **20 / 20 passed**
- `TwoScoutReleasePipelineTest`: **13 / 13 passed**

---

## 2. Requirements Traceability (`R01–R15`)

> Note: "Implementation Disposition" records what exists/was repaired in the native Android codebase. "Verification Status" separates JVM/Robolectric execution (**Pass**) from real-device/WebView/YouTube/human checks (**Blocked** / **Not run**).

| ID | Implementation Disposition | Primary Production Paths | Verification Status | Evidence / Blocker |
| --- | --- | --- | --- | --- |
| **R01** | **Implemented & verified in unit/Robolectric.** Single publication authority (`ReleasedTimelineStore`), canonical identity (`canonicalIdentityKey`), window-spanning overlap reconciliation, single work/claim owner (`SentinelWorkforceCoordinator`), single layout authority (`BalloonLayoutEngine`). Fixed ViewModel revision types, merge span propagation, and Heal supersession. | `domain/pipeline/ScoutReleasePipeline.kt`, `domain/sentinel/SentinelWorkforceCoordinator.kt`, `ui/state/DialogueLensViewModel.kt`, `domain/layout/BalloonLayoutEngine.kt`, `data/model/ProjectModels.kt` | **Pass (JVM/Robolectric)**; live multi-scout race on device **Blocked** | `TwoScoutReleasePipelineTest`, `DialogueLensAuditRepairsTest`, `DialogueLensV1OverhaulTest`, `RollingBoundarySchedulerTest` (**190/190 suite green**). Real YouTube runtime race **Blocked** (no emulator/ADB). |
| **R02** | **Implemented & verified in unit/Robolectric.** Separate tracking of acquired evidence, drafts, review completion, playable fallback, per-dimension verification (`SourceWordingCheckMethod`), verified dialogue lead vs contiguous playable lead, and affirmative silence vs absent evidence. | `domain/pipeline/ScoutReleasePipeline.kt`, `domain/analysis/TranscriptCoverageLedger.kt`, `data/model/ProjectModels.kt`, `ui/state/HumanReadableStatus.kt` | **Pass (JVM/Robolectric)** | `TwoScoutReleasePipelineTest`, `TranscriptReliabilityTest`, `RollingBoundarySchedulerTest`. |
| **R03** | **Implemented & verified in unit/Robolectric.** 6-state `YouTubeNativeCcState` model, separate viewer overlay vs Scout CC probe, `youtubeNativeCcState` recomputation inside `onYouTubePlaybackState`, source-first phrase translation, material source repair clearing obsolete target translations & incrementing `sourceTextRevision`, >45-cue bounded batching, Auto-Detect non-match. | `ui/state/DialogueLensViewModel.kt`, `ui/state/SubtitlePreGenerator.kt`, `ui/player/YouTubeEmbeddedPlayer.kt`, `data/ai/TranslationAiService.kt`, `domain/pipeline/ScoutReleasePipeline.kt` | **Pass (JVM/Robolectric)**; live YouTube iframe CC capability **Blocked** | `ContractComplianceRobolectricTest`, `DialogueLensAuditRepairsTest`, `TwoScoutReleasePipelineTest`, `OperationGoldenVideoRegressionTest`. Real YouTube CC WebView DOM behavior **Blocked** (no emulator/ADB). |
| **R04** | **Implemented & verified in unit/Robolectric.** Bidirectional `Scout2IndependentReviewer` checking unsupported lines and omitted source cues, empty-draft recovery, semantic translation/negation/quantity/idiom/entity checks, source-first coherent release. | `domain/pipeline/ScoutReleasePipeline.kt`, `ui/state/DialogueLensViewModel.kt` | **Pass (JVM/Robolectric)** | `TwoScoutReleasePipelineTest`, `ContractComplianceRobolectricTest`. |
| **R05** | **Implemented & verified in unit/Robolectric.** Hard rolling horizon `min(knownEnd, acknowledgedViewerPosMs + 180_000)` anchored to viewer position, chunk-clipping & remainder tracking across `>180s` timestamps, revalidation at dispatch & post-delay publication. Fixed paused async seek boundary deferral in `SentinelWorkforceCoordinator.onCommittedSeek`. | `domain/sentinel/SentinelWorkforceCoordinator.kt`, `domain/analysis/LookaheadWindowPlanner.kt`, `ui/state/DialogueLensViewModel.kt` | **Pass (JVM/Robolectric)**; live >3-min device run **Blocked** | `RollingBoundarySchedulerTest` (`15/15`), `DialogueLensV2OverhaulTest`. Real-time watch-through beyond 3 min **Blocked** (no emulator/ADB). |
| **R06** | **Implemented & verified in unit/Robolectric.** Priority tiers (imminent gaps & required review before distant lookahead), pause behavior within `[pos, pos+180s]`, bounded 4-clip capture queue, fair shared coordinator claims for Heal/Patrol, idle hysteresis & wake paths. | `domain/sentinel/SentinelWorkforceCoordinator.kt`, `ui/state/DialogueLensViewModel.kt`, `domain/analysis/HealBeaconEngine.kt` | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensV2OverhaulTest`. |
| **R07** | **Implemented & verified in unit/Robolectric.** Single `PlaybackReadinessController` policy; Pause strictly separate from Watch Now; fixed A04 seek self-acknowledgement in `DialogueLensViewModel.seekTo` / `updateVisualPresentationOnly` / interpolation ticker; YouTube error classification (`153` vs `101`/`150`/`5`). | `domain/playback/PlaybackReadinessController.kt`, `ui/state/DialogueLensViewModel.kt`, `ui/player/YouTubeEmbeddedPlayer.kt`, `domain/sentinel/SentinelWorkforceCoordinator.kt` | **Pass (JVM/Robolectric)**; live player & audio sync **Blocked** | `RollingBoundarySchedulerTest`, `ContractComplianceRobolectricTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensV2OverhaulTest`. Real player sync **Blocked** (no emulator/ADB). |
| **R08** | **Implemented & verified in unit/Robolectric.** Shared project activation contract, unified `seekGeneration` / `configGeneration`, preview freeze vs committed seek, immutable dispatch identity & locked-edit protection, cancellation of superseded work. | `ui/state/DialogueLensViewModel.kt`, `domain/pipeline/ScoutReleasePipeline.kt`, `domain/sentinel/SentinelWorkforceCoordinator.kt` | **Pass (JVM/Robolectric)** | `DialogueLensViewModelAndRendererIntegrationRobolectricTest`, `RollingBoundarySchedulerTest`, `TwoScoutReleasePipelineTest`. |
| **R09** | **Implemented & verified in unit/Robolectric.** Exactly 4 mutually exclusive primary modes (`OFF`, `CLASSIC`, `BALLOONS`, `BUBBLES_WITH_TAILS`), legacy `FIXED_ZONES`/`BOTH` migration, shared body anchor between bubble modes, fixed `BalloonLayoutEngine` short-drawn-tail obstruction check (`isShortDrawnTailObstructed`) allowing distant unobstructed aim targets, canonical identity keying across layout/Compose, retained textual speaker labels for tailless/offscreen cues in `SpeechBalloonView`. | `data/model/ProjectModels.kt`, `domain/layout/BalloonLayoutEngine.kt`, `ui/player/SpeechBalloonView.kt`, `ui/player/VideoPlayerStage.kt`, `domain/attribution/SpeakerAttributionEngine.kt` | **Pass (JVM/Robolectric)**; real video frame motion/ML Kit rendering **Blocked** | `DialogueLensEngineTest`, `DialogueLensStabilizationTest`, `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest`, `SpeechBalloonRendererStabilityRobolectricTest`. |
| **R10** | **Implemented & verified in unit/Robolectric.** Half-open `[startMs, endMs)` cue boundaries, raw rolling-window retention, captured audio decode verification (`AudioAnalyzer.inspectPcmWavBytes` & `inspectCapturedAudioChunk`), pinned web-media selection vs ad/iframe hijack, unified `WebNavigationGuard`. | `data/subtitle/SubtitleParser.kt`, `domain/analysis/AudioAnalyzer.kt`, `domain/analysis/WebNavigationGuard.kt`, `ui/player/WebStreamPlayer.kt` | **Pass (JVM/Robolectric)**; live WebView / MediaCodec decoding **Blocked** | `ContractR09R14R15RegressionTest`, `TranscriptReliabilityTest`, `DialogueLensV2OverhaulTest`. Live `MediaCodec` + WebView **Blocked** (no emulator/ADB). |
| **R11** | **Implemented & verified in unit/Robolectric.** `ZERO_CLOUD` (Free CC Only) guard enforced at dispatch, retry, queued capture, and final publication; quota circuit breaker; elapsed-clock deadlines while paused; WebView popup/ad/redirect protection without dropping healthy documents. | `ui/state/DialogueLensViewModel.kt`, `domain/analysis/WebNavigationGuard.kt`, `ui/player/WebStreamPlayer.kt`, `domain/sentinel/SentinelWorkforceCoordinator.kt` | **Pass (JVM/Robolectric)**; live web-page ad/popup run **Blocked** | `ContractR09R14R15RobolectricTest`, `ContractR09R14R15RegressionTest`, `RollingBoundarySchedulerTest`. Live WebView ad/popup check **Blocked**. |
| **R12** | **Implemented & verified in unit/Robolectric.** Common restoration path, monotonic revision freshness across backward seeks, atomic API 24+ compatible export/save & legacy verification migration (`ProjectStore`). | `data/project/ProjectStore.kt`, `ui/state/DialogueLensViewModel.kt` | **Pass (JVM/Robolectric)**; API 24–26 & device install/upgrade **Blocked** | `ProjectStoreLegacyApiExportRobolectricTest`, `RollingBoundarySchedulerTest`. Physical API 24–26 install/upgrade **Blocked**. |
| **R13** | **Implemented & verified in unit/Robolectric.** Heal Beacon states (`QUEUED`, `WAITING_FOR_EVIDENCE`, `CHECKING`, `REPAIRED`, `NEEDS_ATTENTION`, `SUPERSEDED`, `CANCELLED`), exhaustive UI switch handling in `HealBeaconUi.kt`, transient ack vs inspector diagnostics, honest fallback/quality status. | `domain/analysis/HealBeaconEngine.kt`, `ui/player/HealBeaconUi.kt`, `ui/state/HumanReadableStatus.kt`, `ui/state/DialogueLensViewModel.kt` | **Pass (JVM/Robolectric)** | `DialogueLensV2OverhaulTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensViewModelAndRendererIntegrationRobolectricTest`. |
| **R14** | **Implemented (accessibility foundations) & unit/Robolectric checked; Deaf/HoH evaluation Not run.** Same accepted dialogue/SFX across Classic & bubble modes, playhead-gated character name reveal (`visibleDisplayNameAt` including backward seek), retained textual labels in tailless/offscreen mode, accessible font/high-contrast/opacity presets (`CaptionAccessibilityContrast >= 4.5:1`), reduced-motion option. | `data/model/ProjectModels.kt`, `ui/player/SpeechBalloonView.kt`, `ui/player/VideoPlayerStage.kt`, `ui/player/CinemaIntroOverlay.kt`, `ui/settings/BalloonStyleSheet.kt` | **Partial**: automated functional/contrast checks **Pass**; **Deaf/HoH participant evaluation Not run** | `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest`, `ProjectStoreLegacyApiExportRobolectricTest`. Human Deaf/HoH evaluation **Not run** (requires human participants + device setup). |
| **R15** | **Implemented (diagnostics, WAV/PCM energy classifier, bounded capture queue, ownership/lifecycle) & unit/Robolectric checked; Live WebView capture Blocked.** Independent output-mute tracking vs capture health states (`WAITING_FOR_MEDIA`, `UNSUPPORTED`, `RECORDER_STARTED`, `USABLE_DECODED_INPUT`, `CONFIRMED_SILENCE`, `DECODE_FAILED`, `UNAVAILABLE_OR_PROTECTED`), generation-owned capture disposal, non-coalescing interval accounting. | `domain/analysis/AudioAnalyzer.kt`, `domain/analysis/ScoutCaptureDiagnostics.kt`, `ui/player/WebStreamPlayer.kt`, `ui/state/DialogueLensViewModel.kt` | **Partial**: WAV/queue/lifecycle checks **Pass**; **Live WebView `captureStream()`/`MediaRecorder` Blocked** | `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest`. Real Chromium WebView audio capture while scout muted **Blocked** (no emulator/ADB). |

---

## 3. Recording Evidence (`E01–E11`) & Source Audit (`A01–A09`) Mapping

| ID | Mechanism / Defect Summary | Production Fix / Code State | Automated Test Status | Real-Video Replay (`RzagPoxaolQ`) |
| --- | --- | --- | --- | --- |
| **E01 / A08** | Native CC vs app overlay separation & truthful capability states. | Separate viewer CC control vs scout timedtext/caption probe; retryable setup; `youtubeNativeCcState` recomputed on observed playback advance. | **Pass** (`ContractComplianceRobolectricTest`) | **Blocked** |
| **E02 / A01** | Competing descriptions of cheering/applause vs distinct simultaneous SFX. | Canonical reconciliation of overlapping SFX revisions while keeping simultaneous distinct events. | **Pass** (`DialogueLensAuditRepairsTest`, `TwoScoutReleasePipelineTest`) | **Blocked** |
| **E03 / A01** | Two versions of line coexisting on overlay vs one in transcript. | Single accepted snapshot published via `ReleasedTimelineStore`; overlapping window-spanning replacement. | **Pass** (`TwoScoutReleasePipelineTest`, `DialogueLensV1OverhaulTest`) | **Blocked** |
| **E04 / A02** | Source name/spelling regression & lower-third entity preservation. | Provenance & source-first repair rules; `Scout2IndependentReviewer` preserves grounded entity spellings without overwriting source on target edits. | **Pass** (`TwoScoutReleasePipelineTest`, `DialogueLensAuditRepairsTest`) | **Blocked** |
| **E05 / A01** | Rough vs clean bubble coexistence across repair boundary. | `canonicalIdentityKey` + source-span overlap reconciliation replaces active cue cleanly across overlay and transcript. | **Pass** (`TwoScoutReleasePipelineTest`, `DialogueLensViewModelAndRendererIntegrationRobolectricTest`) | **Blocked** |
| **E06 / A03** | Bubble jumping face->bottom without cut; color-parity lane jump. | Neutral fallback uses committed placement & stable identity (`canonicalIdentityKey`), no `accentIndex % 2` parity jump; short-tail checks. | **Pass** (`DialogueLensEngineTest`, `DialogueLensStabilizationTest`) | **Blocked** |
| **E07 / A01** | Multiple red bubbles repeating portions of one dialogue. | Post-review canonicalization & single-instance render key `canonicalIdentityKey`. | **Pass** (`TwoScoutReleasePipelineTest`, `TranscriptReliabilityTest`) | **Blocked** |
| **E08 / A03** | Bubble vs transcript speaker label mismatch & animation desync. | Speaker resolved once per accepted event revision; header, accent, outline, transcript read from same accepted snapshot. | **Pass** (`TwoScoutReleasePipelineTest`, `DialogueLensViewModelAndRendererIntegrationRobolectricTest`) | **Blocked** |
| **E09 / A01** | Named clean dialogue + raw markers/fragments coexisting. | Rolling control stripping & post-normalization deduplication in `SubtitleParser` + `ScoutReleasePipeline`. | **Pass** (`TranscriptReliabilityTest`, `DialogueLensAuditRepairsTest`) | **Blocked** |
| **E10 / A01 / A03** | Duplicate yellow bubbles overlapping versions of same lines. | One active render instance per `canonicalIdentityKey`; updates in place without duplicate entry layers. | **Pass** (`SpeechBalloonRendererStabilityRobolectricTest`, `DialogueLensEngineTest`) | **Blocked** |
| **E11 / A06 / A08** | Lingering Heal banner & non-terminal job status. | Transient heal acknowledgement; explicit `HealBeaconStatus` transitions (`SUPERSEDED`/`CANCELLED` wired cleanly in `HealBeaconUi.kt`). | **Pass** (`DialogueLensV2OverhaulTest`, `RollingBoundarySchedulerTest`) | **Blocked** |
| **A04** | `seekTo()` self-acknowledging `currentTimeMs` and scheduler boundary before player feedback. | Removed optimistic `currentTimeMs = targetMs` in `updateVisualPresentationOnly`; paused clock ticker during `isSeekInFlight`; deferred `acknowledgedViewerPosMs` until observed seek landing (even while paused). | **Pass** (`ContractComplianceRobolectricTest`, `RollingBoundarySchedulerTest`, `DialogueLensV2OverhaulTest`) | **Blocked** |
| **A05** | Artificial YT readiness timer, premature command consumption, caption mode vs Pause holds. | Readiness wait relies on genuine player events; `OFF` releases caption holds without overriding user `PAUSE`. | **Pass** (`ContractComplianceRobolectricTest`, `ContractR09R14R15RobolectricTest`) | **Blocked** |
| **A06** | Capture & Heal bypassing shared scheduling/generation checks. | Bounded 4-clip queue, `ZERO_CLOUD` + generation revalidation, shared Heal supersession/cancellation. | **Pass** (`ContractR09R14R15RobolectricTest`, `RollingBoundarySchedulerTest`) | **Blocked** |
| **A07** | Web player selection hijacked by autoplaying ads & JS/native nav mismatch. | Pinned selected media/document identity; unified `WebNavigationGuard` policy across native & injected JS layers. | **Pass** (`ContractR09R14R15RegressionTest`, `DialogueLensV2OverhaulTest`) | **Blocked** |
| **A09** | Scout mute vs WebView `MediaRecorder` capture health/span assumptions. | `ScoutCaptureDiagnostics` tracks mute separately from capture health; `AudioAnalyzer.inspectPcmWavBytes` decodes PCM/WAV energy vs silence vs corruption; actual clip span accounting. | **Pass (unit/Robolectric)**; WebView live capture **Blocked** | **Blocked** |

---

## 4. Acceptance Matrix (`T01–T40`)

| ID | Scope | Verification Status | Exact Evidence / Remaining Blocker |
| --- | --- | --- | --- |
| **T01** | Readiness hold, Pause vs Watch Now, unified policy | **Pass (JVM/Robolectric)** | `DialogueLensV2OverhaulTest`, `ContractComplianceRobolectricTest`, `ContractR09R14R15RobolectricTest`. |
| **T02** | Partial draft omitted-cue & empty-draft recovery | **Pass (JVM/Robolectric)** | `TwoScoutReleasePipelineTest`, `DialogueLensAuditRepairsTest`. |
| **T03** | Unverified wording/negation/idiom/Auto-Detect/code-switch/kanji/matching lang | **Pass (JVM/Robolectric)** | `ContractComplianceRobolectricTest`, `TwoScoutReleasePipelineTest`, `OperationGoldenVideoRegressionTest`. |
| **T04** | Apply Text edit protection, target invalidation on source repair, stale rejection | **Pass (JVM/Robolectric)** | `DialogueLensAuditRepairsTest`, `TwoScoutReleasePipelineTest`, `ContractComplianceRobolectricTest`. |
| **T05** | Pause at 02:00 keeps 05:00 boundary, imminent gap priority, idle stop | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest` (`viewModelIntegration_*` and scheduler priority tests). |
| **T06** | Fractional & overlapping chunk boundary clipping, end-of-video cap | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest`. |
| **T07** | Seek/resume advance boundary across 0.5× / 1× / 2× speeds | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest`. |
| **T08** | Scrubber preview freeze/wake & seek cancellation while paused/decoding | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest`, `DialogueLensV2OverhaulTest`. |
| **T09** | Full track cache without full inference; >3-min (`04:30`) eligibility & reopen | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest`, `TranscriptReliabilityTest`. |
| **T10** | Mid-video gap stops continuous lead; fallback honest; exhausted exit buffering | **Pass (JVM/Robolectric)** | `TwoScoutReleasePipelineTest`, `RollingBoundarySchedulerTest`. |
| **T11** | Exhausted partial budget & clock deadline while paused | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest`, `TwoScoutReleasePipelineTest`. |
| **T12** | `ZERO_CLOUD` (Free CC Only) cancels/blocks AI attempts & quota circuit | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest`, `ContractR09R14R15RobolectricTest`. |
| **T13** | Cross-project isolation on load + late frames/results/Undo | **Pass (JVM/Robolectric)** | `DialogueLensViewModelAndRendererIntegrationRobolectricTest`. |
| **T14** | YouTube Scout 1 CC discovery, activation retry, active-track swaps | **Pass (JVM/Robolectric)**; Live YT **Blocked** | `ContractComplianceRobolectricTest` passes; live YouTube iframe DOM test **Blocked** (no emulator/ADB). |
| **T15** | Local A/B speaker isolation, null tracks, immutable slot mapping | **Pass (JVM/Robolectric)** | `DialogueLensEngineTest`, `DialogueLensStabilizationTest`. |
| **T16** | Exclusive 4 modes, legacy migration, body anchor preservation, 360° short tail | **Pass (JVM/Robolectric)**; Live video **Blocked** | `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensEngineTest`, `DialogueLensStabilizationTest`. |
| **T17** | Cut/seek/rotate invalidation, coordinates, collisions | **Pass (JVM/Robolectric)**; Live rotation **Blocked** | `DialogueLensEngineTest`, `DialogueLensV1OverhaulTest`, `SpeechBalloonRendererStabilityRobolectricTest`. |
| **T18** | Future-placement reassessment, neutral non-reattach, empty vs failure distinction | **Pass (JVM/Robolectric)** | `DialogueLensEngineTest`, `DialogueLensStabilizationTest`. |
| **T19** | Expanding/repeated cues, 151-char split bounds, >45 foreign cues batching | **Pass (JVM/Robolectric)** | `OperationGoldenVideoRegressionTest`, `TranscriptReliabilityTest`, `ContractR09R14R15RegressionTest`. |
| **T20** | Audio decode validation, sample rates, partial coverage accounting | **Pass (JVM/Robolectric)**; HW `MediaCodec` **Blocked** | `ContractR09R14R15RegressionTest`; live Android `MediaCodec` WebM/AAC test **Blocked**. |
| **T21** | Web-media pinned selection vs ads, popups, redirects | **Pass (JVM/Robolectric)**; Live WebView **Blocked** | `ContractR09R14R15RegressionTest`, `DialogueLensV2OverhaulTest`; live Android WebView **Blocked**. |
| **T22** | Save/progress race ordering, backward seeks, reload generations & exports | **Pass (JVM/Robolectric)** | `ProjectStoreLegacyApiExportRobolectricTest`, `RollingBoundarySchedulerTest`. |
| **T23** | Clean build, API 24/25/26 storage/desugaring, install & update | **Blocked (Device Install/API 24–26)** | Clean APK build & `ProjectStoreLegacyApiExportRobolectricTest` **Pass**; real install/upgrade on API 24–26 **Blocked** (no emulator/ADB). |
| **T24** | Heal beacon expiry, status/icon agreement, prepared span display | **Pass (JVM/Robolectric)** | `DialogueLensV2OverhaulTest`, `DialogueLensViewModelAndRendererIntegrationRobolectricTest`. |
| **T25** | Repro E03–E05, E07–E10 through ViewModel/release store/renderer | **Pass (JVM/Robolectric)** | `TwoScoutReleasePipelineTest`, `SpeechBalloonRendererStabilityRobolectricTest`, `DialogueLensAuditRepairsTest`. |
| **T26** | Split/rejoin unique keys, event-record churn continuity, shared semantic color anim | **Pass (JVM/Robolectric)** | `TranscriptReliabilityTest`, `ContractR09R14R15RegressionTest`, `DialogueLensEngineTest`. |
| **T27** | Async seek non-self-ack, out-of-order callbacks, seek >+180s paused & playing | **Pass (JVM/Robolectric)**; Live YT **Blocked** | `RollingBoundarySchedulerTest`, `ContractComplianceRobolectricTest`, `DialogueLensV2OverhaulTest`; real player lag test **Blocked**. |
| **T28** | YouTube readiness delay, buffering vs playing, blocked scripted play, error 153 vs 101/150 | **Pass (JVM/Robolectric)**; Live YT **Blocked** | `ContractR09R14R15RobolectricTest` & `DialogueLensV2OverhaulTest` **Pass**; real YouTube iframe player **Blocked**. |
| **T29** | Holds at 00:45/01:30/02:15/03:00 + P4 intentional pause regression | **Blocked (Live Replay)**; Logic **Pass** | Readiness/pause logic passes in `RollingBoundarySchedulerTest`/`DialogueLensV2OverhaulTest`; live replay **Blocked**. |
| **T30** | Flooded audio callbacks during gap/Heal + mid-repair mode/lang/seek change | **Pass (JVM/Robolectric)** | `ContractR09R14R15RobolectricTest`. |
| **T31** | Adversarial web ad/popup/redirect/same-URL token refresh/cross-origin isolate | **Blocked (Live WebView)**; Guard logic **Pass** | `ContractR09R14R15RegressionTest` (`WebNavigationGuard`) **Pass**; real WebView DOM/iframe run **Blocked**. |
| **T32** | Heal beacon lifecycle paused/playing across all 4 modes | **Pass (JVM/Robolectric)** | `ContractR09R14R15RobolectricTest`, `DialogueLensV2OverhaulTest`. |
| **T33** | Muted viewer functional parity (dialogue, SFX, music, lyrics across modes) | **Pass (JVM/Robolectric)**; Live device **Blocked** | `ContractR09R14R15RegressionTest`, `DialogueLensEngineTest`. Real muted viewing pass **Blocked**. |
| **T34** | Textual speaker distinction, playhead-gated character reveal & backward-seek re-hide | **Pass (JVM/Robolectric)** | `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest` (live Compose `SpeechBalloonView`). |
| **T35** | Large text, long names, high contrast (>=4.5:1 composited), persistence | **Pass (JVM/Robolectric)**; Visual device **Blocked** | `ContractR09R14R15RegressionTest` (`CaptionAccessibilityContrast`), `ProjectStoreLegacyApiExportRobolectricTest`. |
| **T36** | Accessibility labels/focus/reduced motion on mode switch | **Pass (Robolectric UI)**; TalkBack device **Blocked** | `ContractR09R14R15RobolectricTest`; physical TalkBack navigation **Blocked**. |
| **T37** | Mixed speech/SFX translation provenance; true silence vs music vs unavailable capture | **Pass (JVM/Robolectric)** | `ContractR09R14R15RegressionTest`, `TwoScoutReleasePipelineTest`. |
| **T38** | Android WebView known-speech capture while scout output stays inaudible | **Blocked (No WebView/Audio HW)** | `ContractR09R14R15RobolectricTest` checks queue/diagnostics; real WebView `MediaRecorder` + audio output check **Blocked**. |
| **T39** | WebView capture failure modes (no API/MIME/track, decode fail, silent, DRM, ZERO_CLOUD) | **Partial**: Decode/state/ZERO_CLOUD **Pass**; **Live WebView Blocked** | `ContractR09R14R15RegressionTest` (WAV decode/silence/corrupt) & `ContractR09R14R15RobolectricTest` **Pass**; live WebView states **Blocked**. |
| **T40** | Element/track replacement, seek/rate, late blob rejection, fragment gap accounting | **Partial**: Queue/state **Pass**; **Live MediaRecorder Blocked** | `ContractR09R14R15RobolectricTest` **Pass**; live JS/WebView `MediaRecorder` replacement **Blocked**. |

---

## 5. Required Device-Only Runs Remaining (**Blocked / Not run**)

1. **Required Real-Video Watch-Through (`https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_`) — Blocked:** Create a fresh project in DialogueLens on an Android device/emulator, run a full `1×` watch-through plus interactive pause/seek/speed/mode-switch pass covering `00:45`, `01:30`, `01:59–02:01`, `02:15`, `02:29`, `03:00`, `04:02–04:05`, `04:33`, `05:21–05:23`, `05:55–06:08`, and `06:31–06:35` with video/audio capture and Sentinel Inspector export.
2. **Authorized Foreign-Language Video Fixture — Blocked:** Run native-CC reuse, ASR fallback, source correction, and >45-cue translation publication on device.
3. **Authorized WebView Capture Fixture (`R15 / T38–T40`) — Blocked:** Verify WebView `captureStream()`/`MediaRecorder` successive decodable chunks while scout playback remains physically inaudible on real Android Chromium WebView.
4. **Deaf / Hard-of-Hearing Human Evaluation (`R14`) — Not run:** Conduct evaluation with consenting Deaf/HoH viewers on real hardware before making comprehension/comfort claims.
