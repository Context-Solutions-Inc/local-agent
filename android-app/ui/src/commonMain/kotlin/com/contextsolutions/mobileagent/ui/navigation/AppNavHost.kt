package com.contextsolutions.mobileagent.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.contextsolutions.mobileagent.ui.chat.ChatScreen
import com.contextsolutions.mobileagent.ui.chat.ChatViewModel
import com.contextsolutions.mobileagent.ui.clock.AlarmManagementScreen
import com.contextsolutions.mobileagent.ui.clock.TimerManagementScreen
import com.contextsolutions.mobileagent.ui.history.ConversationHistoryScreen
import com.contextsolutions.mobileagent.ui.memory.ConversationMemoryListScreen
import com.contextsolutions.mobileagent.ui.memory.MemoryScreen
import com.contextsolutions.mobileagent.ui.onboarding.OnboardingHost
import com.contextsolutions.mobileagent.ui.settings.SearchSourcesScreen
import com.contextsolutions.mobileagent.ui.settings.SettingsScreen
import com.contextsolutions.mobileagent.ui.todo.TodoManagementScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Shared data-driven navigation host (docs/DESKTOP_PORT_PLAN.md Phase 9 inc 8d).
 * All screens now live in `:ui`, so the `when (route)` dispatch that used to sit
 * in `:androidApp`'s `MainScreen` moves here and is shared by both shells.
 *
 * Gating is supplied by each shell (Android reads `MainViewModel` /
 * `ModelInventory`; desktop supplies its own), so this stays free of either
 * platform's lifecycle:
 *  - [onboardingComplete] false → [OnboardingHost] (first-run flow).
 *  - [modelPresent] false → [downloadContent] (Android's WorkManager
 *    `DownloadScreen`; desktop downloads via the tray so it passes a placeholder).
 *  - otherwise the Chat-rooted route graph.
 *
 * [chatWarmUp] is rendered inside the Chat branch so a shell can drive an
 * aux-engine warm-up keyed on Chat visibility (Android's `LifecycleResumeEffect`,
 * invariant #22); desktop passes an empty slot (the warm model loads lazily).
 */
@Composable
fun AppNavHost(
    onboardingComplete: Boolean,
    modelPresent: Boolean,
    downloadContent: @Composable () -> Unit,
    chatWarmUp: @Composable () -> Unit = {},
    chatViewModel: ChatViewModel = koinViewModel(),
) {
    var route by rememberSaveable(stateSaver = MainRoute.Saver) {
        mutableStateOf<MainRoute>(MainRoute.Chat)
    }

    if (!onboardingComplete) {
        OnboardingHost(onComplete = { /* state flips automatically */ })
        return
    }

    if (!modelPresent) {
        downloadContent()
        return
    }

    when (val current = route) {
        MainRoute.Chat -> {
            chatWarmUp()
            ChatScreen(
                onOpenSettings = { route = MainRoute.Settings },
                onOpenConversationMemory = { conversationId ->
                    route = MainRoute.ConversationMemory(conversationId)
                },
                onOpenTodos = { route = MainRoute.TodoManagement },
                onOpenTimers = { route = MainRoute.TimerManagement },
                onOpenAlarms = { route = MainRoute.AlarmManagement },
                viewModel = chatViewModel,
            )
        }
        MainRoute.TodoManagement -> {
            PlatformBackHandler { route = MainRoute.Chat }
            TodoManagementScreen(onBack = { route = MainRoute.Chat })
        }
        MainRoute.TimerManagement -> {
            PlatformBackHandler { route = MainRoute.Chat }
            TimerManagementScreen(onBack = { route = MainRoute.Chat })
        }
        MainRoute.AlarmManagement -> {
            PlatformBackHandler { route = MainRoute.Chat }
            AlarmManagementScreen(onBack = { route = MainRoute.Chat })
        }
        MainRoute.Settings -> {
            PlatformBackHandler { route = MainRoute.Chat }
            SettingsScreen(
                onBack = { route = MainRoute.Chat },
                onOpenMemoryManagement = { route = MainRoute.MemoryManagement },
                onOpenConversationHistory = { route = MainRoute.ConversationHistory },
                onOpenSearchSources = { route = MainRoute.SearchSources },
            )
        }
        MainRoute.SearchSources -> {
            PlatformBackHandler { route = MainRoute.Settings }
            SearchSourcesScreen(onBack = { route = MainRoute.Settings })
        }
        MainRoute.MemoryManagement -> {
            PlatformBackHandler { route = MainRoute.Settings }
            MemoryScreen(onBack = { route = MainRoute.Settings })
        }
        MainRoute.ConversationHistory -> {
            PlatformBackHandler { route = MainRoute.Settings }
            ConversationHistoryScreen(
                onBack = { route = MainRoute.Settings },
                onResume = { conversationId ->
                    chatViewModel.loadConversation(conversationId)
                    route = MainRoute.Chat
                },
                // If the user deletes the conversation currently showing in the
                // chat surface, clear the chat so they don't return to ghost
                // messages for a row that no longer exists. No-op for other ids.
                onDeleted = chatViewModel::onConversationDeleted,
            )
        }
        is MainRoute.ConversationMemory -> {
            PlatformBackHandler { route = MainRoute.Chat }
            ConversationMemoryListScreen(
                conversationId = current.conversationId,
                onBack = { route = MainRoute.Chat },
            )
        }
    }
}
