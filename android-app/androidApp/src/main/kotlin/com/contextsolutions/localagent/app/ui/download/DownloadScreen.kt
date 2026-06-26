package com.contextsolutions.localagent.app.ui.download

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import org.koin.compose.viewmodel.koinViewModel
import com.contextsolutions.localagent.app.service.DownloadErrorType
import com.contextsolutions.localagent.app.service.DownloadState
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.ui.i18n.tr

/**
 * UI for the model-download flow (PRD §3.5, §6.2). Minimal but functional —
 * exercises every state transition the [com.contextsolutions.localagent.app.service.ModelDownloadController]
 * produces. Real onboarding polish (illustrations, copy, accessibility) is WS-11.
 *
 * All user-facing copy comes from the shared i18n catalog (`StringKeys.DOWNLOAD_*`)
 * via [tr], so this one-time onboarding screen translates like the rest of the app
 * (the host wraps content in `LocalStrings`).
 *
 * Network-policy buttons (PRD §3.5: explicit confirmation on metered) are split
 * into two for now: "WiFi only" enqueues with NetworkType.UNMETERED, "Allow
 * cellular" with NetworkType.CONNECTED. A real metered-detection dialog can land
 * later — this two-button form is enough to validate the underlying control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(viewModel: DownloadViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val spec = viewModel.spec()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr(StringKeys.DOWNLOAD_TITLE)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
        ) {
            Text(
                text = tr(StringKeys.DOWNLOAD_INTRO),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            Text(tr(StringKeys.DOWNLOAD_MODELS_HEADER), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            for (model in viewModel.specs()) {
                val size = if (model.sizeBytes > 0L) {
                    " — ${Formatter.formatShortFileSize(context, model.sizeBytes)}"
                } else {
                    ""
                }
                Text(
                    "• ${model.filename}$size",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            val totalBytes = viewModel.totalDownloadBytes()
            if (totalBytes > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(StringKeys.DOWNLOAD_TOTAL, Formatter.formatShortFileSize(context, totalBytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
                    text = tr(StringKeys.DOWNLOAD_SPEC_INCOMPLETE),
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
        is DownloadState.Idle -> Text(tr(StringKeys.DOWNLOAD_STATE_IDLE), style = MaterialTheme.typography.bodyMedium)
        // ENQUEUED can mean either "waiting for the network constraint" or
        // "waiting through WorkManager exponential backoff after a failure".
        // The public WorkInfo API doesn't distinguish. Keep the copy generic
        // rather than misleading the user about which one it is.
        is DownloadState.Queued -> Text(
            tr(StringKeys.DOWNLOAD_STATE_QUEUED),
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
                    text = tr(
                        StringKeys.DOWNLOAD_PROGRESS,
                        (pct * 100).toInt(),
                        Formatter.formatShortFileSize(context, downloaded),
                        Formatter.formatShortFileSize(context, total),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(tr(StringKeys.DOWNLOAD_STATE_STARTING), style = MaterialTheme.typography.bodySmall)
            }
        }
        is DownloadState.Completed -> Text(
            tr(StringKeys.DOWNLOAD_STATE_COMPLETED),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is DownloadState.Failed -> {
            Text(
                tr(StringKeys.DOWNLOAD_FAILED, friendlyError(state.type, state.message)),
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
                Button(onClick = onStartUnmetered) { Text(tr(StringKeys.DOWNLOAD_ACTION_WIFI)) }
                OutlinedButton(onClick = onStartMetered) { Text(tr(StringKeys.DOWNLOAD_ACTION_CELLULAR)) }
            }
            is DownloadState.Queued, is DownloadState.Running -> {
                OutlinedButton(onClick = onPause) { Text(tr(StringKeys.DOWNLOAD_ACTION_PAUSE)) }
            }
            // No actions while completed — MainScreen routes us away to chat.
            is DownloadState.Completed -> Unit
            is DownloadState.Failed -> {
                Button(onClick = onRetryUnmetered) { Text(tr(StringKeys.DOWNLOAD_ACTION_RETRY_WIFI)) }
                if (state.type == DownloadErrorType.NETWORK ||
                    state.type == DownloadErrorType.HTTP_SERVER ||
                    state.type == null
                ) {
                    OutlinedButton(onClick = onRetryMetered) { Text(tr(StringKeys.DOWNLOAD_ACTION_RETRY_CELLULAR)) }
                }
            }
        }
    }
}

@Composable
private fun friendlyError(type: DownloadErrorType?, raw: String): String = when (type) {
    DownloadErrorType.NETWORK -> tr(StringKeys.DOWNLOAD_ERROR_NETWORK)
    DownloadErrorType.HTTP_CLIENT -> tr(StringKeys.DOWNLOAD_ERROR_HTTP_CLIENT)
    DownloadErrorType.HTTP_SERVER -> tr(StringKeys.DOWNLOAD_ERROR_HTTP_SERVER)
    DownloadErrorType.STORAGE -> tr(StringKeys.DOWNLOAD_ERROR_STORAGE)
    DownloadErrorType.CHECKSUM -> tr(StringKeys.DOWNLOAD_ERROR_CHECKSUM)
    DownloadErrorType.MISCONFIGURED -> tr(StringKeys.DOWNLOAD_ERROR_MISCONFIGURED)
    null -> raw
}
