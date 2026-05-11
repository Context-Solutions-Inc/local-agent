package com.contextsolutions.mobileagent.app.di

import android.content.Context
import android.util.Log
import com.contextsolutions.mobileagent.agent.currentTimeContext
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import com.contextsolutions.mobileagent.db.MemoriesQueries
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import com.contextsolutions.mobileagent.memory.EmbedderEngine
import com.contextsolutions.mobileagent.memory.LiteRtEmbedderEngine
import com.contextsolutions.mobileagent.memory.MemoryEvictor
import com.contextsolutions.mobileagent.memory.MemoryExtractor
import com.contextsolutions.mobileagent.memory.MemoryPreferences
import com.contextsolutions.mobileagent.memory.MemoryRetriever
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.memory.RememberForgetDetector
import com.contextsolutions.mobileagent.memory.SharedPreferencesMemoryPreferences
import com.contextsolutions.mobileagent.memory.SqlDelightMemoryStore
import com.contextsolutions.mobileagent.memory.TempContextDateParser
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.LocaleProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * M5 / WS-9 — embedder + memory store wiring.
 *
 * - [EmbedderEngine] is provided eagerly (Hilt singleton) but loads its
 *   `.tflite` lazily inside `warmUp()`, kicked off by `ChatViewModel.init`
 *   on a background coroutine (mirrors the M4 classifier pattern).
 * - [MemoryStore] is a thin SQLDelight DAO wrapper — no async init needed.
 * - The vocab + tokenizer are reused from `ClassifierModule`. MiniLM ships
 *   the same `bert-base-uncased` WordPiece vocab as DistilBERT (verified
 *   via SHA match in the Phase A export driver), so a single
 *   [WordPieceTokenizer] singleton serves both engines.
 */
@Module
@InstallIn(SingletonComponent::class)
object MemoryModule {

    @Provides
    @Singleton
    fun provideEmbedderEngine(
        @ApplicationContext context: Context,
        tokenizer: WordPieceTokenizer,
    ): EmbedderEngine = LiteRtEmbedderEngine(context = context, tokenizer = tokenizer)

    @Provides
    @Singleton
    fun provideMemoriesQueries(database: MobileAgentDatabase): MemoriesQueries =
        database.memoriesQueries

    @Provides
    @Singleton
    fun provideMemoryStore(queries: MemoriesQueries): MemoryStore =
        SqlDelightMemoryStore(queries)

    @Provides
    @Singleton
    fun provideMemoryRetriever(
        embedder: EmbedderEngine,
        store: MemoryStore,
        clock: AgentClock,
        counters: TelemetryCounters,
    ): MemoryRetriever = MemoryRetriever(
        embedder = embedder,
        store = store,
        nowProvider = { clock.nowEpochMs() },
        logger = { Log.i("MemoryRetriever", it) },
        counters = counters,
    )

    // -- Phase D wiring -----------------------------------------------------

    @Provides
    @Singleton
    fun provideMemoryPreferences(
        @ApplicationContext context: Context,
    ): MemoryPreferences = SharedPreferencesMemoryPreferences(context)

    @Provides
    @Singleton
    fun provideRememberForgetDetector(): RememberForgetDetector = RememberForgetDetector()

    @Provides
    @Singleton
    fun provideTempContextDateParser(
        clock: AgentClock,
        localeProvider: LocaleProvider,
    ): TempContextDateParser = TempContextDateParser(
        timeContextProvider = { currentTimeContext(clock, localeProvider) },
        // Defaults to TimeZone.UTC inside :shared. The only effect is which
        // moment of the local day is treated as the "start of day" anchor
        // for temp-context expiries — within the 30-day default expiry
        // granularity that's always less than half a day off, which is
        // negligible. v1.x can thread the device's TimeZone through if
        // telemetry shows it matters.
    )

    @Provides
    @Singleton
    fun provideMemoryEvictor(counters: TelemetryCounters): MemoryEvictor = MemoryEvictor(
        logger = { Log.i("MemoryEvictor", it) },
        counters = counters,
    )

    @Provides
    @Singleton
    fun provideMemoryExtractor(
        classifier: ClassifierEngine,
        tokenizer: WordPieceTokenizer,
        embedder: EmbedderEngine,
        store: MemoryStore,
        evictor: MemoryEvictor,
        detector: RememberForgetDetector,
        dateParser: TempContextDateParser,
        clock: AgentClock,
        preferences: MemoryPreferences,
        counters: TelemetryCounters,
    ): MemoryExtractor = MemoryExtractor(
        classifier = classifier,
        tokenizer = tokenizer,
        embedder = embedder,
        store = store,
        evictor = evictor,
        detector = detector,
        dateParser = dateParser,
        nowProvider = { clock.nowEpochMs() },
        creationEnabledProvider = { preferences.creationEnabled() },
        logger = { Log.i("MemoryExtractor", it) },
        counters = counters,
    )
}
