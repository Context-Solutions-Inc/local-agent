package com.contextsolutions.mobileagent.app.di

import android.content.Context
import android.util.Log
import com.contextsolutions.mobileagent.agent.currentTimeContext
import com.contextsolutions.mobileagent.app.service.ManagedEmbedderEngine
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import com.contextsolutions.mobileagent.db.MemoriesQueries
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import com.contextsolutions.mobileagent.memory.EmbedderEngine
import com.contextsolutions.mobileagent.memory.LiteRtEmbedderEngine
import com.contextsolutions.mobileagent.memory.MemoryConfig
import com.contextsolutions.mobileagent.memory.MemoryEvictor
import com.contextsolutions.mobileagent.memory.MemoryExtractor
import com.contextsolutions.mobileagent.memory.MemoryPreferences
import com.contextsolutions.mobileagent.memory.MemoryRetriever
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.memory.QuestionDetector
import com.contextsolutions.mobileagent.memory.RememberForgetDetector
import com.contextsolutions.mobileagent.memory.SharedPreferencesMemoryPreferences
import com.contextsolutions.mobileagent.memory.SqlDelightMemoryStore
import com.contextsolutions.mobileagent.memory.TempContextDateParser
import kotlinx.serialization.json.Json
import com.contextsolutions.mobileagent.app.ui.memory.MemoryBackupController
import com.contextsolutions.mobileagent.app.ui.memory.MemoryBackupOps
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.LocaleProvider
import dagger.Binds
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

    private const val TAG = "MemoryModule"
    private const val MEMORY_CONFIG_ASSET_PATH = "memory_config.json"
    private val configJson = Json { ignoreUnknownKeys = true; isLenient = true }

    @Provides
    @Singleton
    fun provideMemoryConfig(@ApplicationContext context: Context): MemoryConfig = try {
        val raw = context.assets.open(MEMORY_CONFIG_ASSET_PATH)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        configJson.decodeFromString(MemoryConfig.serializer(), raw)
    } catch (t: Throwable) {
        Log.w(
            TAG,
            "Failed to load $MEMORY_CONFIG_ASSET_PATH; using DEFAULT thresholds (${t.message})",
        )
        MemoryConfig.DEFAULT
    }

    /**
     * PR #8 — the engine is wrapped in [ManagedEmbedderEngine] for the
     * same lifecycle as the classifier (5-min idle unload,
     * `onTrimMemory` + watchdog `forceUnload`, eager warm-up).
     */
    @Provides
    @Singleton
    fun provideEmbedderEngine(
        @ApplicationContext context: Context,
        tokenizer: WordPieceTokenizer,
        thermalStatusProvider: ThermalStatusProvider,
        counters: TelemetryCounters,
    ): EmbedderEngine = ManagedEmbedderEngine(
        delegate = LiteRtEmbedderEngine(context = context, tokenizer = tokenizer),
        thermalStatusProvider = thermalStatusProvider,
        counters = counters,
    )

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
    fun provideQuestionDetector(): QuestionDetector = QuestionDetector()

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
        questionDetector: QuestionDetector,
        dateParser: TempContextDateParser,
        clock: AgentClock,
        preferences: MemoryPreferences,
        config: MemoryConfig,
        counters: TelemetryCounters,
    ): MemoryExtractor = MemoryExtractor(
        classifier = classifier,
        tokenizer = tokenizer,
        embedder = embedder,
        store = store,
        evictor = evictor,
        detector = detector,
        questionDetector = questionDetector,
        dateParser = dateParser,
        nowProvider = { clock.nowEpochMs() },
        // MemoryConfig is a @Singleton so reading it through a closure is
        // effectively a constant; surfacing it as a Provider would let
        // telemetry hot-swap thresholds without app restart later.
        configProvider = { config },
        creationEnabledProvider = { preferences.creationEnabled() },
        logger = { Log.i("MemoryExtractor", it) },
        counters = counters,
    )
}

/**
 * `@Binds` half of the memory module — keeps the interface→impl
 * mapping for [MemoryBackupOps] separate from the `object`-style
 * providers above. `MemoryBackupController` has an `@Inject`
 * constructor so Hilt resolves the impl automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryBackupBindingModule {

    @Binds
    @Singleton
    abstract fun bindMemoryBackupOps(impl: MemoryBackupController): MemoryBackupOps
}
