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
                // Metal first; LiteRT-LM falls back to CPU internally when Metal init
                // fails. visionBackend (not maxNumImages) turns on the vision tower.
                let backend: Backend = useGpu ? .gpu : .cpu()
                let config = try EngineConfig(
                    modelPath: modelPath,
                    backend: backend,
                    visionBackend: enableVision ? backend : nil,
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

                let userMessage = Message(turns.last?.text ?? "", role: .user)
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
