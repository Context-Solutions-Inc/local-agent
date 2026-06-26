package com.contextsolutions.localagent.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import com.contextsolutions.localagent.inference.ToolDispatcher
import com.contextsolutions.localagent.mylist.SqlDelightMyListRepository
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.BraveSearchResult
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchService
import kotlinx.coroutines.Dispatchers
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
 * M1 (security) — the text-marker fallback ([AgentLoop.runTextMarkerFallback])
 * must only *execute* a model-emitted clock/My-List tool when THIS turn is a
 * genuine clock/My-List intent. On any other turn — notably a search-grounded
 * turn whose prompt carried attacker-controlled web/RSS text that coaxed the 2B
 * model into emitting a `<|tool_call>…<tool_call|>` marker — the marker is
 * stripped from the reply but never dispatched, so indirect prompt injection
 * cannot mutate local My-List state.
 *
 * The one path where dispatch legitimately survives is an image turn: it bypasses
 * the deterministic clock/My-List short-circuits (so an intent reaches the LLM)
 * AND bypasses pre-flight/search (so it carries no injected web content).
 */
class AgentLoopMarkerFallbackTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: LocalAgentDatabase
    private lateinit var dao: SearchCacheDao
    private lateinit var myListRepo: SqlDelightMyListRepository
    private lateinit var handler: MyListToolHandler

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        db = LocalAgentDatabase(driver)
        dao = SearchCacheDao(db.searchCacheQueries, nowEpochMs = { 1_000L })
        myListRepo = SqlDelightMyListRepository(db.myListQueries, com.contextsolutions.localagent.sync.LocalChangeBus(), ioDispatcher = Dispatchers.Unconfined)
        handler = MyListToolHandler(myListRepo)
    }

    @After
    fun tearDown() = driver.close()

    @Test
    fun `injected delete marker on a non-intent turn is stripped, not executed`() = runTest {
        // Seed an item the marker would delete if it were ever dispatched.
        myListRepo.create(
            id = "t1",
            title = "buy milk",
            priority = com.contextsolutions.localagent.mylist.MyListItemPriority.MEDIUM,
            dueDateEpochMs = null,
            notes = null,
            nowEpochMs = 100L,
        )

        // A plain LLM turn (not a clock/My-List intent). The "model" emits a
        // delete marker — as if coaxed by injected search content.
        val marker = "Sure! <|tool_call>call:delete_mylist_item{index: 1}<tool_call|> done."
        val loop = newLoop(FakeSession(marker))

        val events = loop.run(AgentTurnInput("tell me a joke")).toList()

        // The item survives — the tool was NOT executed.
        assertEquals("delete marker must not be dispatched", 1, myListRepo.snapshot().size)
        assertEquals("buy milk", myListRepo.snapshot().single().title)
        // The raw marker never reaches the user.
        val finalAssistant = events.filterIsInstance<AgentEvent.Done>().single()
            .turnMessages.filterIsInstance<ChatMessage.Assistant>().last().text
        assertFalse("marker must be stripped from the reply", finalAssistant.contains("tool_call"))
    }

    @Test
    fun `add marker on an image turn with a my-list intent is still dispatched`() = runTest {
        // An image turn bypasses the deterministic My-List short-circuit AND
        // search, so a genuine "my list" intent reaches the LLM with no injected
        // content. A marker here is legitimate and must still execute.
        val marker = "<|tool_call>call:add_mylist_item{title: \"buy milk\"}<tool_call|>"
        val loop = newLoop(FakeSession(marker))

        loop.run(
            AgentTurnInput("add buy milk to my list", imageBytes = byteArrayOf(1, 2, 3)),
        ).toList()

        val saved = myListRepo.snapshot()
        assertEquals("add marker on a legit image+intent turn must be dispatched", 1, saved.size)
        assertEquals("buy milk", saved.single().title)
    }

    // -- helpers (mirror AgentLoopMyListTest) ---------------------------------

    private fun newLoop(session: InferenceSession): AgentLoop {
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
        return AgentLoop(
            session = session,
            assembler = PromptAssembler(timeContextProvider = { context }),
            searchService = service,
            preflightRouter = unavailablePreflightRouter(service, context),
            toolHandlers = listOf(handler),
        )
    }

    private fun unavailablePreflightRouter(service: SearchService, timeContext: TimeContext): PreflightRouter {
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

    private class FakeSession(private val emitText: String) : InferenceSession {
        override fun generate(request: GenerationRequest, toolDispatcher: ToolDispatcher?): Flow<GenerationEvent> = flow {
            emit(GenerationEvent.TokenChunk(emitText, 0))
            emit(GenerationEvent.Done(1, FinishReason.END_OF_TURN))
        }
    }

    private object FakeBrave : BraveSearchClient {
        override suspend fun search(query: String, apiKey: String): BraveSearchResult =
            BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "stub")
    }
}
