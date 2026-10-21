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
        let controller = CameraHostController(model: model, dark: darkTheme, close: onClose)
        controller.overrideUserInterfaceStyle = darkTheme ? .dark : .light
        controller.view.backgroundColor = darkTheme
            ? UIColor(red: 16/255, green: 26/255, blue: 22/255, alpha: 1)
            : UIColor(red: 246/255, green: 247/255, blue: 242/255, alpha: 1)
        return controller
    }

    func updateAnalysis(controller: UIViewController, analyzing: Bool, scanComplete: Bool, active: Bool, reduceMotion: Bool) {
        (controller as? CameraHostController)?.updateAnalysis(
            analyzing: analyzing, scanComplete: scanComplete, active: active, reduceMotion: reduceMotion
        )
    }
}

private final class CameraHostController: UIHostingController<CaptureScreen> {
    let model: CaptureSessionModel
    private var handingOffResult = false

    init(model: CaptureSessionModel, dark: Bool, close: @escaping () -> Void) {
        self.model = model
        super.init(rootView: CaptureScreen(model: model, dark: dark, close: close))
        NotificationCenter.default.addObserver(self, selector: #selector(finishHandoffImmediately), name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(motionPreferenceChanged), name: UIAccessibility.reduceMotionStatusDidChangeNotification, object: nil)
    }

    @MainActor required dynamic init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func updateAnalysis(analyzing: Bool, scanComplete: Bool, active: Bool, reduceMotion: Bool) {
        model.updateAnalysis(analyzing: analyzing, scanComplete: scanComplete, active: active, reduceMotion: reduceMotion)
        guard scanComplete else { return }
        if !handingOffResult {
            handingOffResult = true
            view.isUserInteractionEnabled = false
            view.accessibilityElementsHidden = true
            // Compose keeps this controller mounted during the result's entrance.
            // Animate UIKit directly: a Compose alpha layer hides native interop
            // views instead of blending the outgoing camera with the result.
            if active && !reduceMotion && !UIAccessibility.isReduceMotionEnabled {
                UIView.animate(withDuration: 0.18, delay: 0, options: [.beginFromCurrentState, .curveEaseOut]) {
                    self.view.alpha = 0
                }
            } else {
                finishHandoffImmediately()
            }
        } else if !active || reduceMotion {
            finishHandoffImmediately()
        }
    }

    @objc private func finishHandoffImmediately() {
        guard handingOffResult else { return }
        view.layer.removeAllAnimations()
        view.alpha = 0
    }

    @objc private func motionPreferenceChanged() {
        if UIAccessibility.isReduceMotionEnabled { finishHandoffImmediately() }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        finishHandoffImmediately()
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

/// Pinch scale is relative to the beginning of a gesture, not its last event.
struct CameraZoomState {
    private(set) var factor: CGFloat = 1
    private(set) var minimum: CGFloat = 1
    private(set) var maximum: CGFloat = 1
    private var pinchOrigin: CGFloat = 1

    mutating func reset(deviceMinimum: CGFloat, deviceMaximum: CGFloat, appliedFactor: CGFloat) {
        minimum = deviceMinimum.isFinite ? max(1, deviceMinimum) : 1
        maximum = deviceMaximum.isFinite ? max(minimum, min(4, deviceMaximum)) : minimum
        factor = appliedFactor.isFinite ? max(minimum, min(maximum, appliedFactor)) : minimum
        pinchOrigin = factor
    }

    mutating func beginPinch() { pinchOrigin = factor }

    mutating func pinch(scale: CGFloat) {
        guard scale.isFinite, scale > 0 else { return }
        setFactor(pinchOrigin * scale)
    }

    mutating func setFactor(_ value: CGFloat) {
        guard value.isFinite else { return }
        factor = max(minimum, min(maximum, value))
    }

    static func captureDescription(lens: String, factor: CGFloat) -> String {
        "\(lens) · \(String(format: "%.2f", Double(factor)))×"
    }
}

/// All capture-session mutation is serialized; only published UI state is written
/// on the main queue. The capture owns one private JPEG and never uploads it.
final class CaptureSessionModel: NSObject, ObservableObject {
    let session = AVCaptureSession()
    @Published private(set) var availability: CaptureAvailability = .preparing
    @Published private(set) var isCapturing = false
    @Published private(set) var isAnalyzing = false
    @Published private(set) var scanComplete = false
    @Published private(set) var scanActive = false
    @Published private(set) var reduceScanMotion = false
    @Published private(set) var importedAnalysisImage: UIImage?
    @Published private(set) var frontFacing = true
    @Published private(set) var canFlip = false
    @Published private(set) var supportsFlash = false
    @Published private(set) var availableFlashModes: [CaptureFlash] = [.off]
    @Published private(set) var lenses: [CameraLens] = []
    @Published private(set) var selectedLensID = ""
    @Published private(set) var zoom = CameraZoomState()
    @Published private(set) var showingZoom = false
    @Published private(set) var illuminating = false
    @Published var flash: CaptureFlash = .off
    @Published var errorMessage: String?
    var analysisBusy: Bool { isAnalyzing || scanComplete }

    private let queue = DispatchQueue(label: "app.scanface.camera", qos: .userInitiated)
    private let photoOutput = AVCapturePhotoOutput()
    private var input: AVCaptureDeviceInput?
    private var activeDevice: AVCaptureDevice?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservation: NSKeyValueObservation?
    private var notifications: [NSObjectProtocol] = []
    private var captureDescriptions: [Int64: String] = [:]
    private var active = false
    private weak var captureScreen: UIScreen?
    private var brightnessBeforeFlash: CGFloat?
    private var flashRestorationID = UUID()
    private var pendingZoomFactor: CGFloat?
    private var zoomUpdateInFlight = false
    private var zoomGeneration = 0
    private var zoomReadoutDismissal: DispatchWorkItem?
    private var pinching = false
    private let onCaptured: (CapturedPhoto) -> Void

    init(onCaptured: @escaping (CapturedPhoto) -> Void) {
        self.onCaptured = onCaptured
        super.init()
        let center = NotificationCenter.default
        notifications.append(center.addObserver(
            forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main
        ) { [weak self] _ in
            self?.availability = .interrupted
            self?.invalidateZoomUpdates()
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
            self.invalidateZoomUpdates()
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
        isAnalyzing = false
        scanComplete = false
        scanActive = false
        importedAnalysisImage = nil
        invalidateZoomUpdates()
        endScreenFlash()
        queue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    func updateAnalysis(analyzing: Bool, scanComplete: Bool, active: Bool, reduceMotion: Bool) {
        if isAnalyzing != analyzing {
            isAnalyzing = analyzing
            if analyzing { invalidateZoomUpdates() }
        }
        if self.scanComplete != scanComplete { self.scanComplete = scanComplete }
        let visible = (analyzing || scanComplete) && active
        if scanActive != visible { scanActive = visible }
        if reduceScanMotion != reduceMotion { reduceScanMotion = reduceMotion }
        // The store can finish one update before navigation starts its fade.
        // Retain imported pixels until disposal or a new capture, avoiding a flash
        // of the live camera between those two updates.
    }

    private func stopSessionForBackground() {
        availability = .interrupted
        invalidateZoomUpdates()
        endScreenFlash()
        queue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    func flipCamera() {
        guard availability == .ready, !isCapturing, !analysisBusy, canFlip else { return }
        availability = .preparing
        flash = .off
        configure(position: frontFacing ? .back : .front)
    }

    func selectLens(_ id: String) {
        guard availability == .ready, !isCapturing, !analysisBusy, id != selectedLensID else { return }
        availability = .preparing
        flash = .off
        configure(position: frontFacing ? .front : .back, preferredID: id)
    }

    func beginPinch() -> Int? {
        guard availability == .ready, !isCapturing, !analysisBusy else { return nil }
        zoom.beginPinch()
        pinching = true
        showZoomReadout()
        return zoomGeneration
    }

    func updatePinch(scale: CGFloat, generation: Int) {
        guard availability == .ready, !isCapturing, !analysisBusy, generation == zoomGeneration else { return }
        var updated = zoom
        updated.pinch(scale: scale)
        requestZoomUpdate(updated)
    }

    func endPinch(generation: Int) {
        guard generation == zoomGeneration else { return }
        pinching = false
        showZoomReadout()
    }

    func adjustAccessibleZoom(increasing: Bool) {
        guard availability == .ready, !isCapturing, !analysisBusy else { return }
        var updated = zoom
        updated.setFactor(zoom.factor + (increasing ? 0.25 : -0.25))
        requestZoomUpdate(updated)
    }

    private func showZoomReadout() {
        zoomReadoutDismissal?.cancel()
        if !showingZoom { showingZoom = true }
        guard !pinching else { return }
        let dismissal = DispatchWorkItem { [weak self] in self?.showingZoom = false }
        zoomReadoutDismissal = dismissal
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2, execute: dismissal)
    }

    private func requestZoomUpdate(_ updated: CameraZoomState) {
        showZoomReadout()
        guard abs(updated.factor - zoom.factor) >= 0.001 else { return }
        zoom = updated
        pendingZoomFactor = zoom.factor
        applyPendingZoom()
    }

    private func invalidateZoomUpdates() {
        zoomGeneration += 1
        pendingZoomFactor = nil
        zoomUpdateInFlight = false
        zoomReadoutDismissal?.cancel()
        pinching = false
        if showingZoom { showingZoom = false }
    }

    /// Keep one device update in flight and replace its pending successor with
    /// the newest gesture value, rather than building a queue of stale scales.
    private func applyPendingZoom() {
        guard !zoomUpdateInFlight, let factor = pendingZoomFactor, let device = activeDevice else { return }
        pendingZoomFactor = nil
        zoomUpdateInFlight = true
        let generation = zoomGeneration
        queue.async { [weak self] in
            guard let self else { return }
            var succeeded = false
            do {
                try Self.applyZoom(factor, to: device)
                succeeded = true
            } catch { }
            let applied = device.videoZoomFactor
            DispatchQueue.main.async {
                guard generation == self.zoomGeneration else { return }
                self.zoomUpdateInFlight = false
                if succeeded {
                    self.applyPendingZoom()
                } else {
                    self.pendingZoomFactor = nil
                    self.zoom.setFactor(applied)
                    self.errorMessage = "Couldn't adjust zoom. Please try again."
                }
            }
        }
    }

    private static func applyZoom(_ factor: CGFloat, to device: AVCaptureDevice) throws {
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }
        device.videoZoomFactor = min(device.maxAvailableVideoZoomFactor, max(device.minAvailableVideoZoomFactor, min(4, factor)))
    }

    private func discover(position: AVCaptureDevice.Position) -> [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInTrueDepthCamera, .builtInTelephotoCamera],
            mediaType: .video,
            position: position
        ).devices
    }

    private func configure(position: AVCaptureDevice.Position, preferredID: String? = nil) {
        invalidateZoomUpdates()
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
                    self.zoom.reset(deviceMinimum: device.minAvailableVideoZoomFactor,
                                    deviceMaximum: device.maxAvailableVideoZoomFactor,
                                    appliedFactor: device.videoZoomFactor)
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
        default: return device.position == .front ? "Front wide" : "Wide"
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
        guard availability == .ready, !isCapturing, !analysisBusy else { return }
        importedAnalysisImage = nil
        isCapturing = true
        let requestedZoom = zoom.factor
        invalidateZoomUpdates()
        let angle = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture ?? 90
        let requestedFlash = supportsFlash ? flash.avMode : .off
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
                self.performCapture(angle: angle, requestedFlash: requestedFlash, requestedZoom: requestedZoom)
            }
        } else {
            performCapture(angle: angle, requestedFlash: requestedFlash, requestedZoom: requestedZoom)
        }
    }

    private func performCapture(angle: CGFloat, requestedFlash: AVCaptureDevice.FlashMode, requestedZoom: CGFloat) {
        queue.async { [weak self] in
            guard let self else { return }
            var captureDescription = "Camera"
            do {
                if let device = self.input?.device {
                    // Settle the newest gesture target before exposure and record
                    // the actual device factor, including any hardware clamping.
                    try Self.applyZoom(requestedZoom, to: device)
                    captureDescription = CameraZoomState.captureDescription(
                        lens: Self.lensTitle(device), factor: device.videoZoomFactor
                    )
                }
            } catch {
                DispatchQueue.main.async {
                    self.isCapturing = false
                    self.endScreenFlash()
                    self.errorMessage = "Couldn't adjust zoom for this photo. Please try again."
                }
                return
            }
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
            self.captureDescriptions[settings.uniqueID] = captureDescription
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
        guard !isCapturing, !analysisBusy else { return }
        importedAnalysisImage = image
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
                    // Close the input gap before the shared analysis state reaches
                    // the hosting controller; the session itself stays live.
                    self.isAnalyzing = true
                    self.onCaptured(photo)
                }
            } catch {
                DispatchQueue.main.async {
                    self.isCapturing = false
                    self.importedAnalysisImage = nil
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
            queue.async { self.captureDescriptions.removeValue(forKey: photo.resolvedSettings.uniqueID) }
            DispatchQueue.main.async {
                self.isCapturing = false
                self.errorMessage = "The photo couldn't be captured. Please try again."
            }
            return
        }
        queue.async {
            let description = self.captureDescriptions.removeValue(forKey: photo.resolvedSettings.uniqueID) ?? "Camera"
            self.persist(image, lens: description)
        }
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
    private var action: Color { Color(hex: dark ? 0xAED7BA : 0x245C45) }
    private var ready: Bool { model.availability == .ready && !model.isCapturing && !model.analysisBusy }

    var body: some View {
        VStack(spacing: 0) {
            preview
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Button {
                CameraFeedback.light()
                pickingPhoto = true
            } label: {
                Label("Choose an existing photo", systemImage: "photo.on.rectangle")
                    .font(.subheadline.weight(.medium))
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .contentShape(Rectangle())
            }
            .foregroundStyle(action)
            .disabled(model.isCapturing || model.analysisBusy)
            .accessibilityIdentifier("camera.import")
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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

    private var preview: some View {
        ZStack {
            Color(hex: 0x121614)
            if model.availability == .ready || model.availability == .preparing {
                CameraPreview(model: model)
                    .overlay {
                        if model.availability == .preparing && !model.analysisBusy && model.importedAnalysisImage == nil {
                            ProgressView().tint(.white)
                                .accessibilityLabel("Opening camera")
                        }
                    }
            }

            analysisMedia

            VStack {
                LinearGradient(colors: [.black.opacity(0.48), .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: 100)
                Spacer(minLength: 0)
                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 180)
            }
            .allowsHitTesting(false)

            if model.availability != .ready && model.availability != .preparing && !model.analysisBusy && model.importedAnalysisImage == nil {
                unavailableContent
                    .padding(.horizontal, 24)
                    .padding(.top, 56)
                    .padding(.bottom, 148)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            VStack(spacing: 0) {
                topControls
                Spacer(minLength: 8)
                if model.analysisBusy {
                    Text(model.scanComplete ? "Scan complete" : "Analyzing your scan…")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color(hex: 0xC8E8D7))
                        .shadow(color: .black.opacity(0.8), radius: 4)
                        .padding(.bottom, 14)
                        .accessibilityIdentifier("camera.analysisStatus")
                } else if model.showingZoom {
                    Text(String(format: "%.1f×", Double(model.zoom.factor)))
                        .font(.system(size: 14, weight: .medium, design: .rounded))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(.black.opacity(0.4), in: Capsule())
                        .padding(.bottom, 12)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                shutterControls
            }
            .padding(16)
        }
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Camera preview")
        .accessibilityIdentifier("camera.preview")
    }

    /// Image and instruments use the same aspect-fit rectangle. The live preview
    /// already fills the viewport with an aspect-fill crop, so it uses all bounds.
    private var analysisMedia: some View {
        GeometryReader { geometry in
            let bounds = CGRect(origin: .zero, size: geometry.size)
            let imageRect = model.importedAnalysisImage.map {
                AVMakeRect(aspectRatio: $0.size, insideRect: bounds)
            } ?? bounds
            ZStack(alignment: .topLeading) {
                if let image = model.importedAnalysisImage {
                    Color(hex: 0x121614)
                    Image(uiImage: image)
                        .resizable()
                        .frame(width: imageRect.width, height: imageRect.height)
                        .offset(x: imageRect.minX, y: imageRect.minY)
                        .accessibilityLabel("Selected photo")
                }
                if model.scanActive {
                    CameraScanOverlay(reduceMotion: model.reduceScanMotion, completing: model.scanComplete)
                        .frame(width: imageRect.width, height: imageRect.height)
                        .clipped()
                        .offset(x: imageRect.minX, y: imageRect.minY)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(model.scanComplete ? "Scan complete" : "Scan in progress")
                        .accessibilityIdentifier("camera.scanOverlay")
                }
            }
            .frame(width: bounds.width, height: bounds.height, alignment: .topLeading)
        }
        .allowsHitTesting(false)
    }

    private var topControls: some View {
        HStack {
            Button {
                CameraFeedback.light()
                close()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(.black.opacity(0.28), in: Circle())
            }
            .disabled(model.scanComplete)
            .accessibilityLabel("Close camera")
            .accessibilityIdentifier("camera.close")
            Spacer()
            if model.lenses.count > 1 {
                Menu {
                    ForEach(model.lenses) { lens in
                        Button {
                            guard ready else { return }
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
            .opacity(ready ? 1 : 0.35)
            .accessibilityLabel(model.analysisBusy ? "Analyzing scan" : (model.isCapturing ? "Saving photo" : "Take photo"))
            .accessibilityIdentifier("camera.capture")

            HStack {
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
                Spacer()
                flashControl
            }
        }
        .frame(height: 80)
        .frame(maxWidth: 360)
        .frame(maxWidth: .infinity)
    }

    private var flashControl: some View {
        Menu {
            ForEach(model.availableFlashModes, id: \.self) { mode in
                Button {
                    guard ready else { return }
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

}

/// A quiet scanning instrument drawn in the viewfinder, never over the result.
private struct CameraScanOverlay: View {
    let reduceMotion: Bool
    let completing: Bool
    @State private var started = Date()
    @State private var completedAt: Date?
    @State private var completionOrigin = 0.0

    private func loopingSweep(at date: Date) -> Double {
        let phase = max(0, date.timeIntervalSince(started)).truncatingRemainder(dividingBy: 2.8) / 2.8
        return (1 - cos(phase * 2 * .pi)) / 2
    }

    private func sweep(at date: Date) -> Double {
        guard let completedAt else { return loopingSweep(at: date) }
        // Finish within the outgoing camera fade; this never gates navigation.
        let fraction = min(1, max(0, date.timeIntervalSince(completedAt) / 0.16))
        let eased = 1 - pow(1 - fraction, 3)
        return completionOrigin + (1 - completionOrigin) * eased
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
            Canvas { context, size in
                guard size.width > 0, size.height > 0 else { return }
                let tint = Color(hex: 0xB4E2CD)
                let inset = min(14, min(size.width, size.height) * 0.04)
                let frame = CGRect(origin: .zero, size: size).insetBy(dx: inset, dy: inset)
                let corner = min(24, min(size.width, size.height) * 0.13)
                var brackets = Path()
                for (x, y, dx, dy) in [
                    (frame.minX, frame.minY, 1.0, 1.0),
                    (frame.maxX, frame.minY, -1.0, 1.0),
                    (frame.minX, frame.maxY, 1.0, -1.0),
                    (frame.maxX, frame.maxY, -1.0, -1.0)
                ] {
                    brackets.move(to: CGPoint(x: x, y: y + dy * corner))
                    brackets.addLine(to: CGPoint(x: x, y: y))
                    brackets.addLine(to: CGPoint(x: x + dx * corner, y: y))
                }
                context.stroke(brackets, with: .color(tint.opacity(0.7)), style: StrokeStyle(lineWidth: 1.3, lineCap: .round))
                if !reduceMotion {
                    let y = 1 + max(0, size.height - 2) * sweep(at: timeline.date)
                    let band = CGRect(x: frame.minX + 5, y: y - 11, width: max(0, frame.width - 10), height: 22)
                    context.fill(Path(band), with: .linearGradient(
                        Gradient(colors: [.clear, tint.opacity(0.09), .clear]),
                        startPoint: CGPoint(x: 0, y: band.minY), endPoint: CGPoint(x: 0, y: band.maxY)
                    ))
                    var line = Path()
                    line.move(to: CGPoint(x: frame.minX + 5, y: y))
                    line.addLine(to: CGPoint(x: frame.maxX - 5, y: y))
                    context.stroke(line, with: .color(tint.opacity(0.65)), lineWidth: 1)
                }
            }
        }
        .onChange(of: completing, initial: true) { _, complete in
            if complete {
                let now = Date()
                completionOrigin = loopingSweep(at: now)
                completedAt = now
            } else {
                completedAt = nil
            }
        }
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
    var pinchStarted: (() -> Int?)?
    var pinchChanged: ((CGFloat, Int) -> Void)?
    var pinchEnded: ((Int) -> Void)?
    var accessibleZoomChanged: ((Bool) -> Void)?
    private var pinchGeneration: Int?
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    override init(frame: CGRect) {
        super.init(frame: frame)
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        pinch.cancelsTouchesInView = false
        addGestureRecognizer(pinch)
        isAccessibilityElement = true
        accessibilityLabel = "Camera zoom"
        accessibilityHint = "Swipe up or down to adjust zoom."
        accessibilityIdentifier = "camera.zoom"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    @objc private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        switch gesture.state {
        case .began:
            pinchGeneration = pinchStarted?()
        case .changed, .ended:
            if let pinchGeneration { pinchChanged?(gesture.scale, pinchGeneration) }
            if gesture.state == .ended {
                if let pinchGeneration { pinchEnded?(pinchGeneration) }
                pinchGeneration = nil
            }
        default:
            if let pinchGeneration { pinchEnded?(pinchGeneration) }
            pinchGeneration = nil
        }
    }

    override func accessibilityIncrement() { accessibleZoomChanged?(true) }
    override func accessibilityDecrement() { accessibleZoomChanged?(false) }

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
    @ObservedObject var model: CaptureSessionModel
    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.screenChanged = { [weak model] in model?.attachScreen($0) }
        view.pinchStarted = { [weak model] in model?.beginPinch() }
        view.pinchChanged = { [weak model] scale, generation in model?.updatePinch(scale: scale, generation: generation) }
        view.pinchEnded = { [weak model] generation in model?.endPinch(generation: generation) }
        view.accessibleZoomChanged = { [weak model] increasing in model?.adjustAccessibleZoom(increasing: increasing) }
        model.attachPreview(view.previewLayer)
        return view
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.accessibilityValue = String(format: "%.1f times, from %.1f to %.1f", Double(model.zoom.factor), Double(model.zoom.minimum), Double(model.zoom.maximum))
        uiView.accessibilityTraits = model.availability == .ready && !model.isCapturing && !model.analysisBusy ? [.adjustable] : [.notEnabled]
    }
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
