import XCTest
import UIKit
@testable import ScanFace

final class CapturePhotoStorageTests: XCTestCase {
    func testNormalizationConstrainsResolutionAndPreservesAspectRatio() {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 4000, height: 2000)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4000, height: 2000))
        }
        let normalized = CapturePhotoStorage.normalized(image)
        XCTAssertEqual(normalized.size.width, 2048)
        XCTAssertEqual(normalized.size.height, 1024)
        XCTAssertEqual(normalized.scale, 1)
        XCTAssertEqual(normalized.imageOrientation, .up)
    }

    func testStoredCaptureIsPrivateUprightJPEG() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 200, height: 300), format: format).image { context in
            UIColor.green.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 200, height: 300))
        }
        let stored = try CapturePhotoStorage.save(image)
        defer { try? FileManager.default.removeItem(at: stored.url) }
        XCTAssertTrue(stored.url.path.contains("Application Support/ScanFace/Captures/"))
        XCTAssertEqual(stored.width, 200)
        XCTAssertEqual(stored.height, 300)
        let loaded = try XCTUnwrap(UIImage(contentsOfFile: stored.url.path))
        XCTAssertEqual(loaded.imageOrientation, .up)
        XCTAssertEqual(loaded.size.width, 200)
        XCTAssertEqual(loaded.size.height, 300)
        let values = try stored.url.deletingLastPathComponent().resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    func testPortraitRotationIsBakedIntoPixels() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let landscape = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 200), format: format).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 300, height: 200))
        }
        let rotated = UIImage(cgImage: try XCTUnwrap(landscape.cgImage), scale: 1, orientation: .right)
        let normalized = CapturePhotoStorage.normalized(rotated)
        XCTAssertEqual(normalized.imageOrientation, .up)
        XCTAssertEqual(normalized.size.width, 200)
        XCTAssertEqual(normalized.size.height, 300)
        XCTAssertEqual(normalized.cgImage?.width, 200)
        XCTAssertEqual(normalized.cgImage?.height, 300)
    }
}
