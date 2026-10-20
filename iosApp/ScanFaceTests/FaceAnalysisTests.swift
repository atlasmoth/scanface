import CoreGraphics
import CoreML
import ScanFaceKit
import UIKit
import XCTest
@testable import ScanFace

final class FaceAnalysisTests: XCTestCase {
    private func provisionedPortrait() throws -> UIImage {
        guard let url = Bundle(for: Self.self).url(forResource: "author-portrait", withExtension: "jpg") else {
            throw XCTSkip("Run scripts/models/prepare_models.py with --test-portraits and regenerate the Xcode project to provision the optional, non-redistributed author portrait.")
        }
        return try XCTUnwrap(UIImage(contentsOfFile: url.path))
    }

    func testFreshPortraitRunsOfflineVisionAndCoreMLPipeline() throws {
        let stored = try CapturePhotoStorage.save(provisionedPortrait())
        defer { try? FileManager.default.removeItem(at: stored.url) }
        let completed = expectation(description: "On-device analysis completed")
        let analyzer = CoreMLFaceAnalyzer()
        let operation = analyzer.startAnalysis(photo: CapturedPhoto(path: stored.url.path,
            width: Int32(stored.width), height: Int32(stored.height), lens: "Test import",
            mirrored: false, capturedAtMillis: 0)) { result in
            XCTAssertTrue(Thread.isMainThread)
            XCTAssertEqual(result.status, .complete, result.message ?? "No analysis message")
            XCTAssertEqual(result.modelId, CoreMLFaceAnalyzer.modelID)
            XCTAssertNotNil(result.age)
            if let age = result.age?.doubleValue {
                XCTAssertTrue(age.isFinite && (0...130).contains(age))
            }
            completed.fulfill()
        }
        wait(for: [completed], timeout: 30)
        withExtendedLifetime(operation) {}
    }

    func testTwoFacesAreRejectedBeforeAgeInference() throws {
        let portrait = try provisionedPortrait()
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: portrait.size.width * 2,
            height: portrait.size.height), format: format).image { _ in
            portrait.draw(at: .zero)
            portrait.draw(at: CGPoint(x: portrait.size.width, y: 0))
        }
        let stored = try CapturePhotoStorage.save(image)
        defer { try? FileManager.default.removeItem(at: stored.url) }
        XCTAssertThrowsError(try FaceImagePreprocessor.prepare(path: stored.url.path, operation: NativeAnalysisOperation())) {
            XCTAssertEqual($0 as? FaceAnalysisFailure, .multipleFaces, String(describing: $0))
        }
    }

    func testGuardedEyeFallbackRequiresMatchingFaceAndFindsUsableEyes() throws {
        let image = try XCTUnwrap(provisionedPortrait().cgImage)
        let vision = try FaceImagePreprocessor.detect(in: image, operation: NativeAnalysisOperation())
        let recovered = try FaceImagePreprocessor.fallbackEyes(in: image, matching: vision.bounds)
        XCTAssertTrue(recovered.usedCoreImageFallback)
        XCTAssertGreaterThan(hypot(recovered.rightEye.x - recovered.leftEye.x,
            recovered.rightEye.y - recovered.leftEye.y), vision.bounds.width * 0.15)
        XCTAssertThrowsError(try FaceImagePreprocessor.fallbackEyes(in: image,
            matching: CGRect(x: 0, y: 0, width: 100, height: 100))) {
            XCTAssertEqual($0 as? FaceAnalysisFailure, .missingEyes)
        }
    }

    func testAlignmentAndPillowResizeMatchAuthorFixture() throws {
        struct Fixture: Decodable {
            let width: Int
            let height: Int
            let bounding_box: [Int]
            let eyes: [[Int]]
            let rotated_crop: [Int]
            let max_pixel_error: Int
        }
        let bundle = Bundle(for: Self.self)
        let metadata = try XCTUnwrap(bundle.url(forResource: "alignment", withExtension: "json"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: metadata))
        let inputURL = try XCTUnwrap(bundle.url(forResource: "alignment-input", withExtension: "rgb"))
        let expectedURL = try XCTUnwrap(bundle.url(forResource: "alignment-expected", withExtension: "rgb"))
        let input = [UInt8](try Data(contentsOf: inputURL))
        let expected = [UInt8](try Data(contentsOf: expectedURL))
        var rgba = [UInt8]()
        for index in stride(from: 0, to: input.count, by: 3) {
            rgba.append(contentsOf: [input[index], input[index + 1], input[index + 2], 255])
        }
        let box = fixture.bounding_box
        let face = FaceGeometry(bounds: CGRect(x: box[0], y: box[1], width: box[2] - box[0], height: box[3] - box[1]),
            leftEye: CGPoint(x: fixture.eyes[0][0], y: fixture.eyes[0][1]),
            rightEye: CGPoint(x: fixture.eyes[1][0], y: fixture.eyes[1][1]))
        let (crop, _) = try FaceImagePreprocessor.rotatedCrop(face: face, width: fixture.width, height: fixture.height)
        XCTAssertEqual([Int(crop.minX), Int(crop.minY), Int(crop.maxX), Int(crop.maxY)], fixture.rotated_crop)
        let output = try FaceImagePreprocessor.alignedRGB(RGBRaster(width: fixture.width, height: fixture.height, bytes: rgba), face: face)
        XCTAssertEqual(output.count, expected.count)
        let maximumError = zip(output, expected).map { abs(Int($0.0) - Int($0.1)) }.max() ?? 0
        XCTAssertLessThanOrEqual(maximumError, fixture.max_pixel_error)
    }

    func testBundledCoreMLMatchesAuthorReferenceForGradientAndGray() throws {
        let model = CoreMLAgeModel(bundle: .main)
        var gradient = [Float]()
        for y in 0..<112 {
            for x in 0..<112 {
                gradient.append(contentsOf: [Float((3 * x + y) % 256), Float((x + 2 * y) % 256), Float((7 * x + 5 * y) % 256)])
            }
        }
        XCTAssertEqual(try model.predict(rgb: gradient), 5.346164703369141, accuracy: 0.02)
        XCTAssertEqual(try model.predict(rgb: [Float](repeating: 127, count: 112 * 112 * 3)), 62.322818756103516, accuracy: 0.02)
    }

    func testModelRejectsInvalidTensorBeforeInference() {
        let model = CoreMLAgeModel(bundle: .main)
        XCTAssertThrowsError(try model.predict(rgb: [0, 0, 0])) {
            XCTAssertEqual($0 as? FaceAnalysisFailure, .incompatibleModel)
        }
        var input = [Float](repeating: 127, count: 112 * 112 * 3)
        input[4] = .nan
        XCTAssertThrowsError(try model.predict(rgb: input)) {
            XCTAssertEqual($0 as? FaceAnalysisFailure, .incompatibleModel)
        }
    }

    func testMissingModelHasActionableFailure() {
        let model = CoreMLAgeModel(bundle: Bundle(for: Self.self))
        XCTAssertThrowsError(try model.predict(rgb: [Float](repeating: 127, count: 112 * 112 * 3))) {
            XCTAssertEqual($0 as? FaceAnalysisFailure, .modelMissing)
        }
    }

    func testBlankPhotoNeverProducesAnEstimate() throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 400)).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 400, height: 400))
        }
        let stored = try CapturePhotoStorage.save(image)
        defer { try? FileManager.default.removeItem(at: stored.url) }
        XCTAssertThrowsError(try FaceImagePreprocessor.prepare(path: stored.url.path, operation: NativeAnalysisOperation())) {
            XCTAssertEqual($0 as? FaceAnalysisFailure, .noFace, String(describing: $0))
        }
    }

    func testRasterPreservesTopLeftRGBChannelOrder() throws {
        let bytes: [UInt8] = [255, 0, 0, 255, 0, 255, 0, 255,
                             0, 0, 255, 255, 255, 255, 0, 255]
        let provider = try XCTUnwrap(CGDataProvider(data: Data(bytes) as CFData))
        let image = try XCTUnwrap(CGImage(width: 2, height: 2, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: 8, space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
        XCTAssertEqual(try RGBRaster(image: image).bytes, bytes)
    }

    func testAlignmentMovesBoxCenterWithoutExpandingRotatedBounds() throws {
        let face = FaceGeometry(bounds: CGRect(x: 30, y: 30, width: 100, height: 80),
            leftEye: CGPoint(x: 50, y: 55), rightEye: CGPoint(x: 90, y: 75))
        let (crop, angle) = try FaceImagePreprocessor.rotatedCrop(face: face, width: 200, height: 160)
        XCTAssertEqual(angle, atan2(20, 40), accuracy: 0.000001)
        XCTAssertEqual(crop, CGRect(x: 27, y: 40, width: 100, height: 80))
    }

    func testAntialiasedResizeAveragesHighFrequencyPixels() {
        let pixels: [UInt8] = [0, 0, 0, 255, 255, 255, 0, 0, 0, 255, 255, 255]
        XCTAssertEqual(FaceImagePreprocessor.bilinearRGB(pixels, width: 4, height: 1, size: 1), [128, 128, 128])
    }

    func testCancellationSuppressesLateNativeResultAndNeverBlocksMainThread() {
        let started = expectation(description: "Background worker started")
        let finished = expectation(description: "Background worker finished")
        let callback = expectation(description: "Cancelled callback is suppressed")
        callback.isInverted = true
        let gate = DispatchSemaphore(value: 0)
        let analyzer = CoreMLFaceAnalyzer { _, operation in
            XCTAssertFalse(Thread.isMainThread)
            started.fulfill()
            _ = gate.wait(timeout: .now() + 3)
            defer { finished.fulfill() }
            try operation.checkCancellation()
            return AnalysisResult(status: .complete, age: KotlinDouble(value: 40), modelId: "test", message: nil)
        }
        let task = analyzer.startAnalysis(photo: CapturedPhoto(path: "test", width: 200, height: 200,
            lens: "test", mirrored: false, capturedAtMillis: 0)) { _ in callback.fulfill() }
        wait(for: [started], timeout: 2)
        task.cancel()
        gate.signal()
        wait(for: [finished, callback], timeout: 0.3)
    }
}
