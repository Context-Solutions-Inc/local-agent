package com.contextsolutions.mobileagent.subscription

import com.contextsolutions.mobileagent.link.transport.LinkTransportProvider

/**
 * UI seam (#55) to revoke the relay pairing at the gateway (the peer drops + the pair slot
 * frees). Used by BOTH the desktop "Disconnect" (host) and the mobile "Unpair" (client):
 * - Desktop binds [DesktopRelayHost] — revokes, then re-arms a fresh pairing QR.
 * - Mobile binds [RelayUnpairDisconnector] — revokes via the live `MobileClient` and tears
 *   the pipe down (the caller also clears the local config + account secret).
 */
interface RelayDisconnector {
    suspend fun disconnect()
}

/** Mobile binding — revoke the relay pairing through the transport provider's live pipe. */
class RelayUnpairDisconnector(private val transports: LinkTransportProvider) : RelayDisconnector {
    override suspend fun disconnect() = transports.unpairRelay()
}

/** No-op binding (no relay configured / not a host or client). */
object NoOpRelayDisconnector : RelayDisconnector {
    override suspend fun disconnect() = Unit
}
