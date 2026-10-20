# Verification — 20 October 2026

The Android and iOS apps build and run with real capture/import, local history,
appearance preferences, metadata sharing and deletion. Real photos remain in
`AWAITING_MODEL`; only explicitly selected sample records contain estimates.

| Check | Result |
| --- | --- |
| Shared JVM suite | 39 passed: 30 state/storage tests and 9 Ktor manifest tests |
| Android connected suite | 3 passed on Pixel 10 Pro Fold, API 37 |
| Android debug build and lint | Build passed; lint reported no issues |
| iOS simulator suite | 8 passed: 5 native storage/image tests and 3 UI flows |
| iOS simulator and unsigned device builds | Both passed with Xcode 27.1 |
| iOS deployment target | Compiled app binaries report minimum iOS 17.0 |

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

Model installation, model-specific preprocessing and LiteRT/Core ML inference are
intentionally the next implementation work. No weights or fabricated estimates
for user photos are included.
