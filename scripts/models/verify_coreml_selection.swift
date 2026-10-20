#!/usr/bin/env swift
// Build-time model/profile verification; no Python environment is needed.
import CryptoKit
import Foundation

enum VerificationError: Error { case invalid(String) }

func require(_ condition: Bool, _ message: String) throws {
    if !condition { throw VerificationError.invalid(message) }
}

func json(_ url: URL) throws -> [String: Any] {
    guard let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] else {
        throw VerificationError.invalid("Expected a JSON object at \(url.path)")
    }
    return object
}

func digest(_ url: URL) throws -> String {
    let stream = try FileHandle(forReadingFrom: url)
    defer { try? stream.close() }
    var hash = SHA256()
    while let data = try stream.read(upToCount: 1024 * 1024), !data.isEmpty {
        hash.update(data: data)
    }
    return hash.finalize().map { String(format: "%02x", $0) }.joined()
}

do {
    try require(CommandLine.arguments.count == 2, "Pass the repository directory")
    let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true).standardizedFileURL
    let lock = try json(root.appendingPathComponent("models/selected-models.json"))
    let directory = root.appendingPathComponent("iosApp/ScanFace/Models")
    let metadata = try json(directory.appendingPathComponent("conversion.json"))
    guard let runtimes = lock["runtimes"] as? [String: [String: Any]],
          let selected = runtimes["coreml"],
          let files = selected["files"] as? [String: [String: Any]], !files.isEmpty else {
        throw VerificationError.invalid("Selection lock is missing Core ML package hashes")
    }
    let identifier = "fahr-faceage-1fbb84e-coreml-int8w-native-v1"
    let sourceHash = "980ad2f134c5d82e9aca39a738f88a5b10aaf15ca9a5462bf95599be023cdbba"
    try require(selected["model_id"] as? String == identifier && metadata["model_id"] as? String == identifier,
        "Model selection does not match this app's quantized profile")
    try require(selected["candidate"] as? String == "int8-float32" && metadata["candidate"] as? String == "int8-float32",
        "Expected the reviewed int8-weight/float32-compute Core ML model")
    try require(lock["source_checkpoint_sha256"] as? String == sourceHash && metadata["source_checkpoint_sha256"] as? String == sourceHash,
        "Unexpected original checkpoint")
    try require(lock["license"] as? String == "CC-BY-NC-SA-4.0" && metadata["license"] as? String == "CC-BY-NC-SA-4.0",
        "Missing model license attribution")
    try require((metadata["files"] as? NSDictionary)?.isEqual(to: files) == true,
        "Bundled package manifest differs from the reviewed selection")
    try require((metadata["predictions_years"] as? NSDictionary)?.isEqual(to: selected["predictions_years"] as? [String: Any] ?? [:]) == true,
        "Bundled regression predictions differ from the reviewed selection")
    let package = directory.appendingPathComponent("FAHRFaceAge.mlpackage", isDirectory: true)
    let keys: [URLResourceKey] = [.isRegularFileKey, .isSymbolicLinkKey]
    guard let enumerator = FileManager.default.enumerator(at: package, includingPropertiesForKeys: keys) else {
        throw VerificationError.invalid("Core ML package is missing")
    }
    var actual = Set<String>()
    for case let url as URL in enumerator {
        let values = try url.resourceValues(forKeys: Set(keys))
        try require(values.isSymbolicLink != true, "Model packages must not contain symlinks")
        if values.isRegularFile == true {
            let relative = String(url.path.dropFirst(package.path.count + 1))
            actual.insert(relative)
            guard let expected = files[relative], let bytes = expected["bytes"] as? NSNumber,
                  let expectedHash = expected["sha256"] as? String else {
                throw VerificationError.invalid("Unrecorded model file: \(relative)")
            }
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            let actualHash = try digest(url)
            try require((attributes[.size] as? NSNumber)?.int64Value == bytes.int64Value && actualHash == expectedHash,
                "Model size/hash mismatch: \(relative)")
        }
    }
    try require(actual == Set(files.keys), "Core ML package has missing files")
    print("Verified selected Core ML model: \(identifier)")
} catch {
    let message = "error: Core ML selection verification failed: \(error). Generate the reviewed candidates, then run conda run -n dl python scripts/models/install_quantized_models.py --runtime coreml\n"
    FileHandle.standardError.write(Data(message.utf8))
    exit(1)
}
