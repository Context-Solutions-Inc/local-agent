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
import com.contextsolutions.mobileagent.app.BuildConfig

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
    var hfTokenInput by remember { mutableStateOf("") }
    var showHfToken by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { memoryViewModel.refresh() }

    LaunchedEffect(state.keyJustSaved) {
        if (state.keyJustSaved) {
            keyInput = ""
            viewModel.acknowledgeKeySaved()
        }
    }

    LaunchedEffect(state.hfTokenJustSaved) {
        if (state.hfTokenJustSaved) {
            hfTokenInput = ""
            viewModel.acknowledgeHfTokenSaved()
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

            SectionHeader("HuggingFace token")
            Text(
                "The Gemma 4 model weights live in a gated HuggingFace repository. " +
                    "Provide a read-scoped access token from huggingface.co/settings/tokens " +
                    "and accept the Gemma license on the model card before downloading.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            HfTokenStatusRow(state)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = hfTokenInput,
                onValueChange = { hfTokenInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("HuggingFace access token") },
                placeholder = {
                    Text(if (state.hasUserHfToken) "Replace existing token" else "Paste token")
                },
                singleLine = true,
                visualTransformation = if (showHfToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    Text(
                        text = if (showHfToken) "Hide" else "Show",
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
                    onClick = { viewModel.saveHfAuthToken(hfTokenInput) },
                    enabled = hfTokenInput.isNotBlank(),
                ) { Text("Save") }
                if (state.hasUserHfToken) {
                    OutlinedButton(onClick = { viewModel.clearHfAuthToken() }) { Text("Clear") }
                }
                OutlinedButton(onClick = { showHfToken = !showHfToken }) {
                    Text(if (showHfToken) "Mask" else "Reveal")
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            // M6 Phase C — opt-in telemetry. PRD §3.2.1 + §4.4 explicit-opt-in
            // contract: default OFF, toggle is reachable from both first-run
            // onboarding (Phase E) and this Settings section.
            SectionHeader("Anonymous telemetry")
            Text(
                if (state.telemetryEnabled) {
                    "Aggregate counters help us improve the assistant. Off uploads next cycle if you toggle this off."
                } else {
                    "Off. The app never sends usage data when this is off."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Share anonymous counters", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = state.telemetryEnabled,
                    onCheckedChange = { viewModel.setTelemetryEnabled(it) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "What we send: counts per day — queries, search invocations, " +
                    "memory operations, latency percentiles. Plus redacted " +
                    "crash reports so we can fix what breaks. What we don't: " +
                    "your queries, your memories, conversation content, any identifier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            // Debug-only: bypass the 24h periodic schedule and fire one
            // telemetry upload immediately. Watch `adb logcat -s
            // TelemetryWorker:I` for the outcome line. The uploader still
            // gates on consent — this button never sends data when the
            // toggle above is OFF.
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { viewModel.triggerTelemetryUploadNow() }) {
                    Text("Run telemetry upload now (debug)")
                }
                Text(
                    "Debug-only. Bypasses the 24 h periodic schedule. " +
                        "Outcome in logcat -s TelemetryWorker:I.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { viewModel.triggerCrashRedactionTest() }) {
                    Text("Test crash redaction (debug)")
                }
                Text(
                    "Debug-only. Records a non-fatal whose message contains a " +
                        "fake Bearer token, then force-flushes so it ships " +
                        "immediately. Dashboard typically shows the new issue " +
                        "within 1–5 min. Expect 'Bearer <redacted>' in the " +
                        "message, NOT the raw value.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.triggerBreadcrumbRedactionTest() }) {
                    Text("Test breadcrumb redaction (debug)")
                }
                Text(
                    "Debug-only. Records a breadcrumb with a fake subscription " +
                        "token. Breadcrumbs only appear in the dashboard ATTACHED " +
                        "TO a crash — tap this, then tap 'Test crash redaction' " +
                        "above; the breadcrumb appears in that crash's Logs tab " +
                        "with 'X-Subscription-Token: <redacted>'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
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

@Composable
private fun HfTokenStatusRow(state: SettingsUiState) {
    val (label, color) = when {
        state.hasUserHfToken -> "Your token is set." to MaterialTheme.colorScheme.primary
        state.hasDevHfToken -> "No user token — using bundled dev token (debug build only)." to MaterialTheme.colorScheme.outline
        else -> "No token configured. The model download will fail until you add one." to MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.bodySmall, color = color)
}
