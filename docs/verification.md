# Verification — 21 October 2026

The Android and iOS apps build and run with real FAHR-FaceAge inference, native
capture/import, local history, appearance preferences, metadata sharing and
deletion. New usable photos produce model estimates; unusable photos receive
actionable feedback. Saved results come from captures or imported photos.

| Check | Result |
| --- | --- |
| Shared JVM suite | 56 passed: 29 state/storage, 9 Ktor manifest, 8 photo-reference, 8 asynchronous camera-zoom and 2 scan-feedback timing tests |
| Android connected suite | 4 UI flows passed across the camera-refinement run and targeted rerun, including real two-finger zoom and resume; all 10 earlier UI/native tests passed with selected int8 weights during quantization, on the Pixel 10 Pro Fold AVD, API 37 |
| Android debug build and lint | Build passed; lint reported no issues |
| iOS native simulator suite | 23 passed with selected int8 weights, including actual Core ML inference, metadata rejection, preprocessing and storage regressions |
| iOS camera zoom tests | 4 passed: cumulative pinch scale, hardware/product bounds, lens reset and invalid gesture input |
| iOS UI suite | 3 Duo flows and the iPad camera flow passed after camera refinement, including full-height preview/control geometry, theme persistence and permission/picker recovery |
| iOS simulator and unsigned device builds | Both passed with Xcode 27.1 |
| iOS deployment target | Compiled app binaries report minimum iOS 17.0 |
| Model conversion | 24 pinned inputs agree with PyTorch; maximum error 0.0000687 years (LiteRT), 0.0000458 years (Core ML) |
| Quantized installer | 8 safety tests passed, including corrupt artifacts, incomplete evidence, stale package cleanup and rollback |

## Scan feedback

After capture, analysis stays in the live camera preview beneath thin mint corner
brackets, small edge ticks and a narrow moving light band. Camera controls and
pinch zoom are locked while close remains available to cancel. Imported photos
appear within that same preview area during analysis. The overlay is clipped to
the full visible camera image, or the exact aspect-fit rectangle of an imported
photo; the sweep can reach its top and bottom edges without entering letterboxes.

Inference completion immediately starts the result's 220 ms fade-in. The outgoing
camera fades over 180 ms while its sweep finishes at the image's bottom edge over
160 ms; neither animation gates navigation. Capture controls stay locked through
the handoff, and imported pixels remain visible until disposal. Each deliberate
retake has a fresh camera session, even if the previous exit is still running.
Reduced motion, backgrounding and an open confirmation dialog skip the transition.
The result photo has no scan animation.

On iOS, UIKit fades the still-mounted camera controller itself. Result photos
use a remembered Compose bitmap so they blend and clip with the rest of the
result; applying Compose alpha to embedded UIKit views caused a brief blank
frame during the first verification and was replaced.

Soft scan feedback begins after 180 ms and repeats every 650 ms while analysis
is visibly active. A successful result receives a completion cue; unsuccessful
analysis retains its existing warning cue. Pulses stop when inference finishes;
the outgoing overlay completes during the fade. Both stop on cancellation,
a confirmation dialog, or backgrounding. When reduced motion is enabled,
static brackets remain and decorative pulses stop.
Android also observes disabled animations and Battery Saver. Native haptics
follow current platform settings and suppress background completion feedback.
There is no minimum duration or recording delay in either app.

All 56 shared tests passed, including spaced pulses, cancellation and avoiding
stacked feedback for very fast inference. Android debug APK, instrumentation APK
and lint passed for the updated handoff.

One focused Android instrumentation flow passed using the real App navigation
and CameraX capture with an inference hold confined to the test APK. It verifies
that the same preview stays attached and streaming, input controls are locked,
the overlay matches the visible preview bounds, the discard dialog pauses
feedback, and cancellation cannot open a stale result. Results appear within
one second of releasing inference, the camera releases within one second of
result entry, and the outgoing preview never unlocks. Its evidence is in
`artifacts/scan-feedback/android-handoff/`.

The final iOS simulator and unsigned device builds passed; the device binary
retains minimum iOS 17.0. An ordinary photo import on Duo showed the scan clipped
to the imported image inside the native viewfinder, then opened the real Core ML
result. Transition frames confirm the scan reaches the image's bottom edge while
the camera and result blend without a blank-photo gap. The recording contains no
test delay: the handoff is around 8.2–8.4 seconds in
`artifacts/scan-feedback/ios/handoff/duo-native-handoff.mp4`. The earlier in-camera
pass also verified native cancellation. The development apps were restored
without pending captures or test launch arguments.

Local screenshots and recordings are in `artifacts/scan-feedback/`. Simulator
checks verify feedback timing and lifecycle; the tactile feel still needs a
physical device.

## Launch refinement

Launch now uses Home's fine-line scan mark on the app's cream or forest canvas.
The old launcher-icon badge is replaced with a clean viewfinder/face outline and
one short scanning sweep. There is no loading message, startup haptic or separate
shared splash route. Restored scans still open their existing destination.

Android uses its native splash API, with animated vector artwork on Android 12+
and a static compatibility fallback on older versions. iOS pairs static launch
artwork with a native animated handoff while Compose renders. Neither waits for
the animation to finish or for model initialization before revealing ready
content. Returning to a running app does not replay the sequence; disabled
animations and reduced-motion preferences skip the decorative motion.

System launch windows use system appearance before application code can read
preferences. Runtime handoffs restore the saved app appearance without changing
the device's theme. The mark stays centered in the launch window on both
platforms. On Duo, using the same window coordinates avoids a jump when the
runtime receives its asymmetric safe area for right-side navigation.

Five focused iOS tests passed on Duo: saved appearance resolution, corrupt or
missing preference fallback, one handoff per process, cold-launch/resume
interactivity, and persisted appearance. The launch UI check waits for a hittable
Settings control and changes appearance after returning from the background.
Android debug compilation and lint passed. Recorded cold launches covered both
palettes and saved appearance opposite to the system setting; manual checks also
confirmed that hot return preserves History and disabled animations still allow
immediate navigation. iOS simulator and unsigned device builds passed, with the
device binary retaining minimum iOS 17.0.

iOS recordings cover iPad light/dark, Duo light and a forced dark app over a light
system. Duo was also launched with Reduce Motion enabled; Settings remained
interactive and the preference was restored afterward. The final window-centering
adjustment followed the five passing tests and was checked with fresh simulator
and device builds plus a Duo recording.

Launch recordings and extracted frames are stored locally in `artifacts/splash/`.
The iOS visual checks use an ad-hoc signed simulator install: SpringBoard rejected
the storyboard in the earlier unsigned test artifact, producing a black launch
screen. Signing the simulator app restored the expected native launch artwork.

## Interface polish

Home and empty history now use centered content with generous top spacing.
Repeated cards, the home guidance panel, privacy tagline and Settings research
section were removed. Populated overview/history use open sections and simple
rows; scan details preserve the complete photo. The “About this scan” disclosure
was removed. Settings now has a plain “How it works” explanation of face alignment,
on-device estimation and entered-age comparison alongside appearance, age and
saved-scan sections. Light/dark colors remain consistent across shared and native
views.

Both camera screens fill the available preview height above a compact photo-import
footer. The separate header, fixed aspect ratios and width/height caps are gone;
close sits inside the preview. A single bottom row places camera switch on the
left, the circular shutter in the center and flash on the right. Pinch replaces
zoom buttons, with a continuous range up to the lower of 4× and the active
camera's limit. Accessible adjustments remain available without visible chips.
The zoom readout appears while zooming and briefly afterward. Coalesced zoom
requests preserve the final gesture target; capture uses the applied zoom and
lens changes invalidate pending updates before establishing a fresh baseline.
CameraX and AVFoundation retain their physical lens, flash and rotation behavior.
Regular navigation rails can scroll in short landscape windows; Duo's
system-provided right-side region is preserved.

Native feedback covers navigation, selections, camera controls, capture/import,
successful persistence and errors. Android uses View haptic feedback, iOS uses
UIKit generators, and desktop hosts use a no-op adapter. Tap and asynchronous
outcome feedback are separate; restored state does not trigger a launch buzz.
Physical haptic feel and actual iOS camera hardware still require device checks.

The camera refinement passed all 54 shared tests, four Android UI flows, four
iOS zoom tests, three Duo UI flows and the iPad camera flow. Android's native
pinch checks cover zoom in/out, the 1× floor and 4× cap, lens reset, and
background/resume. Android build/lint and iOS simulator/unsigned device builds
passed; the device minimum
remains iOS 17.0. The earlier interface pass also ran all 23 iOS model/storage
tests and the three UI flows on iPad.

Visual review covered folded/expanded Android, short landscape, folded Duo with
right-side navigation, and iPad in light/dark modes. A real Android photo-picker
import produced a 21.5-year estimate and was saved into the new history/detail
layout. Returning from the picker now restores system-bar contrast for the app's
explicit theme. Snackbar and elevated-surface colors use the same app palette.
Those screenshots are stored locally in `artifacts/screenshots/polish/`.
The full-height camera was reviewed separately in light/dark modes on Android,
Duo and iPad, including short Duo landscape. Its actual screenshots are in
`artifacts/screenshots/camera-refinement/`; iOS simulator images show the native
unavailable/denied state because no real camera hardware is present.
Android now reapplies system-bar contrast after configuration and bounds changes;
folding and unfolding in dark mode keeps the status icons legible.

## Removal of sample results

The preview-results feature was removed from Overview, Settings, history and
result rendering. Its data generator, saved-record flag, callbacks and CSV
classification column were deleted. History now contains only saved captures
or imported photos. No migration workflow was added; the development installs
were cleared.

All 46 shared tests, three Android UI flows and three iOS UI flows passed after
the removal. The UI tests now cover empty-history navigation, persisted
appearance, adaptive folding, camera capture/save/relaunch and picker
cancellation without generating preview records. Android debug/lint and the
iOS simulator build passed. Final incremental builds include the simplified
CSV export; both development apps were reinstalled with empty history.

## Quantization follow-up

Both apps now bundle the locked 8-bit-weight, float32-compute selection:
83.34 MiB LiteRT and 82.54 MiB Core ML packages. Android's clean debug APK is
153,867,249 bytes (146.74 MiB), down from 409,398,357 bytes (390.43 MiB).
An incremental APK retained unused ZIP space after replacing the asset, so the
final APK was rebuilt from clean Android outputs. The selected TFLite entry is
uncompressed and its bytes match the committed selection hash.

The compiled Core ML model in both simulator and unsigned device apps is
86,584,797 bytes (82.574 MiB), confirming compression survives compilation.
The device binary still reports minimum iOS 17.0. The new native model IDs
identify the int8-weight selection, and build/runtime checks reject incompatible
profiles. Negative missing/wrong metadata and modified-weight checks failed as
expected; restored selected assets passed the builds.

All six LiteRT candidates ran through Android's actual CompiledModel CPU path.
Five Core ML candidates were compared on macOS CPU_ONLY and ALL requests.
The selected models meet the preselected mean/max face-drift gate of
0.10/0.25 years. Android native mean/max drift was 0.046233/0.166481 years;
Core ML CPU was 0.048133/0.150932 and ALL was 0.048132/0.150948 years.
These checks use 22 synthetic author crops plus two separate diagnostic
patterns, not a clinical validation set. Timings show essentially unchanged
warm inference for the selected models. See the complete
[size, drift and latency comparison](quantization.md).

Both selected artifacts reproduced byte-identically. An isolated fresh
checkpoint-to-Core-ML export also reproduced the selected package, without
modifying the historical baseline or app assets. All Python work used `dl`, and
`pip check` reports no broken requirements. Final Android and Duo installations
have empty histories and no private captures; temporary native benchmark models
were removed from the emulator.

## Model integration

The source checkpoint's downloaded bytes match SHA-256
`980ad2f134c5d82e9aca39a738f88a5b10aaf15ca9a5462bf95599be023cdbba`.
Strict weights-only loading consumes all 200 state tensors. Transformers 4.57.6
with the original key names and 5.14.1 with renamed ViT keys produce identical
reference predictions on all 24 cases. The original baseline graphs use float32,
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
was saved, and cold relaunch restored it with the correct model ID.
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
snapshot roundtrips/migration, launch/navigation, theme persistence, empty
history and photo-picker cancellation. A separate actual picker import was saved,
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
cancellation, invalid model output, capture metadata exports, CSV escaping and
durable photo-deletion retries. Deletion requests are persisted before removing
images and are retried after restart without deleting images still owned by a
saved or pending scan.

Hardware limits: iOS runtime checks used the installed 27.1 simulators; the iOS 17
minimum was verified in the built binary, not on an iOS 17 device. Android runtime
checks used API 37. Physical lens quality, LED flash, autofocus and real sensor
rotation still need a smoke test on an iPhone/iPad and an Android handset.
Simulator success does not establish those hardware properties.

Physical-device inference latency, memory use and battery cost remain unmeasured.
Each preserved full-precision baseline is approximately 327 MiB; the apps now
ship the approximately 83/82 MiB compressed selections described above.
Prospective accuracy evaluation of the native face-detection pipeline remains
future work; no clinical performance claim follows from these host and
simulator/emulator tests.
