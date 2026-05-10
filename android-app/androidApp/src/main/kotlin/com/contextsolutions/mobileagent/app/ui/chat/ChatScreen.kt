package com.contextsolutions.mobileagent.app.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.app.service.SessionState
import com.contextsolutions.mobileagent.app.ui.memory.ConversationMemoryBadge
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.search.SearchSource

/**
 * M2 chat surface — full conversation with streaming, web-search status, and
 * citation chips. Settings (key entry, search toggle, cache clear) is one tap
 * away in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSpike: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConversationMemory: (conversationId: String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val session by viewModel.sessionState.collectAsState()
    val memoryCount by viewModel.memoryCount.collectAsState()
    val conversationId by viewModel.conversationId.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the latest message visible as new tokens arrive.
    LaunchedEffect(ui.messages.size, ui.partialText.length, ui.isGenerating) {
        val total = ui.messages.size + (if (ui.partialText.isNotEmpty() || ui.isGenerating) 1 else 0)
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    // Re-query the badge count whenever the chat screen comes back into
    // the composition — handles the "delete a memory in MemoryScreen
    // and return to chat" path where the in-flight count is otherwise
    // stuck at the pre-delete value.
    LaunchedEffect(Unit) {
        viewModel.refreshMemoryCount()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                actions = {
                    val cid = conversationId
                    if (cid != null) {
                        ConversationMemoryBadge(
                            count = memoryCount,
                            onClick = { onOpenConversationMemory(cid) },
                        )
                    }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                    TextButton(onClick = { viewModel.newConversation() }) { Text("New") }
                    TextButton(onClick = onOpenSpike) { Text("Spike") }
                },
            )
        },
    ) { padding ->
        // imePadding() pushes the bottom of the chat column up by the
        // height of the soft keyboard. Edge-to-edge (set in MainActivity)
        // means the IME is no longer auto-resizing the window — Compose
        // has to consume the inset itself, otherwise the input field gets
        // covered.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            SessionBanner(session)
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.messages.isEmpty() && ui.partialText.isEmpty() && !ui.isGenerating && ui.error == null) {
                    item {
                        Text(
                            "Type a question. Web search runs automatically when the model needs current info.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(ui.messages) { message ->
                    when (message) {
                        is UiMessage.User -> UserBubble(message.text)
                        is UiMessage.Assistant -> AssistantBubble(
                            text = message.text,
                            citations = message.citations,
                            fromCache = message.fromCache,
                        )
                    }
                }
                if (ui.partialText.isNotEmpty() || ui.isGenerating || ui.searchStatus !is SearchStatus.None) {
                    item {
                        StreamingAssistantBubble(
                            partial = ui.partialText,
                            searchStatus = ui.searchStatus,
                            isGenerating = ui.isGenerating,
                        )
                    }
                }
                if (ui.error != null) {
                    item {
                        Text(
                            text = "Error: ${ui.error}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (ui.isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ask anything…") },
                enabled = !ui.isGenerating,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                    enabled = !ui.isGenerating && input.isNotBlank(),
                ) {
                    Text("Send")
                }
                if (ui.isGenerating) {
                    OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
                }
                OutlinedButton(onClick = { viewModel.forceUnload() }) { Text("Unload") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AssistantBubble(text: String, citations: List<SearchSource>, fromCache: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (fromCache) {
            Text(
                "From cache",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
        if (citations.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            CitationChips(citations)
        }
    }
}

@Composable
private fun StreamingAssistantBubble(
    partial: String,
    searchStatus: SearchStatus,
    isGenerating: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (searchStatus is SearchStatus.Searching) {
            SearchingChip(searchStatus.query)
            Spacer(Modifier.height(4.dp))
        } else if (searchStatus is SearchStatus.Failed) {
            Text(
                "Search ${searchStatus.kind.lowercase()}: ${searchStatus.message}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (partial.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(partial, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (isGenerating && searchStatus !is SearchStatus.Searching) {
            Text(
                "Thinking…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SearchingChip(query: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text("Searching: $query") },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun CitationChips(citations: List<SearchSource>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        citations.forEachIndexed { index, source ->
            val host = remember(source.url) { source.url.toHostOrNull() ?: source.url }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, source.url.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "[${index + 1}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = "${source.title} — $host",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

private fun String.toHostOrNull(): String? = try {
    toUri().host
} catch (_: Throwable) {
    null
}

@Composable
private fun SessionBanner(state: SessionState) {
    val (text, isWarning) = when (state) {
        is SessionState.Unloaded ->
            "Model unloaded — next prompt cold-loads in 4–8 s." to false
        is SessionState.Loading ->
            "Loading model…" to false
        is SessionState.Loaded -> {
            if (state.activeAccelerator == Accelerator.CPU) {
                "Loaded on CPU (degraded mode — generation will be slow)." to true
            } else {
                "Loaded on ${state.activeAccelerator.name}." to false
            }
        }
        is SessionState.Failed ->
            "Model load failed: ${state.message}" to true
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
    )
}
