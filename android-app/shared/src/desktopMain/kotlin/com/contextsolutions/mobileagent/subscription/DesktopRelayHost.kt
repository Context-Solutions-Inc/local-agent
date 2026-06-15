package com.contextsolutions.mobileagent.subscription

import com.contextsolutions.mobileagent.link.transport.DesktopRelayBytePipe
import com.contextsolutions.mobileagent.link.transport.FrameDispatcher
import com.contextsolutions.mobileagent.link.transport.LinkConnectionState
import com.contextsolutions.mobileagent.link.transport.LinkRequestHandler
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.securegateway.core.auth.AuthException
import com.securegateway.desktop.DesktopClient
import com.securegateway.desktop.DesktopConfig
import com.securegateway.desktop.SecureGateway
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
) : RelayDisconnector, RelayPairingInitiator {
    private var client: DesktopClient? = null
    private var pipe: DesktopRelayBytePipe? = null
    private var stateJob: Job? = null

    // PR #92 — a user "Pair Now" click emits here; the relay lifecycle (Main.kt) collects
    // it to mint a fresh QR + await pairing instead of auto-minting on subscribe/startup/
    // disconnect (the pairing token is only valid ~PAIRING_WINDOW, so an auto-shown QR is
    // usually stale). replay=0 + a 1-slot buffer drops a click while one is in flight.
    private val _pairRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pairRequests: SharedFlow<Unit> = _pairRequests.asSharedFlow()

    override fun requestPairing() {
        logger("host: pairing requested (Pair Now)")
        _pairRequests.tryEmit(Unit)
    }

    private val _state = MutableStateFlow(LinkConnectionState.DISABLED)
    val state: StateFlow<LinkConnectionState> = _state.asStateFlow()

    // Whether a mobile peer has paired (PR #90). Seeded from the persisted marker so it
    // survives a desktop restart while the phone is offline — the Settings status then
    // shows "Mobile agent offline" + Disconnect instead of "No Mobile Agent paired yet".
    // Set on the first UP, cleared only on an unpair (peer REVOKED or our own Disconnect).
    private val _peerPaired = MutableStateFlow(
        secureStorage.get(SecureStorageKeys.RELAY_PEER_PAIRED) != null,
    )
    val peerPaired: StateFlow<Boolean> = _peerPaired.asStateFlow()

    private fun markPeerPaired() {
        if (!_peerPaired.value) {
            secureStorage.put(SecureStorageKeys.RELAY_PEER_PAIRED, "1")
            _peerPaired.value = true
        }
    }

    private fun clearPeerPaired() {
        secureStorage.remove(SecureStorageKeys.RELAY_PEER_PAIRED)
        _peerPaired.value = false
    }

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
            // PR #91 — restore the prior pairing so a desktop restart reconnects the existing
            // relay session WITHOUT re-pairing (isPaired() → connect() only, no fresh QR / re-scan),
            // symmetric to the phone's MobileConfig.pairId/desktopPublicKeyB64.
            pairId = secureStorage.get(SecureStorageKeys.RELAY_DESKTOP_PAIR_ID)
            mobilePublicKeyB64 = secureStorage.get(SecureStorageKeys.RELAY_MOBILE_PUBLIC_KEY)
            // PR #80 — keep the X25519 identity in the encrypted SecureStorage (secrets.p12)
            // rather than the SDK's plaintext `relay_identity.key` file. The legacy file path is
            // passed so an existing identity migrates in once, then the plaintext file is removed.
            keyStore = SecureStorageKeyStore(secureStorage, keyStorePath) { this@DesktopRelayHost.logger(it) }
            logger = java.util.function.Consumer { this@DesktopRelayHost.logger("[sdk] $it") }
        }
        return SecureGateway.desktop(config)
    }

    /** Mint the relay pairing QR as the JSON string embedded in the QR (B4); carries the account secret. */
    fun generatePairingQr(): String {
        logger("host: minting relay pairing QR (gateway=$gatewayBaseUrl relay=$relayWsUrl)")
        return runCatching { mintQr() }.getOrElse { err ->
            // A persisted desktop device id the gateway no longer recognizes makes
            // createPairingToken fail with `bad_devices` — e.g. the subscription was
            // re-claimed under a NEW account, or the gateway's in-memory store was reset
            // (AUTH_STORE=memory). Since PR #80 there is no LAN QR to fall back to, so
            // self-heal: drop the stale id, rebuild the client (→ ensureDevice registers
            // a fresh device), and retry the mint once.
            if (isStaleDeviceError(err)) {
                logger("host: desktop device id rejected (${err.message}); clearing + re-registering a fresh device")
                secureStorage.remove(SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID)
                runCatching { client?.close() }
                client = null
                mintQr()
            } else {
                throw err
            }
        }
    }

    private fun mintQr(): String {
        val client = ensureClient()
        val qr = client.generatePairingQr().toJson()
        // Persist the device id the SDK just registered so the NEXT launch reuses it
        // (newClient reads it back). Without this the desktop registers a new device on
        // every restart once a pairing already occupies the account's single slot →
        // capacity_exceeded.
        client.deviceId()?.let { id ->
            if (secureStorage.get(SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID) != id) {
                secureStorage.put(SecureStorageKeys.RELAY_DESKTOP_DEVICE_ID, id)
                logger("host: persisted desktop device id=$id")
            }
        }
        return qr
    }

    /**
     * True when the gateway rejected our persisted desktop device id as unknown
     * (`bad_devices`) — recoverable by clearing it and registering a fresh device.
     * NOT `capacity_exceeded` (a valid prior pairing holds the slot; a new device
     * would still be rejected and would leak), which the caller surfaces instead.
     */
    private fun isStaleDeviceError(t: Throwable): Boolean {
        val m = (t.message.orEmpty() + " " + (t.cause?.message ?: ""))
        return "bad_devices" in m
    }

    /** Block until the phone completes pairing (blocking poll — call on an IO dispatcher). */
    fun awaitPairing(timeout: Duration) {
        logger("host: awaiting pairing (timeout=${timeout.seconds}s)")
        val c = ensureClient()
        c.awaitPairing(timeout)
        persistPairing(c)
    }

    /**
     * Persist the pair id + mobile public key learned at [awaitPairing] so the NEXT desktop
     * launch reconnects this session without re-pairing (PR #91). The device id already
     * persists in [mintQr]; this is the symmetric counterpart of the phone's RELAY_PAIRING_STATE.
     */
    private fun persistPairing(c: DesktopClient) {
        val pairId = c.pairId()
        val mobileKey = c.mobilePublicKeyB64()
        if (pairId != null && mobileKey != null) {
            secureStorage.put(SecureStorageKeys.RELAY_DESKTOP_PAIR_ID, pairId)
            secureStorage.put(SecureStorageKeys.RELAY_MOBILE_PUBLIC_KEY, mobileKey)
            logger("host: persisted pairing pairId=$pairId for reconnect-without-repair")
        }
    }

    /** Drop the persisted pairing (pair id + mobile public key) + the peer marker — the pair is dead. */
    private fun clearSavedPairing() {
        secureStorage.remove(SecureStorageKeys.RELAY_DESKTOP_PAIR_ID)
        secureStorage.remove(SecureStorageKeys.RELAY_MOBILE_PUBLIC_KEY)
        clearPeerPaired()
    }

    /**
     * Try to reconnect a previously persisted pairing WITHOUT minting a fresh QR (PR #91). Returns
     * true when a saved pairing exists and the relay session was (re)opened; false when there is no
     * saved pairing or the reconnect failed — the caller then mints a fresh QR + awaits a re-pair.
     * `connect()` issues a token over blocking HTTP, so call on an IO dispatcher.
     */
    fun reconnect(handler: LinkRequestHandler, scope: CoroutineScope): Boolean {
        val c = ensureClient()
        if (!c.isPaired()) return false
        logger("host: saved pairing found (pairId=${c.pairId()}); reconnecting without re-pairing")
        return runCatching {
            connectAndServe(handler, scope) // c.connect() → issueToken (sync) → relay dial (async)
            true
        }.getOrElse { err ->
            // connect()'s issueToken is synchronous; a revoked/unknown pair throws here.
            logger("host: relay reconnect failed (${err.message}); falling back to a fresh QR")
            if (isDeadPairingError(err)) clearSavedPairing() // dead → don't keep retrying it
            close()                                          // tear down stateJob + client
            false
        }
    }

    /**
     * True when the relay reconnect failed because the gateway rejected the pair (revoked / unknown
     * — a real HTTP error), so the saved pairing should be dropped. A `transport_error`
     * ([AuthException.httpStatus] 0, i.e. the gateway was unreachable) is transient — keep the
     * saved pairing so a later attempt can still reconnect.
     */
    private fun isDeadPairingError(t: Throwable): Boolean {
        val auth = generateSequence(t) { it.cause }.filterIsInstance<AuthException>().firstOrNull()
        return auth != null && auth.httpStatus() != 0
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
                    // First successful connection — remember a phone has paired (PR #90).
                    markPeerPaired()
                } else if (st == LinkConnectionState.DISABLED && wasUp) {
                    wasUp = false
                    // REVOKED — the phone unpaired. Forget the peer + the saved pairing (it's dead)
                    // so the status drops to "No Mobile Agent paired yet" (PR #90) and the next loop
                    // mints a fresh QR instead of trying to reconnect a revoked pair (PR #91).
                    clearSavedPairing()
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
        // Desktop-initiated unpair — forget the peer + the saved pairing (the gateway revoke
        // below kills it) so the status drops to "No Mobile Agent paired yet" + hides Disconnect
        // (PR #90), and the next loop mints a fresh QR rather than reconnecting a dead pair (PR #91).
        clearSavedPairing()
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

    companion object {
        /**
         * The pairing window: how long a minted QR is shown + how long [awaitPairing] waits.
         * Matches the relay pairing token's ~300s validity so the on-screen countdown can't
         * outlive the token (PR #92).
         */
        val PAIRING_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
