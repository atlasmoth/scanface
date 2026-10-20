import XCTest
import ScanFaceKit

final class SnapshotStorageTests: XCTestCase {
    func testSnapshotWriteIsImmediatelyReadableAndExcludedFromBackup() throws {
        let storage = IosPlatformKt.createPlatformServices().storage
        let key = "test.snapshot." + UUID().uuidString
        defer { storage.remove(key: key) }
        let payload = "{\"schemaVersion\":1,\"records\":[]}"
        storage.put(key: key, value: payload)
        XCTAssertEqual(storage.get(key: key), payload)
        let support = try XCTUnwrap(FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first)
        let folder = support.appendingPathComponent("ScanFace/State", isDirectory: true)
        XCTAssertEqual(try String(contentsOf: folder.appendingPathComponent(key + ".json"), encoding: .utf8), payload)
        XCTAssertEqual(try folder.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    func testLegacyDefaultsMigrateToSnapshotFile() {
        let storage = IosPlatformKt.createPlatformServices().storage
        let key = "test.legacy." + UUID().uuidString
        defer { storage.remove(key: key) }
        UserDefaults.standard.set("legacy snapshot", forKey: key)
        XCTAssertEqual(storage.get(key: key), "legacy snapshot")
        XCTAssertNil(UserDefaults.standard.string(forKey: key))
        XCTAssertEqual(storage.get(key: key), "legacy snapshot")
    }
}
