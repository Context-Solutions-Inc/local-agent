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
import com.contextsolutions.localagent.job.InlineJobResult
import com.contextsolutions.localagent.job.InlineJobRunner
import com.contextsolutions.localagent.job.Job
import com.contextsolutions.localagent.job.JobRepository
import com.contextsolutions.localagent.job.JobRun
import com.contextsolutions.localagent.job.JobRunStatus
import com.contextsolutions.localagent.job.JobScheduleType
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.BraveSearchResult
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR #88 (#59) — the "run job <name> <keywords>" inline command short-circuits in
 * the AgentLoop: it resolves a job by name, runs it via the [InlineJobRunner]
 * seam, injects the output as grounding context, and the LLM answers in the
 * current turn. Not-found / failure short-circuit with a deterministic message
 * and never touch the engine.
 */
class AgentLoopRunJobTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var dao: SearchCacheDao
    private lateinit var db: LocalAgentDatabase

    private val timeContext = TimeContext(
        now = LocalDateTime(2026, 5, 10, 14, 32),
        timeZoneId = "America/Toronto",
        timeZoneAbbreviation = "EDT",
        utcOffset = "-04:00",
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
    fun run_job_resolves_runs_and_renders_output_directly() = runTest {
        val runner = FakeInlineJobRunner(InlineJobResult.Output("3 listings near Westport [link](https://x)"))
        val session = RecordingSession(FakeSession(emitText = "the LLM must NOT run"))
        val loop = buildLoop(session, runner, listOf(propertySearchJob))

        val events = loop.run(AgentTurnInput("run job property search Westport, Ontario")).toList()

        // The runner ran the resolved job with the keyword(s) from the message.
        assertEquals("job-prop", runner.lastId)
        assertEquals("Westport, Ontario", runner.lastKeywords)

        // The in-progress indicator fired with the job's display name.
        val started = events.filterIsInstance<AgentEvent.JobStarted>().single()
        assertEquals("Property Search", started.jobName)

        // The output renders DIRECTLY — the LLM is never invoked.
        assertTrue("engine must be untouched on direct render", session.requests.isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals("3 listings near Westport [link](https://x)", done.message.text)
        // Markdown is rendered so links/tables format (PR #82) — NOT plain.
        assertTrue("job output must render markdown", done.message.renderMarkdown)
        assertTrue(done.skipMemoryExtraction)
    }

    @Test
    fun empty_keywords_fall_back_to_saved_prompt() = runTest {
        val runner = FakeInlineJobRunner(InlineJobResult.Output("done"))
        val session = RecordingSession(FakeSession(emitText = "ok"))
        val loop = buildLoop(session, runner, listOf(propertySearchJob))

        loop.run(AgentTurnInput("run job property search")).toList()

        assertEquals("saved-keywords", runner.lastKeywords)
    }

    @Test
    fun unknown_job_name_short_circuits_without_running_or_engine() = runTest {
        val runner = FakeInlineJobRunner(InlineJobResult.Output("nope"))
        val session = RecordingSession(FakeSession(emitText = "should not run"))
        val loop = buildLoop(session, runner, listOf(propertySearchJob))

        val events = loop.run(AgentTurnInput("run job nonexistent foo")).toList()

        assertEquals(0, runner.callCount)
        assertTrue("engine must be untouched", session.requests.isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.JobStarted>().isEmpty())
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertTrue("missing not-found copy", done.message.text.contains("couldn't find"))
        assertTrue(done.skipMemoryExtraction)
    }

    @Test
    fun job_failure_surfaces_error_without_engine() = runTest {
        val runner = FakeInlineJobRunner(InlineJobResult.Failure("exit 1: boom"))
        val session = RecordingSession(FakeSession(emitText = "should not run"))
        val loop = buildLoop(session, runner, listOf(propertySearchJob))

        val events = loop.run(AgentTurnInput("run job property search Toronto")).toList()

        assertEquals(1, runner.callCount)
        assertTrue("engine must be untouched", session.requests.isEmpty())
        // JobStarted still fires (the run began), then a deterministic failure.
        assertEquals(1, events.filterIsInstance<AgentEvent.JobStarted>().size)
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertTrue("missing failure copy", done.message.text.contains("didn't complete"))
        assertTrue("missing failure detail", done.message.text.contains("boom"))
    }

    // -- Fixtures -----------------------------------------------------------

    private val propertySearchJob = Job(
        id = "job-prop",
        name = "Property Search",
        command = "echo",
        prompt = "saved-keywords",
        workingDir = null,
        scheduleType = JobScheduleType.ONE_SHOT,
        cronExpression = null,
        fireAtEpochMs = 1,
        paused = false,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        deletedAtEpochMs = null,
        lastRunStatus = null,
        lastRunAtEpochMs = null,
        lastRunSummary = null,
        lastRunConversationId = null,
    )

    private fun buildLoop(
        session: InferenceSession,
        runner: InlineJobRunner,
        jobs: List<Job>,
    ): AgentLoop {
        val assembler = PromptAssembler(timeContextProvider = { timeContext })
        val service = SearchService(StubKeyProvider, FakeBraveSearchClient(), dao)
        val router = PreflightRouter(
            engine = StubClassifierEngine(floatArrayOf(0f, 5f, 0f)), // skip-search band; unused on job turns
            tokenizer = WordPieceTokenizer(stubVocab),
            rewriter = QueryRewriter { timeContext },
            configProvider = { PreflightConfig.DEFAULT },
            searchAvailableProvider = { service.isAvailable() },
            logger = {},
        )
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = service,
            preflightRouter = router,
            jobRepository = FakeJobRepository(jobs),
            inlineJobRunner = runner,
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
        override suspend fun search(query: String, apiKey: String): BraveSearchResult =
            BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "unused")
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

    private class FakeInlineJobRunner(private val result: InlineJobResult) : InlineJobRunner {
        var lastId: String? = null
        var lastKeywords: String? = null
        var callCount = 0
        override suspend fun run(id: String, keywords: String): InlineJobResult {
            callCount++
            lastId = id
            lastKeywords = keywords
            return result
        }
    }

    /** Minimal [JobRepository] — only [snapshot]/[get] are exercised by the loop. */
    private class FakeJobRepository(private val jobs: List<Job>) : JobRepository {
        override fun flow(): Flow<List<Job>> = MutableStateFlow(jobs)
        override suspend fun snapshot(): List<Job> = jobs
        override suspend fun get(id: String): Job? = jobs.firstOrNull { it.id == id }
        override suspend fun create(job: Job) = Unit
        override suspend fun updateDefinition(
            id: String,
            name: String,
            command: String,
            prompt: String,
            workingDir: String?,
            scheduleType: JobScheduleType,
            cronExpression: String?,
            fireAtEpochMs: Long?,
            nowEpochMs: Long,
        ) = Unit
        override suspend fun setPaused(id: String, paused: Boolean, nowEpochMs: Long) = Unit
        override suspend fun softDelete(id: String, nowEpochMs: Long) = Unit
        override suspend fun recordLastRun(
            id: String,
            status: JobRunStatus,
            atEpochMs: Long,
            summary: String?,
            conversationId: String?,
            nowEpochMs: Long,
        ) = Unit
        override suspend fun insertRun(run: JobRun) = Unit
        override suspend fun finishRun(
            id: String,
            status: JobRunStatus,
            finishedAtEpochMs: Long,
            exitCode: Int?,
            response: String?,
            error: String?,
        ) = Unit
        override suspend fun runsForJob(jobId: String): List<JobRun> = emptyList()
        override suspend fun deleteRunsForJob(jobId: String) = Unit
        override suspend fun wipeLocal() = Unit
    }
}
