package com.contextsolutions.mobileagent.app.di

import android.content.Context
import android.util.Log
import com.contextsolutions.mobileagent.agent.currentTimeContext
import com.contextsolutions.mobileagent.app.service.ManagedClassifierEngine
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.LiteRtClassifierEngine
import com.contextsolutions.mobileagent.classifier.PreflightConfig
import com.contextsolutions.mobileagent.classifier.PreflightRouter
import com.contextsolutions.mobileagent.classifier.QueryRewriter
import com.contextsolutions.mobileagent.classifier.Vocab
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.LocaleProvider
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * M4 / WS-8 — pre-flight classifier wiring.
 *
 * - [ClassifierEngine] is created lazily but PROVIDED eagerly (Hilt builds the
 *   singleton at first injection request). [PlayServicesLiteRtClassifierEngine]
 *   does not load the .tflite in its constructor — that happens in
 *   `engine.warmUp()`, kicked off by `ChatViewModel.init` so the load latency
 *   hides behind user typing time. See `M4_PLAN.md` §7 (resolved).
 *
 * - [Vocab] and [WordPieceTokenizer] are loaded eagerly at first request.
 *   The vocab parse runs once (~50 ms on Pixel 7 for 30,522 entries) and the
 *   resulting [Vocab] object is reused across every classify call. Loading
 *   on a background thread happens implicitly because the first injection
 *   into [ChatViewModel] happens off-main inside Compose's lifecycle scope.
 *
 * - [PreflightConfig] is parsed from `preflight_config.json` at first
 *   request. Falls back to [PreflightConfig.DEFAULT] if the file is missing
 *   or malformed — the app still ships with sensible thresholds even if
 *   the asset got mangled.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClassifierModule {

    private const val VOCAB_ASSET_PATH = "vocab.txt"
    private const val PREFLIGHT_CONFIG_ASSET_PATH = "preflight_config.json"
    private const val TAG = "ClassifierModule"

    private val configJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * PR #8 — the engine is wrapped in [ManagedClassifierEngine] so the
     * 67.7 MB `CompiledModel` gets the same lifecycle Gemma does
     * (5-min idle unload, eager warm-up, `onTrimMemory` + watchdog
     * `forceUnload`). Call sites still see a plain [ClassifierEngine]
     * since the wrapper implements the interface.
     */
    @Provides
    @Singleton
    fun provideClassifierEngine(
        @ApplicationContext context: Context,
        thermalStatusProvider: ThermalStatusProvider,
        counters: TelemetryCounters,
    ): ClassifierEngine = ManagedClassifierEngine(
        delegate = LiteRtClassifierEngine(context),
        thermalStatusProvider = thermalStatusProvider,
        counters = counters,
    )

    @Provides
    @Singleton
    fun provideVocab(@ApplicationContext context: Context): Vocab =
        context.assets.open(VOCAB_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { reader ->
            Vocab.fromLines(reader.lineSequence())
        }

    @Provides
    @Singleton
    fun provideWordPieceTokenizer(vocab: Vocab): WordPieceTokenizer = WordPieceTokenizer(vocab)

    @Provides
    @Singleton
    fun providePreflightConfig(@ApplicationContext context: Context): PreflightConfig = try {
        val raw = context.assets.open(PREFLIGHT_CONFIG_ASSET_PATH)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        configJson.decodeFromString(PreflightConfig.serializer(), raw)
    } catch (t: Throwable) {
        Log.w(
            TAG,
            "Failed to load $PREFLIGHT_CONFIG_ASSET_PATH; using DEFAULT thresholds (${t.message})",
        )
        PreflightConfig.DEFAULT
    }

    @Provides
    @Singleton
    fun provideQueryRewriter(
        clock: AgentClock,
        localeProvider: LocaleProvider,
    ): QueryRewriter = QueryRewriter(
        timeContextProvider = { currentTimeContext(clock, localeProvider) },
    )

    @Provides
    @Singleton
    fun providePreflightRouter(
        engine: ClassifierEngine,
        tokenizer: WordPieceTokenizer,
        rewriter: QueryRewriter,
        config: PreflightConfig,
        searchService: SearchService,
        counters: com.contextsolutions.mobileagent.telemetry.TelemetryCounters,
    ): PreflightRouter = PreflightRouter(
        engine = engine,
        tokenizer = tokenizer,
        rewriter = rewriter,
        // PreflightConfig is a @Singleton so reading it through a closure is
        // effectively a constant; surfacing it as a Provider would let M6
        // telemetry hot-swap thresholds without app restart, but that's
        // out of scope for v1.
        configProvider = { config },
        searchAvailableProvider = { searchService.isAvailable() },
        logger = { Log.i(TAG, it) },
        counters = counters,
    )
}
