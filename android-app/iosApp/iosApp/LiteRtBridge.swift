import Foundation
import ComposeApp
// The official LiteRT-LM Swift package (added via Xcode → Add Package Dependencies,
// https://github.com/google-ai-edge/LiteRT-LM). Pin by COMMIT
// a0afb5a56acd106b23a2b2385b8469834dc268c0 (the v0.13.1 commit) — NOT by version
// (the LiteRTLM target uses unsafe build flags, which SwiftPM forbids for versioned
// deps) and NOT "Branch: main" (its sources reference C symbols the released binary
// lacks → "Cannot find … in scope"). See docs/IOS_BUILD.md.
import LiteRTLM

// Swift implementation of the Kotlin `NativeLlmBridge` (PR #41) using the official
// LiteRT-LM Swift API (Metal GPU + CPU fallback, Gemma 4 E2B, streaming). Registered
// into the Koin graph by `iOSApp.init` so `RoutingInferenceEngine`'s local engine on
// iOS is `LiteRtIosInferenceEngine(this)`.
//
// IMPORTANT (on-device only): LiteRT-LM inference runs on a PHYSICAL iPhone, not the
// iOS Simulator (Simulator CPU inference crashes, Metal is unavailable — see
// google-ai-edge/LiteRT-LM#2504). The Simulator exercises only the UI + the remote
// Ollama path.
//
// API surface matches LiteRT-LM Swift v0.13.1 (Engine/EngineConfig/ConversationConfig/
// SamplerConfig/Message). If you bump the package tag, re-check these signatures.
final class LiteRtBridge: NativeLlmBridge {

    private var engine: Engine?

    func load(
        modelPath: String,
        useGpu: Bool,
        enableVision: Bool,
        onLoaded: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task.detached {
            do {
                // Metal first for the TEXT LLM; LiteRT-LM falls back to CPU internally
                // when Metal init fails. visionBackend (not maxNumImages) turns on the
                // vision tower.
                let backend: Backend = useGpu ? .gpu : .cpu()
                // Vision runs on CPU/XNNPack, NOT Metal. Gemma 4 E2B's SigLIP encoder is
                // fully XNNPack-delegatable on iOS (LiteRT-LM #2370), whereas requesting
                // the Metal vision backend routes to the compiled-model executor whose
                // STABLEHLO_COMPOSITE op is not registered in the iOS dylib → "Node …
                // (STABLEHLO_COMPOSITE) failed to prepare" → createConversation fails.
                // iOS vision is a "known Metal constraint" (#2385) — keep it on .cpu().
                let visionBackend: Backend? = enableVision ? .cpu() : nil
                let config = try EngineConfig(
                    modelPath: modelPath,
                    backend: backend,
                    visionBackend: visionBackend,
                    cacheDir: NSTemporaryDirectory()
                )
                let engine = Engine(engineConfig: config)
                try await engine.initialize()
                self.engine = engine
                onLoaded(useGpu ? "gpu" : "cpu")
            } catch {
                onError("LiteRT-LM load failed: \(error.localizedDescription)")
            }
        }
    }

    func generate(
        systemInstruction: String?,
        turns: [NativeChatTurn],
        imageBytes: KotlinByteArray?,
        onToken: @escaping (String) -> Void,
        onDone: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) -> NativeGenHandle {
        let task = Task.detached {
            do {
                guard let engine = self.engine else {
                    onError("model not loaded")
                    return
                }
                // The Swift API has no addMessage; replay prior turns via
                // ConversationConfig.initialMessages so Gemma's chat template carries
                // history. The last turn is the current user message we stream a reply to.
                let history = turns.dropLast().map { turn in
                    Message(turn.text, role: Self.role(from: turn.role))
                }
                let sampler = try SamplerConfig(topK: 40, topP: 0.95, temperature: 0.7)
                let config = ConversationConfig(
                    systemMessage: systemInstruction.map { Message($0, role: .system) },
                    initialMessages: history,
                    samplerConfig: sampler
                )
                let conversation = try await engine.createConversation(with: config)

                // Attach the downscaled JPEG to the CURRENT (trailing) turn only —
                // invariant #39, mirroring Android's
                // Message.user(Contents.of(Content.ImageBytes, Content.Text)) (image
                // first, then text). The image reaches the model solely here; prior
                // turns are text-only. Requires visionBackend != nil (set in load()).
                let lastText = turns.last?.text ?? ""
                let userMessage: Message
                if let imageBytes = imageBytes, imageBytes.size > 0 {
                    // v0.13.1 multi-part initializer: `Message(of: Content..., role:)` —
                    // variadic Content (.imageData(Data) / .text(String)), NOT an array.
                    let imageData = imageBytes.toData()
                    userMessage = Message(of: .imageData(imageData), .text(lastText), role: .user)
                } else {
                    userMessage = Message(lastText, role: .user)
                }
                for try await chunk in conversation.sendMessageStream(userMessage) {
                    if Task.isCancelled { onDone("cancelled"); return }
                    onToken(chunk.toString)
                }
                onDone("stop")
            } catch is CancellationError {
                onDone("cancelled")
            } catch {
                if Task.isCancelled {
                    onDone("cancelled")
                } else {
                    onError("LiteRT-LM generate failed: \(error.localizedDescription)")
                }
            }
        }
        return TaskGenHandle(task: task)
    }

    func unload() {
        engine = nil
    }

    /// Map the Kotlin `NativeChatTurn.role` string to the LiteRT-LM `Role`.
    private static func role(from kotlinRole: String) -> Role {
        switch kotlinRole {
        case "system": return .system
        case "model", "assistant": return .model
        default: return .user
        }
    }
}

/// Bridges Kotlin `NativeGenHandle.cancel()` to a Swift `Task` cancellation.
private final class TaskGenHandle: NativeGenHandle {
    private let task: Task<Void, Never>
    init(task: Task<Void, Never>) { self.task = task }
    func cancel() { task.cancel() }
}

private extension KotlinByteArray {
    /// Copy the Kotlin `ByteArray` into a Swift `Data`. Kotlin/Native exports no bulk
    /// accessor, so this copies per byte — fine for a single downscaled JPEG (~40–90 KB)
    /// built once per image turn, off the main actor in `generate`'s detached task.
    func toData() -> Data {
        let count = Int(size)
        guard count > 0 else { return Data() }
        var data = Data(count: count)
        data.withUnsafeMutableBytes { raw in
            guard let dst = raw.bindMemory(to: Int8.self).baseAddress else { return }
            for i in 0..<count { dst[i] = get(index: Int32(i)) }
        }
        return data
    }
}
