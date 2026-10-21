# ScanFace for iOS

ScanFace uses Apple Vision with a guarded Core Image eye fallback and a bundled
Core ML FAHR-FaceAge model to analyze a fresh capture or imported photo entirely
on the device. The minimum deployment target is iOS 17.0.
Xcode 27.1, XcodeGen and Java 17 are required; the
build phase invokes the repository's Gradle wrapper to embed `ScanFaceKit`.

Prepare the model from the repository root before generating the Xcode project:

```sh
bash scripts/models/install_tools.sh
conda run -n dl python scripts/models/prepare_models.py --runtime coreml --validation-portraits
conda run -n dl python scripts/models/quantize_coreml.py --phase build --variants int8-float32
conda run -n dl python scripts/models/quantize_coreml.py --phase evaluate --variants int8-float32
conda run -n dl python scripts/models/install_quantized_models.py --runtime coreml
cd iosApp
xcodegen generate
open ScanFace.xcodeproj
```

Select the **ScanFace** scheme and an iPhone or iPad simulator. Choose your signing
team before installing on a physical device. The conversion/parity workflow
requires macOS and runs all Python commands in the existing `conda dl`
environment. Converter-only packages are isolated in
`artifacts/models/converter-python`, keeping their older xDSL dependency separate
from the shared environment.

The pinned author checkpoint is approximately 981 MiB. The selected 8-bit-weight,
float32-compute `ScanFace/Models/FAHRFaceAge.mlpackage` is approximately 82 MiB,
down from the 327 MiB float32 baseline, and is ignored by
Git. Xcode compiles it to `FAHRFaceAge.mlmodelc`. `project.yml` bundles the full
`models/licenses` folder and `ScanFace/Models/conversion.json` with the app.
Regenerate the project after model preparation so its resources are present.
Author synthetic portraits are downloaded only for local conversion validation
and never shipped with the app.

The checked-in project is generated from `project.yml`. Privacy strings,
supported orientations, model resources and launch configuration are defined
there; run `xcodegen generate` after changing that configuration.

The static launch artwork and native animated handoff share the Home scan mark,
window-centered placement and light/dark palette. The handoff runs while Compose
renders and exits when its first frames are ready, without waiting for a full
animation or model initialization. It respects Reduce Motion and does not replay
on background return or rotation. The system-owned launch screen follows system
appearance; the runtime handoff restores the app's saved appearance.

```sh
# From the repository root:
xcodebuild -project iosApp/ScanFace.xcodeproj -scheme ScanFace \
  -destination 'platform=iOS Simulator,name=iPhone Duo' \
  -derivedDataPath build/ios build
```

Keep normal ad-hoc simulator signing enabled when checking launch appearance.
An unsigned simulator install can run while SpringBoard rejects its launch
storyboard and shows a black screen. `CODE_SIGNING_ALLOWED=NO` is suitable for
device compilation checks that do not install or run the app.

The SwiftUI host embeds the shared Compose application. Shared navigation opens
the native AVFoundation capture screen through `CameraControllerFactory`.
The capture screen uses current view bounds and safe areas, so it responds to
iPhone Duo resizing, iPad multitasking, rotation, and large accessibility text.
The viewfinder fills the available height, with camera switch on the left,
circular shutter in the center and flash on the right of its bottom controls.
The photo-library action sits below the preview. Pinch zoom is continuous from
1× up to 4×, constrained to the active camera's capabilities; lens changes reset
zoom. Accessible adjustment actions provide an alternative to the pinch gesture.
UIKit feedback accompanies camera controls and shared app actions.

The native camera defaults to a physical wide lens for less facial distortion,
discovers available front/back cameras, and offers only the flash modes supported
by the active output. Front cameras without hardware flash offer a white screen
fill light, with display brightness restored after exposure or interruption.
Preview mirroring is separate from saved image pixels.
iOS 17's rotation coordinator aligns the preview and capture with the horizon.
Interruptions, denied permission, missing hardware, and loading failures have
recoverable states. The system photo picker provides an alternative without
requesting access to the entire photo library.

Images are normalized upright, resized to a maximum edge of 2048 pixels, and
stored as JPEGs under Application Support/ScanFace/Captures with complete file
protection and backup exclusion. Originals in Photos are never modified.
The shared model boundary receives the private path, image dimensions, lens
description, mirroring state, and capture time. A serial worker performs Vision,
Core Image and Core ML work; cancellation stops Vision where possible and
suppresses late results, including after the synchronous Core Image fallback.
The app does not upload photos or download models at runtime.

## Model contract and research scope

`CoreMLFaceAnalyzer` supplies upright sRGB pixels in an explicit BGRA buffer to
Vision rectangle and landmark request revision 3. It requires exactly one face
and visible eyes, and rejects small, clipped or strongly turned faces. If Vision's
eye landmarks fail geometry checks, an offline Core Image detector may supply
eyes only when it finds exactly one face with both eyes and its box overlaps the
Vision box by at least 50% intersection-over-union. The fallback retains Vision's
face bounds and pose checks; both paths require plausible eye spacing and roll.
It aligns the eye line and samples the original-size face rectangle using the
author-style rotation and Pillow-compatible resizing. Vision and the guarded
Core Image fallback substitute for the authors' RetinaFace detector; equivalent
clinical performance has not been established.

The Core ML input `pixels` is float32 `[1, 112, 112, 3]`, RGB NHWC in `0..255`.
The graph performs `/255` and ImageNet normalization itself. Output `age` is a
single float32 value with shape `[1]`, in years. The runtime validates both tensor
contracts and rejects nonfinite or out-of-range estimates. Its identifier is
`fahr-faceage-1fbb84e-coreml-int8w-native-v1`. Missing model resources produce an
actionable error, never a substitute estimate.

The conversion command checks 24 numerical cases when `--validation-portraits` is
supplied: two mathematical patterns and 22 hash-pinned synthetic portraits. The
0.02-year tolerance checks the original float32 conversion. Compression is
screened separately against a 0.10-year mean and 0.25-year maximum drift on the
22 synthetic faces, for both CPU_ONLY and ALL compute requests.
These are numerical checks, not accuracy on people. See the
[quantization comparison](../docs/quantization.md). Models are for academic research and do
not diagnose conditions, measure individual health, or predict lifespan.
FAHR-FaceSurvival weights are not released and no survival score is produced.

The author code is Apache 2.0; the original and converted model weights retain
**CC BY-NC-SA 4.0**. They require attribution, noncommercial use and share-alike
terms for distributed adaptations. Commercial use requires additional permission.
See [provenance, preprocessing and licenses](../docs/models.md) and
[the bundled attribution notice](../models/licenses/NOTICE.md).

Use the simulator to check navigation, appearance, adaptation, and photo import.
Camera preview, hardware flash, camera switching, focus, and physical rotation
require a real iPhone or iPad for final verification.
