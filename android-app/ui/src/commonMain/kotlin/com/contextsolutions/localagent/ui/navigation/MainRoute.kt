package com.contextsolutions.localagent.ui.navigation

import androidx.compose.runtime.saveable.Saver

/**
 * The app's top-level navigation destinations.
 *
 * Deliberately a hand-rolled sealed type + a `when` host rather than
 * Compose Navigation (docs/DESKTOP_PORT_PLAN.md Phase 3): the route set is
 * small and fully data-driven, and a plain sealed interface moves into shared
 * `:ui` commonMain with no extra multiplatform navigation dependency. The
 * host that switches on these routes lives beside the screens (moved into
 * `:ui` with the Chat screen in the final Phase-9 increment); until then the
 * `:androidApp` `MainScreen` consumes this type.
 */
sealed interface MainRoute {
    data object Chat : MainRoute
    data object Settings : MainRoute
    data object MemoryManagement : MainRoute
    data object ConversationHistory : MainRoute
    data object TodoManagement : MainRoute
    data object TimerManagement : MainRoute
    data object AlarmManagement : MainRoute
    data object SearchSources : MainRoute
    data object JobManagement : MainRoute
    data class ConversationMemory(val conversationId: String) : MainRoute

    companion object {
        /**
         * `rememberSaveable` needs to round-trip the route through a Bundle on
         * config change, but Compose doesn't have a default saver for sealed
         * interfaces. We encode as a single string so a screen rotation
         * mid-conversation doesn't dump the user back to Chat.
         */
        val Saver: Saver<MainRoute, String> = Saver(
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
                    JobManagement -> "jobs"
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
                    encoded == "jobs" -> JobManagement
                    encoded.startsWith("cmem:") -> ConversationMemory(encoded.substringAfter("cmem:"))
                    else -> Chat
                }
            },
        )
    }
}
