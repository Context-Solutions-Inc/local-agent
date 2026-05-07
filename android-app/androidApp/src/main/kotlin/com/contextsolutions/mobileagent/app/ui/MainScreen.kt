package com.contextsolutions.mobileagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.app.ui.chat.ChatScreen
import com.contextsolutions.mobileagent.app.ui.download.DownloadScreen
import com.contextsolutions.mobileagent.app.ui.settings.SettingsScreen

/**
 * Top-level Composable hosted by [com.contextsolutions.mobileagent.app.MainActivity].
 *
 * Three states, all data-driven (no Compose Navigation graph):
 *  - Model not present → [DownloadScreen]
 *  - Model present, settings closed → [ChatScreen]
 *  - Model present, settings open → [SettingsScreen]
 */
@Composable
fun MainScreen(
    onOpenSpike: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val modelPresent by viewModel.modelPresent.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (!modelPresent) {
        DownloadScreen()
        return
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(onBack = { showSettings = false })
    } else {
        ChatScreen(
            onOpenSpike = onOpenSpike,
            onOpenSettings = { showSettings = true },
        )
    }
}
