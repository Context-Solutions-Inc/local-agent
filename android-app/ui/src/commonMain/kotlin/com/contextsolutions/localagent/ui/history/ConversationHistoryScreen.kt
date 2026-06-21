package com.contextsolutions.localagent.ui.history

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import com.contextsolutions.localagent.conversation.ConversationRepository
import com.contextsolutions.localagent.conversation.ConversationSummary
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.ui.i18n.tr
import com.contextsolutions.localagent.ui.util.formatRelativeTime
import kotlinx.datetime.Clock

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
    viewModel: ConversationHistoryViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val conversations by viewModel.conversations.collectAsState()
    val query by viewModel.query.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    val searchFocus = remember { FocusRequester() }

    // Autofocus the field (and show the keyboard) the moment search opens.
    LaunchedEffect(searching) {
        if (searching) searchFocus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { viewModel.setQuery(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus),
                            placeholder = { Text(tr(StringKeys.HISTORY_SEARCH_HINT)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { viewModel.setQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = tr(StringKeys.HISTORY_CD_CLEAR_SEARCH))
                                    }
                                }
                            } else {
                                null
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(tr(StringKeys.HISTORY_TITLE))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(StringKeys.COMMON_BACK))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) {
                            // Closing search clears the filter so the full list returns.
                            viewModel.setQuery("")
                            searching = false
                        } else {
                            searching = true
                        }
                    }) {
                        if (searching) {
                            Icon(Icons.Default.Close, contentDescription = tr(StringKeys.HISTORY_CD_CLOSE_SEARCH))
                        } else {
                            Icon(Icons.Default.Search, contentDescription = tr(StringKeys.HISTORY_CD_SEARCH))
                        }
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
                // A blank query with no rows = genuinely empty store; a non-blank
                // query with no rows = no matches for the search term.
                val emptyKey = if (query.isNotBlank()) StringKeys.HISTORY_SEARCH_EMPTY else StringKeys.HISTORY_EMPTY
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        tr(emptyKey),
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
                            tr(StringKeys.HISTORY_CAPACITY_FOOTNOTE, ConversationRepository.CONVERSATION_CAP),
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
            title = { Text(tr(StringKeys.HISTORY_DELETE_TITLE)) },
            text = {
                Text(tr(StringKeys.HISTORY_DELETE_BODY, convo.title))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(convo.id)
                    onDeleted(convo.id)
                    pendingDelete = null
                }) { Text(tr(StringKeys.HISTORY_DELETE)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr(StringKeys.HISTORY_CANCEL)) }
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
                contentDescription = tr(StringKeys.HISTORY_CD_DELETE),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun relativeUpdatedLabel(epochMs: Long): String {
    val now = remember(epochMs) { Clock.System.now().toEpochMilliseconds() }
    return formatRelativeTime(epochMs, now, com.contextsolutions.localagent.ui.i18n.LocalStrings.current)
}
