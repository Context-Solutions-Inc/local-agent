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
 * Onboarding screen — HuggingFace API token entry.
 *
 * The Gemma 4 weights repository is gated on HuggingFace, so authenticating the
 * model download requires a read-scoped HF access token from the user's HF
 * account. The flow mirrors [BraveKeyScreen]: masked entry, "Get a token" link
 * to the HF token-management page, save-and-continue or skip.
 *
 * Skipping is allowed for users who already have the weights sideloaded (e.g.,
 * via `adb push` for debug builds) — the download screen will surface a clear
 * 401/403 error if a real download is then attempted without a token.
 */
@Composable
fun HfAuthTokenScreen(
    onSave: (String) -> Unit,
    onSkip: () -> Unit,
) {
    var tokenInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Add a HuggingFace token",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Mobile Agent downloads the Gemma 4 model weights from a " +
                    "gated HuggingFace repository. A read-scoped token from your " +
                    "HuggingFace account authenticates the one-time download.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("HuggingFace access token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(HF_TOKENS_URL)),
                        )
                    }
                },
            ) {
                Text("Get a token at huggingface.co/settings/tokens")
            }

            Text(
                text = "You also need to accept the Gemma license on the model " +
                    "card page before the download will succeed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onSave(tokenInput) },
                enabled = tokenInput.isNotBlank(),
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
                text = "Without a token, the model download will fail unless the " +
                    "weights have been sideloaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private const val HF_TOKENS_URL = "https://huggingface.co/settings/tokens"
