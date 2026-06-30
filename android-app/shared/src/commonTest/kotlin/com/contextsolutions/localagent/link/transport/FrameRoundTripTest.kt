package com.contextsolutions.localagent.link.transport

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check of the relay RPC framing: a [FrameMultiplexer] (client) and a
 * [FrameDispatcher] (server) wired through two in-memory [RelayBytePipe]s, with a
 * fake [LinkRequestHandler]. Proves unary + streaming requests round-trip over the
 * single byte-pipe with id correlation — the contract both LAN and relay rely on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameRoundTripTest {

    /** Two cross-wired pipes: client.send → server.inbound, server.send → client.inbound. */
    private class PipePair {
        private val a2b = Channel<ByteArray>(Channel.UNLIMITED)
        private val b2a = Channel<ByteArray>(Channel.UNLIMITED)
        val clientState = MutableStateFlow(LinkConnectionState.UP)
        val serverState = MutableStateFlow(LinkConnectionState.UP)

        val client: RelayBytePipe = object : RelayBytePipe {
            override suspend fun send(bytes: ByteArray) = a2b.send(bytes)
            override val inbound: Flow<ByteArray> = b2a.receiveAsFlow()
            override val state: StateFlow<LinkConnectionState> = clientState
            override suspend fun close() {}
        }
        val server: RelayBytePipe = object : RelayBytePipe {
            override suspend fun send(bytes: ByteArray) = b2a.send(bytes)
            override val inbound: Flow<ByteArray> = a2b.receiveAsFlow()
            override val state: StateFlow<LinkConnectionState> = serverState
            override suspend fun close() {}
        }
    }

    private class FakeHandler : LinkRequestHandler {
        override suspend fun handleUnary(request: LinkRequest): LinkResponse = when (request.method) {
            LinkMethod.HEALTH -> LinkResponse(200, """{"status":"ok"}""")
            LinkMethod.SYNC_CHANGES -> LinkResponse(200, "since=${request.query["since"]}")
            else -> LinkResponse(200, "echo:${request.body}")
        }

        override fun handleStream(request: LinkRequest): Flow<LinkStreamEvent> = flow {
            emit(LinkStreamEvent.Data("a"))
            emit(LinkStreamEvent.Data("b"))
            emit(LinkStreamEvent.End(200))
        }
    }

    /** Handler whose stream emits its FIRST frame only after [delayMs] (virtual time). */
    private class DelayingHandler(private val delayMs: Long) : LinkRequestHandler {
        override suspend fun handleUnary(request: LinkRequest): LinkResponse = LinkResponse(200, "ok")

        override fun handleStream(request: LinkRequest): Flow<LinkStreamEvent> = flow {
            delay(delayMs)
            emit(LinkStreamEvent.Data("late"))
            emit(LinkStreamEvent.End(200))
        }
    }

    /** A pipe whose UP state never changes and whose sends vanish — no peer ever replies. */
    private class SilentPipe : RelayBytePipe {
        private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun send(bytes: ByteArray) = Unit // request reaches no server
        override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()
        override val state: StateFlow<LinkConnectionState> = MutableStateFlow(LinkConnectionState.UP)
        override suspend fun close() {}
    }

    /** A pipe whose send always fails (peer offline), mirroring the real byte pipes' propagation. */
    private class ThrowingSendPipe : RelayBytePipe {
        private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun send(bytes: ByteArray): Unit = throw RuntimeException("peer is offline")
        override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()
        override val state: StateFlow<LinkConnectionState> = MutableStateFlow(LinkConnectionState.UP)
        override suspend fun close() {}
    }

    @Test
    fun unaryRequestsRoundTrip() = runTest {
        val pipes = PipePair()
        FrameDispatcher(pipes.server, FakeHandler(), backgroundScope).start()
        val mux = FrameMultiplexer(pipes.client, backgroundScope).also { it.start() }

        val health = mux.unary(LinkRequest(LinkMethod.HEALTH))
        assertEquals(200, health.status)
        assertEquals("""{"status":"ok"}""", health.body)

        val changes = mux.unary(LinkRequest(LinkMethod.SYNC_CHANGES, query = mapOf("since" to "42")))
        assertEquals("since=42", changes.body)

        val upsert = mux.unary(LinkRequest(LinkMethod.SYNC_UPSERT, body = "{\"x\":1}"))
        assertEquals("echo:{\"x\":1}", upsert.body)
    }

    @Test
    fun streamingRequestRoundTrips() = runTest {
        val pipes = PipePair()
        FrameDispatcher(pipes.server, FakeHandler(), backgroundScope).start()
        val mux = FrameMultiplexer(pipes.client, backgroundScope).also { it.start() }

        val events = mux.serverStream(LinkRequest(LinkMethod.CHAT, body = "{}")).toList()

        val data = events.filterIsInstance<LinkStreamEvent.Data>().map { it.body }
        assertEquals(listOf("a", "b"), data)
        assertTrue(events.last() is LinkStreamEvent.End, "stream ends with End")
    }

    @Test
    fun unaryResponseSendFailureDoesNotEscape() = runTest {
        // The peer goes offline mid-request, so the RESPONSE send throws (the relay surfaces a
        // PeerOfflineException). The dispatcher runs that send in a fire-and-forget launch on the
        // app scope (SupervisorJob + Dispatchers.Default, no CoroutineExceptionHandler), so an
        // escaping throw reaches the thread's uncaught handler — the original bug:
        // "Exception in thread DefaultDispatcher-worker-N: peer is offline". We model that scope
        // here with a capturing CoroutineExceptionHandler: an escaping throw lands in `escaped`.
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val failingServer = object : RelayBytePipe {
            override suspend fun send(bytes: ByteArray) {
                throw RuntimeException("peer is offline")
            }
            override val inbound: Flow<ByteArray> = inbound.receiveAsFlow()
            override val state: StateFlow<LinkConnectionState> = MutableStateFlow(LinkConnectionState.UP)
            override suspend fun close() {}
        }
        val escaped = mutableListOf<Throwable>()
        val appScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, t -> escaped.add(t) },
        )
        FrameDispatcher(failingServer, FakeHandler(), appScope).start()

        inbound.send(LinkFrameCodec.encode(LinkFrame(id = 1, kind = FrameKind.REQUEST, method = LinkMethod.HEALTH)))
        advanceUntilIdle()

        assertTrue(escaped.isEmpty(), "send failure must not escape the dispatcher launch: $escaped")
        appScope.cancel()
    }

    @Test
    fun unaryTimesOutWhenServerNeverReplies() = runTest {
        // The pipe stays UP (so failAllInFlight never fires) but no response ever arrives —
        // the desync case. Without the first-frame timeout this awaited forever; now it must
        // fail with status 0 so the caller falls back on-device.
        val mux = FrameMultiplexer(SilentPipe(), backgroundScope).also { it.start() }
        val resp = mux.unary(LinkRequest(LinkMethod.HEALTH))
        assertEquals(0, resp.status, "a never-answered unary times out to status 0")
    }

    @Test
    fun unarySendFailureFailsTheRequest() = runTest {
        // The real byte pipes now PROPAGATE a send failure (previously swallowed). The
        // multiplexer must map it to status 0 rather than hang on the never-completed deferred.
        val mux = FrameMultiplexer(ThrowingSendPipe(), backgroundScope).also { it.start() }
        val resp = mux.unary(LinkRequest(LinkMethod.HEALTH))
        assertEquals(0, resp.status, "a send failure fails the request")
        assertTrue(resp.body.contains("offline"), "carries the failure message: ${resp.body}")
    }

    @Test
    fun chatStreamFirstFrameTimesOut() = runTest {
        // The first frame is delayed past the CHAT first-token budget (60s) → the stream must
        // fail with a status-0 Error so the caller falls back instead of hanging.
        val pipes = PipePair()
        FrameDispatcher(pipes.server, DelayingHandler(delayMs = 120_000), backgroundScope).start()
        val mux = FrameMultiplexer(pipes.client, backgroundScope).also { it.start() }

        val events = mux.serverStream(LinkRequest(LinkMethod.CHAT, body = "{}")).toList()

        val error = events.filterIsInstance<LinkStreamEvent.Error>().single()
        assertEquals(0, error.status, "timed-out chat first frame surfaces as status 0")
    }

    @Test
    fun inlineJobStreamIsNotFirstFrameTimedOut() = runTest {
        // RUN_JOB_INLINE emits its first frame only after the (long) subprocess run, so it must
        // be exempt from the first-frame timeout: a 120s wait (> CHAT's 60s) still delivers.
        val pipes = PipePair()
        FrameDispatcher(pipes.server, DelayingHandler(delayMs = 120_000), backgroundScope).start()
        val mux = FrameMultiplexer(pipes.client, backgroundScope).also { it.start() }

        val events = mux.serverStream(
            LinkRequest(LinkMethod.RUN_JOB_INLINE, query = mapOf("id" to "x")),
        ).toList()

        assertTrue(events.any { it is LinkStreamEvent.Data }, "inline job output is delivered")
        assertTrue(events.last() is LinkStreamEvent.End, "inline stream ends cleanly")
    }

    @Test
    fun concurrentRequestsAreCorrelatedById() = runTest {
        val pipes = PipePair()
        FrameDispatcher(pipes.server, FakeHandler(), backgroundScope).start()
        val mux = FrameMultiplexer(pipes.client, backgroundScope).also { it.start() }

        // A stream and a unary interleaved on the one pipe must not cross wires.
        val stream = mux.serverStream(LinkRequest(LinkMethod.SYNC_SUBSCRIBE))
        val unary = mux.unary(LinkRequest(LinkMethod.HEALTH))
        assertEquals(200, unary.status)
        val streamData = stream.toList().filterIsInstance<LinkStreamEvent.Data>().map { it.body }
        assertEquals(listOf("a", "b"), streamData)
    }
}
