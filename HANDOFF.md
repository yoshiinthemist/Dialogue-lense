# DialogueLens — Build & Verification Handoff (`AIStudio-Android-5`)

## 1. Build & Source Identity
- **App Name:** `DialogueLens` (`res/values/strings.xml`, `settings.gradle.kts`, `metadata.json`)
- **Application ID:** `com.aistudio.dialoguelens.vkpzmx`
- **Namespace:** `com.example`
- **Version:** `34.0` (`versionCode = 34`)
- **SDK Levels:** `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`
- **Build Type / Signing:** `debug` signed with existing `debugConfig` (`debug.keystore` preserved; release signing config preserved untouched)
- **Source Identity:** `workspace-snapshot` (pre-loaded Android `34.0` workspace plus uncommitted repairs from this execution; `.git` history is not present in the build container)
- **Play Policy Check:** `AndroidManifest.xml` declares only normal install-time permissions (`android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`), consistent with `play-creator-track-policy`.

## 2. Deliverable Artifacts
- **Installable Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`167M`)
- **APK SHA-256:** `3b44e866a2c7ad15ffe90795d4cdc178493116bb240d5f04e7e0575ee9eabaf2`
- **Traceability Matrix:** `verification/RESULTS.md`
- **Handoff Archive:** `DialogueLens_34.0_workspace-snapshot_handoff.zip` (contains the debug APK, complete Android source snapshot excluding `build/` and `.gradle/`, `HANDOFF.md`, and `verification/RESULTS.md`)

## 3. Summary of Key Repairs Applied in This Pass
1. **Evidence, Publication & ViewModel Integrity (`R01`, `R03`, `R13`, `A01`, `A02`, `A08`):** Aligned `DialogueLensViewModel.kt` source revision types (`Int`), merge span lineage (`sourceSpanStartMs`/`sourceSpanEndMs`), and named `HealBeaconEngine.markBeaconSuperseded` calls; wired exhaustive `SUPERSEDED`/`CANCELLED` handling in `HealBeaconUi.kt`; recomputed `youtubeNativeCcState` on observed `onYouTubePlaybackState` updates.
2. **Async Seek Acknowledgement & Paused Boundary Gating (`R05`, `R07`, `A04`):** Stopped `DialogueLensViewModel.seekTo` / `updateVisualPresentationOnly` and the 16ms clock interpolation loop from self-acknowledging `currentTimeMs` before real player feedback; deferred advancing `SentinelWorkforceCoordinator.acknowledgedViewerPosMs` until observed seek landing (including seeks committed while paused).
3. **Bubble Pointer & Accessible Labeling (`R09`, `R14`, `A03`):** Evaluated `BalloonLayoutEngine` tail obstruction strictly against the actual drawn short pointer (`isShortDrawnTailObstructed`) while keeping distant on-screen mouths eligible; retained textual speaker labels in tailless (`BALLOONS`) and offscreen cues in `SpeechBalloonView.kt`.
4. **Audio Decode & PCM/WAV Inspection (`R15`, `A09`):** Fixed companion placement of `AudioAnalyzer.inspectPcmWavBytes` and `classifyDecodedSamples` for deterministic PCM-16/WAV energy, silence, and decode-failure inspection alongside `AudioAnalyzer.inspectCapturedAudioChunk`.

## 4. Executed Verification (In-Container)
- `compile_applet`: **Pass**
- `gradle assembleDebug --console=plain`: **Pass** (`3b44e866a2c7ad15ffe90795d4cdc178493116bb240d5f04e7e0575ee9eabaf2`)
- `gradle :app:testDebugUnitTest --console=plain`: **Pass — 190 / 190 tests passed across 17 suites** (`0 failures`, `0 errors`, `0 skipped`).
- **Installation / Upgrade Testing:** **Not run / Blocked** (no Android Emulator or ADB exists in the AI Studio build container).

## 5. Reproduction & Device Continuation Steps
Run locally with Android SDK + connected device or emulator (API 24–36):
```bash
# 1. Re-run local JVM + Robolectric verification
gradle :app:testDebugUnitTest --console=plain

# 2. Verify clean APK build & checksum
gradle assembleDebug --console=plain
sha256sum app/build/outputs/apk/debug/app-debug.apk

# 3. Install onto device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Then execute the remaining container-blocked checks documented in `verification/RESULTS.md`:
1. Full `1×` watch-through + interactive pause/seek/speed/4-mode/bubble-switch pass on **`https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_`** inspecting `00:45`, `01:30`, `01:59–02:01`, `02:15`, `02:29`, `03:00`, `04:02–04:05`, `04:33`, `05:21–05:23`, `05:55–06:08`, and `06:31–06:35` (`E01–E11`, `T27–T29`).
2. Authorized foreign-language fixture watch-through (`T03`, `T19`, `T37`).
3. Live Android WebView `MediaRecorder` / `MediaCodec` + inaudible scout output verification (`R15`, `T31`, `T38–T40`).
4. Install/upgrade & storage verification on physical/emulated API `24–26` (`T23`).
5. Consenting Deaf/Hard-of-Hearing participant viewing evaluation (`R14`).
