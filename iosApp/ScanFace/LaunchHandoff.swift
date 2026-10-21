import UIKit

/// Only a new process receives the handoff; scene recreation and resume do not.
final class LaunchHandoffGate {
    static let process = LaunchHandoffGate()
    private var claimed = false

    func claim() -> Bool {
        guard !claimed else { return false }
        claimed = true
        return true
    }
}

enum LaunchAppearance: String {
    case system = "SYSTEM", light = "LIGHT", dark = "DARK"

    static func decode(_ data: Data?) -> LaunchAppearance {
        guard let data, let snapshot = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let theme = snapshot["theme"] as? String else { return .system }
        return LaunchAppearance(rawValue: theme) ?? .system
    }

    static func saved() -> LaunchAppearance {
        // Read only the durable theme before constructing Kotlin/Compose. The
        // system launch screen itself necessarily follows the system appearance.
        guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return .system }
        let url = support.appendingPathComponent("ScanFace/State/scanface.snapshot.v1.json")
        return decode(try? Data(contentsOf: url))
    }

    func isDark(systemDark: Bool) -> Bool {
        switch self {
        case .system: return systemDark
        case .light: return false
        case .dark: return true
        }
    }
}

final class LaunchHandoffView: UIView {
    private let mark = LaunchMarkView()

    init(dark: Bool) {
        super.init(frame: .zero)
        accessibilityElementsHidden = true
        addSubview(mark)
        mark.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            mark.widthAnchor.constraint(equalToConstant: 112),
            mark.heightAnchor.constraint(equalToConstant: 112),
            mark.centerXAnchor.constraint(equalTo: centerXAnchor),
            mark.centerYAnchor.constraint(equalTo: centerYAnchor)
        ])
        apply(dark: dark)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    static func canvas(dark: Bool) -> UIColor {
        dark ? UIColor(red: 16 / 255, green: 26 / 255, blue: 22 / 255, alpha: 1) :
            UIColor(red: 246 / 255, green: 247 / 255, blue: 242 / 255, alpha: 1)
    }

    func apply(dark: Bool) {
        backgroundColor = Self.canvas(dark: dark)
        mark.apply(ink: dark ? UIColor(red: 174 / 255, green: 215 / 255, blue: 186 / 255, alpha: 1) :
            UIColor(red: 36 / 255, green: 92 / 255, blue: 69 / 255, alpha: 1))
    }

    func animateIfAllowed() {
        guard !UIAccessibility.isReduceMotionEnabled, !UIAccessibility.isVoiceOverRunning else { return }
        mark.sweep()
    }

    func settle() { mark.settle() }
}

/// The same 92-unit paths as the static launch asset and shared ScanMark.
private final class LaunchMarkView: UIView {
    private let corners = CAShapeLayer()
    private let face = CAShapeLayer()
    private let scan = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        for shape in [corners, face, scan] {
            shape.fillColor = UIColor.clear.cgColor
            shape.lineCap = .round
            shape.lineJoin = .round
            layer.addSublayer(shape)
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func apply(ink: UIColor) {
        corners.strokeColor = ink.withAlphaComponent(0.55).cgColor
        face.strokeColor = ink.cgColor
        scan.strokeColor = ink.withAlphaComponent(0.35).cgColor
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = min(bounds.width, bounds.height) / 92
        var transform = CGAffineTransform(scaleX: scale, y: scale)
        let path = CGMutablePath()
        path.move(to: CGPoint(x: 22, y: 4)); path.addLine(to: CGPoint(x: 12, y: 4))
        path.addQuadCurve(to: CGPoint(x: 4, y: 12), control: CGPoint(x: 4, y: 4)); path.addLine(to: CGPoint(x: 4, y: 22))
        path.move(to: CGPoint(x: 70, y: 4)); path.addLine(to: CGPoint(x: 80, y: 4))
        path.addQuadCurve(to: CGPoint(x: 88, y: 12), control: CGPoint(x: 88, y: 4)); path.addLine(to: CGPoint(x: 88, y: 22))
        path.move(to: CGPoint(x: 4, y: 70)); path.addLine(to: CGPoint(x: 4, y: 80))
        path.addQuadCurve(to: CGPoint(x: 12, y: 88), control: CGPoint(x: 4, y: 88)); path.addLine(to: CGPoint(x: 22, y: 88))
        path.move(to: CGPoint(x: 88, y: 70)); path.addLine(to: CGPoint(x: 88, y: 80))
        path.addQuadCurve(to: CGPoint(x: 80, y: 88), control: CGPoint(x: 88, y: 88)); path.addLine(to: CGPoint(x: 70, y: 88))
        let line = CGMutablePath()
        line.move(to: CGPoint(x: 17, y: 46)); line.addLine(to: CGPoint(x: 75, y: 46))
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for shape in [corners, face, scan] { shape.lineWidth = 1.6 * scale }
        corners.path = path.copy(using: &transform)
        face.path = CGPath(ellipseIn: CGRect(x: 28, y: 20, width: 36, height: 49), transform: &transform)
        scan.path = line.copy(using: &transform)
        CATransaction.commit()
    }

    func sweep() {
        let movement = CAKeyframeAnimation(keyPath: "transform.translation.y")
        movement.values = [0, 23 * bounds.height / 92, 23 * bounds.height / 92, 0, 0]
        movement.keyTimes = [0, 0.70, 0.78, 0.80, 1]
        let opacity = CAKeyframeAnimation(keyPath: "opacity")
        opacity.values = [1, 1, 0, 0, 1]
        opacity.keyTimes = movement.keyTimes
        let sweep = CAAnimationGroup()
        sweep.animations = [movement, opacity]
        sweep.duration = 0.55
        sweep.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        scan.add(sweep, forKey: "launch.sweep")
        let settle = CAKeyframeAnimation(keyPath: "opacity")
        settle.values = [1, 0.8, 1]
        settle.duration = 0.55
        corners.add(settle, forKey: "launch.settle")
    }

    func settle() {
        scan.removeAllAnimations()
        corners.removeAllAnimations()
    }
}
