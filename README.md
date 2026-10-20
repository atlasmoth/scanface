# ScanFace

A Kotlin Multiplatform app for private facial-age exploration. Android and iOS share
the Compose interface, scan lifecycle, local history and appearance preferences.
Camera capture stays native: CameraX on Android and AVFoundation on iOS.

Capture or import a photo and get a real **FAHR-FaceAge** research estimate on the
device: LiteRT on Android, Core ML on iOS. Review and save the result, revisit
history, export a metadata summary, or delete the local capture. Missing models
and unusable photos produce an explanation, never an invented estimate. Optional
sample results remain clearly labelled as fictional.

The model weights are **CC BY-NC-SA 4.0: noncommercial use only**. The author's
code is Apache 2.0. Commercial use of the weights requires additional permission;
the app's code license cannot replace the weight license. See
[model provenance, licenses and limitations](docs/models.md).

## Run

Requirements: JDK 17, Android SDK 37, and (for iOS and Core ML conversion) macOS
with Xcode 27.1 and XcodeGen. Model preparation uses the existing Conda environment
named `dl`. The Gradle wrapper and dependency versions are checked in. Android
supports API 26 and newer; iOS supports 17.0 and newer.

Prepare the model assets before building either app. From the repository root:

```sh
bash scripts/models/install_tools.sh
conda run -n dl python scripts/models/prepare_models.py --runtime both --install --test-portraits
```

This downloads and verifies the pinned approximately 981 MiB author checkpoint,
converts it to float32 mobile models, checks numerical agreement, and installs
the assets plus conversion metadata. Each inference model is approximately
327 MiB and is ignored by Git; a fresh clone must run this preparation step.
Allow several gigabytes for the checkpoint, converters, outputs and build caches.
The full `both` command runs on macOS; Android-only conversion can use
`--runtime litert`.

All model Python commands run in `conda dl`. The bootstrap isolates converter-only
packages under `artifacts/models/converter-python`; do not downgrade shared
environment packages to satisfy the converter. `--test-portraits` provisions
22 hash-pinned author synthetic portraits for local numerical and device tests.
These images are not checked into Git or included in the shipping apps. See
[the model setup details](docs/models.md#prepare-the-mobile-artifacts).

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
| `androidApp` | Koin, Ktor/OkHttp, bundled ML Kit face detection and LiteRT inference |
| `iosApp` | SwiftUI host, AVFoundation camera, Photos picker, Vision landmarks, Core ML inference and native tests |
| `shared/src/commonTest` | Persistence, cancellation, image ownership, malformed results and model-manifest tests |
| `scripts/models` | Conda tool bootstrap, pinned checkpoint conversion and numerical fixtures |
| `models` | Source/hash manifests, attribution, licenses and non-personal pixel fixtures |
| `design/concept-v1` | Approved visual direction and palette |

## Interface and cameras

- System, light and dark appearance are persisted. Native camera controls follow
  the selected appearance.
- On iPhone Duo, navigation occupies the system-provided vertical bar region,
  below the status controls and clear of the camera. It follows the system's
  physical edge across folding and rotation without consuming extra content
  width. This uses iOS 27.1 layout-region APIs with availability guards.
- Elsewhere, windows below 600 dp use bottom navigation; larger windows use a
  rail. At 840 dp, overview and history use multiple columns. Navigation and
  selection survive resizing; all content respects safe areas and supports scrolling.
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
iOS stores photo references relative to its private capture folder, so moving the
app's data container during an update or reinstall does not break previews or
cleanup. Existing absolute references resolve against the current container on
load and become relative on the next successful save; no data reset is needed.

## On-device model workflow

`AndroidFaceAnalyzer` and `CoreMLFaceAnalyzer` implement the shared `FaceAnalyzer`
boundary. Detection, eye alignment and inference run off the UI thread. Each
requires one sufficiently large, front-facing face with both eyes visible. Failed
detection never falls back to estimating age from the full frame. Native work
handles cancellation without publishing a discarded capture's result.

The mobile graphs accept one aligned `112 × 112` RGB crop as float32 NHWC values
in `0..255`; channel transposition, `/255`, and ImageNet normalization are inside
the exported graph. Android uses the LiteRT `serving_default` signature with
`args_0` → `output_0`; iOS uses `pixels` → `age`. Both return one age in years.
Do not normalize the input twice. Model identifiers are
`fahr-faceage-1fbb84e-litert-native-v1` and
`fahr-faceage-1fbb84e-coreml-native-v1`.

Android uses bundled ML Kit 16.1.7 and iOS uses Vision landmark revision 3 instead
of the paper's RetinaFace detector. The crop geometry and sampling follow the
author pipeline, but these native detector substitutions have not been validated
as clinically equivalent. The conversion checks use 24 inputs: two mathematical
patterns and 22 author synthetic crops. Numerical agreement is a conversion
check, **not a clinical accuracy study**. The current app has no survival model;
FAHR-FaceSurvival weights are not publicly released.

Models are installed at build time; the app does not download weights or contact
a model endpoint. `ModelManifestService` remains an unused, tested network
boundary. The entered age is only a comparison value and never a model input.
Completed scans disclose the model identifier and preprocessing variation.

Facial age is a research estimate, not a diagnosis, health score or prediction of
an individual's lifespan. Settings links the author source, study and weight
license. See [the complete input contract and research limitations](docs/models.md).
