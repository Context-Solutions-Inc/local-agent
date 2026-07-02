package com.contextsolutions.localagent.link.transport

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [RelayBytePipe] over the Swift Secure Gateway `MobileClient` via [NativeRelaySession]. Keeps all
 * SDK types in Swift (#23). Mirror of `AndroidRelayBytePipe`.
 *
 * The SDK callbacks are wired in `init` (before `connect()`), so the factory constructs this after
 * `pair`/reconnect and then calls [connect]. Inbound frames arrive on SDK threads → buffered
 * through an unlimited [Channel]. The Swift bridge adapts async `pair/connect/send/unpair` into
 * exactly-one-of callbacks, which we bridge back to `suspend` (the inverse of `LiteRtIosInferenceEngine`).
 */
class IosRelayBytePipe(
    private val session: NativeRelaySession,
    private val logger: (String) -> Unit = {},
) : RelayBytePipe {

    private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(LinkConnectionState.DOWN)
    private var inboundCount = 0L

    @kotlin.concurrent.Volatile
    private var closed = false

    init {
        session.setCallbacks(
            onMessage = { bytes ->
                inboundCount++
                if (inboundCount <= 3L || inboundCount % 50L == 0L) {
                    logger("pipe: inbound #$inboundCount (${bytes.size}B)")
                }
                inboundChannel.trySend(bytes)
            },
            onStateChange = { code ->
                val mapped = code.toLinkState()
                logger("pipe: state $code -> $mapped")
                _state.value = mapped
            },
        )
    }

    /**
     * Kick off the token-issue + wss dial. Suspends for the synchronous token-issue step; the socket
     * opens asynchronously and its outcome surfaces via [state]. Called by the factory after
     * pair/reconnect (callbacks already wired in `init`).
     */
    internal suspend fun connect() = suspendCancellableCoroutine<Unit> { cont ->
        session.connect(
            onDone = { if (cont.isActive) cont.resume(Unit) },
            onError = { msg -> if (cont.isActive) cont.resumeWithException(IllegalStateException(msg)) },
        )
    }

    override suspend fun send(bytes: ByteArray) {
        // Drop sends once closed (unpair/teardown): a frame racing the close would otherwise hit the
        // closing SDK client and surface errors for a dead session.
        if (closed) {
            logger("pipe: send dropped (closed), ${bytes.size}B")
            return
        }
        logger("pipe: send ${bytes.size}B (state=${_state.value})")
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                session.send(
                    bytes,
                    onAck = { if (cont.isActive) cont.resume(Unit) },
                    onError = { msg -> if (cont.isActive) cont.resumeWithException(IllegalStateException(msg)) },
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Peer offline / connection lost / revoked mid-send. Propagate so the FrameMultiplexer
            // fails the in-flight request and the caller falls back to on-device (mirrors Android).
            logger("pipe: send failed (${t.message}); propagating")
            throw t
        }
    }

    override suspend fun unpair() {
        logger("pipe: unpair (revoke relay pairing at gateway)")
        // Swallow failures (like Android's runCatching { client.unpair() }).
        suspendCancellableCoroutine<Unit> { cont ->
            session.unpair(
                onDone = { if (cont.isActive) cont.resume(Unit) },
                onError = { msg ->
                    logger("pipe: unpair failed: $msg")
                    if (cont.isActive) cont.resume(Unit)
                },
            )
        }
    }

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    override val state: StateFlow<LinkConnectionState> = _state.asStateFlow()

    override suspend fun close() {
        closed = true
        logger("pipe: close()")
        runCatching { session.close() }
        inboundChannel.close()
        _state.value = LinkConnectionState.DISABLED
    }
}

/** State map — identical to `AndroidRelayBytePipe.toLinkState`. */
internal fun Int.toLinkState(): LinkConnectionState = when (this) {
    NativeRelayState.CONNECTED -> LinkConnectionState.UP
    NativeRelayState.RECONNECTING, NativeRelayState.PEER_OFFLINE -> LinkConnectionState.DOWN
    else -> LinkConnectionState.DISABLED // REVOKED, SUPERSEDED (terminal)
}
