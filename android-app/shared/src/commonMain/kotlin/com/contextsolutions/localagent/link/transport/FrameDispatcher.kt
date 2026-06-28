package com.contextsolutions.localagent.link.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 *
 * Resource bounds (security M5 / audit F4): the paired phone is lower-trust than
 * the desktop, yet every streaming REQUEST spawns an LLM generation or a desktop
 * subprocess and every unary REQUEST a coroutine. [maxConcurrentStreams] and
 * [maxConcurrentUnary] bound concurrent in-flight work per connection; excess
 * REQUESTs are rejected with a 429-style status ([STATUS_OVERLOADED]) instead of
 * launching. A duplicate (attacker-chosen) [LinkFrame.id] for a live stream is
 * rejected ([STATUS_DUPLICATE]) so a replay can't silently overwrite — and leak —
 * the prior [Job], nor kill the legit stream.
 *
 * Reconnect cleanup (PR #30, CLAUDE.md #77): the same pipe survives a peer going
 * away and returning (mobile backgrounded → [LinkConnectionState.DOWN] → UP), so
 * this server must drain its in-flight registry whenever the pipe leaves UP —
 * symmetric with the client [FrameMultiplexer.failAllInFlight]. Without it an idle
 * stream (the `SYNC_SUBSCRIBE` subscription blocks in `localChanges.collect` and
 * never `send`s, so it never trips the peer-offline send failure that frees a slot)
 * leaks one [maxConcurrentStreams] slot per background/foreground cycle until the
 * cap is exhausted and every new stream — including CHAT — is 429'd.
 */
class FrameDispatcher(
    private val pipe: RelayBytePipe,
    private val handler: LinkRequestHandler,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val maxConcurrentStreams: Int = DEFAULT_MAX_CONCURRENT_STREAMS,
    private val maxConcurrentUnary: Int = DEFAULT_MAX_CONCURRENT_UNARY,
) {
    private val mutex = Mutex()
    private val streamJobs = mutableMapOf<Long, Job>()
    private var unaryInFlight = 0

    fun start() {
        scope.launch {
            pipe.inbound.collect { bytes ->
                val frame = runCatching { LinkFrameCodec.decode(bytes) }.getOrNull() ?: return@collect
                onFrame(frame)
            }
        }
        scope.launch {
            // Release all in-flight work whenever the peer is no longer reachable (DOWN/DISABLED),
            // symmetric with FrameMultiplexer.failAllInFlight() on the client. The StateFlow replays
            // its current value on collect; an initial UP (or a drain of an empty registry) is a
            // harmless no-op. See the class doc (PR #30, CLAUDE.md #77).
            pipe.state.collect { state ->
                if (state != LinkConnectionState.UP) drainInFlight()
            }
        }
    }

    /**
     * Cancel + forget every in-flight stream and reset the unary counter so a reconnect on the
     * same pipe starts from an empty registry. Jobs are snapshotted + the maps cleared under the
     * mutex, then cancelled outside the lock; each cancelled [runStream]'s own `finally` re-`remove`s
     * under the mutex — a no-op on the already-cleared map.
     */
    private suspend fun drainInFlight() {
        val jobs = mutex.withLock {
            val snapshot = streamJobs.values.toList()
            streamJobs.clear()
            unaryInFlight = 0
            snapshot
        }
        jobs.forEach { it.cancel() }
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
            // Build the worker LAZY so the cap/duplicate decision + registration happen
            // atomically under the mutex BEFORE it can run (fixes the old launch-then-register
            // TOCTOU and the silent-overwrite leak). On rejection, cancel the unstarted job so
            // it never lingers as a scope child.
            val job = scope.launch(start = CoroutineStart.LAZY) { runStream(frame, request) }
            var inFlight = -1
            val reject: Int? = mutex.withLock {
                when {
                    streamJobs.containsKey(frame.id) -> STATUS_DUPLICATE
                    streamJobs.size >= maxConcurrentStreams -> STATUS_OVERLOADED
                    else -> { streamJobs[frame.id] = job; inFlight = streamJobs.size; null }
                }
            }
            if (reject == null) {
                logAccept("stream", frame.id, inFlight, maxConcurrentStreams)
                job.start()
            } else {
                job.cancel()
                logger("stream ${frame.id} rejected ($reject)")
                runCatching {
                    send(
                        LinkFrame(
                            id = frame.id,
                            kind = FrameKind.STREAM_ERROR,
                            status = reject,
                            body = if (reject == STATUS_DUPLICATE) """{"error":"duplicate id"}""" else """{"error":"too many concurrent streams"}""",
                        ),
                    )
                }
            }
        } else {
            var inFlight = -1
            val accepted = mutex.withLock {
                if (unaryInFlight >= maxConcurrentUnary) false else { unaryInFlight++; inFlight = unaryInFlight; true }
            }
            if (!accepted) {
                logger("unary ${frame.id} rejected (overloaded)")
                runCatching {
                    send(LinkFrame(id = frame.id, kind = FrameKind.RESPONSE, status = STATUS_OVERLOADED, body = """{"error":"too many concurrent requests"}"""))
                }
                return
            }
            logAccept("unary", frame.id, inFlight, maxConcurrentUnary)
            scope.launch {
                try {
                    val response = runCatching { handler.handleUnary(request) }
                        .getOrElse { LinkResponse(500, """{"error":"${it.message ?: "error"}"}""") }
                    // The response send can fail if the peer went offline mid-request (relay
                    // PeerOfflineException). Swallow it — symmetric with the streaming branch — so it
                    // can't escape this launch and reach the uncaught handler.
                    runCatching {
                        send(LinkFrame(id = frame.id, kind = FrameKind.RESPONSE, status = response.status, body = response.body))
                    }.onFailure { logger("unary ${frame.id} response send failed: ${it.message}") }
                } finally {
                    // coerceAtLeast(0): a concurrent drainInFlight() (peer went offline) may have
                    // already reset the counter to 0, so a late decrement here must not go negative.
                    mutex.withLock { unaryInFlight = (unaryInFlight - 1).coerceAtLeast(0) }
                }
            }
        }
    }

    private suspend fun runStream(frame: LinkFrame, request: LinkRequest) {
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

    /**
     * Observability for the concurrency caps: one line per accepted request with the
     * current in-flight count, escalated to a `WARN` once within [NEAR_CAP_MARGIN] of
     * the cap (i.e. the last two slots) so the log shows the desktop approaching the
     * limit before any 429 rejection actually fires.
     */
    private fun logAccept(kind: String, id: Long, inFlight: Int, max: Int) {
        if (inFlight >= max - NEAR_CAP_MARGIN) {
            logger("WARN $kind $id accepted ($inFlight/$max) — near concurrency cap")
        } else {
            logger("$kind $id accepted ($inFlight/$max)")
        }
    }

    private suspend fun send(frame: LinkFrame) = pipe.send(LinkFrameCodec.encode(frame))

    companion object {
        /** Max concurrent in-flight streams per connection (security M5 / audit F4). */
        const val DEFAULT_MAX_CONCURRENT_STREAMS = 8

        /** Max concurrent in-flight unary requests per connection. */
        const val DEFAULT_MAX_CONCURRENT_UNARY = 16

        /** 429-style: too many concurrent requests/streams. */
        const val STATUS_OVERLOADED = 429

        /** 409-style: a REQUEST reused a [LinkFrame.id] with a live stream. */
        const val STATUS_DUPLICATE = 409

        /** Accepts within this many slots of a cap are logged as WARN ("the last two slots"). */
        const val NEAR_CAP_MARGIN = 1
    }
}
