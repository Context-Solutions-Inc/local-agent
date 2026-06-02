package com.contextsolutions.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Phase-1 shell screen for the shared Compose Multiplatform UI module
 * (docs/DESKTOP_PORT_PLAN.md). Exists to prove the :ui module compiles for both
 * Android and desktop and renders in the :desktopApp window. Phase 3 replaces this
 * with the migrated chat/settings/onboarding screens.
 */
@Composable
fun PlaceholderScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Mobile Agent",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Desktop shell — shared Compose Multiplatform UI (Phase 1)",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
