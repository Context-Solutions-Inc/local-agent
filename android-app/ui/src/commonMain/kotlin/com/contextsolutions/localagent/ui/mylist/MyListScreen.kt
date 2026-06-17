package com.contextsolutions.localagent.ui.mylist

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings
import com.contextsolutions.localagent.mylist.MyListItem
import com.contextsolutions.localagent.mylist.MyListItemPriority
import com.contextsolutions.localagent.ui.i18n.LocalStrings
import com.contextsolutions.localagent.ui.i18n.tr
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/**
 * Full-screen My List management surface (PR #15). Opens from the chat
 * top-bar icon to the left of the timer. Mirrors the
 * `ConversationMemoryListScreen` shape (top bar + LazyColumn + delete
 * confirmation) plus a FAB and an edit dialog adapted from `AlarmSheet`'s
 * inline editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListScreen(
    onBack: () -> Unit,
    viewModel: MyListViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsState()
    var editing by remember { mutableStateOf<MyListItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MyListItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(StringKeys.MYLIST_UI_TITLE)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(StringKeys.COMMON_BACK))
                    }
                },
                actions = {
                    if (items.any { it.completed }) {
                        TextButton(onClick = { viewModel.clearCompleted() }) {
                            Text(tr(StringKeys.MYLIST_UI_CLEAR_DONE))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = tr(StringKeys.MYLIST_UI_CD_ADD))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (items.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items, key = { it.id }) { item ->
                        MyListRow(
                            item = item,
                            onToggleCompleted = { viewModel.setCompleted(item.id, it) },
                            onEdit = { editing = item },
                            onDelete = { deleting = item },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (creating) {
        MyListItemFormDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { title, priority, due, notes ->
                viewModel.createItem(title, priority, due, notes)
                creating = false
            },
        )
    }

    editing?.let { current ->
        MyListItemFormDialog(
            initial = current,
            onDismiss = { editing = null },
            onSave = { title, priority, due, notes ->
                viewModel.updateItem(
                    current.copy(
                        title = title,
                        priority = priority,
                        dueDateEpochMs = due,
                        notes = notes,
                    ),
                )
                editing = null
            },
        )
    }

    deleting?.let { current ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr(StringKeys.MYLIST_UI_DELETE_TITLE)) },
            text = { Text(tr(StringKeys.MYLIST_UI_DELETE_BODY, current.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItem(current.id)
                    deleting = null
                }) { Text(tr(StringKeys.MYLIST_UI_DELETE)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(tr(StringKeys.MYLIST_UI_CANCEL)) }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tr(StringKeys.MYLIST_UI_EMPTY),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr(StringKeys.MYLIST_UI_EMPTY_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MyListRow(
    item: MyListItem,
    onToggleCompleted: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Checkbox(checked = item.completed, onCheckedChange = onToggleCompleted)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.completed)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None,
            )
            val tags = buildList {
                if (item.priority != MyListItemPriority.MEDIUM) add(item.priority.label(strings))
                item.dueDateEpochMs?.let { add(strings.get(StringKeys.MYLIST_DUE, relativeDateLabel(it, strings))) }
            }
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = tr(StringKeys.MYLIST_UI_CD_EDIT))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = tr(StringKeys.MYLIST_UI_CD_DELETE))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyListItemFormDialog(
    initial: MyListItem?,
    onDismiss: () -> Unit,
    onSave: (title: String, priority: MyListItemPriority, dueDateEpochMs: Long?, notes: String?) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var priority by remember { mutableStateOf(initial?.priority ?: MyListItemPriority.MEDIUM) }
    var dueDate by remember { mutableStateOf(initial?.dueDateEpochMs) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }
    val strings = LocalStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr(StringKeys.MYLIST_UI_NEW_ITEM) else tr(StringKeys.MYLIST_UI_EDIT_ITEM)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tr(StringKeys.MYLIST_UI_TITLE_LABEL)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(tr(StringKeys.MYLIST_UI_PRIORITY), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(MyListItemPriority.LOW, MyListItemPriority.MEDIUM, MyListItemPriority.HIGH).forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.label(strings)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr(StringKeys.MYLIST_UI_DUE_PREFIX) + (dueDate?.let { relativeDateLabel(it, strings) } ?: tr(StringKeys.COMMON_NONE)),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickingDate = true }) {
                        Text(if (dueDate == null) tr(StringKeys.MYLIST_UI_SET) else tr(StringKeys.MYLIST_UI_CHANGE))
                    }
                    if (dueDate != null) {
                        TextButton(onClick = { dueDate = null }) { Text(tr(StringKeys.COMMON_CLEAR)) }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(tr(StringKeys.MYLIST_UI_NOTES_OPTIONAL)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(title, priority, dueDate, notes.ifBlank { null }) },
            ) {
                Text(if (initial == null) tr(StringKeys.MYLIST_UI_ADD) else tr(StringKeys.COMMON_SAVE))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr(StringKeys.MYLIST_UI_CANCEL)) }
        },
    )

    if (pickingDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueDate)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val selected = pickerState.selectedDateMillis
                    if (selected != null) {
                        // DatePicker returns UTC midnight; convert to local-tz
                        // midnight so the relative-date label ("today",
                        // "tomorrow") matches user expectation.
                        dueDate = utcMidnightToLocalMidnight(selected)
                    }
                    pickingDate = false
                }) { Text(tr(StringKeys.MYLIST_UI_OK)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(tr(StringKeys.MYLIST_UI_CANCEL)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun MyListItemPriority.label(strings: Strings): String = when (this) {
    MyListItemPriority.LOW -> strings.get(StringKeys.MYLIST_PRIORITY_LOW)
    MyListItemPriority.MEDIUM -> strings.get(StringKeys.MYLIST_PRIORITY_MEDIUM)
    MyListItemPriority.HIGH -> strings.get(StringKeys.MYLIST_PRIORITY_HIGH)
}

private fun relativeDateLabel(epochMs: Long, strings: Strings): String {
    val tz = TimeZone.currentSystemDefault()
    val today: LocalDate = Clock.System.now().toLocalDateTime(tz).date
    val target: LocalDate = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz).date
    return when (target) {
        today -> strings.get(StringKeys.MYLIST_DUE_TODAY)
        today.plus(1, DateTimeUnit.DAY) -> strings.get(StringKeys.MYLIST_DUE_TOMORROW)
        today.plus(-1, DateTimeUnit.DAY) -> strings.get(StringKeys.MYLIST_DUE_YESTERDAY)
        else -> target.toString()
    }
}

private fun utcMidnightToLocalMidnight(utcMidnightEpochMs: Long): Long {
    val utcDate = Instant.fromEpochMilliseconds(utcMidnightEpochMs)
        .toLocalDateTime(TimeZone.UTC).date
    return LocalDateTime(utcDate, LocalTime(0, 0))
        .toInstant(TimeZone.currentSystemDefault())
        .toEpochMilliseconds()
}
