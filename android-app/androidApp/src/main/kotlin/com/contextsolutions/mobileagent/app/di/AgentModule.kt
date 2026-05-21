package com.contextsolutions.mobileagent.app.di

import android.util.Log
import com.contextsolutions.mobileagent.agent.AgentLoop
import com.contextsolutions.mobileagent.agent.ClockToolHandler
import com.contextsolutions.mobileagent.agent.PromptAssembler
import com.contextsolutions.mobileagent.agent.ResponseFilter
import com.contextsolutions.mobileagent.agent.TodoCommandParser
import com.contextsolutions.mobileagent.agent.TodoIntentDetector
import com.contextsolutions.mobileagent.agent.TodoResponseFormatter
import com.contextsolutions.mobileagent.agent.StockResponseFormatter
import com.contextsolutions.mobileagent.agent.TodoToolHandler
import com.contextsolutions.mobileagent.agent.WeatherResponseFormatter
import com.contextsolutions.mobileagent.agent.currentTimeContext
import com.contextsolutions.mobileagent.classifier.PreflightRouter
import com.contextsolutions.mobileagent.language.PreferredLanguage
import com.contextsolutions.mobileagent.memory.MemoryRetriever
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.LocaleProvider
import com.contextsolutions.mobileagent.preferences.DefaultSiteResolver
import com.contextsolutions.mobileagent.preferences.LocationCatalog
import com.contextsolutions.mobileagent.preferences.SearchPreferencesRepository
import com.contextsolutions.mobileagent.preferences.WeatherLocationResolver
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.vertical.VerticalSearchDispatcher
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the agent layer (prompt assembler, agent-loop factory) into Hilt.
 *
 * The function-call parser is no longer in the production path — the engine
 * surfaces tool calls via LiteRT-LM's structured callback (see
 * `LiteRtInferenceEngine.generate`), so there's nothing to parse out of text.
 * The marker parser stays in the codebase for tests and as a fallback if a
 * future model swap reverts to text-emitted tool calls.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideWeatherResponseFormatter(): WeatherResponseFormatter = WeatherResponseFormatter

    @Provides
    @Singleton
    fun provideStockResponseFormatter(): StockResponseFormatter = StockResponseFormatter

    @Provides
    @Singleton
    fun provideWeatherLocationResolver(catalog: LocationCatalog): WeatherLocationResolver =
        WeatherLocationResolver(catalog)

    @Provides
    @Singleton
    fun providePromptAssembler(
        clock: AgentClock,
        localeProvider: LocaleProvider,
    ): PromptAssembler = PromptAssembler(
        timeContextProvider = { currentTimeContext(clock, localeProvider) },
    )

    @Provides
    @Singleton
    fun provideAgentLoopFactory(
        assembler: PromptAssembler,
        searchService: SearchService,
        preflightRouter: PreflightRouter,
        memoryRetriever: MemoryRetriever,
        counters: TelemetryCounters,
        clockToolHandler: ClockToolHandler,
        todoToolHandler: TodoToolHandler,
        todoIntentDetector: TodoIntentDetector,
        todoCommandParser: TodoCommandParser,
        todoResponseFormatter: TodoResponseFormatter,
        verticalDispatcher: VerticalSearchDispatcher,
        searchPreferences: SearchPreferencesRepository,
        locationCatalog: LocationCatalog,
        weatherLocationResolver: WeatherLocationResolver,
        defaultSiteResolver: DefaultSiteResolver,
        weatherResponseFormatter: WeatherResponseFormatter,
        stockResponseFormatter: StockResponseFormatter,
    ): AgentLoopFactory = object : AgentLoopFactory {
        override fun create(
            session: com.contextsolutions.mobileagent.agent.InferenceSession,
            responseLanguage: PreferredLanguage,
            responseFilter: ResponseFilter,
        ): AgentLoop = AgentLoop(
            session = session,
            assembler = assembler,
            searchService = searchService,
            preflightRouter = preflightRouter,
            memoryRetriever = memoryRetriever,
            toolHandlers = listOf(clockToolHandler, todoToolHandler),
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
            logger = { Log.i("AgentLoop", it) },
            counters = counters,
            responseLanguage = responseLanguage,
            responseFilter = responseFilter,
        )
    }
}

/**
 * Builds a per-turn [AgentLoop]. The session lifetime is one user turn; the
 * factory takes the user's [PreferredLanguage] + the matching
 * [ResponseFilter] decided at the call-site so each turn can either enforce
 * the filter (normal turn) or relax it (translation-intent turn). Defaults
 * preserve pre-PR-#10 behaviour for tests and any caller that doesn't yet
 * know about the language path.
 */
interface AgentLoopFactory {
    fun create(
        session: com.contextsolutions.mobileagent.agent.InferenceSession,
        responseLanguage: PreferredLanguage = PreferredLanguage.DEFAULT,
        responseFilter: ResponseFilter = ResponseFilter.NoOp,
    ): AgentLoop
}
