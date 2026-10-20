import AVFoundation
import PhotosUI
import SwiftUI
import UIKit
import ScanFaceKit

final class NativeCameraFactory: NSObject, CameraControllerFactory {
    func makeCameraController(
        darkTheme: Bool,
        onCaptured: @escaping (CapturedPhoto) -> Void,
        onClose: @escaping () -> Void
    ) -> UIViewController {
        let model = CaptureSessionModel(onCaptured: onCaptured)
        let controller = UIHostingController(
            rootView: CaptureScreen(model: model, dark: darkTheme, close: onClose)
        )
        controller.overrideUserInterfaceStyle = darkTheme ? .dark : .light
        controller.view.backgroundColor = darkTheme
            ? UIColor(red: 16/255, green: 26/255, blue: 22/255, alpha: 1)
            : UIColor(red: 246/255, green: 247/255, blue: 242/255, alpha: 1)
        return controller
    }
}

enum CaptureAvailability: Equatable {
    case preparing, ready, denied, unavailable, interrupted, failed(String)
}

enum CaptureFlash: String, CaseIterable {
    case off = "Off", auto = "Auto", on = "On", screen = "Screen"
    var avMode: AVCaptureDevice.FlashMode {
        switch self { case .off, .screen: return .off; case .auto: return .auto; case .on: return .on }
    }
    var symbol: String {
        switch self { case .off: return "bolt.slash"; case .screen: return "sun.max.fill"; default: return "bolt.fill" }
    }
}

struct CameraLens: Identifiable, Equatable {
    let id: String
    let title: String
}

/// All capture-session mutation is serialized; only published UI state is written
/// on the main queue. The capture owns one private JPEG and never uploads it.
final class CaptureSessionModel: NSObject, ObservableObject {
    let session = AVCaptureSession()
    @Published private(set) var availability: CaptureAvailability = .preparing
    @Published private(set) var isCapturing = false
    @Published private(set) var frontFacing = true
    @Published private(set) var canFlip = false
    @Published private(set) var supportsFlash = false
    @Published private(set) var availableFlashModes: [CaptureFlash] = [.off]
    @Published private(set) var lenses: [CameraLens] = []
    @Published private(set) var selectedLensID = ""
    @Published private(set) var illuminating = false
    @Published var flash: CaptureFlash = .off
    @Published var errorMessage: String?

    private let queue = DispatchQueue(label: "app.scanface.camera", qos: .userInitiated)
    private let photoOutput = AVCapturePhotoOutput()
    private var input: AVCaptureDeviceInput?
    private var activeDevice: AVCaptureDevice?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservation: NSKeyValueObservation?
    private var notifications: [NSObjectProtocol] = []
    private var captureLens = "Wide"
    private var active = false
    private weak var captureScreen: UIScreen?
    private var brightnessBeforeFlash: CGFloat?
    private var flashRestorationID = UUID()
    private let onCaptured: (CapturedPhoto) -> Void

    init(onCaptured: @escaping (CapturedPhoto) -> Void) {
        self.onCaptured = onCaptured
        super.init()
        let center = NotificationCenter.default
        notifications.append(center.addObserver(
            forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main
        ) { [weak self] _ in
            self?.availability = .interrupted
            self?.endScreenFlash()
            // Keep an in-flight capture busy until its AVFoundation completion.
        })
        notifications.append(center.addObserver(
            forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: .main
        ) { [weak self] _ in
            guard let self, self.active else { return }
            self.start()
        })
        notifications.append(center.addObserver(
            forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: .main
        ) { [weak self] _ in
            self?.availability = .failed("The camera stopped. Try opening it again.")
            self?.isCapturing = false
            self?.endScreenFlash()
        })
        notifications.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.stopSessionForBackground() })
        notifications.append(center.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, self.active else { return }
            self.start()
        })
        notifications.append(center.addObserver(
            forName: AVCaptureDevice.wasDisconnectedNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, self.active else { return }
            self.configure(position: self.frontFacing ? .front : .back)
        })
    }

    func attachPreview(_ layer: AVCaptureVideoPreviewLayer) {
        previewLayer = layer
        layer.session = session
        layer.videoGravity = .resizeAspectFill
        if let activeDevice { updateRotation(for: activeDevice) }
    }

    func attachScreen(_ screen: UIScreen?) {
        if screen !== captureScreen { endScreenFlash() }
        captureScreen = screen
    }

    func start() {
        active = true
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            availability = .preparing
            configure(position: frontFacing ? .front : .back)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
                DispatchQueue.main.async {
                    guard let self, self.active else { return }
                    if allowed { self.configure(position: .front) } else { self.availability = .denied }
                }
            }
        default:
            availability = .denied
        }
    }

    func stop() {
        active = false
        endScreenFlash()
        queue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func stopSessionForBackground() {
        availability = .interrupted
        endScreenFlash()
        queue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    func flipCamera() {
        guard !isCapturing, canFlip else { return }
        availability = .preparing
        flash = .off
        configure(position: frontFacing ? .back : .front)
    }

    func selectLens(_ id: String) {
        guard !isCapturing, id != selectedLensID else { return }
        availability = .preparing
        flash = .off
        configure(position: frontFacing ? .front : .back, preferredID: id)
    }

    private func discover(position: AVCaptureDevice.Position) -> [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInTrueDepthCamera, .builtInTelephotoCamera],
            mediaType: .video,
            position: position
        ).devices
    }

    private func configure(position: AVCaptureDevice.Position, preferredID: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            var candidates = self.discover(position: position)
            if candidates.isEmpty {
                candidates = self.discover(position: position == .front ? .back : .front)
            }
            let preferredSystem = AVCaptureDevice.systemPreferredCamera
            let device = candidates.first { $0.uniqueID == preferredID }
                // Prefer a physical wide lens: avoid close-range ultra-wide distortion.
                ?? candidates.first { $0.deviceType == .builtInWideAngleCamera }
                ?? candidates.first { $0.uniqueID == preferredSystem?.uniqueID }
                ?? candidates.first
            guard let device else {
                DispatchQueue.main.async { self.availability = .unavailable }
                return
            }

            do {
                let nextInput = try AVCaptureDeviceInput(device: device)
                self.session.beginConfiguration()
                self.session.sessionPreset = .photo
                if let oldInput = self.input { self.session.removeInput(oldInput) }
                guard self.session.canAddInput(nextInput) else {
                    if let oldInput = self.input, self.session.canAddInput(oldInput) {
                        self.session.addInput(oldInput)
                    }
                    self.session.commitConfiguration()
                    DispatchQueue.main.async { self.availability = .failed("This camera is unavailable. Try another lens.") }
                    return
                }
                self.session.addInput(nextInput)
                self.input = nextInput
                if self.session.outputs.isEmpty {
                    guard self.session.canAddOutput(self.photoOutput) else {
                        self.session.commitConfiguration()
                        DispatchQueue.main.async {
                            self.availability = .failed("This camera can't capture photos. Choose a photo instead.")
                        }
                        return
                    }
                    self.session.addOutput(self.photoOutput)
                }
                if self.session.isMultitaskingCameraAccessSupported {
                    self.session.isMultitaskingCameraAccessEnabled = true
                }
                self.photoOutput.maxPhotoQualityPrioritization = .quality
                self.session.commitConfiguration()
                try device.lockForConfiguration()
                if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
                if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
                if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { device.whiteBalanceMode = .continuousAutoWhiteBalance }
                device.videoZoomFactor = min(device.maxAvailableVideoZoomFactor, max(device.minAvailableVideoZoomFactor, 1))
                device.unlockForConfiguration()
                if !self.session.isRunning { self.session.startRunning() }

                let opposite = self.discover(position: device.position == .front ? .back : .front)
                let options = candidates.map {
                    CameraLens(id: $0.uniqueID, title: Self.lensTitle($0))
                }
                let hasHardwareFlash = device.hasFlash && device.isFlashAvailable
                let flashModes: [CaptureFlash] = hasHardwareFlash
                    ? [.off, .auto, .on].filter { self.photoOutput.supportedFlashModes.contains($0.avMode) }
                    : (device.position == .front ? [.off, .screen] : [.off])
                DispatchQueue.main.async {
                    guard self.active else { self.stop(); return }
                    self.activeDevice = device
                    self.frontFacing = device.position == .front
                    self.canFlip = !opposite.isEmpty
                    self.lenses = options
                    self.selectedLensID = device.uniqueID
                    self.supportsFlash = flashModes.count > 1
                    self.availableFlashModes = flashModes
                    self.updateRotation(for: device)
                    self.availability = .ready
                }
            } catch {
                DispatchQueue.main.async { self.availability = .failed("Couldn't open the camera. Please try again.") }
            }
        }
    }

    static func lensTitle(_ device: AVCaptureDevice) -> String {
        switch device.deviceType {
        case .builtInTelephotoCamera: return "Telephoto"
        case .builtInTrueDepthCamera: return "TrueDepth"
        default: return device.position == .front ? "Front wide" : "Wide · 1×"
        }
    }

    private func updateRotation(for device: AVCaptureDevice) {
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        rotationCoordinator = coordinator
        rotationObservation = coordinator.observe(
            \.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]
        ) { [weak self] coordinator, _ in
            DispatchQueue.main.async {
                guard let self, let connection = self.previewLayer?.connection else { return }
                let angle = coordinator.videoRotationAngleForHorizonLevelPreview
                if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
                if connection.isVideoMirroringSupported {
                    connection.automaticallyAdjustsVideoMirroring = false
                    connection.isVideoMirrored = device.position == .front
                }
            }
        }
    }

    func capture() {
        guard availability == .ready, !isCapturing else { return }
        isCapturing = true
        let angle = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture ?? 90
        let requestedFlash = supportsFlash ? flash.avMode : .off
        captureLens = lenses.first { $0.id == selectedLensID }?.title ?? "Wide"
        if frontFacing && flash == .screen {
            let restorationID = UUID()
            flashRestorationID = restorationID
            brightnessBeforeFlash = captureScreen?.brightness
            captureScreen?.brightness = 1
            illuminating = true
            // Restore the screen even if interrupted hardware never completes.
            // This does not release an in-flight photo request.
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                guard let self, self.flashRestorationID == restorationID else { return }
                self.endScreenFlash()
            }
            // Let auto-exposure settle under the same fill light used for exposure.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                guard let self else { return }
                guard self.active, self.availability == .ready else {
                    self.endScreenFlash()
                    self.isCapturing = false
                    return
                }
                self.performCapture(angle: angle, requestedFlash: requestedFlash)
            }
        } else {
            performCapture(angle: angle, requestedFlash: requestedFlash)
        }
    }

    private func performCapture(angle: CGFloat, requestedFlash: AVCaptureDevice.FlashMode) {
        queue.async { [weak self] in
            guard let self else { return }
            let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            settings.photoQualityPrioritization = .quality
            if self.photoOutput.supportedFlashModes.contains(requestedFlash) {
                settings.flashMode = requestedFlash
            }
            if let connection = self.photoOutput.connection(with: .video) {
                if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
                // Mirror only the viewfinder. Save canonical, unmirrored pixels for ML.
                if connection.isVideoMirroringSupported {
                    connection.automaticallyAdjustsVideoMirroring = false
                    connection.isVideoMirrored = false
                }
            }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    private func endScreenFlash() {
        flashRestorationID = UUID()
        illuminating = false
        if let brightnessBeforeFlash { captureScreen?.brightness = brightnessBeforeFlash }
        brightnessBeforeFlash = nil
    }

    func importPhoto(_ image: UIImage) {
        guard !isCapturing else { return }
        isCapturing = true
        persist(image, lens: "Imported photo")
    }

    private func persist(_ image: UIImage, lens: String) {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let stored = try CapturePhotoStorage.save(image)
                let photo = CapturedPhoto(
                    path: stored.url.path,
                    width: Int32(stored.width),
                    height: Int32(stored.height),
                    lens: lens,
                    mirrored: false,
                    capturedAtMillis: Int64(Date().timeIntervalSince1970 * 1000)
                )
                DispatchQueue.main.async {
                    self.isCapturing = false
                    guard self.active else {
                        try? FileManager.default.removeItem(at: stored.url)
                        return
                    }
                    self.onCaptured(photo)
                }
            } catch {
                DispatchQueue.main.async {
                    self.isCapturing = false
                    self.errorMessage = "Couldn't save this photo. Check available storage and try again."
                }
            }
        }
    }

    deinit {
        notifications.forEach(NotificationCenter.default.removeObserver)
    }
}

extension CaptureSessionModel: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
        DispatchQueue.main.async { self.endScreenFlash() }
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        DispatchQueue.main.async { self.endScreenFlash() }
        guard error == nil, let data = photo.fileDataRepresentation(), let image = UIImage(data: data) else {
            DispatchQueue.main.async {
                self.isCapturing = false
                self.errorMessage = "The photo couldn't be captured. Please try again."
            }
            return
        }
        persist(image, lens: captureLens)
    }
}

struct StoredPhoto {
    let url: URL
    let width: Int
    let height: Int
}

enum CapturePhotoStorage {
    /// Normalizes EXIF rotation and downsizes the longest edge to 2048 without
    /// distorting the aspect ratio. The model receives upright, unmirrored pixels.
    static func normalized(_ image: UIImage, maximumEdge: CGFloat = 2048) -> UIImage {
        let pixelSize = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        let factor = min(1, maximumEdge / max(pixelSize.width, pixelSize.height))
        let size = CGSize(width: max(1, (pixelSize.width * factor).rounded()), height: max(1, (pixelSize.height * factor).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    static func save(_ image: UIImage) throws -> StoredPhoto {
        let upright = normalized(image)
        guard let jpeg = upright.jpegData(compressionQuality: 0.92) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        var folder = base.appendingPathComponent("ScanFace/Captures", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try folder.setResourceValues(values)
        let destination = folder.appendingPathComponent(UUID().uuidString).appendingPathExtension("jpg")
        try jpeg.write(to: destination, options: [.atomic, .completeFileProtection])
        return StoredPhoto(url: destination, width: Int(upright.size.width), height: Int(upright.size.height))
    }
}

private struct CaptureScreen: View {
    @ObservedObject var model: CaptureSessionModel
    @State var dark: Bool
    let close: () -> Void
    @State private var pickingPhoto = false
    @Environment(\.dynamicTypeSize) private var typeSize

    private var canvas: Color { Color(hex: dark ? 0x101A16 : 0xF6F7F2) }
    private var ink: Color { Color(hex: dark ? 0xF0F4EE : 0x16251F) }
    private var secondary: Color { Color(hex: dark ? 0xB5C5B9 : 0x59695F) }
    private var surface: Color { Color(hex: dark ? 0x23382D : 0xE5EDE6) }
    private var action: Color { Color(hex: dark ? 0xAED7BA : 0x245C45) }
    private var onAction: Color { Color(hex: dark ? 0x12261A : 0xFFFFFF) }

    var body: some View {
        GeometryReader { geometry in
            let useColumns = geometry.size.width >= 700 && geometry.size.height >= 440 && !typeSize.isAccessibilitySize
            VStack(spacing: 16) {
                header
                if useColumns {
                    HStack(spacing: 24) {
                        preview.frame(maxWidth: .infinity, maxHeight: .infinity)
                        ScrollView { guidanceAndControls }
                            .frame(width: min(320, geometry.size.width * 0.36))
                    }
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            Text("Look straight ahead").font(.title2.weight(.semibold)).foregroundStyle(ink)
                            preview.frame(height: max(220, min(460, geometry.size.height * 0.48)))
                            controls
                        }
                    }
                }
            }
            .padding(24)
            .background(canvas)
        }
        .background(canvas.ignoresSafeArea())
        .overlay {
            if model.illuminating { Color.white.ignoresSafeArea().allowsHitTesting(false) }
        }
        .preferredColorScheme(dark ? .dark : .light)
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("ScanFaceThemeDidChange"))) { notification in
            if let newTheme = notification.userInfo?["dark"] as? Bool { dark = newTheme }
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .sheet(isPresented: $pickingPhoto) {
            PhotoPicker(completion: { image in
                pickingPhoto = false
                if let image { model.importPhoto(image) }
            }, onError: { message in
                pickingPhoto = false
                model.errorMessage = message
            })
            .preferredColorScheme(dark ? .dark : .light)
        }
        .alert("Photo unavailable", isPresented: Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { model.errorMessage = nil }
        } message: { Text(model.errorMessage ?? "") }
    }

    private var header: some View {
        HStack {
            Button(action: close) { Image(systemName: "xmark").frame(width: 44, height: 44) }
                .accessibilityLabel("Close camera")
                .accessibilityIdentifier("camera.close")
            Spacer()
            Text("New scan").font(.headline)
            Spacer()
            Menu {
                ForEach(model.availableFlashModes, id: \.self) { mode in
                    Button {
                        model.flash = mode
                    } label: {
                        Label(mode.rawValue, systemImage: model.flash == mode ? "checkmark" : mode.symbol)
                    }
                }
            } label: {
                Image(systemName: model.flash.symbol).frame(width: 44, height: 44)
            }
            .disabled(!model.supportsFlash || model.isCapturing)
            .accessibilityLabel(model.supportsFlash ? "Flash \(model.flash.rawValue)" : "Flash unavailable on this camera")
            .accessibilityIdentifier("camera.flash")
        }
        .foregroundStyle(ink)
    }

    @ViewBuilder private var preview: some View {
        ZStack {
            Color(hex: dark ? 0x192820 : 0xE5EDE6)
            if model.availability == .ready || model.availability == .preparing {
                CameraPreview(model: model)
                if model.availability == .ready {
                    GeometryReader { geometry in
                        let guideHeight = min(geometry.size.height * 0.82, geometry.size.width)
                        Ellipse()
                            .stroke(.white.opacity(0.9), style: StrokeStyle(lineWidth: 2, dash: [18, 6]))
                            .frame(width: guideHeight * 0.72, height: guideHeight)
                            .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
                    }.allowsHitTesting(false)
                } else {
                    ProgressView("Opening camera…").tint(ink).foregroundStyle(ink)
                }
            } else {
                unavailableContent.padding(24)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Camera preview")
    }

    @ViewBuilder private var unavailableContent: some View {
        VStack(spacing: 16) {
            Image(systemName: model.availability == .denied ? "camera.badge.ellipsis" : "camera")
                .font(.system(size: 42)).foregroundStyle(secondary)
            switch model.availability {
            case .denied:
                Text("Camera access is off").font(.title3.weight(.semibold))
                Text("Allow camera access in Settings, or choose a photo from your library.")
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }.buttonStyle(.bordered)
            case .interrupted:
                Text("Camera paused").font(.title3.weight(.semibold))
                Text("Close other camera apps or return ScanFace to the foreground.")
                Button("Try again") { model.start() }.buttonStyle(.bordered)
            case .failed(let message):
                Text("Camera unavailable").font(.title3.weight(.semibold))
                Text(message)
                Button("Try again") { model.start() }.buttonStyle(.bordered)
            default:
                Text("No camera available").font(.title3.weight(.semibold))
                Text("Choose a photo to continue. A real camera is available when using a supported device.")
            }
        }.foregroundStyle(ink).multilineTextAlignment(.center)
    }

    private var guidanceAndControls: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Look straight ahead").font(.largeTitle.weight(.semibold)).foregroundStyle(ink)
            Text("Use even lighting and keep your full face inside the oval. Hold the camera at eye level.")
                .foregroundStyle(secondary)
            controls
        }
    }

    private var controls: some View {
        VStack(spacing: 16) {
            HStack(spacing: 12) {
                Label("Even lighting", systemImage: "sun.max")
                Label("Neutral expression", systemImage: "face.smiling")
            }
            .font(.caption)
            .foregroundStyle(secondary)
            .padding(12).frame(maxWidth: .infinity)
            .background(surface, in: RoundedRectangle(cornerRadius: 14))

            if model.availability == .ready {
                HStack(spacing: 12) {
                    if model.lenses.count > 1 {
                        Menu {
                            ForEach(model.lenses) { lens in
                                Button(lens.title) { model.selectLens(lens.id) }
                            }
                        } label: {
                            Label(model.lenses.first { $0.id == model.selectedLensID }?.title ?? "Wide", systemImage: "camera.aperture")
                                .frame(minHeight: 44)
                        }.disabled(model.isCapturing)
                    } else {
                        Text(model.frontFacing ? "Front camera" : "Wide · 1×").font(.subheadline)
                    }
                    Spacer()
                    Button { model.flipCamera() } label: {
                        Label("Flip", systemImage: "arrow.triangle.2.circlepath.camera")
                            .frame(minHeight: 44)
                    }
                    .disabled(!model.canFlip || model.isCapturing)
                    .accessibilityLabel("Switch to \(model.frontFacing ? "back" : "front") camera")
                    .accessibilityIdentifier("camera.flip")
                }.foregroundStyle(ink)
                if !model.supportsFlash {
                    Text("Flash isn't available on this camera. Use even ambient light.")
                        .font(.caption).foregroundStyle(secondary)
                }
            }
            Label("Photos stay on your device", systemImage: "lock")
                .font(.caption).foregroundStyle(secondary)
            Button { model.capture() } label: {
                HStack {
                    if model.isCapturing { ProgressView().tint(onAction) }
                    else { Image(systemName: "camera") }
                    Text(model.isCapturing ? "Saving photo…" : "Take photo").fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity, minHeight: 56)
                .foregroundStyle(onAction)
                .background(action, in: RoundedRectangle(cornerRadius: 14))
            }
            .disabled(model.availability != .ready || model.isCapturing)
            .opacity(model.availability == .ready && !model.isCapturing ? 1 : 0.45)
            .accessibilityIdentifier("camera.capture")
            Button { pickingPhoto = true } label: {
                Label("Choose a photo", systemImage: "photo")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .foregroundStyle(action)
            .disabled(model.isCapturing)
            .accessibilityIdentifier("camera.import")
        }
    }
}

private final class PreviewView: UIView {
    var screenChanged: ((UIScreen?) -> Void)?
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        screenChanged?(window?.windowScene?.screen)
    }
}

private struct CameraPreview: UIViewRepresentable {
    let model: CaptureSessionModel
    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.screenChanged = { [weak model] in model?.attachScreen($0) }
        model.attachPreview(view.previewLayer)
        return view
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}
}

private struct PhotoPicker: UIViewControllerRepresentable {
    var completion: (UIImage?) -> Void
    var onError: (String) -> Void
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration()
        configuration.selectionLimit = 1
        configuration.filter = .images
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(completion: completion, onError: onError) }
    static func dismantleUIViewController(_ uiViewController: PHPickerViewController, coordinator: Coordinator) {
        coordinator.cancelPendingLoad()
    }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let completion: (UIImage?) -> Void
        let onError: (String) -> Void
        private var requestID = UUID()
        init(completion: @escaping (UIImage?) -> Void, onError: @escaping (String) -> Void) {
            self.completion = completion
            self.onError = onError
        }
        func cancelPendingLoad() { requestID = UUID() }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            cancelPendingLoad()
            let currentRequest = requestID
            guard let provider = results.first?.itemProvider else {
                completion(nil)
                return
            }
            guard provider.canLoadObject(ofClass: UIImage.self) else {
                onError("This image format isn't supported. Choose a different photo.")
                return
            }
            provider.loadObject(ofClass: UIImage.self) { [weak self] image, error in
                DispatchQueue.main.async {
                    guard let self, self.requestID == currentRequest else { return }
                    guard error == nil, let image = image as? UIImage else {
                        self.onError("This photo couldn't be loaded. Check its download in Photos and try again.")
                        return
                    }
                    self.completion(image)
                }
            }
        }
    }
}

private extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 255) / 255,
            green: Double((hex >> 8) & 255) / 255,
            blue: Double(hex & 255) / 255,
            opacity: 1
        )
    }
}
