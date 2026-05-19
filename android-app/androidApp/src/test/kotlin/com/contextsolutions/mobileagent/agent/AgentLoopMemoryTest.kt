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
import com.contextsolutions.mobileagent.inference.PendingToolCall
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import com.contextsolutions.mobileagent.memory.EmbedderAccelerator
import com.contextsolutions.mobileagent.memory.EmbedderEngine
import com.contextsolutions.mobileagent.memory.EmbedderOutput
import com.contextsolutions.mobileagent.memory.Memory
import com.contextsolutions.mobileagent.memory.MemoryCategory
import com.contextsolutions.mobileagent.memory.MemoryHit
import com.contextsolutions.mobileagent.memory.MemoryRetriever
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.BraveSearchResult
import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchCacheDao
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

/**
 * M5 Phase C integration coverage: end-to-end through [AgentLoop] with the
 * memory subsystem wired in. The fake retriever supplies a seeded memory;
 * [QueryRewriter] should substitute the possessive and the system prompt
 * should pick up the §5 [MEMORY CONTEXT BLOCK].
 *
 * Companion to [AgentLoopPreflightTest], which covers the same shape minus
 * memories.
 */
class AgentLoopMemoryTest {

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

    private val eaglesMemory = Memory(
        id = "m-eagles",
        text = "my favorite nfl team is the philadelphia eagles",
        category = MemoryCategory.PREFERENCE,
        conversationId = null,
        createdAtEpochMs = 0L,
        lastAccessedEpochMs = 0L,
        accessCount = 0,
        embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
        expiresAtEpochMs = null,
    )

    @Test
    fun seeded_eagles_memory_lets_high_band_query_substitute_my_team() = runTest {
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
            retrievedMemories = listOf(eaglesMemory),
        )

        val events = loop.run(
            AgentTurnInput(userMessage = "did my team win last night", history = emptyList()),
        ).toList()

        // Possessive substitution + date pass.
        val started = events.filterIsInstance<AgentEvent.SearchStarted>().single()
        assertEquals("did philadelphia eagles win 2026-05-09 evening", started.query)
        assertEquals(1, client.callCount)

        // System prompt picks up the §5 memory block AND the [SEARCH CONTEXT] block.
        val request = session.requests.single()
        val sys = requireNotNull(request.systemInstruction)
        assertTrue("memory header missing\n$sys", sys.contains(PromptAssembler.MEMORY_CONTEXT_HEADER))
        assertTrue(
            "memory bullet missing\n$sys",
            sys.contains("- (preference) my favorite nfl team is the philadelphia eagles"),
        )
        assertTrue(
            "search context header missing",
            sys.contains("=== Search context for this turn ==="),
        )
    }

    @Test
    fun no_memories_preserves_M4_rewriter_abort_on_possessive() = runTest {
        val client = FakeBraveSearchClient()
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "I don't know which team you mean."))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(5f, 0f, 0f),
            retrievedMemories = emptyList(),
        )
        loop.run(
            AgentTurnInput(userMessage = "did my team win last night", history = emptyList()),
        ).toList()

        // No memories → rewriter aborts → no search → M2 path.
        assertEquals(0, client.callCount)
        val request = session.requests.single()
        val sys = requireNotNull(request.systemInstruction)
        assertFalse("memory block should be omitted", sys.contains(PromptAssembler.MEMORY_CONTEXT_HEADER))
        assertFalse(
            "no search context block on RewriterAbort",
            sys.contains("=== Search context for this turn ==="),
        )
    }

    @Test
    fun memory_block_renders_even_when_router_decides_skipSearch() = runTest {
        // Low-band query — no search runs, but if the retriever surfaced
        // a memory it should still appear in the prompt so Gemma can
        // personalise its answer.
        val client = FakeBraveSearchClient()
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "ack"))
        val loop = buildLoop(
            session = session,
            searchService = service,
            preflightLogits = floatArrayOf(0f, 5f, 0f), // p_search_required ≈ 0.005
            retrievedMemories = listOf(eaglesMemory),
        )
        loop.run(
            AgentTurnInput(userMessage = "what is photosynthesis", history = emptyList()),
        ).toList()

        assertEquals(0, client.callCount)
        val request = session.requests.single()
        val sys = requireNotNull(request.systemInstruction)
        assertTrue("memory block missing on SkipSearch path", sys.contains(PromptAssembler.MEMORY_CONTEXT_HEADER))
        assertFalse(
            "no search context block on SkipSearch",
            sys.contains("=== Search context for this turn ==="),
        )
    }

    @Test
    fun null_retriever_path_reproduces_M4_behavior_exactly() = runTest {
        // Verifies the back-compat path used by tests/clients that haven't
        // wired the memory retriever yet.
        val client = FakeBraveSearchClient()
        val service = SearchService(StubKeyProvider, client, dao)
        val session = RecordingSession(FakeSession(emitText = "ack"))
        val assembler = PromptAssembler(timeContextProvider = { timeContext })
        val engine = StubClassifierEngine(floatArrayOf(0f, 5f, 0f))
        val router = PreflightRouter(
            engine = engine,
            tokenizer = WordPieceTokenizer(stubVocab),
            rewriter = QueryRewriter { timeContext },
            configProvider = { PreflightConfig.DEFAULT },
            searchAvailableProvider = { service.isAvailable() },
            logger = {},
        )
        // memoryRetriever = null — same call shape M2/M4 used.
        val loop = AgentLoop(
            session = session,
            assembler = assembler,
            searchService = service,
            preflightRouter = router,
            memoryRetriever = null,
        )
        loop.run(
            AgentTurnInput(userMessage = "what is photosynthesis", history = emptyList()),
        ).toList()
        val sys = requireNotNull(session.requests.single().systemInstruction)
        assertFalse("no memory block when retriever is null", sys.contains(PromptAssembler.MEMORY_CONTEXT_HEADER))
    }

    // -- Test fixtures ------------------------------------------------------

    private fun buildLoop(
        session: InferenceSession,
        searchService: SearchService,
        preflightLogits: FloatArray,
        retrievedMemories: List<Memory>,
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
        val retriever = MemoryRetriever(
            embedder = AlwaysReadyEmbedder(),
            store = SeededMemoryStore(retrievedMemories),
            nowProvider = { 1_000L },
        )
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = searchService,
            preflightRouter = router,
            memoryRetriever = retriever,
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
        override suspend fun deleteExpired(now: Long): Int = 0
        override suspend fun selectLruEvictionCandidateIds(lastAccessedCutoff: Long, limit: Int): List<String> = emptyList()
    }

    private class FakeBraveSearchClient : BraveSearchClient {
        var next: BraveSearchResult = BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "unset")
        var callCount: Int = 0
        override suspend fun search(query: String, apiKey: String): BraveSearchResult {
            callCount++
            return next
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
