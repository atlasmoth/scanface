# Build verification

The repository has no automated test suites. Shared Kotlin, Android instrumentation,
iOS XCTest/UI, and Python installer suites and their dedicated fixtures were
removed on 21 October 2026. The desktop JVM target was only a shared-test host
and has also been removed.

After removal, the Android debug build and lint passed, and the signed iOS
simulator build passed for iPhone Duo. Python model-tool syntax checks used
the `dl` Conda environment. No test suites were run.

## Current checks

Prepare the model assets using [the model setup instructions](models.md), then run:

```sh
scripts/check.sh
```

This builds the Android debug APK and runs Android lint. To also generate the
Xcode project and build iOS, specify an installed simulator:

```sh
SCANFACE_IOS_DESTINATION='platform=iOS Simulator,name=iPhone Duo' scripts/check.sh
```

These commands do not run tests or reset app data. Xcode's generated scheme
contains only the ScanFace app target. Physical-device builds require a signing
team, or `CODE_SIGNING_ALLOWED=NO` for compile-only verification.

Model preparation, installation, and app builds retain their artifact hash,
metadata, tensor-contract, and license-presence checks. Numerical conversion
and quantization reports are preserved as model provenance; see
[model details](models.md) and [the quantization comparison](quantization.md).

## Device validation

External image opening was checked on 25 October 2026 with temporary sender apps
outside the repository. Android build/lint and the ARM64 iOS simulator build
passed. JPEG/PNG opening, queued imports behind unfinished scans and Settings,
restart recovery, corrupt files and unavailable grants were exercised. Saved
results and photo bytes remained intact; imports did not request the camera.
Temporary checks also covered failed queue writes and ownership-safe cleanup.
No test source or fixtures were added to the repository.

The supported minimums remain Android API 26 and iOS 17. Simulator and emulator
builds do not establish physical camera quality, lens/LED-flash behavior, haptic
feel, or on-device inference latency, memory use, and battery cost. These still
require manual checks on physical Android and iOS devices.
