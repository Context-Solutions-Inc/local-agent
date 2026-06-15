package com.contextsolutions.mobileagent.di

import app.cash.sqldelight.db.SqlDriver
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.DesktopVocabLoader
import com.contextsolutions.mobileagent.classifier.OnnxClassifierEngine
import com.contextsolutions.mobileagent.classifier.PreflightConfig
import com.contextsolutions.mobileagent.classifier.PreflightRouter
import com.contextsolutions.mobileagent.classifier.Vocab
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.inference.DesktopAuxModels
import com.contextsolutions.mobileagent.inference.DesktopModelDownloader
import com.contextsolutions.mobileagent.agent.ChatLogger
import com.contextsolutions.mobileagent.agent.TranslationIntentDetector
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.conversation.SqlDelightConversationRepository
import com.contextsolutions.mobileagent.inference.DesktopModelInventory
import com.contextsolutions.mobileagent.inference.DesktopSystemMemoryStatusProvider
import com.contextsolutions.mobileagent.inference.SystemMemoryStatusProvider
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.ui.theme.DesktopThemePreferences
import com.contextsolutions.mobileagent.ui.theme.DesktopWindowPreferences
import com.contextsolutions.mobileagent.ui.theme.ThemePreferences
import com.contextsolutions.mobileagent.inference.LlamaServerBinaryStore
import com.contextsolutions.mobileagent.inference.LlamaServerDevices
import com.contextsolutions.mobileagent.inference.LlamaServerInferenceEngine
import com.contextsolutions.mobileagent.inference.OllamaClient
import com.contextsolutions.mobileagent.inference.OllamaConnectionMonitor
import com.contextsolutions.mobileagent.inference.OllamaInferenceEngine
import com.contextsolutions.mobileagent.inference.RoutingInferenceEngine
import com.contextsolutions.mobileagent.inference.DesktopLinkStatusProvider
import com.contextsolutions.mobileagent.inference.PollingDesktopLinkStatusProvider
import com.contextsolutions.mobileagent.link.DesktopLinkConnectionStatus
import com.contextsolutions.mobileagent.link.DesktopLinkQrProvider
import com.contextsolutions.mobileagent.link.MutableDesktopLinkConnectionStatus
import com.contextsolutions.mobileagent.link.MutableDesktopLinkQr
import com.contextsolutions.mobileagent.sync.DesktopLastSyncStore
import com.contextsolutions.mobileagent.sync.DesktopSyncWatermarkStore
import com.contextsolutions.mobileagent.sync.DesktopJobSyncPolicy
import com.contextsolutions.mobileagent.sync.JobSyncPolicy
import com.contextsolutions.mobileagent.sync.LastSyncStatus
import com.contextsolutions.mobileagent.sync.LastSyncStore
import com.contextsolutions.mobileagent.sync.LinkSyncService
import com.contextsolutions.mobileagent.sync.LocalChangeBus
import com.contextsolutions.mobileagent.sync.MutableLastSyncStatus
import com.contextsolutions.mobileagent.sync.SqlDelightLinkSyncService
import com.contextsolutions.mobileagent.sync.SyncWatermarkStore
import com.contextsolutions.mobileagent.job.DesktopJobScheduler
import com.contextsolutions.mobileagent.job.InlineJobRunner
import com.contextsolutions.mobileagent.job.JobAdmin
import com.contextsolutions.mobileagent.job.JobExecutor
import com.contextsolutions.mobileagent.job.JobRepository
import com.contextsolutions.mobileagent.job.JobService
import com.contextsolutions.mobileagent.job.LocalInlineJobRunner
import com.contextsolutions.mobileagent.job.SqlDelightJobRepository
import com.contextsolutions.mobileagent.preferences.DesktopDesktopLinkPreferences
import com.contextsolutions.mobileagent.preferences.DesktopGpuPreferences
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.preferences.DesktopOllamaPreferences
import com.contextsolutions.mobileagent.preferences.DesktopSubscriptionPreferences
import com.contextsolutions.mobileagent.preferences.OllamaPreferences
import com.contextsolutions.mobileagent.subscription.DesktopRelayHost
import com.contextsolutions.mobileagent.subscription.DesktopSubscriptionUiController
import com.contextsolutions.mobileagent.subscription.RelayDisconnector
import com.contextsolutions.mobileagent.subscription.RelayGatewayClient
import com.contextsolutions.mobileagent.subscription.RelayPairingInitiator
import com.contextsolutions.mobileagent.subscription.RelaySubscriptionService
import com.contextsolutions.mobileagent.subscription.SubscriptionPreferences
import com.contextsolutions.mobileagent.subscription.SubscriptionUiController
import com.contextsolutions.mobileagent.memory.DesktopMemoryPreferences
import com.contextsolutions.mobileagent.memory.EmbedderEngine
import com.contextsolutions.mobileagent.memory.MemoryPreferences
import com.contextsolutions.mobileagent.memory.OnnxEmbedderEngine
import com.contextsolutions.mobileagent.preferences.DefaultSiteResolver
import com.contextsolutions.mobileagent.preferences.DesktopSearchPreferencesRepository
import com.contextsolutions.mobileagent.preferences.SearchPreferencesRepository
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.AppBuildConfig
import com.contextsolutions.mobileagent.platform.DesktopAppBuildConfig
import com.contextsolutions.mobileagent.platform.DesktopToaster
import com.contextsolutions.mobileagent.platform.DesktopUrlOpener
import com.contextsolutions.mobileagent.platform.Toaster
import com.contextsolutions.mobileagent.platform.UrlOpener
import com.contextsolutions.mobileagent.platform.DesktopDatabaseFactory
import com.contextsolutions.mobileagent.platform.DesktopHttpEngineFactory
import com.contextsolutions.mobileagent.platform.DesktopJsonStore
import com.contextsolutions.mobileagent.platform.DesktopSecureStorage
import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.inference.DesktopAppDirs
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.DefaultBraveKeyProvider
import com.contextsolutions.mobileagent.search.KtorBraveLlmContextClient
import com.contextsolutions.mobileagent.search.KtorBraveSearchClient
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.vertical.VerticalSearchDispatcher
import com.contextsolutions.mobileagent.search.vertical.VerticalSearchDispatcherFactory
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.preferences.LocationCatalog
import com.contextsolutions.mobileagent.preferences.WeatherLocationResolver
import com.contextsolutions.mobileagent.agent.StockResponseFormatter
import com.contextsolutions.mobileagent.agent.TodoCommandParser
import com.contextsolutions.mobileagent.agent.TodoIntentDetector
import com.contextsolutions.mobileagent.agent.TodoResponseFormatter
import com.contextsolutions.mobileagent.agent.TodoToolHandler
import com.contextsolutions.mobileagent.agent.WeatherResponseFormatter
import com.contextsolutions.mobileagent.agent.currentTimeContext
import com.contextsolutions.mobileagent.memory.MemoryBackupController
import com.contextsolutions.mobileagent.memory.MemoryBackupOps
import com.contextsolutions.mobileagent.memory.MemoryConfig
import com.contextsolutions.mobileagent.memory.MemoryExtractor
import com.contextsolutions.mobileagent.memory.MemoryRetriever
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.memory.QuestionDetector
import com.contextsolutions.mobileagent.memory.RememberForgetDetector
import com.contextsolutions.mobileagent.memory.SqlDelightMemoryStore
import com.contextsolutions.mobileagent.memory.TempContextDateParser
import com.contextsolutions.mobileagent.todo.SqlDelightTodoRepository
import com.contextsolutions.mobileagent.todo.TodoRepository
import org.koin.core.qualifier.named
import com.contextsolutions.mobileagent.platform.DesktopResources
import com.contextsolutions.mobileagent.inference.DesktopMemoryHeadroomProvider
import com.contextsolutions.mobileagent.inference.DesktopThermalStatusProvider
import com.contextsolutions.mobileagent.inference.MemoryHeadroomProvider
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.language.DesktopLanguagePreferences
import com.contextsolutions.mobileagent.language.LanguagePreferences
import com.contextsolutions.mobileagent.onboarding.DesktopOnboardingPreferences
import com.contextsolutions.mobileagent.onboarding.OnboardingPreferences
import com.contextsolutions.mobileagent.telemetry.DesktopTelemetryConsentManager
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import com.contextsolutions.mobileagent.agent.ClockToolHandler
import com.contextsolutions.mobileagent.clock.AlarmScheduler
import com.contextsolutions.mobileagent.clock.ClockRepository
import com.contextsolutions.mobileagent.clock.ClockService
import com.contextsolutions.mobileagent.clock.DesktopAlarmScheduler
import com.contextsolutions.mobileagent.clock.DesktopClockRepository
import com.contextsolutions.mobileagent.notification.MutableNotificationPresenter
import com.contextsolutions.mobileagent.notification.NotificationPresenter
import com.contextsolutions.mobileagent.observability.SafeCrashReporter
import com.contextsolutions.mobileagent.observability.SentrySafeCrashReporter
import com.contextsolutions.mobileagent.telemetry.AnalyticsSink
import com.contextsolutions.mobileagent.telemetry.DesktopTelemetryScheduler
import com.contextsolutions.mobileagent.telemetry.FileAnalyticsSink
import com.contextsolutions.mobileagent.telemetry.InMemoryTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryFlusher
import com.contextsolutions.mobileagent.telemetry.TelemetryPayloadBuilder
import com.contextsolutions.mobileagent.telemetry.TelemetryUploader
import com.contextsolutions.mobileagent.task.SqlDelightTaskRepository
import com.contextsolutions.mobileagent.task.TaskRepository
import com.contextsolutions.mobileagent.voice.ChatSpeaker
import com.contextsolutions.mobileagent.voice.Dictation
import com.contextsolutions.mobileagent.voice.DesktopTtsSpeaker
import com.contextsolutions.mobileagent.voice.DesktopTtsPreferences
import com.contextsolutions.mobileagent.voice.DesktopTtsVoices
import com.contextsolutions.mobileagent.voice.PiperBinaryStore
import com.contextsolutions.mobileagent.voice.PiperSpeechSynthesizer
import com.contextsolutions.mobileagent.voice.PiperVoiceStore
import com.contextsolutions.mobileagent.voice.TtsPreferences
import com.contextsolutions.mobileagent.voice.VoskDictation
import com.contextsolutions.mobileagent.voice.VoskModelStore
import com.contextsolutions.mobileagent.vision.DesktopFilePicker
import com.contextsolutions.mobileagent.vision.DesktopImagePreprocessor
import com.contextsolutions.mobileagent.vision.FilePicker
import com.contextsolutions.mobileagent.vision.ImagePreprocessor
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
 * retriever + extractor over the live embedder), and the **todo** tool. Every
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
                    System.getenv("MOBILEAGENT_LLAMA_SERVER_DEVICE")?.takeIf { it.isNotBlank() }
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
    // from the app-data `models/` dir (or a `MOBILEAGENT_*_ONNX` env override);
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
    single<HttpEngineFactory> { DesktopHttpEngineFactory() }

    // Secrets (Brave key, HF token, search-enabled flag) in a PKCS#12 keystore at
    // <app-data>/secrets.p12 (Phase 6). The search-enabled BraveKeyProvider that
    // reads this lands in the search-on increment.
    single<SecureStorage> { DesktopSecureStorage.create() }

    // File-backed DB at <app-data>/mobile_agent.db (Phase 6) — persists across
    // launches, create/migrate driven off PRAGMA user_version.
    single<SqlDriver> { DesktopDatabaseFactory.create(logger = { System.err.println("[DB] $it") }) }
    single { MobileAgentDatabase(get()) }
    single { get<MobileAgentDatabase>().searchCacheQueries }
    single { get<MobileAgentDatabase>().memoriesQueries }
    single { get<MobileAgentDatabase>().todosQueries }
    single { get<MobileAgentDatabase>().telemetryAggregateQueries }
    single { get<MobileAgentDatabase>().tasksQueries }
    // Conversation history — wired on desktop with the Phase-9 Chat surface
    // (mirrors androidModule). Persists/resumes chats through the shared :ui VM.
    single { get<MobileAgentDatabase>().conversationsQueries }
    single<ConversationRepository> { SqlDelightConversationRepository(get(), get(), localChangeBus = get()) }
    // PR #70 — jobs. Repository on both platforms (mobile renders synced state);
    // the scheduler/executor/service below are desktop-only.
    single { get<MobileAgentDatabase>().jobsQueries }
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
    // Swappable presenter: starts logging (fallback), the desktopApp installs a
    // TrayState-backed delegate once the tray composes (Phase 7 inc 5), so the
    // clock subsystem AND the task queue both route to the system tray. Bound as
    // the concrete type too so the app can resolve it to call setDelegate.
    single { MutableNotificationPresenter() }
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

    // -- Todo subsystem (agent tool). --
    single<TodoRepository> { SqlDelightTodoRepository(get()) }
    single { TodoIntentDetector() }
    single { TodoCommandParser() }
    single { TodoResponseFormatter() }
    single { TodoToolHandler(get()) }
}
