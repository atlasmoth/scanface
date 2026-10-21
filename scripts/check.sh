#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :shared:desktopTest :androidApp:assembleDebug :androidApp:lintDebug --console=plain

if [[ -n "${SCANFACE_IOS_DESTINATION:-}" ]]; then
  xcodegen generate --spec iosApp/project.yml
  xcodebuild -project iosApp/ScanFace.xcodeproj -scheme ScanFace \
    -destination "$SCANFACE_IOS_DESTINATION" \
    -derivedDataPath iosApp/DerivedData test
fi
