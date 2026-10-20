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
    @Published private(set) var zoomFactors: [CGFloat] = [1]
    @Published private(set) var zoomFactor: CGFloat = 1
    @Published private(set) var isAdjustingZoom = false
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
            guard let self, self.active else { return }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .denied, .restricted:
                self.availability = .denied
            default:
                self.availability = .failed("The camera stopped. Try opening it again.")
            }
            self.isCapturing = false
            self.endScreenFlash()
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
        guard availability == .ready, !isCapturing, !isAdjustingZoom, canFlip else { return }
        availability = .preparing
        flash = .off
        configure(position: frontFacing ? .back : .front)
    }

    func selectLens(_ id: String) {
        guard availability == .ready, !isCapturing, !isAdjustingZoom, id != selectedLensID else { return }
        availability = .preparing
        flash = .off
        configure(position: frontFacing ? .front : .back, preferredID: id)
    }

    func selectZoom(_ factor: CGFloat) {
        guard availability == .ready, !isCapturing, !isAdjustingZoom,
              zoomFactors.contains(factor), factor != zoomFactor else { return }
        isAdjustingZoom = true
        queue.async { [weak self] in
            guard let self, let device = self.input?.device else {
                DispatchQueue.main.async { self?.isAdjustingZoom = false }
                return
            }
            do {
                try device.lockForConfiguration()
                device.videoZoomFactor = min(device.maxAvailableVideoZoomFactor, max(device.minAvailableVideoZoomFactor, factor))
                device.unlockForConfiguration()
                let applied = device.videoZoomFactor
                DispatchQueue.main.async {
                    self.zoomFactor = applied
                    self.isAdjustingZoom = false
                }
            } catch {
                DispatchQueue.main.async {
                    self.isAdjustingZoom = false
                    self.errorMessage = "This zoom is unavailable. Try another setting."
                }
            }
        }
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
                    self.zoomFactor = device.videoZoomFactor
                    self.zoomFactors = [1, 1.5, 2].filter {
                        $0 >= device.minAvailableVideoZoomFactor && $0 <= device.maxAvailableVideoZoomFactor
                    }
                    if self.zoomFactors.isEmpty { self.zoomFactors = [device.videoZoomFactor] }
                    self.isAdjustingZoom = false
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
        guard availability == .ready, !isCapturing, !isAdjustingZoom else { return }
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
        if illuminating { illuminating = false }
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
                    CameraFeedback.success()
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

    private var canvas: Color { Color(hex: dark ? 0x101A16 : 0xF6F7F2) }
    private var ink: Color { Color(hex: dark ? 0xF0F4EE : 0x16251F) }
    private var action: Color { Color(hex: dark ? 0xAED7BA : 0x245C45) }
    private var ready: Bool { model.availability == .ready && !model.isCapturing && !model.isAdjustingZoom }

    var body: some View {
        GeometryReader { geometry in
            let landscape = geometry.size.width > geometry.size.height * 1.2
            let aspect: CGFloat = landscape ? 4.0 / 3.0 : 3.0 / 4.0
            let maximumWidth: CGFloat = landscape ? 720 : 540
            let heightBudget = max(300, min(680, geometry.size.height - 142))
            let previewWidth = min(maximumWidth, max(200, geometry.size.width - 40), heightBudget * aspect)
            VStack(spacing: 12) {
                header
                ScrollView {
                    VStack(spacing: 16) {
                        preview
                            .frame(width: previewWidth, height: previewWidth / aspect)
                        Button {
                            CameraFeedback.light()
                            pickingPhoto = true
                        } label: {
                            Label("Choose an existing photo", systemImage: "photo.on.rectangle")
                                .font(.subheadline.weight(.medium))
                                .frame(minHeight: 48)
                                .contentShape(Rectangle())
                        }
                        .foregroundStyle(action)
                        .disabled(model.isCapturing)
                        .accessibilityIdentifier("camera.import")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, 20)
                }
                .scrollIndicators(.hidden)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
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
        .onChange(of: model.errorMessage) { _, message in
            if message != nil { CameraFeedback.error() }
        }
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
            Button("OK", role: .cancel) {
                CameraFeedback.light()
                model.errorMessage = nil
            }
        } message: { Text(model.errorMessage ?? "") }
    }

    private var header: some View {
        ZStack {
            Text("New scan")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(ink)
            HStack {
                Button {
                    CameraFeedback.light()
                    close()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 17, weight: .medium))
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .foregroundStyle(ink)
                .accessibilityLabel("Close camera")
                .accessibilityIdentifier("camera.close")
                Spacer()
            }
        }
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity)
        .frame(height: 48)
    }

    private var preview: some View {
        ZStack {
            Color(hex: 0x121614)
            if model.availability == .ready || model.availability == .preparing {
                CameraPreview(model: model)
                    .overlay {
                        if model.availability == .preparing {
                            ProgressView().tint(.white)
                                .accessibilityLabel("Opening camera")
                        }
                    }
            }

            VStack {
                LinearGradient(colors: [.black.opacity(0.48), .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: 100)
                Spacer(minLength: 0)
                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 180)
            }
            .allowsHitTesting(false)

            if model.availability == .ready {
                GeometryReader { geometry in
                    let guideTop: CGFloat = 72
                    let guideSpace = max(0, geometry.size.height - guideTop - 180)
                    let height = min(guideSpace, geometry.size.height * 0.48, geometry.size.width * 0.72)
                    if height >= 120 {
                        Ellipse()
                            .stroke(.white.opacity(0.36), style: StrokeStyle(lineWidth: 1, dash: [4, 8]))
                            .frame(width: height * 0.74, height: height)
                            .position(x: geometry.size.width / 2, y: guideTop + guideSpace / 2)
                    }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            } else if model.availability != .preparing {
                unavailableContent
                    .padding(.horizontal, 24)
                    .padding(.top, 56)
                    .padding(.bottom, 148)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            VStack(spacing: 0) {
                topControls
                Spacer(minLength: 8)
                zoomControl
                    .padding(.bottom, 12)
                shutterControls
            }
            .padding(16)
        }
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Camera preview")
        .accessibilityIdentifier("camera.preview")
    }

    private var topControls: some View {
        HStack {
            Menu {
                ForEach(model.availableFlashModes, id: \.self) { mode in
                    Button {
                        CameraFeedback.selection()
                        model.flash = mode
                    } label: {
                        Label(mode.rawValue, systemImage: model.flash == mode ? "checkmark" : mode.symbol)
                    }
                }
            } label: {
                Image(systemName: model.flash.symbol)
                    .font(.system(size: 19, weight: .medium))
                    .foregroundStyle(model.flash == .off ? .white : .yellow)
                    .frame(width: 48, height: 48)
                    .background(.black.opacity(0.28), in: Circle())
            }
            .disabled(!model.supportsFlash || !ready)
            .opacity(model.supportsFlash ? 1 : 0.4)
            .accessibilityLabel(model.supportsFlash ? "Flash \(model.flash.rawValue)" : "Flash unavailable on this camera")
            .accessibilityIdentifier("camera.flash")
            Spacer()
            if model.lenses.count > 1 {
                Menu {
                    ForEach(model.lenses) { lens in
                        Button {
                            CameraFeedback.selection()
                            model.selectLens(lens.id)
                        } label: {
                            if lens.id == model.selectedLensID {
                                Label(lens.title, systemImage: "checkmark")
                            } else { Text(lens.title) }
                        }
                    }
                } label: {
                    Image(systemName: "camera.aperture")
                        .font(.system(size: 19, weight: .medium))
                        .frame(width: 48, height: 48)
                        .background(.black.opacity(0.28), in: Circle())
                }
                .foregroundStyle(.white)
                .disabled(!ready)
                .accessibilityLabel("Choose camera lens")
                .accessibilityValue(model.lenses.first { $0.id == model.selectedLensID }?.title ?? "")
                .accessibilityIdentifier("camera.lens")
            }
        }
    }

    private var zoomControl: some View {
        HStack(spacing: 2) {
            ForEach(model.zoomFactors, id: \.self) { factor in
                let selected = abs(model.zoomFactor - factor) < 0.01
                Button {
                    CameraFeedback.selection()
                    model.selectZoom(factor)
                } label: {
                    Text(zoomLabel(factor))
                        .font(.system(size: 14, weight: .semibold, design: .rounded))
                        .foregroundStyle(selected ? Color.black : Color.white)
                        .frame(width: 44, height: 44)
                        .background(selected ? Color.white : Color.clear, in: Circle())
                        .frame(width: 48, height: 48)
                        .contentShape(Circle())
                }
                .disabled(!ready)
                .accessibilityLabel("\(zoomLabel(factor)) zoom")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(3)
        .background(.black.opacity(0.40), in: Capsule())
        .opacity(model.availability == .ready ? 1 : 0.4)
        .accessibilityIdentifier("camera.zoom")
    }

    private var shutterControls: some View {
        ZStack {
            Button {
                CameraFeedback.shutter()
                model.capture()
            } label: {
                ZStack {
                    Circle().stroke(.white, lineWidth: 3)
                    Circle().fill(.white).padding(7)
                    if model.isCapturing {
                        ProgressView().tint(.black)
                    }
                }
                .frame(width: 76, height: 76)
                .contentShape(Circle())
            }
            .buttonStyle(CameraShutterStyle())
            .disabled(!ready)
            .opacity(model.availability == .ready ? 1 : 0.35)
            .accessibilityLabel(model.isCapturing ? "Saving photo" : "Take photo")
            .accessibilityIdentifier("camera.capture")

            HStack {
                Spacer()
                Button {
                    CameraFeedback.selection()
                    model.flipCamera()
                } label: {
                    Image(systemName: "arrow.triangle.2.circlepath.camera")
                        .font(.system(size: 21, weight: .medium))
                        .foregroundStyle(.white)
                        .frame(width: 48, height: 48)
                        .background(.black.opacity(0.32), in: Circle())
                }
                .disabled(!model.canFlip || !ready)
                .opacity(model.canFlip ? 1 : 0.35)
                .accessibilityLabel("Switch to \(model.frontFacing ? "back" : "front") camera")
                .accessibilityIdentifier("camera.flip")
            }
        }
        .frame(height: 80)
    }

    @ViewBuilder private var unavailableContent: some View {
        VStack(spacing: 10) {
            Image(systemName: model.availability == .denied ? "camera.badge.ellipsis" : "camera")
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(.white.opacity(0.5))
            switch model.availability {
            case .denied:
                Text("Camera access is off").font(.headline)
                Button("Open Settings") {
                    CameraFeedback.light()
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .font(.subheadline.weight(.medium))
                .frame(minHeight: 48)
            case .interrupted:
                Text("Camera paused").font(.headline)
                Button("Try again") { CameraFeedback.light(); model.start() }
                    .frame(minHeight: 48)
            case .failed(let message):
                Text("Camera unavailable").font(.headline)
                Text(message).font(.caption)
                Button("Try again") { CameraFeedback.light(); model.start() }
                    .frame(minHeight: 48)
            default:
                Text("Camera unavailable").font(.headline)
                Text("Choose a photo below to continue.").font(.subheadline)
                    .foregroundStyle(.white.opacity(0.65))
            }
        }
        .foregroundStyle(.white)
        .multilineTextAlignment(.center)
    }

    private func zoomLabel(_ factor: CGFloat) -> String {
        factor == factor.rounded() ? "\(Int(factor))×" : "\(factor)×"
    }
}

private struct CameraShutterStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.93 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private enum CameraFeedback {
    static func selection() { UISelectionFeedbackGenerator().selectionChanged() }
    static func light() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func shutter() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

private final class PreviewView: UIView {
    var screenChanged: ((UIScreen?) -> Void)?
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        let screen = window?.windowScene?.screen
        // Screen attachment happens during SwiftUI updates. Defer the model's
        // flash-state publication until that view update has completed.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.window?.windowScene?.screen === screen else { return }
            self.screenChanged?(screen)
        }
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
