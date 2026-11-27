# DialogueLens — Verification Results (`v6 Regression Repair / 35.0`)

**Application ID:** `com.aistudio.dialoguelens.vkpzmx`  
**Namespace:** `com.example`  
**Version:** `35.0` (`versionCode = 35`, `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`; Studio badge bound to `"v${BuildConfig.VERSION_NAME}"` at `DialogueLensScreen.kt:2030`)  
**Upstream Baseline Commit:** `7df5c0a6ef264e183379730913eef2c7ceabcc32` (`.git` metadata absent in container)  
**Reproducible Source Manifest:** `verification/SOURCE_MANIFEST.sha256` (`107` files, SHA-256 `8efbdf61ea58da3c471aac8c4857101af796ee1b145e224cc6c788703d78d5e7`, short digest `8efbdf61`)  
**Build Type & Signing:** `debug` signed with existing `debugConfig` (`debug.keystore` preserved)  
**Installable Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`167M`)  
**APK SHA-256:** `62a66d760a3a1186ddb9e267dec6d0c6a219844c85b318069ee8bcf8a3133ab0`

---

## 1. Executed Build & Verification Results (**Passed / Failed / Blocked**)

### Passed (Verified In-Container)
| Check | Command / Tool | Result | Notes |
| --- | --- | --- | --- |
| Full JVM + Robolectric Unit Suite | `gradle :app:testDebugUnitTest --console=plain` | **Pass (`197 / 197`)** | **18 suites, 197 tests, 197 passed, 0 failures, 0 errors, 0 skipped.** Includes all 7 `DialogueLensV6RegressionTest` production-path checks. |
| Debug APK Assembly | `gradle assembleDebug --console=plain` | **Pass** | Built `app/build/outputs/apk/debug/app-debug.apk` (`167M`, SHA-256 `62a66d760a3a1186ddb9e267dec6d0c6a219844c85b318069ee8bcf8a3133ab0`). |
| Full Applet Compilation | `compile_applet` | **Pass** | Verified clean Kotlin/Compose build after final production edits. |
| Reproducible Source Manifest | `find ... \| LC_ALL=C sort \| xargs sha256sum` | **Pass** | `verification/SOURCE_MANIFEST.sha256` covers 107 source/config/test files (`8efbdf61ea58da3c471aac8c4857101af796ee1b145e224cc6c788703d78d5e7`). |

### Failed
- **None (`0` automated checks failed after final edits).**

### Blocked / Not Run (Environment Lacks Emulator, ADB, Live WebView & Human Participants)
| Check | Target | Outcome | Exact Blocker |
| --- | --- | --- | --- |
| Android Install & Upgrade Verification | `app-debug.apk` on API 24–36 (`T23`) | **Blocked** | No Android Emulator and no ADB exist in the AI Studio container. |
| Live YouTube Clean & Restored-Cache Watch-Through | `https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_` (`T14`, `T27–T29`, `E01–E11`) | **Blocked** | Requires live Android WebView + network YouTube iframe playback and screen/audio capture. |
| Live Web-Media Interstitial, Watch Recovery & Capture | General web media + `captureStream()` / `MediaRecorder` / `MediaCodec` (`R15`, `T20`, `T31`, `T38–T40`) | **Blocked** | Requires live Android Chromium WebView + device audio/video codec pipeline. |
| Deaf / Hard-of-Hearing Participant Evaluation | Comprehension/comfort evaluation (`R14`) | **Not run** | Requires consenting Deaf/HoH participants on physical hardware. |

### Per-Suite Test Breakdown (`app/build/test-results/testDebugUnitTest/`)
- `ContractComplianceRobolectricTest`: **5 / 5 passed**
- `ContractR09R14R15RegressionTest`: **6 / 6 passed**
- `ContractR09R14R15RobolectricTest`: **4 / 4 passed**
- `DialogueLensAuditRepairsTest`: **12 / 12 passed**
- `DialogueLensEngineTest`: **65 / 65 passed**
- `DialogueLensStabilizationTest`: **4 / 4 passed**
- `DialogueLensV1OverhaulTest`: **16 / 16 passed**
- `DialogueLensV2OverhaulTest`: **13 / 13 passed**
- `DialogueLensV6RegressionTest`: **7 / 7 passed**
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

## 2. V6 Required Regression Matrix (`v6 §10`)

| V6 Test | Scope | Outcome | Evidence / Blocker |
| --- | --- | --- | --- |
| `sourceImportParity_enforcesSharedActivationContractAcrossLauncherSheetShareAndViewModel` | Same web URL across launcher, `+` sheet, share entry, and `ViewModel`; rejects foreign-host 11-char segments as YT IDs; validates `youtu.be/RzagPoxaolQ`, direct media, and local files | **Pass (Robolectric)** | `DialogueLensV6RegressionTest` |
| `deferredReviewWhileHeld_drainsWithoutReTranscribeOrPlayTapAndPatrolCannotConsumeIt` | Blocks Scout 2, stages draft, checks patrol cannot steal `SCOUT2_REQUIRED_REVIEW`, releases blocker while paused/held, verifies drain without Play tap + overdue fallback while paused + DataStore `quotaSaverModeFlow` race protection | **Pass (Robolectric)** | `DialogueLensV6RegressionTest` |
| `pendingTransport_gatesPlayUntilAckBlocksLateAckAfterPauseRejectsWebPlayAndHonorsOffWatchNow` | Delays YT playing ack, delays web media creation, rejects web play, blocks late ack after user `PAUSE`, verifies `OFF` Watch Now bypass reset and `PLAYBACK_OR_ANALYSIS_ERROR` clearing | **Pass (Robolectric)** | `DialogueLensV6RegressionTest` |
| `restoredCacheReplacement_preventsCrossingCueDoublesPreservesNamesConcurrentSpeakersRepeatsSfxAndLocks` | Reconciles crossing 28–34s/30–35s restored-cache cues, rejects ALL-CAPS phonetic degradation against mixed-case entities (`isSuspiciousAllCapsPhoneticDegradation`), preserves concurrent speakers, later repeats, SFX, and user locks | **Pass (Robolectric)** | `DialogueLensV6RegressionTest` |
| `webOwnership_commitsSelectedElementBlocksAdRedirectsAndRejectsStaleSeekEvidence` | Keeps committed web element vs ready ad injection, verifies `WebNavigationGuard` interstitial/redirect/generation rules, rejects stale-seek evidence | **Pass (Robolectric)** | `DialogueLensV6RegressionTest` |
| `pausedScoutHorizon_processesInside180sIdlesCleanlyWithZeroRewindLoopAndInvalidatesOnSeek` | Processes within 180s horizon while paused, coordinates physical transport without 7s rewind loop, invalidates obsolete work on seek | **Pass (Robolectric)** | `DialogueLensV6RegressionTest` |
| `tailGeometryAndRendering_enforces18DpMaxFixesShoutMidpointLogsSuppressionAndKeepsBodyStable` | Sweeps 360° including 5.625° midpoints, densities, distances, ordinary/shout styles; enforces `<= 18dp` reach; logs suppression reasons; verifies Compose body/tag/tail stability across `BALLOONS` <-> `BUBBLES_WITH_TAILS` | **Pass (Robolectric + Compose)** | `DialogueLensV6RegressionTest` |
| Real YouTube (`https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_`) | Clean + restored-cache continuous `1×` device pass at `00:45`, `01:30`, `02:15`, `03:00`, `00:32`, `01:19–01:21`, `03:52–03:56` | **Blocked** | No Android Emulator or ADB in container |
| Real Web Player | Live Watch progression after `"Continue to Video"` interstitial + viewer mute + inaudible scout output + live `MediaRecorder` capture | **Blocked** | No real Chromium WebView / Android audio HW in container |

---

## 3. Requirements Traceability (`R01–R15`)

| ID | Implementation Disposition | Primary Production Paths | Verification Status | Evidence / Blocker |
| --- | --- | --- | --- | --- |
| **R01** | **Implemented & verified in unit/Robolectric.** Single publication authority (`ReleasedTimelineStore`), canonical identity (`canonicalIdentityKey`), crossing-boundary lineage replacement, single work/claim owner (`SentinelWorkforceCoordinator`), single layout authority (`BalloonLayoutEngine`). | `domain/pipeline/ScoutReleasePipeline.kt`, `domain/sentinel/SentinelWorkforceCoordinator.kt`, `ui/state/DialogueLensViewModel.kt`, `domain/layout/BalloonLayoutEngine.kt`, `data/model/ProjectModels.kt` | **Pass (JVM/Robolectric)**; live multi-scout race on device **Blocked** | `TwoScoutReleasePipelineTest`, `DialogueLensAuditRepairsTest`, `DialogueLensV1OverhaulTest`, `RollingBoundarySchedulerTest`, `DialogueLensV6RegressionTest`. |
| **R02** | **Implemented & verified in unit/Robolectric.** Separate tracking of acquired evidence, drafts, review completion, playable fallback, per-dimension verification, verified dialogue lead vs contiguous playable lead, affirmative silence vs absent evidence. | `domain/pipeline/ScoutReleasePipeline.kt`, `domain/analysis/TranscriptCoverageLedger.kt`, `data/model/ProjectModels.kt`, `ui/state/HumanReadableStatus.kt` | **Pass (JVM/Robolectric)** | `TwoScoutReleasePipelineTest`, `TranscriptReliabilityTest`, `RollingBoundarySchedulerTest`. |
| **R03** | **Implemented & verified in unit/Robolectric.** 6-state `YouTubeNativeCcState`, separate viewer overlay vs Scout CC probe, source-first phrase translation, >45-cue bounded batching, phonetic ALL-CAPS entity degradation guard, Auto-Detect non-match. | `ui/state/DialogueLensViewModel.kt`, `ui/state/SubtitlePreGenerator.kt`, `ui/player/YouTubeEmbeddedPlayer.kt`, `data/ai/TranslationAiService.kt`, `data/ai/OnDeviceTranslatorService.kt`, `domain/pipeline/ScoutReleasePipeline.kt` | **Pass (JVM/Robolectric)**; live YouTube iframe CC capability **Blocked** | `ContractComplianceRobolectricTest`, `DialogueLensAuditRepairsTest`, `TwoScoutReleasePipelineTest`, `OperationGoldenVideoRegressionTest`, `DialogueLensV6RegressionTest`. |
| **R04** | **Implemented & verified in unit/Robolectric.** Bidirectional `Scout2IndependentReviewer` + deferred required-review drain loop (`startScout2ReviewAndDeadlineLoop`) + task-type claim filtering (`allowedKinds`). | `domain/pipeline/ScoutReleasePipeline.kt`, `domain/sentinel/SentinelWorkforceCoordinator.kt`, `ui/state/DialogueLensViewModel.kt` | **Pass (JVM/Robolectric)** | `TwoScoutReleasePipelineTest`, `ContractComplianceRobolectricTest`, `DialogueLensV6RegressionTest`. |
| **R05** | **Implemented & verified in unit/Robolectric.** Rolling 180s horizon anchored to acknowledged viewer position, `LookaheadWindowPlanner.computeCoordinatorScoutCommand` preventing 7s rewind loops, paused async seek deferral. | `domain/sentinel/SentinelWorkforceCoordinator.kt`, `domain/analysis/LookaheadWindowPlanner.kt`, `ui/state/DialogueLensViewModel.kt` | **Pass (JVM/Robolectric)**; live >3-min device run **Blocked** | `RollingBoundarySchedulerTest`, `DialogueLensV2OverhaulTest`, `DialogueLensV6RegressionTest`. |
| **R06** | **Implemented & verified in unit/Robolectric.** Priority tiers, pause behavior within `[pos, pos+180s]`, bounded 4-clip capture queue, fair shared coordinator claims, `quotaSaverModeFlow` DataStore override guard. | `domain/sentinel/SentinelWorkforceCoordinator.kt`, `ui/state/DialogueLensViewModel.kt`, `domain/analysis/HealBeaconEngine.kt` | **Pass (JVM/Robolectric)** | `RollingBoundarySchedulerTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensV2OverhaulTest`, `DialogueLensV6RegressionTest`. |
| **R07** | **Implemented & verified in unit/Robolectric.** Single `PlaybackReadinessController`, pending transport command gating (`onPlayCommandResult`, `onStreamPlayCommandResult`), `OFF` Watch Now bypass reset, `isMediaHardwareError` -> `"Video failed to load"`, `dismissScoutCatchUpBuffering()` intro cancel. | `domain/playback/PlaybackReadinessController.kt`, `ui/state/DialogueLensViewModel.kt`, `ui/player/YouTubeEmbeddedPlayer.kt`, `ui/player/WebStreamPlayer.kt`, `ui/player/CinemaIntroOverlay.kt` | **Pass (JVM/Robolectric)**; live player & audio sync **Blocked** | `RollingBoundarySchedulerTest`, `ContractComplianceRobolectricTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensV2OverhaulTest`, `DialogueLensV6RegressionTest`. |
| **R08** | **Implemented & verified in unit/Robolectric.** Consolidated `MediaSourceAdapterRegistry.resolveForActivation`, unified `seekGeneration` / `configGeneration`, preview freeze vs committed seek, stale rejection. | `domain/media/MediaSourceAdapter.kt`, `ui/setup/MediaImportAndInfoSheets.kt`, `ui/setup/StartLauncherScreen.kt`, `ui/state/DialogueLensViewModel.kt`, `domain/pipeline/ScoutReleasePipeline.kt` | **Pass (JVM/Robolectric)** | `DialogueLensViewModelAndRendererIntegrationRobolectricTest`, `RollingBoundarySchedulerTest`, `TwoScoutReleasePipelineTest`, `DialogueLensV6RegressionTest`. |
| **R09** | **Implemented & verified in unit/Robolectric.** 4 mutually exclusive primary modes, shared body anchor, `TailGeometry` (`12dp` nominal / `18dp` max), `computeShoutBalloonVertices` midpoint-angle fix, `lastTailSuppressionReasons`, canonical identity keying, textual labels for tailless/offscreen cues. | `domain/layout/TailGeometry.kt`, `domain/layout/BalloonLayoutEngine.kt`, `ui/player/SpeechBalloonView.kt`, `ui/player/VideoPlayerStage.kt`, `data/model/ProjectModels.kt` | **Pass (JVM/Robolectric)**; real video frame motion/ML Kit **Blocked** | `DialogueLensEngineTest`, `DialogueLensStabilizationTest`, `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest`, `SpeechBalloonRendererStabilityRobolectricTest`, `DialogueLensV6RegressionTest`. |
| **R10** | **Implemented & verified in unit/Robolectric.** Half-open cue boundaries, PCM/WAV decode checks, committed web-element ownership (`activeDocGeneration`, `__dlCommittedElementId`), `awaitingSeekSettle`, unified `WebNavigationGuard`. | `data/subtitle/SubtitleParser.kt`, `domain/analysis/AudioAnalyzer.kt`, `domain/analysis/WebNavigationGuard.kt`, `ui/player/WebStreamPlayer.kt` | **Pass (JVM/Robolectric)**; live WebView / `MediaCodec` **Blocked** | `ContractR09R14R15RegressionTest`, `TranscriptReliabilityTest`, `DialogueLensV2OverhaulTest`, `DialogueLensV6RegressionTest`. |
| **R11** | **Implemented & verified in unit/Robolectric.** `ZERO_CLOUD` guard across dispatch/retry/queue/publish, quota circuit breaker, elapsed-clock deadlines while paused, interstitial/popup guard + `CookieManager.flush()`. | `ui/state/DialogueLensViewModel.kt`, `domain/analysis/WebNavigationGuard.kt`, `ui/player/WebStreamPlayer.kt`, `domain/sentinel/SentinelWorkforceCoordinator.kt` | **Pass (JVM/Robolectric)**; live web-page ad/popup run **Blocked** | `ContractR09R14R15RobolectricTest`, `ContractR09R14R15RegressionTest`, `RollingBoundarySchedulerTest`, `DialogueLensV6RegressionTest`. |
| **R12** | **Implemented & verified in unit/Robolectric.** Common restoration + crossing-cue canonicalization (`reconcileRestoredTimeline`), monotonic revision freshness, atomic API 24+ compatible export/save. | `data/project/ProjectStore.kt`, `ui/state/DialogueLensViewModel.kt` | **Pass (JVM/Robolectric)**; API 24–26 & device install/upgrade **Blocked** | `ProjectStoreLegacyApiExportRobolectricTest`, `RollingBoundarySchedulerTest`, `DialogueLensV6RegressionTest`. |
| **R13** | **Implemented & verified in unit/Robolectric.** Heal Beacon lifecycle states, exhaustive UI switch handling, transient ack vs inspector diagnostics. | `domain/analysis/HealBeaconEngine.kt`, `ui/player/HealBeaconUi.kt`, `ui/state/HumanReadableStatus.kt`, `ui/state/DialogueLensViewModel.kt` | **Pass (JVM/Robolectric)** | `DialogueLensV2OverhaulTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensViewModelAndRendererIntegrationRobolectricTest`. |
| **R14** | **Implemented (accessibility foundations) & unit/Robolectric checked; Deaf/HoH evaluation Not run.** | `data/model/ProjectModels.kt`, `ui/player/SpeechBalloonView.kt`, `ui/player/VideoPlayerStage.kt`, `ui/player/CinemaIntroOverlay.kt`, `ui/settings/BalloonStyleSheet.kt` | **Partial**: automated checks **Pass**; **Deaf/HoH participant study Not run** | `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest`, `ProjectStoreLegacyApiExportRobolectricTest`, `DialogueLensV6RegressionTest`. |
| **R15** | **Implemented (diagnostics, WAV/PCM classifier, bounded capture queue, ownership/lifecycle) & unit/Robolectric checked; Live WebView capture Blocked.** | `domain/analysis/AudioAnalyzer.kt`, `domain/analysis/ScoutCaptureDiagnostics.kt`, `ui/player/WebStreamPlayer.kt`, `ui/state/DialogueLensViewModel.kt` | **Partial**: WAV/queue/lifecycle **Pass**; **Live WebView `captureStream()`/`MediaRecorder` Blocked** | `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensV6RegressionTest`. |

---

## 4. Acceptance Matrix (`T01–T40`)

| ID | Scope | Status | Evidence / Blocker |
| --- | --- | --- | --- |
| **T01–T13** | Readiness, Pause vs Watch Now, Scout 2 review, 180s boundary, fractional clipping, seek/speed, scrubber freeze, cache, mid-gap fallback, deadlines, `ZERO_CLOUD`, cross-project isolation | **Pass (JVM/Robolectric)** | `DialogueLensV2OverhaulTest`, `ContractComplianceRobolectricTest`, `ContractR09R14R15RobolectricTest`, `TwoScoutReleasePipelineTest`, `DialogueLensAuditRepairsTest`, `RollingBoundarySchedulerTest`, `DialogueLensViewModelAndRendererIntegrationRobolectricTest`, `DialogueLensV6RegressionTest` |
| **T14** | YouTube Scout 1 CC discovery & swaps | **Pass (JVM/Robolectric)**; Live YT **Blocked** | `ContractComplianceRobolectricTest`, `DialogueLensV6RegressionTest`; live iframe DOM **Blocked** |
| **T15** | Local A/B speaker isolation & slot mapping | **Pass (JVM/Robolectric)** | `DialogueLensEngineTest`, `DialogueLensStabilizationTest` |
| **T16–T17** | Exclusive 4 modes, legacy migration, stable body, 360° short tail (`<=18dp`), shout midpoint fix, cut/seek/rotate invalidation | **Pass (JVM/Robolectric + Compose)**; Live device **Blocked** | `DialogueLensV6RegressionTest`, `ContractR09R14R15RegressionTest`, `ContractR09R14R15RobolectricTest`, `DialogueLensEngineTest`, `SpeechBalloonRendererStabilityRobolectricTest` |
| **T18–T19** | Neutral placement fallback, expanding/split cues, >45-cue batching | **Pass (JVM/Robolectric)** | `DialogueLensEngineTest`, `OperationGoldenVideoRegressionTest`, `TranscriptReliabilityTest` |
| **T20–T21** | Audio decode validation, web-media pinned selection vs ads/redirects | **Pass (JVM/Robolectric)**; Live `MediaCodec`/WebView **Blocked** | `DialogueLensV6RegressionTest`, `ContractR09R14R15RegressionTest`, `DialogueLensV2OverhaulTest` |
| **T22** | Save/progress race ordering, backward seeks, exports | **Pass (JVM/Robolectric)** | `ProjectStoreLegacyApiExportRobolectricTest`, `RollingBoundarySchedulerTest` |
| **T23** | Clean build, API 24/25/26 install & upgrade | **Blocked (Device Install)** | APK build & `ProjectStoreLegacyApiExportRobolectricTest` **Pass**; device install/upgrade **Blocked** |
| **T24–T26** | Heal beacon expiry, E03–E10 repro, crossing-cue canonicalization | **Pass (JVM/Robolectric)** | `DialogueLensV6RegressionTest`, `TwoScoutReleasePipelineTest`, `SpeechBalloonRendererStabilityRobolectricTest`, `DialogueLensV2OverhaulTest` |
| **T27–T28** | Async seek non-self-ack, YT readiness/pending transport/153 vs 101/150 | **Pass (JVM/Robolectric)**; Live YT **Blocked** | `DialogueLensV6RegressionTest`, `ContractR09R14R15RobolectricTest`, `RollingBoundarySchedulerTest` |
| **T29** | Live YT holds at `00:45`/`01:30`/`02:15`/`03:00` + P4 pause | **Blocked (Live Replay)** | Readiness/drain logic passes in `DialogueLensV6RegressionTest` + `RollingBoundarySchedulerTest`; live clip replay **Blocked** |
| **T30** | Flooded audio callbacks during gap/Heal + mid-repair changes | **Pass (JVM/Robolectric)** | `ContractR09R14R15RobolectricTest` |
| **T31** | Live adversarial web ad/popup/interstitial/Watch-recovery | **Blocked (Live WebView)** | Guard/ownership logic passes in `DialogueLensV6RegressionTest` + `ContractR09R14R15RegressionTest`; live Chromium WebView **Blocked** |
| **T32–T37** | Heal lifecycle, muted viewer parity, character reveal/contrast/a11y/provenance | **Pass (JVM/Robolectric)**; Device TalkBack/Deaf-HoH study **Blocked / Not run** | `ContractR09R14R15RobolectricTest`, `ContractR09R14R15RegressionTest`, `ProjectStoreLegacyApiExportRobolectricTest` |
| **T38–T40** | Live Android WebView `captureStream()` / `MediaRecorder` / inaudible scout output / replacement | **Blocked (No WebView/Audio HW)** | Queue/WAV/diagnostics checks pass in `ContractR09R14R15RobolectricTest` + `ContractR09R14R15RegressionTest`; real WebView + HW audio **Blocked** |
