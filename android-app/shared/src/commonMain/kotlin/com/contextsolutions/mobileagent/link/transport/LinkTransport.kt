package com.contextsolutions.mobileagent.link.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The mobile↔desktop link transport seam (relay follow-up to PR #57).
 *
 * The phone reaches the desktop agent two ways: over the LAN ([LanLinkTransport],
 * plain HTTP, PR #57) or — when a relay subscription is active — over the Secure
 * Gateway's end-to-end-encrypted relay WebSocket (`RelayLinkTransport`, the
 * follow-up). Both speak the SAME small request/response + server-stream surface
 * so the callers (the desktop-link [com.contextsolutions.mobileagent.inference.InferenceEngine],
 * the sync client, the status provider) never branch on access mode — they ask
 * [LinkTransportProvider] for the active transport and issue [LinkRequest]s.
 *
 * The existing wire payloads ride **unchanged** in [LinkRequest.body] /
 * [LinkResponse.body] / [LinkStreamEvent.Data.body] (OpenAI chat JSON, the
 * post-`data:` SSE chunk strings, `SyncBundle` JSON), so swapping LAN↔relay never
 * touches the OpenAI/Ollama/Sync (de)serialization.
 */
interface LinkTransport {
    /**
     * Stable id of the current endpoint — the LAN `baseUrl`, or a constant like
     * `"relay"`. Used purely as the reconnect-watch key for the desktop-link
     * [com.contextsolutions.mobileagent.inference.OllamaConnectionMonitor].
     */
    val target: String

    /** Single request → single response. Never throws on transport failure: returns a non-2xx [LinkResponse]. */
    suspend fun unary(request: LinkRequest): LinkResponse

    /**
     * Request → a stream of server-pushed items, terminated by exactly one
     * [LinkStreamEvent.End] or [LinkStreamEvent.Error]. Cancelling the collector
     * tears the stream down (LAN: close the SSE; relay: send a CANCEL frame).
     */
    fun serverStream(request: LinkRequest): Flow<LinkStreamEvent>
}

/** The link methods, mirroring the [LanLinkTransport] / desktop-server routes. */
enum class LinkMethod(val streaming: Boolean) {
    HEALTH(false),
    PAIR(false),
    CHAT(true),
    SYNC_CHANGES(false),
    SYNC_UPSERT(false),
    SYNC_SUBSCRIBE(true),
}

data class LinkRequest(
    val method: LinkMethod,
    val body: String? = null,
    val query: Map<String, String> = emptyMap(),
)

data class LinkResponse(val status: Int, val body: String) {
    /** Status `0` is reserved for "could not reach the peer" (transport-level failure). */
    val isSuccess: Boolean get() = status in 200..299
}

sealed interface LinkStreamEvent {
    /** One stream item — the raw post-`data:` payload (an OpenAI delta chunk, `"[DONE]"` excluded, or `"changed"`). */
    data class Data(val body: String) : LinkStreamEvent

    /** Clean end of stream. */
    data class End(val status: Int) : LinkStreamEvent

    /** Stream failed. [status] `0` means the peer was unreachable (vs an HTTP-status error while up). */
    data class Error(val status: Int, val message: String) : LinkStreamEvent
}

/** Live link reachability, surfaced for the chat-header dot (maps to `DesktopLinkStatus`). */
enum class LinkConnectionState { UP, DOWN, DISABLED }

/**
 * Picks the active [LinkTransport] for the current access mode. LAN is the
 * default and the fallback; the relay transport is returned only when a relay
 * subscription is configured + connected (the follow-up wires that in).
 */
interface LinkTransportProvider {
    /** The transport to use right now, or `null` when the link isn't configured. */
    fun current(): LinkTransport?

    /**
     * Live state of the relay pipe (UP/DOWN/DISABLED), so the status UI can show
     * relay connectivity — there's no LAN `/health` endpoint to poll over the relay.
     * Defaults to a constant DISABLED for implementations without a relay.
     */
    val relayState: StateFlow<LinkConnectionState> get() = DISABLED_RELAY_STATE

    /**
     * Revoke the relay pairing at the gateway and tear down the relay pipe (the mobile
     * "Unpair"). Default no-op for providers without a relay.
     */
    suspend fun unpairRelay() {}
}

private val DISABLED_RELAY_STATE: StateFlow<LinkConnectionState> =
    MutableStateFlow(LinkConnectionState.DISABLED)

/**
 * The desktop-side seam: the ONE implementation of each link route body, shared
 * by the Ktor LAN server ([com.contextsolutions.mobileagent.link.DesktopLinkServer])
 * and the relay frame dispatcher. Authorization is the transport's concern (LAN
 * bearer / relay E2EE), so the handler trusts its caller.
 */
interface LinkRequestHandler {
    suspend fun handleUnary(request: LinkRequest): LinkResponse
    fun handleStream(request: LinkRequest): Flow<LinkStreamEvent>
}
