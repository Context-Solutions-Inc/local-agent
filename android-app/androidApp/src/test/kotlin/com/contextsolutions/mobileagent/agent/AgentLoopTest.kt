package com.contextsolutions.mobileagent.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.inference.FinishReason
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.PendingToolCall
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.BraveSearchResult
import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentLoopTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var dao: SearchCacheDao
    private lateinit var db: MobileAgentDatabase
    private val now: () -> Long = { 1_000L }

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        db = MobileAgentDatabase(driver)
        dao = SearchCacheDao(db.searchCacheQueries, nowEpochMs = now)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `no tool call streams text and emits Done with empty citations`() = runTest {
        val session = FakeSession(emitText = "Hi there.")
        val loop = newLoop(session, FakeBraveSearchClient())

        val events = loop.run(AgentTurnInput("hello")).toList()

        val text = events.filterIsInstance<AgentEvent.TokenChunk>().joinToString("") { it.text }
        assertEquals("Hi there.", text)
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals("Hi there.", done.message.text)
        assertTrue(done.message.citations.isEmpty())
    }

    @Test
    fun `tool dispatcher drives search and accumulates citations`() = runTest {
        val payload = FormattedSearchPayload(
            json = """[{"title":"ESPN","url":"https://espn.com","snippet":"x"}]""",
            sources = listOf(SearchSource("ESPN", "https://espn.com", "x")),
        )
        val client = FakeBraveSearchClient().apply { next = BraveSearchResult.Success(payload) }
        val session = FakeSession(
            beforeText = "Let me check.",
            toolCalls = listOf(PendingToolCall("web_search", """{"query":"eagles"}""")),
            afterText = " They won.",
        )
        val loop = newLoop(session, client)

        val events = loop.run(AgentTurnInput("did the eagles win?")).toList()

        val started = events.filterIsInstance<AgentEvent.SearchStarted>().single()
        assertEquals("eagles", started.query)
        val completed = events.filterIsInstance<AgentEvent.SearchCompleted>().single()
        assertTrue(completed.outcome is SearchOutcome.Success)
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals(1, done.message.citations.size)
        assertEquals("https://espn.com", done.message.citations.single().url)
        assertTrue(done.message.text.contains("They won"))
    }

    @Test
    fun `search error is surfaced and the loop still completes`() = runTest {
        val client = FakeBraveSearchClient().apply {
            next = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "boom")
        }
        val session = FakeSession(
            toolCalls = listOf(PendingToolCall("web_search", """{"query":"x"}""")),
            afterText = "Sorry — search failed.",
        )
        val loop = newLoop(session, client)

        val events = loop.run(AgentTurnInput("eagles")).toList()

        val completed = events.filterIsInstance<AgentEvent.SearchCompleted>().single()
        assertEquals(SearchOutcome.ErrorKind.Network, (completed.outcome as SearchOutcome.Error).kind)
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals("Sorry — search failed.", done.message.text)
        assertTrue(done.message.citations.isEmpty())
    }

    @Test
    fun `cap is enforced — beyond max, dispatcher returns the limit error string`() = runTest {
        val payload = FormattedSearchPayload(json = "[]", sources = emptyList())
        val client = FakeBraveSearchClient().apply { next = BraveSearchResult.Success(payload) }
        val capturedResponses = mutableListOf<String>()
        val session = FakeSession(
            toolCalls = listOf(
                PendingToolCall("web_search", """{"query":"a"}"""),
                PendingToolCall("web_search", """{"query":"b"}"""),
                PendingToolCall("web_search", """{"query":"c"}"""),
                // Fourth call should hit the cap.
                PendingToolCall("web_search", """{"query":"d"}"""),
            ),
            afterText = "ok",
            captureResponses = capturedResponses,
        )
        val loop = newLoop(session, client, maxToolCalls = 3)

        loop.run(AgentTurnInput("hi")).toList()

        // First three returned the search payload; fourth got the cap error.
        assertEquals(4, capturedResponses.size)
        assertTrue(capturedResponses[0].contains("[]"))
        assertTrue(capturedResponses[3].contains("tool call limit reached"))
    }

    @Test
    fun `engine error propagates to AgentEvent_Error`() = runTest {
        val session = FakeSession(error = "engine crashed")
        val loop = newLoop(session, FakeBraveSearchClient())

        val events = loop.run(AgentTurnInput("hi")).toList()

        val err = events.filterIsInstance<AgentEvent.Error>().single()
        assertEquals("engine crashed", err.message)
        assertFalse(events.any { it is AgentEvent.Done })
    }

    @Test
    fun `request carries system instruction and trailing user message`() = runTest {
        val session = RecordingSession(FakeSession(emitText = "ok"))
        val loop = newLoop(session, FakeBraveSearchClient())

        loop.run(AgentTurnInput("hello", history = listOf(ChatMessage.User("first"), ChatMessage.Assistant("noted")))).toList()

        val request = session.requests.single()
        assertEquals("hello", request.history.last().text)
        assertTrue("first" in request.history.map { it.text })
        assertTrue("=== Current date and time ===" in request.systemInstruction!!)
        assertFalse("<start_of_turn>" in request.systemInstruction!!)
    }

    @Test
    fun `tools registered when search available, empty when not`() = runTest {
        val session = FakeSession(emitText = "ok")
        val available = newLoop(session, FakeBraveSearchClient())
        val noKey = newLoop(
            FakeSession(emitText = "ok"),
            FakeBraveSearchClient(),
            keyProvider = object : BraveKeyProvider { override fun currentKey(): String? = null },
        )

        val r1 = RecordingSession(session).also { newLoop(it, FakeBraveSearchClient()).run(AgentTurnInput("a")).toList() }
        val r2 = RecordingSession(FakeSession(emitText = "ok")).also {
            newLoop(it, FakeBraveSearchClient(),
                keyProvider = object : BraveKeyProvider { override fun currentKey(): String? = null }).run(AgentTurnInput("a")).toList()
        }

        assertEquals(1, r1.requests.single().tools.size)
        assertTrue(r2.requests.single().tools.isEmpty())
    }

    private fun newLoop(
        session: InferenceSession,
        client: FakeBraveSearchClient,
        maxToolCalls: Int = 3,
        keyProvider: BraveKeyProvider = object : BraveKeyProvider {
            override fun currentKey(): String = "test-key"
        },
        searchService: SearchService? = null,
    ): AgentLoop {
        val service = searchService ?: SearchService(keyProvider, client, dao)
        val context = TimeContext(
            now = LocalDateTime(2026, 5, 7, 12, 0),
            timeZoneId = "America/Toronto",
            timeZoneAbbreviation = "EDT",
            utcOffset = "-04:00",
        )
        val assembler = PromptAssembler(timeContextProvider = { context })
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = service,
            maxToolCalls = maxToolCalls,
        )
    }

    /**
     * Scripts a sequence of tool calls then a final text emission. The dispatcher
     * is invoked once per scripted call; the captured response is recorded into
     * [captureResponses] (when provided) so tests can assert what the engine
     * "saw" from the agent loop.
     */
    private class FakeSession(
        private val beforeText: String = "",
        private val toolCalls: List<PendingToolCall> = emptyList(),
        private val afterText: String = "",
        private val emitText: String = "",
        private val error: String? = null,
        private val captureResponses: MutableList<String>? = null,
    ) : InferenceSession {

        override fun generate(request: GenerationRequest, toolDispatcher: ToolDispatcher?): Flow<GenerationEvent> = flow {
            if (error != null) {
                emit(GenerationEvent.Error(error))
                return@flow
            }
            if (beforeText.isNotEmpty()) emit(GenerationEvent.TokenChunk(beforeText, 0))
            for (call in toolCalls) {
                val response = toolDispatcher?.execute(call) ?: ""
                captureResponses?.add(response)
            }
            val tail = afterText.ifEmpty { emitText }
            if (tail.isNotEmpty()) emit(GenerationEvent.TokenChunk(tail, 0))
            emit(GenerationEvent.Done(1, FinishReason.END_OF_TURN))
        }
    }

    private class RecordingSession(private val delegate: InferenceSession) : InferenceSession {
        val requests = mutableListOf<GenerationRequest>()
        override fun generate(request: GenerationRequest, toolDispatcher: ToolDispatcher?): Flow<GenerationEvent> {
            requests.add(request)
            return delegate.generate(request, toolDispatcher)
        }
    }

    private class FakeBraveSearchClient : BraveSearchClient {
        var next: BraveSearchResult = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "unset")
        var callCount: Int = 0
        override suspend fun search(query: String, apiKey: String): BraveSearchResult {
            callCount++
            return next
        }
    }
}
