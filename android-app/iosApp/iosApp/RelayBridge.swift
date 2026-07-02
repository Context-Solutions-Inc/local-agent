import Foundation
import ComposeApp
// The native Secure Gateway iOS SDK (added via Xcode → Add Package Dependencies as a LOCAL
// package pointing at ../secure-gateway/sdk/ios during co-dev; pin to a tag/commit for
// CI/release once stable). SwiftPM transitively pulls swift-sodium + swift-crypto. See
// docs/IOS_BUILD.md.
import SecureGatewaySDK

// Swift implementation of the Kotlin `NativeRelayBridge` (iOS relay) using the native
// SecureGatewaySDK `MobileClient` (CryptoKit + swift-sodium + URLSessionWebSocketTask).
// Registered into the Koin graph by `iOSApp.init` so the iOS relay transport
// (IosRelayBytePipe / IosRelayBytePipeFactory) tunnels chat + sync to the paired desktop.
// All SDK types stay in Swift (#23); pairing ORCHESTRATION lives in the Kotlin factory.
//
// IMPORTANT (on-device only): the relay + E2EE crypto run on a PHYSICAL iPhone. The Simulator
// exercises only the UI + the on-device fallback.
final class RelayBridge: NativeRelayBridge {
    func open(config: NativeRelayConfig) -> NativeRelaySession {
        RelaySession(config: config) // ↔ SecureGateway.mobile(config)
    }
}

private final class RelaySession: NativeRelaySession {
    private let client: MobileClient?
    private let initError: String?

    init(config: NativeRelayConfig) {
        do {
            self.client = try MobileClient(
                authURL: config.authUrl,
                relayURL: config.relayUrl,
                accountSecret: config.accountSecret.isEmpty ? nil : config.accountSecret,
                deviceId: config.deviceId,
                pairId: config.pairId,
                desktopPublicKeyB64: config.desktopPublicKeyB64,
                pairCredential: config.pairCredential
            )
            self.initError = nil
        } catch {
            self.client = nil
            self.initError = "MobileClient init failed: \(error.localizedDescription)"
        }
    }

    // Kotlin `(bytes: ByteArray) -> Unit` / `(stateCode: Int) -> Unit` export as
    // `(KotlinByteArray) -> Void` / `(KotlinInt) -> Void`. The pipe calls this before connect(),
    // so the closures are wired into MobileClient (which copies them into the ConnectionManager
    // at connect time) — mirroring Android wiring onMessage/onStateChange before connect().
    func setCallbacks(onMessage: @escaping (KotlinByteArray) -> Void,
                      onStateChange: @escaping (KotlinInt) -> Void) {
        client?.onMessage = { data in onMessage(data.toKotlinByteArray()) }
        client?.onStateChange = { state in onStateChange(KotlinInt(int: Self.code(state))) }
    }

    func isPaired() -> Bool { client?.isPaired() ?? false }

    func pair(qrJson: String, onDone: @escaping () -> Void, onError: @escaping (String) -> Void) {
        guard let client = client else { onError(initError ?? "client unavailable"); return }
        Task.detached {
            do { try await client.pair(qrJSON: qrJson); onDone() }
            catch { onError("pair failed: \(error.localizedDescription)") }
        }
    }

    func connect(onDone: @escaping () -> Void, onError: @escaping (String) -> Void) {
        guard let client = client else { onError(initError ?? "client unavailable"); return }
        Task.detached {
            do { try await client.connect(); onDone() }
            catch { onError("connect failed: \(error.localizedDescription)") }
        }
    }

    func send(bytes: KotlinByteArray, onAck: @escaping () -> Void, onError: @escaping (String) -> Void) {
        guard let client = client else { onError("client unavailable"); return }
        Task.detached {
            do { try await client.send(bytes.toData()); onAck() }
            catch { onError("send failed: \(error.localizedDescription)") }
        }
    }

    func unpair(onDone: @escaping () -> Void, onError: @escaping (String) -> Void) {
        guard let client = client else { onDone(); return }
        Task.detached {
            do { try await client.unpair(); onDone() }
            catch { onError("unpair failed: \(error.localizedDescription)") }
        }
    }

    func close() { client?.close() }

    func deviceId() -> String? { client?.currentDeviceId }
    func currentPairId() -> String? { client?.currentPairId }
    func pairCredential() -> String? { client?.currentPairCredential }
    func desktopPublicKeyB64() -> String? { client?.desktopPublicKeyB64 }

    /// Map the SDK `ConnectionState` to the Kotlin `NativeRelayState.*` Int code.
    private static func code(_ s: ConnectionState) -> Int32 {
        switch s {
        case .connected:    return 0
        case .reconnecting: return 1
        case .peerOffline:  return 2
        case .revoked:      return 3
        case .superseded:   return 4
        @unknown default:   return 3
        }
    }
}

// File-private (won't collide with LiteRtBridge's private toData()).
private extension Data {
    /// Copy a Swift `Data` into a Kotlin `ByteArray` (per-byte; relay frames are small).
    func toKotlinByteArray() -> KotlinByteArray {
        let arr = KotlinByteArray(size: Int32(count))
        for (i, b) in enumerated() { arr.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return arr
    }
}

private extension KotlinByteArray {
    /// Copy a Kotlin `ByteArray` into a Swift `Data` (Kotlin/Native exports no bulk accessor).
    func toData() -> Data {
        let n = Int(size)
        guard n > 0 else { return Data() }
        var data = Data(count: n)
        data.withUnsafeMutableBytes { raw in
            guard let dst = raw.bindMemory(to: Int8.self).baseAddress else { return }
            for i in 0..<n { dst[i] = get(index: Int32(i)) }
        }
        return data
    }
}
