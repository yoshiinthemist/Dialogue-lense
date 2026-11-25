# Building DialogueLens for Android

DialogueLens 2.0.0 uses version code 29 and application ID `com.aistudio.dialoguelens.vkpzmx`. The Gradle wrapper pins Gradle 9.3.1 and verifies its distribution checksum. Use JDK 21 and Android SDK platform 36.1 with build tools 36.0.0.

## Build the Play update in the existing Google AI Studio project

This project is developed in Google AI Studio and synced to GitHub. After the verified pull request is merged, open the existing DialogueLens app in Studio, pull the changes from its connected GitHub repository and check that `app/build.gradle.kts` shows version `2.0.0` and code `29`. The original Studio project holds the app's signing identity and Firebase integration.

Use **Settings → Publish → Publish to Play Store** in Studio to send the update to internal testing. Install it over the current Play version, run the media checks below and confirm that saved projects survive. Production promotion happens in Play Console after the internal checks pass.

[Google's Android publishing guide](https://aistudio.google.com/docs/aistudio-android#publish)

## Build and test locally

From the repository root, install the SDK packages and create a local debug signer once. Run these commands with the Android command-line tools on your PATH.

```sh
sdkmanager "platforms;android-36.1" "build-tools;36.0.0" "platform-tools"
test -f debug.keystore || keytool -genkeypair -keystore debug.keystore \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 365 -dname "CN=Android Debug,O=Android,C=US"

bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Connect a device or start an emulator, then run `bash gradlew :app:connectedDebugAndroidTest`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Use a spare device or emulator for a debug APK when your phone already has the Play Store version. Android requires matching signing keys to update an installed package.

## Connect cloud dialogue analysis

Supply the Firebase Android configuration for this exact application ID in `app/google-services.json`. Enable the project's Firebase AI Logic service and configure App Check for its deployed provider. Use a registered App Check debug token only with a development installation. The production app selects reCAPTCHA Enterprise when a site key is configured, otherwise Play Integrity. The debug package built by public pull-request CI exercises local functionality without production credentials.

## Optional GitHub signing workflow

The Android verification workflow runs the unit suite, lint, debug packaging and API 35 emulator tests. After merging to `main`, the owner can run the workflow manually and select **Build a signed Play bundle**. The `production` GitHub environment supplies the existing upload credential and Firebase configuration.

| GitHub secret | Required value |
| --- | --- |
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | Base64 of the existing upload keystore with alias `upload` |
| `ANDROID_STORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_PASSWORD` | Upload-key password |
| `ANDROID_GOOGLE_SERVICES_JSON` | Contents of the matching Firebase configuration |

The signed job waits for both verification jobs, runs release lint, builds the AAB, and checks its signer against the supplied upload key. Download `DialogueLens-signed-bundle-<commit>` from the successful run. Publication is a separate Play Console action.

## Verify the upgrade with real media

Use Play internal testing to install the candidate over the previous store version. Confirm that saved projects, viewing position, speaker corrections and preferences survive the update. With the release App Check configuration active, check a local file, a permitted direct stream and a chaptered YouTube video. Review chapter transitions at normal and double speed, seek forward across an unprepared gap, and pause during preparation. Confirm a manual Heal Beacon leaves the viewer at the same time and that undo restores the affected dialogue.
