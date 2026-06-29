package com.contextsolutions.localagent.desktop.app

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.ui.i18n.tr
import kotlinx.coroutines.flow.StateFlow

/** One model row on the desktop download gate: display name, known size, live status. */
class DesktopDownloadItem(
    val name: String,
    val totalBytes: Long,
    val status: StateFlow<ModelDownloadStatus>,
)

/**
 * Desktop first-run model-download gate (PR #38) — the desktop analogue of
 * Android's `DownloadScreen`. Supplied into the shared [AppNavHost] `downloadContent`
 * slot from `Main.kt`; shown until every required model ([items]) reaches
 * [ModelDownloadStatus.Present] (or [NotConfigured], which can't be fetched and so
 * doesn't block — see `Main.kt`).
 *
 * Unlike mobile there's no WiFi/cellular choice (desktop is unmetered and the
 * downloads auto-start via the existing `ensure*` calls); this screen is display +
 * a single [onRetry] when any source has failed. Copy comes from the shared
 * `StringKeys.DOWNLOAD_*` catalog via [tr] (the window wraps content in `LocalStrings`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopDownloadScreen(
    items: List<DesktopDownloadItem>,
    onRetry: () -> Unit,
) {
    val statuses = items.map { it.status.collectAsState() }
    val scrollState = rememberScrollState()

    // Byte-weighted aggregate across all sources (mirrors DesktopChatSessionController.bindDownloads).
    val totalAll = items.sumOf { it.totalBytes }
    val doneAll = items.indices.sumOf { i ->
        when (val s = statuses[i].value) {
            is ModelDownloadStatus.Downloading -> s.downloadedBytes
            is ModelDownloadStatus.Present -> items[i].totalBytes
            else -> 0L
        }
    }
    val fraction = if (totalAll > 0L) (doneAll.toFloat() / totalAll).coerceIn(0f, 1f) else 0f
    val anyFailed = statuses.any { it.value is ModelDownloadStatus.Failed }

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
            Text(tr(StringKeys.DOWNLOAD_INTRO), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            Text(tr(StringKeys.DOWNLOAD_MODELS_HEADER), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            for (i in items.indices) {
                ModelRow(items[i], statuses[i].value)
            }
            if (totalAll > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(StringKeys.DOWNLOAD_TOTAL, humanBytes(totalAll)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))

            // Aggregate progress / terminal state.
            if (anyFailed) {
                Text(
                    tr(StringKeys.DOWNLOAD_FAILED, ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry) { Text(tr(StringKeys.DOWNLOAD_ACTION_RETRY)) }
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(
                        StringKeys.DOWNLOAD_PROGRESS,
                        (fraction * 100).toInt(),
                        humanBytes(doneAll),
                        humanBytes(totalAll),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ModelRow(item: DesktopDownloadItem, status: ModelDownloadStatus) {
    val size = if (item.totalBytes > 0L) " — ${humanBytes(item.totalBytes)}" else ""
    val suffix = when (status) {
        is ModelDownloadStatus.Present -> "  ✓"
        is ModelDownloadStatus.Downloading -> "  ${(status.fraction * 100).toInt()}%"
        is ModelDownloadStatus.Failed -> "  ✗"
        else -> ""
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "• ${item.name}$size$suffix",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Compact human-readable size (desktop has no `android.text.format.Formatter`). */
private fun humanBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${bytes} B" else "${(value * 10).toLong() / 10.0} ${units[unit]}"
}
