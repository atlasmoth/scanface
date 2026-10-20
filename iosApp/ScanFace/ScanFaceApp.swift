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
        let shared = IosPlatformKt.MainViewController(cameraFactory: NativeCameraFactory())
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
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { dark ? .lightContent : .darkContent }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .all }

    deinit {
        if let themeObserver { NotificationCenter.default.removeObserver(themeObserver) }
    }
}
