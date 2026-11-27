# DialogueLens — Build & Verification Handoff (`v6 Regression Repair + v36 Secondary Speaker & Scout Timestamp Audit / 36.0`)

## 1. Build & Source Identity
- **App Name:** `DialogueLens` (`res/values/strings.xml`, `settings.gradle.kts`, `metadata.json`)
- **Application ID:** `com.aistudio.dialoguelens.vkpzmx`
- **Namespace:** `com.example`
- **Version:** `36.0` (`versionCode = 36`, Studio badge bound at `DialogueLensScreen.kt:2030` via `"v${BuildConfig.VERSION_NAME}"`)
- **SDK Levels:** `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`
- **Build Type / Signing:** `debug` signed with existing `debugConfig` (`debug.keystore` preserved untouched; `release` signing config preserved untouched)
- **Upstream Baseline Commit:** `7df5c0a6ef264e183379730913eef2c7ceabcc32` (`.git` metadata is absent in the AI Studio build container)
- **Reproducible Source Manifest:** `verification/SOURCE_MANIFEST.sha256` (`108` sorted project/source/build/config/test files)
  - **Source Manifest SHA-256:** `61cc11fbd0df51a1c451c7d1f5695604fb823099f5773c6fc87a5299382f4bd4`
  - **Short Digest:** `61cc11fb`
- **Play Policy Check:** `AndroidManifest.xml` declares only install-time permissions (`android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`).

## 2. Deliverable Artifacts
- **Installable Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`167M`)
- **APK SHA-256:** `a234d1df988bc3905fba0b62b0460f6474cad15bea563c5407022b70a28b4665`
- **Source Manifest:** `verification/SOURCE_MANIFEST.sha256` (`61cc11fbd0df51a1c451c7d1f5695604fb823099f5773c6fc87a5299382f4bd4`)
- **Traceability & Verification Report:** `verification/RESULTS.md`
- **Handoff Archive:** `DialogueLens_36.0_61cc11fb_handoff.zip` (debug APK, `HANDOFF.md`, `verification/RESULTS.md`, `verification/SOURCE_MANIFEST.sha256`, and clean Android project sources excluding `build/`, `.gradle/`, `.build-outputs/`, and archives)

## 3. Changes Summary (`35.0` v6 Repairs + `36.0` Enhancements)
### `36.0` Secondary Speaker Naming & Scout 1 → Scout 2 Timestamp Verification
1. **Immediate Contextual/Inferred Naming for Speakers After the First (`TranslationAiService.kt`, `DialogueLensViewModel.kt`):**
   - Added `speakerUpdates` JSON schema & parser (`A..H` + `UNCERTAIN`) to `AiAudioTranscriptionResult` / `audioTranscriptionSchema` / `parseAudioTranscriptionPayload`, expanded `discoverCastAndRoles` to slots `A..H`, and updated YouTube & Audio ASR prompts to emit inferred or contextual names/roles for secondary speakers immediately.
   - Reconciled `detailed.speakerUpdates` and `turnHints` (`suggestedSpeakerSlot`) across `processCapturedAudioQueueItem`, `startRollingMediaLookahead`, and `processSingleHealBeacon`.
   - Updated `mergeCastSlotAssignments` to apply non-generic inferred/contextual names immediately (`revealProvenance = "SCOUT_INFERRED"`, preserving `nameRevealedAtMs = existing.nameRevealedAtMs`), keep blank/generic labels (`Speaker B`, `Host C`, etc.) unconfirmed/dotted-gray, protect `isUserNamed` / `isLocked` slots, and upgrade contextual placeholders in place (`"SCOUT_DISCOVERY"`) when a visual chyron/intro confirms a speaker's real name.
   - Tightened `isGenericSpeakerName` regex in `reconcileWindowOutcomeSpeakersAndCues` so multi-word contextual role labels (e.g., `"Guest Comedian"`) are not misclassified as generic slot letters and do not spawn duplicate speaker slots.
2. **Scout 1 → Scout 2 Timestamp Report & Unconditional Reference Snapping (`TranslationAiService.kt`, `ScoutReleasePipeline.kt`, `DialogueLensViewModel.kt`):**
   - Added `rawStartSec`, `rawEndSec`, and `appliedClockOffsetMs` to `AiTranscribedCue` across YouTube and Audio ASR parsers.
   - Added `ScoutClockProvenance`, `Scout1CueTimestamp`, `Scout1TimestampReport`, and `TimestampAdjustmentRecord` in `ScoutReleasePipeline.kt`, attaching `timestampReport` to every `DraftInterval` built in `DialogueLensViewModel.buildScout1TimestampReport(...)` across interval release gates, Scout 2 deadline loops, YouTube lookahead, rolling media lookahead, changeover seam handling, and audio ASR queue processing.
   - Updated `Scout2IndependentReviewer.reviewDraft` with Pass 2 drifted-cue semantic+temporal pairing (`DRIFTED_SEMANTIC_PAIR_WINDOW_MS = 8_000L`) and Step 4b Timestamp Verification: unconditionally snaps non-`lockedByUser` cues to reference evidence (`"SNAPPED_TO_REFERENCE"`), clamps unreferenced out-of-bounds cues (`"CLAMPED_TO_INTERVAL"`), and resolves same-speaker cue collisions (`"RESOLVED_OVERLAP"`).
3. **Sentinel Inspector Diagnostics Only (`SentinelWorkforceCoordinator.kt`, `TimelineSubtitleEditor.kt`):**
   - Added `latestScout1ReportSummary`, `totalTimestampsVerified`, `totalTimestampsAdjusted`, and `recentTimestampAdjustments` to `SentinelInspectorSnapshot` (`recordScout1TimestampReportSummary`, `recordScoutTimestampAudit`).
   - Added the `"Scout 1 → 2 Timestamp Audit"` block (`Modifier.testTag("sentinel_timestamp_audit")`) strictly inside `Studio -> Diagnostics -> sentinel_inspector_card`, keeping Watch overlays and timeline rows free of diagnostic clutter.

### Baseline `v6` Regression Repairs Preserved in `36.0`
1. **Validated Source Import & Routing Parity (`v6 §3`):** Consolidated URL/share/sheet/project-reopen routing onto `MediaSourceAdapterRegistry.default.resolveForActivation` (`MediaSourceAdapter.kt`, `MediaImportAndInfoSheets.kt`, `StartLauncherScreen.kt`, `DialogueLensViewModel.kt`). Non-YouTube URLs no longer trigger empty-ID YouTube projects; empty/malformed YouTube IDs fail activation before starting caption pipelines.
2. **Deferred Scout 2 Execution, Paused Drain & Quota Sync (`v6 §4`):** Preserved `allowedKinds` filtering on `SentinelWorkforceCoordinator.claimNextWork`, lifecycle-aware `startScout2ReviewAndDeadlineLoop()`, and `pendingQuotaModeOverride` + `releaseStore.updateSeekOrConfigGeneration` sync.
3. **Pending Transport Acknowledgements, Watch Now Reset & Error State (`v6 §5`):** Preserved separated intent vs `pendingTransportCommand` / `onPlayCommandResult` bridge vs acknowledged playback state, `isMediaHardwareError` -> `"Video failed to load"`, `OFF` Watch Now bypass reset, and `dismissScoutCatchUpBuffering()` intro cancel.
4. **Single Canonical Timeline Reconciliation & Entity Integrity (`v6 §6`):** Preserved crossing-boundary lineage merging (`mergeIntervalEventsAtomicallyLocked`, `mergeReleasedIntervalPreservingLocks`, `reconcileRestoredTimeline`) and `isSuspiciousAllCapsPhoneticDegradation`.
5. **Committed Web Media Ownership, Interstitial Recovery & Coordinated 180s Scout (`v6 §7`):** Preserved committed `activeDocGeneration` / `__dlCommittedElementId` ownership, interstitial protection, `CookieManager.flush()`, `awaitingSeekSettle`, and `LookaheadWindowPlanner.computeCoordinatorScoutCommand` inside the 180-second horizon.
6. **Consistent 4-Mode Layout & 12–18 dp Short Tails (`v6 §8`):** Preserved unified `TailGeometry.kt` (`12dp` nominal / `18dp` max), shout midpoint-angle fix, synchronized `BalloonLayoutEngine` + `SpeechBalloonView` rendering, stable body anchors across `BALLOONS` <-> `BUBBLES_WITH_TAILS`, and `lastTailSuppressionReasons`.
7. **Translator Safety in Headless/Unit Environments (`OnDeviceTranslatorService.kt`):** Preserved `Translation.getClient(options)` `try/catch(Throwable)/finally` safety.

## 4. Verification Summary (Passed / Failed / Blocked)
- **Passed (In-Container):**
  - `gradle :app:testDebugUnitTest --console=plain`: **200 / 200 passed across 19 suites** (`0 failures`, `0 errors`, `0 skipped`), including all **7** tests in `DialogueLensV6RegressionTest` and all **3** tests in `DialogueLensV36RegressionTest`.
  - `gradle assembleDebug --console=plain`: **Pass** (`app/build/outputs/apk/debug/app-debug.apk`, `167M`, SHA-256 `a234d1df988bc3905fba0b62b0460f6474cad15bea563c5407022b70a28b4665`).
  - `compile_applet`: **Pass**.
  - `sha256sum -c verification/SOURCE_MANIFEST.sha256`: **Pass** (`108` files, SHA-256 `61cc11fbd0df51a1c451c7d1f5695604fb823099f5773c6fc87a5299382f4bd4`).
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
# Expected manifest SHA-256: 61cc11fbd0df51a1c451c7d1f5695604fb823099f5773c6fc87a5299382f4bd4
sha256sum app/build/outputs/apk/debug/app-debug.apk
# Expected APK SHA-256:      a234d1df988bc3905fba0b62b0460f6474cad15bea563c5407022b70a28b4665

# 2. Re-run local JVM + Robolectric suites if desired
gradle :app:testDebugUnitTest --console=plain

# 3. Install candidate on connected device / emulator (preserving user data)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Then verify on device:
1. Launch Studio/Info and confirm the version badge displays **`v36.0`**.
2. Open **`https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_`** (both a clean project and an upgraded/restored project), run an uninterrupted `1×` watch-through across `00:45`, `01:30`, `02:15`, `03:00`, `00:32`, `01:19–01:21`, and `03:52–03:56`, verifying secondary speakers receive inferred/contextual labels immediately, upgrade cleanly on visual confirmation, and inspecting `Studio -> Diagnostics -> Sentinel Inspector -> Scout 1 → 2 Timestamp Audit`.
3. Test web-media import parity (launcher vs `+` sheet vs share) with non-YouTube URLs, clear a `"Continue to Video"` interstitial, confirm **Watch** (not just Inspector) advances frames and media time, and test viewer mute/unmute, inaudible scout playback, and `MediaRecorder`/`MediaCodec` capture (`R15`, `T31`, `T38–T40`).
4. Install/upgrade on API 24–26 (`T23`) and conduct consenting Deaf/HoH participant testing (`R14`).
