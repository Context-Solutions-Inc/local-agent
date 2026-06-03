package com.contextsolutions.mobileagent.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Persistent user preference for the **mobile↔desktop agent link** (PR #57). When
 * the user pairs their phone to their desktop agent over the LAN (by scanning a QR
 * code shown on the desktop's Settings page), the phone records the desktop's
 * endpoint + a pairing token here. While the link is enabled and the desktop is
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
 * Mobile↔desktop link configuration.
 *
 * @property enabled the user's "Auto Desktop Link" toggle. Independent of whether
 *   a peer is paired — the link is *active* only when [enabled] AND a peer is set.
 * @property peerHost the paired desktop's LAN IP/hostname (no scheme), from the QR.
 * @property peerPort the desktop link server's bound port, from the QR.
 * @property pairingToken bearer token (from the QR) sent on every request so the
 *   desktop rejects un-paired LAN clients. Plain HTTP by design (trusted LAN).
 * @property pairedDeviceId the desktop's stable device id (from the QR).
 * @property selfDeviceId this install's stable id (minted on first read).
 */
@Serializable
data class DesktopLinkConfig(
    val enabled: Boolean = false,
    val peerHost: String = "",
    val peerPort: Int? = null,
    val pairingToken: String = "",
    val pairedDeviceId: String = "",
    val selfDeviceId: String = "",
) {
    /** True once a desktop is paired (regardless of the [enabled] toggle). */
    val isPaired: Boolean
        get() = peerHost.isNotBlank() && peerPort != null && pairingToken.isNotBlank()

    /** The gate that makes the link the active backend: toggled on AND paired. */
    val isLinkConfigured: Boolean
        get() = enabled && isPaired

    /**
     * Base URL of the desktop link server, e.g. `http://192.168.1.20:47215`.
     * Null when host/port are unset. A bare IP/hostname gets an `http://` scheme
     * (the LAN link is plain HTTP); an explicit scheme is preserved.
     */
    fun baseUrl(): String? {
        if (peerHost.isBlank() || peerPort == null) return null
        val h = peerHost.trim().trimEnd('/')
        val withScheme = if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
        return "$withScheme:$peerPort"
    }

    companion object {
        const val DEFAULT_PORT = 47215
        val EMPTY = DesktopLinkConfig()
    }
}
