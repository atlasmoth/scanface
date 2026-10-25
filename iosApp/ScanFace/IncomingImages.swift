import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers
import ScanFaceKit

enum IncomingImageReceiver {
    private static let worker = DispatchQueue(label: "app.scanface.image-import", qos: .userInitiated)

    static func receive(_ url: URL) {
        guard url.isFileURL else {
            IosPlatformKt.reportIncomingImageFailure(message: ImageImportFailure.unavailable.localizedDescription)
            return
        }
        let source = ScopedImageSource(url)
        let id = UUID().uuidString
        let receivedAt = Int64(Date().timeIntervalSince1970 * 1000)
        worker.async {
            do {
                let stored = try autoreleasepool {
                    let image = try ImageImportDecoder.load(source)
                    do { return try CapturePhotoStorage.save(image) }
                    catch { throw ImageImportFailure.storage }
                }
                DispatchQueue.main.async {
                    let photo = CapturedPhoto(path: stored.url.path, width: Int32(stored.width),
                        height: Int32(stored.height), lens: "Imported photo", mirrored: false,
                        capturedAtMillis: receivedAt)
                    _ = IosPlatformKt.receiveIncomingImage(id: id, photo: photo)
                }
            } catch {
                let message = ((error as? ImageImportFailure) ?? .unavailable).localizedDescription
                DispatchQueue.main.async { IosPlatformKt.reportIncomingImageFailure(message: message) }
            }
        }
    }
}

private final class ScopedImageSource {
    let url: URL
    private let accessed: Bool

    init(_ url: URL) {
        self.url = url
        accessed = url.startAccessingSecurityScopedResource()
    }

    deinit { if accessed { url.stopAccessingSecurityScopedResource() } }
}

private enum ImageImportDecoder {
    private static let maximumBytes = 50 * 1024 * 1024

    static func load(_ input: ScopedImageSource) throws -> UIImage {
        var coordinationError: NSError?
        var result: Result<UIImage, Error>?
        withExtendedLifetime(input) {
            NSFileCoordinator().coordinate(readingItemAt: input.url, options: .withoutChanges,
                error: &coordinationError) { url in
                result = Result { try decode(read(url)) }
            }
        }
        if coordinationError != nil { throw ImageImportFailure.unavailable }
        guard let result else { throw ImageImportFailure.unavailable }
        return try result.get()
    }

    private static func read(_ url: URL) throws -> Data {
        let properties = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard properties.isRegularFile == true else { throw ImageImportFailure.unavailable }
        if let size = properties.fileSize, size > maximumBytes { throw ImageImportFailure.tooLarge }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var data = Data()
        while let chunk = try handle.read(upToCount: 1024 * 1024), !chunk.isEmpty {
            guard chunk.count <= maximumBytes - data.count else { throw ImageImportFailure.tooLarge }
            data.append(chunk)
        }
        guard !data.isEmpty else { throw ImageImportFailure.unsupported }
        return data
    }

    private static func decode(_ data: Data) throws -> UIImage {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options),
              CGImageSourceGetCount(source) > 0, let type = CGImageSourceGetType(source),
              UTType(type as String)?.conforms(to: .image) == true,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.int64Value,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.int64Value,
              width > 0, height > 0 else { throw ImageImportFailure.unsupported }
        guard width <= 100_000_000 / height else { throw ImageImportFailure.tooLarge }
        let thumbnailOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 2048,
            kCGImageSourceShouldCacheImmediately: true,
        ] as CFDictionary
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions) else {
            throw ImageImportFailure.unsupported
        }
        return UIImage(cgImage: image)
    }
}

private enum ImageImportFailure: LocalizedError {
    case unavailable, unsupported, tooLarge, storage

    var errorDescription: String? {
        switch self {
        case .unavailable: "This image could not be opened. Download it in Files, then try again."
        case .unsupported: "This file is not a supported image, or it is damaged. Choose a different photo."
        case .tooLarge: "This image is too large. Choose one under 50 MB and 100 megapixels."
        case .storage: "This photo could not be saved. Check available storage and try again."
        }
    }
}
