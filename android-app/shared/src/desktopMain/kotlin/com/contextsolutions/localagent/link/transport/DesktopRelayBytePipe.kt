package com.contextsolutions.localagent.link.transport

import com.contextsolutions.securegateway.core.transport.ConnectionState
import com.contextsolutions.securegateway.desktop.DesktopClient
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [RelayBytePipe] over the Secure Gateway desktop SDK ([DesktopClient]). Keeps all
 * `com.contextsolutions.securegateway.*` types in desktopMain (#23).
 *
 * The SDK callbacks (`onMessage`, `onStateChange`) must be wired **before**
 * `connect()`, so this wraps an unconnected client; the caller
 * ([DesktopRelayHost]) calls `connect()` after constructing the pipe. Inbound
 * frames arrive on SDK threads → buffered through an unlimited [Channel] so the
 * coroutine collector never drops one.
 */
class DesktopRelayBytePipe(
    private val client: DesktopClient,
    private val logger: (String) -> Unit = {},
) : RelayBytePipe {

    private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(LinkConnectionState.DOWN)
    private var inboundCount = 0L

    init {
        client.onMessage { bytes ->
            inboundCount++
            if (inboundCount <= 3L || inboundCount % 50L == 0L) {
                logger("pipe: inbound #$inboundCount (${bytes.size}B)")
            }
            inboundChannel.trySend(bytes)
        }
        client.onStateChange { state ->
            val mapped = state.toLinkState()
            logger("pipe: state $state -> $mapped")
            _state.value = mapped
        }
    }

    override suspend fun send(bytes: ByteArray) {
        logger("pipe: send ${bytes.size}B (state=${_state.value})")
        try {
            client.send(bytes).awaitVoid()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Peer offline / connection lost / revoked mid-send (e.g. the phone unpaired).
            // Propagate (symmetric with the mobile pipe) so the FrameDispatcher stops pumping a
            // stream to a dead peer instead of swallowing each frame. Its response/stream sends
            // already wrap this in runCatching / catch, so the throw can't escape uncaught.
            logger("pipe: send failed (${t::class.simpleName}: ${t.message}); propagating")
            throw t
        }
    }

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    override val state: StateFlow<LinkConnectionState> = _state.asStateFlow()

    override suspend fun close() {
        logger("pipe: close()")
        runCatching { client.close() }
        inboundChannel.close()
        _state.value = LinkConnectionState.DISABLED
    }
}

internal fun ConnectionState.toLinkState(): LinkConnectionState = when (this) {
    ConnectionState.CONNECTED -> LinkConnectionState.UP
    ConnectionState.RECONNECTING, ConnectionState.PEER_OFFLINE -> LinkConnectionState.DOWN
    // Terminal (close 4001/4004) — disable so callers fall back to LAN/local.
    ConnectionState.REVOKED, ConnectionState.SUPERSEDED -> LinkConnectionState.DISABLED
}

/** Bridge the SDK's `CompletableFuture<Void>` (peer ack) into a cancellable suspend. */
internal suspend fun CompletableFuture<Void>.awaitVoid(): Unit =
    suspendCancellableCoroutine { cont ->
        whenComplete { _, error ->
            if (error != null) cont.resumeWithException(error) else cont.resume(Unit)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
