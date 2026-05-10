package com.contextsolutions.mobileagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.app.ui.chat.ChatScreen
import com.contextsolutions.mobileagent.app.ui.download.DownloadScreen
import com.contextsolutions.mobileagent.app.ui.memory.ConversationMemoryListScreen
import com.contextsolutions.mobileagent.app.ui.memory.MemoryScreen
import com.contextsolutions.mobileagent.app.ui.settings.SettingsScreen

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
    onOpenSpike: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val modelPresent by viewModel.modelPresent.collectAsState()
    var route by rememberSaveable(stateSaver = MainRoute.Saver) {
        mutableStateOf<MainRoute>(MainRoute.Chat)
    }

    if (!modelPresent) {
        DownloadScreen()
        return
    }

    when (val current = route) {
        MainRoute.Chat -> ChatScreen(
            onOpenSpike = onOpenSpike,
            onOpenSettings = { route = MainRoute.Settings },
            onOpenConversationMemory = { conversationId ->
                route = MainRoute.ConversationMemory(conversationId)
            },
        )
        MainRoute.Settings -> {
            BackHandler { route = MainRoute.Chat }
            SettingsScreen(
                onBack = { route = MainRoute.Chat },
                onOpenMemoryManagement = { route = MainRoute.MemoryManagement },
            )
        }
        MainRoute.MemoryManagement -> {
            BackHandler { route = MainRoute.Settings }
            MemoryScreen(onBack = { route = MainRoute.Settings })
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

private sealed interface MainRoute {
    data object Chat : MainRoute
    data object Settings : MainRoute
    data object MemoryManagement : MainRoute
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
                        is ConversationMemory -> "cmem:${it.conversationId}"
                    }
                },
                restore = { encoded ->
                    when {
                        encoded == "chat" -> Chat
                        encoded == "settings" -> Settings
                        encoded == "mem" -> MemoryManagement
                        encoded.startsWith("cmem:") -> ConversationMemory(encoded.substringAfter("cmem:"))
                        else -> Chat
                    }
                },
            )
    }
}
