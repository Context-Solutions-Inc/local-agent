package com.contextsolutions.localagent.link.transport

/**
 * Swift→Kotlin bridge for the Secure Gateway mobile relay client on iOS (PR — iOS relay).
 *
 * iOS has no Kotlin/Native Secure Gateway artifact (`:core` is JVM-only — ServiceLoader + JNA
 * lazysodium + `java.net`), so — exactly like [com.contextsolutions.localagent.inference.NativeLlmBridge]
 * for the LLM — the **Swift** app implements this callback-shaped interface using the native
 * `SecureGatewaySDK` Swift package (CryptoKit + swift-sodium + `URLSessionWebSocketTask`) and
 * registers the instance into Koin via `doInitKoin(relayBridge = …)`. This keeps all
 * `com.contextsolutions.securegateway.*` / `SecureGatewaySDK` types in Swift (#23).
 *
 * The Kotlin [IosRelayBytePipeFactory] / [IosRelayBytePipe] adapt this into the commonMain
 * [RelayBytePipeFactory] / [RelayBytePipe] seam (`suspend send` + `Flow inbound` + `StateFlow state`)
 * — the inverse of what `LiteRtIosInferenceEngine` does. Pairing ORCHESTRATION (pair-once vs
 * reconnect + `RELAY_PAIRING_STATE` persistence) stays in Kotlin, mirroring `AndroidRelayBytePipeFactory`;
 * the bridge only wraps SDK calls.
 *
 * Deliberately callback-based (no suspend / `Flow`) so it is trivial to conform to from Swift.
 */
interface NativeRelayBridge {
    /**
     * Construct a `MobileClient` from [config] (reconnect seeds included). Does NOT pair or connect —
     * the Kotlin factory drives the returned session. Maps to `SecureGateway.mobile(config)`.
     */
    fun open(config: NativeRelayConfig): NativeRelaySession
}

/**
 * One live `MobileClient`. Every async SDK call is modelled as "exactly one of onDone/onError"
 * (or onAck/onError for [send]) so the Kotlin side can wrap it in `suspendCancellableCoroutine`.
 */
interface NativeRelaySession {
    /**
     * Register the inbound + state sinks. MUST be called before [connect] (the pipe's `init` calls
     * this, mirroring Android wiring `onMessage`/`onStateChange` before `connect()`). [stateCode] is
     * a [NativeRelayState] constant.
     */
    fun setCallbacks(onMessage: (bytes: ByteArray) -> Unit, onStateChange: (stateCode: Int) -> Unit)

    /** True if the SDK already holds a complete pairing (reconnect path — skip pair()). */
    fun isPaired(): Boolean

    /** Complete pairing from the scanned QR JSON. */
    fun pair(qrJson: String, onDone: () -> Unit, onError: (message: String) -> Unit)

    /** Issue a token + dial the relay wss. [onDone] = kickoff succeeded (the socket opens async, surfaced via state). */
    fun connect(onDone: () -> Unit, onError: (message: String) -> Unit)

    /** Send one plaintext frame; the peer-ack completion is [onAck], failure is [onError]. */
    fun send(bytes: ByteArray, onAck: () -> Unit, onError: (message: String) -> Unit)

    /** Revoke this pairing at the gateway (FR-2.5). */
    fun unpair(onDone: () -> Unit, onError: (message: String) -> Unit)

    /** Drop the local session + socket. Safe to call repeatedly. Synchronous (matches Swift `close()`). */
    fun close()

    // Getters populated after pair()/reconnect — read by the factory to persist SavedPairing.
    fun deviceId(): String?
    fun currentPairId(): String?
    fun pairCredential(): String?
    fun desktopPublicKeyB64(): String?
}

/**
 * `MobileConfig` mirror. Reconnect seeds ([deviceId]/[pairId]/[desktopPublicKeyB64]/[pairCredential]/
 * [relayUrl]) are null on a fresh pair, non-null on reconnect. [accountSecret] is a legacy fallback only.
 */
data class NativeRelayConfig(
    val authUrl: String,
    val relayUrl: String?,
    val accountSecret: String,
    val deviceId: String? = null,
    val pairId: String? = null,
    val desktopPublicKeyB64: String? = null,
    val pairCredential: String? = null,
)

/** ConnectionState (5 cases) crosses the bridge as an Int — trivially Swift-friendly. */
object NativeRelayState {
    const val CONNECTED = 0
    const val RECONNECTING = 1
    const val PEER_OFFLINE = 2
    const val REVOKED = 3
    const val SUPERSEDED = 4
}
