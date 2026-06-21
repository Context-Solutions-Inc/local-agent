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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.viewmodel.koinViewModel
import com.contextsolutions.localagent.clock.AlarmDay
import com.contextsolutions.localagent.clock.AlarmEntry
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings
import com.contextsolutions.localagent.ui.i18n.LocalStrings
import com.contextsolutions.localagent.ui.i18n.tr
import com.contextsolutions.localagent.ui.platform.rememberIsLandscape

/**
 * Full-screen alarms surface (PR #17). Replaces the prior
 * `ModalBottomSheet`-based `AlarmSheet`. Mirrors `MyListScreen`'s
 * shape: top bar + LazyColumn + FAB → create-dialog, plus an edit dialog
 * reached from each row.
 *
 * Each row offers:
 *  - Enable/disable Switch (recurring alarms only — one-shot is on by definition,
 *    but the Switch reflects whatever the entry's `enabled` flag is)
 *  - "Edit" → opens the edit dialog with pre-filled time/days/label
 *  - "Cancel" → removes the row
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmManagementScreen(
    onBack: () -> Unit,
    viewModel: ClockViewModel = koinViewModel(),
) {
    val alarms by viewModel.alarms.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AlarmEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(StringKeys.CLOCK_UI_ALARMS_TITLE)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(StringKeys.COMMON_BACK))
                    }
                },
                actions = {
                    // Nudge left so the "+" right-aligns with the per-row trash icon,
                    // which sits inside the body's 16dp horizontal padding while
                    // TopAppBar actions inset only ~4dp.
                    IconButton(onClick = { creating = true }, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = tr(StringKeys.CLOCK_UI_CD_NEW_ALARM))
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
            if (alarms.isEmpty()) {
                EmptyAlarms()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmRow(
                            alarm = alarm,
                            onToggleEnabled = { viewModel.setAlarmEnabled(alarm.id, it) },
                            onEdit = { editing = alarm },
                            onCancel = { viewModel.cancelAlarm(alarm.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (creating) {
        AlarmFormDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { hour, minute, days, label ->
                viewModel.createAlarm(hour, minute, days, label)
                creating = false
            },
        )
    }

    editing?.let { current ->
        AlarmFormDialog(
            initial = current,
            onDismiss = { editing = null },
            onSave = { hour, minute, days, label ->
                viewModel.updateAlarm(
                    current.copy(
                        hour = hour,
                        minute = minute,
                        recurringDays = days,
                        label = label,
                    ),
                )
                editing = null
            },
        )
    }
}

@Composable
private fun EmptyAlarms() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tr(StringKeys.CLOCK_UI_ALARMS_EMPTY),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr(StringKeys.CLOCK_UI_ALARMS_EMPTY_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: AlarmEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "%02d:%02d".format(alarm.hour, alarm.minute),
                style = MaterialTheme.typography.titleMedium,
            )
            val subtitle = buildString {
                if (alarm.label?.isNotBlank() == true) {
                    append(alarm.label); append(" · ")
                }
                append(
                    if (alarm.isRecurring) alarm.recurringDays.toLabel(strings) else strings.get(StringKeys.CLOCK_UI_ONCE),
                )
                if (!alarm.enabled) append(strings.get(StringKeys.CLOCK_UI_OFF_SUFFIX))
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggleEnabled)
        // Grey pencil/trash (onSurfaceVariant) to match the top-bar "+" and the My
        // List row icons, replacing the prior text "Edit"/"Cancel" buttons.
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = tr(StringKeys.CLOCK_UI_EDIT),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = tr(StringKeys.CLOCK_UI_CANCEL),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmFormDialog(
    initial: AlarmEntry?,
    onDismiss: () -> Unit,
    onSave: (hour: Int, minute: Int, days: Set<AlarmDay>, label: String?) -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = initial?.hour ?: 7,
        initialMinute = initial?.minute ?: 0,
        is24Hour = false,
    )
    var days by remember { mutableStateOf(initial?.recurringDays ?: emptySet()) }
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }

    // Landscape: the horizontal TimePicker layout overlaps the number/AM-PM column
    // in the platform-default-width dialog, so widen it ~2× (PR #71). Portrait is
    // unchanged; desktop's rememberIsLandscape() is always false.
    val landscape = rememberIsLandscape()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
        modifier = if (landscape) Modifier.fillMaxWidth(0.92f) else Modifier,
        title = { Text(if (initial == null) tr(StringKeys.CLOCK_UI_NEW_ALARM) else tr(StringKeys.CLOCK_UI_EDIT_ALARM)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimePicker(state = timeState)
                DaysChips(selected = days, onChange = { days = it })
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
            TextButton(onClick = {
                onSave(timeState.hour, timeState.minute, days, label.takeIf { it.isNotBlank() })
            }) {
                Text(if (initial == null) tr(StringKeys.CLOCK_UI_ADD) else tr(StringKeys.COMMON_SAVE))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr(StringKeys.CLOCK_UI_CANCEL)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaysChips(selected: Set<AlarmDay>, onChange: (Set<AlarmDay>) -> Unit) {
    // Sun-first ordering matches the US calendar convention and gives the
    // canonical S M T W T F S row layout. Tue/Thu and Sat/Sun share a
    // first letter; positional context disambiguates per the standard
    // alarm-clock UI pattern.
    val strings = LocalStrings.current
    val order = listOf(
        AlarmDay.SUNDAY, AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
        AlarmDay.THURSDAY, AlarmDay.FRIDAY, AlarmDay.SATURDAY,
    )
    // Per-day toggles only. Weekdays/Weekends/Every-day presets were dropped
    // in PR #17 review: "Every day" wrapped at dialog width on Pixel 7, and
    // tapping seven chips is fast enough to not warrant the row. The
    // WEEKDAYS / WEEKENDS / ALL_DAYS constants stay because `toLabel()`
    // still renders those subtitles on the row when the selection matches.
    //
    // weight(1f) on each chip splits the row width evenly so all seven fit
    // without horizontal overflow at Pixel 7 width. FlowRow is avoided
    // (BOM 2024.12.01 foundation-layout runtime/compile ABI skew on the
    // new FlowRowOverflow param).
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (day in order) {
            FilterChip(
                selected = day in selected,
                onClick = {
                    onChange(if (day in selected) selected - day else selected + day)
                },
                // Single-letter labels centered inside the chip. The
                // label slot otherwise start-aligns the text, which
                // looks off-balance once weight(1f) stretches the
                // chip wider than the letter. fillMaxWidth on the
                // Text plus textAlign=Center pushes the glyph to the
                // visual middle of the chip's content area.
                label = {
                    Text(
                        text = day.singleLetter(strings),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val WEEKDAYS: Set<AlarmDay> = setOf(
    AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY, AlarmDay.THURSDAY, AlarmDay.FRIDAY,
)
private val WEEKENDS: Set<AlarmDay> = setOf(AlarmDay.SATURDAY, AlarmDay.SUNDAY)
private val ALL_DAYS: Set<AlarmDay> = WEEKDAYS + WEEKENDS

private fun AlarmDay.singleLetter(strings: Strings): String = when (this) {
    AlarmDay.SUNDAY -> strings.get(StringKeys.CLOCK_UI_DAY_LETTER_SUN)
    AlarmDay.MONDAY -> strings.get(StringKeys.CLOCK_UI_DAY_LETTER_MON)
    AlarmDay.TUESDAY -> strings.get(StringKeys.CLOCK_UI_DAY_LETTER_TUE)
    AlarmDay.WEDNESDAY -> strings.get(StringKeys.CLOCK_UI_DAY_LETTER_WED)
    AlarmDay.THURSDAY -> strings.get(StringKeys.CLOCK_UI_DAY_LETTER_THU)
    AlarmDay.FRIDAY -> strings.get(StringKeys.CLOCK_UI_DAY_LETTER_FRI)
    AlarmDay.SATURDAY -> strings.get(StringKeys.CLOCK_UI_DAY_LETTER_SAT)
}

private fun AlarmDay.shortName(strings: Strings): String = when (this) {
    AlarmDay.MONDAY -> strings.get(StringKeys.CLOCK_UI_DAY_SHORT_MON)
    AlarmDay.TUESDAY -> strings.get(StringKeys.CLOCK_UI_DAY_SHORT_TUE)
    AlarmDay.WEDNESDAY -> strings.get(StringKeys.CLOCK_UI_DAY_SHORT_WED)
    AlarmDay.THURSDAY -> strings.get(StringKeys.CLOCK_UI_DAY_SHORT_THU)
    AlarmDay.FRIDAY -> strings.get(StringKeys.CLOCK_UI_DAY_SHORT_FRI)
    AlarmDay.SATURDAY -> strings.get(StringKeys.CLOCK_UI_DAY_SHORT_SAT)
    AlarmDay.SUNDAY -> strings.get(StringKeys.CLOCK_UI_DAY_SHORT_SUN)
}

private fun Set<AlarmDay>.toLabel(strings: Strings): String {
    if (isEmpty()) return strings.get(StringKeys.CLOCK_UI_ONCE)
    return when (this) {
        WEEKDAYS -> strings.get(StringKeys.CLOCK_UI_WEEKDAYS)
        WEEKENDS -> strings.get(StringKeys.CLOCK_UI_WEEKENDS)
        ALL_DAYS -> strings.get(StringKeys.CLOCK_UI_EVERY_DAY)
        else -> joinToString(", ") { it.shortName(strings) }
    }
}
