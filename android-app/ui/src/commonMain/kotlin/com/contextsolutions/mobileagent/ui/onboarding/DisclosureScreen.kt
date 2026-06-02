package com.contextsolutions.mobileagent.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Onboarding screen 1 — on-device disclosure (PRD §6.1).
 *
 * Explains the privacy model in plain language; user must acknowledge
 * via checkbox before continuing. The checkbox is a deliberate friction
 * point — we want the user to read the text, not blindly tap Continue.
 */
@Composable
fun DisclosureScreen(
    onContinue: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Your assistant. On your device.",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Mobile Agent runs the language model entirely on your phone. " +
                    "Your conversations, the things it remembers about you, and the " +
                    "memories it creates are stored locally and never leave the device.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "What does leave the device:",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "•  Web search queries — sent to Brave Search API only when " +
                    "the assistant decides a search is needed. Just the query, " +
                    "never your other messages or memories.\n\n" +
                    "•  Optional anonymous telemetry — off by default. You decide " +
                    "on the next screen.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it },
                )
                Text(
                    text = "I understand.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onContinue,
                enabled = acknowledged,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue")
            }
        }
    }
}
