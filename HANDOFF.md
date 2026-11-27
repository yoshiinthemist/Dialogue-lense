# DialogueLens — Build & Verification Handoff (`v6 Regression Repair / 35.0`)

## 1. Build & Source Identity
- **App Name:** `DialogueLens` (`res/values/strings.xml`, `settings.gradle.kts`, `metadata.json`)
- **Application ID:** `com.aistudio.dialoguelens.vkpzmx`
- **Namespace:** `com.example`
- **Version:** `35.0` (`versionCode = 35`, Studio badge bound at `DialogueLensScreen.kt:2030` via `"v${BuildConfig.VERSION_NAME}"`)
- **SDK Levels:** `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`
- **Build Type / Signing:** `debug` signed with existing `debugConfig` (`debug.keystore` preserved untouched; `release` signing config preserved untouched)
- **Upstream Baseline Commit:** `7df5c0a6ef264e183379730913eef2c7ceabcc32` (`.git` metadata is absent in the AI Studio build container)
- **Reproducible Source Manifest:** `verification/SOURCE_MANIFEST.sha256` (`107` sorted project/source/build/config/test files)
  - **Source Manifest SHA-256:** `8efbdf61ea58da3c471aac8c4857101af796ee1b145e224cc6c788703d78d5e7`
  - **Short Digest:** `8efbdf61`
- **Play Policy Check:** `AndroidManifest.xml` declares only install-time permissions (`android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`).

## 2. Deliverable Artifacts
- **Installable Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`167M`)
- **APK SHA-256:** `62a66d760a3a1186ddb9e267dec6d0c6a219844c85b318069ee8bcf8a3133ab0`
- **Source Manifest:** `verification/SOURCE_MANIFEST.sha256` (`8efbdf61ea58da3c471aac8c4857101af796ee1b145e224cc6c788703d78d5e7`)
- **Traceability & Verification Report:** `verification/RESULTS.md`
- **Handoff Archive:** `DialogueLens_35.0_8efbdf61_handoff.zip` (debug APK, `HANDOFF.md`, `verification/RESULTS.md`, `verification/SOURCE_MANIFEST.sha256`, and clean Android project sources excluding `build/`, `.gradle/`, `.build-outputs/`, and archives)

## 3. Root-Cause-to-Fix Summary (v6 Repairs)
1. **Validated Source Import & Routing Parity (`v6 §3`):** Consolidated URL/share/sheet/project-reopen routing onto `MediaSourceAdapterRegistry.default.resolveForActivation` (`MediaSourceAdapter.kt`, `MediaImportAndInfoSheets.kt`, `StartLauncherScreen.kt`, `DialogueLensViewModel.kt`). Non-YouTube URLs (including URLs containing arbitrary 11-character path segments) no longer trigger empty-ID YouTube projects; empty/malformed YouTube IDs fail activation before starting caption pipelines.
2. **Deferred Scout 2 Execution, Paused Drain & Quota Sync (`v6 §4`):** Added `allowedKinds` filtering on `SentinelWorkforceCoordinator.claimNextWork` (so patrol tasks cannot silently consume `SCOUT2_REQUIRED_REVIEW`), introduced an independent lifecycle-aware `startScout2ReviewAndDeadlineLoop()` in `DialogueLensViewModel.kt` that drains deferred reviews and expires deadlines while playback is held/paused without re-transcribing, and added `pendingQuotaModeOverride` + `releaseStore.updateSeekOrConfigGeneration` sync in `DialogueLensViewModel.kt` so async DataStore startup reads never revert explicit `setQuotaSaverMode(...)` transitions or orphan queued drafts.
3. **Pending Transport Acknowledgements, Watch Now Reset & Error State (`v6 §5`):** Separated user intent, transport command dispatch (`pendingTransportCommand` / `onPlayCommandResult` JS-to-Compose bridge in `YouTubeEmbeddedPlayer.kt`, `WebStreamPlayer.kt`, `VideoPlayerStage.kt`, `DialogueLensScreen.kt`), and acknowledged playback state. Late play acknowledgements cannot override a newer `PAUSE`, seek, or source change. `PlaybackReadinessController` tracks `isMediaHardwareError` -> `PLAYBACK_OR_ANALYSIS_ERROR` (`CinemaIntroOverlay` shows `"Video failed to load"`), clears stale `Watch Now` bypass on `CaptionDisplayMode.OFF` / seek / source change while preserving it during active buffering, and `dismissScoutCatchUpBuffering()` cancels `introJob`.
4. **Single Canonical Timeline Reconciliation & Entity Integrity (`v6 §6`):** Added crossing-boundary utterance/source-span lineage merging (`mergeIntervalEventsAtomicallyLocked` in `ScoutReleasePipeline.kt`, `mergeReleasedIntervalPreservingLocks` + `reconcileRestoredTimeline` in `DialogueLensViewModel.kt`) so repaired cues replace overlapping/split restored-cache cues cleanly across overlay, transcript, and persistence while preserving user locks, distinct simultaneous speakers, later repeats, and SFX. Added phonetic ALL-CAPS ASR degradation protection (`isSuspiciousAllCapsPhoneticDegradation`) to reject lower-evidence noisy rewrites of grounded mixed-case names without hardcoding names.
5. **Committed Web Media Ownership, Interstitial Recovery & Coordinated 180s Scout (`v6 §7`):** Added committed `activeDocGeneration` / `__dlCommittedElementId` element ownership, consent/interstitial protection, `CookieManager.flush()`, and `awaitingSeekSettle` verification in `WebStreamPlayer.kt` (`buildWebStreamHookScript`) and `DialogueLensViewModel.kt`. Replaced legacy fixed-7s rewind logic with `LookaheadWindowPlanner.computeCoordinatorScoutCommand` honoring coordinator work windows inside the frozen 180-second horizon.
6. **Consistent 4-Mode Layout & 12–18 dp Short Tails (`v6 §8`):** Unified normal and shout balloon geometry onto `TailGeometry.kt` (`NOMINAL_TAIL_REACH_DP = 12f`, `MAX_TAIL_REACH_DP = 18f`), fixed `computeShoutBalloonVertices` midpoint-angle omission by inserting exact throat/tip/throat vertices along the perimeter, synchronized `BalloonLayoutEngine` obstruction checks and `SpeechBalloonView` rendering, preserved stable bubble body anchors across `BALLOONS` <-> `BUBBLES_WITH_TAILS`, and logged explicit `lastTailSuppressionReasons`.
7. **Translator Safety in Headless/Unit Environments (`OnDeviceTranslatorService.kt`):** Wrapped `Translation.getClient(options)` inside `try/catch(Throwable)/finally` so headless/Robolectric environments without `MlKitContext` return `Result.failure` instead of throwing.

## 4. Verification Summary (Passed / Failed / Blocked)
- **Passed (In-Container):**
  - `gradle :app:testDebugUnitTest --console=plain`: **197 / 197 passed across 18 suites** (`0 failures`, `0 errors`, `0 skipped`), including all **7** production-path tests in `DialogueLensV6RegressionTest`.
  - `gradle assembleDebug --console=plain`: **Pass** (`app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `62a66d760a3a1186ddb9e267dec6d0c6a219844c85b318069ee8bcf8a3133ab0`).
  - `compile_applet`: **Pass**.
- **Failed:** `0` checks failed.
- **Blocked / Not Run (Requires Real Android Device or Emulator):**
  - **Device APK install & upgrade verification (`T23`):** Blocked — container has no Android Emulator or ADB.
  - **Live YouTube runtime pass (`https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_` — clean & restored cache, `T14`, `T27–T29`, `E01–E11`):** Blocked — requires live Android WebView + YouTube iframe runtime.
  - **Live Android Chromium WebView interstitial/ad/Watch-recovery & `captureStream`/`MediaRecorder`/`MediaCodec` verification (`R15`, `T20`, `T31`, `T38–T40`):** Blocked — requires real WebView and hardware audio/video pipeline.
  - **Deaf / Hard-of-Hearing participant viewing evaluation (`R14`):** Not run.
  - **Important Honesty Note:** Because no Android device/emulator runtime exists in this container, the live YouTube/Web viewing experience is **not verified** yet and must be validated on device using the steps below.

## 5. Shortest Remaining Device Validation Commands
```bash
# 1. Verify source manifest & APK checksums match this candidate
sha256sum -c verification/SOURCE_MANIFEST.sha256
sha256sum verification/SOURCE_MANIFEST.sha256
# Expected manifest SHA-256: 8efbdf61ea58da3c471aac8c4857101af796ee1b145e224cc6c788703d78d5e7
sha256sum app/build/outputs/apk/debug/app-debug.apk
# Expected APK SHA-256:      62a66d760a3a1186ddb9e267dec6d0c6a219844c85b318069ee8bcf8a3133ab0

# 2. Re-run local JVM + Robolectric suites if desired
gradle :app:testDebugUnitTest --console=plain

# 3. Install candidate on connected device / emulator (preserving user data)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Then verify on device:
1. Launch Studio/Info and confirm the version badge displays **`v35.0`**.
2. Open **`https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_`** (both a clean project and an upgraded/restored project), run an uninterrupted `1×` watch-through across `00:45`, `01:30`, `02:15`, `03:00`, `00:32`, `01:19–01:21`, and `03:52–03:56`, recording any holds/interventions (`E01–E11`, `T27–T29`).
3. Test web-media import parity (launcher vs `+` sheet vs share) with non-YouTube URLs, clear a `"Continue to Video"` interstitial, confirm **Watch** (not just Inspector) advances frames and media time, and test viewer mute/unmute, inaudible scout playback, and `MediaRecorder`/`MediaCodec` capture (`R15`, `T31`, `T38–T40`).
4. Install/upgrade on API 24–26 (`T23`) and conduct consenting Deaf/HoH participant testing (`R14`).
