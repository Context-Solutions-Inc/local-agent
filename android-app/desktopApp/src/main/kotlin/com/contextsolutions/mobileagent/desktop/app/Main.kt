package com.contextsolutions.mobileagent.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
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
import com.contextsolutions.mobileagent.agent.ChatLogger
import com.contextsolutions.mobileagent.agent.ChatSessionController
import com.contextsolutions.mobileagent.agent.PromptAssembler
import com.contextsolutions.mobileagent.agent.TranslationIntentDetector
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.PreflightRouter
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.inference.SystemMemoryStatusProvider
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.memory.EmbedderEngine
import com.contextsolutions.mobileagent.memory.MemoryExtractor
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.platform.AppBuildConfig
import com.contextsolutions.mobileagent.platform.UrlOpener
import com.contextsolutions.mobileagent.preferences.OllamaPreferences
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import com.contextsolutions.mobileagent.vision.FilePicker
import com.contextsolutions.mobileagent.vision.ImagePreprocessor
import com.contextsolutions.mobileagent.voice.ChatSpeaker
import com.contextsolutions.mobileagent.voice.Dictation
import com.contextsolutions.mobileagent.voice.TtsPreferences
import com.contextsolutions.mobileagent.voice.VoskModelStore
import com.contextsolutions.mobileagent.clock.ClockService
import com.contextsolutions.mobileagent.di.AgentLoopFactory
import com.contextsolutions.mobileagent.di.agentCoreModule
import com.contextsolutions.mobileagent.di.desktopModule
import com.contextsolutions.mobileagent.inference.DesktopModelDownloader
import com.contextsolutions.mobileagent.inference.DesktopModelInventory
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.OllamaConnectionMonitor
import com.contextsolutions.mobileagent.inference.LlamaServerBinaryStore
import com.contextsolutions.mobileagent.inference.LlamaServerRelease
import com.contextsolutions.mobileagent.language.LanguagePreferences
import com.contextsolutions.mobileagent.notification.MutableNotificationPresenter
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.task.TaskQueue
import com.contextsolutions.mobileagent.task.TaskRepository
import com.contextsolutions.mobileagent.telemetry.DesktopTelemetryScheduler
import com.contextsolutions.mobileagent.inference.DesktopAppDirs
import com.contextsolutions.mobileagent.link.DesktopLinkServer
import com.contextsolutions.mobileagent.link.LanAddress
import com.contextsolutions.mobileagent.link.LinkPairingPayload
import com.contextsolutions.mobileagent.link.MutableDesktopLinkConnectionStatus
import com.contextsolutions.mobileagent.link.MutableDesktopLinkQr
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.desktop.app.ui.theme.MobileAgentDesktopTheme
import com.contextsolutions.mobileagent.sync.LinkSyncService
import com.contextsolutions.mobileagent.ui.di.uiModule
import com.contextsolutions.mobileagent.ui.navigation.AppNavHost
import com.contextsolutions.mobileagent.ui.theme.DesktopThemePreferences
import com.contextsolutions.mobileagent.ui.theme.DesktopWindowPreferences
import com.contextsolutions.mobileagent.ui.theme.ThemeMode
import com.contextsolutions.mobileagent.ui.theme.ThemePreferences
import com.contextsolutions.mobileagent.ui.theme.UiZoom
import java.awt.Color
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
        System.err.println("[desktopApp] Another Mobile Agent instance is already running; exiting.")
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

    // PR #57 — mobile↔desktop link server: the OpenAI proxy drives generation
    // through warmModel.session() (the desktop's own backend, local OR its own
    // Ollama — never exposed to the phone), plus REST sync + pairing. Once bound,
    // publish the pairing-QR payload (LAN IP + port + token + device id) for the
    // Settings screen.
    val desktopLinkPrefs = koin.get<DesktopLinkPreferences>()
    val linkServer = DesktopLinkServer(
        preferences = desktopLinkPrefs,
        sessionProvider = { warmModel.session() },
        syncService = koin.get<LinkSyncService>(),
        connectionStatus = koin.get<MutableDesktopLinkConnectionStatus>(),
        logger = { System.err.println("[DesktopLink] $it") },
    )
    appScope.launch {
        runCatching {
            val port = linkServer.start()
            val host = LanAddress.primaryIpv4()
            // Republish the pairing QR whenever the token/device id changes (e.g.
            // a Disconnect rotates the token), so a fresh phone scans a valid QR
            // and the old phone's stale token is rejected.
            desktopLinkPrefs.configFlow()
                .map { cfg -> host?.let { LinkPairingPayload(it, port, cfg.pairingToken, cfg.selfDeviceId).encode() } }
                .distinctUntilChanged()
                .onEach { payload ->
                    koin.get<MutableDesktopLinkQr>().set(payload)
                    System.err.println("[DesktopLink] pairing QR ${if (payload != null) "ready ($host:$port)" else "unavailable (no LAN address)"}")
                }
                .launchIn(appScope)
        }.onFailure { System.err.println("[DesktopLink] server failed to start: ${it.message}") }
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
        nowEpochMs = { clock.nowEpochMs() },
        scope = appScope,
    )

    val modelInventory = koin.get<DesktopModelInventory>()
    val modelDownload = DesktopModelDownloadController(
        inventory = modelInventory,
        downloader = koin.get<DesktopModelDownloader>(),
        notifications = presenter,
        scope = appScope,
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
        scope = appScope,
        logger = { System.err.println("[MmprojDownload] $it") },
    )

    // PR #55 (Option 3) — prebuilt llama-server acquisition, with its own status flow so the
    // chat banner can reflect it alongside the GGUF + mmproj. GPU (Vulkan) variant by
    // default; the engine falls back to CPU at load if the GPU server can't start.
    // `MOBILEAGENT_LLAMA_SERVER_VARIANT=cpu` prefetches CPU instead.
    val wantGpu = System.getenv("MOBILEAGENT_LLAMA_SERVER_VARIANT")?.trim()?.lowercase() != "cpu"
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

    // Startup lifecycle: re-arm persisted alarms/timers (desktop boot-receiver
    // analogue), start the telemetry upload loop, start the task consumer, and
    // fetch the GGUF in the background if it isn't downloaded yet (tray progress).
    koin.get<ClockService>().rearmAll()
    koin.get<DesktopTelemetryScheduler>().start()
    taskQueue.start()
    // PR #70 — re-arm persisted jobs (cron + future one-shots). After taskQueue
    // so any immediately-firing job has a live runtime. rearmAll is suspend.
    appScope.launch { koin.get<com.contextsolutions.mobileagent.job.JobService>().rearmAll() }
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
            runCatching { linkServer.stop() }
            runCatching { warmModel.unload() }
            runCatching { appScope.cancel() }
            exitProcess(0)
        }

        var windowVisible by remember { mutableStateOf(true) }

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
                    tooltip = "Mobile Agent",
                    onShow = { windowVisible = true },
                    onQuit = { shutdown() },
                    menuStyle = { trayStyle.value },
                )
                if (tray != null) presenter.setDelegate(tray.notificationPresenter)
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
            title = "Mobile Agent",
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
            MobileAgentDesktopTheme(themeMode = themeMode, fontScale = fontScale, fontFamily = fontFamily, densityScale = uiZoom) {
                AppNavHost(
                    onboardingComplete = true,
                    modelPresent = true,
                    downloadContent = {},
                )
            }
        }
    }
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
