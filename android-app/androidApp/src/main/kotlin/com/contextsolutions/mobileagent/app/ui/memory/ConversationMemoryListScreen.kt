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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.memory.Memory
import com.contextsolutions.mobileagent.memory.MemoryCategory

/**
 * Per-conversation memory list (M5 Phase E). Shares [MemoryViewModel]
 * with [MemoryScreen] so a delete here is reflected everywhere.
 *
 * Flat list (no category grouping) since per-conversation lists are
 * naturally short. Each row carries a category chip so the user can
 * still see what kind of fact it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationMemoryListScreen(
    conversationId: String,
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    DisposableEffect(conversationId) {
        viewModel.observeConversation(conversationId)
        onDispose { viewModel.stopObservingConversation() }
    }

    val memories by viewModel.conversationMemories.collectAsState()
    var pendingDelete by remember { mutableStateOf<Memory?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memories from this chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            if (memories.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No memories from this conversation yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(count = memories.size, key = { memories[it].id }) { idx ->
                        val memory = memories[idx]
                        ConversationMemoryRow(
                            memory = memory,
                            onDelete = { pendingDelete = memory },
                        )
                        if (idx < memories.size - 1) HorizontalDivider(thickness = 0.5.dp)
                    }
                }
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
}

@Composable
private fun ConversationMemoryRow(
    memory: Memory,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(memory.category)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    relativeCreatedLabel(memory.createdAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(memory.text, style = MaterialTheme.typography.bodyMedium)
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
private fun CategoryChip(category: MemoryCategory) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = humanLabelShort(category),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun relativeCreatedLabel(epochMs: Long): String {
    val now = remember(epochMs) { System.currentTimeMillis() }
    return DateUtils.getRelativeTimeSpanString(
        epochMs,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

private fun humanLabelShort(category: MemoryCategory): String = when (category) {
    MemoryCategory.PERSONAL_IDENTITY -> "identity"
    MemoryCategory.PREFERENCE -> "preference"
    MemoryCategory.PROFESSIONAL -> "professional"
    MemoryCategory.INTEREST -> "interest"
    MemoryCategory.RELATIONSHIP -> "relationship"
    MemoryCategory.TEMPORARY_CONTEXT -> "temporary"
}
