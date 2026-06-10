package com.contextsolutions.mobileagent.link.transport

import com.securegateway.core.transport.ConnectionState
import com.securegateway.mobile.MobileClient
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [RelayBytePipe] over the Secure Gateway mobile SDK ([MobileClient]). Keeps all
 * `com.securegateway.*` types in androidMain (#23). Mirror of the desktop pipe.
 *
 * The SDK callbacks must be wired **before** `connect()`, so the factory
 * constructs this (after `pair`) and then calls `connect()`. Inbound frames arrive
 * on SDK threads → buffered through an unlimited [Channel].
 */
class AndroidRelayBytePipe(
    private val client: MobileClient,
    private val logger: (String) -> Unit = {},
) : RelayBytePipe {

    private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(LinkConnectionState.DOWN)
    private var inboundCount = 0L

    @Volatile
    private var closed = false

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
        // Drop sends once closed (unpair/teardown): in-flight frames racing the close would
        // otherwise hit the closing SDK client and surface errors for a dead session.
        if (closed) {
            logger("pipe: send dropped (closed), ${bytes.size}B")
            return
        }
        logger("pipe: send ${bytes.size}B (state=${_state.value})")
        client.send(bytes).awaitVoid()
    }

    override suspend fun unpair() {
        logger("pipe: unpair (revoke relay pairing at gateway)")
        withContext(Dispatchers.IO) { runCatching { client.unpair() } }
            .onFailure { logger("pipe: unpair failed: ${it.message}") }
    }

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    override val state: StateFlow<LinkConnectionState> = _state.asStateFlow()

    override suspend fun close() {
        closed = true
        logger("pipe: close()")
        runCatching { client.close() }
        inboundChannel.close()
        _state.value = LinkConnectionState.DISABLED
    }
}

internal fun ConnectionState.toLinkState(): LinkConnectionState = when (this) {
    ConnectionState.CONNECTED -> LinkConnectionState.UP
    ConnectionState.RECONNECTING, ConnectionState.PEER_OFFLINE -> LinkConnectionState.DOWN
    ConnectionState.REVOKED, ConnectionState.SUPERSEDED -> LinkConnectionState.DISABLED
}

internal suspend fun CompletableFuture<Void>.awaitVoid(): Unit =
    suspendCancellableCoroutine { cont ->
        whenComplete { _, error ->
            if (error != null) cont.resumeWithException(error) else cont.resume(Unit)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
