package com.contextsolutions.localagent.link.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The opaque end-to-end-encrypted byte-pipe to the paired peer, as a
 * platform-clean seam (#23). The actuals wrap the Secure Gateway SDK clients —
 * `DesktopClient` (desktopMain) and `MobileClient` (androidMain) — keeping all
 * `com.contextsolutions.securegateway.*` types out of commonMain.
 *
 * [send] suspends until the peer acks the message (the SDK's `send` future). The
 * [FrameMultiplexer] (client) and [FrameDispatcher] (server) build the link RPC
 * on top of this pipe.
 */
interface RelayBytePipe {
    /** Send one message to the peer; suspends until the peer acks (or fails). */
    suspend fun send(bytes: ByteArray)

    /** Inbound messages from the peer (decrypted plaintext). */
    val inbound: Flow<ByteArray>

    /** Live connection state (derived from the SDK's `onStateChange`). */
    val state: StateFlow<LinkConnectionState>

    /**
     * Revoke this pairing at the gateway (FR-2.5) so the peer's session is cut and the pair
     * slot freed (the phone "Unpair" / desktop "Disconnect"). Default no-op for pipes that
     * don't drive revocation. Call [close] afterward to drop the local session.
     */
    suspend fun unpair() {}

    suspend fun close()
}
