package com.contextsolutions.mobileagent.app.ui.download

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.app.service.DownloadErrorType
import com.contextsolutions.mobileagent.app.service.DownloadState

/**
 * UI for the model-download flow (PRD §3.5, §6.2). Minimal but functional —
 * exercises every state transition the [com.contextsolutions.mobileagent.app.service.ModelDownloadController]
 * produces. Real onboarding polish (illustrations, copy, accessibility) is WS-11.
 *
 * Network-policy buttons (PRD §3.5: explicit confirmation on metered) are split
 * into two for now: "WiFi only" enqueues with NetworkType.UNMETERED, "Allow
 * cellular" with NetworkType.CONNECTED. A real metered-detection dialog can land
 * later — this two-button form is enough to validate the underlying control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(viewModel: DownloadViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val spec = viewModel.spec()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up the on-device model") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
        ) {
            Text(
                text = "Mobile Agent runs Gemma 4 entirely on your device. The first " +
                    "step is a one-time download of the model weights.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            Text("File: ${spec.filename}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (spec.sizeBytes > 0L) {
                Text(
                    "Size: ${Formatter.formatShortFileSize(context, spec.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "URL: ${spec.downloadUrl}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(16.dp))

            DownloadStateBlock(state, context)
            Spacer(Modifier.height(16.dp))

            DownloadActions(
                state = state,
                onStartUnmetered = { viewModel.start(allowMetered = false) },
                onStartMetered = { viewModel.start(allowMetered = true) },
                onPause = { viewModel.pause() },
                onRetryUnmetered = { viewModel.retry(allowMetered = false) },
                onRetryMetered = { viewModel.retry(allowMetered = true) },
            )

            if (!spec.isConfigured) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "⚠ Model spec is incomplete. Set MODEL_SHA256 + " +
                        "MODEL_SIZE_BYTES in secrets.properties. The HuggingFace " +
                        "auth token comes from your onboarding entry / Settings " +
                        "(or the secrets.properties dev fallback on debug builds).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DownloadStateBlock(state: DownloadState, context: android.content.Context) {
    when (state) {
        is DownloadState.Idle -> Text("Ready to download.", style = MaterialTheme.typography.bodyMedium)
        // ENQUEUED can mean either "waiting for the network constraint" or
        // "waiting through WorkManager exponential backoff after a failure".
        // The public WorkInfo API doesn't distinguish. Keep the copy generic
        // rather than misleading the user about which one it is.
        is DownloadState.Queued -> Text(
            "Queued — waiting for network or retry backoff…",
            style = MaterialTheme.typography.bodyMedium,
        )
        is DownloadState.Running -> {
            val total = state.bytesTotal
            val downloaded = state.bytesDownloaded
            if (total > 0L) {
                val pct = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${(pct * 100).toInt()}% — " +
                        "${Formatter.formatShortFileSize(context, downloaded)} of " +
                        Formatter.formatShortFileSize(context, total),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Starting…", style = MaterialTheme.typography.bodySmall)
            }
        }
        is DownloadState.Completed -> Text(
            "✓ Model is ready.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is DownloadState.Failed -> {
            Text(
                "Download failed: ${friendlyError(state.type, state.message)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(state.message, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun DownloadActions(
    state: DownloadState,
    onStartUnmetered: () -> Unit,
    onStartMetered: () -> Unit,
    onPause: () -> Unit,
    onRetryUnmetered: () -> Unit,
    onRetryMetered: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            // Idle covers both "first time" and "post-pause" — there's no
            // separate Paused state because WorkManager moves cancelled work to
            // CANCELLED, which we map to Idle. The Worker picks up the partial
            // file automatically on the next enqueue, so the action is the same
            // regardless of how we got here. Neutral "Download" labels rather
            // than splitting into Download/Resume.
            is DownloadState.Idle -> {
                Button(onClick = onStartUnmetered) { Text("Download (WiFi only)") }
                OutlinedButton(onClick = onStartMetered) { Text("Allow cellular") }
            }
            is DownloadState.Queued, is DownloadState.Running -> {
                OutlinedButton(onClick = onPause) { Text("Pause") }
            }
            // No actions while completed — MainScreen routes us away to chat.
            is DownloadState.Completed -> Unit
            is DownloadState.Failed -> {
                Button(onClick = onRetryUnmetered) { Text("Retry (WiFi)") }
                if (state.type == DownloadErrorType.NETWORK ||
                    state.type == DownloadErrorType.HTTP_SERVER ||
                    state.type == null
                ) {
                    OutlinedButton(onClick = onRetryMetered) { Text("Retry (allow cellular)") }
                }
            }
        }
    }
}

private fun friendlyError(type: DownloadErrorType?, raw: String): String = when (type) {
    DownloadErrorType.NETWORK -> "Network error — check your connection."
    DownloadErrorType.HTTP_CLIENT -> "Server rejected the request. (Is your HuggingFace token set in Settings?)"
    DownloadErrorType.HTTP_SERVER -> "Server problem — please try again later."
    DownloadErrorType.STORAGE -> "Not enough free storage."
    DownloadErrorType.CHECKSUM -> "The file didn't match its expected checksum and was discarded."
    DownloadErrorType.MISCONFIGURED -> "Model spec missing — see settings."
    null -> raw
}
