package com.contextsolutions.mobileagent.subscription

/**
 * UI seam (PR #92) to request a fresh relay pairing QR on demand. The relay pairing
 * token is only valid ~300s, so auto-minting it on subscribe / clean startup /
 * disconnect usually leaves a stale code on screen. Instead the desktop shows a
 * "Pair Now" button; clicking it calls [requestPairing] to mint a QR for the next
 * 300s window.
 * - Desktop binds [DesktopRelayHost] — emits to its `pairRequests` flow, which the
 *   relay lifecycle (Main.kt) collects to mint + await + serve.
 * - Mobile / iOS bind [NoOpRelayPairingInitiator] — the phone scans a QR, it never
 *   mints one.
 */
interface RelayPairingInitiator {
    fun requestPairing()
}

/** No-op binding (no relay host present — mobile/iOS). */
object NoOpRelayPairingInitiator : RelayPairingInitiator {
    override fun requestPairing() = Unit
}
