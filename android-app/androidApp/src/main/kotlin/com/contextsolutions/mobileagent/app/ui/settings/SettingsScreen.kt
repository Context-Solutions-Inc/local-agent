package com.contextsolutions.mobileagent.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Settings: Brave API key (BYOK), web-search toggle, cache clear. Reachable from
 * the chat top bar. Production users land here on first run via the
 * "Add a key" affordance once that's wired (M6 polish); for M2 it's manual.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMemoryManagement: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    memoryViewModel: com.contextsolutions.mobileagent.app.ui.memory.MemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val memoryState by memoryViewModel.state.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { memoryViewModel.refresh() }

    LaunchedEffect(state.keyJustSaved) {
        if (state.keyJustSaved) {
            keyInput = ""
            viewModel.acknowledgeKeySaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Brave Search")
            Text(
                "The assistant searches the web through Brave Search. Get a key at " +
                    "https://brave.com/search/api/ — the free tier is enough for personal use.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            KeyStatusRow(state)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Brave API key") },
                placeholder = { Text(if (state.hasUserKey) "Replace existing key" else "Paste key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    Text(
                        text = if (showKey) "Hide" else "Show",
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .padding(vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.saveBraveKey(keyInput) },
                    enabled = keyInput.isNotBlank(),
                ) { Text("Save") }
                if (state.hasUserKey) {
                    OutlinedButton(onClick = { viewModel.clearBraveKey() }) { Text("Clear") }
                }
                OutlinedButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Mask" else "Reveal")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            SectionHeader("Web search")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow web search", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "When off, the model answers from training data only. " +
                            "Tool calls return a 'search disabled' error to the model.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.searchEnabled,
                    onCheckedChange = { viewModel.setSearchEnabled(it) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            SectionHeader("Search cache")
            val countLabel = when {
                state.cacheCount < 0 -> "…"
                state.cacheCount == 0L -> "empty"
                else -> "${state.cacheCount} entries"
            }
            Text(
                "Cached results: $countLabel. Time-sensitive queries expire after " +
                    "5 minutes; general results after 24 hours.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.clearCache() }) { Text("Clear cache") }
            if (state.cacheJustCleared) {
                LaunchedEffect(Unit) {
                    viewModel.acknowledgeCacheCleared()
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Cleared.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            SectionHeader("Memory")
            val memoryCountLabel = when (memoryState.totalCount) {
                0 -> "no memories saved"
                1 -> "1 memory saved"
                else -> "${memoryState.totalCount} memories saved"
            }
            val creationLabel = if (memoryState.creationEnabled) "creation on" else "creation off"
            Text(
                "$memoryCountLabel · $creationLabel.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenMemoryManagement) { Text("Manage memories") }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun KeyStatusRow(state: SettingsUiState) {
    val (label, color) = when {
        state.hasUserKey -> "Your key is set." to MaterialTheme.colorScheme.primary
        state.hasDevKey -> "No user key — using bundled dev key (debug build only)." to MaterialTheme.colorScheme.outline
        else -> "No key configured. Web search will be disabled until you add one." to MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.bodySmall, color = color)
}
