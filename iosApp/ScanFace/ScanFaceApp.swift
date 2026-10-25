import SwiftUI
import UIKit
import ScanFaceKit

@main
struct ScanFaceApp: App {
    var body: some Scene {
        WindowGroup {
            SharedApplication()
                .ignoresSafeArea()
                .onOpenURL { IncomingImageReceiver.receive($0) }
        }
    }
}

private struct SharedApplication: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ComposeHostController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Keeps explicit app theme changes independent of system traits and forwards
/// asymmetric safe areas to Compose on iPad, resized windows, and iPhone Duo.
private final class ComposeHostController: UIViewController {
    private var content: UIViewController!
    private var dark = false
    private var receivedTheme = false
    private var themeObserver: NSObjectProtocol?
    private var backgroundObserver: NSObjectProtocol?
    private var motionObserver: NSObjectProtocol?
    private var lastNavigationRect: CGRect?
    private let initialAppearance = LaunchAppearance.saved()
    private var launchHandoff: LaunchHandoffView?
    private var launchStarted = false
    private let presentsLaunch = LaunchHandoffGate.process.claim()
    private var launchCompleted = false
    private var launchTimeout: DispatchWorkItem?

    init() {
        super.init(nibName: nil, bundle: nil)
        themeObserver = NotificationCenter.default.addObserver(
            forName: NSNotification.Name("ScanFaceThemeDidChange"),
            object: nil, queue: .main
        ) { [weak self] notification in
            guard let self, let dark = notification.userInfo?["dark"] as? Bool else { return }
            self.dark = dark
            self.receivedTheme = true
            self.launchHandoff?.apply(dark: dark)
            self.setNeedsStatusBarAppearanceUpdate()
        }
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.finishLaunch(animated: false) }
        motionObserver = NotificationCenter.default.addObserver(
            forName: UIAccessibility.reduceMotionStatusDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, UIAccessibility.isReduceMotionEnabled else { return }
            self.launchHandoff?.settle()
            if self.launchCompleted { self.finishLaunch(animated: false) }
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        dark = initialAppearance.isDark(systemDark: traitCollection.userInterfaceStyle == .dark)
        view.backgroundColor = LaunchHandoffView.canvas(dark: dark)
        content = IosPlatformKt.MainViewController(
            cameraFactory: NativeCameraFactory(), analyzer: CoreMLFaceAnalyzer(),
            onReady: { [weak self] in self?.finishLaunch(animated: true) }
        )
        addChild(content)
        view.addSubview(content.view)
        content.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            content.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.view.topAnchor.constraint(equalTo: view.topAnchor),
            content.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        content.didMove(toParent: self)
        if presentsLaunch && !launchCompleted {
            let handoff = LaunchHandoffView(dark: dark)
            launchHandoff = handoff
            view.addSubview(handoff)
            handoff.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                handoff.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                handoff.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                handoff.topAnchor.constraint(equalTo: view.topAnchor),
                handoff.bottomAnchor.constraint(equalTo: view.bottomAnchor)
            ])
            content.view.accessibilityElementsHidden = true
            // A missed renderer callback must never leave an invisible input or
            // accessibility barrier. Normal launches remove this on the first draw.
            let timeout = DispatchWorkItem { [weak self] in self?.finishLaunch(animated: false) }
            launchTimeout = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: timeout)
        }
        if #available(iOS 27.1, *) {
            registerForTraitChanges(UITraitCollection.systemTraitsAffectingVerticalBarEdge) {
                (controller: ComposeHostController, _: UITraitCollection) in
                controller.view.setNeedsLayout()
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateNavigationRegion()
        if !launchStarted, view.window != nil, let launchHandoff {
            launchStarted = true
            // Match the system storyboard’s window center before animating.
            launchHandoff.layoutIfNeeded()
            launchHandoff.animateIfAllowed()
        }
    }

    override func viewIsAppearing(_ animated: Bool) {
        super.viewIsAppearing(animated)
        if !receivedTheme {
            dark = initialAppearance.isDark(systemDark: traitCollection.userInterfaceStyle == .dark)
            view.backgroundColor = LaunchHandoffView.canvas(dark: dark)
            launchHandoff?.apply(dark: dark)
            setNeedsStatusBarAppearanceUpdate()
        }
    }

    private func finishLaunch(animated: Bool) {
        if launchCompleted {
            if !animated {
                launchHandoff?.layer.removeAllAnimations()
                launchHandoff?.removeFromSuperview()
                launchHandoff = nil
            }
            return
        }
        launchCompleted = true
        launchTimeout?.cancel()
        launchTimeout = nil
        content?.view.accessibilityElementsHidden = false
        guard let handoff = launchHandoff else { return }
        handoff.isUserInteractionEnabled = false
        let remove = { [weak self] in
            handoff.removeFromSuperview()
            if self?.launchHandoff === handoff { self?.launchHandoff = nil }
        }
        if animated && !UIAccessibility.isReduceMotionEnabled && !UIAccessibility.isVoiceOverRunning {
            UIView.animate(withDuration: 0.18, delay: 0, options: [.beginFromCurrentState, .curveEaseOut], animations: {
                handoff.alpha = 0
            }, completion: { _ in remove() })
        } else {
            remove()
        }
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        view.setNeedsLayout()
    }

    /// The system bar region uses the existing edge gutter while excluding the
    /// camera, status area, screen corners, and other reserved system regions.
    private func updateNavigationRegion() {
        guard #available(iOS 27.1, *), content.view.window != nil else {
            publishNavigationRegion(nil)
            return
        }
        let host = content.view!
        let rtl = host.effectiveUserInterfaceLayoutDirection == .rightToLeft
        let edge: UIRectEdge
        switch host.traitCollection.verticalBarEdge {
        case .leading: edge = rtl ? .right : .left
        case .trailing: edge = rtl ? .left : .right
        default:
            publishNavigationRegion(nil)
            return
        }
        let inset = edge == .right ? host.safeAreaInsets.right : host.safeAreaInsets.left
        // During display transitions, the trait can arrive before the matching
        // safe area. Never create a new gutter inside otherwise usable content.
        guard inset >= 44 else {
            publishNavigationRegion(nil)
            return
        }
        // Use the space already reserved for vertical controls, not a model-specific width.
        let barInsets = host.edgeInsets(for: .bar(onEdge: edge, extent: inset))
        let rect = host.bounds.inset(by: barInsets).intersection(host.bounds)
        guard !rect.isNull, !rect.isInfinite, rect.width >= 44, rect.height >= 176,
              [rect.minX, rect.minY, rect.width, rect.height].allSatisfy(\.isFinite) else {
            publishNavigationRegion(nil)
            return
        }
        publishNavigationRegion(rect)
    }

    private func publishNavigationRegion(_ rect: CGRect?) {
        guard rect != lastNavigationRect else { return }
        lastNavigationRect = rect
        IosPlatformKt.updateNavigationRegion(region: rect.map {
            NavigationRegion(x: Float($0.minX), y: Float($0.minY), width: Float($0.width), height: Float($0.height))
        })
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { dark ? .lightContent : .darkContent }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .all }

    deinit {
        IosPlatformKt.updateNavigationRegion(region: nil)
        if let themeObserver { NotificationCenter.default.removeObserver(themeObserver) }
        if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) }
        if let motionObserver { NotificationCenter.default.removeObserver(motionObserver) }
        launchTimeout?.cancel()
    }
}
