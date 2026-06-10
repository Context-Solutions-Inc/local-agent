package com.contextsolutions.mobileagent.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Persistent user preference for the **mobile↔desktop agent link** (PR #57; LAN
 * path removed in PR #80). The user pairs their phone to their desktop agent by
 * scanning the Secure Gateway **relay** QR shown on the desktop's Settings page
 * (only available while the desktop holds an active subscription). The scanned
 * relay QR JSON is recorded here. While the link is enabled and the relay is
 * reachable:
 *
 *  - the *large chat LLM* routes to the desktop agent (which itself decides local
 *    vs. its own remote Ollama — the phone never sees that downstream endpoint),
 *    taking **priority over** a directly-configured [OllamaPreferences] server;
 *  - conversations, memories and a safe subset of settings sync bidirectionally.
 *
 * Mirrors [OllamaPreferences] exactly:
 *  - Plain `SharedPreferences` on Android / a `DesktopJsonStore` file on desktop
 *    (endpoint/token are configuration, not high-value credentials, and the LAN
 *    is trusted — the requirement is no SSL).
 *  - In-memory `MutableStateFlow` seeded from disk at construction; writes update
 *    both for next-process recovery and current-process subscribers.
 *  - The whole [DesktopLinkConfig] is stored as one JSON blob under a single key.
 *
 * The [configFlow] is load-bearing: toggling the link or pairing a new desktop in
 * Settings must drop a resident model so the next turn re-decides the backend
 * (see `RoutingInferenceEngine`). The Android session manager / desktop warm-model
 * runtime observe it and force an unload on change — exactly the PR #56 Ollama hook.
 *
 * Every install lazily mints a stable [DesktopLinkConfig.selfDeviceId] on first
 * read so the two peers can identify each other during pairing + sync.
 */
interface DesktopLinkPreferences {

    /** Snapshot read. Safe from any dispatcher; serves from in-memory state. */
    fun config(): DesktopLinkConfig

    /** Reactive read. Emits the current value on subscribe, then each change. */
    fun configFlow(): Flow<DesktopLinkConfig>

    /** Persist [config] for current and future processes. Idempotent. */
    fun setConfig(config: DesktopLinkConfig)

    /** This install's stable device id (minted + persisted on first read). */
    fun selfDeviceId(): String = config().selfDeviceId

    /** Disable + forget the paired desktop (keeps [DesktopLinkConfig.selfDeviceId]). */
    fun clear() = setConfig(DesktopLinkConfig(selfDeviceId = config().selfDeviceId))
}

/**
 * Mobile↔desktop link configuration (relay-only since PR #80).
 *
 * @property enabled the user's "Desktop Agent Connection" toggle. Independent of whether
 *   a peer is paired — the link is *active* only when [enabled] AND a peer is set.
 * @property pairedDeviceId the desktop's stable device id (from the relay QR).
 * @property selfDeviceId this install's stable id (minted on first read).
 * @property relayQrJson the scanned Secure Gateway relay QR JSON; enough to re-pair /
 *   reconnect over the E2EE relay.
 */
@Serializable
data class DesktopLinkConfig(
    val enabled: Boolean = false,
    val pairedDeviceId: String = "",
    val selfDeviceId: String = "",
    val relayQrJson: String = "",
) {
    /** True once a desktop is paired (regardless of the [enabled] toggle): a scanned relay QR. */
    val isPaired: Boolean
        get() = relayQrJson.isNotBlank()

    /** The gate that makes the link the active backend: toggled on AND paired. */
    val isLinkConfigured: Boolean
        get() = enabled && isPaired

    /** Relay path active: toggled on, with a scanned relay QR. (Relay is the only path.) */
    val isRelayConfigured: Boolean
        get() = isLinkConfigured

    companion object {
        const val DEFAULT_PORT = 47215
        val EMPTY = DesktopLinkConfig()
    }
}
