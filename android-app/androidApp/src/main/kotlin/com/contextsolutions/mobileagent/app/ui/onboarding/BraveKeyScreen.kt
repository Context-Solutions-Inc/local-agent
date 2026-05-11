package com.contextsolutions.mobileagent.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Onboarding screen 2 — Brave Search API key entry (PRD §6.1).
 *
 * Skippable; the assistant still works without web search, just with
 * the disclaimer that it can't fetch current data. The "Get a key" link
 * opens Brave's API console in the user's browser.
 *
 * The key is masked via [PasswordVisualTransformation] and stored in
 * EncryptedSharedPreferences immediately on Save (via
 * [OnboardingViewModel.saveBraveKey] → SecureStorage). The composable
 * holds nothing beyond the in-flight `keyInput`.
 */
@Composable
fun BraveKeyScreen(
    onSave: (String) -> Unit,
    onSkip: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Add a Brave Search key",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Mobile Agent uses Brave Search for questions about " +
                    "current events, scores, prices, and other time-sensitive " +
                    "things. The free tier is enough for personal use.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Brave Search API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(BRAVE_API_URL)),
                        )
                    }
                },
            ) {
                Text("Get a key at api.search.brave.com")
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onSave(keyInput) },
                enabled = keyInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save and continue")
            }

            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Skip — I'll add it later in Settings")
            }

            Text(
                text = "Without a key, the assistant works offline using only " +
                    "its on-device knowledge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private const val BRAVE_API_URL = "https://api.search.brave.com/"
