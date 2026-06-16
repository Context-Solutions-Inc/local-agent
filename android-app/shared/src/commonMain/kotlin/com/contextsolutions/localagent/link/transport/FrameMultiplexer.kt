package com.contextsolutions.localagent.link.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client side of the relay RPC (the mobile). Allocates a request [LinkFrame.id],
 * sends the request over the [RelayBytePipe], and correlates the response/stream
 * frames back to the caller. Many calls multiplex on the one pipe.
 *
 * When the pipe goes [LinkConnectionState.DOWN]/[LinkConnectionState.DISABLED] all
 * in-flight calls fail so the callers fall back to LAN/local (mirrors the LAN
 * transport's unreachable handling); the mobile sync loop then re-subscribes when
 * the pipe returns.
 */
class FrameMultiplexer(
    private val pipe: RelayBytePipe,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var counter = 0L
    private val pending = mutableMapOf<Long, CompletableDeferred<LinkFrame>>()
    private val streams = mutableMapOf<Long, Channel<LinkStreamEvent>>()

    fun start() {
        scope.launch {
            pipe.inbound.collect { bytes ->
                val frame = runCatching { LinkFrameCodec.decode(bytes) }.getOrNull() ?: return@collect
                route(frame)
            }
        }
        scope.launch {
            pipe.state.collect { state ->
                if (state != LinkConnectionState.UP) failAllInFlight()
            }
        }
    }

    suspend fun unary(request: LinkRequest): LinkResponse {
        val id = nextId()
        val deferred = CompletableDeferred<LinkFrame>()
        mutex.withLock { pending[id] = deferred }
        return try {
            pipe.send(LinkFrameCodec.encode(requestFrame(id, request)))
            val frame = deferred.await()
            LinkResponse(frame.status ?: 500, frame.body ?: "")
        } catch (t: Throwable) {
            LinkResponse(0, t.message ?: "relay unreachable")
        } finally {
            mutex.withLock { pending.remove(id) }
        }
    }

    fun serverStream(request: LinkRequest): Flow<LinkStreamEvent> = channelFlow {
        val id = nextId()
        val channel = Channel<LinkStreamEvent>(Channel.BUFFERED)
        mutex.withLock { streams[id] = channel }
        try {
            pipe.send(LinkFrameCodec.encode(requestFrame(id, request)))
            for (event in channel) {
                send(event)
                if (event is LinkStreamEvent.End || event is LinkStreamEvent.Error) break
            }
        } finally {
            mutex.withLock { streams.remove(id) }
            // Tell the server to stop (no-op if it already ended).
            runCatching { pipe.send(LinkFrameCodec.encode(LinkFrame(id = id, kind = FrameKind.CANCEL))) }
        }
    }

    private fun requestFrame(id: Long, request: LinkRequest) = LinkFrame(
        id = id,
        kind = FrameKind.REQUEST,
        method = request.method,
        query = request.query.takeIf { it.isNotEmpty() },
        body = request.body,
    )

    private suspend fun route(frame: LinkFrame) {
        when (frame.kind) {
            FrameKind.RESPONSE -> mutex.withLock { pending.remove(frame.id) }?.complete(frame)
            FrameKind.STREAM_DATA ->
                streamChannel(frame.id)?.trySend(LinkStreamEvent.Data(frame.body ?: ""))
            FrameKind.STREAM_END -> streamChannel(frame.id)?.let {
                it.trySend(LinkStreamEvent.End(frame.status ?: 200)); it.close()
            }
            FrameKind.STREAM_ERROR -> streamChannel(frame.id)?.let {
                it.trySend(LinkStreamEvent.Error(frame.status ?: 0, frame.body ?: "error")); it.close()
            }
            else -> Unit // the client never receives REQUEST/CANCEL frames
        }
    }

    private suspend fun failAllInFlight() {
        val (deferreds, channels) = mutex.withLock {
            val d = pending.values.toList(); pending.clear()
            val c = streams.values.toList(); streams.clear()
            d to c
        }
        deferreds.forEach { it.completeExceptionally(RelayUnreachable()) }
        channels.forEach { it.trySend(LinkStreamEvent.Error(0, "relay disconnected")); it.close() }
    }

    private suspend fun streamChannel(id: Long): Channel<LinkStreamEvent>? = mutex.withLock { streams[id] }

    private suspend fun nextId(): Long = mutex.withLock { ++counter }

    private class RelayUnreachable : RuntimeException("relay disconnected")
}
