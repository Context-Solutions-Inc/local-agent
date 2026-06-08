package com.contextsolutions.mobileagent.subscription

import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.securegateway.core.auth.QrPayload
import com.securegateway.desktop.DesktopClient
import com.securegateway.desktop.DesktopConfig
import com.securegateway.desktop.SecureGateway
import java.nio.file.Path

/**
 * PR #74 transport seam — the **stub** hook for the relay follow-up PR.
 *
 * When a subscription is active, the desktop should pair + relay over the Secure
 * Gateway's end-to-end-encrypted WebSocket instead of the LAN HTTP server. That
 * is a substantial change (tunnel the existing sync + chat-completions traffic
 * through the relay's opaque `send`/`onMessage` byte pipe) and is
 * deliberately **out of scope** for this draft, which delivers payment + claim +
 * launch validation + the Settings UI.
 *
 * This class proves the gateway SDK is consumable from the desktop module and
 * pins the exact construction the follow-up will build on:
 *
 *  - [relayPairingQr] mints the versioned relay QR ({@code v:1}, with the
 *    relay/auth endpoints + desktop pubkey) via [DesktopClient.generatePairingQr];
 *    the desktop would render this instead of the LAN `magent://link` payload.
 *  - the data path ([DesktopClient.connect]/`send`/`onMessage`) is the TODO.
 *
 * Nothing wires this into `RoutingInferenceEngine`/`SyncController` yet, so the
 * working LAN link is untouched.
 */
class RelayLinkTransport(
    private val prefs: SubscriptionPreferences,
    private val secureStorage: SecureStorage,
    private val gatewayBaseUrl: String,
    private val relayWsUrl: String,
    private val keyStorePath: Path,
) {
    private fun newClient(): DesktopClient {
        val state = prefs.state()
        val secret = secureStorage.get(SecureStorageKeys.RELAY_ACCOUNT_SECRET)
            ?: error("no relay account secret; not subscribed")
        val config = DesktopConfig().apply {
            authUrl = gatewayBaseUrl
            relayUrl = relayWsUrl
            accountSecret = secret
            licenseId = state.licenseId
        }.keyStoreFile(keyStorePath)
        return SecureGateway.desktop(config)
    }

    /** Mint the relay pairing QR payload (B4). Used by the follow-up to render it. */
    fun relayPairingQr(): QrPayload = newClient().generatePairingQr()

    /** The encrypted relay data path. Implemented in the transport follow-up PR. */
    fun connectAndRelay(): Nothing =
        TODO("relay transport (sync + inference over the E2EE websocket) — follow-up PR")
}
