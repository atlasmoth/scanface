import XCTest
import UIKit
import ScanFaceKit
@testable import ScanFace

final class PhotoReferenceStorageTests: XCTestCase {
    private let key = "scanface.snapshot.v1"
    private var snapshotURL: URL!
    private var originalSnapshot: Data?
    private var originalDefaults: Any?
    private var fixtureURLs: [URL] = []

    override func setUpWithError() throws {
        try super.setUpWithError()
        let support = try XCTUnwrap(FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first)
        snapshotURL = support.appendingPathComponent("ScanFace/State/\(key).json")
        if FileManager.default.fileExists(atPath: snapshotURL.path) {
            originalSnapshot = try Data(contentsOf: snapshotURL)
        }
        originalDefaults = UserDefaults.standard.object(forKey: key)
    }

    override func tearDownWithError() throws {
        defer {
            fixtureURLs.forEach { try? FileManager.default.removeItem(at: $0) }
            UserDefaults.standard.set(originalDefaults, forKey: key)
        }
        if let originalSnapshot {
            try originalSnapshot.write(to: snapshotURL, options: [.atomic, .completeFileProtection])
        } else if FileManager.default.fileExists(atPath: snapshotURL.path) {
            try FileManager.default.removeItem(at: snapshotURL)
        }
        try super.tearDownWithError()
    }

    func testSnapshotWritesRelativeReferencesAndReturnsLoadableCurrentPaths() throws {
        let photos = try makePhotos()
        let storage = IosPlatformKt.createPlatformServices().storage
        storage.put(key: key, value: try snapshot(paths: photos.map(\.path)))

        XCTAssertEqual(try paths(in: String(contentsOf: snapshotURL, encoding: .utf8)), photos.map(relativeReference))
        let loaded = try XCTUnwrap(storage.get(key: key))
        XCTAssertEqual(try paths(in: loaded), photos.map(\.path))
        try assertPhotosLoad(in: loaded)
    }

    func testLegacyContainerPathsResolveBeforeNextSaveWithoutDeletingOtherPhotos() throws {
        let photos = try makePhotos()
        let oldPaths = photos.map(legacyReference)
        let legacySnapshot = try snapshot(paths: oldPaths)
        try writeSnapshotDirectly(legacySnapshot)
        let services = IosPlatformKt.createPlatformServices()

        let loaded = try XCTUnwrap(services.storage.get(key: key))
        XCTAssertEqual(try paths(in: loaded), photos.map(\.path))
        try assertPhotosLoad(in: loaded)
        XCTAssertEqual(try String(contentsOf: snapshotURL, encoding: .utf8), legacySnapshot,
                       "Reading resolves references without rewriting the durable snapshot.")

        services.storage.put(key: key, value: loaded)
        XCTAssertEqual(try paths(in: String(contentsOf: snapshotURL, encoding: .utf8)), photos.map(relativeReference))
        services.deletePhoto(path: oldPaths[2])
        XCTAssertFalse(FileManager.default.fileExists(atPath: photos[2].path))
        XCTAssertNotNil(UIImage(contentsOfFile: photos[0].path))
        XCTAssertNotNil(UIImage(contentsOfFile: photos[1].path))
    }

    func testRelativeSnapshotResolvesAllPhotoOwnersAndCleanupIsIdempotent() throws {
        let photos = try makePhotos()
        try writeSnapshotDirectly(snapshot(paths: photos.map(relativeReference)))
        let services = IosPlatformKt.createPlatformServices()

        let loaded = try XCTUnwrap(services.storage.get(key: key))
        XCTAssertEqual(try paths(in: loaded), photos.map(\.path))
        try assertPhotosLoad(in: loaded)
        let unrelated = FileManager.default.temporaryDirectory.appendingPathComponent(photos[2].lastPathComponent)
        try Data("unrelated file with the same name".utf8).write(to: unrelated)
        fixtureURLs.append(unrelated)

        services.deletePhoto(path: relativeReference(photos[2]))
        services.deletePhoto(path: relativeReference(photos[2]))
        XCTAssertFalse(FileManager.default.fileExists(atPath: photos[2].path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
        XCTAssertNotNil(UIImage(contentsOfFile: photos[0].path))
        XCTAssertNotNil(UIImage(contentsOfFile: photos[1].path))
    }

    func testLegacyDefaultsMigrationWritesRelativeAndReturnsCurrentPaths() throws {
        let photos = try makePhotos()
        if FileManager.default.fileExists(atPath: snapshotURL.path) {
            try FileManager.default.removeItem(at: snapshotURL)
        }
        UserDefaults.standard.set(try snapshot(paths: photos.map(legacyReference)), forKey: key)

        let loaded = try XCTUnwrap(IosPlatformKt.createPlatformServices().storage.get(key: key))
        XCTAssertEqual(try paths(in: loaded), photos.map(\.path))
        try assertPhotosLoad(in: loaded)
        XCTAssertNil(UserDefaults.standard.object(forKey: key))
        XCTAssertEqual(try paths(in: String(contentsOf: snapshotURL, encoding: .utf8)), photos.map(relativeReference))
    }

    func testMissingPhotoKeepsMetadataAndCanBeCleanedUp() throws {
        let photos = try makePhotos()
        try FileManager.default.removeItem(at: photos[2])
        try writeSnapshotDirectly(snapshot(paths: photos.map(relativeReference)))
        let services = IosPlatformKt.createPlatformServices()

        let loaded = try XCTUnwrap(services.storage.get(key: key))
        XCTAssertEqual(try paths(in: loaded), photos.map(\.path))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(loaded.utf8)) as? [String: Any])
        let records = try XCTUnwrap(object["records"] as? [[String: Any]])
        XCTAssertEqual(records.first?["id"] as? String, "saved-photo")
        XCTAssertNil(UIImage(contentsOfFile: photos[2].path))
        services.deletePhoto(path: relativeReference(photos[2]))
        XCTAssertNotNil(UIImage(contentsOfFile: photos[0].path))
    }

    private func makePhotos() throws -> [URL] {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 120, height: 160), format: format).image { context in
            UIColor.systemPurple.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 120, height: 160))
        }
        return try (0..<3).map { _ in
            let photo = try CapturePhotoStorage.save(image)
            fixtureURLs.append(photo.url)
            return photo.url
        }
    }

    private func relativeReference(_ url: URL) -> String { "captures/" + url.lastPathComponent }

    private func legacyReference(_ url: URL) -> String {
        "/private/var/mobile/Containers/Data/Application/00000000-1111-2222-3333-444444444444/Library/Application Support/ScanFace/Captures/" + url.lastPathComponent
    }

    private func snapshot(paths: [String]) throws -> String {
        let value: [String: Any] = [
            "version": 1,
            "records": [["id": "saved-photo", "createdAtMillis": 1_700_000_000_000,
                          "photoPath": paths[0], "width": 120, "height": 160]],
            "pending": ["id": "pending-photo", "createdAtMillis": 1_700_000_000_001,
                        "photoPath": paths[1], "width": 120, "height": 160],
            "cleanupPaths": [paths[2]],
            "theme": "DARK",
        ]
        return String(decoding: try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), as: UTF8.self)
    }

    private func paths(in snapshot: String) throws -> [String] {
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(snapshot.utf8)) as? [String: Any])
        let records = try XCTUnwrap(object["records"] as? [[String: Any]])
        let pending = try XCTUnwrap(object["pending"] as? [String: Any])
        let cleanup = try XCTUnwrap(object["cleanupPaths"] as? [String])
        return [try XCTUnwrap(records.first?["photoPath"] as? String),
                try XCTUnwrap(pending["photoPath"] as? String), try XCTUnwrap(cleanup.first)]
    }

    private func assertPhotosLoad(in snapshot: String) throws {
        for path in try paths(in: snapshot) {
            let image = try XCTUnwrap(UIImage(contentsOfFile: path))
            XCTAssertEqual(image.imageOrientation, .up)
            XCTAssertEqual(image.size, CGSize(width: 120, height: 160))
        }
    }

    private func writeSnapshotDirectly(_ value: String) throws {
        try FileManager.default.createDirectory(at: snapshotURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(value.utf8).write(to: snapshotURL, options: [.atomic, .completeFileProtection])
    }
}
