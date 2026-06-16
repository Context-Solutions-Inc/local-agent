package com.contextsolutions.localagent.app.di

import android.util.Log
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.contextsolutions.localagent.agent.currentTimeContext
import com.contextsolutions.localagent.app.BuildConfig
import com.contextsolutions.localagent.app.service.ManagedClassifierEngine
import com.contextsolutions.localagent.app.service.ManagedEmbedderEngine
import com.contextsolutions.localagent.app.spike.StubInferenceEngine
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.LiteRtClassifierEngine
import com.contextsolutions.localagent.classifier.PreflightConfig
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.agent.ChatSessionController
import com.contextsolutions.localagent.di.AgentLogger
import com.contextsolutions.localagent.inference.AndroidMemoryHeadroomProvider
import com.contextsolutions.localagent.inference.AndroidThermalStatusProvider
import com.contextsolutions.localagent.inference.DesktopLinkInferenceEngine
import com.contextsolutions.localagent.inference.DesktopLinkStatusProvider
import com.contextsolutions.localagent.inference.PollingDesktopLinkStatusProvider
import com.contextsolutions.localagent.inference.InferenceEngine
import com.contextsolutions.localagent.inference.LiteRtInferenceEngineFactory
import com.contextsolutions.localagent.inference.MemoryHeadroomProvider
import com.contextsolutions.localagent.inference.OllamaClient
import com.contextsolutions.localagent.inference.OllamaConnectionMonitor
import com.contextsolutions.localagent.inference.OllamaInferenceEngine
import com.contextsolutions.localagent.inference.RoutingInferenceEngine
import com.contextsolutions.localagent.inference.SystemMemoryThresholds
import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.LiteRtEmbedderEngine
import com.contextsolutions.localagent.memory.MemoryBackupController
import com.contextsolutions.localagent.memory.MemoryBackupOps
import com.contextsolutions.localagent.memory.MemoryConfig
import com.contextsolutions.localagent.memory.MemoryExtractor
import com.contextsolutions.localagent.memory.MemoryPreferences
import com.contextsolutions.localagent.memory.MemoryRetriever
import com.contextsolutions.localagent.memory.MemoryStore
import com.contextsolutions.localagent.memory.QuestionDetector
import com.contextsolutions.localagent.memory.RememberForgetDetector
import com.contextsolutions.localagent.memory.SharedPreferencesMemoryPreferences
import com.contextsolutions.localagent.memory.SqlDelightMemoryStore
import com.contextsolutions.localagent.memory.TempContextDateParser
import com.contextsolutions.localagent.platform.AgentClock
import com.contextsolutions.localagent.platform.AndroidHttpEngineFactory
import com.contextsolutions.localagent.platform.HttpEngineFactory
import com.contextsolutions.localagent.app.platform.AndroidAppBuildConfig
import com.contextsolutions.localagent.platform.AppBuildConfig
import com.contextsolutions.localagent.platform.AndroidToaster
import com.contextsolutions.localagent.platform.AndroidUrlOpener
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.Toaster
import com.contextsolutions.localagent.platform.UrlOpener
import com.contextsolutions.localagent.platform.SecureStorageFactory
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.DefaultBraveKeyProvider
import com.contextsolutions.localagent.search.KtorBraveLlmContextClient
import com.contextsolutions.localagent.search.KtorBraveSearchClient
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.telemetry.InMemoryTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryFlusher
import com.contextsolutions.localagent.agent.ClockToolHandler
import com.contextsolutions.localagent.agent.StockResponseFormatter
import com.contextsolutions.localagent.agent.TodoCommandParser
import com.contextsolutions.localagent.agent.TodoIntentDetector
import com.contextsolutions.localagent.agent.TodoResponseFormatter
import com.contextsolutions.localagent.agent.TodoToolHandler
import com.contextsolutions.localagent.agent.WeatherResponseFormatter
import com.contextsolutions.localagent.app.service.clock.AndroidAlarmScheduler
import com.contextsolutions.localagent.app.service.clock.ClockNotifications
import com.contextsolutions.localagent.clock.AlarmScheduler
import com.contextsolutions.localagent.clock.ClockRepository
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.clock.SharedPreferencesClockRepository
import com.contextsolutions.localagent.onboarding.OnboardingPreferences
import com.contextsolutions.localagent.onboarding.SharedPreferencesOnboardingPreferences
import com.contextsolutions.localagent.preferences.DataStoreSearchPreferencesRepository
import com.contextsolutions.localagent.preferences.DefaultSiteResolver
import com.contextsolutions.localagent.preferences.DesktopLinkPreferences
import com.contextsolutions.localagent.preferences.OllamaPreferences
import com.contextsolutions.localagent.subscription.NoOpRelayPairingInitiator
import com.contextsolutions.localagent.subscription.NoOpSubscriptionPreferences
import com.contextsolutions.localagent.subscription.NoOpSubscriptionUiController
import com.contextsolutions.localagent.subscription.RelayDisconnector
import com.contextsolutions.localagent.subscription.RelayPairingInitiator
import com.contextsolutions.localagent.subscription.RelayUnpairDisconnector
import com.contextsolutions.localagent.subscription.SubscriptionPreferences
import com.contextsolutions.localagent.subscription.SubscriptionUiController
import com.contextsolutions.localagent.preferences.SharedPreferencesDesktopLinkPreferences
import com.contextsolutions.localagent.preferences.SharedPreferencesOllamaPreferences
import com.contextsolutions.localagent.preferences.LocationCatalog
import com.contextsolutions.localagent.preferences.SearchPreferencesRepository
import com.contextsolutions.localagent.preferences.WeatherLocationResolver
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcher
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcherFactory
import com.contextsolutions.localagent.todo.SqlDelightTodoRepository
import com.contextsolutions.localagent.todo.TodoRepository
import com.contextsolutions.localagent.agent.TranslationIntentDetector
import com.contextsolutions.localagent.conversation.ConversationRepository
import com.contextsolutions.localagent.conversation.SqlDelightConversationRepository
import com.contextsolutions.localagent.link.DesktopLinkConnectionStatus
import com.contextsolutions.localagent.link.DesktopLinkQrProvider
import com.contextsolutions.localagent.link.NoDesktopLinkConnection
import com.contextsolutions.localagent.link.NoDesktopLinkQr
import com.contextsolutions.localagent.link.transport.AndroidRelayBytePipeFactory
import com.contextsolutions.localagent.link.transport.DefaultLinkTransportProvider
import com.contextsolutions.localagent.link.transport.LinkMethod
import com.contextsolutions.localagent.link.transport.LinkRequest
import com.contextsolutions.localagent.link.transport.LinkTransportProvider
import com.contextsolutions.localagent.link.transport.RelayBytePipeFactory
import com.contextsolutions.localagent.sync.LastSyncStatus
import com.contextsolutions.localagent.sync.LastSyncStore
import com.contextsolutions.localagent.sync.LinkSyncClient
import com.contextsolutions.localagent.sync.LinkSyncService
import com.contextsolutions.localagent.sync.LocalChangeBus
import com.contextsolutions.localagent.sync.MobileJobSyncPolicy
import com.contextsolutions.localagent.sync.MutableLastSyncStatus
import com.contextsolutions.localagent.sync.JobSyncPolicy
import com.contextsolutions.localagent.sync.SharedPreferencesLastSyncStore
import com.contextsolutions.localagent.sync.SharedPreferencesSyncWatermarkStore
import com.contextsolutions.localagent.sync.SqlDelightLinkSyncService
import com.contextsolutions.localagent.sync.SyncController
import com.contextsolutions.localagent.sync.SyncWatermarkStore
import com.contextsolutions.localagent.app.notification.AndroidNotificationPresenter
import com.contextsolutions.localagent.job.InlineJobRunner
import com.contextsolutions.localagent.job.JobBadge
import com.contextsolutions.localagent.job.JobCompletionNotifier
import com.contextsolutions.localagent.job.JobNotificationPrefs
import com.contextsolutions.localagent.job.JobRepository
import com.contextsolutions.localagent.job.RelayInlineJobRunner
import com.contextsolutions.localagent.job.RelayRemoteJobRunner
import com.contextsolutions.localagent.job.RemoteJobRunner
import com.contextsolutions.localagent.job.SharedPreferencesJobNotificationPrefs
import com.contextsolutions.localagent.job.SqlDelightJobRepository
import com.contextsolutions.localagent.notification.NotificationPresenter
import com.contextsolutions.localagent.i18n.StringPackLoader
import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.language.SharedPreferencesLanguagePreferences
import com.contextsolutions.localagent.telemetry.SharedPreferencesTelemetryConsentManager
import com.contextsolutions.localagent.telemetry.TelemetryConsentManager
import com.contextsolutions.localagent.app.service.AndroidInferenceForegroundServiceController
import com.contextsolutions.localagent.app.service.AuxModelLifecycleCoordinator
import com.contextsolutions.localagent.app.service.ForegroundServiceController
import com.contextsolutions.localagent.app.service.AndroidChatSessionController
import com.contextsolutions.localagent.app.service.InferenceSessionManager
import com.contextsolutions.localagent.app.service.ModelDownloadController
import com.contextsolutions.localagent.app.service.ModelInventory
import com.contextsolutions.localagent.app.observability.HandlerMainThreadProbe
import com.contextsolutions.localagent.app.observability.MainThreadHeartbeatWatchdog
import com.contextsolutions.localagent.app.observability.MainThreadProbe
import com.contextsolutions.localagent.app.observability.MemoryPressureWatchdog
import com.contextsolutions.localagent.app.observability.AndroidSystemMemoryStatusProvider
import com.contextsolutions.localagent.app.observability.SystemMemoryMonitor
import com.contextsolutions.localagent.app.service.ModelDownloader
import com.contextsolutions.localagent.inference.DefaultHfAuthTokenProvider
import com.contextsolutions.localagent.inference.HfAuthTokenProvider
import com.contextsolutions.localagent.telemetry.AnalyticsSink
import com.contextsolutions.localagent.telemetry.FirebaseAnalyticsSink
import com.contextsolutions.localagent.telemetry.TelemetryPayloadBuilder
import com.contextsolutions.localagent.telemetry.TelemetryUploader
import okhttp3.OkHttpClient
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import com.contextsolutions.localagent.vision.AndroidImagePreprocessor
import com.contextsolutions.localagent.vision.ImagePreprocessor
import com.contextsolutions.localagent.voice.AndroidTtsSpeaker
import com.contextsolutions.localagent.voice.ChatSpeaker
import com.contextsolutions.localagent.voice.SharedPreferencesTtsPreferences
import com.contextsolutions.localagent.voice.TtsPreferences
import com.contextsolutions.localagent.app.ui.theme.SharedPreferencesThemePreferences
import com.contextsolutions.localagent.ui.theme.ThemePreferences
import com.contextsolutions.localagent.agent.ChatLogger
import com.contextsolutions.localagent.inference.SystemMemoryStatusProvider
import com.contextsolutions.localagent.voice.AndroidDictation
import com.contextsolutions.localagent.voice.Dictation
import com.contextsolutions.localagent.observability.FirebaseSafeCrashReporter
import com.contextsolutions.localagent.observability.NoOpSafeCrashReporter
import com.contextsolutions.localagent.observability.SafeCrashReporter
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.contextsolutions.localagent.app.ui.MainViewModel
import com.contextsolutions.localagent.app.ui.download.DownloadViewModel
import com.contextsolutions.localagent.app.spike.SpikeViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val DB_NAME = "local_agent.db"
private const val VOCAB_ASSET_PATH = "vocab.txt"
private const val MEMORY_CONFIG_ASSET_PATH = "memory_config.json"
private const val SYSTEM_MEMORY_CONFIG_ASSET_PATH = "system_memory_config.json"
private const val PREFLIGHT_CONFIG_ASSET_PATH = "preflight_config.json"
private const val LOCATIONS_ASSET = "locations.json"
private const val SEARCH_DEFAULTS_ASSET = "search_defaults.json"
private const val EMPTY_DEFAULTS_JSON = """{"fallback":"US","countries":{"US":{}}}"""
private const val KOIN_TAG = "AndroidKoinModule"
// Preserve the "BraveApi" logcat tag (invariant #28: search log tags come from DI).
private const val BRAVE_TAG = "BraveApi"
private val configJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Android platform bindings for the Koin graph (docs/DESKTOP_PORT_PLAN.md, Phase 2b) —
 * the counterpart of `desktopModule`. As the Hilt→Koin migration proceeds, the Android
 * platform-leaf singletons land here and the matching Hilt `@Provides` delegate via
 * `getKoin().get()`, giving one source of truth shared with the desktop graph.
 *
 * Loaded by `LocalAgentApplication.onCreate` alongside the shared `agentCoreModule`.
 */
val androidModule: Module = module {
    single<HttpEngineFactory> { AndroidHttpEngineFactory() }
    single<SecureStorage> { SecureStorageFactory.create(androidContext()) }
    // App build flags for shared :ui screens (Phase 9). Backed by :androidApp BuildConfig.
    single<AppBuildConfig> { AndroidAppBuildConfig() }
    // Open web URLs (onboarding key/token dashboards) for shared :ui screens (Phase 9).
    single<UrlOpener> { AndroidUrlOpener(androidContext()) }
    // Transient messages (memory backup feedback) for shared :ui screens (Phase 9).
    single<Toaster> { AndroidToaster(androidContext()) }

    // SQLDelight: one DB per process via the Android driver (same file/migration path as
    // the former DatabaseModule). Per-table query handles are distinct Koin types, so
    // they resolve by type without qualifiers (mirrors desktopModule's searchCacheQueries).
    single { LocalAgentDatabase(AndroidSqliteDriver(LocalAgentDatabase.Schema, androidContext(), DB_NAME)) }
    single { get<LocalAgentDatabase>().searchCacheQueries }
    single { get<LocalAgentDatabase>().telemetryAggregateQueries }
    // PR #70 — jobs. The repo renders synced state offline; mobile binds NO
    // scheduler/executor (jobs run only on the desktop).
    single { get<LocalAgentDatabase>().jobsQueries }
    single<JobRepository> { SqlDelightJobRepository(queries = get(), bus = get()) }
    // PR #85 — mobile-only job-completion signals (header badge + OS notification).
    single<NotificationPresenter> { AndroidNotificationPresenter(androidContext()) }
    single<JobNotificationPrefs> { SharedPreferencesJobNotificationPrefs(androidContext()) }
    single { JobBadge(repository = get(), prefs = get()) }
    single {
        JobCompletionNotifier(
            repository = get(),
            presenter = get(),
            prefs = get(),
            logger = { Log.i("JobNotifier", it) },
        )
    }
    single { get<LocalAgentDatabase>().conversationsQueries }
    single { get<LocalAgentDatabase>().todosQueries }
    single { get<LocalAgentDatabase>().memoriesQueries }

    // Bundled DistilBERT/MiniLM WordPiece vocab (shared by classifier + embedder).
    // WordPieceTokenizer itself is in the shared agentCoreModule and resolves this.
    single {
        androidContext().assets.open(VOCAB_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { reader ->
            Vocab.fromLines(reader.lineSequence())
        }
    }

    // Telemetry counters: one InMemoryTelemetryCounters bound to both the recording
    // (TelemetryCounters) and persistence (TelemetryFlusher) interfaces so they share
    // state (TelemetryAggregateQueries comes from the DB single above).
    single { InMemoryTelemetryCounters(get()) }
    single<TelemetryCounters> { get<InMemoryTelemetryCounters>() }
    single<TelemetryFlusher> { get<InMemoryTelemetryCounters>() }

    // System-state providers (PowerManager / ActivityManager via context).
    single<ThermalStatusProvider> { AndroidThermalStatusProvider(androidContext()) }
    single<MemoryHeadroomProvider> { AndroidMemoryHeadroomProvider(androidContext()) }

    // PR #56 — remote Ollama config + client + engine (shared OpenAI-compatible
    // engine; only the prefs store is Android-specific).
    single<OllamaPreferences> { SharedPreferencesOllamaPreferences(androidContext()) }
    // PR #74 — the phone never subscribes (it only scans); paid "anywhere access"
    // is provisioned on the desktop. Bind the always-empty subscription state so
    // the shared Settings UI compiles and reports "no subscription".
    single<SubscriptionPreferences> { NoOpSubscriptionPreferences() }
    single<SubscriptionUiController> { NoOpSubscriptionUiController() }
    // Mobile "Unpair" revokes the relay pairing at the gateway via the live MobileClient.
    single<RelayDisconnector> { RelayUnpairDisconnector(get<LinkTransportProvider>()) }
    // Mobile never mints a desktop pairing QR (PR #92) — no-op initiator.
    single<RelayPairingInitiator> { NoOpRelayPairingInitiator }
    single { OllamaClient(get<HttpEngineFactory>(), get<SecureStorage>(), logger = { Log.i("OllamaClient", it) }) }
    single {
        OllamaConnectionMonitor(
            healthProbe = { url -> get<OllamaClient>().health(url, get<OllamaPreferences>().config().serverType) },
            logger = { Log.i("Ollama", it) },
        )
    }
    single {
        OllamaInferenceEngine(
            httpEngineFactory = get(),
            preferences = get(),
            client = get(),
            monitor = get(),
            secureStorage = get(),
            logger = { Log.i("Ollama", it) },
        )
    }

    // PR #57 (relay-only since PR #80) — mobile↔desktop link: prefs + a connection
    // monitor (the OllamaConnectionMonitor class is generic over its health probe;
    // this instance probes the desktop over the current relay transport) + the
    // OpenAI-compatible engine that routes chat to the paired desktop. The status
    // provider mirrors the relay pipe state for the chat-header link dot.
    single<DesktopLinkPreferences> { SharedPreferencesDesktopLinkPreferences(androidContext()) }
    // The link transport seam — the relay transport when a subscription is active +
    // paired (else null → on-device fallback). The desktop-link engine + sync client
    // route through the provider.
    single<RelayBytePipeFactory> {
        AndroidRelayBytePipeFactory(androidContext(), get<SecureStorage>(), logger = { Log.i("Relay", it) })
    }
    single<LinkTransportProvider> {
        DefaultLinkTransportProvider(
            preferences = get(),
            relayFactory = get(),
            // The relay has no pollable health URL — push a reload when it comes
            // up/down so the next turn re-decides (reuses the desktop-link monitor).
            onRelayConnectivityChanged = { get<OllamaConnectionMonitor>(named("desktopLink")).requestReload() },
            logger = { Log.i("Relay", it) },
        )
    }
    single(named("desktopLink")) {
        OllamaConnectionMonitor(
            // Probe the desktop over whatever transport is current (the relay): a
            // HEALTH unary that succeeds iff the relay pipe is up and the desktop answers.
            healthProbe = { _ ->
                val t = get<LinkTransportProvider>().current()
                t != null && t.unary(LinkRequest(LinkMethod.HEALTH)).isSuccess
            },
            logger = { Log.i("DesktopLink", it) },
        )
    }
    single {
        DesktopLinkInferenceEngine(
            transports = get(),
            monitor = get(named("desktopLink")),
            logger = { Log.i("DesktopLink", it) },
        )
    }
    single<DesktopLinkStatusProvider> {
        PollingDesktopLinkStatusProvider(
            preferences = get(),
            relayState = get<LinkTransportProvider>().relayState,
        )
    }
    // Mobile shows no QR (it scans one) — a null provider keeps the shared
    // Settings UI's DesktopLinkQrProvider injection valid on both platforms.
    single<DesktopLinkQrProvider> { NoDesktopLinkQr() }
    single<DesktopLinkConnectionStatus> { NoDesktopLinkConnection() }

    // PR #57 — sync engine. LocalChangeBus is fired by the repos on genuine local
    // writes; SqlDelightLinkSyncService reads/applies change bundles; SyncController
    // drives mobile→desktop reconcile (pull + push + SSE subscribe).
    single { LocalChangeBus() }
    single<SyncWatermarkStore> { SharedPreferencesSyncWatermarkStore(androidContext()) }
    // PR #70 — last-successful-sync wall-clock for the Jobs screen. Seed the
    // mutable holder from disk so the first emission survives a restart.
    single<LastSyncStore> { SharedPreferencesLastSyncStore(androidContext()) }
    single { MutableLastSyncStatus(get<LastSyncStore>().get()) }
    single<LastSyncStatus> { get<MutableLastSyncStatus>() }
    // PR #70 — mobile trusts the authoritative desktop and applies its job records
    // verbatim; mobile only ever pushes a paused toggle.
    single<JobSyncPolicy> { MobileJobSyncPolicy() }
    single<LinkSyncService> {
        SqlDelightLinkSyncService(
            conversations = get(),
            memories = get(),
            jobs = get(),
            jobPolicy = get(),
            embedder = get(),
            bus = get(),
            logger = { Log.i("Sync", it) },
        )
    }
    single { LinkSyncClient(get<LinkTransportProvider>()) }
    // Mobile-only: run-now over the link (PR #84). Desktop runs jobs locally via
    // JobService, so it leaves RemoteJobRunner unbound.
    single<RemoteJobRunner> { RelayRemoteJobRunner(get<LinkTransportProvider>()) }
    // PR #88 — "run job …" inline chat command runs on the paired desktop over the
    // relay and streams its output back. Desktop binds the local variant.
    single<InlineJobRunner> { RelayInlineJobRunner(get<LinkTransportProvider>()) }
    single {
        SyncController(
            preferences = get(),
            local = get(),
            http = get(),
            watermarks = get(),
            lastSync = get(),
            lastSyncStatus = get(),
            logger = { Log.i("Sync", it) },
        )
    }

    // On-device engines. ClassifierEngine/EmbedderEngine are the Managed* lifecycle
    // wrappers (5-min idle unload, warm-up, forceUnload) typed as their interfaces —
    // AuxModelLifecycleCoordinator casts back to Managed* to drive unloads.
    // InferenceEngine honours the USE_STUB_ENGINE flag (mirrors desktopModule's
    // LlamaCppInferenceEngine binding). PR #56 — wrapped in RoutingInferenceEngine
    // so a configured + reachable Ollama server serves chat instead of the local
    // model (falling back to local when unreachable).
    single<InferenceEngine> {
        val local = if (BuildConfig.USE_STUB_ENGINE) StubInferenceEngine()
        else LiteRtInferenceEngineFactory.create(androidContext())
        RoutingInferenceEngine(
            local = local,
            ollama = get<OllamaInferenceEngine>(),
            preferences = get(),
            desktopLink = get<DesktopLinkInferenceEngine>(),
            desktopLinkPreferences = get(),
            logger = { Log.i("Inference", it) },
        )
    }
    single<ClassifierEngine> {
        ManagedClassifierEngine(
            delegate = LiteRtClassifierEngine(androidContext()),
            thermalStatusProvider = get(),
            counters = get(),
        )
    }
    single<EmbedderEngine> {
        ManagedEmbedderEngine(
            delegate = LiteRtEmbedderEngine(context = androidContext(), tokenizer = get()),
            thermalStatusProvider = get(),
            counters = get(),
        )
    }

    // -- Memory subsystem (orchestrators are pure commonMain classes; this is the
    //    Android wiring — promote to a shared Koin module when desktop enables memory). --
    single<MemoryConfig> {
        try {
            val raw = androidContext().assets.open(MEMORY_CONFIG_ASSET_PATH)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            configJson.decodeFromString(MemoryConfig.serializer(), raw)
        } catch (t: Throwable) {
            Log.w(KOIN_TAG, "Failed to load $MEMORY_CONFIG_ASSET_PATH; using DEFAULT (${t.message})")
            MemoryConfig.DEFAULT
        }
    }
    single<SystemMemoryThresholds> {
        try {
            val raw = androidContext().assets.open(SYSTEM_MEMORY_CONFIG_ASSET_PATH)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            configJson.decodeFromString(SystemMemoryThresholds.serializer(), raw)
        } catch (t: Throwable) {
            Log.w(KOIN_TAG, "Failed to load $SYSTEM_MEMORY_CONFIG_ASSET_PATH; using DEFAULT (${t.message})")
            SystemMemoryThresholds.DEFAULT
        }
    }
    single<MemoryStore> { SqlDelightMemoryStore(get(), localChangeBus = get()) }
    single<MemoryPreferences> { SharedPreferencesMemoryPreferences(androidContext()) }
    single { RememberForgetDetector() }
    single { QuestionDetector() }
    single { TempContextDateParser(timeContextProvider = { currentTimeContext(get(), get()) }) }
    single {
        val clock = get<AgentClock>()
        MemoryRetriever(
            embedder = get(),
            store = get(),
            nowProvider = { clock.nowEpochMs() },
            logger = { Log.i("MemoryRetriever", it) },
            counters = get(),
        )
    }
    single {
        val clock = get<AgentClock>()
        val config = get<MemoryConfig>()
        val preferences = get<MemoryPreferences>()
        MemoryExtractor(
            classifier = get(),
            tokenizer = get(),
            embedder = get(),
            store = get(),
            detector = get(),
            questionDetector = get(),
            dateParser = get(),
            nowProvider = { clock.nowEpochMs() },
            configProvider = { config },
            creationEnabledProvider = { preferences.creationEnabled() },
            logger = { Log.i("MemoryExtractor", it) },
            counters = get(),
        )
    }

    // -- Search subsystem -------------------------------------------------------
    // Per-platform like desktopModule (which binds disabled stubs). The 3 Hilt
    // qualifiers (@SportsSearch/@NewsSearch/@FinanceSearch) become Koin named()
    // qualifiers; VerticalSearchModule resolves them via delegating Hilt providers.
    single<BraveKeyProvider> {
        DefaultBraveKeyProvider(get(), if (BuildConfig.INTERNAL_BUILD) BuildConfig.BRAVE_DEV_KEY else null)
    }
    // Default (GENERAL): /llm/context, maxUrls = 3.
    single<BraveSearchClient> { KtorBraveLlmContextClient(get(), maxUrls = 3) { Log.i(BRAVE_TAG, it) } }
    single { SearchCacheDao(queries = get(), nowEpochMs = get<AgentClock>()::nowEpochMs) }

    single<SearchService> {
        SearchService(
            keyProvider = get(),
            client = get(),
            cache = get(),
            isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
            counters = get(),
            cacheNamespace = "ctx:",
        )
    }
    single<SearchService>(named("sports")) {
        SearchService(
            keyProvider = get(),
            client = KtorBraveLlmContextClient(get(), maxUrls = 1) { Log.i(BRAVE_TAG, it) },
            cache = get(),
            isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
            counters = get(),
            cacheNamespace = "sports:",
        )
    }
    single<SearchService>(named("news")) {
        SearchService(
            keyProvider = get(),
            client = KtorBraveLlmContextClient(get(), maxUrls = 10) { Log.i(BRAVE_TAG, it) },
            cache = get(),
            isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
            counters = get(),
            cacheNamespace = "news:",
        )
    }
    single<SearchService>(named("finance")) {
        SearchService(
            keyProvider = get(),
            client = KtorBraveSearchClient(get()) { Log.i(BRAVE_TAG, it) },
            cache = get(),
            isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
            counters = get(),
            cacheNamespace = "fin:",
        )
    }

    // -- Pre-flight classifier router (per-platform: search-availability policy). --
    single<PreflightConfig> {
        try {
            val raw = androidContext().assets.open(PREFLIGHT_CONFIG_ASSET_PATH)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            configJson.decodeFromString(PreflightConfig.serializer(), raw)
        } catch (t: Throwable) {
            Log.w(KOIN_TAG, "Failed to load $PREFLIGHT_CONFIG_ASSET_PATH; using DEFAULT (${t.message})")
            PreflightConfig.DEFAULT
        }
    }
    single {
        val config = get<PreflightConfig>()
        val searchService = get<SearchService>()
        PreflightRouter(
            engine = get(),
            tokenizer = get(),
            rewriter = get(),
            configProvider = { config },
            searchAvailableProvider = { searchService.isAvailable() },
            subtypeDetector = get(),
            logger = { Log.i("ClassifierModule", it) }, // invariant #28: preflight log tag
            counters = get(),
        )
    }

    // -- Vertical search dispatcher (consumes the 4 SearchService variants). --
    single<VerticalSearchDispatcher> {
        VerticalSearchDispatcherFactory.create(
            httpEngineFactory = get(),
            searchService = get(),
            sportsSearchService = get(named("sports")),
            financeSearchService = get(named("finance")),
            newsSearchService = get(named("news")),
            logger = { Log.i("VerticalSearch", it) }, // invariant #28
        )
    }

    // -- Vertical-search preferences + location data (asset-backed). --
    single<DefaultSiteResolver> {
        try {
            val raw = androidContext().assets.open(SEARCH_DEFAULTS_ASSET)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            DefaultSiteResolver(raw)
        } catch (t: Throwable) {
            Log.w(KOIN_TAG, "Failed to load $SEARCH_DEFAULTS_ASSET; empty defaults (${t.message})")
            DefaultSiteResolver(EMPTY_DEFAULTS_JSON)
        }
    }
    single<SearchPreferencesRepository> { DataStoreSearchPreferencesRepository(context = androidContext(), resolver = get()) }
    single<LocationCatalog> {
        try {
            val raw = androidContext().assets.open(LOCATIONS_ASSET)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            LocationCatalog(raw)
        } catch (t: Throwable) {
            Log.w(KOIN_TAG, "Failed to load $LOCATIONS_ASSET; empty catalog (${t.message})")
            LocationCatalog("""{"countries":[]}""")
        }
    }
    single { WeatherLocationResolver(get()) }
    single<OnboardingPreferences> { SharedPreferencesOnboardingPreferences(androidContext()) }

    // -- Deterministic response formatters (object singletons). --
    single { WeatherResponseFormatter }
    single { StockResponseFormatter }

    // AgentLoop's diagnostic logger — keeps the "AgentLoop" logcat tag (invariant #28).
    // Consumed by the AgentLoopFactory binding in the shared agentCoreModule.
    single<AgentLogger> { AgentLogger { Log.i("AgentLoop", it) } }

    // -- Todo subsystem (agent tool). --
    single<TodoRepository> { SqlDelightTodoRepository(get()) }
    single { TodoIntentDetector() }
    single { TodoCommandParser() }
    single { TodoResponseFormatter() }
    single { TodoToolHandler(get()) }
    // TodoViewModel migrated to :ui commonMain (Phase 9 inc 2) — bound in `uiModule`.

    // -- Clock subsystem (agent tool). AlarmScheduler/ClockNotifications are
    //    Android-only; desktop gets coroutine/OS-notification equivalents in Phase 7. --
    single<ClockRepository> { SharedPreferencesClockRepository(androidContext()) }
    single<AlarmScheduler> { AndroidAlarmScheduler(androidContext()) }
    single { ClockNotifications(androidContext()) }
    single { ClockService(repository = get(), scheduler = get()) }
    single { ClockToolHandler(get()) }

    // -- Remaining shared commonMain-interface bindings. --
    single<ConversationRepository> { SqlDelightConversationRepository(get(), get(), localChangeBus = get()) }
    single<LanguagePreferences> { SharedPreferencesLanguagePreferences(androidContext()) }
    single { TranslationIntentDetector() }
    // i18n language packs (PR #96): read `assets/i18n/strings_<code>.json` off the
    // IO dispatcher. None ship today (English is the in-code floor) → returns null
    // and the catalog stays English; drop a pack in to activate a language.
    single<StringPackLoader> {
        val context = androidContext()
        StringPackLoader { code ->
            withContext(Dispatchers.IO) {
                try {
                    context.assets.open("i18n/strings_$code.json")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (_: FileNotFoundException) {
                    null
                } catch (t: Throwable) {
                    Log.w(KOIN_TAG, "Failed to load i18n pack '$code' (${t.message})")
                    null
                }
            }
        }
    }
    single<TelemetryConsentManager> { SharedPreferencesTelemetryConsentManager(androidContext()) }

    // -- Phase 3: app-shell services (formerly Hilt @Inject-constructor / module @Provides).
    //    Koin owns the single instance; the matching Hilt @Provides delegate via getKoin().get()
    //    so the still-Hilt entry points (Application, watchdogs, ViewModels) share it. --

    // Inference session lifecycle + its foreground-service controller.
    single<ForegroundServiceController> { AndroidInferenceForegroundServiceController(androidContext()) }
    single {
        InferenceSessionManager(
            engine = get(),
            foregroundServiceController = get(),
            counters = get(),
        )
    }

    // Aux-engine lifecycle coordinator + system-memory monitor (observability).
    single { AuxModelLifecycleCoordinator(classifierEngine = get(), embedderEngine = get()) }
    single { SystemMemoryMonitor(provider = get(), thresholds = get()) }

    // Main-thread heartbeat probe + the two watchdogs (drive forceUnload — invariant #29).
    single<MainThreadProbe> { HandlerMainThreadProbe() }
    single {
        MainThreadHeartbeatWatchdog(
            sessionManager = get(),
            auxModelCoordinator = get(),
            crashReporter = get(),
            counters = get(),
            probe = get(),
        )
    }
    single {
        MemoryPressureWatchdog(
            sessionManager = get(),
            auxModelCoordinator = get(),
            provider = get(),
            thresholds = get(),
        )
    }

    // Model inventory + download controller (read path + WorkManager kickoff).
    single { ModelInventory(androidContext()) }
    single { ModelDownloadController(androidContext()) }
    // Chat session seam for the shared :ui ChatViewModel (Phase 9): wraps the
    // foreground-service InferenceSessionManager + ModelInventory.
    single<ChatSessionController> { AndroidChatSessionController(get(), get()) }

    // Dedicated OkHttp client for the 2.58 GB model download (tuned timeouts, GET-only).
    single<OkHttpClient>(named("modelDownloadHttp")) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    // HF token for the gated Gemma download (BYOK; internal builds get a dev fallback).
    single<HfAuthTokenProvider> {
        DefaultHfAuthTokenProvider(
            get(),
            if (BuildConfig.INTERNAL_BUILD) BuildConfig.HF_AUTH_TOKEN else null,
        )
    }
    single { ModelDownloader(inventory = get(), httpClient = get(named("modelDownloadHttp")), hfAuthTokenProvider = get()) }

    // Telemetry upload pipeline (opt-in; Firebase analytics sink lives in androidApp, #23).
    single<AnalyticsSink> { FirebaseAnalyticsSink(androidContext()) }
    single { TelemetryPayloadBuilder(get()) }
    single {
        TelemetryUploader(
            consent = get(),
            flusher = get(),
            builder = get(),
            sink = get(),
            queries = get(),
            nowEpochMs = { System.currentTimeMillis() },
        )
    }

    // Memory backup (export/import) — MemoryBackupController : MemoryBackupOps
    // (now in :shared commonMain; file I/O is via the :ui BackupWriter/Reader).
    single<MemoryBackupOps> {
        MemoryBackupController(
            store = get(),
            embedder = get(),
            clock = get(),
            counters = get(),
            config = get(),
            appVersionName = get<AppBuildConfig>().versionName,
            logger = { msg -> Log.i("MemoryBackup", msg) },
        )
    }

    // Voice read-aloud (TTS) seam + persisted toggle.
    single<TtsPreferences> { SharedPreferencesTtsPreferences(androidContext()) }
    single<ChatSpeaker> { AndroidTtsSpeaker(androidContext()) }

    // Vision image preprocessing seam (invariant #39) — decode + downscale a
    // picked photo to the model-ready JPEG. Consumed by the :ui rememberImagePicker
    // Android actual (desktop binds DesktopImagePreprocessor in desktopModule).
    single<ImagePreprocessor> { AndroidImagePreprocessor() }

    // Continuous dictation (SpeechRecognizer), the Android actual of the shared
    // voice.Dictation seam (#42). Driven by the :ui Chat screen.
    single<Dictation> { AndroidDictation(androidContext()) }

    // Chat-screen log sink — keeps the "ChatViewModel" logcat tag (invariant #28)
    // for the shared :ui ChatViewModel.
    single<ChatLogger> { ChatLogger { Log.i("ChatViewModel", it) } }

    // System-memory status dot source (chat header) — re-exposes the
    // SystemMemoryMonitor's status flow behind the shared seam.
    single<SystemMemoryStatusProvider> { AndroidSystemMemoryStatusProvider(get()) }

    // Theme-mode persistence (ThemeMode/ThemePreferences live in :shared; the VM in :ui).
    single<ThemePreferences> { SharedPreferencesThemePreferences(androidContext()) }

    // Crash-reporting facade (Firebase impl lives in androidApp, invariant #23). Redaction
    // is enforced inside FirebaseSafeCrashReporter (invariant #24).
    //
    // Firebase auto-init only runs when a matching `google-services.json` was present
    // at build time — it's gitignored, so it's absent in CI / fresh checkouts and when
    // its `package_name` doesn't match the `applicationId`. Without it,
    // `FirebaseCrashlytics.getInstance()` throws and crashes the app on launch. Fall
    // back to the no-op reporter (its documented purpose) instead of hard-crashing.
    single<SafeCrashReporter> {
        if (FirebaseApp.getApps(androidContext()).isNotEmpty()) FirebaseSafeCrashReporter() else NoOpSafeCrashReporter
    }

    // -- ViewModels (Phase 3): off Hilt, resolved via koinViewModel(). All deps above are
    //    Koin-owned. Context/Application come from androidContext()/androidApplication(). The
    //    VMs still live in :androidApp (Android types intact); they move to :ui in Phase 9. --
    viewModelOf(::MainViewModel)
    // ClockViewModel migrated to :ui (Phase 9 inc 5) — bound in uiModule.
    viewModelOf(::DownloadViewModel)
    // ConversationHistoryViewModel migrated to :ui (Phase 9 inc 4) — bound in uiModule.
    // MemoryViewModel migrated to :ui (Phase 9 inc 7) — bound in uiModule.
    // SystemMemoryStatusViewModel removed (Phase 9 inc 8d) — the chat header dot
    // now reads SystemMemoryStatusProvider (above) via koinInject.
    // OnboardingViewModel migrated to :ui (Phase 9 inc 6) — bound in uiModule.
    // SettingsViewModel + SearchSourcesViewModel migrated to :ui (Phase 9 inc 3) — bound in uiModule.
    // ThemeModeViewModel + ChatViewModel migrated to :ui (Phase 9 inc 8d) — bound in uiModule.
    viewModel { SpikeViewModel(androidApplication(), get()) }
}
