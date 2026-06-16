package com.contextsolutions.localagent.di

import com.contextsolutions.localagent.agent.AgentLoop
import com.contextsolutions.localagent.agent.ClockToolHandler
import com.contextsolutions.localagent.agent.InferenceSession
import com.contextsolutions.localagent.agent.PromptAssembler
import com.contextsolutions.localagent.agent.ResponseFilter
import com.contextsolutions.localagent.agent.StockResponseFormatter
import com.contextsolutions.localagent.agent.TodoCommandParser
import com.contextsolutions.localagent.agent.TodoIntentDetector
import com.contextsolutions.localagent.agent.TodoResponseFormatter
import com.contextsolutions.localagent.agent.TodoToolHandler
import com.contextsolutions.localagent.agent.WeatherResponseFormatter
import com.contextsolutions.localagent.agent.currentTimeContext
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.classifier.QueryRewriter
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.i18n.DefaultStringCatalog
import com.contextsolutions.localagent.i18n.StringCatalog
import com.contextsolutions.localagent.i18n.Strings
import com.contextsolutions.localagent.job.InlineJobRunner
import com.contextsolutions.localagent.job.JobRepository
import com.contextsolutions.localagent.language.PreferredLanguage
import com.contextsolutions.localagent.memory.MemoryRetriever
import com.contextsolutions.localagent.platform.AgentClock
import com.contextsolutions.localagent.platform.LocaleProvider
import com.contextsolutions.localagent.preferences.DefaultSiteResolver
import com.contextsolutions.localagent.preferences.LocationCatalog
import com.contextsolutions.localagent.preferences.SearchPreferencesRepository
import com.contextsolutions.localagent.preferences.WeatherLocationResolver
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.search.SearchSubtypeDetector
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcher
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Platform-agnostic slice of the agent DI graph (docs/DESKTOP_PORT_PLAN.md, Phase 2).
 * Only the pieces that are identical on every platform and need no platform types live
 * here — `AgentClock`/`LocaleProvider` (expect classes with a common constructor),
 * `PromptAssembler`, and `QueryRewriter`.
 *
 * Everything that varies by platform OR by policy (the inference/classifier/embedder
 * engines, the HTTP + DB stack, and the search-enablement decision baked into
 * `PreflightRouter`/`SearchService`) is bound in the per-platform module — `desktopModule`
 * on desktop, and the future `androidModule` when Android migrates off Hilt. Keeping the
 * search policy out of common avoids hard-coding desktop's "search disabled" choice into
 * code Android will later share.
 */
val agentCoreModule: Module = module {
    single { AgentClock() }
    single { LocaleProvider() }
    single { PromptAssembler(timeContextProvider = { currentTimeContext(get(), get()) }) }
    single { QueryRewriter(timeContextProvider = { currentTimeContext(get(), get()) }) }
    // Pure-Kotlin tokenizer; the per-platform module supplies the `Vocab` (Android loads
    // the bundled vocab.txt, desktop a placeholder until Phase 5). Shared here so both
    // platforms (and the classifier/embedder/memory consumers) use one definition.
    single { WordPieceTokenizer(get()) }
    // Pure query-subtype detector consumed by PreflightRouter.
    single { SearchSubtypeDetector() }

    // Runtime localization catalog (PR #96). English is the in-code floor; the
    // per-platform `StringPackLoader` supplies any translated JSON pack. Drives
    // both the Compose `active` flow and the agent's per-turn `stringsFor`.
    single<StringCatalog> { DefaultStringCatalog(loader = get(), languagePreferences = get()) }

    // Per-turn AgentLoop builder (lifted from androidApp's AgentModule in Phase 3). Required
    // collaborators (assembler/search/router) resolve from this graph; the many optional
    // collaborators (memory, vertical search, weather/finance, tool handlers, formatters) are
    // pulled with getOrNull() so a platform that doesn't bind them (e.g. the Phase-0/2 desktop
    // graph with search+memory disabled) still produces a working loop. The logger routes
    // through the per-platform [AgentLogger] so Android keeps the "AgentLoop" logcat tag (#28).
    single<AgentLoopFactory> {
        val assembler = get<PromptAssembler>()
        val searchService = get<SearchService>()
        val preflightRouter = get<PreflightRouter>()
        val memoryRetriever = getOrNull<MemoryRetriever>()
        val toolHandlers = listOfNotNull(getOrNull<ClockToolHandler>(), getOrNull<TodoToolHandler>())
        val todoIntentDetector = getOrNull<TodoIntentDetector>() ?: TodoIntentDetector()
        val todoCommandParser = getOrNull<TodoCommandParser>() ?: TodoCommandParser()
        val todoResponseFormatter = getOrNull<TodoResponseFormatter>() ?: TodoResponseFormatter()
        val verticalDispatcher = getOrNull<VerticalSearchDispatcher>()
        val searchPreferences = getOrNull<SearchPreferencesRepository>()
        val locationCatalog = getOrNull<LocationCatalog>()
        val weatherLocationResolver = getOrNull<WeatherLocationResolver>()
        val defaultSiteResolver = getOrNull<DefaultSiteResolver>()
        val weatherResponseFormatter = getOrNull<WeatherResponseFormatter>()
        val stockResponseFormatter = getOrNull<StockResponseFormatter>()
        // PR #88 — "run job …" inline command. Both bound on desktop + mobile
        // (jobs sync to mobile); the runner differs per platform (local executor
        // vs relay). Null on graphs without jobs → the feature is inert.
        val jobRepository = getOrNull<JobRepository>()
        val inlineJobRunner = getOrNull<InlineJobRunner>()
        val counters = getOrNull<TelemetryCounters>() ?: NoOpTelemetryCounters
        val agentLogger = getOrNull<AgentLogger>()
        // PR #96 — resolve the per-turn localized strings from the catalog (falls
        // back to English if a graph doesn't bind one).
        val stringCatalog = getOrNull<StringCatalog>()
        object : AgentLoopFactory {
            override fun create(
                session: InferenceSession,
                responseLanguage: PreferredLanguage,
                responseFilter: ResponseFilter,
            ): AgentLoop = AgentLoop(
                session = session,
                assembler = assembler,
                searchService = searchService,
                preflightRouter = preflightRouter,
                memoryRetriever = memoryRetriever,
                toolHandlers = toolHandlers,
                todoIntentDetector = todoIntentDetector,
                todoCommandParser = todoCommandParser,
                todoResponseFormatter = todoResponseFormatter,
                verticalDispatcher = verticalDispatcher,
                searchPreferences = searchPreferences,
                locationCatalog = locationCatalog,
                weatherLocationResolver = weatherLocationResolver,
                defaultSiteResolver = defaultSiteResolver,
                weatherResponseFormatter = weatherResponseFormatter,
                stockResponseFormatter = stockResponseFormatter,
                jobRepository = jobRepository,
                inlineJobRunner = inlineJobRunner,
                logger = { agentLogger?.log(it) },
                counters = counters,
                responseLanguage = responseLanguage,
                responseFilter = responseFilter,
                strings = stringCatalog?.stringsFor(responseLanguage) ?: Strings.ENGLISH,
            )
        }
    }
}
