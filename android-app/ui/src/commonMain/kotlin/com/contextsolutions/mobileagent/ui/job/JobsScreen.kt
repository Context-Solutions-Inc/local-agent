package com.contextsolutions.mobileagent.ui.job

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.contextsolutions.mobileagent.clock.AlarmDay
import com.contextsolutions.mobileagent.inference.DesktopLinkStatus
import com.contextsolutions.mobileagent.job.Job
import com.contextsolutions.mobileagent.job.JobRunStatus
import com.contextsolutions.mobileagent.job.JobScheduleType
import com.contextsolutions.mobileagent.ui.platform.isDesktopPlatform
import com.contextsolutions.mobileagent.ui.util.formatRelativeTime
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/**
 * Jobs surface (PR #70). Opens from the always-on chat top-bar icon on BOTH
 * platforms. Mobile shows the last-synced list read-only (with a "Synced Nm ago •
 * Offline" header) and pause/resume enabled only when the desktop link is UP;
 * the desktop additionally surfaces create / edit / delete / run-now (the §2
 * trust boundary — jobs are only ever defined on the locally trusted desktop).
 *
 * A job's last-run conversation opens in Chat via [onOpenConversation], reusing
 * the exact resume path conversation history uses (the run conversation itself
 * synced via the normal conversation path).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    onBack: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
    viewModel: JobsViewModel = koinViewModel(),
) {
    val jobs by viewModel.jobs.collectAsState()
    val lastSyncedAtMs by viewModel.lastSyncedAtMs.collectAsState()
    val linkStatus by viewModel.linkStatus.collectAsState()

    // PR #85 — viewing the Jobs screen marks completed runs as seen (clears the
    // chat-header badge). Re-fires on list changes so a run that finishes while
    // the screen is open is acknowledged too. No-op on desktop.
    LaunchedEffect(jobs) { viewModel.markSeen() }
    val isAdmin = viewModel.isAdmin
    // The desktop (admin) always controls its own jobs; mobile needs the link UP
    // to push a pause/resume.
    val canControl = isAdmin || linkStatus == DesktopLinkStatus.UP

    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Job?>(null) }
    var deleting by remember { mutableStateOf<Job?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jobs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (isAdmin) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add job")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SyncHeader(lastSyncedAtMs = lastSyncedAtMs, linkStatus = linkStatus, isAdmin = isAdmin)
            Spacer(Modifier.height(8.dp))
            if (jobs.isEmpty()) {
                EmptyState(isAdmin)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(jobs, key = { it.id }) { job ->
                        JobRow(
                            job = job,
                            canControl = canControl,
                            isAdmin = isAdmin,
                            onTogglePaused = { paused -> viewModel.setPaused(job.id, paused) },
                            onOpenConversation = { job.lastRunConversationId?.let(onOpenConversation) },
                            onRunNow = { viewModel.runNow(job.id) },
                            onEdit = { editing = job },
                            onDelete = { deleting = job },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (creating) {
        JobFormDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, command, prompt, workingDir, scheduleType, cron, fireAt ->
                viewModel.create(name, command, prompt, workingDir, scheduleType, cron, fireAt)
                creating = false
            },
        )
    }

    editing?.let { current ->
        JobFormDialog(
            initial = current,
            onDismiss = { editing = null },
            onSave = { name, command, prompt, workingDir, scheduleType, cron, fireAt ->
                viewModel.update(current.id, name, command, prompt, workingDir, scheduleType, cron, fireAt)
                editing = null
            },
        )
    }

    deleting?.let { current ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete job?") },
            text = { Text("\"${current.name}\" will be removed and will no longer run.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(current.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SyncHeader(lastSyncedAtMs: Long?, linkStatus: DesktopLinkStatus, isAdmin: Boolean) {
    // The desktop is authoritative and runs jobs locally, so its "sync" line is
    // not meaningful — only the mobile remote-view shows freshness.
    if (isAdmin) return
    val now = Clock.System.now().toEpochMilliseconds()
    val synced = lastSyncedAtMs?.let { "Synced ${formatRelativeTime(it, now)}" } ?: "Never synced"
    val (dot, label) = when (linkStatus) {
        DesktopLinkStatus.UP -> Color(0xFF43A047) to "Online"
        DesktopLinkStatus.DOWN -> Color(0xFFE53935) to "Offline"
        DesktopLinkStatus.DISABLED -> Color(0xFFFFA000) to "Not linked"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(synced, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Icon(Icons.Filled.Circle, contentDescription = null, tint = dot, modifier = Modifier.size(10.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(isAdmin: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No jobs yet.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (isAdmin) "Tap + to schedule a command." else "Jobs are created on the desktop agent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JobRow(
    job: Job,
    canControl: Boolean,
    isAdmin: Boolean,
    onTogglePaused: (Boolean) -> Unit,
    onOpenConversation: () -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val now = Clock.System.now().toEpochMilliseconds()
    // A recurring job always has future runs; a one-shot is "pending" only while its
    // instant is still in the future. Keyed on fireAt (NOT lastRunStatus) so editing
    // a completed one-shot to a new future time makes it pending again.
    val hasFutureRuns = job.deletedAtEpochMs == null && when (job.scheduleType) {
        JobScheduleType.CRON -> true
        JobScheduleType.ONE_SHOT -> job.fireAtEpochMs?.let { it > now } ?: false
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(job.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = scheduleLabel(job),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val last = job.lastRunStatus
            if (last == JobRunStatus.RUNNING) {
                // Working indicator: a spinner while the run is in flight. On
                // completion the row switches to the result text below (PR #84).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = JobRunStatus.RUNNING.color(),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Running…",
                        style = MaterialTheme.typography.bodySmall,
                        color = JobRunStatus.RUNNING.color(),
                    )
                }
            } else if (last != null) {
                Text(
                    text = buildString {
                        append(last.label())
                        job.lastRunAtEpochMs?.let { append(" · ${formatRelativeTime(it, now)}") }
                        job.lastRunSummary?.takeIf { it.isNotBlank() }?.let { append(" · ${it.replace('\n', ' ')}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = last.color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (job.lastRunConversationId != null) {
                TextButton(onClick = onOpenConversation, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("View conversation", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        // Run-now is available wherever the job is controllable: desktop (admin)
        // always, and mobile when the link is UP — the phone sends a RUN_JOB command
        // to the desktop, which actually executes (PR #84). Disabled on a deleted job
        // and while a run is already in flight. Edit/Delete stay desktop-only (a peer
        // can't mutate a job definition).
        if (canControl) {
            IconButton(
                onClick = onRunNow,
                enabled = job.deletedAtEpochMs == null && job.lastRunStatus != JobRunStatus.RUNNING,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Run now")
            }
        }
        if (isAdmin) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
        // Pause toggle: enabled only while controllable AND there are future runs
        // (a completed one-shot has nothing to pause). Mobile also needs the link UP.
        Switch(
            checked = !job.paused,
            onCheckedChange = { resumed -> onTogglePaused(!resumed) },
            enabled = canControl && hasFutureRuns,
        )
    }
}

private fun scheduleLabel(job: Job): String = when (job.scheduleType) {
    JobScheduleType.CRON -> job.cronExpression?.let { expr ->
        parseJobCron(expr)?.let { (h, m, days) ->
            val time = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
            if (days.isEmpty()) "Daily at $time" else daysShortLabel(days) + " at $time"
        } ?: "cron: $expr"
    } ?: "Repeat"
    JobScheduleType.ONE_SHOT -> job.fireAtEpochMs?.let {
        "Once on " + Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).let { dt ->
            "${dt.date} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
        }
    } ?: "Once"
}

private fun daysShortLabel(days: Set<AlarmDay>): String {
    val order = listOf(
        AlarmDay.SUNDAY, AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
        AlarmDay.THURSDAY, AlarmDay.FRIDAY, AlarmDay.SATURDAY,
    )
    return order.filter { it in days }.joinToString(", ") {
        when (it) {
            AlarmDay.SUNDAY -> "Sun"; AlarmDay.MONDAY -> "Mon"; AlarmDay.TUESDAY -> "Tue"
            AlarmDay.WEDNESDAY -> "Wed"; AlarmDay.THURSDAY -> "Thu"; AlarmDay.FRIDAY -> "Fri"
            AlarmDay.SATURDAY -> "Sat"
        }
    }
}

private fun JobRunStatus.label(): String = when (this) {
    JobRunStatus.RUNNING -> "Running"
    JobRunStatus.SUCCEEDED -> "Succeeded"
    JobRunStatus.FAILED -> "Failed"
    JobRunStatus.CANCELLED -> "Cancelled"
}

@Composable
private fun JobRunStatus.color(): Color = when (this) {
    JobRunStatus.SUCCEEDED -> Color(0xFF43A047)
    JobRunStatus.FAILED -> Color(0xFFE53935)
    JobRunStatus.RUNNING -> Color(0xFFFFA000)
    JobRunStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Job create/edit form. Reuses the mobile clock UI approach so there's nothing
 * new to learn (desktop has no alarm/timer icons by design, but the layout is the
 * same): **Repeat** = the alarm flow (a `TimePicker` time-of-day + day-of-week
 * chips → a cron expression), **Once** = the timer flow (an h/m/s duration → a
 * one-shot fire instant `now + duration`). No `DatePicker` (its desktop
 * kotlinx-datetime calendar model crashes — `KotlinxDatetimeCalendarModel.getToday`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobFormDialog(
    initial: Job?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        command: String,
        prompt: String,
        workingDir: String?,
        scheduleType: JobScheduleType,
        cronExpression: String?,
        fireAtEpochMs: Long?,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var workingDir by remember { mutableStateOf(initial?.workingDir.orEmpty()) }
    var scheduleType by remember { mutableStateOf(initial?.scheduleType ?: JobScheduleType.CRON) }

    // Repeat (alarm-style): time-of-day + day-of-week chips → cron `m h * * dows`.
    val parsed = remember(initial) { initial?.cronExpression?.let(::parseJobCron) }
    val init24 = parsed?.first ?: 9
    val timeState = rememberTimePickerState(
        initialHour = init24,
        initialMinute = parsed?.second ?: 0,
        is24Hour = false,
    )
    // Desktop uses replaceable 12-hour text fields + AM/PM (see the CRON branch
    // below); these locals are the desktop source of truth, mirrored into the cron.
    var hourText by remember { mutableStateOf(hourTo12(init24).toString()) }
    var minuteText by remember { mutableStateOf((parsed?.second ?: 0).toString().padStart(2, '0')) }
    var isPm by remember { mutableStateOf(init24 >= 12) }
    var days by remember { mutableStateOf(parsed?.third ?: emptySet()) }

    // Unified 24-hour selection: desktop reads the text fields, mobile the dial.
    val selHour = if (isDesktopPlatform) hourTo24(hourText.toIntOrNull() ?: 12, isPm) else timeState.hour
    val selMinute = if (isDesktopPlatform) (minuteText.toIntOrNull() ?: 0).coerceIn(0, 59) else timeState.minute

    // Once (timer-style): an h/m/s duration → a one-shot fire at now + duration.
    val initSecs = remember(initial) {
        val fireAt = initial?.fireAtEpochMs
        if (initial?.scheduleType == JobScheduleType.ONE_SHOT && fireAt != null) {
            ((fireAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L)) / 1000L
        } else {
            0L
        }
    }
    var hours by remember { mutableStateOf((initSecs / 3600).nonZeroOrBlank()) }
    var minutes by remember { mutableStateOf(((initSecs % 3600) / 60).nonZeroOrBlank()) }
    var seconds by remember { mutableStateOf((initSecs % 60).nonZeroOrBlank()) }
    val totalMs = ((hours.toIntOrNull() ?: 0) * 3600L +
        (minutes.toIntOrNull() ?: 0) * 60L +
        (seconds.toIntOrNull() ?: 0)) * 1000L

    val scheduleValid = when (scheduleType) {
        JobScheduleType.CRON -> true            // a time is always selected
        JobScheduleType.ONE_SHOT -> totalMs > 0
    }
    val valid = name.isNotBlank() && command.isNotBlank() && scheduleValid

    // PR #70 diagnostics — logs WHY Create is enabled/disabled. Desktop: console
    // running `:desktopApp:run`.
    LaunchedEffect(name, command, scheduleType, days, totalMs, selHour, selMinute) {
        println(
            "[JobForm] create-enabled=$valid name.ok=${name.isNotBlank()} command.ok=${command.isNotBlank()} " +
                "schedule=$scheduleType scheduleValid=$scheduleValid totalMs=$totalMs days=$days time=$selHour:$selMinute",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New job" else "Edit job") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Job Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(command, { command = it }, label = { Text("Command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Command Argument") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(workingDir, { workingDir = it }, label = { Text("Working dir (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("Schedule", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = scheduleType == JobScheduleType.CRON,
                        onClick = { scheduleType = JobScheduleType.CRON },
                        label = { Text("Repeat") },
                        colors = selectedChipColors(),
                    )
                    FilterChip(
                        selected = scheduleType == JobScheduleType.ONE_SHOT,
                        onClick = { scheduleType = JobScheduleType.ONE_SHOT },
                        label = { Text("Once") },
                        colors = selectedChipColors(),
                    )
                }
                when (scheduleType) {
                    JobScheduleType.CRON -> {
                        // Desktop uses replaceable 12-hour text fields + AM/PM instead
                        // of the analog clock: the dial's selector knob obscured the
                        // selected digit under the monochrome theme (issue #2), and
                        // Material3's TimeInput wouldn't let a typed digit replace the
                        // existing one (PR #71). Mobile keeps the touch-friendly dial.
                        if (isDesktopPlatform) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ClockField(hourText, { hourText = it }, "Hour", 1..12, Modifier.weight(1f))
                                Text(":")
                                ClockField(minuteText, { minuteText = it }, "Min", 0..59, Modifier.weight(1f))
                                FilterChip(
                                    selected = !isPm,
                                    onClick = { isPm = false },
                                    label = { Text("AM") },
                                    colors = selectedChipColors(),
                                )
                                FilterChip(
                                    selected = isPm,
                                    onClick = { isPm = true },
                                    label = { Text("PM") },
                                    colors = selectedChipColors(),
                                )
                            }
                        } else {
                            TimePicker(state = timeState, colors = jobTimePickerColors())
                        }
                        JobDaysChips(selected = days, onChange = { days = it })
                        // Echo the concrete 24-hour time + days so AM/PM can't be
                        // mistaken (e.g. 10:20 PM → 22:20), per issue #7.
                        val time24 = "${selHour.toString().padStart(2, '0')}:${selMinute.toString().padStart(2, '0')}"
                        Text(
                            (if (days.isEmpty()) "Runs daily" else "Runs ${daysShortLabel(days)}") + " at $time24",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    JobScheduleType.ONE_SHOT -> {
                        Text("Run once after", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DurationField(hours, { hours = it }, "h", Modifier.weight(1f))
                            DurationField(minutes, { minutes = it }, "m", Modifier.weight(1f))
                            DurationField(seconds, { seconds = it }, "s", Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val cronExpr: String?
                    val fireAt: Long?
                    if (scheduleType == JobScheduleType.CRON) {
                        cronExpr = buildJobCron(selHour, selMinute, days)
                        fireAt = null
                    } else {
                        cronExpr = null
                        fireAt = Clock.System.now().toEpochMilliseconds() + totalMs
                    }
                    println("[JobForm] save name='$name' command='$command' schedule=$scheduleType cron='$cronExpr' fireAt=$fireAt")
                    onSave(name, command, prompt, workingDir.ifBlank { null }, scheduleType, cronExpr, fireAt)
                },
            ) { Text(if (initial == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** 24-hour → 12-hour clock face (0→12, 13→1, …). */
private fun hourTo12(h24: Int): Int = ((h24 % 12).let { if (it == 0) 12 else it })

/** 12-hour clock face + AM/PM → 24-hour. */
private fun hourTo24(h12: Int, isPm: Boolean): Int {
    val base = h12 % 12              // 12 → 0
    return if (isPm) base + 12 else base
}

/**
 * Desktop hour/minute field with REPLACE semantics (PR #71). `takeLast(2)` (not
 * `take(2)`) shifts the value like an odometer — typing into a full "09" field
 * replaces rather than being ignored, which Material3's `TimeInput` wouldn't do.
 * Blank is accepted mid-edit; otherwise the value must land in `range`.
 */
@Composable
private fun ClockField(value: String, onValueChange: (String) -> Unit, label: String, range: IntRange, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).takeLast(2)
            if (digits.isEmpty() || (digits.toIntOrNull()?.let { it in range } == true)) onValueChange(digits)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun DurationField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(2)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Sun-first single-letter day chips, mirroring the mobile alarm screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobDaysChips(selected: Set<AlarmDay>, onChange: (Set<AlarmDay>) -> Unit) {
    val order = listOf(
        AlarmDay.SUNDAY, AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
        AlarmDay.THURSDAY, AlarmDay.FRIDAY, AlarmDay.SATURDAY,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (day in order) {
            FilterChip(
                selected = day in selected,
                onClick = { onChange(if (day in selected) selected - day else selected + day) },
                label = {
                    Text(day.singleLetter(), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
                colors = selectedChipColors(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * High-contrast selected colours (issues #3/#5): the desktop monochrome theme's
 * `secondaryContainer` (the FilterChip default selected colour) is near-white, so
 * a selection was almost invisible. Use `primary`/`onPrimary` (black-on-white /
 * white-on-black) so the selected state reads clearly on both themes.
 */
@Composable
private fun selectedChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

/**
 * Explicit TimePicker colours (issue #2): with the monochrome theme the clock-hand
 * selector circle and the number beneath it both rendered dark, hiding the digit.
 * Pin the selected content to `onPrimary` so it contrasts the `primary` selector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun jobTimePickerColors() = TimePickerDefaults.colors(
    clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
    selectorColor = MaterialTheme.colorScheme.primary,
    clockDialSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
    clockDialUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
    periodSelectorSelectedContainerColor = MaterialTheme.colorScheme.primary,
    periodSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
    periodSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surface,
    periodSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
    timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primary,
    timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
    timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    timeSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
)

private fun Long.nonZeroOrBlank(): String = if (this > 0) toString() else ""

private fun AlarmDay.singleLetter(): String = when (this) {
    AlarmDay.SUNDAY -> "S"
    AlarmDay.MONDAY -> "M"
    AlarmDay.TUESDAY -> "T"
    AlarmDay.WEDNESDAY -> "W"
    AlarmDay.THURSDAY -> "T"
    AlarmDay.FRIDAY -> "F"
    AlarmDay.SATURDAY -> "S"
}

// ---- cron <-> alarm-style time+days (5-field UNIX, dow 0=Sun..6=Sat) --------

private fun AlarmDay.cronDow(): Int = when (this) {
    AlarmDay.SUNDAY -> 0
    AlarmDay.MONDAY -> 1
    AlarmDay.TUESDAY -> 2
    AlarmDay.WEDNESDAY -> 3
    AlarmDay.THURSDAY -> 4
    AlarmDay.FRIDAY -> 5
    AlarmDay.SATURDAY -> 6
}

private fun cronDowToDay(n: Int): AlarmDay? = when (n % 7) {
    0 -> AlarmDay.SUNDAY
    1 -> AlarmDay.MONDAY
    2 -> AlarmDay.TUESDAY
    3 -> AlarmDay.WEDNESDAY
    4 -> AlarmDay.THURSDAY
    5 -> AlarmDay.FRIDAY
    6 -> AlarmDay.SATURDAY
    else -> null
}

/** `minute hour * * dows` (dows = `*` for every day). */
private fun buildJobCron(hour: Int, minute: Int, days: Set<AlarmDay>): String {
    val dow = if (days.isEmpty()) "*" else days.map { it.cronDow() }.sorted().joinToString(",")
    return "$minute $hour * * $dow"
}

/** Best-effort reverse of [buildJobCron] for pre-filling an edit. */
private fun parseJobCron(expr: String): Triple<Int, Int, Set<AlarmDay>>? {
    val parts = expr.trim().split(Regex("\\s+"))
    if (parts.size != 5) return null
    val minute = parts[0].toIntOrNull() ?: return null
    val hour = parts[1].toIntOrNull() ?: return null
    val days = if (parts[4] == "*") {
        emptySet()
    } else {
        parts[4].split(",").mapNotNull { it.toIntOrNull()?.let(::cronDowToDay) }.toSet()
    }
    return Triple(hour, minute, days)
}
