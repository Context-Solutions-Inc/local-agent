package com.contextsolutions.localagent.ui.clock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import com.contextsolutions.localagent.clock.TimerEntry
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.ui.i18n.tr
import kotlinx.coroutines.delay

/**
 * Full-screen timers surface (PR #17). Replaces the prior
 * `ModalBottomSheet`-based `TimerSheet`. Mirrors `MyListScreen`'s
 * shape: top bar + LazyColumn + FAB → create-dialog.
 *
 * Each row shows a live-ticking remaining countdown driven by a single
 * screen-level 1 s `LaunchedEffect`, so per-row launches aren't needed.
 *
 * "+1 min" / "+5 min" extend `fireAtEpochMs` and re-arm AlarmManager via
 * `ClockViewModel.extendTimer`. Cancel removes the row + arm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerManagementScreen(
    onBack: () -> Unit,
    viewModel: ClockViewModel = koinViewModel(),
) {
    val timers by viewModel.timers.collectAsState()
    var creating by remember { mutableStateOf(false) }

    // Tick a local clock once a second so the per-row remaining-time labels
    // update without each row spinning up its own LaunchedEffect.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(StringKeys.CLOCK_UI_TIMERS_TITLE)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(StringKeys.COMMON_BACK))
                    }
                },
                actions = {
                    // Nudge left so the "+" right-aligns with the per-row cancel/trash
                    // icon, which sits inside the body's 16dp horizontal padding while
                    // TopAppBar actions inset only ~4dp.
                    IconButton(onClick = { creating = true }, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = tr(StringKeys.CLOCK_UI_CD_NEW_TIMER))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (timers.isEmpty()) {
                EmptyTimers()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(timers, key = { it.id }) { timer ->
                        TimerRow(
                            timer = timer,
                            nowMs = nowMs,
                            onExtend = { extraMs -> viewModel.extendTimer(timer.id, extraMs) },
                            onCancel = { viewModel.cancelTimer(timer.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (creating) {
        NewTimerDialog(
            onDismiss = { creating = false },
            onCreate = { ms, label ->
                viewModel.createTimer(ms, label)
                creating = false
            },
        )
    }
}

@Composable
private fun EmptyTimers() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tr(StringKeys.CLOCK_UI_TIMERS_EMPTY),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr(StringKeys.CLOCK_UI_TIMERS_EMPTY_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimerRow(
    timer: TimerEntry,
    nowMs: Long,
    onExtend: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    val remainingMs = (timer.fireAtEpochMs - nowMs).coerceAtLeast(0)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timer.label?.takeIf { it.isNotBlank() } ?: tr(StringKeys.CLOCK_UI_TIMER_DEFAULT_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = formatHms(remainingMs),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // Grey trash can (onSurfaceVariant) to match the top-bar "+" and the My
            // List row icons, replacing the prior text "Cancel" button.
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = tr(StringKeys.CLOCK_UI_CANCEL),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExtend(60_000L) }) { Text(tr(StringKeys.CLOCK_UI_EXTEND_1MIN)) }
            OutlinedButton(onClick = { onExtend(5 * 60_000L) }) { Text(tr(StringKeys.CLOCK_UI_EXTEND_5MIN)) }
        }
    }
}

@Composable
private fun NewTimerDialog(
    onDismiss: () -> Unit,
    onCreate: (Long, String?) -> Unit,
) {
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val totalMs = ((hours.toIntOrNull() ?: 0) * 3600L +
        (minutes.toIntOrNull() ?: 0) * 60L +
        (seconds.toIntOrNull() ?: 0)) * 1000L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(StringKeys.CLOCK_UI_NEW_TIMER)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DurationField(value = hours, onValueChange = { hours = it }, label = tr(StringKeys.CLOCK_UI_DURATION_H), modifier = Modifier.weight(1f))
                    DurationField(value = minutes, onValueChange = { minutes = it }, label = tr(StringKeys.CLOCK_UI_DURATION_M), modifier = Modifier.weight(1f))
                    DurationField(value = seconds, onValueChange = { seconds = it }, label = tr(StringKeys.CLOCK_UI_DURATION_S), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(tr(StringKeys.CLOCK_UI_LABEL_OPTIONAL)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = totalMs > 0,
                onClick = { onCreate(totalMs, label.takeIf { it.isNotBlank() }) },
            ) {
                Text(tr(StringKeys.CLOCK_UI_START))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr(StringKeys.CLOCK_UI_CANCEL)) }
        },
    )
}

@Composable
private fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            // Numeric-only, 0..99 for any single field (alarm/timer max is hours).
            val filtered = input.filter(Char::isDigit).take(2)
            onValueChange(filtered)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun formatHms(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
