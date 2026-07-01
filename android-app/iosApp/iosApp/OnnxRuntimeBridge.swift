import Foundation
import ComposeApp
// ONNX Runtime's Objective-C iOS distribution. Add via Xcode → Add Package Dependencies:
//   https://github.com/microsoft/onnxruntime-swift-package-manager
// (the prebuilt xcframework). Add the `onnxruntime` LIBRARY product to the iosApp target,
// but the importable Swift MODULE is `OnnxRuntimeBindings` (the SwiftPM target that
// re-exports the binary's Objective-C API — ORTEnv/ORTSession/ORTValue). Prefer the
// prebuilt xcframework over a from-source pod so ORT's vendored deps (protobuf/abseil/
// XNNPACK) stay symbol-hidden and don't collide with LiteRT-LM under the SwiftPM-forced
// `-all_load` (ComposeApp must stay a DYNAMIC framework, invariant #78). If you bump the
// ORT version, re-check the ORTValue/ORTSession signatures below. See docs/IOS_BUILD.md.
import OnnxRuntimeBindings

// Swift implementation of the Kotlin `NativeClassifierBridge` + `NativeEmbedderBridge`
// (Phase 2), running the aux `.onnx` models (pre-flight/memory classifier + MiniLM
// embedder) on ONNX Runtime. ONE class conforms to BOTH protocols, sharing one ORTEnv
// but holding two independent ORTSessions. Registered into Koin by `iOSApp.init` so
// `OnnxIosClassifierEngine` / `OnnxIosEmbedderEngine` (the real iOS engines) wrap it.
//
// SCAFFOLD (invariant #78): the ORT Objective-C symbol names / CoreML EP call are
// version-sensitive and can only be finalized + verified in Xcode on a physical device
// (CI is compile-only; ORT CPU EP also runs in the Simulator). The Kotlin bridge
// contract (pure-numeric, named IO) is stable.
//
// Named-IO contract (must match the desktop OnnxClassifierEngine/OnnxEmbedderEngine and
// the export): inputs `input_ids` / `attention_mask` (int64, shape [1,128]); classifier
// outputs `preflight_logits` [1,3], `presence_logits` [1,2], `category_logits` [1,6];
// embedder = its single output [1,384].
final class OnnxRuntimeBridge: NativeClassifierBridge, NativeEmbedderBridge {

    private let env: ORTEnv?
    private var classifierSession: ORTSession?
    private var embedderSession: ORTSession?
    private var embedderOutputName: String = ""

    init() {
        // Best-effort; if the env can't init, loadSession(...) reports the error per-model
        // (ORTEnv has only a throwing initializer — no non-throwing fallback).
        self.env = try? ORTEnv(loggingLevel: ORTLoggingLevel.warning)
    }

    // MARK: - NativeClassifierBridge

    func load(
        modelPath: String,
        useGpu: Bool,
        onLoaded: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        // Both Kotlin protocols declare an identical `load` signature, so Swift merges
        // them into this single method — shared by the classifier AND embedder warmUp.
        // We route to the right session by model filename (the two `.onnx` names are
        // fixed: "…MiniLM…" = embedder, else classifier). See loadSession(...).
        loadSession(modelPath: modelPath, useGpu: useGpu, onLoaded: onLoaded, onError: onError)
    }

    func classify(
        inputIds: KotlinLongArray,
        attentionMask: KotlinLongArray,
        onResult: @escaping (KotlinFloatArray, KotlinFloatArray, KotlinFloatArray) -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task.detached {
            do {
                guard let session = self.classifierSession else { onError("classifier not loaded"); return }
                let outputs = try self.run(session, inputIds: inputIds, attentionMask: attentionMask,
                                           outputNames: ["preflight_logits", "presence_logits", "category_logits"])
                let pre = try Self.floats(outputs["preflight_logits"])
                let pres = try Self.floats(outputs["presence_logits"])
                let cat = try Self.floats(outputs["category_logits"])
                onResult(pre, pres, cat)
            } catch {
                onError("classify failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - NativeEmbedderBridge

    func embed(
        inputIds: KotlinLongArray,
        attentionMask: KotlinLongArray,
        onResult: @escaping (KotlinFloatArray) -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task.detached {
            do {
                guard let session = self.embedderSession else { onError("embedder not loaded"); return }
                let outputs = try self.run(session, inputIds: inputIds, attentionMask: attentionMask,
                                           outputNames: [self.embedderOutputName])
                let vec = try Self.floats(outputs[self.embedderOutputName])
                onResult(vec)
            } catch {
                onError("embed failed: \(error.localizedDescription)")
            }
        }
    }

    func unload() {
        classifierSession = nil
        embedderSession = nil
    }

    // MARK: - Shared

    /// Create an ORTSession (CoreML EP first, CPU fallback) and stash it in the classifier
    /// or embedder slot based on the model filename.
    private func loadSession(
        modelPath: String,
        useGpu: Bool,
        onLoaded: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task.detached {
            do {
                guard let env = self.env else {
                    onError("ONNX env failed to initialize")
                    return
                }
                let options = try ORTSessionOptions()
                var accelerator = "cpu"
                if useGpu {
                    // CoreML EP — best-effort; falls back to CPU on any failure.
                    do {
                        let coreml = ORTCoreMLExecutionProviderOptions()
                        try options.appendCoreMLExecutionProvider(with: coreml)
                        accelerator = "gpu"
                    } catch {
                        accelerator = "cpu"
                    }
                }
                let session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: options)
                if modelPath.contains("MiniLM") {
                    self.embedderSession = session
                    self.embedderOutputName = try session.outputNames().first ?? ""
                } else {
                    self.classifierSession = session
                }
                onLoaded(accelerator)
            } catch {
                onError("ONNX load failed for \(modelPath): \(error.localizedDescription)")
            }
        }
    }

    private func run(
        _ session: ORTSession,
        inputIds: KotlinLongArray,
        attentionMask: KotlinLongArray,
        outputNames: Set<String>
    ) throws -> [String: ORTValue] {
        let ids = try Self.int64Tensor(inputIds)
        let mask = try Self.int64Tensor(attentionMask)
        return try session.run(
            withInputs: ["input_ids": ids, "attention_mask": mask],
            outputNames: outputNames,
            runOptions: try ORTRunOptions()
        )
    }

    /// Build a `[1, N]` int64 ORT tensor from a KotlinLongArray.
    private static func int64Tensor(_ arr: KotlinLongArray) throws -> ORTValue {
        let n = Int(arr.size)
        var buf = [Int64](repeating: 0, count: n)
        for i in 0..<n { buf[i] = arr.get(index: Int32(i)) }
        // NSMutableData(bytes:length:) copies, so `buf` can be released after this call.
        let data = buf.withUnsafeBytes { raw in
            NSMutableData(bytes: raw.baseAddress, length: raw.count)
        }
        return try ORTValue(
            tensorData: data,
            elementType: ORTTensorElementDataType.int64,
            shape: [NSNumber(value: 1), NSNumber(value: n)]
        )
    }

    /// Read a `[1, N]` float32 ORT tensor into a KotlinFloatArray (row 0).
    private static func floats(_ value: ORTValue?) throws -> KotlinFloatArray {
        guard let value = value else { throw BridgeError.missingOutput }
        let data = try value.tensorData() as Data
        let floats: [Float] = data.withUnsafeBytes { raw in Array(raw.bindMemory(to: Float.self)) }
        let out = KotlinFloatArray(size: Int32(floats.count))
        for i in 0..<floats.count { out.set(index: Int32(i), value: floats[i]) }
        return out
    }

    private enum BridgeError: Error { case missingOutput }
}
