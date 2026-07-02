package com.contextsolutions.localagent.di

import com.contextsolutions.localagent.agent.ChatLogger
import com.contextsolutions.localagent.agent.ChatSessionController
import com.contextsolutions.localagent.agent.ClockToolHandler
import com.contextsolutions.localagent.agent.MyListCommandParser
import com.contextsolutions.localagent.agent.MyListIntentDetector
import com.contextsolutions.localagent.agent.MyListResponseFormatter
import com.contextsolutions.localagent.agent.MyListToolHandler
import com.contextsolutions.localagent.agent.StockResponseFormatter
import com.contextsolutions.localagent.agent.TranslationIntentDetector
import com.contextsolutions.localagent.agent.WeatherResponseFormatter
import com.contextsolutions.localagent.agent.currentTimeContext
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.IosVocabLoader
import com.contextsolutions.localagent.classifier.OnnxIosClassifierEngine
import com.contextsolutions.localagent.classifier.PreflightConfig
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.clock.AlarmScheduler
import com.contextsolutions.localagent.clock.ClockRepository
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.clock.IosAlarmScheduler
import com.contextsolutions.localagent.clock.IosClockRepository
import com.contextsolutions.localagent.conversation.ConversationRepository
import com.contextsolutions.localagent.conversation.SqlDelightConversationRepository
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.i18n.StringPackLoader
import com.contextsolutions.localagent.inference.DesktopLinkInferenceEngine
import com.contextsolutions.localagent.inference.InferenceConfig
import com.contextsolutions.localagent.inference.InferenceEngine
import com.contextsolutions.localagent.inference.IosAuxModelStore
import com.contextsolutions.localagent.inference.IosAuxModelWarmer
import com.contextsolutions.localagent.inference.IosChatSessionController
import com.contextsolutions.localagent.inference.IosMemoryHeadroomProvider
import com.contextsolutions.localagent.inference.IosModelDownloadController
import com.contextsolutions.localagent.inference.IosModelStore
import com.contextsolutions.localagent.inference.IosSystemMemoryStatusProvider
import com.contextsolutions.localagent.inference.IosThermalStatusProvider
import com.contextsolutions.localagent.inference.LiteRtIosInferenceEngine
import com.contextsolutions.localagent.inference.MemoryHeadroomProvider
import com.contextsolutions.localagent.inference.NativeClassifierBridge
import com.contextsolutions.localagent.inference.NativeEmbedderBridge
import com.contextsolutions.localagent.inference.NativeLlmBridge
import com.contextsolutions.localagent.inference.OllamaClient
import com.contextsolutions.localagent.inference.OllamaConnectionMonitor
import com.contextsolutions.localagent.inference.OllamaInferenceEngine
import com.contextsolutions.localagent.inference.PollingDesktopLinkStatusProvider
import com.contextsolutions.localagent.inference.RoutingInferenceEngine
import com.contextsolutions.localagent.inference.SystemMemoryStatusProvider
import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.inference.DesktopLinkStatusProvider
import com.contextsolutions.localagent.language.IosLanguagePreferences
import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.link.DesktopLinkConnectionStatus
import com.contextsolutions.localagent.link.DesktopLinkQrProvider
import com.contextsolutions.localagent.link.NoDesktopLinkConnection
import com.contextsolutions.localagent.link.NoDesktopLinkQr
import com.contextsolutions.localagent.link.transport.DefaultLinkTransportProvider
import com.contextsolutions.localagent.link.transport.IosRelayBytePipeFactory
import com.contextsolutions.localagent.link.transport.LinkMethod
import com.contextsolutions.localagent.link.transport.LinkRequest
import com.contextsolutions.localagent.link.transport.LinkTransportProvider
import com.contextsolutions.localagent.link.transport.NativeRelayBridge
import com.contextsolutions.localagent.link.transport.RelayBytePipeFactory
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.IosMemoryPreferences
import com.contextsolutions.localagent.memory.MemoryBackupController
import com.contextsolutions.localagent.memory.MemoryBackupOps
import com.contextsolutions.localagent.memory.MemoryConfig
import com.contextsolutions.localagent.memory.MemoryExtractor
import com.contextsolutions.localagent.memory.MemoryPreferences
import com.contextsolutions.localagent.memory.MemoryRetriever
import com.contextsolutions.localagent.memory.MemoryStore
import com.contextsolutions.localagent.memory.OnnxIosEmbedderEngine
import com.contextsolutions.localagent.memory.QuestionDetector
import com.contextsolutions.localagent.memory.RememberForgetDetector
import com.contextsolutions.localagent.memory.SqlDelightMemoryStore
import com.contextsolutions.localagent.memory.TempContextDateParser
import com.contextsolutions.localagent.mylist.MyListRepository
import com.contextsolutions.localagent.mylist.SqlDelightMyListRepository
import com.contextsolutions.localagent.notification.IosNotificationPresenter
import com.contextsolutions.localagent.notification.NotificationPresenter
import com.contextsolutions.localagent.observability.NoOpSafeCrashReporter
import com.contextsolutions.localagent.observability.SafeCrashReporter
import com.contextsolutions.localagent.onboarding.IosOnboardingPreferences
import com.contextsolutions.localagent.onboarding.OnboardingPreferences
import com.contextsolutions.localagent.platform.AgentClock
import com.contextsolutions.localagent.platform.AppBuildConfig
import com.contextsolutions.localagent.platform.HttpEngineFactory
import com.contextsolutions.localagent.platform.IosResources
import com.contextsolutions.localagent.platform.IosAppBuildConfig
import com.contextsolutions.localagent.platform.IosDatabaseFactory
import com.contextsolutions.localagent.platform.IosHttpEngineFactory
import com.contextsolutions.localagent.platform.IosJsonStore
import com.contextsolutions.localagent.platform.IosSecureStorage
import com.contextsolutions.localagent.platform.IosToaster
import com.contextsolutions.localagent.platform.NativeQrScanner
import com.contextsolutions.localagent.platform.IosUrlOpener
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.localagent.platform.Toaster
import com.contextsolutions.localagent.platform.UrlOpener
import com.contextsolutions.localagent.preferences.DefaultSiteResolver
import com.contextsolutions.localagent.preferences.DesktopLinkPreferences
import com.contextsolutions.localagent.preferences.IosDesktopLinkPreferences
import com.contextsolutions.localagent.preferences.IosOllamaPreferences
import com.contextsolutions.localagent.preferences.IosSearchPreferencesRepository
import com.contextsolutions.localagent.preferences.LocationCatalog
import com.contextsolutions.localagent.preferences.OllamaPreferences
import com.contextsolutions.localagent.preferences.SearchPreferencesRepository
import com.contextsolutions.localagent.preferences.WeatherLocationResolver
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.DefaultBraveKeyProvider
import com.contextsolutions.localagent.search.KtorBraveLlmContextClient
import com.contextsolutions.localagent.search.KtorBraveSearchClient
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcher
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcherFactory
import com.contextsolutions.localagent.subscription.NoOpRelayPairingInitiator
import com.contextsolutions.localagent.subscription.NoOpSubscriptionPreferences
import com.contextsolutions.localagent.subscription.NoOpSubscriptionUiController
import com.contextsolutions.localagent.subscription.RelayDisconnector
import com.contextsolutions.localagent.subscription.RelayUnpairDisconnector
import com.contextsolutions.localagent.subscription.RelayPairingInitiator
import com.contextsolutions.localagent.subscription.SubscriptionPreferences
import com.contextsolutions.localagent.subscription.SubscriptionUiController
import com.contextsolutions.localagent.sync.IosLastSyncStore
import com.contextsolutions.localagent.sync.IosSyncWatermarkStore
import com.contextsolutions.localagent.sync.JobSyncPolicy
import com.contextsolutions.localagent.sync.LastSyncStatus
import com.contextsolutions.localagent.sync.LastSyncStore
import com.contextsolutions.localagent.sync.LinkSyncClient
import com.contextsolutions.localagent.sync.LinkSyncService
import com.contextsolutions.localagent.sync.LocalChangeBus
import com.contextsolutions.localagent.sync.MobileJobSyncPolicy
import com.contextsolutions.localagent.sync.MutableLastSyncStatus
import com.contextsolutions.localagent.sync.SqlDelightLinkSyncService
import com.contextsolutions.localagent.sync.SyncController
import com.contextsolutions.localagent.sync.SyncWatermarkStore
import com.contextsolutions.localagent.telemetry.AnalyticsSink
import com.contextsolutions.localagent.telemetry.IosTelemetryConsentManager
import com.contextsolutions.localagent.telemetry.NoOpAnalyticsSink
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.localagent.telemetry.NoOpTelemetryFlusher
import com.contextsolutions.localagent.telemetry.TelemetryConsentManager
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryFlusher
import com.contextsolutions.localagent.telemetry.TelemetryPayloadBuilder
import com.contextsolutions.localagent.telemetry.TelemetryUploader
import com.contextsolutions.localagent.job.JobRepository
import com.contextsolutions.localagent.job.SqlDelightJobRepository
import com.contextsolutions.localagent.ui.theme.IosThemePreferences
import com.contextsolutions.localagent.ui.theme.ThemePreferences
import com.contextsolutions.localagent.voice.ChatSpeaker
import com.contextsolutions.localagent.voice.Dictation
import com.contextsolutions.localagent.voice.IosChatSpeaker
import com.contextsolutions.localagent.voice.IosSpeechDictation
import com.contextsolutions.localagent.voice.IosTtsPreferences
import com.contextsolutions.localagent.voice.TtsPreferences
import com.contextsolutions.localagent.vision.ImagePreprocessor
import com.contextsolutions.localagent.vision.IosImagePreprocessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Clock
import com.contextsolutions.localagent.platform.platformIoDispatcher
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val EMPTY_DEFAULTS_JSON = """{"fallback":"US","countries":{"US":{}}}"""

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * iOS platform bindings for the Koin graph (PR #41) — the counterpart of
 * `androidModule` / `desktopModule`. Combined with [agentCoreModule] and the
 * `:ui` `uiModule` this resolves a complete agent graph on iOS.
 *
 * Chat runs on-device via [LiteRtIosInferenceEngine] (the Swift [NativeLlmBridge]
 * passed in from `IosEntryPoint.doInitKoin`) wrapped in [RoutingInferenceEngine]
 * (remote Ollama wins when configured + reachable). Persistence is the native
 * SQLite driver; secrets are the Keychain; networking is Ktor/Darwin. The Secure Gateway
 * relay ([NativeRelayBridge], the Swift SecureGatewaySDK) tunnels chat + sync to a paired
 * desktop; subscription/jobs-admin stay no-op (the phone pairs, never buys).
 */
fun iosModule(
    llmBridge: NativeLlmBridge,
    classifierBridge: NativeClassifierBridge,
    embedderBridge: NativeEmbedderBridge,
    relayBridge: NativeRelayBridge,
    qrScanner: NativeQrScanner,
): Module = module {
    // The Swift bridges, injected from the app shell: LiteRT-LM (LLM) + ONNX Runtime
    // (classifier/embedder) + Secure Gateway relay + camera QR scanner. See NativeLlmBridge /
    // NativeAuxBridges / NativeRelayBridge / NativeQrScanner.
    single<NativeLlmBridge> { llmBridge }
    single<NativeClassifierBridge> { classifierBridge }
    single<NativeEmbedderBridge> { embedderBridge }
    single<NativeRelayBridge> { relayBridge }
    single<NativeQrScanner> { qrScanner }

    // -- Platform seams --
    single<HttpEngineFactory> { IosHttpEngineFactory() }
    single<SecureStorage> { IosSecureStorage() }
    single<AppBuildConfig> { IosAppBuildConfig() }
    single<UrlOpener> { IosUrlOpener() }
    single<Toaster> { IosToaster() }
    single<ThermalStatusProvider> { IosThermalStatusProvider() }
    single<MemoryHeadroomProvider> { IosMemoryHeadroomProvider() }
    single<SystemMemoryStatusProvider> { IosSystemMemoryStatusProvider() }
    single<NotificationPresenter> { IosNotificationPresenter() }
    single<SafeCrashReporter> { NoOpSafeCrashReporter }

    // -- Database + per-table queries --
    single { LocalAgentDatabase(IosDatabaseFactory.create()) }
    single { get<LocalAgentDatabase>().searchCacheQueries }
    single { get<LocalAgentDatabase>().telemetryAggregateQueries }
    single { get<LocalAgentDatabase>().jobsQueries }
    single { get<LocalAgentDatabase>().conversationsQueries }
    single { get<LocalAgentDatabase>().myListQueries }
    single { get<LocalAgentDatabase>().memoriesQueries }

    // -- Vocab: the real 30,522-entry WordPiece vocab bundled in the app (invariant
    //    #13), needed now that the classifier/embedder run for real. Falls back to a
    //    5-token stub only if the bundle resource is missing (tokenizer never runs
    //    while both aux engines are absent, so the stub keeps the graph resolvable). --
    single { IosVocabLoader.loadOrNull() ?: Vocab.fromLines(sequenceOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")) }

    // -- Telemetry (opt-in, off by default; egress is a NoOp sink on iOS). --
    single<TelemetryCounters> { NoOpTelemetryCounters }
    single<TelemetryFlusher> { NoOpTelemetryFlusher }
    single<AnalyticsSink> { NoOpAnalyticsSink }
    single { TelemetryPayloadBuilder(get()) }
    single {
        TelemetryUploader(
            consent = get(),
            flusher = get(),
            builder = get(),
            sink = get(),
            queries = get(),
            nowEpochMs = ::nowMs,
        )
    }

    // -- Sync engine (relay-tunneled, mirrors androidModule). LocalChangeBus is fired by the repos
    //    on genuine local writes; SqlDelightLinkSyncService reads/applies change bundles;
    //    SyncController drives mobile→desktop reconcile over the relay transport. Separate prefs
    //    files per store (each IosJsonStore persists its whole map — sharing one file would clobber). --
    single { LocalChangeBus() }
    single<SyncWatermarkStore> { IosSyncWatermarkStore(IosJsonStore("sync_watermark.json")) }
    single<LastSyncStore> { IosLastSyncStore(IosJsonStore("last_sync.json")) }
    single { MutableLastSyncStatus(get<LastSyncStore>().get()) }
    single<LastSyncStatus> { get<MutableLastSyncStatus>() }
    // Mobile trusts the authoritative desktop and applies its job records verbatim; mobile only
    // ever pushes a paused toggle (jobs-admin stays desktop-only, #78).
    single<JobSyncPolicy> { MobileJobSyncPolicy() }
    single<LinkSyncService> {
        SqlDelightLinkSyncService(
            conversations = get(),
            memories = get(),
            jobs = get(),
            myList = get(),
            jobPolicy = get(),
            embedder = get(),
            bus = get(),
            logger = diag("Sync"),
        )
    }
    single { LinkSyncClient(get<LinkTransportProvider>()) }
    single {
        SyncController(
            preferences = get(),
            local = get(),
            http = get(),
            watermarks = get(),
            lastSync = get(),
            lastSyncStatus = get(),
            logger = diag("Sync"),
        )
    }

    // -- Jobs: read-only on iOS (no admin/scheduler/executor → JobsViewModel gets them via getOrNull). --
    single<JobRepository> { SqlDelightJobRepository(queries = get(), bus = get()) }

    // -- Remote Ollama (the configured-remote chat path). --
    single<OllamaPreferences> { IosOllamaPreferences(IosJsonStore("ollama_prefs.json")) }
    single { OllamaClient(get<HttpEngineFactory>(), get<SecureStorage>(), logger = diag("OllamaClient")) }
    single {
        OllamaConnectionMonitor(
            healthProbe = { url -> get<OllamaClient>().health(url, get<OllamaPreferences>().config().serverType) },
            logger = diag("Ollama"),
        )
    }
    single {
        OllamaInferenceEngine(
            httpEngineFactory = get(),
            preferences = get(),
            client = get(),
            monitor = get(),
            secureStorage = get(),
            logger = diag("Ollama"),
        )
    }

    // -- Subscription: no-op on iOS — the phone pairs to a subscribed desktop, never buys (#54). --
    single<SubscriptionPreferences> { NoOpSubscriptionPreferences() }
    single<SubscriptionUiController> { NoOpSubscriptionUiController() }
    // Mobile "Unpair" revokes the relay pairing at the gateway via the live MobileClient.
    single<RelayDisconnector> { RelayUnpairDisconnector(get<LinkTransportProvider>()) }
    // Mobile never mints a desktop pairing QR — it scans one. No-op initiator.
    single<RelayPairingInitiator> { NoOpRelayPairingInitiator }

    // -- Secure Gateway relay transport (mirrors androidModule; the Swift NativeRelayBridge wraps
    //    the SecureGatewaySDK MobileClient). Chat routes to the paired desktop over the relay + sync
    //    rides the same transport; falls back on-device when the link is down. --
    single<DesktopLinkPreferences> { IosDesktopLinkPreferences(IosJsonStore("desktop_link_prefs.json")) }
    single<RelayBytePipeFactory> {
        // Jobs are desktop-specific: pairing a DIFFERENT desktop wipes stale local jobs + resets the
        // sync watermark so the new desktop's state re-pulls. Resolve eagerly so the lambda doesn't
        // capture the Koin scope.
        val jobRepository = get<JobRepository>()
        val watermarks = get<SyncWatermarkStore>()
        IosRelayBytePipeFactory(
            bridge = get(),
            secureStorage = get(),
            logger = diag("Relay"),
            onPairedDifferentDesktop = {
                jobRepository.wipeLocal()
                watermarks.set(0)
            },
        )
    }
    single<LinkTransportProvider> {
        DefaultLinkTransportProvider(
            preferences = get(),
            relayFactory = get(),
            // The relay has no pollable health URL — push a reload when it comes up/down so the next
            // turn re-decides (reuses the desktop-link monitor).
            onRelayConnectivityChanged = { get<OllamaConnectionMonitor>(named("desktopLink")).requestReload() },
            logger = diag("Relay"),
        )
    }
    single(named("desktopLink")) {
        OllamaConnectionMonitor(
            // Probe the desktop over whatever transport is current (the relay): a HEALTH unary that
            // succeeds iff the relay pipe is up and the desktop answers.
            healthProbe = { _ ->
                val t = get<LinkTransportProvider>().current()
                t != null && t.unary(LinkRequest(LinkMethod.HEALTH)).isSuccess
            },
            logger = diag("DesktopLink"),
        )
    }
    single {
        DesktopLinkInferenceEngine(
            transports = get(),
            monitor = get(named("desktopLink")),
            logger = diag("DesktopLink"),
        )
    }
    single<DesktopLinkStatusProvider> {
        PollingDesktopLinkStatusProvider(preferences = get(), relayState = get<LinkTransportProvider>().relayState)
    }
    // Mobile shows no QR (it scans one) — null providers keep the shared Settings UI valid.
    single<DesktopLinkQrProvider> { NoDesktopLinkQr() }
    single<DesktopLinkConnectionStatus> { NoDesktopLinkConnection() }

    // -- The inference seam: on-device LiteRT-LM (Swift bridge) + remote Ollama. --
    single<InferenceEngine> {
        RoutingInferenceEngine(
            // Vision ON via the CPU/XNNPack vision backend (LiteRtBridge sets
            // visionBackend = .cpu()). Gemma 4 E2B's SigLIP encoder is fully
            // XNNPack-delegatable on iOS (LiteRT-LM #2370); routing vision to Metal
            // instead hits the compiled-model STABLEHLO_COMPOSITE op that is not
            // registered in the iOS dylib → "Failed to create conversation". iOS vision
            // is CPU-only ("known Metal constraint", #2385). The text LLM stays on Metal.
            local = LiteRtIosInferenceEngine(bridge = get(), enableVision = true),
            ollama = get<OllamaInferenceEngine>(),
            preferences = get(),
            desktopLink = get<DesktopLinkInferenceEngine>(),
            desktopLinkPreferences = get(),
            logger = diag("Inference"),
        )
    }
    // -- Image input: decode + downscale a picked photo to the model-ready JPEG (#39). --
    single<ImagePreprocessor> { IosImagePreprocessor() }
    // -- Aux models: real ONNX classifier + embedder via the Swift ORT bridges. The
    //    model path is download-gated (IosAuxModelStore) so warmUp() no-ops until the
    //    .onnx is present; IosAuxModelWarmer fetches + warms lazily per enabled feature. --
    single { IosAuxModelStore() }
    single<ClassifierEngine> {
        OnnxIosClassifierEngine(
            bridge = get(),
            modelPath = { get<IosAuxModelStore>().classifierPathIfPresent() },
            logger = diag("Classifier"),
        )
    }
    single<EmbedderEngine> {
        OnnxIosEmbedderEngine(
            bridge = get(),
            tokenizer = get(),
            modelPath = { get<IosAuxModelStore>().embedderPathIfPresent() },
            logger = diag("Embedder"),
        )
    }
    single {
        IosAuxModelWarmer(
            store = get(),
            classifier = get(),
            embedder = get(),
            secureStorage = get(),
            memoryPreferences = get(),
            scope = appScope(),
            logger = diag("AuxWarmer"),
        )
    }

    // -- Model download gate (first-run Gemma .litertlm fetch). --
    single { IosModelStore() }
    single { IosModelDownloadController(store = get(), scope = appScope()) }

    // -- Chat session controller (keeps the model resident once loaded). --
    single<ChatSessionController> {
        IosChatSessionController(
            engine = get(),
            modelPath = { get<IosModelDownloadController>().modelPath() },
            config = InferenceConfig(enableVision = true), // vision via CPU/XNNPack (see engine binding above)
            // Drop the resident handle so the next turn re-decides the backend (#44): the relay
            // going down (desktop-link monitor) or a remote/link config change → fall back to the
            // on-device GPU instead of retrying the dead link.
            ollamaMonitor = get(),
            desktopLinkMonitor = get(named("desktopLink")),
            ollamaPreferences = get(),
            desktopLinkPreferences = get(),
            scope = appScope(),
        )
    }

    // -- Memory subsystem (embedder is NoOp → retriever/extractor degrade to no-ops). --
    single<MemoryConfig> { MemoryConfig.DEFAULT }
    single<MemoryStore> { SqlDelightMemoryStore(get(), localChangeBus = get()) }
    single<MemoryPreferences> { IosMemoryPreferences(IosJsonStore("memory_prefs.json")) }
    single { RememberForgetDetector() }
    single { QuestionDetector() }
    single { TempContextDateParser(timeContextProvider = { currentTimeContext(get(), get()) }) }
    single {
        val clock = get<AgentClock>()
        MemoryRetriever(
            embedder = get(),
            store = get(),
            nowProvider = { clock.nowEpochMs() },
            logger = diag("MemoryRetriever"),
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
            logger = diag("MemoryExtractor"),
            counters = get(),
        )
    }
    single<MemoryBackupOps> {
        MemoryBackupController(
            store = get(),
            embedder = get(),
            clock = get(),
            counters = get(),
            config = get(),
            appVersionName = get<AppBuildConfig>().versionName,
            logger = diag("MemoryBackup"),
        )
    }

    // -- Search subsystem (opt-in; off until a Brave key + the toggle are set). --
    single<BraveKeyProvider> { DefaultBraveKeyProvider(get(), null) }
    single<BraveSearchClient> { KtorBraveLlmContextClient(get(), maxUrls = 3) {} }
    single { SearchCacheDao(queries = get(), nowEpochMs = get<AgentClock>()::nowEpochMs) }
    single<SearchService> { searchService(get(), KtorBraveLlmContextClient(get(), maxUrls = 3) {}, get(), get(), "ctx:") }
    single<SearchService>(named("sports")) {
        searchService(get(), KtorBraveLlmContextClient(get(), maxUrls = 1) {}, get(), get(), "sports:")
    }
    single<SearchService>(named("news")) {
        searchService(get(), KtorBraveLlmContextClient(get(), maxUrls = 10) {}, get(), get(), "news:")
    }
    single<SearchService>(named("finance")) {
        searchService(get(), KtorBraveSearchClient(get()) {}, get(), get(), "fin:")
    }

    // -- Pre-flight classifier router (engine is NoOp → falls through to the LLM). --
    single<PreflightConfig> { PreflightConfig.DEFAULT }
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
            logger = diag("ClassifierModule"),
            counters = get(),
        )
    }
    single<VerticalSearchDispatcher> {
        VerticalSearchDispatcherFactory.create(
            httpEngineFactory = get(),
            searchService = get(),
            sportsSearchService = get(named("sports")),
            financeSearchService = get(named("finance")),
            newsSearchService = get(named("news")),
            logger = diag("VerticalSearch"),
            logQueries = false,
        )
    }

    // -- Vertical-search prefs + location data: real bundled defaults (invariant #31/#32),
    //    so the site-pinned SPORTS/NEWS/FINANCE verticals + the deterministic WEATHER
    //    force-fire (which needs a resolvable city) work. Fall back to empty if a bundle
    //    resource is missing. --
    single<DefaultSiteResolver> {
        DefaultSiteResolver(IosResources.readTextOrNull("search_defaults.json") ?: EMPTY_DEFAULTS_JSON)
    }
    single<SearchPreferencesRepository> { IosSearchPreferencesRepository(IosJsonStore("search_prefs.json"), get()) }
    single<LocationCatalog> {
        LocationCatalog(IosResources.readTextOrNull("locations.json") ?: """{"countries":[]}""")
    }
    single { WeatherLocationResolver(get()) }
    single<OnboardingPreferences> { IosOnboardingPreferences(IosJsonStore("onboarding_prefs.json")) }

    // -- Deterministic response formatters (objects). --
    single { WeatherResponseFormatter }
    single { StockResponseFormatter }
    single<AgentLogger> { AgentLogger(diag("AgentLoop")) }

    // -- My List. --
    single<MyListRepository> { SqlDelightMyListRepository(get(), get()) }
    single { MyListIntentDetector() }
    single { MyListCommandParser() }
    single { MyListResponseFormatter() }
    single { MyListToolHandler(get()) }

    // -- Clock (view-only on iOS; the scheduler is a no-op). --
    single<ClockRepository> { IosClockRepository(IosJsonStore("clock_prefs.json")) }
    // clockServiceProvider is lazy to break the ClockService↔scheduler DI cycle
    // (mirrors DesktopAlarmScheduler): the scheduler is built first; ClockService is
    // resolved on the first arm, by which point the graph is complete.
    single<AlarmScheduler> {
        IosAlarmScheduler(clockServiceProvider = { get<ClockService>() }, logger = diag("Clock"))
    }
    single { ClockService(repository = get(), scheduler = get()) }
    single { ClockToolHandler(get()) }

    // -- Remaining shared bindings. --
    single<ConversationRepository> { SqlDelightConversationRepository(get(), get(), localChangeBus = get()) }
    single<LanguagePreferences> { IosLanguagePreferences(IosJsonStore("language_prefs.json")) }
    single { TranslationIntentDetector() }
    single<StringPackLoader> { StringPackLoader { null } }
    single<TelemetryConsentManager> { IosTelemetryConsentManager(IosJsonStore("telemetry_consent.json")) }
    single<ChatLogger> { ChatLogger(diag("ChatViewModel")) }
    single<ThemePreferences> { IosThemePreferences(IosJsonStore("theme_prefs.json")) }
    single<TtsPreferences> { IosTtsPreferences(IosJsonStore("tts_prefs.json")) }
    single<ChatSpeaker> { IosChatSpeaker() }
    single<Dictation> { IosSpeechDictation(logger = diag("Dictation")) }
}

/** Long-lived scope for background work owned by iOS singletons (download, monitors). */
private fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + platformIoDispatcher)

private fun diag(tag: String): (String) -> Unit = { println("[$tag] $it") }

/** Shared SearchService builder (the 4 variants differ only by client + cache namespace). */
private fun org.koin.core.scope.Scope.searchService(
    keyProvider: BraveKeyProvider,
    client: BraveSearchClient,
    cache: SearchCacheDao,
    counters: TelemetryCounters,
    cacheNamespace: String,
): SearchService = SearchService(
    keyProvider = keyProvider,
    client = client,
    cache = cache,
    isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) == "true" },
    counters = counters,
    cacheNamespace = cacheNamespace,
)
