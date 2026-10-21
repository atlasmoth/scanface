import CoreGraphics
import CoreImage
import CoreML
import CoreVideo
import Foundation
import ImageIO
import Vision

private struct FaceGeometry {
    let bounds: CGRect
    let leftEye: CGPoint
    let rightEye: CGPoint
}

private struct RGBRaster {
    let width: Int
    let height: Int
    let bytes: [UInt8]

    init(image: CGImage) throws {
        let width = image.width
        let height = image.height
        guard width > 0, height > 0, width <= 4096, height <= 4096 else {
            throw FaceAnalysisFailure.unreadableImage
        }
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let succeeded = bytes.withUnsafeMutableBytes { data -> Bool in
            guard let context = CGContext(data: data.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard succeeded else { throw FaceAnalysisFailure.unreadableImage }
        self.width = width
        self.height = height
        self.bytes = bytes
    }
}

enum FaceImagePreprocessor {
    static func prepare(path: String, operation: NativeAnalysisOperation) throws -> [Float] {
        try operation.checkCancellation()
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw FaceAnalysisFailure.unreadableImage
        }
        let geometry = try detect(in: image, operation: operation)
        try operation.checkCancellation()
        return try alignedRGB(RGBRaster(image: image), face: geometry, operation: operation)
    }

    private static func detect(in image: CGImage, operation: NativeAnalysisOperation) throws -> FaceGeometry {
        let pixels = try visionPixels(image)
        let handler = VNImageRequestHandler(cvPixelBuffer: pixels, orientation: .up)
        // Landmarks revision 3 otherwise runs detector revision 2 internally,
        // which has no pitch estimate. Detect explicitly with revision 3 first.
        let rectangles = VNDetectFaceRectanglesRequest()
        rectangles.revision = VNDetectFaceRectanglesRequestRevision3
        try configure(rectangles)
        try operation.track(rectangles)
        defer { try? operation.track(nil) }
        try handler.perform([rectangles])
        try operation.checkCancellation()
        let faces = rectangles.results ?? []
        guard !faces.isEmpty else { throw FaceAnalysisFailure.noFace }
        guard faces.count == 1 else { throw FaceAnalysisFailure.multipleFaces }
        let request = VNDetectFaceLandmarksRequest()
        // Pin the supported iOS 17 revision rather than silently changing algorithms.
        request.revision = VNDetectFaceLandmarksRequestRevision3
        request.inputFaceObservations = faces
        try configure(request)
        try operation.track(request)
        try handler.perform([request])
        try operation.checkCancellation()
        let face = request.results?.first ?? faces[0]
        let box = face.boundingBox
        let width = CGFloat(image.width), height = CGFloat(image.height)
        let bounds = CGRect(x: box.minX * width, y: (1 - box.maxY) * height,
            width: box.width * width, height: box.height * height)
        guard bounds.width >= 112, bounds.height >= 112,
              box.width >= 0.08, box.height >= 0.08 else { throw FaceAnalysisFailure.tooSmall }
        guard box.minX > 0.01, box.minY > 0.01, box.maxX < 0.99, box.maxY < 0.99 else {
            throw FaceAnalysisFailure.incompleteFace
        }
        let maximumPose = 25.0 * Double.pi / 180
        guard let yaw = face.yaw?.doubleValue, let pitch = face.pitch?.doubleValue,
              yaw.isFinite, pitch.isFinite, abs(yaw) <= maximumPose, abs(pitch) <= maximumPose else {
            throw FaceAnalysisFailure.pose
        }
        func center(_ region: VNFaceLandmarkRegion2D?) -> CGPoint? {
            guard let region, region.pointCount >= 2 else { return nil }
            let points = region.normalizedPoints
            var x: CGFloat = 0, y: CGFloat = 0
            for index in 0..<region.pointCount {
                x += CGFloat(points[index].x)
                y += CGFloat(points[index].y)
            }
            let count = CGFloat(region.pointCount)
            return CGPoint(x: (box.minX + x / count * box.width) * width,
                y: (1 - box.minY - y / count * box.height) * height)
        }
        if let first = center(face.landmarks?.leftEye), let second = center(face.landmarks?.rightEye),
           let valid = validatedGeometry(bounds: bounds, firstEye: first, secondEye: second) {
            return valid
        }
        // Some runtime/accelerator combinations return unusable Vision landmarks.
        // A separate offline detector may recover eyes only after it independently
        // agrees on exactly one face at substantially the same location.
        try operation.checkCancellation()
        let recovered = try fallbackEyes(in: image, matching: bounds)
        try operation.checkCancellation()
        return recovered
    }

    private static func validatedGeometry(bounds: CGRect, firstEye: CGPoint, secondEye: CGPoint) -> FaceGeometry? {
        let eyes = [firstEye, secondEye].sorted { $0.x < $1.x }
        let distance = hypot(eyes[1].x - eyes[0].x, eyes[1].y - eyes[0].y)
        guard distance.isFinite, distance >= max(20, bounds.width * 0.15),
              distance <= bounds.width * 0.75, bounds.contains(eyes[0]), bounds.contains(eyes[1]) else { return nil }
        let angle = atan2(eyes[1].y - eyes[0].y, eyes[1].x - eyes[0].x)
        guard angle.isFinite, abs(angle) <= .pi / 6 else { return nil }
        return FaceGeometry(bounds: bounds, leftEye: eyes[0], rightEye: eyes[1])
    }

    private static func fallbackEyes(in image: CGImage, matching bounds: CGRect) throws -> FaceGeometry {
        let input = CIImage(cgImage: image)
        let context = CIContext(options: [.useSoftwareRenderer: true])
        guard let detector = CIDetector(ofType: CIDetectorTypeFace, context: context,
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]),
              let faces = detector.features(in: input, options: [CIDetectorImageOrientation: 1]) as? [CIFaceFeature],
              faces.count == 1, let face = faces.first,
              face.hasLeftEyePosition, face.hasRightEyePosition else {
            throw FaceAnalysisFailure.missingEyes
        }
        let height = CGFloat(image.height)
        let other = CGRect(x: face.bounds.minX, y: height - face.bounds.maxY,
            width: face.bounds.width, height: face.bounds.height)
        let intersection = bounds.intersection(other)
        let overlap = intersection.isNull ? 0 : intersection.width * intersection.height
        let union = bounds.width * bounds.height + other.width * other.height - overlap
        guard union > 0, overlap / union >= 0.5,
              let valid = validatedGeometry(bounds: bounds,
                firstEye: CGPoint(x: face.leftEyePosition.x, y: height - face.leftEyePosition.y),
                secondEye: CGPoint(x: face.rightEyePosition.x, y: height - face.rightEyePosition.y)) else {
            throw FaceAnalysisFailure.missingEyes
        }
        return valid
    }

    /// Avoids platform-dependent implicit RGBA conversions in Vision's cropper.
    private static func visionPixels(_ image: CGImage) throws -> CVPixelBuffer {
        var pixels: CVPixelBuffer?
        let attributes = [kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary
        guard CVPixelBufferCreate(kCFAllocatorDefault, image.width, image.height,
            kCVPixelFormatType_32BGRA, attributes, &pixels) == kCVReturnSuccess,
              let pixels, CVPixelBufferLockBaseAddress(pixels, []) == kCVReturnSuccess else {
            throw FaceAnalysisFailure.unreadableImage
        }
        defer { CVPixelBufferUnlockBaseAddress(pixels, []) }
        guard let context = CGContext(data: CVPixelBufferGetBaseAddress(pixels), width: image.width,
            height: image.height, bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pixels),
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else {
            throw FaceAnalysisFailure.unreadableImage
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return pixels
    }

    private static func configure(_ request: VNRequest) throws {
        #if targetEnvironment(simulator)
        // Simulator hardware does not expose every accelerator selected by Vision.
        for (stage, devices) in try request.supportedComputeStageDevices {
            if let cpu = devices.first(where: { if case .cpu = $0 { return true }; return false }) {
                request.setComputeDevice(cpu, for: stage)
            }
        }
        #endif
    }

    /// Author preprocessing rotates the full image about its center, then moves
    /// only the bounding-box center. It does not crop the rotated corner bounds.
    private static func rotatedCrop(face: FaceGeometry, width: Int, height: Int) throws -> (CGRect, CGFloat) {
        let eyes = [face.leftEye, face.rightEye].sorted { $0.x < $1.x }
        let angle = atan2(eyes[1].y - eyes[0].y, eyes[1].x - eyes[0].x)
        guard angle.isFinite, face.bounds.width.isFinite, face.bounds.height.isFinite else {
            throw FaceAnalysisFailure.missingEyes
        }
        let centerX = CGFloat(width) / 2, centerY = CGFloat(height) / 2
        let x = face.bounds.midX - centerX, y = face.bounds.midY - centerY
        let movedX = centerX + cos(angle) * x + sin(angle) * y
        let movedY = centerY - sin(angle) * x + cos(angle) * y
        let x1 = max(Int(movedX - face.bounds.width / 2), 0)
        let y1 = max(Int(movedY - face.bounds.height / 2), 0)
        let x2 = min(Int(movedX + face.bounds.width / 2), width)
        let y2 = min(Int(movedY + face.bounds.height / 2), height)
        guard x2 > x1, y2 > y1 else { throw FaceAnalysisFailure.incompleteFace }
        return (CGRect(x: x1, y: y1, width: x2 - x1, height: y2 - y1), angle)
    }

    private static func alignedRGB(_ image: RGBRaster, face: FaceGeometry,
        operation: NativeAnalysisOperation) throws -> [Float] {
        let (crop, angle) = try rotatedCrop(face: face, width: image.width, height: image.height)
        let width = Int(crop.width), height = Int(crop.height)
        let centerX = Double(image.width) / 2, centerY = Double(image.height) / 2
        let cosine = cos(Double(angle)), sine = sin(Double(angle))
        func fixed(_ value: Double) -> Int { Int(floor(value * 65_536 + 0.5)) }
        // Pillow's nearest affine transform quantizes its coefficients to 16.16.
        // Double-only sampling can select a neighboring pixel at an edge.
        let stepXX = fixed(cosine), stepXY = fixed(-sine)
        let stepYX = fixed(sine), stepYY = fixed(cosine)
        let baseX = fixed(centerX - cosine * centerX + sine * centerY + 0.5 * cosine - 0.5 * sine)
        let baseY = fixed(centerY - sine * centerX - cosine * centerY + 0.5 * sine + 0.5 * cosine)
        var aligned = [UInt8](repeating: 0, count: width * height * 3)
        // Sampling just the crop is equivalent to a full-size nearest-neighbor
        // rotation with black fill and no expansion, followed by that crop.
        for y in 0..<height {
            try operation.checkCancellation()
            for x in 0..<width {
                let dx = x + Int(crop.minX), dy = y + Int(crop.minY)
                let sx = (dx * stepXX + dy * stepXY + baseX) >> 16
                let sy = (dx * stepYX + dy * stepYY + baseY) >> 16
                if sx >= 0, sx < image.width, sy >= 0, sy < image.height {
                    let source = (sy * image.width + sx) * 4, destination = (y * width + x) * 3
                    aligned[destination] = image.bytes[source]
                    aligned[destination + 1] = image.bytes[source + 1]
                    aligned[destination + 2] = image.bytes[source + 2]
                }
            }
        }
        return bilinearRGB(aligned, width: width, height: height, size: 112)
    }

    private static func bilinearRGB(_ pixels: [UInt8], width: Int, height: Int, size: Int) -> [Float] {
        // Pillow BILINEAR widens its triangle when reducing an image. Its RGB
        // path uses 22-bit normalized coefficients and rounds each separable pass.
        func coefficients(source: Int) -> [(start: Int, weights: [Int])] {
            let scale = Double(source) / Double(size), support = max(1, Double(source) / Double(size))
            return (0..<size).map { destination in
                let center = (Double(destination) + 0.5) * scale
                let start = max(0, Int(center - support + 0.5))
                let end = min(source, Int(center + support + 0.5))
                let weights = (start..<end).map { max(0, 1 - abs((Double($0) - center + 0.5) / support)) }
                let sum = weights.reduce(0, +)
                return (start, weights.map { Int(0.5 + $0 / sum * Double(1 << 22)) })
            }
        }
        var horizontal = [UInt8](repeating: 0, count: size * height * 3)
        let xWeights = coefficients(source: width)
        for y in 0..<height {
            for x in 0..<size {
                let coefficient = xWeights[x]
                for channel in 0..<3 {
                    var sum = 1 << 21
                    for (offset, weight) in coefficient.weights.enumerated() {
                        sum += Int(pixels[(y * width + coefficient.start + offset) * 3 + channel]) * weight
                    }
                    horizontal[(y * size + x) * 3 + channel] = UInt8(clamping: sum >> 22)
                }
            }
        }
        let yWeights = coefficients(source: height)
        var result = [Float](repeating: 0, count: size * size * 3)
        for y in 0..<size {
            let coefficient = yWeights[y]
            for x in 0..<size {
                for channel in 0..<3 {
                    var sum = 1 << 21
                    for (offset, weight) in coefficient.weights.enumerated() {
                        sum += Int(horizontal[((coefficient.start + offset) * size + x) * 3 + channel]) * weight
                    }
                    result[(y * size + x) * 3 + channel] = Float(UInt8(clamping: sum >> 22))
                }
            }
        }
        return result
    }
}
