package com.contextsolutions.localagent.desktop.harness

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.agent.AgentEvent
import com.contextsolutions.localagent.agent.AgentLoop
import com.contextsolutions.localagent.agent.AgentTurnInput
import com.contextsolutions.localagent.agent.InferenceSession
import com.contextsolutions.localagent.agent.PromptAssembler
import com.contextsolutions.localagent.agent.currentTimeContext
import com.contextsolutions.localagent.classifier.NoOpClassifierEngine
import com.contextsolutions.localagent.classifier.PreflightConfig
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.classifier.QueryRewriter
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.InferenceConfig
import com.contextsolutions.localagent.inference.InferenceEngine
import com.contextsolutions.localagent.inference.LlamaServerBinaryStore
import com.contextsolutions.localagent.inference.LlamaServerInferenceEngine
import com.contextsolutions.localagent.inference.ModelHandle
import com.contextsolutions.localagent.inference.ToolDispatcher
import com.contextsolutions.localagent.platform.AgentClock
import com.contextsolutions.localagent.platform.LocaleProvider
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.BraveSearchResult
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/**
 * Phase-0 headless slice (docs/DESKTOP_PORT_PLAN.md): drive the real, unchanged
 * [AgentLoop] from commonMain on the JVM, generating with [LlamaServerInferenceEngine]
 * (llama.cpp `llama-server` subprocess / GGUF Gemma). Search + memory are disabled (NoOp aux engines,
 * search-unavailable), so this exercises the LLM path end-to-end with zero UI/DI.
 *
 * Run:
 *   GEMMA_GGUF_PATH=/path/to/gemma-*-it.gguf ./gradlew :desktopHarness:run --args="your prompt"
 *
 * Without GEMMA_GGUF_PATH set, it prints how to run (the wiring still compiles +
 * constructs, proving the agent loop is desktop-portable).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val clock = AgentClock()
    val locale = LocaleProvider()

    val engine: InferenceEngine = LlamaServerInferenceEngine(binaryStore = LlamaServerBinaryStore())

    // --- Build the real AgentLoop with search/memory disabled. ---
    val assembler = PromptAssembler(timeContextProvider = { currentTimeContext(clock, locale) })
    val rewriter = QueryRewriter(timeContextProvider = { currentTimeContext(clock, locale) })
    // Tokenizer is constructed but never invoked: searchAvailable=false short-circuits
    // the router before tokenization. A minimal vocab keeps Phase 0 free of asset loading.
    val tokenizer = WordPieceTokenizer(Vocab.fromLines(sequenceOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")))
    val preflightRouter = PreflightRouter(
        engine = NoOpClassifierEngine(),
        tokenizer = tokenizer,
        rewriter = rewriter,
        configProvider = { PreflightConfig.DEFAULT },
        searchAvailableProvider = { false },
    )

    // In-memory LocalAgentDatabase via the JDBC driver — also validates the SQLDelight
    // JVM driver seam (Phase 6). Only the search cache is touched, and search is disabled.
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalAgentDatabase.Schema.create(it) }
    val db = LocalAgentDatabase(driver)
    val searchService = SearchService(
        keyProvider = object : BraveKeyProvider { override fun currentKey(): String? = null },
        client = object : BraveSearchClient {
            override suspend fun search(query: String, apiKey: String): BraveSearchResult =
                error("search disabled in Phase 0 harness")
        },
        cache = SearchCacheDao(db.searchCacheQueries, nowEpochMs = { clock.nowEpochMs() }),
        isEnabled = { false },
    )

    val modelPath = System.getenv("GEMMA_GGUF_PATH")
    if (modelPath.isNullOrBlank()) {
        println(
            """
            |[desktopHarness] Wiring constructed OK — AgentLoop + LlamaServerInferenceEngine are desktop-portable.
            |Set GEMMA_GGUF_PATH to run real inference:
            |  GEMMA_GGUF_PATH=/path/to/gemma-3-4b-it-Q4_K_M.gguf ./gradlew :desktopHarness:run --args="capital of France?"
            """.trimMargin()
        )
        return@runBlocking
    }

    println("[desktopHarness] Loading $modelPath …")
    val handle = engine.loadModel(modelPath, InferenceConfig(enableVision = false))
    println("[desktopHarness] Loaded on ${handle.activeAccelerator}. Generating…\n")

    val session = HarnessInferenceSession(engine, handle)
    val loop = AgentLoop(
        session = session,
        assembler = assembler,
        searchService = searchService,
        preflightRouter = preflightRouter,
    )

    val prompt = args.joinToString(" ").ifBlank { "In one sentence, what is the capital of France?" }
    println("> $prompt\n")

    loop.run(AgentTurnInput(userMessage = prompt)).collect { event ->
        when (event) {
            is AgentEvent.TokenChunk -> { print(event.text); System.out.flush() }
            is AgentEvent.Done -> println("\n\n[done]")
            is AgentEvent.Error -> println("\n[error] ${event.message}")
            else -> {}
        }
    }
    engine.unload(handle) // stops the llama-server subprocess
    // Ktor/CIO keeps a selector thread pool alive; force a clean exit for the headless CLI.
    kotlin.system.exitProcess(0)
}

/** Minimal [InferenceSession] over a loaded model handle. */
private class HarnessInferenceSession(
    private val engine: InferenceEngine,
    private val handle: ModelHandle,
) : InferenceSession {
    override fun generate(
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> = engine.generate(handle, request, toolDispatcher)
}
