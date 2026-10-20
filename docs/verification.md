# Verification — 20 October 2026

The Android and iOS apps build and run with real FAHR-FaceAge inference, native
capture/import, local history, appearance preferences, metadata sharing and
deletion. New usable photos produce model estimates; unusable photos receive
actionable feedback. Sample records remain explicitly fictional.

| Check | Result |
| --- | --- |
| Shared JVM suite | 47 passed: 30 state/storage, 9 Ktor manifest and 8 photo-reference tests |
| Android connected suite | 10 passed, no skips, on a fresh Pixel 10 Pro Fold AVD, API 37, with Wi-Fi/mobile data disabled |
| Android debug build and lint | Build passed; lint reported no issues |
| iOS native simulator suite | 22 passed, including actual Core ML inference, image preprocessing, cancellation and photo-reference regressions |
| iOS UI suite | 3 flows passed again after model integration |
| iOS simulator and unsigned device builds | Both passed with Xcode 27.1 |
| iOS deployment target | Compiled app binaries report minimum iOS 17.0 |
| Model conversion | 24 pinned inputs agree with PyTorch; maximum error 0.0000687 years (LiteRT), 0.0000458 years (Core ML) |

## Model integration

The source checkpoint's downloaded bytes match SHA-256
`980ad2f134c5d82e9aca39a738f88a5b10aaf15ca9a5462bf95599be023cdbba`.
Strict weights-only loading consumes all 200 state tensors. Transformers 4.57.6
with the original key names and 5.14.1 with renamed ViT keys produce identical
reference predictions on all 24 cases. The converted graphs use float32,
including normalization; installed assets match the hashes in
[`models/conversion-validation.json`](../models/conversion-validation.json).

The 24 conversion cases are 22 hash-pinned author synthetic crops and two
mathematical patterns. Native tests independently run the bundled model on
gradient/gray inputs and compare tilted face alignment and resizing against
Pillow-generated pixel fixtures. A synthetic pattern is never accepted as a
face by bypassing detection in the shipping pipeline. These checks establish
numerical conversion and implementation behavior, not clinical accuracy or
equivalence of the native detectors to the paper's RetinaFace preprocessing.

Android's native tests cover real bundled ML Kit detection plus LiteRT inference,
zero/multiple-face rejection, missing-model feedback and cancellation ownership.
The actual photo-picker UI was then used to import a fresh padded author portrait:
Koin's analyzer returned 21.451419830322266 years (displayed as 21.5), the result
was saved, and cold relaunch restored it with the correct non-demo model ID.
The dedicated `ScanFace_Model_QA` AVD has a 12 GB data partition: the previous
AVD had too little free space to install the approximately 400 MB debug APK.
Both native tests and the manual UI flow ran with Wi-Fi and mobile data disabled.
The model is stored uncompressed in the APK for mapping, and only LiteRT-supported
ABIs are packaged. The build guard was tested with a missing notice, failed as
expected, and passed after restoration. Lint reported no issues.

iOS tests cover a fresh protected JPEG through Vision, guarded Core Image eye
recovery, alignment and actual Core ML inference, plus zero/multiple-face
rejection, invalid input, missing-model feedback and cancellation. The installed
27.0/27.1 simulators returned unusable Vision eye landmarks on clear portraits;
the native fallback must independently find exactly one face with both eyes and
at least 0.5 bounding-box overlap, and retains Vision's pose/size/boundary gates.
Tests verify usable geometry and rejection of mismatched fallback boxes. Input
to Vision is explicit BGRA; RGB orientation/channel ordering has a separate test.
The built app contains the compiled model, conversion metadata and license folder.
The actual iOS photo-picker UI also completed a fresh import through the injected
analyzer: the author portrait produced 82.6743392944336 years (displayed as 82.7),
was saved as `COMPLETE` with the Core ML model ID and a relative capture path,
and reopened after a cold relaunch. No source change was needed for either
platform's manual import/save/relaunch check. Their portraits differ; these two
numbers are not a comparison of Android and iOS numerical accuracy.

All model Python work used `conda dl`. The documented bootstrap and complete
conversion command were run successfully. Converter-only packages are isolated
under `artifacts/models/converter-python`; `pip check` reports no broken
requirements in `dl`. Weights and author portraits remain ignored generated
files; reproducible setup is documented in [models.md](models.md).

After validation, ScanFace's development app data was cleared and the Android,
iPhone Duo and iPad apps were left installed with empty histories and no private
capture files. The temporary Android MediaStore portrait was deleted; the single
iOS Photos fixture was moved to Recently Deleted, and its temporary cleanup
helper was uninstalled. No workflow was added for retrying existing photos.

## Existing app coverage

Android device tests cover front/rear switching, zoom or explicit recovery,
screen flash, private JPEG capture, save/reopen, theme persistence and preserving
the selected history record across folded/expanded transitions. A stalled zoom
request has a bounded recovery path; camera callbacks are guarded against stale
sessions.

iOS tests cover upright image normalization, private protected storage, atomic
snapshot roundtrips/migration, launch/navigation, theme persistence, sample
results and photo-picker cancellation. A separate actual picker import was saved,
the app was terminated and relaunched, and the stored photo and pending result
were confirmed.

Visual checks covered Android compact/expanded layouts, iPhone Duo closed/open
views, and iPad portrait/landscape and native camera layouts, including light and
dark appearance. Local screenshots are in `artifacts/screenshots/` and are excluded
from Git. Build/test reports are also generated locally:

- `shared/build/reports/tests/desktopTest/`
- `androidApp/build/reports/androidTests/connected/debug/`
- `androidApp/build/reports/lint-results-debug.html`
- `build/ios/Logs/Test/`

The navigation follow-up uses UIKit's iOS 27.1 vertical-bar edge and bar layout
region for Duo. On the folded simulator, the 466 × 678-point view reports a bar
region at (358, 174), sized 84 × 470 points. The shared icon rail occupies that
region; the content retains its safe-area layout and the bottom bar is removed.
Capture screenshots from the active outer display (`--display=1` in this
simulator): the default internal display is inactive while folded.
The updated rail was checked in both themes, through folding/rotation, and with
all four destinations plus camera dismissal. Android debug, iOS simulator and
unsigned iOS device builds passed after the change; the device binary still
reports minimum iOS 17.0.

The photo-reference follow-up fixes iOS container relocation without resetting
data. Saved scans, pending captures and cleanup entries are stored as relative
capture references and resolved together to current absolute paths on load.
Legacy absolute paths are supported and become relative on the next successful
write. The recovery snapshot remains untouched. Five new native tests cover real
JPEG loading, relative storage, relocated paths, legacy defaults migration and
idempotent deletion, including preserving unrelated files with the same name.
Eight shared regression tests also verify relocation, mixed path aliases,
ownership-safe startup cleanup, failed writes, invalid references and recovery
data preservation. The previously broken imported image was confirmed visible in
the running app, then saved with a relative reference and confirmed after relaunch.
All four existing records were retained at that earlier verification. For the
model-integration work, the user explicitly requested deleting existing photos:
the development app containers were cleared instead of adding a retry/migration
workflow for those records. Android debug and unsigned iOS device builds passed;
the iOS minimum remains 17.0.

The shared suite verifies failed writes, corrupt/unreadable storage, analysis
cancellation, invalid model output, explicit sample labeling, CSV escaping and
durable photo-deletion retries. Deletion requests are persisted before removing
images and are retried after restart without deleting images still owned by a
saved or pending scan.

Hardware limits: iOS runtime checks used the installed 27.1 simulators; the iOS 17
minimum was verified in the built binary, not on an iOS 17 device. Android runtime
checks used API 37. Physical lens quality, LED flash, autofocus and real sensor
rotation still need a smoke test on an iPhone/iPad and an Android handset.
Simulator success does not establish those hardware properties.

Physical-device inference latency, memory use and battery cost remain unmeasured.
Each full-precision model is approximately 327 MiB. Compression and prospective
accuracy evaluation of the native face-detection pipeline are separate future
work; no clinical performance claim follows from these simulator/emulator tests.
