package com.contextsolutions.localagent.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.app.spike.StubInferenceEngine
import com.contextsolutions.localagent.classifier.ClassifierAccelerator
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.ClassifierOutput
import com.contextsolutions.localagent.classifier.PreflightConfig
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.classifier.QueryRewriter
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.inference.FinishReason
import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.HistoryRole
import com.contextsolutions.localagent.inference.InferenceConfig
import com.contextsolutions.localagent.inference.ToolDispatcher
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.BraveSearchResult
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.todo.SqlDelightTodoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR #48 — image (vision) input path. Asserts the cross-cutting contract that
 * an attached photo:
 *  - bypasses the pre-flight classifier + search entirely (the question is
 *    about the image, not the web),
 *  - rides on the trailing user turn as `HistoryMessage.imageBytes`,
 *  - keeps the engine's warm default sampling (not the search-grounded GREEDY),
 *  - bypasses the deterministic clock/todo short-circuits even when the text
 *    would otherwise match them,
 *  - never leaks into future-turn history (ephemeral).
 */
class AgentLoopImageTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var dao: SearchCacheDao

    private val timeContext = TimeContext(
        now = LocalDateTime(2026, 5, 23, 12, 0),
        timeZoneId = "UTC",
        timeZoneAbbreviation = "UTC",
        utcOffset = "+00:00",
    )

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        dao = SearchCacheDao(db.searchCacheQueries, nowEpochMs = { 1_000L })
    }

    @After
    fun tearDown() = driver.close()

    @Test
    fun image_turn_skips_classifier_and_search_and_keeps_default_sampling() = runTest {
        val client = FakeBraveSearchClient()
        val service = SearchService(StubKeyProvider, client, dao)
        val classifier = RecordingClassifierEngine(floatArrayOf(5f, 0f, 0f)) // would normally fire search
        val session = RecordingSession(FakeSession(emitText = "That's a golden retriever."))
        val loop = buildLoop(session, service, classifier)

        val image = byteArrayOf(1, 2, 3, 4)
        val request = run {
            loop.run(
                AgentTurnInput(userMessage = "what's in this photo?", history = emptyList(), imageBytes = image),
            ).toList()
            session.requests.single()
        }

        // The classifier was never consulted, and no Brave search fired —
        // despite logits that would otherwise land in the high band.
        assertFalse("classifier must NOT run on an image turn", classifier.classifyCalled)
        assertEquals("no search on an image turn", 0, client.callCount)

        // The image rides on the trailing user turn.
        val tail = request.history.last()
        assertEquals(HistoryRole.USER, tail.role)
        assertTrue("trailing user turn must carry the image", tail.imageBytes != null)
        assertEquals(4, tail.imageBytes!!.size)

        // Warm defaults, not search-grounded GREEDY.
        assertNull("image turn keeps default sampling", request.sampling)
    }

    @Test
    fun image_turn_does_not_round_trip_into_future_history() = runTest {
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val session = RecordingSession(FakeSession(emitText = "ok"))
        val loop = buildLoop(session, service, RecordingClassifierEngine(floatArrayOf(0f, 5f, 0f)))

        val done = loop.run(
            AgentTurnInput(userMessage = "describe this", imageBytes = byteArrayOf(9, 9, 9)),
        ).toList().filterIsInstance<AgentEvent.Done>().single()

        // turnMessages becomes future history — the user turn there must be
        // text-only so the image never replays on a later turn (ephemeral).
        val user = done.turnMessages.filterIsInstance<ChatMessage.User>().single()
        assertNull("persisted/in-memory user turn must drop the image", user.imageBytes)
    }

    @Test
    fun image_turn_bypasses_clock_short_circuit_and_reaches_engine() = runTest {
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val todoRepo = SqlDelightTodoRepository(db.todosQueries, ioDispatcher = Dispatchers.Unconfined)

        // Control: clock-shaped text with NO image short-circuits (engine skipped).
        val controlSession = RecordingSession(FakeSession(emitText = "should not run"))
        buildLoop(controlSession, service, RecordingClassifierEngine(floatArrayOf(0f, 5f, 0f)), listOf(TodoToolHandler(todoRepo)))
            .run(AgentTurnInput("set a timer for 5 minutes")).toList()
        assertTrue("control: clock turn must skip the engine", controlSession.requests.isEmpty())

        // With an image, the same text bypasses the clock short-circuit and runs.
        val imageSession = RecordingSession(FakeSession(emitText = "It's a kitchen timer."))
        buildLoop(imageSession, service, RecordingClassifierEngine(floatArrayOf(0f, 5f, 0f)), listOf(TodoToolHandler(todoRepo)))
            .run(AgentTurnInput("set a timer for 5 minutes", imageBytes = byteArrayOf(7))).toList()
        assertTrue("image turn must reach the engine", imageSession.requests.isNotEmpty())
        assertTrue(imageSession.requests.single().history.last().imageBytes != null)
    }

    @Test
    fun prompt_assembler_carries_image_only_on_the_trailing_user_turn() {
        val assembler = PromptAssembler(timeContextProvider = { timeContext })
        val structured = assembler.assembleStructured(
            history = listOf(
                ChatMessage.User("earlier text-only turn"),
                ChatMessage.Assistant("a reply"),
                ChatMessage.User("look at this", imageBytes = byteArrayOf(1, 2)),
            ),
        )
        val tail = structured.history.last()
        assertEquals(HistoryRole.USER, tail.role)
        assertTrue("trailing user turn must carry the image", tail.imageBytes != null)
        // The earlier user turn stays text-only.
        val firstUser = structured.history.first { it.role == HistoryRole.USER }
        assertNull("prior user turn must have no image", firstUser.imageBytes)
    }

    @Test
    fun inference_config_vision_defaults_off() {
        assertFalse("vision must default off", InferenceConfig().enableVision)
        assertTrue(InferenceConfig(enableVision = true).enableVision)
    }

    @Test
    fun stub_engine_completes_and_echoes_an_image_request() = runTest {
        val stub = StubInferenceEngine(
            simulatedFirstTokenLatencyMs = 0,
            simulatedColdLoadMs = 0,
        )
        val handle = stub.loadModel("/stub", InferenceConfig())
        val request = GenerationRequest(
            history = listOf(
                com.contextsolutions.localagent.inference.HistoryMessage(
                    role = HistoryRole.USER,
                    text = "what is this",
                    imageBytes = byteArrayOf(1, 2, 3, 4, 5),
                ),
            ),
        )
        val events = stub.generate(handle, request, null).toList()
        val text = events.filterIsInstance<GenerationEvent.TokenChunk>().joinToString("") { it.text }
        assertTrue("stub should acknowledge the image", text.contains("received image of 5 bytes"))
        assertTrue(events.any { it is GenerationEvent.Done })
    }

    // -- fixtures (mirror AgentLoopPreflightTest) -----------------------------

    private fun buildLoop(
        session: InferenceSession,
        searchService: SearchService,
        classifier: ClassifierEngine,
        toolHandlers: List<ToolHandler> = emptyList(),
    ): AgentLoop {
        val assembler = PromptAssembler(timeContextProvider = { timeContext })
        val router = PreflightRouter(
            engine = classifier,
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
            toolHandlers = toolHandlers,
        )
    }

    private val stubVocab = Vocab(
        tokenToId = mapOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102),
        idToToken = mapOf(0 to "[PAD]", 100 to "[UNK]", 101 to "[CLS]", 102 to "[SEP]"),
    )

    private object StubKeyProvider : BraveKeyProvider {
        override fun currentKey(): String = "test-key"
    }

    private class RecordingClassifierEngine(private val logits: FloatArray) : ClassifierEngine {
        var classifyCalled = false
        override val isLoaded: Boolean = true
        override suspend fun warmUp(): ClassifierAccelerator = ClassifierAccelerator.CPU
        override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput {
            classifyCalled = true
            return ClassifierOutput(
                preflightLogits = logits,
                presenceLogits = floatArrayOf(0f, 0f),
                categoryLogits = FloatArray(6),
            )
        }
        override suspend fun unload() = Unit
    }

    private class FakeBraveSearchClient : BraveSearchClient {
        var callCount: Int = 0
        override suspend fun search(query: String, apiKey: String): BraveSearchResult {
            callCount++
            return BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "unused")
        }
    }

    private class FakeSession(private val emitText: String) : InferenceSession {
        override fun generate(request: GenerationRequest, toolDispatcher: ToolDispatcher?): Flow<GenerationEvent> = flow {
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
