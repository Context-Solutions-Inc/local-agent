package com.contextsolutions.mobileagent.app.ui.memory

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.memory.Memory
import com.contextsolutions.mobileagent.memory.MemoryCategory

/**
 * M5 Phase E memory management surface (PRD §3.2.4 user-facing controls).
 *
 *  - Title bar: back arrow + overflow → Clear all (with confirmation).
 *  - Creation toggle row: "Remember things from our conversations" gates
 *    the [com.contextsolutions.mobileagent.memory.MemoryExtractor]'s
 *    `creationEnabled` provider in real time.
 *  - Body: category-grouped list with per-row delete (confirmation
 *    dialog). Empty state explains the feature instead of showing nothing.
 *
 * Editing memory text in place is deferred to v1.x per Q4 in
 * `M5_PLAN.md` §2 — delete-and-re-state is the v1 workaround.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showOverflow by remember { mutableStateOf(false) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Memory?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear all") },
                                enabled = state.totalCount > 0,
                                onClick = {
                                    showOverflow = false
                                    clearAllConfirm = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            CreationToggleRow(
                enabled = state.creationEnabled,
                onToggle = { viewModel.onToggleCreation(it) },
            )
            HorizontalDivider()

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.totalCount == 0) {
                EmptyState()
            } else {
                MemoryList(
                    state = state,
                    onDeleteRequest = { pendingDelete = it },
                )
            }
        }
    }

    pendingDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete memory?") },
            text = { Text("\"${memory.text}\"\n\nThis cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDelete(memory.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (clearAllConfirm) {
        AlertDialog(
            onDismissRequest = { clearAllConfirm = false },
            title = { Text("Clear all memories?") },
            text = {
                Text(
                    "All ${state.totalCount} memories will be permanently deleted. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onClearAll()
                    clearAllConfirm = false
                }) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { clearAllConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CreationToggleRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Remember things from our conversations",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (enabled) {
                    "On — new memories will be saved automatically."
                } else {
                    "Off — no new memories will be created. Existing ones stay until you delete them."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(horizontal = 8.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "No memories saved yet. They'll appear here as the assistant learns about you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun MemoryList(
    state: MemoryUiState,
    onDeleteRequest: (Memory) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        for (category in MemoryCategory.entries) {
            val rows = state.memoriesByCategory[category].orEmpty()
            if (rows.isEmpty()) continue
            item(key = "header-${category.wireName}") {
                CategoryHeader(category, count = rows.size)
            }
            items(rows.size, key = { rows[it].id }) { idx ->
                val memory = rows[idx]
                MemoryRow(memory = memory, onDelete = { onDeleteRequest(memory) })
                if (idx < rows.size - 1) HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: MemoryCategory, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = humanLabel(category),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryRow(memory: Memory, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(memory.text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = createdLabel(memory),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete memory",
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun createdLabel(memory: Memory): String {
    val now = remember(memory.createdAtEpochMs) { System.currentTimeMillis() }
    val rel = DateUtils.getRelativeTimeSpanString(
        memory.createdAtEpochMs,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    val expiry = memory.expiresAtEpochMs?.let { exp ->
        val relExp = DateUtils.getRelativeTimeSpanString(
            exp, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        " · expires $relExp"
    }.orEmpty()
    return "Created $rel$expiry"
}

private fun humanLabel(category: MemoryCategory): String = when (category) {
    MemoryCategory.PERSONAL_IDENTITY -> "Personal identity"
    MemoryCategory.PREFERENCE -> "Preferences"
    MemoryCategory.PROFESSIONAL -> "Professional"
    MemoryCategory.INTEREST -> "Interests"
    MemoryCategory.RELATIONSHIP -> "Relationships"
    MemoryCategory.TEMPORARY_CONTEXT -> "Temporary"
}
