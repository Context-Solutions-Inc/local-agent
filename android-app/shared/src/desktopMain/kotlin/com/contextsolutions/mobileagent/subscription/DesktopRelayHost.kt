package com.contextsolutions.mobileagent.subscription

import com.contextsolutions.mobileagent.link.transport.DesktopRelayBytePipe
import com.contextsolutions.mobileagent.link.transport.FrameDispatcher
import com.contextsolutions.mobileagent.link.transport.LinkConnectionState
import com.contextsolutions.mobileagent.link.transport.LinkRequestHandler
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.securegateway.desktop.DesktopClient
import com.securegateway.desktop.DesktopConfig
import com.securegateway.desktop.SecureGateway
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop relay host (relay follow-up to PR #74). When a subscription is
 * active, the desktop becomes a relay *request-server*: it mints the relay
 * pairing QR, waits for the phone to pair, connects to the Secure Gateway relay,
 * and serves framed link requests through the SAME [LinkRequestHandler] the LAN
 * Ktor server uses ([com.contextsolutions.mobileagent.link.DesktopLinkRequestHandler]).
 *
 * The LAN server keeps running alongside this (concurrent) — relay covers
 * remote access, LAN stays the fast same-network path and the offline/revoked
 * fallback. All `com.securegateway.*` types stay in desktopMain (#23).
 *
 * The account secret is embedded in the QR by the SDK's `generatePairingQr`
 * (decision: the phone has no subscription of its own), so the scanned QR lets
 * the phone issue connection tokens.
 */
class DesktopRelayHost(
    private val prefs: SubscriptionPreferences,
    private val secureStorage: SecureStorage,
    private val gatewayBaseUrl: String,
    private val relayWsUrl: String,
    private val keyStorePath: Path,
    private val logger: (String) -> Unit = {},
) : RelayDisconnector {
    private var client: DesktopClient? = null
    private var pipe: DesktopRelayBytePipe? = null
    private var stateJob: Job? = null

    private val _state = MutableStateFlow(LinkConnectionState.DISABLED)
    val state: StateFlow<LinkConnectionState> = _state.asStateFlow()

    // Bumped by [disconnect] to re-arm the relay lifecycle (Main.kt re-mints a fresh QR +
    // awaits + serves when this changes, mirroring the LAN Disconnect's token rotation).
    private val _rearm = MutableStateFlow(0)
    val rearm: StateFlow<Int> = _rearm.asStateFlow()

    private fun ensureClient(): DesktopClient = client ?: newClient().also { client = it }

    private fun newClient(): DesktopClient {
        val state = prefs.state()
        val secret = secureStorage.get(SecureStorageKeys.RELAY_ACCOUNT_SECRET)
            ?: error("no relay account secret; not subscribed")
        val config = DesktopConfig().apply {
            authUrl = gatewayBaseUrl
            relayUrl = relayWsUrl
            accountSecret = secret
            licenseId = state.licenseId
            // Reuse the desktop device id across restarts so a re-mint is a re-pair (reuses the
            // max_pairs slot) rather than a new device the gateway rejects as capacity_exceeded.
            deviceId = secureStorage.get(SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID)
            logger = java.util.function.Consumer { this@DesktopRelayHost.logger("[sdk] $it") }
        }.keyStoreFile(keyStorePath)
        return SecureGateway.desktop(config)
    }

    /** Mint the relay pairing QR as the JSON string embedded in the QR (B4); carries the account secret. */
    fun generatePairingQr(): String {
        logger("host: minting relay pairing QR (gateway=$gatewayBaseUrl relay=$relayWsUrl)")
        val client = ensureClient()
        val qr = client.generatePairingQr().toJson()
        // Persist the device id the SDK just registered so the NEXT launch reuses it (above).
        // Without this the desktop falls back to the LAN QR on every restart once a pairing
        // already occupies the account's single slot.
        client.deviceId()?.let { id ->
            if (secureStorage.get(SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID) != id) {
                secureStorage.put(SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID, id)
                logger("host: persisted desktop device id=$id")
            }
        }
        return qr
    }

    /** Block until the phone completes pairing (blocking poll — call on an IO dispatcher). */
    fun awaitPairing(timeout: Duration) {
        logger("host: awaiting pairing (timeout=${timeout.seconds}s)")
        ensureClient().awaitPairing(timeout)
    }

    /**
     * Wire the relay byte-pipe + frame dispatcher, then open the relay session.
     * `connect()` does blocking HTTP (token issue) — call on an IO dispatcher.
     */
    fun connectAndServe(handler: LinkRequestHandler, scope: CoroutineScope) {
        logger("host: connectAndServe — wiring dispatcher + opening relay session")
        val c = ensureClient()
        val p = DesktopRelayBytePipe(c, logger) // wires onMessage/onStateChange before connect()
        stateJob?.cancel()
        stateJob = scope.launch {
            var wasUp = false
            p.state.collect { st ->
                logger("host: relay state -> $st")
                _state.value = st
                // The phone revoking the pairing (Unpair) surfaces as REVOKED → DISABLED,
                // which consumes the QR's pairing token. If we'd connected and didn't close
                // it ourselves (close() cancels this collector first), re-mint a fresh QR so
                // the next phone can pair. PEER_OFFLINE (phone temporarily away) maps to DOWN,
                // not DISABLED, so a backgrounded phone doesn't trigger a re-mint.
                if (st == LinkConnectionState.UP) {
                    wasUp = true
                } else if (st == LinkConnectionState.DISABLED && wasUp) {
                    wasUp = false
                    logger("host: relay session revoked by peer; re-arming a fresh QR")
                    _rearm.value += 1
                }
            }
        }
        FrameDispatcher(p, handler, scope, logger).start()
        pipe = p
        c.connect()
    }

    /**
     * Desktop "Disconnect" for a relay connection: revoke the pairing at the gateway (the
     * phone drops + the pair slot frees), drop the local session, then bump [rearm] so the
     * lifecycle re-mints a fresh QR for re-pairing. The gateway call is blocking HTTP.
     */
    override suspend fun disconnect() {
        // Cancel the peer-revocation watcher BEFORE our own unpair: the gateway revoke we're
        // about to issue bounces back as a REVOKED close, which would otherwise make the
        // watcher fire a SECOND re-arm — churning the QR mint and racing the next pairing.
        stateJob?.cancel()
        stateJob = null
        withContext(Dispatchers.IO) {
            runCatching { client?.unpair() }
                .onFailure { logger("host: unpair failed: ${it.message}") }
        }
        close()
        _rearm.value += 1
    }

    fun close() {
        // Cancel the state collector BEFORE closing so our own teardown's DISABLED transition
        // doesn't get mistaken for a peer revocation (which would re-arm).
        stateJob?.cancel()
        stateJob = null
        runCatching { client?.close() }
        client = null
        pipe = null
        _state.value = LinkConnectionState.DISABLED
    }
}
