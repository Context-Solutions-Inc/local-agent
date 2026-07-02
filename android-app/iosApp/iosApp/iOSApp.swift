import SwiftUI
import ComposeApp

// Local Agent iOS app entry point (PR #41).
//
// Wires the Swift on-device LLM bridge into the shared Koin graph, then hosts the
// shared Compose-Multiplatform UI. The Kotlin side (`:ui` ComposeApp framework)
// owns navigation, screens, chat, persistence; Swift owns only the LiteRT-LM
// engine (via LiteRtBridge) and the UIViewController host.
@main
struct iOSApp: App {
    init() {
        // Start Koin with the Swift on-device bridges: LiteRT-LM for the LLM, and a
        // single ONNX Runtime bridge for BOTH aux models (classifier + embedder).
        // `IosEntryPointKt` is generated from the shared/.../ios entry point.
        let ort = OnnxRuntimeBridge()
        IosEntryPointKt.doInitKoin(
            llmBridge: LiteRtBridge(),
            classifierBridge: ort,
            embedderBridge: ort,
            relayBridge: RelayBridge(),
            qrScanner: QRScannerBridge()
        )
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
