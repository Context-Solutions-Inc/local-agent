package com.contextsolutions.localagent.link.transport

/**
 * Builds a connected [RelayBytePipe] from a scanned relay QR (the mobile side).
 * commonMain seam (#23) — the androidMain actual wraps the Secure Gateway
 * `MobileClient` (pair from the QR, then connect). Returns null when the relay is
 * unavailable so the caller falls back to LAN/local.
 */
interface RelayBytePipeFactory {
    suspend fun create(relayQrJson: String): RelayBytePipe?
}
