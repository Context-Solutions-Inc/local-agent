package com.contextsolutions.mobileagent.ui.di

import com.contextsolutions.mobileagent.ui.chat.ChatViewModel
import com.contextsolutions.mobileagent.ui.clock.ClockViewModel
import com.contextsolutions.mobileagent.ui.history.ConversationHistoryViewModel
import com.contextsolutions.mobileagent.ui.memory.MemoryViewModel
import com.contextsolutions.mobileagent.ui.onboarding.OnboardingViewModel
import com.contextsolutions.mobileagent.ui.settings.SearchSourcesViewModel
import com.contextsolutions.mobileagent.ui.settings.SettingsViewModel
import com.contextsolutions.mobileagent.ui.theme.ThemeModeViewModel
import com.contextsolutions.mobileagent.ui.todo.TodoViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin module for the shared `:ui` layer (docs/DESKTOP_PORT_PLAN.md Phase 9).
 *
 * Holds the ViewModel bindings for every screen that has been migrated into
 * `:ui` commonMain. Both shells load it alongside the agent graph
 * ([com.contextsolutions.mobileagent.di.agentCoreModule]) and their
 * platform module (`androidModule` / `desktopModule`).
 *
 * Starts empty: the screen-by-screen cutover (Todo first, Chat last) adds a
 * `viewModelOf(::X)` here as each screen moves and its ViewModel sheds its
 * Android types. ViewModels that still need a platform dependency (e.g. a
 * `Context`-backed helper) bind in the platform module instead — this module
 * only owns the all-common-dependency ViewModels.
 */
val uiModule: Module = module {
    // Phase 9 inc 2 — Todo (the migration proof). All-common dependencies
    // (TodoRepository, already Koin-owned), so it binds here rather than in a
    // platform module.
    viewModelOf(::TodoViewModel)
    // Phase 9 inc 3 — Settings cluster. All deps are Koin-owned on both shells
    // (the Context-bound bits were replaced by the AppBuildConfig seam +
    // TelemetryUploader, and the memory summary reads MemoryStore directly).
    viewModelOf(::SettingsViewModel)
    viewModelOf(::SearchSourcesViewModel)
    // Phase 9 inc 4 — History (clean VM; DateUtils replaced by ui.util.formatRelativeTime).
    viewModelOf(::ConversationHistoryViewModel)
    // Phase 9 inc 5 — Clock (Alarm/Timer screens; clean VM).
    viewModelOf(::ClockViewModel)
    // Phase 9 inc 6 — Onboarding (UrlOpener + LocaleProvider.countryCode seams).
    viewModelOf(::OnboardingViewModel)
    // Phase 9 inc 7 — Memory (backup file-picker + Toaster seams). Bound with an
    // explicit lambda, NOT viewModelOf: MemoryViewModel has an `ioDispatcher:
    // CoroutineDispatcher = Dispatchers.IO` ctor param, and viewModelOf resolves
    // EVERY constructor param by type — CoroutineDispatcher is bound nowhere, so
    // it threw InstanceCreationException the first time the screen rendered (the
    // unit test passes an explicit TestDispatcher, so it never exercised this).
    // The lambda passes only the real deps and lets ioDispatcher keep its default.
    viewModel {
        MemoryViewModel(
            store = get(),
            preferences = get(),
            clock = get(),
            backupController = get(),
        )
    }
    // Phase 9 inc 8d — Theme toggle + Chat. ChatViewModel's last platform deps
    // became Koin seams (ChatSessionController, ChatLogger, ChatSpeaker,
    // ImagePreprocessor, vision/voice via the screen), so it binds here and
    // resolves on both shells.
    viewModelOf(::ThemeModeViewModel)
    viewModelOf(::ChatViewModel)
}
