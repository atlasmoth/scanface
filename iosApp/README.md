# ScanFace for iOS

Open `ScanFace.xcodeproj` in Xcode 27.1 or newer and select the **ScanFace** scheme.
The minimum deployment target is iOS 17.0. The build phase invokes the repository's
Gradle wrapper to build and embed `ScanFaceKit`; Java 17 must be installed.
Choose your signing team before installing on a physical device.

The checked-in project is generated from `project.yml`. After changing project
configuration, run `xcodegen generate` in this directory. Privacy strings,
supported orientations, and launch configuration are defined in that file.

```sh
# From the repository root:
xcodebuild -project iosApp/ScanFace.xcodeproj -scheme ScanFace \
  -destination 'platform=iOS Simulator,name=iPhone Duo' \
  -derivedDataPath build/ios CODE_SIGNING_ALLOWED=NO test
```

The SwiftUI host embeds the shared Compose application. Shared navigation opens
the native AVFoundation capture screen through `CameraControllerFactory`.
The capture screen uses current view bounds and safe areas, so it responds to
iPhone Duo resizing, iPad multitasking, rotation, and large accessibility text.

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
description, mirroring state, and capture time.

Simulator runs exercise navigation, appearance, adaptation, import, and photo
normalization. Camera preview, hardware flash, camera switching, focus, and
physical rotation require a real iPhone or iPad for final verification.
