import SwiftUI
import UIKit
import ScanFaceKit

@main
struct ScanFaceApp: App {
    init() {
        #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--uitesting-reset") {
            IosPlatformKt.createPlatformServices().storage.remove(key: "scanface.snapshot.v1")
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            if let captures = support?.appendingPathComponent("ScanFace/Captures", isDirectory: true) {
                try? FileManager.default.removeItem(at: captures)
            }
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            SharedApplication()
                .ignoresSafeArea()
        }
    }
}

private struct SharedApplication: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let shared = IosPlatformKt.MainViewController(cameraFactory: NativeCameraFactory(), analyzer: CoreMLFaceAnalyzer())
        return ComposeHostController(content: shared)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Keeps explicit app theme changes independent of system traits and forwards
/// asymmetric safe areas to Compose on iPad, resized windows, and iPhone Duo.
private final class ComposeHostController: UIViewController {
    private let content: UIViewController
    private var dark = false
    private var themeObserver: NSObjectProtocol?
    private var lastNavigationRect: CGRect?

    init(content: UIViewController) {
        self.content = content
        super.init(nibName: nil, bundle: nil)
        themeObserver = NotificationCenter.default.addObserver(
            forName: NSNotification.Name("ScanFaceThemeDidChange"),
            object: nil, queue: .main
        ) { [weak self] notification in
            guard let self, let dark = notification.userInfo?["dark"] as? Bool else { return }
            self.dark = dark
            self.setNeedsStatusBarAppearanceUpdate()
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
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
    }
}
