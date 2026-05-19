package com.contextsolutions.mobileagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.contextsolutions.mobileagent.app.ui.chat.ChatScreen
import com.contextsolutions.mobileagent.app.ui.chat.ChatViewModel
import com.contextsolutions.mobileagent.app.ui.clock.AlarmManagementScreen
import com.contextsolutions.mobileagent.app.ui.clock.TimerManagementScreen
import com.contextsolutions.mobileagent.app.ui.download.DownloadScreen
import com.contextsolutions.mobileagent.app.ui.history.ConversationHistoryScreen
import com.contextsolutions.mobileagent.app.ui.memory.ConversationMemoryListScreen
import com.contextsolutions.mobileagent.app.ui.memory.MemoryScreen
import com.contextsolutions.mobileagent.app.ui.onboarding.OnboardingHost
import com.contextsolutions.mobileagent.app.ui.settings.SearchSourcesScreen
import com.contextsolutions.mobileagent.app.ui.settings.SettingsScreen
import com.contextsolutions.mobileagent.app.ui.todo.TodoManagementScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top-level Composable hosted by [com.contextsolutions.mobileagent.app.MainActivity].
 *
 * Five states, all data-driven (no Compose Navigation graph):
 *  - Model not present → [DownloadScreen]
 *  - Chat (default) → [ChatScreen]
 *  - Settings → [SettingsScreen]
 *  - Memory management (M5 Phase E) → [MemoryScreen]
 *  - Per-conversation memory list (M5 Phase E) → [ConversationMemoryListScreen]
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val modelPresent by viewModel.modelPresent.collectAsState()
    val onboardingComplete by viewModel.onboardingComplete.collectAsState()
    var route by rememberSaveable(stateSaver = MainRoute.Saver) {
        mutableStateOf<MainRoute>(MainRoute.Chat)
    }

    // M6 Phase E — first-run onboarding gate (PRD §6.1). Lands new
    // installs on the disclosure → Brave key → telemetry consent flow
    // before the existing download screen. Re-entering the app
    // mid-onboarding resumes at the right step (state persisted in
    // SharedPreferences via OnboardingPreferences + TelemetryConsentManager).
    if (!onboardingComplete) {
        OnboardingHost(onComplete = { /* state flips automatically */ })
        return
    }

    if (!modelPresent) {
        DownloadScreen()
        return
    }

    // M6 Phase B — eager Gemma warm-up.
    //
    // Fires when the Chat surface becomes visible AND the Activity is in the
    // RESUMED state. Two triggers, one effect:
    //   1. User navigates to Chat from Settings / Memory → route changes,
    //      effect re-keys, runs.
    //   2. User backgrounds the app and returns → ON_PAUSE then ON_RESUME,
    //      LifecycleResumeEffect re-runs its body. Important: 5-min idle
    //      unload (M0 Decision 5) or onTrimMemory may have unloaded the model
    //      while we were backgrounded — we'd otherwise pay the cold-load on
    //      the next send().
    //
    // 300 ms debounce catches short Settings → Chat flips and short
    // background bounces (notification glance, quick app-switcher peek) so
    // the model load isn't kicked off for trivial visits. Compose's
    // LifecycleResumeEffect cancels the launched coroutine via
    // onPauseOrDispose when ON_PAUSE fires or the route changes, so an
    // in-flight warm-up that becomes irrelevant gets cleaned up without
    // racing the actual load.
    val warmUpScope = rememberCoroutineScope()
    LifecycleResumeEffect(route) {
        var warmUpJob: Job? = null
        if (route is MainRoute.Chat) {
            warmUpJob = warmUpScope.launch {
                delay(EAGER_WARMUP_DEBOUNCE_MS)
                viewModel.warmUpEagerly()
            }
        }
        onPauseOrDispose { warmUpJob?.cancel() }
    }

    // PR#13 — hoist ChatViewModel so the conversation-history screen can
    // ask it to load a different conversation without spinning up a second
    // instance. Reaching the chat ViewModel from sibling routes is
    // necessary because the no-Nav-Compose setup doesn't expose a shared
    // back-stack scope.
    val chatViewModel: ChatViewModel = hiltViewModel()
    when (val current = route) {
        MainRoute.Chat -> ChatScreen(
            onOpenSettings = { route = MainRoute.Settings },
            onOpenConversationMemory = { conversationId ->
                route = MainRoute.ConversationMemory(conversationId)
            },
            onOpenTodos = { route = MainRoute.TodoManagement },
            onOpenTimers = { route = MainRoute.TimerManagement },
            onOpenAlarms = { route = MainRoute.AlarmManagement },
            viewModel = chatViewModel,
        )
        MainRoute.TodoManagement -> {
            BackHandler { route = MainRoute.Chat }
            TodoManagementScreen(onBack = { route = MainRoute.Chat })
        }
        MainRoute.TimerManagement -> {
            BackHandler { route = MainRoute.Chat }
            TimerManagementScreen(onBack = { route = MainRoute.Chat })
        }
        MainRoute.AlarmManagement -> {
            BackHandler { route = MainRoute.Chat }
            AlarmManagementScreen(onBack = { route = MainRoute.Chat })
        }
        MainRoute.Settings -> {
            BackHandler { route = MainRoute.Chat }
            SettingsScreen(
                onBack = { route = MainRoute.Chat },
                onOpenMemoryManagement = { route = MainRoute.MemoryManagement },
                onOpenConversationHistory = { route = MainRoute.ConversationHistory },
                onOpenSearchSources = { route = MainRoute.SearchSources },
            )
        }
        MainRoute.SearchSources -> {
            BackHandler { route = MainRoute.Settings }
            SearchSourcesScreen(onBack = { route = MainRoute.Settings })
        }
        MainRoute.MemoryManagement -> {
            BackHandler { route = MainRoute.Settings }
            MemoryScreen(onBack = { route = MainRoute.Settings })
        }
        MainRoute.ConversationHistory -> {
            BackHandler { route = MainRoute.Settings }
            ConversationHistoryScreen(
                onBack = { route = MainRoute.Settings },
                onResume = { conversationId ->
                    chatViewModel.loadConversation(conversationId)
                    route = MainRoute.Chat
                },
                // If the user deletes the conversation that's currently
                // showing in the chat surface, clear the chat so they
                // don't return to ghost messages for a row that no longer
                // exists in the DB. No-op for other ids.
                onDeleted = chatViewModel::onConversationDeleted,
            )
        }
        is MainRoute.ConversationMemory -> {
            BackHandler { route = MainRoute.Chat }
            ConversationMemoryListScreen(
                conversationId = current.conversationId,
                onBack = { route = MainRoute.Chat },
            )
        }
    }
}

/**
 * Debounce window between landing on Chat and kicking off the Gemma warm-up.
 * 300 ms catches Settings → Chat → Settings flips (warm-up is cancelled
 * before any work happens) without delaying intentional Chat entry
 * noticeably. Empirically the Chat screen's first frame composes in well
 * under 200 ms on Pixel 7, so the debounce is safe; bump to 500 ms if
 * Phase B's manual test shows the load attempt frequently kicks off during
 * Chat-screen draw work.
 */
private const val EAGER_WARMUP_DEBOUNCE_MS = 300L

internal sealed interface MainRoute {
    data object Chat : MainRoute
    data object Settings : MainRoute
    data object MemoryManagement : MainRoute
    data object ConversationHistory : MainRoute
    data object TodoManagement : MainRoute
    data object TimerManagement : MainRoute
    data object AlarmManagement : MainRoute
    data object SearchSources : MainRoute
    data class ConversationMemory(val conversationId: String) : MainRoute

    companion object {
        /**
         * `rememberSaveable` needs to round-trip the route through a Bundle on
         * config change, but Compose doesn't have a default saver for sealed
         * interfaces. We encode as a single string so a screen rotation
         * mid-conversation doesn't dump the user back to Chat.
         */
        val Saver: androidx.compose.runtime.saveable.Saver<MainRoute, String> =
            androidx.compose.runtime.saveable.Saver(
                save = {
                    when (it) {
                        Chat -> "chat"
                        Settings -> "settings"
                        MemoryManagement -> "mem"
                        ConversationHistory -> "history"
                        TodoManagement -> "todos"
                        TimerManagement -> "timers"
                        AlarmManagement -> "alarms"
                        SearchSources -> "search_sources"
                        is ConversationMemory -> "cmem:${it.conversationId}"
                    }
                },
                restore = { encoded ->
                    when {
                        encoded == "chat" -> Chat
                        encoded == "settings" -> Settings
                        encoded == "mem" -> MemoryManagement
                        encoded == "history" -> ConversationHistory
                        encoded == "todos" -> TodoManagement
                        encoded == "timers" -> TimerManagement
                        encoded == "alarms" -> AlarmManagement
                        encoded == "search_sources" -> SearchSources
                        encoded.startsWith("cmem:") -> ConversationMemory(encoded.substringAfter("cmem:"))
                        else -> Chat
                    }
                },
            )
    }
}
