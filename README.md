# ScanFace

A Kotlin Multiplatform app for private facial-age exploration. Android and iOS share
the Compose interface, scan lifecycle, local history and appearance preferences.
Camera capture stays native: CameraX on Android and AVFoundation on iOS.

The app is functional before model integration: take or import a photo, review it,
save it, revisit history, export a metadata summary and delete local captures.
Real captures display **Awaiting model**. Optional, clearly labelled sample results
demonstrate the estimate and history views. No model weights or inference results
are bundled.

## Run

Requirements: JDK 17, Android SDK 37, and (for iOS) macOS with Xcode 27.1 and
XcodeGen. The Gradle wrapper and dependency versions are checked in. Android
supports API 26 and newer; iOS supports 17.0 and newer.

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

Configure your Android SDK with `ANDROID_HOME` or an untracked `local.properties`
containing `sdk.dir=/absolute/path/to/Android/sdk`. Open the root project in Android
Studio to run the `androidApp` configuration.

```sh
cd iosApp
xcodegen generate
open ScanFace.xcodeproj
```

Choose the ScanFace scheme and an iPhone or iPad simulator. The Xcode build phase
builds the shared Kotlin framework automatically. Physical-device builds require
your own signing team in Xcode.

## Verify

See [verification results](docs/verification.md) for the tested devices, completed
checks and remaining physical-camera validation.

```sh
./gradlew :shared:desktopTest :androidApp:assembleDebug
# With an Android emulator or device connected:
./gradlew :androidApp:connectedDebugAndroidTest
# iOS simulator (replace the destination with an installed simulator):
xcodebuild -project iosApp/ScanFace.xcodeproj -scheme ScanFace \
  -destination 'platform=iOS Simulator,name=iPhone Duo' \
  -derivedDataPath iosApp/DerivedData test CODE_SIGNING_ALLOWED=NO
```

`scripts/check.sh` runs the shared checks and Android build; setting
`SCANFACE_IOS_DESTINATION` also runs the Xcode suite.
The Android and iOS UI suites reset ScanFace's local test data; run them on a
simulator or dedicated test device.

## Structure

| Location | Responsibility |
| --- | --- |
| `shared/src/commonMain` | Compose screens, semantic themes, navigation, durable app state, model contracts |
| `shared/src/androidMain` | CameraX, Android photo picker, EXIF normalization, private storage and native sharing |
| `shared/src/iosMain` | UIKit bridge, native image rendering, preferences and sharing |
| `androidApp` | Android application, Koin dependency graph, configured Ktor/OkHttp client |
| `iosApp` | SwiftUI host, AVFoundation camera, Photos picker, Xcode project and native tests |
| `shared/src/commonTest` | Persistence, cancellation, image ownership, malformed results and model-manifest tests |
| `design/concept-v1` | Approved visual direction and palette |

## Interface and cameras

- System, light and dark appearance are persisted. Native camera controls follow
  the selected appearance.
- Below 600 dp, the app uses bottom navigation. Larger windows use a rail.
  At 840 dp, overview and history use multiple columns. Navigation and selection
  survive resizing; all content respects safe areas and supports scrolling.
- iOS supports all orientations and iPad multitasking. Layout decisions use
  available window bounds, including iPhone Duo's changing viewport and insets.
- Android chooses a suitable main wide lens using camera capabilities, exposes
  available zoom choices, offers rear off/auto/on flash when supported, and uses
  screen flash for the front camera.
- iOS discovers available lenses, defaults to a face-friendly wide camera,
  exposes supported choices, and uses the iOS 17 rotation coordinator. Flash
  controls reflect hardware support; front capture has screen illumination.
- Both adapters produce upright JPEGs in private app storage. Imported originals
  remain untouched. Permission denial, missing cameras, interrupted sessions,
  storage failures and canceled captures have recovery paths.

Photos never enter the networking layer. The share action exports CSV metadata
without local paths or photo bytes. Android backup is disabled; iOS captures are
excluded from backup. Saved photos and metadata persist until deleted in the app
or the app is removed. Photo deletions are journaled before cleanup; failed
deletions can be retried in Settings and are retried on the next launch.

## Model integration is the next boundary

Implement `FaceAnalyzer.analyze(CapturedPhoto)` and replace
`PendingModelAnalyzer` in the platform dependency graph. The input is an upright
private image plus capture metadata. Return an `AnalysisResult` with a valid
finite age and an explicit model identifier. The store rejects malformed
completed results and never converts a missing model into an estimate.

1. Confirm the research model's redistribution and intended-use license before
   packaging weights. The linked paper is a research starting point, not an
   endorsement of this app.
2. Reproduce the model's face detection, landmark alignment, crop, color order and
   normalization contract. Add quality feedback from measured outputs.
3. Add LiteRT on Android and Core ML on iOS behind `FaceAnalyzer`; run inference
   off the UI thread and make cancellation release native resources.
4. Validate both implementations against the same held-out fixtures, including
   preprocessing tensors and numerical tolerance.
5. If downloading weights, configure `ModelManifestService` with an explicit
   metadata endpoint. It validates a bounded HTTPS manifest and artifact
   metadata; download, SHA-256 verification and model installation remain to be
   implemented. No endpoint is contacted by the current app.

The UI deliberately avoids treating facial age as a diagnosis or lifespan
prediction. Sample records remain marked as fictional in both UI and exports.

Research context: [The Lancet Digital Health paper](https://www.thelancet.com/journals/landig/article/PIIS2589-7500%2826%2900079-8/fulltext).
