package com.contextsolutions.mobileagent.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.contextsolutions.mobileagent.task.QueuedTask
import com.contextsolutions.mobileagent.task.TaskQueue
import com.contextsolutions.mobileagent.task.TaskStatus

/**
 * Minimal window content for Phase 7 — the live task-queue status (depth +
 * running task progress + recent rows), collected from [TaskQueue.tasks]. The
 * full chat / settings / enqueue UI lands in Phase 9; this proves the tray's
 * warm-model background queue is wired and observable.
 */
@Composable
fun QueueStatusScreen(taskQueue: TaskQueue, modelDownload: DesktopModelDownloadController) {
    val tasks by taskQueue.tasks.collectAsState()
    val modelStatus by modelDownload.status.collectAsState()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Mobile Agent", style = MaterialTheme.typography.headlineSmall)

                Text("Model: ${modelLine(modelStatus)}")
                HorizontalDivider()

                Text("Task queue", style = MaterialTheme.typography.titleMedium)
                val running = tasks.firstOrNull { it.status == TaskStatus.RUNNING }
                val queued = tasks.count { it.status == TaskStatus.QUEUED }
                Text(summaryLine(queued, running))

                HorizontalDivider()

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(tasks) { task ->
                        Text(taskLine(task), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun summaryLine(queued: Int, running: QueuedTask?): String =
    if (running != null) {
        "Queued: $queued   ·   Running: ${preview(running.prompt)} (${(running.progress * 100).toInt()}%)"
    } else {
        "Queued: $queued   ·   Idle"
    }

private fun taskLine(task: QueuedTask): String {
    val tail = when (task.status) {
        TaskStatus.SUCCEEDED -> task.result?.let { " → ${preview(it)}" } ?: ""
        TaskStatus.FAILED -> task.error?.let { " → $it" } ?: ""
        else -> ""
    }
    return "[${task.status}] ${preview(task.prompt)}$tail"
}

private fun modelLine(status: ModelDownloadStatus): String = when (status) {
    is ModelDownloadStatus.Idle -> "checking…"
    is ModelDownloadStatus.Present -> "ready"
    is ModelDownloadStatus.NotConfigured -> "not configured (set the model spec)"
    is ModelDownloadStatus.Downloading -> "downloading ${(status.fraction * 100).toInt()}%"
    is ModelDownloadStatus.Failed -> "download failed — ${status.message}"
}

private fun preview(text: String, max: Int = 60): String =
    if (text.length <= max) text else text.take(max).trimEnd() + "…"
