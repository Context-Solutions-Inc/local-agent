package com.contextsolutions.localagent.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.contextsolutions.localagent.agent.ChatLogger
import com.contextsolutions.localagent.agent.ChatSessionController
import com.contextsolutions.localagent.agent.PromptAssembler
import com.contextsolutions.localagent.agent.TranslationIntentDetector
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.conversation.ConversationRepository
import com.contextsolutions.localagent.inference.SystemMemoryStatusProvider
import com.contextsolutions.localagent.inference.ThermalStatusProvider
import com.contextsolutions.localagent.memory.EmbedderEngine
import com.contextsolutions.localagent.memory.MemoryExtractor
import com.contextsolutions.localagent.memory.MemoryStore
import com.contextsolutions.localagent.platform.AppBuildConfig
import com.contextsolutions.localagent.platform.UrlOpener
import com.contextsolutions.localagent.preferences.OllamaPreferences
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import com.contextsolutions.localagent.vision.FilePicker
import com.contextsolutions.localagent.vision.ImagePreprocessor
import com.contextsolutions.localagent.voice.ChatSpeaker
import com.contextsolutions.localagent.voice.Dictation
import com.contextsolutions.localagent.voice.TtsPreferences
import com.contextsolutions.localagent.voice.VoskModelStore
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.di.AgentLoopFactory
import com.contextsolutions.localagent.di.agentCoreModule
import com.contextsolutions.localagent.di.desktopModule
import com.contextsolutions.localagent.inference.DesktopModelDownloader
import com.contextsolutions.localagent.inference.DesktopModelInventory
import com.contextsolutions.localagent.inference.InferenceEngine
import com.contextsolutions.localagent.inference.OllamaConnectionMonitor
import com.contextsolutions.localagent.inference.LlamaServerBinaryStore
import com.contextsolutions.localagent.inference.LlamaServerRelease
import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.notification.DesktopOs
import com.contextsolutions.localagent.notification.MutableNotificationPresenter
import com.contextsolutions.localagent.platform.AgentClock
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.task.TaskQueue
import com.contextsolutions.localagent.task.TaskRepository
import com.contextsolutions.localagent.telemetry.DesktopTelemetryScheduler
import com.contextsolutions.localagent.inference.DesktopAppDirs
import com.contextsolutions.localagent.job.JobExecutor
import com.contextsolutions.localagent.job.JobRepository
import com.contextsolutions.localagent.job.JobService
import com.contextsolutions.localagent.link.DesktopLinkRequestHandler
import com.contextsolutions.localagent.link.DesktopLinkServer
import com.contextsolutions.localagent.subscription.DesktopRelayHost
import com.contextsolutions.localagent.subscription.RelaySubscriptionService
import com.contextsolutions.localagent.subscription.SubscriptionPreferences
import com.contextsolutions.localagent.link.MutableDesktopLinkConnectionStatus
import com.contextsolutions.localagent.link.transport.LinkConnectionState
import com.contextsolutions.localagent.link.MutableDesktopLinkQr
import com.contextsolutions.localagent.preferences.DesktopGpuPreferences
import com.contextsolutions.localagent.preferences.DesktopLinkPreferences
import com.contextsolutions.localagent.desktop.app.ui.theme.LocalAgentDesktopTheme
import com.contextsolutions.localagent.sync.LinkSyncService
import com.contextsolutions.localagent.ui.di.uiModule
import com.contextsolutions.localagent.i18n.StringCatalog
import com.contextsolutions.localagent.ui.i18n.LocalStrings
import com.contextsolutions.localagent.ui.navigation.AppNavHost
import com.contextsolutions.localagent.ui.theme.DesktopThemePreferences
import com.contextsolutions.localagent.ui.theme.DesktopWindowPreferences
import com.contextsolutions.localagent.ui.theme.ThemeMode
import com.contextsolutions.localagent.ui.theme.ThemePreferences
import com.contextsolutions.localagent.ui.theme.UiZoom
import java.awt.Color
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Desktop entry point (docs/DESKTOP_PORT_PLAN.md). Starts the Koin graph
 * (shared [agentCoreModule] + [desktopModule]) and, in normal runs, hosts the
 * system tray + warm-model background runtime.
 *
 * `DI_CHECK=1` force-resolves the agent graph and the Phase-7 runtime singletons,
 * then exits without opening a window — a headless smoke test of the DI wiring.
 *
 * `LOCALAGENT_HEADLESS=1` starts the full background runtime (jobs, clock, task queue,
 * mobile-link server, warm LLM, aux engines) but does NOT open the main window on launch —
 * for running the agent as a system-startup service. With a system tray it starts minimized
 * to the tray (Show opens the UI, Shut down quits); on a display-less box with no tray it
 * runs fully windowless and blocks. See docs/DESKTOP_PACKAGING.md "Headless / standalone
 * deployment".
 *
 * Phase 7 (inc 5): a Compose Desktop [Tray] keeps the app alive when the window
 * is hidden (window-close hides to tray rather than quitting); the model stays
 * resident ([WarmModel]) and the single-consumer [TaskQueue] runs queued agent
 * tasks in the background on a long-lived app scope. Tray notifications carry
 * clock fires + task completions via the [MutableNotificationPresenter] delegate.
 * Real screens (enqueue UI, chat) land in Phase 9.
 */
fun main() {
    val koin = startKoin {
        modules(agentCoreModule, desktopModule, uiModule, desktopAppModule)
    }.koin

    // Force-resolve the agent core + the Phase-7 runtime singletons so a broken
    // graph fails fast at startup rather than on first use.
    koin.get<PromptAssembler>()
    koin.get<PreflightRouter>()
    koin.get<SearchService>()
    koin.get<InferenceEngine>()
    koin.get<AgentLoopFactory>()
    koin.get<DesktopModelDownloader>()
    koin.get<ClockService>()
    koin.get<TaskRepository>()
    koin.get<DesktopTelemetryScheduler>()
    koin.get<MutableNotificationPresenter>()
    koin.get<ChatSessionController>()
    // Resolve every shared :ui ChatViewModel collaborator + chat-screen seam so a
    // missing chat-graph binding fails the headless smoke test rather than only at
    // render time. The VM itself can't be constructed here (its viewModelScope/
    // stateIn need Dispatchers.Main, absent headless), so resolve its deps — all
    // singletons — directly. (Regression guard: TranslationIntentDetector +
    // ConversationRepository were androidModule-only until the Phase-9 Chat cutover.)
    koin.get<TranslationIntentDetector>()
    koin.get<ConversationRepository>()
    koin.get<LanguagePreferences>()
    koin.get<MemoryExtractor>()
    koin.get<MemoryStore>()
    koin.get<TelemetryCounters>()
    koin.get<TtsPreferences>()
    koin.get<ChatSpeaker>()
    koin.get<ChatLogger>()
    koin.get<ThermalStatusProvider>()
    koin.get<SystemMemoryStatusProvider>()
    koin.get<Dictation>()
    koin.get<AppBuildConfig>()
    koin.get<UrlOpener>()
    koin.get<ImagePreprocessor>()
    koin.get<FilePicker>()
    System.err.println("[desktopApp] Koin agent graph resolved OK")

    if (System.getenv("DI_CHECK") == "1") {
        kotlin.system.exitProcess(0)
    }

    // --- Single instance (Phase 8) -------------------------------------------
    // The app keeps a warm model, a file-backed SQLite DB, and a single-consumer
    // task queue resident. A second instance would mean two warm models fighting
    // for RAM, two task consumers racing the same `tasks` rows, and SQLite write
    // contention. Hold an OS file lock for the JVM's lifetime; if another process
    // already holds it, exit quietly. Checked AFTER the DI_CHECK exit so the
    // headless smoke test never contends with (or blocks) a real instance.
    if (!SingleInstance.acquire()) {
        System.err.println("[desktopApp] Another Local Agent instance is already running; exiting.")
        return
    }

    // --- Warm-model background runtime (Phase 7) -----------------------------
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = koin.get<AgentClock>()
    val presenter = koin.get<MutableNotificationPresenter>()
    val warmModel = koin.get<WarmModel>()
    // PR #56 — drop the resident model when the Ollama server config changes so
    // the next turn re-decides remote-vs-local. drop(1) skips the replayed value
    // at startup (nothing loaded yet).
    koin.get<OllamaPreferences>().configFlow()
        .drop(1)
        .distinctUntilChanged()
        .onEach { warmModel.invalidate() }
        .launchIn(appScope)
    // PR #56 — drop the resident model when the Ollama server goes offline (fall
    // back to local) or comes back (reconnect), so the next turn re-decides.
    koin.get<OllamaConnectionMonitor>().reloadRequests
        .onEach { warmModel.invalidate() }
        .launchIn(appScope)
    // PR #78 — drop the resident model when the GPU device pin changes so the next turn
    // re-launches llama-server pinned to the chosen device. drop(1) skips the startup replay.
    koin.get<DesktopGpuPreferences>().devicePinFlow()
        .drop(1)
        .distinctUntilChanged()
        .onEach { warmModel.invalidate() }
        .launchIn(appScope)

    // PR #57 — mobile↔desktop link server: the OpenAI proxy drives generation
    // through warmModel.session() (the desktop's own backend, local OR its own
    // Ollama — never exposed to the phone), plus REST sync + pairing. Once bound,
    // publish the pairing-QR payload (LAN IP + port + token + device id) for the
    // Settings screen.
    val desktopLinkPrefs = koin.get<DesktopLinkPreferences>()
    // PR #74 — paid "anywhere access". The link server hosts the Stripe Checkout
    // success callback; the service claims the credential and re-validates on launch.
    val subscription = koin.get<RelaySubscriptionService>()
    // The one implementation of each link route body, shared by the LAN server
    // and (in the relay follow-up) the relay frame dispatcher.
    // PR #84 — a mobile peer may ask the desktop to run a job now (the phone can't
    // spawn subprocesses). Guard on the id existing; the desktop is the authority.
    val jobRepository = koin.get<JobRepository>()
    val jobService = koin.get<JobService>()
    val jobExecutor = koin.get<JobExecutor>()
    val linkHandler = DesktopLinkRequestHandler(
        preferences = desktopLinkPrefs,
        sessionProvider = { warmModel.session() },
        syncService = koin.get<LinkSyncService>(),
        runJob = { id ->
            if (jobRepository.get(id) != null) {
                jobService.runNow(id)
                true
            } else {
                false
            }
        },
        // PR #88 — a mobile "run job …" chat command runs the job inline on the
        // desktop and streams its output back. Unknown id → null (→ stream 404);
        // cancellation of the relay stream cancels runCapture → kills the process.
        runJobInline = { id, keywords ->
            jobRepository.get(id)?.let { job -> jobExecutor.runCapture(job, keywords) }
        },
    )
    val linkServer = DesktopLinkServer(
        onSubscribeCallback = { code, nonce -> subscription.handleClaimCode(code, nonce) },
        logger = { System.err.println("[DesktopLink] $it") },
    )
    // The checkout redirect targets the loopback callback server's port.
    subscription.callbackPortProvider = { linkServer.boundPort.takeIf { it > 0 } }
    // Launch-time subscription re-validation (only if an account exists locally, #74).
    appScope.launch { runCatching { subscription.refresh() } }
    // The relay pairing QR (JSON). Published only while a subscription is active
    // (minted by the relay lifecycle below); null otherwise. PR #80 removed the LAN
    // `magent://` QR — the relay is the only pairing path, so an unsubscribed desktop
    // shows no QR at all.
    val relayQrPayload = MutableStateFlow<String?>(null)
    // Epoch-ms the shown QR expires (now + PAIRING_WINDOW), published alongside the payload
    // so the desktop UI can render a live countdown (PR #92); null whenever no QR is shown.
    val relayQrExpiresAt = MutableStateFlow<Long?>(null)
    appScope.launch {
        runCatching {
            linkServer.start() // serves /ping + the Stripe /subscribe/callback (loopback)
            combine(relayQrPayload, relayQrExpiresAt) { payload, expiresAt -> payload to expiresAt }
                .onEach { (payload, expiresAt) ->
                    koin.get<MutableDesktopLinkQr>().set(payload, expiresAt)
                    System.err.println("[DesktopLink] pairing QR ${if (payload != null) "ready" else "unavailable"}")
                }
                .launchIn(appScope)
        }.onFailure { System.err.println("[DesktopLink] callback server failed to start: ${it.message}") }
    }

    // Relay transport lifecycle. While a subscription is active, mint the relay QR,
    // wait for the phone to pair, connect, and serve framed link requests through the
    // shared handler. The relay is the only mobile↔desktop pairing path (PR #80).
    val relayHost = koin.get<DesktopRelayHost>()
    appScope.launch {
        // Re-run the lifecycle when the subscription's active state changes OR when a
        // Disconnect re-arms (relayHost.rearm bumps after revoking the pairing), so the
        // desktop re-mints a fresh QR for the next phone — like the LAN token rotation.
        combine(
            koin.get<SubscriptionPreferences>().stateFlow().map { it.isActive },
            relayHost.rearm,
        ) { active, epoch -> active to epoch }
            .distinctUntilChanged()
            .collectLatest { (active, _) ->
                // Drop any prior session (revoked by a peer Unpair, or our own Disconnect)
                // before (re)arming, so generatePairingQr() builds a fresh client + token.
                relayHost.close()
                if (!active) {
                    relayQrPayload.value = null
                    relayQrExpiresAt.value = null
                    return@collectLatest
                }
                runCatching {
                    withContext(Dispatchers.IO) {
                        // Reconnect a persisted pairing first (desktop restart with the phone still
                        // paired) — no fresh QR, no re-scan (PR #91). Only mint a QR + await a
                        // re-pair when there's no saved pairing or the reconnect fails.
                        if (relayHost.reconnect(linkHandler, appScope)) {
                            relayQrPayload.value = null
                            relayQrExpiresAt.value = null
                            System.err.println("[Relay] reconnected to existing pairing; serving framed link requests")
                        } else {
                            // PR #92 — don't auto-mint (the token is only valid ~300s). Show the
                            // "Pair Now" button (no QR) and wait for the user's click before minting.
                            relayQrPayload.value = null
                            relayQrExpiresAt.value = null
                            while (currentCoroutineContext().isActive) {
                                relayHost.pairRequests.first() // suspend until "Pair Now"
                                relayQrExpiresAt.value =
                                    clock.nowEpochMs() + DesktopRelayHost.PAIRING_WINDOW.toMillis()
                                relayQrPayload.value = relayHost.generatePairingQr()
                                System.err.println("[Relay] pairing QR ready; awaiting phone (300s)…")
                                val paired = runCatching {
                                    relayHost.awaitPairing(DesktopRelayHost.PAIRING_WINDOW)
                                    true
                                }.getOrElse { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    false
                                }
                                relayQrPayload.value = null
                                relayQrExpiresAt.value = null
                                if (paired) {
                                    relayHost.connectAndServe(linkHandler, appScope)
                                    System.err.println("[Relay] connected; serving framed link requests")
                                    break // connected — stop waiting for further pair requests
                                } else {
                                    // Window elapsed without a scan — drop the half-open client so the
                                    // next mint is fresh, then loop back to the "Pair Now" button.
                                    relayHost.close()
                                    System.err.println("[Relay] pairing window expired; showing Pair Now again")
                                }
                            }
                        }
                    }
                }.onFailure {
                    // A re-arm (or subscription change) cancels the in-flight mint via
                    // collectLatest — that's normal control flow, not a relay failure.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    reportRelaySetupFailure(it)
                    relayQrPayload.value = null
                }
            }
    }
    // Mirror the relay pipe state + persisted paired marker into the "Local Agent
    // Connection" status so Settings shows "connected" when a phone is attached, and
    // "Mobile agent offline" (with Disconnect) while a paired phone is merely away (#55,
    // PR #90).
    appScope.launch {
        combine(relayHost.state, relayHost.peerPaired) { st, paired -> st to paired }
            .collect { (st, paired) ->
                koin.get<MutableDesktopLinkConnectionStatus>()
                    .update(connected = st == LinkConnectionState.UP, everPaired = paired)
            }
    }

    val taskRunner = DesktopTaskRunner(
        warmModel = warmModel,
        factory = koin.get(),
        language = koin.get<LanguagePreferences>(),
    )
    val taskQueue = TaskQueue(
        repository = koin.get(),
        runner = taskRunner,
        notifications = presenter,
        stringCatalog = koin.get(),
        nowEpochMs = { clock.nowEpochMs() },
        scope = appScope,
    )

    val modelInventory = koin.get<DesktopModelInventory>()
    val modelDownload = DesktopModelDownloadController(
        inventory = modelInventory,
        downloader = koin.get<DesktopModelDownloader>(),
        notifications = presenter,
        stringCatalog = koin.get(),
        scope = appScope,
        // PR #95 — suppress the per-model start/complete toast; a single aggregated
        // "Models ready" notification fires once all first-run models finish.
        notifyMilestones = false,
    )

    // PR #55 — vision projector (mmproj) acquisition. Fetched in the background like
    // the GGUF; the engine loads it on the next cold model load when present (so an
    // image turn can route through llama.cpp's mtmd pipeline). Optional: text chat
    // works without it, and the engine logs a clear degrade when an image arrives
    // before the projector is downloaded.
    val mmprojInventory = koin.get<DesktopModelInventory>(named("mmproj"))
    val mmprojDownload = DesktopModelDownloadController(
        inventory = mmprojInventory,
        downloader = koin.get<DesktopModelDownloader>(named("mmproj")),
        notifications = presenter,
        stringCatalog = koin.get(),
        scope = appScope,
        logger = { System.err.println("[MmprojDownload] $it") },
        notifyMilestones = false, // PR #95 — see modelDownload above (single aggregate notice)
    )

    // PR #55 (Option 3) — prebuilt llama-server acquisition, with its own status flow so the
    // chat banner can reflect it alongside the GGUF + mmproj. GPU (Vulkan) variant by
    // default; the engine falls back to CPU at load if the GPU server can't start.
    // `LOCALAGENT_LLAMA_SERVER_VARIANT=cpu` prefetches CPU instead.
    val wantGpu = System.getenv("LOCALAGENT_LLAMA_SERVER_VARIANT")?.trim()?.lowercase() != "cpu"
    val serverBinaryStore = koin.get<LlamaServerBinaryStore>()
    val serverAssetBytes = LlamaServerRelease.assetForHost(wantGpu)?.sizeBytes ?: 0L
    val serverStatus = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)

    // Surface ALL first-run downloads on the chat banner ("Downloading model… N%", aggregate
    // across the GGUF + vision projector + server binary) instead of "next prompt cold-loads".
    koin.get<DesktopChatSessionController>().bindDownloads(
        sources = listOf(
            DownloadSource(modelInventory.spec.sizeBytes, modelDownload.status),
            DownloadSource(mmprojInventory.spec.sizeBytes, mmprojDownload.status),
            DownloadSource(serverAssetBytes, serverStatus),
        ),
        scope = appScope,
    )

    // PR #95 — one "Models ready" notification once ALL first-run downloads (GGUF +
    // vision projector + server binary) finish, instead of one toast per model. The
    // per-controller milestone toasts are suppressed (notifyMilestones = false above);
    // stays silent for a returning user whose models are already present.
    notifyWhenAllModelsDownloaded(
        sources = listOf(modelDownload.status, mmprojDownload.status, serverStatus),
        notifications = presenter,
        stringCatalog = koin.get(),
        scope = appScope,
    )

    // Startup lifecycle: re-arm persisted alarms/timers (desktop boot-receiver
    // analogue), start the telemetry upload loop, start the task consumer, and
    // fetch the GGUF in the background if it isn't downloaded yet (tray progress).
    koin.get<ClockService>().rearmAll()
    koin.get<DesktopTelemetryScheduler>().start()
    taskQueue.start()
    // PR #100 — extract the bundled `agent-jobs` library into <app-data>/agent-jobs
    // (overlay, gated by the deployment stamp) so the Choose Job catalog has jobs to
    // offer. Best-effort; a missing resource (bare classpath) is a no-op.
    appScope.launch { runCatching { koin.get<com.contextsolutions.localagent.job.DesktopJobLibraryStore>().ensure() } }
    // PR #70 — re-arm persisted jobs (cron + future one-shots). After taskQueue
    // so any immediately-firing job has a live runtime. rearmAll is suspend.
    appScope.launch { koin.get<com.contextsolutions.localagent.job.JobService>().rearmAll() }
    // PR #93 — notify on the desktop when a job finishes (was Android-only, #58): the
    // desktop is where jobs run, so surface SUCCEEDED/FAILED via notify-send (Linux) /
    // tray toast (macOS/Windows). Rides JobRepository.flow() with a baseline-suppress so
    // the startup backfill doesn't storm. Runs in every mode (GUI + headless).
    koin.get<com.contextsolutions.localagent.job.JobCompletionNotifier>().start(appScope)
    modelDownload.ensurePresent()
    mmprojDownload.ensurePresent()
    // Server binary in the background, driving serverStatus (loadModel also ensures it lazily).
    appScope.launch {
        if (serverBinaryStore.isPresent(wantGpu)) {
            serverStatus.value = ModelDownloadStatus.Present
            return@launch
        }
        serverStatus.value = ModelDownloadStatus.Downloading(0f, 0L, serverAssetBytes)
        runCatching {
            serverBinaryStore.ensure(wantGpu) { done, total ->
                serverStatus.value = ModelDownloadStatus.Downloading(
                    if (total > 0L) done.toFloat() / total else 0f, done, total,
                )
            }
        }.onSuccess { serverStatus.value = ModelDownloadStatus.Present }
            .onFailure { serverStatus.value = ModelDownloadStatus.Failed(it.message ?: "server download failed", retryable = true) }
    }

    // Prefetch the Vosk speech-to-text model (~40 MB) in the background so the
    // first mic toggle starts dictating immediately instead of blocking on the
    // download. ensure() no-ops when the model is already cached / env-overridden
    // and returns null (no throw) when offline — dictation just stays disabled
    // until a later launch succeeds. Not surfaced on the chat banner: it's
    // unrelated to the LLM and shouldn't read as "chat is loading".
    appScope.launch { runCatching { koin.get<VoskModelStore>().ensure() } }

    // Eagerly warm the ONNX aux engines (preflight classifier + memory embedder).
    // Both are inert until warmUp() runs — classify()/embed() return null while
    // their OrtSession is unset, silently degrading the agent to Gemma-only +
    // no-op memory. Android drives this from the Chat-screen RESUME hook
    // (LifecycleResumeEffect, invariant #22); desktop has no such lifecycle, so we
    // warm once on the long-lived app scope. warmUp() never throws and is
    // idempotent; the warm sessions stay resident for the JVM's lifetime.
    appScope.launch {
        // First run: fetch the ONNX aux models (classifier + embedder) if absent, like
        // the GGUF/Vosk, THEN warm. ensure*() no-ops when the file is already present or
        // env-overridden, and silently skips when the hosting endpoint isn't configured
        // (-PauxModelBaseUrl); warmUp() then degrades to Gemma-only / no-op memory.
        // Not surfaced on the chat banner (like Vosk): unrelated to the LLM load.
        val auxModels = koin.get<com.contextsolutions.localagent.inference.DesktopAuxModelStore>()
        runCatching { auxModels.ensureClassifier() }
        runCatching { auxModels.ensureEmbedder() }
        koin.get<ClassifierEngine>().warmUp()
        koin.get<EmbedderEngine>().warmUp()
    }

    // Eagerly load the GGUF to the GPU so the first chat turn doesn't pay the multi-second
    // cold-load, and the banner shows the real accelerator (Downloading → Loading →
    // Loaded(GPU)). The desktop default is to warm once the model is available — at startup
    // for a returning user, or **right after the first-run download completes**. Android keeps
    // Gemma lazy (loads on first prompt, invariant #22). A model load is one-shot, so we wait
    // for the projector + server too, so it loads with vision on the right backend.
    // warmUp() is idempotent and swallows failures.
    appScope.launch {
        if (!warmModel.isModelPresent()) {
            if (!modelInventory.spec.isConfigured) return@launch // no model to warm
            modelDownload.status.first { it is ModelDownloadStatus.Present || it is ModelDownloadStatus.Failed }
            if (!warmModel.isModelPresent()) return@launch // download failed
        }
        if (mmprojInventory.spec.isConfigured && !mmprojInventory.isPresent()) {
            mmprojDownload.status.first { it is ModelDownloadStatus.Present || it is ModelDownloadStatus.Failed }
        }
        serverStatus.first { it is ModelDownloadStatus.Present || it is ModelDownloadStatus.Failed }
        koin.get<DesktopChatSessionController>().warmUp()
    }

    // Probe system-tray support ONCE, robustly. `SystemTray.isSupported()` lies on
    // modern GNOME/Wayland (returns true, then `getSystemTray()` / peer creation
    // throws UnsupportedOperationException on the AWT EDT). Compose's `Tray()` does
    // the AWT call async on the EDT, so a try/catch around the composable can't catch
    // it — we must decide up-front whether to compose the tray at all.
    val traySupported = runCatching {
        java.awt.SystemTray.isSupported() && java.awt.SystemTray.getSystemTray() != null
    }.getOrDefault(false)
    if (!traySupported) {
        System.err.println("[desktopApp] System tray unavailable; running without a tray icon (close-to-quit).")
    }

    // Background / startup-service mode (systemd, launchd, Task Scheduler): the full
    // runtime above is already live on appScope — jobs, clock, task queue, mobile-link
    // server, warm LLM, aux engines. We don't open the main window on launch; the user
    // opens it on demand from the tray (Show), or shuts the agent down (Shut down).
    //   • Tray available → fall through to application{} but START HIDDEN — the tray keeps
    //     AWT's non-daemon thread alive (same as hide-to-tray today), so the window can be
    //     summoned later. This is the good-UX path on a graphical login session.
    //   • No tray (display-less server, or the GNOME/Wayland trap) → there's nothing to
    //     summon the window from and an invisible-window composition would just exit, so
    //     run FULLY WINDOWLESS: block the main thread and rely on the service manager.
    val startHeadless = System.getenv("LOCALAGENT_HEADLESS") == "1"
    if (startHeadless && !traySupported) {
        System.err.println(
            "[desktopApp] headless (no tray): runtime started (jobs/clock/tasks/link/LLM). SIGTERM to stop.",
        )
        // Block the non-daemon main thread to keep the JVM alive — appScope runs on
        // Dispatchers.Default (daemon threads), so returning from main would exit. The
        // shutdown hook fires on SIGTERM (systemd's default) / Ctrl-C and mirrors the GUI
        // shutdown() cleanup (sans exitProcess — we're already tearing down).
        val done = java.util.concurrent.CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { relayHost.close() }
            runCatching { linkServer.stop() }
            runCatching { warmModel.unload() }
            runCatching { appScope.cancel() }
            done.countDown()
        })
        done.await()
        return
    }
    if (startHeadless) {
        System.err.println("[desktopApp] background: runtime started, minimized to tray (Show to open).")
    }

    application {
        // Shared shutdown path: the tray "Shut down" item AND the no-tray window-close
        // both use it. Without a tray, hiding the window would trap it (no Show/Shut down
        // affordance), so close must actually exit.
        fun shutdown() {
            // Best-effort cleanup, each guarded so a failure can't divert us before the
            // process exit below (a half-run shutdown was part of why tray Shut down misbehaved
            // — PR #71). We deliberately do NOT call exitApplication(): it only ends the
            // Compose composition (the AWT SystemTray + tray popup keep AWT's non-daemon
            // thread alive, so the JVM lingers) and its window teardown could surface the
            // hidden main window for an instant. exitProcess(0) terminates immediately and
            // unconditionally; the OS drops the tray icon when the process dies.
            runCatching { relayHost.close() }
            runCatching { linkServer.stop() }
            runCatching { warmModel.unload() }
            runCatching { appScope.cancel() }
            exitProcess(0)
        }

        // Start hidden in background/startup mode (tray-only until the user clicks Show);
        // normal launches open the window. Only reached with a tray (the no-tray headless
        // path returned above), so a hidden start is always recoverable.
        var windowVisible by remember { mutableStateOf(!startHeadless) }

        // Theme + UI-zoom prefs, collected up here so BOTH the styled tray menu (PR #71)
        // and the main Window below share them. desktopApp has no koin-compose, so we read
        // the singletons off the Koin graph and collect their flows. (PR #59: this is what
        // makes the desktop light/auto/dark selector actually drive the colour scheme.)
        val zoomPrefs = koin.get<DesktopThemePreferences>()
        val uiZoom by zoomPrefs.uiZoomFlow().collectAsState(initial = zoomPrefs.uiZoom())
        val themePrefs = koin.get<ThemePreferences>()
        val themeMode by themePrefs.themeModeFlow().collectAsState(initial = themePrefs.themeMode())
        val fontScale by themePrefs.fontScaleFlow().collectAsState(initial = themePrefs.fontScale())
        val fontFamily by themePrefs.fontFamilyFlow().collectAsState(initial = themePrefs.fontFamily())
        // i18n active strings (PR #96) — seeded into the composition like the theme prefs.
        val strings by koin.get<StringCatalog>().active.collectAsState()
        // Colours for the Swing tray menu, tracking the resolved theme + zoom. Held in a
        // rememberUpdatedState so the long-lived install lambda reads the live value at
        // show time. (System mode on Linux is unreliable — invariant #46 — but harmless
        // here: the menu just follows isSystemInDarkTheme's best guess.)
        val darkResolved = when (themeMode) {
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
            ThemeMode.System -> isSystemInDarkTheme()
        }
        val trayStyle = rememberUpdatedState(trayMenuStyle(darkResolved, uiZoom))

        if (traySupported) {
            // Raw AWT tray (AwtTray) instead of Compose's `Tray()` so the icon can be
            // enlarged 2× (issue #68 — Compose's Tray exposes no font hook and renders the
            // icon only at the tray's preferred size). The menu is a STYLED Swing
            // JPopupMenu (PR #71) — not native AWT (unstyleable on Linux) and not a Compose
            // window (AWT's tray pointer-grab eats the click; see AwtTray). On install it
            // routes clock + task notifications to the tray; if the AWT peer fails to create
            // (the GNOME/Wayland trap) it returns null and the default logging
            // DesktopNotificationPresenter stays in place (degrade to logs, not crash).
            DisposableEffect(Unit) {
                val tray = AwtTray.install(
                    tooltip = "Local Agent",
                    onShow = { windowVisible = true },
                    onQuit = { shutdown() },
                    menuStyle = { trayStyle.value },
                )
                // On Linux notifications go through notify-send (PR #93) — the DI
                // fallback already installed a LinuxNotificationPresenter, so DON'T
                // overwrite it with the tray toast presenter even if a tray exists
                // (e.g. a GNOME extension). The tray's Show/Shut down menu still works.
                if (tray != null && !DesktopOs.isLinux) presenter.setDelegate(tray.notificationPresenter)
                onDispose { tray?.remove() }
            }
        }

        // Restore the window the way the user left it (size/position/maximized),
        // persisted in window_prefs.json. First run falls back to 960×720 centered.
        val windowPrefs = koin.get<DesktopWindowPreferences>()
        val savedGeometry = remember { windowPrefs.load() }
        val savedX = savedGeometry?.xDp
        val savedY = savedGeometry?.yDp
        val windowState = rememberWindowState(
            placement = if (savedGeometry?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (savedX != null && savedY != null) {
                WindowPosition(savedX.dp, savedY.dp)
            } else {
                WindowPosition.PlatformDefault
            },
            size = DpSize(
                (savedGeometry?.widthDp ?: 960f).dp,
                (savedGeometry?.heightDp ?: 720f).dp,
            ),
        )
        // Persist geometry on change, debounced (collectLatest cancels the in-flight
        // delay when a newer value arrives mid-drag, so we only write once it settles).
        // Width/height are saved only while floating, so maximizing doesn't clobber the
        // restore size; position is saved only when absolute (skips PlatformDefault).
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
                .collectLatest { (size, position, placement) ->
                    delay(500)
                    val floating = placement == WindowPlacement.Floating
                    val absolute = position as? WindowPosition.Absolute
                    windowPrefs.save(
                        widthDp = if (floating) size.width.value else null,
                        heightDp = if (floating) size.height.value else null,
                        xDp = absolute?.x?.value,
                        yDp = absolute?.y?.value,
                        maximized = placement == WindowPlacement.Maximized,
                    )
                }
        }
        Window(
            // With a tray, close hides to it (background runtime stays resident). With
            // no tray, close exits cleanly so the window can never be trapped.
            onCloseRequest = { if (traySupported) windowVisible = false else shutdown() },
            visible = windowVisible,
            state = windowState,
            title = "Local Agent",
            icon = AppIcon,
            // Ctrl (Cmd on macOS) + Equals/Plus zoom in, + Minus zooms out, + 0 resets.
            // `onKeyEvent` fires only on events the focused component left unconsumed —
            // a focused text field never consumes Ctrl/Cmd combos, so typing is safe.
            onKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) {
                    false
                } else when (event.key) {
                    Key.Equals, Key.Plus, Key.NumPadAdd -> {
                        zoomPrefs.setUiZoom(zoomPrefs.uiZoom() + UiZoom.STEP); true
                    }
                    Key.Minus, Key.NumPadSubtract -> {
                        zoomPrefs.setUiZoom(zoomPrefs.uiZoom() - UiZoom.STEP); true
                    }
                    Key.Zero, Key.NumPad0 -> {
                        zoomPrefs.setUiZoom(UiZoom.DEFAULT); true
                    }
                    else -> false
                }
            },
        ) {
            // The real shared UI (Phase 9 inc 8d). Onboarding is operator-driven on
            // desktop (keys via env/SecureStorage, Phase 6) and the GGUF is fetched
            // in the background via the tray, so both gates pass through to Chat; the
            // model loads lazily on the first turn (WarmModel). Queue/download status
            // remains visible via tray notifications.
            // Theme prefs (themeMode/fontScale/fontFamily/uiZoom) are collected above in
            // the application scope so the tray popup shares them (PR #71).
            CompositionLocalProvider(LocalStrings provides strings) {
                LocalAgentDesktopTheme(themeMode = themeMode, fontScale = fontScale, fontFamily = fontFamily, densityScale = uiZoom) {
                    AppNavHost(
                        onboardingComplete = true,
                        modelPresent = true,
                        downloadContent = {},
                    )
                }
            }
        }
    }
}

/**
 * Print a clear, actionable banner when the relay (anywhere-access) setup fails, instead of
 * a terse one-liner — the failure means the desktop is silently falling back to the LAN QR,
 * so the user needs to know why and how to fix it (#55). Interprets the common auth-service
 * reason codes carried in the exception message.
 */
private fun reportRelaySetupFailure(error: Throwable) {
    val msg = error.message ?: error.toString()
    val fix = when {
        "bad_devices" in msg ->
            "The gateway didn't recognize this desktop's saved device id — usually a re-claim under a NEW " +
                "account or a gateway restart with AUTH_STORE=memory. PR #80 auto-clears the stale id and " +
                "re-registers, so a retry normally recovers; if it persists, clear the desktop subscription " +
                "(delete subscription_prefs.json + RELAY_DESKTOP_DEVICE_ID) and re-claim against this gateway."
        "capacity_exceeded" in msg ->
            "The subscription's pairing slots (max_pairs, default 1) are full — likely an old/other " +
                "desktop still holds the slot. Unpair it (POST /v1/pairings/unpair), raise max_pairs on the " +
                "Stripe price + re-subscribe, reuse the same desktop install (relay_identity.key) so it re-pairs, " +
                "or restart the gateway to reset (memory store)."
        "license_not_found" in msg || "license_invalid" in msg || "no_subscription" in msg ->
            "The account has no valid license. Re-subscribe and confirm 'license provisioned' appears in the " +
                "gateway log before pairing (needs AUTH_STRIPE_SECRET_KEY + the webhook to provision)."
        "401" in msg || "unauthorized" in msg ->
            "The account secret was rejected (often a gateway restart with AUTH_STORE=memory). Clear the desktop " +
                "subscription and re-claim against the current gateway."
        else -> "See the cause above; check the gateway (cmd/auth) log for the matching error."
    }
    // PR #80 — the LAN QR is gone, so a relay-mint failure means NO pairing code is shown.
    System.err.println("┌─ [Relay] anywhere-access UNAVAILABLE — NOT publishing a pairing QR (no LAN fallback since #80).")
    System.err.println("│  cause: $msg")
    System.err.println("└─ fix:   $fix")
}

/**
 * Colours for the styled Swing tray menu, tracking the app's monochrome theme (PR #71).
 * Mirrors `Theme.kt`'s Light/Dark monochrome schemes (surfaceContainer + onSurface +
 * outlineVariant); font scales with the UI-zoom.
 */
private fun trayMenuStyle(dark: Boolean, uiZoom: Float): TrayMenuStyle {
    val fontSize = (13f * uiZoom).roundToInt().coerceAtLeast(11)
    return if (dark) {
        TrayMenuStyle(
            background = Color(0x1E, 0x1E, 0x1E),
            foreground = Color(0xFF, 0xFF, 0xFF),
            border = Color(0x3A, 0x3A, 0x3A),
            fontSize = fontSize,
        )
    } else {
        TrayMenuStyle(
            background = Color(0xFF, 0xFF, 0xFF),
            foreground = Color(0x00, 0x00, 0x00),
            border = Color(0xC8, 0xC8, 0xC8),
            fontSize = fontSize,
        )
    }
}

/**
 * The app/tray icon — the brand logo bundled as a classpath resource
 * (`icon.png`, generated by `desktopApp/icons/generate_icons.py`, the same master
 * as the per-OS installer icons in `nativeDistributions`). **Lazy:** decoding pulls
 * in Skia's native libs, which need a display (libGL); deferring past the `DI_CHECK`
 * early-exit keeps the headless DI smoke test working — the icon is only touched once
 * the tray + window compose, which only happens on a real desktop.
 */
private val AppIcon: Painter by lazy {
    BitmapPainter(
        object {}.javaClass.getResourceAsStream("/icon.png")!!
            .use { it.readAllBytes().decodeToImageBitmap() },
    )
}

/**
 * Process-wide single-instance guard. Acquires an exclusive [FileLock] on
 * `<app-data>/.instance.lock`; the lock + channel are kept alive for the JVM's
 * lifetime (the OS releases them on exit, covering crashes too). [acquire] returns
 * `false` only when another *process* already holds the lock — a clean
 * "already running" signal. On any other error (can't create the file, etc.) it
 * returns `true` and lets startup proceed: failing open beats blocking the app on
 * a lock-file glitch.
 */
private object SingleInstance {
    // Strong refs so the lock isn't released by GC mid-run.
    @Suppress("unused") private var channel: FileChannel? = null
    @Suppress("unused") private var lock: FileLock? = null

    fun acquire(): Boolean {
        return try {
            val dir = DesktopAppDirs.dataDir().apply { mkdirs() }
            val ch = RandomAccessFile(dir.resolve(".instance.lock"), "rw").channel
            val l = ch.tryLock()
            if (l == null) {
                ch.close()
                false // held by another process
            } else {
                channel = ch
                lock = l
                true
            }
        } catch (e: Exception) {
            System.err.println("[desktopApp] single-instance lock unavailable, proceeding: ${e.message}")
            true
        }
    }
}
