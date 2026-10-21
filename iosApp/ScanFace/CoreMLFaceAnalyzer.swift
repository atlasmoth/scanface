import CoreML
import Foundation
import ScanFaceKit
import Vision

/// Vision is an offline research substitute for the authors' RetinaFace detector.
/// Its results have not been validated as clinically equivalent to that pipeline.
final class CoreMLFaceAnalyzer: NSObject, NativeFaceAnalyzer {
    static let modelID = "fahr-faceage-1fbb84e-coreml-int8w-native-v1"
    private let queue = DispatchQueue(label: "app.scanface.face-analysis", qos: .userInitiated)
    private let model = CoreMLAgeModel()

    func startAnalysis(photo: CapturedPhoto, onResult: @escaping (AnalysisResult) -> Void) -> any NativeAnalysisTask {
        let operation = NativeAnalysisOperation()
        queue.async { [self] in
            do {
                try operation.checkCancellation()
                let pixels = try autoreleasepool {
                    try FaceImagePreprocessor.prepare(path: photo.path, operation: operation)
                }
                try operation.checkCancellation()
                let age = try model.predict(rgb: pixels)
                try operation.checkCancellation()
                let result = AnalysisResult(status: .complete, age: KotlinDouble(value: age),
                    modelId: Self.modelID,
                    message: "Research estimate using on-device Core ML. Apple Vision, with Core Image eye detection when needed, replaces the study's RetinaFace preprocessing; equivalence has not been established. Not for medical decisions.")
                operation.deliver(result, to: onResult)
            } catch is CancellationError {
                // The Kotlin continuation is already cancelled. Never revive its scan.
            } catch {
                let failure = error as? FaceAnalysisFailure
                operation.deliver(AnalysisResult(
                    status: failure == .modelMissing ? .awaitingModel : .failed,
                    age: nil, modelId: Self.modelID,
                    message: failure?.message ?? "This photo could not be analyzed on this device. Please try another well-lit, front-facing photo."
                ), to: onResult)
            }
        }
        return operation
    }
}

final class NativeAnalysisOperation: NSObject, NativeAnalysisTask {
    private let lock = NSLock()
    private var cancelled = false
    private var completed = false
    private var request: VNRequest?

    func cancel() {
        lock.lock()
        cancelled = true
        let request = request
        lock.unlock()
        request?.cancel()
    }

    func checkCancellation() throws {
        lock.lock()
        let cancelled = cancelled
        lock.unlock()
        if cancelled { throw CancellationError() }
    }

    func track(_ request: VNRequest?) throws {
        lock.lock()
        self.request = request
        let cancelled = cancelled
        lock.unlock()
        if cancelled {
            request?.cancel()
            throw CancellationError()
        }
    }

    func deliver(_ result: AnalysisResult, to callback: @escaping (AnalysisResult) -> Void) {
        DispatchQueue.main.async {
            self.lock.lock()
            let shouldDeliver = !self.cancelled && !self.completed
            self.completed = true
            self.lock.unlock()
            if shouldDeliver { callback(result) }
        }
    }
}

enum FaceAnalysisFailure: Error, Equatable {
    case unreadableImage, noFace, multipleFaces, tooSmall, incompleteFace, pose, missingEyes
    case modelMissing, incompatibleModel, invalidOutput

    var message: String {
        switch self {
        case .unreadableImage: return "The saved photo could not be read. Please capture or import it again."
        case .noFace: return "No face was found. Try a well-lit photo with your whole face visible."
        case .multipleFaces: return "More than one face was found. Use a photo of one person."
        case .tooSmall: return "Your face is too small in this photo. Move closer and try again."
        case .incompleteFace: return "Keep your whole face inside the frame, with a little space around it."
        case .pose: return "Look straight at the camera and keep your head level, then try again."
        case .missingEyes: return "Both eyes must be clearly visible. Try better lighting and look at the camera."
        case .modelMissing: return "The on-device age model is missing from this build. Install a build that includes the FAHR-FaceAge model."
        case .incompatibleModel: return "This build contains an incompatible model. No estimate was produced."
        case .invalidOutput: return "The model could not produce a valid age estimate for this photo."
        }
    }
}

/// Accessed only by the analyzer's serial worker, including model creation.
private final class CoreMLAgeModel {
    private var loaded: MLModel?
    private var inputName: String?
    private var outputName: String?

    private func load() throws -> MLModel {
        if let loaded { return loaded }
        let bundle = Bundle.main
        guard let url = bundle.url(forResource: "FAHRFaceAge", withExtension: "mlmodelc") else {
            throw FaceAnalysisFailure.modelMissing
        }
        guard let metadataURL = bundle.url(forResource: "conversion", withExtension: "json"),
              let metadata = try? Data(contentsOf: metadataURL) else {
            throw FaceAnalysisFailure.incompatibleModel
        }
        try Self.validateSelection(metadata)
        let configuration = MLModelConfiguration()
        #if targetEnvironment(simulator)
        configuration.computeUnits = .cpuOnly
        #else
        configuration.computeUnits = .all
        #endif
        let model = try MLModel(contentsOf: url, configuration: configuration)
        let contract = try Self.validate(model.modelDescription)
        inputName = contract.input
        outputName = contract.output
        loaded = model
        return model
    }

    private static func validateSelection(_ data: Data) throws {
        struct Selection: Decodable { let model_id: String }
        guard let selection = try? JSONDecoder().decode(Selection.self, from: data),
              selection.model_id == CoreMLFaceAnalyzer.modelID else {
            throw FaceAnalysisFailure.incompatibleModel
        }
    }

    private static func validate(_ description: MLModelDescription) throws -> (input: String, output: String) {
        let inputs = description.inputDescriptionsByName
        let outputs = description.outputDescriptionsByName
        guard inputs.count == 1, outputs.count == 1,
              let input = inputs.first, let output = outputs.first,
              input.value.type == .multiArray, output.value.type == .multiArray,
              let inputArray = input.value.multiArrayConstraint,
              let outputArray = output.value.multiArrayConstraint,
              inputArray.dataType == .float32, outputArray.dataType == .float32,
              inputArray.shape.map(\.intValue) == [1, 112, 112, 3],
              outputArray.shape.map(\.intValue) == [1] else {
            throw FaceAnalysisFailure.incompatibleModel
        }
        return (input.key, output.key)
    }

    func predict(rgb: [Float]) throws -> Double {
        guard rgb.count == 112 * 112 * 3, rgb.allSatisfy({ $0.isFinite && (0...255).contains($0) }) else {
            throw FaceAnalysisFailure.incompatibleModel
        }
        let model = try load()
        guard let inputName, let outputName else { throw FaceAnalysisFailure.incompatibleModel }
        let array = try MLMultiArray(shape: [1, 112, 112, 3], dataType: .float32)
        let pointer = array.dataPointer.bindMemory(to: Float.self, capacity: rgb.count)
        for index in rgb.indices { pointer[index] = rgb[index] }
        let input = try MLDictionaryFeatureProvider(dictionary: [inputName: MLFeatureValue(multiArray: array)])
        let result = try model.prediction(from: input)
        guard let output = result.featureValue(for: outputName)?.multiArrayValue,
              output.dataType == .float32, output.shape.map(\.intValue) == [1] else {
            throw FaceAnalysisFailure.invalidOutput
        }
        let age = output[0].doubleValue
        guard age.isFinite, (0...130).contains(age) else { throw FaceAnalysisFailure.invalidOutput }
        return age
    }
}
