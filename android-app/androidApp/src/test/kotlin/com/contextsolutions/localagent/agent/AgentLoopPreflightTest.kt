package com.contextsolutions.localagent.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.classifier.ClassifierAccelerator
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.ClassifierOutput
import com.contextsolutions.localagent.classifier.PreflightConfig
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings
import com.contextsolutions.localagent.classifier.QueryRewriter
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.inference.FinishReason
import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.HistoryRole
import com.contextsolutions.localagent.inference.PendingToolCall
import com.contextsolutions.localagent.inference.SamplingParams
import com.contextsolutions.localagent.inference.ToolDispatcher
import com.contextsolutions.localagent.memory.EmbedderAccelerator
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.EmbedderOutput
import com.contextsolutions.localagent.memory.Memory
import com.contextsolutions.localagent.memory.MemoryCategory
import com.contextsolutions.localagent.memory.MemoryHit
import com.contextsolutions.localagent.memory.MemoryRetriever
import com.contextsolutions.localagent.memory.MemoryStore
import com.contextsolutions.localagent.preferences.DefaultSiteResolver
import com.contextsolutions.localagent.preferences.GpsCoordinates
import com.contextsolutions.localagent.preferences.LocationCatalog
import com.contextsolutions.localagent.preferences.SearchPreferencesRepository
import com.contextsolutions.localagent.preferences.SiteConfig
import com.contextsolutions.localagent.preferences.SourceKind
import com.contextsolutions.localagent.preferences.UserLocation
import com.contextsolutions.localagent.preferences.VerticalPreferences
import com.contextsolutions.localagent.preferences.WeatherLocationResolver
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.BraveSearchResult
import com.contextsolutions.localagent.search.FormattedSearchPayload
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchOutcome
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.search.SearchSource
import com.contextsolutions.localagent.search.SearchSubtype
import com.contextsolutions.localagent.search.vertical.GeneralSearchAdapter
import com.contextsolutions.localagent.search.vertical.VerticalSearchAdapter
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private lateinit var db: LocalAgentDatabase
    private val now: () -> Long = { 1_000L }

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
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
        // PR #103 — search-grounded LLM turns now render markdown (lists/bold/
        // links). Only deterministic WEATHER/FINANCE cards stay plain.
        assertTrue("search-grounded turn should render markdown", done.message.renderMarkdown)

        // The [SEARCH CONTEXT] block + pre-flight notice ride on the current
        // user turn (recency), NOT the system instruction.
        val request = session.requests.single()
        val sysInstruction = requireNotNull(request.systemInstruction)
        assertFalse(
            "search context must NOT be in the system prompt\n$sysInstruction",
            sysInstruction.contains("=== Search context for this turn ==="),
        )
        assertFalse("payload must not leak into system prompt", sysInstruction.contains("Eagles 28-22 win"))

        val tail = request.history.last()
        assertEquals(HistoryRole.USER, tail.role)
        assertTrue(
            "tail user turn should contain the SEARCH CONTEXT header\n${tail.text}",
            tail.text.contains("=== Search context for this turn ==="),
        )
        assertTrue("payload missing in current user turn", tail.text.contains("Eagles 28-22 win"))
        assertTrue(
            "pre-flight notice should appear together with the block",
            tail.text.contains("`[SEARCH CONTEXT]` block above"),
        )
        // Tools must NOT be advertised — LLM-side tool calling is disabled.
        assertTrue("tools list must be empty", request.tools.isEmpty())

        // Search-grounded turns decode near-greedy so figures (scores, prices)
        // copy out of the [SEARCH CONTEXT] verbatim instead of being perturbed.
        assertEquals(SamplingParams.GREEDY, request.sampling)

        // No synthetic tool history is injected: the engine sees the plain
        // user message (now carrying the search context) as the tail.
        val historyRoles = request.history.map { it.role }
        assertEquals(HistoryRole.USER, historyRoles.last())
        assertFalse(
            "no synthetic MODEL or TOOL entries in history",
            historyRoles.any { it == HistoryRole.MODEL || it == HistoryRole.TOOL },
        )
    }

    @Test
    fun sports_search_grounded_turn_renders_markdown() = runTest {
        // PR #103 — search-grounded LLM turns render markdown regardless of
        // subtype (lists/bold/links). Covers a SPORTS-routed query.
        val payload = FormattedSearchPayload(
            json = """[{"title":"NBA scores","url":"https://espn.com/nba","snippet":"..."}]""",
            sources = listOf(SearchSource("NBA scores", "https://espn.com/nba", "...")),
        )
        val client = FakeBraveSearchClient().apply { next = BraveSearchResult.Success(payload) }
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "**Lakers** beat the Celtics."))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(5f, 0f, 0f),
        )

        val events = loop.run(
            AgentTurnInput(userMessage = "nba scores tonight", history = emptyList()),
        ).toList()

        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals(1, done.message.citations.size)
        assertTrue("SPORTS turn should render markdown", done.message.renderMarkdown)
    }

    @Test
    fun news_search_grounded_turn_renders_markdown() = runTest {
        // PR #103 — covers a NEWS-routed query.
        val payload = FormattedSearchPayload(
            json = """[{"title":"Headline","url":"https://apnews.com/x","snippet":"..."}]""",
            sources = listOf(SearchSource("Headline", "https://apnews.com/x", "...")),
        )
        val client = FakeBraveSearchClient().apply { next = BraveSearchResult.Success(payload) }
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "Here's the latest."))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(5f, 0f, 0f),
        )

        val events = loop.run(
            AgentTurnInput(userMessage = "latest news on the election", history = emptyList()),
        ).toList()

        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals(1, done.message.citations.size)
        assertTrue("NEWS turn should render markdown", done.message.renderMarkdown)
    }

    @Test
    fun explicit_web_search_prefix_forces_search_on_stripped_query() = runTest {
        // Invariant #43 — a mid-band query that OPENS with "web search …" fires
        // anyway, and the command words are stripped before reaching Brave.
        val payload = FormattedSearchPayload(
            json = """[{"title":"AOSP","url":"https://source.android.com","snippet":"Android Open Source Project"}]""",
            sources = listOf(SearchSource("AOSP", "https://source.android.com", "Android Open Source Project")),
        )
        val client = FakeBraveSearchClient().apply { next = BraveSearchResult.Success(payload) }
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "It's source.android.com."))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(1f, 1f, 1f), // uniform → middle band (would NOT fire alone)
        )

        val events = loop.run(
            AgentTurnInput(
                userMessage = "web search the url of the android open source project",
                history = emptyList(),
            ),
        ).toList()

        // Search fired despite the middle band, and the dispatched query has the
        // "web search" command stripped.
        val started = events.filterIsInstance<AgentEvent.SearchStarted>().single()
        assertEquals("the url of the android open source project", started.query)
        assertEquals(1, client.callCount)

        // Search-grounded turn decodes near-greedy (same as any FireSearch path).
        val request = session.requests.single()
        assertEquals(SamplingParams.GREEDY, request.sampling)
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
        val events = loop.run(
            AgentTurnInput(userMessage = "explain photosynthesis", history = emptyList()),
        ).toList()
        // No pre-flight search, no notice block.
        assertEquals(0, client.callCount)
        val request = session.requests.single()
        assertFalse(requireNotNull(request.systemInstruction).contains("=== Search context for this turn ==="))
        // History tail is the user message — the M2 path.
        assertEquals(HistoryRole.USER, request.history.last().role)
        // Non-search turn keeps the engine's warm default sampling (no override).
        assertNull(request.sampling)
        // Model-composed answer (no search) renders markdown/LaTeX (PR #50).
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertTrue("model-only turn should render markdown", done.message.renderMarkdown)
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
        // in the current user turn's [SEARCH CONTEXT] block instead.
        val toolMessages = done.turnMessages.filterIsInstance<ChatMessage.Tool>()
        assertTrue("no synthetic tool messages", toolMessages.isEmpty())
        // Verify the error string reached the current user turn (not the system prompt).
        val request = session.requests.single()
        val tail = request.history.last()
        assertEquals(HistoryRole.USER, tail.role)
        assertTrue(tail.text.contains("=== Search context for this turn ==="))
        assertTrue(tail.text.contains("error: Network"))
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

    // -- PR #37: query-time weather location resolution -------------------

    @Test
    fun weather_with_no_city_asks_for_location_and_skips_search_and_engine() = runTest {
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(5f, 0f, 0f), storedCountry = "US")

        val events = loop.run(AgentTurnInput("what's the weather right now?")).toList()

        // No search, no engine — just the deterministic ask.
        assertTrue("adapter must not be hit", adapter.callCount == 0)
        assertTrue("no SearchStarted", events.filterIsInstance<AgentEvent.SearchStarted>().isEmpty())
        assertTrue("engine untouched", session.requests.isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals(Strings.ENGLISH.get(StringKeys.WEATHER_LOCATION_PROMPT), done.message.text)
        assertNull("nothing to remember when no city given", done.locationToRemember)
    }

    @Test
    fun weather_with_city_in_query_resolves_coords_and_remembers_location() = runTest {
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(5f, 0f, 0f), storedCountry = "US")

        val events = loop.run(AgentTurnInput("weather in Miami, Florida")).toList()

        // Adapter received Miami's coords; same-country keeps the stored
        // (US) weather source.
        assertEquals(25.7617, adapter.lastGps?.latitude)
        assertEquals("weather.gov", adapter.lastPrefs?.weather?.firstOrNull()?.domain)
        assertEquals("US", adapter.lastLocation?.country)
        assertTrue("engine untouched on weather direct path", session.requests.isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertTrue("rendered weather bubble", done.message.text.contains("Weather for Miami"))
        assertEquals("I live in Miami, Florida", done.locationToRemember)
    }

    @Test
    fun weather_for_city_in_another_country_uses_that_countrys_source() = runTest {
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        // User onboarded in the US, asks about a Canadian city.
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(5f, 0f, 0f), storedCountry = "US")

        loop.run(AgentTurnInput("weather in Toronto, Ontario")).toList()

        // Cross-country: Environment Canada source + Toronto coords.
        assertEquals(43.6532, adapter.lastGps?.latitude)
        assertEquals("weather.gc.ca", adapter.lastPrefs?.weather?.firstOrNull()?.domain)
        assertEquals("CA", adapter.lastLocation?.country)
    }

    @Test
    fun weather_intent_forces_search_even_when_classifier_underfires() = runTest {
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        // Classifier says NO search needed (low p_search_required) — the exact
        // middle-band miss seen in production for "what's the weather".
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(0f, 5f, 0f), storedCountry = "US")

        val events = loop.run(AgentTurnInput("what's the weather")).toList()

        // Despite the classifier, the weather path runs deterministically: with
        // no city + no saved location it asks the user, and the LLM is untouched.
        assertTrue("engine must not run on a weather turn", session.requests.isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals(Strings.ENGLISH.get(StringKeys.WEATHER_LOCATION_PROMPT), done.message.text)
    }

    @Test
    fun bare_weather_pattern_matches_only_tight_location_requests() {
        val p = AgentLoop.BARE_WEATHER_PATTERN
        // Accept: tight "use my location" requests.
        for (q in listOf(
            "what is the weather", "what's the weather", "whats the weather",
            "What is the weather today", "what's the weather tomorrow",
            "how's the weather right now", "weather", "weather today",
            "what is the weather like", "what's the forecast", "weather forecast today",
        )) {
            assertTrue("should match: \"$q\"", p.matches(q.trim()))
        }
        // Reject: general questions / anything naming a place.
        for (q in listOf(
            "what is the weather typically like in England",
            "what is the weather like in England",
            "why is the weather so weird lately",
            "weather in Miami", "weather patterns explained", "is the weather affecting flights",
        )) {
            assertFalse("should NOT match: \"$q\"", p.matches(q.trim()))
        }
    }

    @Test
    fun city_mention_without_weather_word_does_not_fire_weather() = runTest {
        // PR #89 — "what is the history of Miami" names a catalog city but is not a
        // weather question. It must NOT render a forecast; it reaches the LLM.
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Miami was founded in 1896..."))
        // Low p_search so nothing else fires either — isolates the weather gate.
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(0f, 5f, 0f), storedCountry = "US")

        val events = loop.run(AgentTurnInput("what is the history of Miami")).toList()

        assertTrue("weather adapter must not be hit", adapter.callCount == 0)
        assertTrue("engine must run — not a weather turn", session.requests.isNotEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertFalse("must not render a weather bubble", done.message.text.contains("Weather for"))
        assertFalse("must not ask for a city", done.message.text == Strings.ENGLISH.get(StringKeys.WEATHER_LOCATION_PROMPT))
    }

    @Test
    fun historical_weather_question_for_city_falls_through_to_llm() = runTest {
        // PR #89 — weather word + catalog city, BUT a PAST temporal marker
        // ("last year"). Historical weather isn't the live forecast, so it must
        // reach the LLM rather than render the deterministic bubble.
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Miami last year was warm..."))
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(0f, 5f, 0f), storedCountry = "US")

        val events = loop.run(AgentTurnInput("what was the weather like in Miami last year")).toList()

        assertTrue("weather adapter must not be hit", adapter.callCount == 0)
        assertTrue("engine must run — historical weather goes to the LLM", session.requests.isNotEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertFalse("must not render a weather bubble", done.message.text.contains("Weather for"))
    }

    @Test
    fun current_weather_for_city_with_future_marker_still_fires() = runTest {
        // PR #89 regression guard — a present/future weather question for a
        // catalog city still force-fires the deterministic forecast path.
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(0f, 5f, 0f), storedCountry = "US")

        val events = loop.run(AgentTurnInput("what's the weather in Miami tomorrow")).toList()

        assertEquals(25.7617, adapter.lastGps?.latitude)
        assertTrue("engine untouched on weather direct path", session.requests.isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertTrue("rendered weather bubble", done.message.text.contains("Weather for Miami"))
    }

    @Test
    fun general_weather_question_is_not_hijacked_by_the_weather_path() = runTest {
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "England is typically mild and rainy."))
        // Even with the classifier firing, a general "weather" question that
        // names no resolvable city and isn't a bare request must NOT be
        // answered from the saved location — it reaches the LLM.
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(5f, 0f, 0f), storedCountry = "US")

        val events = loop.run(
            AgentTurnInput("what is the weather typically like in England"),
        ).toList()

        assertTrue("engine should run for a general weather question", session.requests.isNotEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertFalse("must not ask for a city", done.message.text == Strings.ENGLISH.get(StringKeys.WEATHER_LOCATION_PROMPT))
        assertFalse("must not render a weather bubble", done.message.text.contains("Weather for"))
    }

    @Test
    fun ambiguous_city_without_saved_location_prompts_to_disambiguate() = runTest {
        // PR #89 — "weather in london" maps to London ON and London England.
        // With no saved location we must NOT guess; we prompt, listing both.
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        val loop = buildWeatherLocationLoop(session, service, dispatcher, floatArrayOf(5f, 0f, 0f), storedCountry = "US")

        val events = loop.run(AgentTurnInput("what is the weather like in london")).toList()

        assertTrue("adapter must not be hit", adapter.callCount == 0)
        assertTrue("engine must not run — we prompt", session.requests.isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertTrue("names the ambiguity\n${done.message.text}", done.message.text.contains("more than one place called London"))
        assertTrue("offers London, Ontario", done.message.text.contains("London, Ontario"))
        assertTrue("offers London, England", done.message.text.contains("London, England"))
        assertNull("nothing to remember when ambiguous", done.locationToRemember)
    }

    @Test
    fun ambiguous_city_resolved_from_saved_location_memory_renders_weather() = runTest {
        // PR #89 — a saved "I live in London, Ontario" memory disambiguates the
        // same ambiguous query without a prompt.
        val adapter = CapturingWeatherAdapter()
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.WEATHER to adapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        val loop = buildWeatherLocationLoopWithMemory(
            session, service, dispatcher, floatArrayOf(5f, 0f, 0f), storedCountry = "US",
            memories = listOf(memory("I live in London, Ontario")),
        )

        val events = loop.run(AgentTurnInput("what is the weather like in london")).toList()

        // Disambiguated to London, ON → Environment Canada source + ON coords.
        assertEquals(42.9849, adapter.lastGps?.latitude)
        assertEquals("weather.gc.ca", adapter.lastPrefs?.weather?.firstOrNull()?.domain)
        assertTrue("engine untouched on weather direct path", session.requests.isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertTrue("rendered London weather\n${done.message.text}", done.message.text.contains("Weather for London"))
        assertNull("already saved — don't re-propose remembering it", done.locationToRemember)
    }

    private fun memory(text: String): Memory = Memory(
        id = text.hashCode().toString(),
        text = text,
        category = MemoryCategory.PERSONAL_IDENTITY,
        conversationId = null,
        createdAtEpochMs = 1_000L,
        lastAccessedEpochMs = 1_000L,
        accessCount = 0,
        embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
        expiresAtEpochMs = null,
    )

    private fun buildWeatherLocationLoopWithMemory(
        session: InferenceSession,
        searchService: SearchService,
        verticalDispatcher: VerticalSearchDispatcher,
        preflightLogits: FloatArray,
        storedCountry: String,
        memories: List<Memory>,
    ): AgentLoop {
        val assembler = PromptAssembler(timeContextProvider = { timeContext })
        val router = PreflightRouter(
            engine = StubClassifierEngine(preflightLogits),
            tokenizer = WordPieceTokenizer(stubVocab),
            rewriter = QueryRewriter { timeContext },
            configProvider = { PreflightConfig.DEFAULT },
            searchAvailableProvider = { searchService.isAvailable() },
            logger = {},
        )
        val catalog = LocationCatalog(weatherCatalogJson)
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = searchService,
            preflightRouter = router,
            memoryRetriever = MemoryRetriever(
                embedder = AlwaysReadyEmbedder(),
                store = SeededMemoryStore(memories),
                nowProvider = { 1_000L },
            ),
            verticalDispatcher = verticalDispatcher,
            searchPreferences = FakeSearchPreferences(storedCountry),
            locationCatalog = catalog,
            weatherLocationResolver = WeatherLocationResolver(catalog),
            defaultSiteResolver = DefaultSiteResolver(weatherDefaultsJson),
            weatherResponseFormatter = WeatherResponseFormatter,
        )
    }

    private class AlwaysReadyEmbedder : EmbedderEngine {
        override val isLoaded: Boolean = true
        override suspend fun warmUp(): EmbedderAccelerator = EmbedderAccelerator.CPU
        override suspend fun embed(text: String): EmbedderOutput =
            EmbedderOutput(FloatArray(Memory.EMBEDDING_DIM) { 0f })
        override suspend fun unload() = Unit
    }

    private class SeededMemoryStore(private val memories: List<Memory>) : MemoryStore {
        override suspend fun insert(memory: Memory) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun deleteByCosine(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun retrieveTopK(
            queryEmbedding: FloatArray,
            k: Int,
            threshold: Double,
            now: Long,
        ): List<MemoryHit> = memories.take(k).map { MemoryHit(it, similarity = 0.9) }
        override suspend fun findCosineMatch(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun count(now: Long): Int = memories.size
        override suspend fun listForConversation(conversationId: String): List<Memory> = emptyList()
        override suspend fun countForConversation(conversationId: String): Int = 0
        override suspend fun listAll(): List<Memory> = memories
        override suspend fun deleteAll() = Unit
    }

    private fun buildWeatherLocationLoop(
        session: InferenceSession,
        searchService: SearchService,
        verticalDispatcher: VerticalSearchDispatcher,
        preflightLogits: FloatArray,
        storedCountry: String,
    ): AgentLoop {
        val assembler = PromptAssembler(timeContextProvider = { timeContext })
        val router = PreflightRouter(
            engine = StubClassifierEngine(preflightLogits),
            tokenizer = WordPieceTokenizer(stubVocab),
            rewriter = QueryRewriter { timeContext },
            configProvider = { PreflightConfig.DEFAULT },
            searchAvailableProvider = { searchService.isAvailable() },
            logger = {},
        )
        val catalog = LocationCatalog(weatherCatalogJson)
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = searchService,
            preflightRouter = router,
            verticalDispatcher = verticalDispatcher,
            searchPreferences = FakeSearchPreferences(storedCountry),
            locationCatalog = catalog,
            weatherLocationResolver = WeatherLocationResolver(catalog),
            defaultSiteResolver = DefaultSiteResolver(weatherDefaultsJson),
            weatherResponseFormatter = WeatherResponseFormatter,
        )
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
            stockResponseFormatter = StockResponseFormatter,
        )
    }

    @Test
    fun finance_stock_quote_renders_directly_and_skips_engine() = runTest {
        val quoteJson = """
            {"subtype":"stock_quote","query":"nvidia stock price","quote":{
              "symbol":"NVDA","name":"NVIDIA Corporation","exchange":"NASDAQ",
              "latest_price":131.26,"change":2.34,"change_percent":1.81}}
        """.trimIndent()
        val payload = FormattedSearchPayload(
            json = quoteJson,
            sources = listOf(SearchSource("NVIDIA Corporation (NVDA)", "https://stockanalysis.com/stocks/nvda/", "")),
        )
        val fakeAdapter = object : VerticalSearchAdapter {
            override suspend fun fetch(
                query: String,
                prefs: VerticalPreferences,
                location: UserLocation?,
                gps: GpsCoordinates?,
            ): SearchOutcome = SearchOutcome.Success(payload, fromCache = false)
        }
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.FINANCE to fakeAdapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Gemma should NOT speak."))
        val loop = buildWeatherDirectLoop(session, service, dispatcher, floatArrayOf(5f, 0f, 0f))

        val events = loop.run(AgentTurnInput("what is the stock price of Nvidia")).toList()

        assertTrue("engine must be untouched on finance direct path", session.requests.isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertFalse("Gemma's text leaked", done.message.text.contains("Gemma"))
        assertTrue("missing symbol", done.message.text.contains("NVDA"))
        assertTrue("missing price", done.message.text.contains("$131.26"))
        assertTrue("missing up arrow", done.message.text.contains("▲"))
        assertTrue("missing source", done.message.text.contains("stockanalysis.com"))
        assertTrue("skipMemoryExtraction must be set", done.skipMemoryExtraction)
        assertEquals(2, done.turnMessages.size)
    }

    @Test
    fun finance_fallback_snippet_payload_falls_through_to_llm() = runTest {
        // A fallback web-snippet payload has no "stock_quote" marker, so the
        // formatter declines and the turn goes to the LLM (today's behavior).
        val snippetJson = """[{"title":"NVDA","url":"https://finance.yahoo.com/quote/NVDA","snippet":"…"}]"""
        val payload = FormattedSearchPayload(
            json = snippetJson,
            sources = listOf(SearchSource("NVDA", "https://finance.yahoo.com/quote/NVDA", "…")),
        )
        val fakeAdapter = object : VerticalSearchAdapter {
            override suspend fun fetch(
                query: String,
                prefs: VerticalPreferences,
                location: UserLocation?,
                gps: GpsCoordinates?,
            ): SearchOutcome = SearchOutcome.Success(payload, fromCache = false)
        }
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val dispatcher = VerticalSearchDispatcher(
            adapters = mapOf(SearchSubtype.FINANCE to fakeAdapter),
            generalAdapter = GeneralSearchAdapter(service),
        )
        val session = RecordingSession(FakeSession(emitText = "Nvidia is trading around …"))
        val loop = buildWeatherDirectLoop(session, service, dispatcher, floatArrayOf(5f, 0f, 0f))

        loop.run(AgentTurnInput("nvidia stock price")).toList()

        assertTrue("engine should run for a fallback snippet payload", session.requests.isNotEmpty())
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

    /** Records what the dispatcher handed the weather adapter. Mirrors the
     *  real FeedAdapter: with no coords it can't fetch a forecast, so it
     *  errors (the GPS-templated source gets skipped); with coords it returns
     *  a renderable DWML-shaped payload so the direct path produces a bubble. */
    private class CapturingWeatherAdapter : VerticalSearchAdapter {
        var callCount = 0
        var lastGps: GpsCoordinates? = null
        var lastLocation: UserLocation? = null
        var lastPrefs: VerticalPreferences? = null
        override suspend fun fetch(
            query: String,
            prefs: VerticalPreferences,
            location: UserLocation?,
            gps: GpsCoordinates?,
        ): SearchOutcome {
            callCount++
            lastGps = gps
            lastLocation = location
            lastPrefs = prefs
            return if (gps == null) {
                SearchOutcome.Error(SearchOutcome.ErrorKind.Network, "no coords")
            } else {
                SearchOutcome.Success(RENDERABLE_WEATHER_PAYLOAD, fromCache = false)
            }
        }
    }

    private class FakeSearchPreferences(private val country: String) : SearchPreferencesRepository {
        // Stored (onboarded) weather source for the country = US NWS.
        private val prefs = VerticalPreferences(
            weather = listOf(
                SiteConfig("weather.gov", "NWS", SourceKind.DWML, "https://forecast.weather.gov/MapClick.php?lat={lat}&lon={lon}"),
            ),
        )
        override suspend fun snapshot(): VerticalPreferences = prefs
        override fun flow(): Flow<VerticalPreferences> = flow { emit(prefs) }
        override suspend fun location(): UserLocation = UserLocation(country, "", "")
        override suspend fun setLocation(location: UserLocation) = Unit
        override suspend fun setSites(subtype: SearchSubtype, sites: List<SiteConfig>) = Unit
        override suspend fun isOnboarded(): Boolean = true
    }

    private companion object {
        val RENDERABLE_WEATHER_PAYLOAD = FormattedSearchPayload(
            json = """
                {"sources":[{"domain":"weather.gov","url":"https://forecast.weather.gov/MapClick.php?lat=1&lon=2","payload":[
                  {"title":"Current Conditions: Sunny","description":"72°F","published":"2026-05-21T08:00:00-04:00"},
                  {"title":"Today: Sunny","description":"","published":"2026-05-21T08:00:00-04:00"}
                ]}]}
            """.trimIndent(),
            sources = listOf(SearchSource("NWS", "https://forecast.weather.gov/MapClick.php?lat=1&lon=2", "")),
        )

        val weatherCatalogJson = """
            {"countries":[
              {"code":"US","name":"United States","regions":[
                {"code":"FL","name":"Florida","cities":[{"name":"Miami","lat":25.7617,"lon":-80.1918}]}
              ]},
              {"code":"CA","name":"Canada","regions":[
                {"code":"ON","name":"Ontario","cities":[
                  {"name":"Toronto","lat":43.6532,"lon":-79.3832},
                  {"name":"London","lat":42.9849,"lon":-81.2453}
                ]}
              ]},
              {"code":"GB","name":"United Kingdom","regions":[
                {"code":"ENG","name":"England","cities":[{"name":"London","lat":51.5074,"lon":-0.1278}]}
              ]}
            ]}
        """.trimIndent()

        val weatherDefaultsJson = """
            {"fallback":"US","countries":{
              "US":{"weather":[{"domain":"weather.gov","displayName":"NWS","kind":"DWML","endpointTemplate":"https://forecast.weather.gov/MapClick.php?lat={lat}&lon={lon}&FcstType=dwml"}]},
              "CA":{"weather":[{"domain":"weather.gc.ca","displayName":"Environment Canada","kind":"RSS","endpointTemplate":"https://weather.gc.ca/rss/weather/{lat}_{lon}_e.xml"}]}
            }}
        """.trimIndent()
    }
}
