package com.contextsolutions.mobileagent.desktop.app

import com.contextsolutions.mobileagent.agent.ChatSessionController
import com.contextsolutions.mobileagent.inference.DesktopModelInventory
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * `:desktopApp` Koin module (docs/DESKTOP_PORT_PLAN.md Phase 9 inc 8d). Binds the
 * shell-level singletons that live in `:desktopApp` (above `:shared`/`:ui`): the
 * resident [WarmModel] and the [ChatSessionController] over it that the shared
 * `:ui` `ChatViewModel` resolves. Loaded by `Main.startKoin` alongside
 * `agentCoreModule` + `desktopModule` + `uiModule`.
 *
 * `WarmModel` is a singleton so the tray/task runtime ([Main]) and the chat
 * window share one resident model (one GGUF in RAM, not two). Construction is
 * cheap — the model loads lazily on the first session — so resolving it during
 * the headless `DI_CHECK` is safe.
 */
val desktopAppModule: Module = module {
    // enableVision=true (PR #55): the engine loads the mmproj when present and routes
    // image turns through llama.cpp's mtmd pipeline; it degrades to text-only (logged)
    // when no projector is on disk, so this is safe even before the mmproj download.
    single { WarmModel(engine = get(), inventory = get<DesktopModelInventory>(), enableVision = true) }
    // Bound as the concrete type AND the shared interface (one instance): the
    // shared `:ui` ChatViewModel resolves ChatSessionController, while [Main]
    // resolves the concrete type to drive the startup warm-up ([warmUp]).
    single { DesktopChatSessionController(get()) } bind ChatSessionController::class
}
