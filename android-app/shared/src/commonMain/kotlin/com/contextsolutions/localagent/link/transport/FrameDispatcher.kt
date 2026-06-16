package com.contextsolutions.localagent.link.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Server side of the relay RPC (the desktop). Reads framed requests off a
 * [RelayBytePipe], runs them through the shared [LinkRequestHandler] (the SAME one
 * the LAN Ktor server uses), and writes responses / stream frames back. Mirrors
 * the [FrameMultiplexer] on the client.
 *
 * Streaming requests get a per-[LinkFrame.id] [Job] so a client [FrameKind.CANCEL]
 * (or shutdown) tears the generation/subscription down. Each stream frame is
 * `send`-ed and awaited (peer ack) before the next, giving per-stream backpressure
 * across the relay.
 */
class FrameDispatcher(
    private val pipe: RelayBytePipe,
    private val handler: LinkRequestHandler,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val streamJobs = mutableMapOf<Long, Job>()

    fun start() {
        scope.launch {
            pipe.inbound.collect { bytes ->
                val frame = runCatching { LinkFrameCodec.decode(bytes) }.getOrNull() ?: return@collect
                onFrame(frame)
            }
        }
    }

    private suspend fun onFrame(frame: LinkFrame) {
        when (frame.kind) {
            FrameKind.REQUEST -> dispatchRequest(frame)
            FrameKind.CANCEL -> mutex.withLock { streamJobs.remove(frame.id) }?.cancel()
            else -> Unit // the server never receives RESPONSE/STREAM_* frames
        }
    }

    private suspend fun dispatchRequest(frame: LinkFrame) {
        val method = frame.method ?: run {
            send(LinkFrame(id = frame.id, kind = FrameKind.RESPONSE, status = 400, body = """{"error":"missing method"}"""))
            return
        }
        val request = LinkRequest(method, frame.body, frame.query ?: emptyMap())
        if (method.streaming) {
            val job = scope.launch {
                try {
                    handler.handleStream(request).collect { event ->
                        when (event) {
                            is LinkStreamEvent.Data ->
                                send(LinkFrame(id = frame.id, kind = FrameKind.STREAM_DATA, body = event.body))
                            is LinkStreamEvent.End ->
                                send(LinkFrame(id = frame.id, kind = FrameKind.STREAM_END, status = event.status))
                            is LinkStreamEvent.Error ->
                                send(LinkFrame(id = frame.id, kind = FrameKind.STREAM_ERROR, status = event.status, body = event.message))
                        }
                    }
                    // The handler stream completed without an explicit terminal
                    // (e.g. localChanges closed) — signal a clean end.
                    send(LinkFrame(id = frame.id, kind = FrameKind.STREAM_END, status = 200))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    logger("stream ${frame.id} failed: ${t.message}")
                    runCatching {
                        send(LinkFrame(id = frame.id, kind = FrameKind.STREAM_ERROR, status = 0, body = t.message ?: "stream failed"))
                    }
                } finally {
                    mutex.withLock { streamJobs.remove(frame.id) }
                }
            }
            mutex.withLock { streamJobs[frame.id] = job }
        } else {
            scope.launch {
                val response = runCatching { handler.handleUnary(request) }
                    .getOrElse { LinkResponse(500, """{"error":"${it.message ?: "error"}"}""") }
                // The response send can fail if the peer went offline mid-request (relay
                // PeerOfflineException). Swallow it — symmetric with the streaming branch — so it
                // can't escape this launch and reach the uncaught handler.
                runCatching {
                    send(LinkFrame(id = frame.id, kind = FrameKind.RESPONSE, status = response.status, body = response.body))
                }.onFailure { logger("unary ${frame.id} response send failed: ${it.message}") }
            }
        }
    }

    private suspend fun send(frame: LinkFrame) = pipe.send(LinkFrameCodec.encode(frame))
}
