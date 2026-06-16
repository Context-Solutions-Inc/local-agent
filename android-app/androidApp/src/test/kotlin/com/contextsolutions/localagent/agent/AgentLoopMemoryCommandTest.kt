package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.classifier.ClassifierAccelerator
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.ClassifierOutput
import com.contextsolutions.localagent.classifier.PreflightConfig
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.classifier.QueryRewriter
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.inference.FinishReason
import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.ToolDispatcher
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.BraveSearchResult
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchService
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Locks the load-bearing contract introduced for the explicit memory-command
 * short-circuit: when the user prefixes a turn with "remember …" or
 * "forget …", the agent loop dispatches a deterministic acknowledgement
 * and the LLM is NEVER invoked.
 *
 * Bug being prevented: prior to the short-circuit, Gemma saw the add_todo
 * tool description and reliably called add_todo("favorite color is blue")
 * for prompts like "remember my favorite color is blue", producing both
 * a spurious todo AND an empty assistant bubble (no follow-up text after
 * the tool result).
 *
 * The actual memory save still happens downstream in
 * `MemoryExtractor.extract()` via the same `RememberForgetDetector` —
 * verified by `skipMemoryExtraction == false` on the emitted Done.
 */
class AgentLoopMemoryCommandTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var dao: SearchCacheDao

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        dao = SearchCacheDao(db.searchCacheQueries, nowEpochMs = { 1_000L })
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `remember prefix emits ack and never invokes the engine`() = runTest {
        val session = RecordingSession()
        val loop = newLoop(session)

        val events = loop.run(AgentTurnInput("remember my favorite color is blue")).toList()

        assertFalse("engine.generate must NOT be called on remember turns", session.invoked)
        val text = events.filterIsInstance<AgentEvent.TokenChunk>().joinToString("") { it.text }
        assertEquals("OK, I'll remember that.", text)
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        // skipMemoryExtraction MUST be false — downstream MemoryExtractor is
        // what actually writes the memory. If we set this true the explicit
        // command would acknowledge but save nothing.
        assertEquals(false, done.skipMemoryExtraction)
        assertEquals(2, done.turnMessages.size) // User + Assistant final
    }

    @Test
    fun `forget prefix emits ack and never invokes the engine`() = runTest {
        val session = RecordingSession()
        val loop = newLoop(session)

        val events = loop.run(AgentTurnInput("forget what I said about my favorite color")).toList()

        assertFalse("engine.generate must NOT be called on forget turns", session.invoked)
        val text = events.filterIsInstance<AgentEvent.TokenChunk>().joinToString("") { it.text }
        assertEquals("OK, I'll forget that.", text)
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals(false, done.skipMemoryExtraction)
    }

    @Test
    fun `a non-memory message still falls through to the engine`() = runTest {
        val session = RecordingSession()
        val loop = newLoop(session)

        loop.run(AgentTurnInput("what's the weather in Toronto")).toList()

        assertNotNull(session)
        assertEquals(
            "engine.generate must be invoked for non-memory turns",
            true,
            session.invoked,
        )
    }

    private fun newLoop(session: RecordingSession): AgentLoop {
        val service = SearchService(
            object : BraveKeyProvider { override fun currentKey(): String? = null },
            FakeBrave,
            dao,
        )
        val context = TimeContext(
            now = LocalDateTime(2026, 5, 15, 12, 0),
            timeZoneId = "UTC",
            timeZoneAbbreviation = "UTC",
            utcOffset = "+00:00",
        )
        val assembler = PromptAssembler(timeContextProvider = { context })
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = service,
            preflightRouter = unavailablePreflightRouter(service, context),
            // No tool handlers — memory short-circuit must work even when
            // the LLM path is fully wired up. Tests with handlers (Todo,
            // Clock) are covered separately; here we exercise the no-handler
            // path which exercises a slightly different code branch.
        )
    }

    private fun unavailablePreflightRouter(
        service: SearchService,
        timeContext: TimeContext,
    ): PreflightRouter {
        val emptyVocab = Vocab(
            tokenToId = mapOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102),
            idToToken = mapOf(0 to "[PAD]", 100 to "[UNK]", 101 to "[CLS]", 102 to "[SEP]"),
        )
        val noEngine = object : ClassifierEngine {
            override val isLoaded: Boolean = false
            override suspend fun warmUp(): ClassifierAccelerator? = null
            override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? = null
            override suspend fun unload() = Unit
        }
        return PreflightRouter(
            engine = noEngine,
            tokenizer = WordPieceTokenizer(emptyVocab),
            rewriter = QueryRewriter { timeContext },
            configProvider = { PreflightConfig.DEFAULT },
            searchAvailableProvider = { service.isAvailable() },
            logger = {},
        )
    }

    private class RecordingSession : InferenceSession {
        var invoked: Boolean = false
        override fun generate(request: GenerationRequest, toolDispatcher: ToolDispatcher?): Flow<GenerationEvent> = flow {
            invoked = true
            emit(GenerationEvent.TokenChunk("ok", 0))
            emit(GenerationEvent.Done(1, FinishReason.END_OF_TURN))
        }
    }

    private object FakeBrave : BraveSearchClient {
        override suspend fun search(query: String, apiKey: String): BraveSearchResult =
            BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "stub")
    }
}
