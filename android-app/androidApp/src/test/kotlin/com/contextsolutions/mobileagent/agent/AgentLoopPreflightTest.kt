package com.contextsolutions.mobileagent.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.classifier.ClassifierAccelerator
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.ClassifierOutput
import com.contextsolutions.mobileagent.classifier.PreflightConfig
import com.contextsolutions.mobileagent.classifier.PreflightRouter
import com.contextsolutions.mobileagent.classifier.QueryRewriter
import com.contextsolutions.mobileagent.classifier.Vocab
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.inference.FinishReason
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.HistoryRole
import com.contextsolutions.mobileagent.inference.PendingToolCall
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import com.contextsolutions.mobileagent.preferences.GpsCoordinates
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.BraveSearchResult
import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSource
import com.contextsolutions.mobileagent.search.SearchSubtype
import com.contextsolutions.mobileagent.search.vertical.GeneralSearchAdapter
import com.contextsolutions.mobileagent.search.vertical.VerticalSearchAdapter
import com.contextsolutions.mobileagent.search.vertical.VerticalSearchDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase D integration tests: exercise the full pre-flight injection path
 * through [AgentLoop]. The engine layer is faked (no LiteRT-LM); the pre-
 * flight router is the production class with a stub classifier engine
 * supplying scripted logits. Asserts:
 *
 *  - high-band query → SearchStarted with rewritten query, search runs,
 *    the system prompt picks up a `[SEARCH CONTEXT]` block carrying the
 *    fetched payload (LLM-side tool calling is fully disabled — no
 *    synthetic tool messages on the wire).
 *  - middle-band query → no notice block, no inline search.
 *  - search disabled → router short-circuits, no notice block.
 *  - search error during pre-flight → `[SEARCH CONTEXT]` block carries
 *    the error line and the agent keeps generating.
 */
class AgentLoopPreflightTest {

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

    private val timeContext = TimeContext(
        now = LocalDateTime(2026, 5, 10, 14, 32),
        timeZoneId = "America/Toronto",
        timeZoneAbbreviation = "EDT",
        utcOffset = "-04:00",
    )

    @Test
    fun high_band_fires_pre_flight_search_and_injects_notice_block() = runTest {
        val payload = FormattedSearchPayload(
            json = """[{"title":"Eagles 28-22 win","url":"https://espn.com/x","snippet":"Eagles beat..."}]""",
            sources = listOf(SearchSource("Eagles 28-22 win", "https://espn.com/x", "Eagles beat...")),
        )
        val client = FakeBraveSearchClient().apply { next = BraveSearchResult.Success(payload) }
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "Yes — Eagles won 28-22."))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(5f, 0f, 0f), // p_search_required ≈ 0.95
        )

        val events = loop.run(
            AgentTurnInput(userMessage = "did the eagles win last night", history = emptyList()),
        ).toList()

        // SearchStarted carries the rewriter's output (yesterday → 2026-05-09 evening).
        val started = events.filterIsInstance<AgentEvent.SearchStarted>().single()
        assertEquals("did the eagles win 2026-05-09 evening", started.query)
        assertEquals(1, client.callCount)

        val completed = events.filterIsInstance<AgentEvent.SearchCompleted>().single()
        assertTrue(completed.outcome is SearchOutcome.Success)

        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals(1, done.message.citations.size)
        assertEquals("https://espn.com/x", done.message.citations.single().url)
        assertTrue(done.message.text.contains("Eagles won"))

        // System prompt picks up the [SEARCH CONTEXT] block + pre-flight notice.
        val request = session.requests.single()
        val sysInstruction = requireNotNull(request.systemInstruction)
        assertTrue(
            "system prompt should contain the SEARCH CONTEXT header\n$sysInstruction",
            sysInstruction.contains("=== Search context for this turn ==="),
        )
        assertTrue("payload missing in system prompt", sysInstruction.contains("Eagles 28-22 win"))
        assertTrue(
            "pre-flight notice should appear together with the block",
            sysInstruction.contains("`[SEARCH CONTEXT]` block above"),
        )
        // Tools must NOT be advertised — LLM-side tool calling is disabled.
        assertTrue("tools list must be empty", request.tools.isEmpty())

        // No synthetic tool history is injected: the engine sees the plain
        // user message as the tail. Pre-flight context lives in the system
        // instruction only.
        val historyRoles = request.history.map { it.role }
        assertEquals(HistoryRole.USER, historyRoles.last())
        assertFalse(
            "no synthetic MODEL or TOOL entries in history",
            historyRoles.any { it == HistoryRole.MODEL || it == HistoryRole.TOOL },
        )
    }

    @Test
    fun middle_band_keeps_M2_path_no_notice_block() = runTest {
        val client = FakeBraveSearchClient()
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "Photosynthesis is..."))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(1f, 1f, 1f), // uniform → middle band
        )
        loop.run(
            AgentTurnInput(userMessage = "explain photosynthesis", history = emptyList()),
        ).toList()
        // No pre-flight search, no notice block.
        assertEquals(0, client.callCount)
        val request = session.requests.single()
        assertFalse(requireNotNull(request.systemInstruction).contains("=== Search context for this turn ==="))
        // History tail is the user message — the M2 path.
        assertEquals(HistoryRole.USER, request.history.last().role)
    }

    @Test
    fun low_band_keeps_M2_path_no_search() = runTest {
        val client = FakeBraveSearchClient()
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "ack"))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(0f, 5f, 0f), // p_search_required ≈ 0.005
        )
        loop.run(
            AgentTurnInput(userMessage = "what is photosynthesis", history = emptyList()),
        ).toList()
        assertEquals(0, client.callCount)
        val request = session.requests.single()
        assertFalse(requireNotNull(request.systemInstruction).contains("=== Search context for this turn ==="))
    }

    @Test
    fun search_disabled_short_circuits_router_and_skips_search() = runTest {
        val client = FakeBraveSearchClient()
        val service = SearchService(StubKeyProvider, client, dao, isEnabled = { false })
        val session = RecordingSession(FakeSession(emitText = "ack"))
        val loop = buildLoop(
            session = session,
            searchService = service,
            // Logits don't matter — router short-circuits before classify.
            preflightLogits = floatArrayOf(5f, 0f, 0f),
        )
        loop.run(
            AgentTurnInput(userMessage = "did the eagles win", history = emptyList()),
        ).toList()
        assertEquals(0, client.callCount)
        val request = session.requests.single()
        assertFalse(requireNotNull(request.systemInstruction).contains("=== Search context for this turn ==="))
    }

    @Test
    fun pre_flight_search_error_marks_tool_message_isError_and_continues() = runTest {
        val client = FakeBraveSearchClient().apply {
            next = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "no internet")
        }
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "I can't verify that right now."))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(5f, 0f, 0f),
        )
        val events = loop.run(
            AgentTurnInput(userMessage = "did the eagles win last night", history = emptyList()),
        ).toList()
        val completed = events.filterIsInstance<AgentEvent.SearchCompleted>().single()
        assertTrue(completed.outcome is SearchOutcome.Error)

        // Generation continues — Done is emitted with the error in context.
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertNotNull(done)
        // No synthetic tool messages in turnMessages now — the error lives
        // in the system instruction's [SEARCH CONTEXT] block instead.
        val toolMessages = done.turnMessages.filterIsInstance<ChatMessage.Tool>()
        assertTrue("no synthetic tool messages", toolMessages.isEmpty())
        // Verify the error string reached the system prompt.
        val request = session.requests.single()
        val sysInstruction = requireNotNull(request.systemInstruction)
        assertTrue(sysInstruction.contains("=== Search context for this turn ==="))
        assertTrue(sysInstruction.contains("error: Network"))
    }

    @Test
    fun weather_subtype_renders_directly_and_skips_engine() = runTest {
        // EC RSS-shaped fixture — alert + current + 2 forecast periods.
        // Matches the JSON envelope FeedAdapter emits.
        val torontoJson = """
            {
              "subtype":"weather",
              "query":"weather forecast for Toronto",
              "sources":[{
                "domain":"weather.gc.ca",
                "url":"https://weather.gc.ca/rss/weather/43.6532_-79.3832_e.xml",
                "snippet":"...",
                "payload":[
                  {"title":"YELLOW WARNING - HEAT, Toronto","description":"alert text","published":"2026-05-18T19:58:00Z"},
                  {"title":"Current Conditions: Mostly Cloudy, 29.7°C","description":"Condition: Mostly Cloudy Temperature: 29.7°C Humidex: 34 Wind: SW 26 km/h gust 48 km/h Humidity: 41 %","published":"2026-05-18T20:00:00Z"},
                  {"title":"Monday night: Chance of showers. Low 18. POP 40%","description":"forecast","published":"2026-05-18T19:30:00Z"},
                  {"title":"Tuesday: Chance of showers. High 29. POP 30%","description":"forecast","published":"2026-05-18T19:30:00Z"}
                ]
              }]
            }
        """.trimIndent()
        val weatherPayload = FormattedSearchPayload(
            json = torontoJson,
            sources = listOf(
                SearchSource(
                    title = "Environment Canada",
                    url = "https://weather.gc.ca/rss/weather/43.6532_-79.3832_e.xml",
                    snippet = "",
                ),
            ),
        )
        val fakeWeatherAdapter = object : VerticalSearchAdapter {
            override suspend fun fetch(
                query: String,
                prefs: VerticalPreferences,
                location: UserLocation?,
                gps: GpsCoordinates?,
            ): SearchOutcome = SearchOutcome.Success(weatherPayload, fromCache = false)
        }
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to fakeWeatherAdapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        val loop = buildWeatherDirectLoop(
            session = session,
            searchService = service,
            verticalDispatcher = dispatcher,
            preflightLogits = floatArrayOf(5f, 0f, 0f),
        )

        val events = loop.run(
            AgentTurnInput(userMessage = "weather forecast for Toronto", history = emptyList()),
        ).toList()

        // Gemma must NOT be invoked — the direct path returns before
        // session.generate() runs.
        assertTrue(
            "engine session must be untouched on weather direct path, got ${session.requests.size} requests",
            session.requests.isEmpty(),
        )

        // SearchStarted / SearchCompleted still fire so the UI shows the
        // "Searching..." indicator + citations.
        assertEquals(1, events.filterIsInstance<AgentEvent.SearchStarted>().size)
        val completed = events.filterIsInstance<AgentEvent.SearchCompleted>().single()
        assertTrue(completed.outcome is SearchOutcome.Success)

        // Done event carries the formatter output, not Gemma's text.
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertFalse("Gemma's text leaked into Done", done.message.text.contains("Gemma"))
        assertTrue("missing location header", done.message.text.contains("Weather for"))
        assertTrue("missing current temp", done.message.text.contains("29.7°C"))
        assertTrue("missing alert banner", done.message.text.contains("⚠"))
        assertTrue("missing forecast bullet", done.message.text.contains("• Monday night"))
        assertTrue("missing source citation", done.message.text.contains("weather.gc.ca"))
        assertTrue("skipMemoryExtraction must be set on weather direct path", done.skipMemoryExtraction)
        // turnMessages: just User + final Assistant. No synthetic tool
        // messages — there was no tool call.
        assertEquals(2, done.turnMessages.size)
    }

    private fun buildWeatherDirectLoop(
        session: InferenceSession,
        searchService: SearchService,
        verticalDispatcher: VerticalSearchDispatcher,
        preflightLogits: FloatArray,
    ): AgentLoop {
        val assembler = PromptAssembler(timeContextProvider = { timeContext })
        val engine = StubClassifierEngine(preflightLogits)
        val router = PreflightRouter(
            engine = engine,
            tokenizer = WordPieceTokenizer(stubVocab),
            rewriter = QueryRewriter { timeContext },
            configProvider = { PreflightConfig.DEFAULT },
            searchAvailableProvider = { searchService.isAvailable() },
            logger = {},
        )
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = searchService,
            preflightRouter = router,
            verticalDispatcher = verticalDispatcher,
            weatherResponseFormatter = WeatherResponseFormatter,
        )
    }

    // -- Test fixtures ------------------------------------------------------

    private fun buildLoop(
        session: InferenceSession,
        searchService: SearchService,
        preflightLogits: FloatArray,
    ): AgentLoop {
        val assembler = PromptAssembler(timeContextProvider = { timeContext })
        val engine = StubClassifierEngine(preflightLogits)
        val router = PreflightRouter(
            engine = engine,
            tokenizer = WordPieceTokenizer(stubVocab),
            rewriter = QueryRewriter { timeContext },
            configProvider = { PreflightConfig.DEFAULT },
            searchAvailableProvider = { searchService.isAvailable() },
            logger = {},
        )
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = searchService,
            preflightRouter = router,
        )
    }

    private val stubVocab = Vocab(
        tokenToId = mapOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102),
        idToToken = mapOf(0 to "[PAD]", 100 to "[UNK]", 101 to "[CLS]", 102 to "[SEP]"),
    )

    private object StubKeyProvider : BraveKeyProvider {
        override fun currentKey(): String = "test-key"
    }

    private class StubClassifierEngine(private val preflightLogits: FloatArray) : ClassifierEngine {
        override val isLoaded: Boolean = true
        override suspend fun warmUp(): ClassifierAccelerator = ClassifierAccelerator.CPU
        override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput =
            ClassifierOutput(
                preflightLogits = preflightLogits,
                presenceLogits = floatArrayOf(0f, 0f),
                categoryLogits = FloatArray(6),
            )
        override suspend fun unload() = Unit
    }

    private class FakeBraveSearchClient : BraveSearchClient {
        var next: BraveSearchResult = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "unset")
        var callCount: Int = 0
        override suspend fun search(query: String, apiKey: String): BraveSearchResult {
            callCount++
            return next
        }
    }

    private class FakeSession(
        private val emitText: String,
        private val toolCalls: List<PendingToolCall> = emptyList(),
    ) : InferenceSession {
        override fun generate(request: GenerationRequest, toolDispatcher: ToolDispatcher?): Flow<GenerationEvent> = flow {
            for (call in toolCalls) {
                toolDispatcher?.execute(call)
            }
            if (emitText.isNotEmpty()) emit(GenerationEvent.TokenChunk(emitText, 0))
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
}
