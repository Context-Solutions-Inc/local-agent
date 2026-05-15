package com.contextsolutions.mobileagent.app.ui.history

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.conversation.ConversationSummary

/**
 * PR#13 — conversation history list reached from Settings → "Manage
 * conversations". Tapping a row resumes that conversation in [ChatScreen];
 * tapping the delete icon hard-deletes the conversation (and via FK CASCADE
 * its messages). Memories tagged with that conversation_id survive — see
 * [ConversationRepository] doc.
 *
 * Capacity ceiling ([ConversationRepository.CONVERSATION_CAP]) is surfaced
 * as a footnote so the auto-eviction isn't surprising.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistoryScreen(
    onBack: () -> Unit,
    onResume: (conversationId: String) -> Unit,
    onDeleted: (conversationId: String) -> Unit = {},
    viewModel: ConversationHistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val conversations by viewModel.conversations.collectAsState()
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conversation history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No conversations yet.",
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
                    items(count = conversations.size, key = { conversations[it].id }) { idx ->
                        val convo = conversations[idx]
                        ConversationRow(
                            summary = convo,
                            onResume = { onResume(convo.id) },
                            onDeleteRequest = { pendingDelete = convo },
                        )
                        if (idx < conversations.size - 1) HorizontalDivider(thickness = 0.5.dp)
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Stores up to ${ConversationRepository.CONVERSATION_CAP} " +
                                "conversations. Oldest are removed automatically.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { convo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = {
                Text(
                    "\"${convo.title}\"\n\n" +
                        "This deletes the conversation and its messages. Memories " +
                        "saved from this chat are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(convo.id)
                    onDeleted(convo.id)
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
private fun ConversationRow(
    summary: ConversationSummary,
    onResume: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onResume)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = relativeUpdatedLabel(summary.updatedAtEpochMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            val preview = summary.lastMessagePreview
            if (!preview.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDeleteRequest) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete conversation",
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun relativeUpdatedLabel(epochMs: Long): String {
    val now = remember(epochMs) { System.currentTimeMillis() }
    return DateUtils.getRelativeTimeSpanString(
        epochMs,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}
