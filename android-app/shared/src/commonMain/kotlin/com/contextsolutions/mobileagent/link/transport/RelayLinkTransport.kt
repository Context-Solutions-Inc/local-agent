package com.contextsolutions.mobileagent.link.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Mobile [LinkTransport] over the E2EE relay (PR #57 relay path; the only link
 * transport since PR #80). Frames link requests onto a [RelayBytePipe] via a
 * [FrameMultiplexer]; the desktop's [FrameDispatcher] answers. Used when a relay
 * subscription is active and the phone paired via the relay QR.
 */
class RelayLinkTransport(
    pipe: RelayBytePipe,
    scope: CoroutineScope,
) : LinkTransport {

    private val mux = FrameMultiplexer(pipe, scope).also { it.start() }

    override val target: String = "relay"

    override suspend fun unary(request: LinkRequest): LinkResponse = mux.unary(request)

    override fun serverStream(request: LinkRequest): Flow<LinkStreamEvent> = mux.serverStream(request)
}
