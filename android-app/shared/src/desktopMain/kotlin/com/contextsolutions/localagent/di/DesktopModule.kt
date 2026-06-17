package com.contextsolutions.localagent.di

import app.cash.sqldelight.db.SqlDriver
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.DesktopVocabLoader
import com.contextsolutions.localagent.classifier.OnnxClassifierEngine
import com.contextsolutions.localagent.classifier.PreflightConfig
import com.contextsolutions.localagent.i18n.StringPackLoader
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.inference.DesktopAuxModels
import com.contextsolutions.localagent.inference.DesktopAuxModelStore
import com.contextsolutions.localagent.inference.DesktopModelDownloader
import com.contextsolutions.localagent.agent.ChatLogger
import com.contextsolutions.localagent.agent.TranslationIntentDetector
import com.contextsolutions.localagent.conversation.ConversationRepository
import com.contextsolutions.localagent.conversation.SqlDelightConversationRepository
import com.contextsolutions.localagent.inference.DesktopModelInventory
import com.contextsolutions.localagent.inference.DesktopSystemMemoryStatusProvider
import com.contextsolutions.localagent.inference.SystemMemoryStatusProvider
import com.contextsolutions.localagent.inference.InferenceEngine
import com.contextsolutions.localagent.ui.theme.DesktopThemePreferences
import com.contextsolutions.localagent.ui.theme.DesktopWindowPreferences
import com.contextsolutions.localagent.ui.theme.ThemePreferences
import com.contextsolutions.localagent.inference.LlamaServerBinaryStore
import com.contextsolutions.localagent.inference.LlamaServerDevices
import com.contextsolutions.localagent.inference.LlamaServerInferenceEngine
import com.contextsolutions.localagent.inference.OllamaClient
import com.contextsolutions.localagent.inference.OllamaConnectionMonitor
import com.contextsolutions.localagent.inference.OllamaInferenceEngine
import com.contextsolutions.localagent.inference.RoutingInferenceEngine
import com.contextsolutions.localagent.inference.DesktopLinkStatusProvider
import com.contextsolutions.localagent.inference.PollingDesktopLinkStatusProvider
import com.contextsolutions.localagent.link.DesktopLinkConnectionStatus
import com.contextsolutions.localagent.link.DesktopLinkQrProvider
import com.contextsolutions.localagent.link.MutableDesktopLinkConnectionStatus
import com.contextsolutions.localagent.link.MutableDesktopLinkQr
import com.contextsolutions.localagent.sync.DesktopLastSyncStore
import com.contextsolutions.localagent.sync.DesktopSyncWatermarkStore
import com.contextsolutions.localagent.sync.DesktopJobSyncPolicy
import com.contextsolutions.localagent.sync.JobSyncPolicy
import com.contextsolutions.localagent.sync.LastSyncStatus
import com.contextsolutions.localagent.sync.LastSyncStore
import com.contextsolutions.localagent.sync.LinkSyncService
import com.contextsolutions.localagent.sync.LocalChangeBus
import com.contextsolutions.localagent.sync.MutableLastSyncStatus
import com.contextsolutions.localagent.sync.SqlDelightLinkSyncService
import com.contextsolutions.localagent.sync.SyncWatermarkStore
import com.contextsolutions.localagent.job.DesktopJobNotificationPrefs
import com.contextsolutions.localagent.job.DesktopJobScheduler
import com.contextsolutions.localagent.job.InlineJobRunner
import com.contextsolutions.localagent.job.JobAdmin
import com.contextsolutions.localagent.job.JobCompletionNotifier
import com.contextsolutions.localagent.job.JobExecutor
import com.contextsolutions.localagent.job.JobNotificationPrefs
import com.contextsolutions.localagent.job.JobRepository
import com.contextsolutions.localagent.job.JobService
import com.contextsolutions.localagent.job.LocalInlineJobRunner
import com.contextsolutions.localagent.job.SqlDelightJobRepository
import com.contextsolutions.localagent.preferences.DesktopDesktopLinkPreferences
import com.contextsolutions.localagent.preferences.DesktopGpuPreferences
import com.contextsolutions.localagent.preferences.DesktopLinkPreferences
import com.contextsolutions.localagent.preferences.DesktopOllamaPreferences
import com.contextsolutions.localagent.preferences.DesktopSubscriptionPreferences
import com.contextsolutions.localagent.preferences.OllamaPreferences
import com.contextsolutions.localagent.subscription.DesktopRelayHost
import com.contextsolutions.localagent.subscription.DesktopSubscriptionUiController
import com.contextsolutions.localagent.subscription.RelayDisconnector
import com.contextsolutions.localagent.subscription.RelayGatewayClient
import com.contextsolutions.localagent.subscription.RelayPairingInitiator
import com.contextsolutions.localagent.subscription.RelaySubscriptionService
import com.contextsolutions.localagent.subscription.SubscriptionPreferences
import com.contextsolutions.localagent.subscription.SubscriptionUiController
import com.contextsolutions.localagent.memory.DesktopMemoryPreferences
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.MemoryPreferences
import com.contextsolutions.localagent.memory.OnnxEmbedderEngine
import com.contextsolutions.localagent.preferences.DefaultSiteResolver
import com.contextsolutions.localagent.preferences.DesktopSearchPreferencesRepository
import com.contextsolutions.localagent.preferences.SearchPreferencesRepository
import com.contextsolutions.localagent.platform.AgentClock
import com.contextsolutions.localagent.platform.AppBuildConfig
import com.contextsolutions.localagent.platform.DesktopAppBuildConfig
import com.contextsolutions.localagent.platform.DesktopToaster
import com.contextsolutions.localagent.platform.DesktopUrlOpener
import com.contextsolutions.localagent.platform.Toaster
import com.contextsolutions.localagent.platform.UrlOpener
import com.contextsolutions.localagent.platform.DesktopDatabaseFactory
import com.contextsolutions.localagent.platform.DesktopHttpEngineFactory
import com.contextsolutions.localagent.platform.DesktopJsonStore
import com.contextsolutions.localagent.platform.DesktopSecureStorage
import com.contextsolutions.localagent.platform.HttpEngineFactory
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.inference.DesktopAppDirs
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.contextsolutions.localagent.search.BraveKeyProvider
import com.contextsolutions.localagent.search.BraveSearchClient
import com.contextsolutions.localagent.search.DefaultBraveKeyProvider
import com.contextsolutions.localagent.search.KtorBraveLlmContextClient
import com.contextsolutions.localagent.search.KtorBraveSearchClient
import com.contextsolutions.localagent.search.SearchCacheDao
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcher
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcherFactory
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.localagent.preferences.LocationCatalog
import com.contextsolutions.localagent.preferences.WeatherLocationResolver
import com.contextsolutions.localagent.agent.StockResponseFormatter
import com.contextsolutions.localagent.agent.MyListCommandParser
import com.contextsolutions.localagent.agent.MyListIntentDetector
import com.contextsolutions.localagent.agent.MyListResponseFormatter
import com.contextsolutions.localagent.agent.MyListToolHandler
import com.contextsolutions.localagent.agent.WeatherResponseFormatter
import com.contextsolutions.localagent.agent.currentTimeContext
import com.contextsolutions.localagent.memory.MemoryBackupController
import com.contextsolutions.localagent.memory.MemoryBackupOps
import com.contextsolutions.localagent.memory.MemoryConfig
import com.contextsolutions.localagent.memory.MemoryExtractor
import com.contextsolutions.localagent.memory.MemoryRetriever
import com.contextsolutions.localagent.memory.MemoryStore
import com.contextsolutions.localagent.memory.QuestionDetector
import com.contextsolutions.localagent.memory.RememberForgetDetector
import com.contextsolutions.localagent.memory.SqlDelightMemoryStore
import com.contextsolutions.localagent.memory.TempContextDateParser
import com.contextsolutions.localagent.mylist.SqlDelightMyListRepository
import com.contextsolutions.localagent.mylist.MyListRepository
import org.koin.core.qualifier.named
import com.contextsolutions.localagent.platform.DesktopResources
import com.contextsolutions.localagent.inference.DesktopMemoryHeadroomProvider
import com.contextsolutions.localagent.inference.DesktopThermalStatusProvider
import com.contextsolutions.localagent.inference.MemoryHeadroomProvider
import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.language.DesktopLanguagePreferences
import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.onboarding.DesktopOnboardingPreferences
import com.contextsolutions.localagent.onboarding.OnboardingPreferences
import com.contextsolutions.localagent.telemetry.DesktopTelemetryConsentManager
import com.contextsolutions.localagent.telemetry.TelemetryConsentManager
import com.contextsolutions.localagent.agent.ClockToolHandler
import com.contextsolutions.localagent.clock.AlarmScheduler
import com.contextsolutions.localagent.clock.ClockRepository
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.clock.DesktopAlarmScheduler
import com.contextsolutions.localagent.clock.DesktopClockRepository
import com.contextsolutions.localagent.notification.DesktopNotificationPresenter
import com.contextsolutions.localagent.notification.DesktopOs
import com.contextsolutions.localagent.notification.LinuxNotificationPresenter
import com.contextsolutions.localagent.notification.MutableNotificationPresenter
import com.contextsolutions.localagent.notification.NotificationPresenter
import com.contextsolutions.localagent.observability.SafeCrashReporter
import com.contextsolutions.localagent.observability.SentrySafeCrashReporter
import com.contextsolutions.localagent.telemetry.AnalyticsSink
import com.contextsolutions.localagent.telemetry.DesktopTelemetryScheduler
import com.contextsolutions.localagent.telemetry.FileAnalyticsSink
import com.contextsolutions.localagent.telemetry.InMemoryTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryFlusher
import com.contextsolutions.localagent.telemetry.TelemetryPayloadBuilder
import com.contextsolutions.localagent.telemetry.TelemetryUploader
import com.contextsolutions.localagent.task.SqlDelightTaskRepository
import com.contextsolutions.localagent.task.TaskRepository
import com.contextsolutions.localagent.voice.ChatSpeaker
import com.contextsolutions.localagent.voice.Dictation
import com.contextsolutions.localagent.voice.DesktopTtsSpeaker
import com.contextsolutions.localagent.voice.DesktopTtsPreferences
import com.contextsolutions.localagent.voice.DesktopTtsVoices
import com.contextsolutions.localagent.voice.PiperBinaryStore
import com.contextsolutions.localagent.voice.PiperSpeechSynthesizer
import com.contextsolutions.localagent.voice.PiperVoiceStore
import com.contextsolutions.localagent.voice.TtsPreferences
import com.contextsolutions.localagent.voice.VoskDictation
import com.contextsolutions.localagent.voice.VoskModelStore
import com.contextsolutions.localagent.vision.DesktopFilePicker
import com.contextsolutions.localagent.vision.DesktopImagePreprocessor
import com.contextsolutions.localagent.vision.FilePicker
import com.contextsolutions.localagent.vision.ImagePreprocessor
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module

// Lenient JSON for the bundled *_config.json / search_defaults.json assets,
// matching androidModule's configJson (ignoreUnknownKeys + isLenient).
private val configJson = Json { ignoreUnknownKeys = true; isLenient = true }

// Wall-clock prefix for desktop stderr logs (no logcat off-device).
private val AGENT_LOG_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

// Fallback when search_defaults.json can't be read (matches androidModule).
private const val EMPTY_DEFAULTS_JSON = """{"fallback":"US","countries":{"US":{}}}"""

/**
 * Desktop (JVM) bindings for the agent DI graph (docs/DESKTOP_PORT_PLAN.md, Phase 2).
 * Combined with [agentCoreModule] this resolves a complete agent core on desktop.
 *
 * As of Phase 6 this is a near-complete agent graph: the real
 * `LlamaCppInferenceEngine` (Phase 4) + ONNX classifier/embedder (Phase 5) +
 * file-backed SQLite + PKCS#12 `SecureStorage` + file-JSON preferences, with
 * **search ON** (the four Brave `SearchService` variants + vertical dispatcher,
 * gated by a Brave key from `SecureStorage`/`BRAVE_API_KEY`), **memory** (store +
 * retriever + extractor over the live embedder), and the **My List** tool. Every
 * model-dependent piece degrades gracefully when its artifact/key is absent
 * (null engine warmUp, `SearchService.isAvailable()` = false) so the graph
 * resolves with or without the downloaded models / a Brave key present.
 *
 * Phase 7 adds: the deferred system-state providers (thermal/headroom) and
 * UI/first-run preferences (onboarding/language/telemetry-consent) [inc 1]; and
 * the clock subsystem — file-JSON ClockRepository + DesktopAlarmScheduler
 * (coroutine delay-until-instant, replacing AlarmManager) + the
 * NotificationPresenter seam + ClockService/ClockToolHandler [inc 2]; and the
 * telemetry pipeline — InMemoryTelemetryCounters + a file/JSONL AnalyticsSink +
 * a Sentry SafeCrashReporter + the TelemetryUploader/scheduler, gated by
 * TelemetryConsentManager [inc 3]; the queued-task persistence (TaskRepository
 * over the v8 `tasks` table) [inc 4]; a swappable NotificationPresenter the
 * :desktopApp tray installs a TrayState-backed delegate into [inc 5]; voice I/O
 * (ChatSpeaker→DesktopTtsSpeaker, Dictation→VoskDictation) [inc 8]; and the
 * vision image pipeline (FilePicker + ImagePreprocessor — engine vision stays off
 * pending llama.cpp mmproj validation) [inc 9]. The TaskQueue + warm-model
 * runtime + tray + model-download-via-tray live in :desktopApp (Main.kt);
 * Markdown/LaTeX is an expect/actual in :ui. Phase 7 complete (→ Phase 8
 * packaging, Phase 9 UI cutover).
 */
val desktopModule: Module = module {
    // PR #55 (Option 3) — desktop inference runs through llama.cpp's reference
    // `llama-server` subprocess over localhost HTTP, NOT the net.ladenthin:llama JNI
    // binding (which drops images before the vision encoder and ships CPU-only natives).
    // The binary is downloaded/cached on first run; the mmproj resolver (read lazily at
    // loadModel) enables vision via `--mmproj`.
    single { LlamaServerBinaryStore(logger = { System.err.println("[LlamaServer] $it") }) }

    // PR #78 — desktop GPU device pin. The persisted pin rides into the llama-server
    // launch args as `--device <id>` so a multi-GPU box ignores the slow iGPU; the
    // enumerator backs the Settings "Detect devices" button.
    single { DesktopGpuPreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "gpu_prefs.json"))) }
    single { LlamaServerDevices(binaryStore = get(), logger = { System.err.println("[LlamaServer] $it") }) }

    // PR #56 — remote Ollama config + client + engine. The OpenAI-compatible
    // OllamaInferenceEngine is shared (commonMain); only the prefs store is
    // desktop-specific (file-JSON under app-data).
    single<OllamaPreferences> {
        DesktopOllamaPreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "ollama_prefs.json")))
    }
    single { OllamaClient(get<HttpEngineFactory>(), get<SecureStorage>(), logger = { System.err.println("[OllamaClient] $it") }) }

    // PR #74 — paid "anywhere access" (Secure Gateway relay subscription).
    single<SubscriptionPreferences> {
        DesktopSubscriptionPreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "subscription_prefs.json")))
    }
    single { RelayGatewayClient(get<HttpEngineFactory>(), logger = { System.err.println("[RelayGateway] $it") }) }
    single {
        RelaySubscriptionService(
            client = get(),
            prefs = get(),
            secureStorage = get(),
            urlOpener = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            gatewayBaseUrl = RelaySubscriptionService.gatewayUrlFromEnv(),
            subscriptionPortalUrl = RelaySubscriptionService.portalUrlFromEnv(),
            logger = { System.err.println("[Subscription] $it") },
        )
    }
    single<SubscriptionUiController> {
        DesktopSubscriptionUiController(
            service = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            logger = { System.err.println("[Subscription] $it") },
        )
    }
    // Relay follow-up — the desktop relay host (mints the relay QR + serves framed
    // link requests over the E2EE relay when subscribed). Main.kt drives its
    // lifecycle off the subscription state.
    single {
        DesktopRelayHost(
            prefs = get(),
            secureStorage = get(),
            gatewayBaseUrl = RelaySubscriptionService.gatewayUrlFromEnv(),
            relayWsUrl = RelaySubscriptionService.relayWsUrlFromEnv(),
            keyStorePath = File(DesktopAppDirs.dataDir(), "relay_identity.key").toPath(),
            logger = { System.err.println("[Relay] $it") },
        )
    }
    // Desktop "Disconnect" for a relay connection revokes the pairing via the relay host.
    single<RelayDisconnector> { get<DesktopRelayHost>() }
    // Desktop "Pair Now" (PR #92) — the relay host mints a QR on demand instead of auto-minting.
    single<RelayPairingInitiator> { get<DesktopRelayHost>() }
    single {
        OllamaConnectionMonitor(
            healthProbe = { url -> get<OllamaClient>().health(url, get<OllamaPreferences>().config().serverType) },
            logger = { System.err.println("[Ollama] $it") },
        )
    }
    single {
        OllamaInferenceEngine(
            httpEngineFactory = get(),
            preferences = get(),
            client = get(),
            monitor = get(),
            secureStorage = get(),
            logger = { System.err.println("[Ollama] $it") },
        )
    }

    // PR #56 — the InferenceEngine seam now routes to Ollama when configured +
    // reachable, else falls back to the local llama-server (built as `local`).
    // PR #57 — the desktop *hosts* the link (QR + server live here) and never
    // routes its own chat to itself, so the desktop-link backend is NOT wired
    // into RoutingInferenceEngine here (left null); the desktop uses
    // DesktopLinkPreferences only for its device id and recording the paired mobile.
    single<InferenceEngine> {
        RoutingInferenceEngine(
            local = LlamaServerInferenceEngine(
                binaryStore = get(),
                mmprojPathProvider = { DesktopModelInventory.resolveMmprojPath() },
                // PR #78 — env override wins (for headless, #53) over the stored Settings pin.
                devicePinProvider = {
                    System.getenv("LOCALAGENT_LLAMA_SERVER_DEVICE")?.takeIf { it.isNotBlank() }
                        ?: get<DesktopGpuPreferences>().devicePin()
                },
                logger = { System.err.println("[LlamaServer] $it") },
            ),
            ollama = get<OllamaInferenceEngine>(),
            preferences = get(),
            logger = { System.err.println("[Inference] $it") },
        )
    }

    // PR #57 — desktop link host config + status provider. The status provider
    // reports DISABLED on desktop (the link is never enabled here — the desktop is
    // the host, not a client), so the chat-header link dot is hidden. The loopback
    // callback server is wired separately in Main.kt (DesktopLinkServer).
    single<DesktopLinkPreferences> {
        DesktopDesktopLinkPreferences(
            DesktopJsonStore(File(DesktopAppDirs.dataDir(), "desktop_link_prefs.json")),
        )
    }
    single<DesktopLinkStatusProvider> {
        PollingDesktopLinkStatusProvider(preferences = get())
    }

    // PR #57 — sync engine (desktop side). The desktop is the sync SERVER, so it
    // needs LinkSyncService (the relay frame dispatcher's sync methods drive it)
    // + the LocalChangeBus (fires the sync-subscribe stream on desktop-side writes).
    // The link request handler + relay host are constructed in Main.kt (they need WarmModel).
    single { LocalChangeBus() }
    single<SyncWatermarkStore> {
        DesktopSyncWatermarkStore(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "sync_state.json")))
    }
    // PR #70 — last-successful-sync wall-clock (shares sync_state.json). Desktop
    // has no SyncController, so it stays null ("Never synced"); bound only so the
    // shared Jobs UI can inject LastSyncStatus without a missing-binding crash.
    single<LastSyncStore> {
        DesktopLastSyncStore(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "sync_state.json")))
    }
    single { MutableLastSyncStatus(get<LastSyncStore>().get()) }
    single<LastSyncStatus> { get<MutableLastSyncStatus>() }
    // PR #70 — desktop is the authority: drop remote inserts/tombstones, accept
    // only a paused toggle on existing jobs (the §2 trust boundary, fail-closed).
    single<JobSyncPolicy> { DesktopJobSyncPolicy() }
    single<LinkSyncService> {
        SqlDelightLinkSyncService(
            conversations = get(),
            memories = get(),
            jobs = get(),
            jobPolicy = get(),
            embedder = get(),
            bus = get(),
            logger = { System.err.println("[Sync] $it") },
            // A mobile pause wrote the row via the raw query (no bus) — drive the
            // scheduler so the job's coroutine actually cancels/rearms.
            onJobPausedFromPeer = { id, paused -> get<JobService>().reactToPausedChange(id, paused) },
        )
    }
    // Desktop publishes the pairing-QR payload here once the link server is bound
    // (set by Main.kt); the shared Settings UI renders it as a QR image.
    single { MutableDesktopLinkQr() }
    single<DesktopLinkQrProvider> { get<MutableDesktopLinkQr>() }
    // Live "phone connected" signal (driven by the link server's SSE subscribers).
    single { MutableDesktopLinkConnectionStatus() }
    single<DesktopLinkConnectionStatus> { get<MutableDesktopLinkConnectionStatus>() }

    // GGUF acquisition (Phase 4): inventory resolves the OS app-data model path; the
    // downloader fetches/verifies/promotes it. Phase 7's tray/chat drives download() and
    // loads from inventory.localFile() — replacing the harness's GEMMA_GGUF_PATH env var.
    single { DesktopModelInventory(DesktopModelInventory.DEFAULT) }
    single { DesktopModelDownloader(inventory = get(), logger = { System.err.println("[ModelDownload] $it") }) }

    // PR #55 — vision projector (mmproj) acquisition. A second inventory + downloader
    // for the ~990 MB mmproj GGUF; the desktop app fetches it in the background
    // alongside the main GGUF. Named to disambiguate from the LLM inventory/downloader.
    single(named("mmproj")) { DesktopModelInventory(DesktopModelInventory.MMPROJ_DEFAULT) }
    single(named("mmproj")) {
        DesktopModelDownloader(
            inventory = get(named("mmproj")),
            logger = { System.err.println("[MmprojDownload] $it") },
        )
    }

    // ONNX classifier + embedder (Phase 5). Re-exports of the Android .tflite
    // models (ai-edge-litert is Android-only, invariant #18). Model files come
    // from the app-data `models/` dir (or a `LOCALAGENT_*_ONNX` env override);
    // when absent, `warmUp` returns null and the agent degrades exactly as the
    // Phase-0 NoOp engines did (PreflightRouter → Gemma; memory → no-op). So
    // DI_CHECK stays green without the .onnx artifacts present — construction
    // never touches ORT or the filesystem (that's deferred to warmUp).
    single<ClassifierEngine> {
        OnnxClassifierEngine(
            modelPath = DesktopAuxModels.classifierModel(),
            logger = { System.err.println("[Classifier] $it") },
        )
    }
    single<EmbedderEngine> {
        OnnxEmbedderEngine(
            tokenizer = get(),
            modelPath = DesktopAuxModels.embedderModel(),
            logger = { System.err.println("[Embedder] $it") },
        )
    }
    // First-run downloader for the two ONNX aux models (classifier + embedder), like
    // the GGUF/Vosk. Main.kt ensures them before warmUp; no-op when present, env-
    // overridden, or the hosting endpoint isn't configured (-PauxModelBaseUrl).
    single { DesktopAuxModelStore(logger = { System.err.println("[AuxModels] $it") }) }
    single<HttpEngineFactory> { DesktopHttpEngineFactory() }

    // Secrets (Brave key, HF token, search-enabled flag) in a PKCS#12 keystore at
    // <app-data>/secrets.p12 (Phase 6). The search-enabled BraveKeyProvider that
    // reads this lands in the search-on increment.
    single<SecureStorage> { DesktopSecureStorage.create() }

    // File-backed DB at <app-data>/local_agent.db (Phase 6) — persists across
    // launches, create/migrate driven off PRAGMA user_version.
    single<SqlDriver> { DesktopDatabaseFactory.create(logger = { System.err.println("[DB] $it") }) }
    single { LocalAgentDatabase(get()) }
    single { get<LocalAgentDatabase>().searchCacheQueries }
    single { get<LocalAgentDatabase>().memoriesQueries }
    single { get<LocalAgentDatabase>().myListQueries }
    single { get<LocalAgentDatabase>().telemetryAggregateQueries }
    single { get<LocalAgentDatabase>().tasksQueries }
    // Conversation history — wired on desktop with the Phase-9 Chat surface
    // (mirrors androidModule). Persists/resumes chats through the shared :ui VM.
    single { get<LocalAgentDatabase>().conversationsQueries }
    single<ConversationRepository> { SqlDelightConversationRepository(get(), get(), localChangeBus = get()) }
    // PR #70 — jobs. Repository on both platforms (mobile renders synced state);
    // the scheduler/executor/service below are desktop-only.
    single { get<LocalAgentDatabase>().jobsQueries }
    single<JobRepository> { SqlDelightJobRepository(queries = get(), bus = get()) }
    single { SearchCacheDao(queries = get(), nowEpochMs = { get<AgentClock>().nowEpochMs() }) }

    // Translation-intent detector — platform-agnostic commonMain class consumed by
    // the shared :ui ChatViewModel (per-turn response-language filter, #10).
    single { TranslationIntentDetector() }

    // -- Voice I/O (Phase 7, invariant #42). Read-aloud shells out to the OS
    //    speech engine (say/spd-say/PowerShell); dictation is offline Vosk STT.
    //    Both degrade to no-op without an engine/model. Consumed by the shared
    //    Chat ViewModel in Phase 9; bindable now. --
    // The Vosk acoustic model (~40 MB) is downloaded + cached on first mic use.
    single { VoskModelStore() }
    single<Dictation> { VoskDictation(modelProvider = { get<VoskModelStore>().ensure() }) }
    // Read-aloud toggle + voice settings (PR #66). Bound as the concrete type too so
    // the speaker + the desktop-only voice picker in Settings reach voiceConfig()
    // (engine/voice/rate aren't on the shared TtsPreferences interface, #45).
    single { DesktopTtsPreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "tts_prefs.json"))) }
    single<TtsPreferences> { get<DesktopTtsPreferences>() }
    single { DesktopTtsVoices() }
    // Piper neural engine (PR #66) — self-contained binary + voice model downloaded on
    // first use (like the LLM/Vosk models), played in-JVM via Java Sound. Selected via the
    // "piper" engine in the desktop voice picker; falls back to the OS engine otherwise.
    single { PiperBinaryStore() }
    single { PiperVoiceStore() }
    single { PiperSpeechSynthesizer(binaryStore = get(), voiceStore = get()) }
    single<ChatSpeaker> {
        DesktopTtsSpeaker(voiceConfig = { get<DesktopTtsPreferences>().voiceConfig() }, piper = get())
    }
    // Chat-screen log sink (Phase 9 inc 8d) — desktop routes to stderr.
    single<ChatLogger> { ChatLogger { System.err.println("[ChatViewModel] $it") } }
    // System-memory status dot source (chat header) — constant healthy on desktop.
    single<SystemMemoryStatusProvider> { DesktopSystemMemoryStatusProvider() }
    // Theme-mode persistence for the shared :ui ThemeModeViewModel (Phase 9 inc 8d).
    // Bound as the concrete type too so Main.kt can reach the desktop-only UI-zoom
    // methods (Ctrl/Cmd +/-) that aren't on the shared ThemePreferences interface.
    single { DesktopThemePreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "theme_prefs.json"))) }
    single<ThemePreferences> { get<DesktopThemePreferences>() }
    // Main-window geometry persistence (size/position/maximized) so the app reopens
    // the way the user left it. Read once at window creation + saved on change.
    single { DesktopWindowPreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "window_prefs.json"))) }

    // -- Vision (Phase 7 + PR #55, invariant #39). The image pipeline — Swing file
    //    chooser + ImageIO decode/downscale → JPEG ByteArray — feeds the Chat UI.
    //    Engine vision is now ON: WarmModel loads enableVision=true and the engine
    //    loads the mmproj (above) when present, routing an image turn through
    //    llama.cpp's mtmd pipeline. Without the mmproj the engine degrades to
    //    text-only (logged), so the graph still resolves on a fresh install. --
    single<FilePicker> { DesktopFilePicker() }
    single<ImagePreprocessor> { DesktopImagePreprocessor() }

    // -- Queued agent-task system (Phase 7). Persistence for the desktop
    //    TaskQueue (schema v8, migration 7.sqm). The TaskQueue + TaskRunner
    //    binding lands with the system-tray increment (it needs the warm
    //    AgentLoop session + app scope); the repository is bindable now. --
    single<TaskRepository> { SqlDelightTaskRepository(get()) }

    // -- Telemetry (Phase 7). Opt-in aggregate counters + crash reporting,
    //    gated by TelemetryConsentManager (default OFF, PRD §3.2.1). One
    //    InMemoryTelemetryCounters bound to both the recording (TelemetryCounters,
    //    pulled by AgentLoopFactory) and persistence (TelemetryFlusher) interfaces.
    //    Crashes → Sentry (no-op without SENTRY_DSN); aggregate events → a
    //    file/JSONL AnalyticsSink (the egress chokepoint, #27). The
    //    DesktopTelemetryScheduler coroutine replaces the Android upload worker;
    //    the app/tray increment start()s it. --
    single { InMemoryTelemetryCounters(get()) }
    single<TelemetryCounters> { get<InMemoryTelemetryCounters>() }
    single<TelemetryFlusher> { get<InMemoryTelemetryCounters>() }
    single<AnalyticsSink> {
        FileAnalyticsSink(
            file = File(DesktopAppDirs.dataDir(), "telemetry/events.jsonl"),
            nowEpochMs = { get<AgentClock>().nowEpochMs() },
            logger = { System.err.println("[Telemetry] $it") },
        )
    }
    single<SafeCrashReporter> { SentrySafeCrashReporter(consent = get()) }
    single { TelemetryPayloadBuilder(get()) }
    single {
        TelemetryUploader(
            consent = get(),
            flusher = get(),
            builder = get(),
            sink = get(),
            queries = get(),
            nowEpochMs = { get<AgentClock>().nowEpochMs() },
        )
    }
    single { DesktopTelemetryScheduler(uploader = get(), logger = { System.err.println("[Telemetry] $it") }) }

    // -- Search ON (Phase 6) ---------------------------------------------------
    // User key from SecureStorage (set via settings, Phase 9) overrides the
    // BRAVE_API_KEY env fallback — same priority resolver Android uses, just a
    // different dev-key source. No key ⇒ SearchService.isAvailable() is false and
    // the agent silently skips search (graceful).
    single<BraveKeyProvider> {
        DefaultBraveKeyProvider(get(), System.getenv("BRAVE_API_KEY"))
    }
    // Default (GENERAL): /llm/context, maxUrls = 3 (invariant #37).
    single<BraveSearchClient> {
        KtorBraveLlmContextClient(get<HttpEngineFactory>(), maxUrls = 3) { System.err.println("[BraveApi] $it") }
    }

    // Real bundled WordPiece vocab (classpath resource, byte-identical to the
    // Android asset — invariant #13). The wired ONNX classifier/embedder need it
    // the moment they tokenize. Falls back to a 5-token stub only if the resource
    // is somehow missing, so the graph still resolves (the tokenizer is a no-op
    // while there's no .onnx model to feed). WordPieceTokenizer is bound once in
    // the shared agentCoreModule and resolves this Vocab.
    single {
        DesktopVocabLoader.loadOrNull()
            ?: Vocab.fromLines(sequenceOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"))
    }

    // Pre-flight thresholds from the bundled preflight_config.json (invariant #14:
    // the asset is the runtime source of truth), DEFAULT only if it fails to load.
    single<PreflightConfig> {
        DesktopResources.readTextOrNull("preflight_config.json")?.let {
            runCatching { configJson.decodeFromString(PreflightConfig.serializer(), it) }.getOrNull()
        } ?: PreflightConfig.DEFAULT
    }
    // i18n language packs (PR #96): read `i18n/strings_<code>.json` from the
    // classpath. None ship today (English is the in-code floor), so this returns
    // null and the catalog stays English; drop a pack in to activate a language.
    single<StringPackLoader> {
        StringPackLoader { code -> DesktopResources.readTextOrNull("i18n/strings_$code.json") }
    }
    // -- Preferences (Phase 6): file-backed JSON in the app-data dir, the desktop
    //    counterparts of Android's DataStore/SharedPreferences impls. Search +
    //    memory prefs are wired now (their consumers land with search/memory);
    //    the UI/telemetry/clock-coupled prefs (Onboarding/Language/TelemetryConsent/
    //    Clock) follow in Phase 7 alongside those subsystems. --
    single<DefaultSiteResolver> {
        DefaultSiteResolver(DesktopResources.readTextOrNull("search_defaults.json") ?: EMPTY_DEFAULTS_JSON)
    }
    single<SearchPreferencesRepository> {
        DesktopSearchPreferencesRepository(
            store = DesktopJsonStore(File(DesktopAppDirs.dataDir(), "search_prefs.json")),
            resolver = get(),
        )
    }
    single<MemoryPreferences> {
        DesktopMemoryPreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "memory_prefs.json")))
    }

    // -- System-state providers (Phase 7). Desktop has no portable thermal API
    //    (invariant #4) — the provider always reports NONE so no thermal gate
    //    fires on mains-powered hardware. Memory headroom maps to OS free
    //    physical RAM via OperatingSystemMXBean (the JVM analogue of Android's
    //    ActivityManager.availMem). Consumed by the Phase-7 warm-model/tray path. --
    single<ThermalStatusProvider> { DesktopThermalStatusProvider() }
    single<MemoryHeadroomProvider> { DesktopMemoryHeadroomProvider() }
    // App build flags for shared :ui screens (Phase 9). Desktop ships no bundled
    // dev secrets, so the dev-key flags are constant false.
    single<AppBuildConfig> { DesktopAppBuildConfig() }
    // Open web URLs (onboarding key/token dashboards) for shared :ui screens (Phase 9).
    single<UrlOpener> { DesktopUrlOpener() }
    // Transient messages (memory backup feedback) for shared :ui screens (Phase 9).
    single<Toaster> { DesktopToaster() }

    // -- UI / first-run preferences (Phase 7): file-JSON, the desktop
    //    counterparts of Android's SharedPreferences impls. Onboarding gates,
    //    response-language choice, and telemetry consent (default OFF, PRD
    //    §3.2.1) — one DesktopJsonStore file per repo under the app-data dir. --
    single<OnboardingPreferences> {
        DesktopOnboardingPreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "onboarding_prefs.json")))
    }
    single<LanguagePreferences> {
        DesktopLanguagePreferences(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "language_prefs.json")))
    }
    single<TelemetryConsentManager> {
        DesktopTelemetryConsentManager(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "telemetry_consent.json")))
    }

    // Four SearchService variants (invariant #37: per-vertical URL budget + cache
    // namespace). Search-enabled gate reads SecureStorage SEARCH_ENABLED (default
    // on); mirrors androidModule exactly.
    single<SearchService> {
        SearchService(
            keyProvider = get(),
            client = get(),
            cache = get(),
            isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
            cacheNamespace = "ctx:",
        )
    }
    single<SearchService>(named("sports")) {
        SearchService(
            keyProvider = get(),
            client = KtorBraveLlmContextClient(get<HttpEngineFactory>(), maxUrls = 1) { System.err.println("[BraveApi] $it") },
            cache = get(),
            isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
            cacheNamespace = "sports:",
        )
    }
    single<SearchService>(named("news")) {
        SearchService(
            keyProvider = get(),
            client = KtorBraveLlmContextClient(get<HttpEngineFactory>(), maxUrls = 10) { System.err.println("[BraveApi] $it") },
            cache = get(),
            isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
            cacheNamespace = "news:",
        )
    }
    single<SearchService>(named("finance")) {
        SearchService(
            keyProvider = get(),
            client = KtorBraveSearchClient(get<HttpEngineFactory>()) { System.err.println("[BraveApi] $it") },
            cache = get(),
            isEnabled = { get<SecureStorage>().get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
            cacheNamespace = "fin:",
        )
    }

    // AgentLogger is bound on Android (Log.i) but was unbound on desktop, so every
    // AgentLoop "[turn] …" line (incl. the WEATHER force-fire decision) was silently
    // dropped here. Bind it to timestamped stderr so the force-fire path is visible.
    single<AgentLogger> {
        AgentLogger { msg ->
            System.err.println("[AgentLoop ${LocalTime.now().format(AGENT_LOG_TIME_FMT)}] $msg")
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
            logger = { System.err.println("[ClassifierModule] $it") }, // invariant #28
        )
    }

    // Vertical dispatcher consumes the 4 SearchService variants (invariant #31/#37).
    single<VerticalSearchDispatcher> {
        VerticalSearchDispatcherFactory.create(
            httpEngineFactory = get(),
            searchService = get(),
            sportsSearchService = get(named("sports")),
            financeSearchService = get(named("finance")),
            newsSearchService = get(named("news")),
            logger = { System.err.println("[VerticalSearch] $it") },
        )
    }

    // Weather/finance deterministic-render support + location data (invariant #32/#33).
    single<LocationCatalog> {
        LocationCatalog(DesktopResources.readTextOrNull("locations.json") ?: """{"countries":[]}""")
    }
    single { WeatherLocationResolver(get()) }
    single { WeatherResponseFormatter }
    single { StockResponseFormatter }

    // -- Memory subsystem (Phase 6): orchestrators are commonMain; embedder (Phase 5)
    //    + file DB + memory prefs are now present, so memory is enabled. --
    single<MemoryConfig> {
        DesktopResources.readTextOrNull("memory_config.json")?.let {
            runCatching { configJson.decodeFromString(MemoryConfig.serializer(), it) }.getOrNull()
        } ?: MemoryConfig.DEFAULT
    }
    single<MemoryStore> { SqlDelightMemoryStore(get(), localChangeBus = get()) }
    // Memory backup (export/import) — shared controller, file I/O via the :ui
    // BackupWriter/Reader. Phase 9 (Memory screen migrated to shared :ui).
    single<MemoryBackupOps> {
        MemoryBackupController(
            store = get(),
            embedder = get(),
            clock = get(),
            counters = get(),
            config = get(),
            appVersionName = get<AppBuildConfig>().versionName,
            logger = { msg -> System.err.println("[MemoryBackup] $msg") },
        )
    }
    single { RememberForgetDetector() }
    single { QuestionDetector() }
    single { TempContextDateParser(timeContextProvider = { currentTimeContext(get(), get()) }) }
    single {
        val clock = get<AgentClock>()
        MemoryRetriever(
            embedder = get(),
            store = get(),
            nowProvider = { clock.nowEpochMs() },
            logger = { System.err.println("[MemoryRetriever] $it") },
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
            logger = { System.err.println("[MemoryExtractor] $it") },
        )
    }

    // -- Clock subsystem (agent tool, Phase 7). The repository persists timers/
    //    alarms to a file-JSON store (the desktop counterpart of Android's
    //    SharedPreferences repo); DesktopAlarmScheduler replaces AlarmManager
    //    with a coroutine delay-until-instant registry that fires through the
    //    NotificationPresenter seam. clockServiceProvider is lazy to break the
    //    ClockService↔scheduler cycle. ClockToolHandler is pulled by the
    //    AgentLoopFactory (getOrNull), so binding it here enables the clock
    //    tools on the desktop AgentLoop. The app/tray increment calls
    //    ClockService.rearmAll() at startup to re-create armed fires. --
    // Swappable presenter: starts with a platform fallback, the desktopApp installs a
    // tray-backed delegate once the tray composes (Phase 7 inc 5), so the clock
    // subsystem AND the task queue both route to the system tray. Bound as the
    // concrete type too so the app can resolve it to call setDelegate.
    // On Linux the fallback is notify-send (PR #93) — the AWT tray is usually
    // unsupported there and the app deliberately does NOT setDelegate to the tray on
    // Linux, so this fallback is the real delivery path on every Linux entry point
    // (GUI, tray-minimized, windowless headless). macOS/Windows keep the logging
    // fallback until the tray delegate lands.
    single {
        MutableNotificationPresenter(
            fallback = if (DesktopOs.isLinux) LinuxNotificationPresenter() else DesktopNotificationPresenter(),
        )
    }
    single<NotificationPresenter> { get<MutableNotificationPresenter>() }
    single<ClockRepository> {
        DesktopClockRepository(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "clock_prefs.json")))
    }
    single<AlarmScheduler> {
        DesktopAlarmScheduler(
            clockServiceProvider = { get<ClockService>() },
            presenter = get(),
            clock = get(),
            logger = { System.err.println("[ClockScheduler] $it") },
        )
    }
    single { ClockService(repository = get(), scheduler = get()) }
    single { ClockToolHandler(get()) }

    // -- Job subsystem (PR #70), desktop-only. Mirrors the clock subsystem: a
    //    coroutine delay-until-instant scheduler (jobServiceProvider lazy to break
    //    the JobService↔scheduler cycle) + an executor that runs the subprocess
    //    and writes the run conversation + history. JobService.rearmAll() is
    //    called from Main.kt at startup, next to ClockService.rearmAll(). --
    single {
        DesktopJobScheduler(
            jobServiceProvider = { get<JobService>() },
            clock = get(),
            logger = { System.err.println("[JobScheduler] $it") },
        )
    }
    single {
        JobExecutor(
            jobs = get(),
            conversations = get(),
            clock = get(),
            logger = { System.err.println("[JobExecutor] $it") },
        )
    }
    single {
        JobService(
            repository = get(),
            scheduler = get(),
            executor = get(),
            clock = get(),
            logger = { System.err.println("[JobService] $it") },
        )
    }
    // The desktop is the authority: bind the admin seam so the shared Jobs UI can
    // create/edit/delete/run. Mobile leaves this unbound → the UI is read-only.
    single<JobAdmin> { get<JobService>() }
    // PR #88 — the desktop runs a "run job …" chat command locally (subprocess);
    // mobile binds the relay variant. AgentCoreModule pulls this with getOrNull().
    single<InlineJobRunner> { LocalInlineJobRunner(jobs = get(), executor = get<JobExecutor>()) }
    // PR #93 — notify on job completion on the DESKTOP too (was Android-only, #58):
    // jobs run on the desktop, so the machine that ran them should surface the result
    // (via notify-send on Linux / tray toast on macOS/Windows). Reuses the commonMain
    // JobCompletionNotifier (rides JobRepository.flow(), noteworthy=SUCCEEDED/FAILED,
    // baseline-suppress + watermark dedup). Main.kt starts it on appScope. The desktop
    // prefs has no header badge (seen watermark inert); notified watermark is the live one.
    single<JobNotificationPrefs> {
        DesktopJobNotificationPrefs(DesktopJsonStore(File(DesktopAppDirs.dataDir(), "job_notify_prefs.json")))
    }
    single {
        JobCompletionNotifier(
            repository = get(),
            presenter = get(),
            prefs = get(),
            stringCatalog = get(),
            logger = { System.err.println("[JobNotifier] $it") },
        )
    }

    // -- My List subsystem (agent tool). --
    single<MyListRepository> { SqlDelightMyListRepository(get()) }
    single { MyListIntentDetector() }
    single { MyListCommandParser() }
    single { MyListResponseFormatter() }
    single { MyListToolHandler(get()) }
}
