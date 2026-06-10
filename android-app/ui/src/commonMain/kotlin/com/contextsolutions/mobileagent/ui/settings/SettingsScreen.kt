package com.contextsolutions.mobileagent.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.contextsolutions.mobileagent.inference.DesktopLinkStatus
import com.contextsolutions.mobileagent.link.MobileLinkKind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.contextsolutions.mobileagent.platform.AppBuildConfig
import com.contextsolutions.mobileagent.preferences.OllamaConfig
import com.contextsolutions.mobileagent.preferences.RemoteServerType
import com.contextsolutions.mobileagent.ui.platform.isDesktopPlatform
import com.contextsolutions.mobileagent.ui.theme.AppFontFamily
import com.contextsolutions.mobileagent.ui.theme.FontScale
import com.contextsolutions.mobileagent.ui.theme.ThemeMode
import com.contextsolutions.mobileagent.ui.theme.ThemeModeViewModel
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings: Brave API key (BYOK), web-search toggle, cache clear. Reachable from
 * the chat top bar. Production users land here on first run via the
 * "Add a key" affordance once that's wired (M6 polish); for M2 it's manual.
 *
 * Section order (PR #44): the day-to-day controls come first
 * (Conversations, Web search, Search cache, Search sources, Memory,
 * Anonymous telemetry), then the BYOK credential sections (Brave Search
 * API, HuggingFace token) sit last. Response language was removed in
 * PR #44 — launch ships English-only; multi-language is a v2 activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMemoryManagement: () -> Unit,
    onOpenConversationHistory: () -> Unit,
    onOpenSearchSources: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    themeModeViewModel: ThemeModeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val themeMode by themeModeViewModel.mode.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var hfTokenInput by remember { mutableStateOf("") }
    var showHfToken by remember { mutableStateOf(false) }

    // PR #56 — Ollama server editable fields, re-seeded from the persisted config
    // (so an external save/clear or reload reflects here). Editing the text
    // fields doesn't change `state.ollamaConfig`, so typing isn't reset.
    val ollamaCfg = state.ollamaConfig
    var ollamaHost by remember(ollamaCfg) { mutableStateOf(ollamaCfg.host) }
    var ollamaPort by remember(ollamaCfg) {
        mutableStateOf(ollamaCfg.port?.toString() ?: OllamaConfig.DEFAULT_PORT.toString())
    }
    var ollamaChatModel by remember(ollamaCfg) { mutableStateOf(ollamaCfg.chatModel) }
    var ollamaVisionModel by remember(ollamaCfg) { mutableStateOf(ollamaCfg.visionModel) }
    // PR #73 — backend type + SSL. An OpenAI-compatible server is always HTTPS,
    // so `ollamaUseSsl` is shown checked-and-locked while OPENAI is selected.
    var ollamaServerType by remember(ollamaCfg) { mutableStateOf(ollamaCfg.serverType) }
    var ollamaUseSsl by remember(ollamaCfg) { mutableStateOf(ollamaCfg.useSsl) }

    LaunchedEffect(Unit) { viewModel.refreshMemorySummary() }

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
            // PR #44 — shared style for the in-copy clickable URLs (Brave +
            // HuggingFace credential sections below).
            val linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            )

            // PR#13 — conversation history list, accessible from Settings
            // (NOT the chat top bar) to keep the chat surface focused on
            // the active conversation.
            SectionHeader("Conversations")
            Text(
                "Browse, resume, or delete prior chats. The most recent 50 are kept; " +
                    "older conversations are removed automatically.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenConversationHistory) { Text("Manage conversations") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            SectionHeaderWithToggle(
                title = "Web search",
                checked = state.searchEnabled,
                onCheckedChange = { viewModel.setSearchEnabled(it) },
            )
            Text(
                "When off, the model answers from training data only.",
                style = MaterialTheme.typography.bodySmall,
            )
            // The toggle alone isn't enough — search needs a Brave API key too.
            // Surface that here (tied to the control the user just flipped) so an
            // enabled-but-keyless config doesn't silently fall back to
            // SearchDisabled. Mirrors the gate: isAvailable() = enabled && hasKey.
            if (state.searchEnabled && !state.hasUserKey && !state.hasDevKey) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "No API key — search is disabled until you add a Brave Search API key below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
                    "5 minutes; general results after 1 hour.",
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

            // PR #23 — vertical search sources. Per-vertical site lists
            // captured at onboarding from the user's location, editable
            // here. SearchSourcesScreen renders five sections with
            // add/remove affordances. Placed under Search cache (PR #44)
            // so the two search-config controls sit together.
            SectionHeader("Search sources")
            Text(
                "Choose what websites to use for weather, news, sports and finance questions.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSearchSources) { Text("Manage search sources") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            SectionHeader("Memory")
            val memoryCountLabel = when (state.memoryCount) {
                -1 -> "…"
                0 -> "no memories saved"
                1 -> "1 memory saved"
                else -> "${state.memoryCount} memories saved"
            }
            val creationLabel = if (state.memoryCreationEnabled) "creation on" else "creation off"
            Text(
                "$memoryCountLabel · $creationLabel.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenMemoryManagement) { Text("Manage memories") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            // Theme selector (PR #59). Moved here from the Chat top-bar icon and
            // made a three-way segmented control. Drives ThemeModeViewModel.setMode,
            // which MainActivity (Android) and Main.kt (desktop) observe to flip the
            // MaterialTheme colorScheme. "Auto" follows the OS dark-mode setting.
            SectionHeader("Appearance")
            Text(
                "Choose how the app looks. Auto follows your system's light/dark setting.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val themeOptions = listOf(
                ThemeMode.Light to "Light",
                ThemeMode.System to "Auto",
                ThemeMode.Dark to "Dark",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themeOptions.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { themeModeViewModel.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themeOptions.size),
                    ) { Text(label) }
                }
            }

            // Font family + size (PR #60). Applied app-wide on BOTH platforms via
            // ThemeModeViewModel → AppThemeScaffold: a LocalDensity fontScale override
            // scales every sp text (incl. the Android markdown TextView), and the
            // typography family overrides the font. The desktop default density renders
            // small on HiDPI monitors — the size slider lets the user compensate. The
            // whole Settings screen reflects the choice live as it's changed.
            Spacer(Modifier.height(20.dp))
            val fontFamily by themeModeViewModel.fontFamily.collectAsState()
            val fontScale by themeModeViewModel.fontScale.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Font: ", style = MaterialTheme.typography.bodyMedium)
                var fontMenuOpen by remember { mutableStateOf(false) }
                AssistChip(
                    onClick = { fontMenuOpen = true },
                    label = { Text(fontFamily.displayLabel()) },
                )
                DropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                    AppFontFamily.entries.forEach { family ->
                        DropdownMenuItem(
                            text = { Text(family.displayLabel()) },
                            onClick = { themeModeViewModel.setFontFamily(family); fontMenuOpen = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Text size — ${(fontScale * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = fontScale,
                onValueChange = { themeModeViewModel.setFontScale(it) },
                valueRange = FontScale.MIN..FontScale.MAX,
                steps = 15,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The quick brown fox jumps over the lazy dog.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )

            // PR #66 — desktop-only read-aloud voice picker (engine/voice/rate). The
            // section (incl. its own leading divider) renders nothing on Android,
            // where the OS owns voice selection.
            DesktopVoiceSection()

            // PR #78 — desktop-only GPU device pin for the local llama-server. Renders
            // nothing on Android (LiteRT-LM, no device selection).
            DesktopGpuSection()

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            // M6 Phase C — opt-in telemetry. PRD §3.2.1 + §4.4 explicit-opt-in
            // contract: default OFF, toggle is reachable from both first-run
            // onboarding (Phase E) and this Settings section.
            SectionHeaderWithToggle(
                title = "Anonymous telemetry",
                checked = state.telemetryEnabled,
                onCheckedChange = { viewModel.setTelemetryEnabled(it) },
            )
            Text(
                if (state.telemetryEnabled) {
                    "Aggregate counters help us improve the assistant."
                } else {
                    "Off. The app never sends usage data when this is off."
                },
                style = MaterialTheme.typography.bodySmall,
            )
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
            if (state.isDebugBuild) {
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            // BYOK credential sections moved to the bottom (PR #44) — set
            // once at setup, rarely touched afterwards.
            SectionHeader("Brave Search API")
            val braveUrl = "https://brave.com/search/api/"
            Text(
                buildAnnotatedString {
                    append("The assistant searches the web through Brave Search. Get a key at ")
                    withLink(LinkAnnotation.Url(braveUrl, linkStyles)) { append(braveUrl) }
                    append(" — the free tier is enough for personal use.")
                },
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
            val hfTokensUrl = "https://huggingface.co/settings/tokens"
            val hfModelCardUrl = "https://huggingface.co/google/gemma-4-E2B-it"
            Text(
                buildAnnotatedString {
                    append("The AI model weights are located in a HuggingFace ")
                    append("repository. Create a read-scoped access token from ")
                    withLink(LinkAnnotation.Url(hfTokensUrl, linkStyles)) { append(hfTokensUrl) }
                    append(" and accept the ")
                    withLink(LinkAnnotation.Url(hfModelCardUrl, linkStyles)) { append(hfModelCardUrl) }
                    append(" license on the model card before downloading.")
                },
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

            // PR #57 — mobile↔desktop link. When on + paired + reachable, the chat
            // model runs on the paired desktop agent (priority over Ollama), and
            // conversations + memories sync. Sits above Ollama; greys it out when active.
            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
            DesktopLinkSection(
                state = state,
                onToggle = { viewModel.setAutoDesktopLinkEnabled(it) },
                onScanned = { viewModel.applyScannedLink(it) },
                onUnpair = { viewModel.unpairDesktop() },
                onDisconnectMobile = { viewModel.disconnectMobileDevice() },
                showUpgrade = viewModel.subscriptionAvailable,
                onUpgrade = { viewModel.upgradeToAnywhereAccess() },
                onManageSubscription = { viewModel.openSubscriptionSettings() },
            )

            // PR #56 — Remote Ollama server. When configured, the large chat
            // model runs on this server (text + images) instead of on-device;
            // the classifier, embedder, search and memory stay local.
            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
            OllamaServerSection(
                state = state,
                enabled = !state.desktopLinkActive,
                host = ollamaHost,
                port = ollamaPort,
                chatModel = ollamaChatModel,
                visionModel = ollamaVisionModel,
                serverType = ollamaServerType,
                useSsl = ollamaUseSsl,
                onHostChange = { ollamaHost = it },
                onPortChange = { ollamaPort = it.filter(Char::isDigit) },
                onChatModelChange = { ollamaChatModel = it },
                onVisionModelChange = { ollamaVisionModel = it },
                onServerTypeChange = {
                    ollamaServerType = it
                    viewModel.resetOllamaProbe()
                },
                onUseSslChange = {
                    ollamaUseSsl = it
                    viewModel.resetOllamaProbe()
                },
                onToggleEnabled = { viewModel.setOllamaEnabled(it) },
                onTest = { viewModel.testOllama(ollamaHost, ollamaPort, ollamaServerType, ollamaUseSsl) },
                onSave = {
                    viewModel.saveOllama(
                        ollamaHost, ollamaPort, ollamaChatModel, ollamaVisionModel,
                        ollamaServerType, ollamaUseSsl,
                    )
                },
                onClear = { viewModel.clearOllama() },
                onSaveApiKey = { viewModel.saveOllamaApiKey(it) },
                onClearApiKey = { viewModel.clearOllamaApiKey() },
            )

            // PR #63 — About lives at the very bottom; surfaces the running
            // build's identity (was a header-logo dialog in chat before).
            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
            AboutSection()
        }
    }
}

/**
 * PR #63 — About: the running agent's identity. `isDesktopPlatform` labels which
 * agent this is (each platform's Settings shows its own build); Version is the
 * semantic release tag, Build is VERSION_CODE (HEAD's commit timestamp), and Git
 * is `git describe` (SHA + `-dirty`) — read through the cross-platform
 * [AppBuildConfig] seam, the same source the former chat About dialog used.
 */
@Composable
private fun AboutSection() {
    val buildConfig = koinInject<AppBuildConfig>()
    SectionHeader("About")
    Text(
        text = if (isDesktopPlatform) "Desktop Agent" else "Mobile Agent",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Version ${buildConfig.versionName}\n" +
            "Build ${buildConfig.versionCode}\n" +
            "Git ${buildConfig.gitDescribe}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * PR #57 — the "Desktop Agent Connection" section. The toggle + status live in shared
 * UI; the pairing controls differ per platform (desktop shows a QR to scan;
 * mobile shows a "Scan desktop QR" button + camera), so they're an expect/actual.
 */
@Composable
private fun DesktopLinkSection(
    state: SettingsUiState,
    onToggle: (Boolean) -> Unit,
    onScanned: (String) -> Unit,
    onUnpair: () -> Unit,
    onDisconnectMobile: () -> Unit,
    showUpgrade: Boolean = false,
    onUpgrade: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
) {
    // The desktop HOSTS the link (shows a QR + the connected phone, no toggle); the
    // phone JOINS it (toggle + pairing copy + scanner). PR #57.
    if (isDesktopPlatform) {
        val anywhere = state.subscription.isActive // PR #74 — paid relay active
        SectionHeader("Mobile Agent Connection")
        Text(
            if (anywhere) {
                "Let the Mobile Agent app on your phone connect to this desktop anywhere. " +
                    "Scan the code below from the phone's Settings."
            } else {
                // PR #80 — the LAN path is gone; pairing requires an active subscription.
                "Connect the Mobile Agent app on your phone to this desktop from anywhere. " +
                    "Subscribe to anywhere access to show a pairing code here."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        // PR #74 — the upgrade / subscription-settings link. Hidden unless the
        // desktop has a configured gateway (showUpgrade); label + action flip once
        // a subscription is active.
        if (showUpgrade) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { if (anywhere) onManageSubscription() else onUpgrade() },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (anywhere) "Subscription Settings" else "Upgrade to anywhere connection")
            }
        }
        Spacer(Modifier.height(8.dp))
        ConnectedMobileRow(state = state, onDisconnect = onDisconnectMobile)
        Spacer(Modifier.height(8.dp))
        DesktopLinkPairingControls(state = state, onScanned = onScanned, onUnpair = onUnpair)
    } else {
        SectionHeaderWithToggle(
            title = "Desktop Agent Connection",
            checked = state.desktopLinkConfig.enabled,
            onCheckedChange = onToggle,
        )
        Text(
            "Pair this phone with your desktop agent over its secure gateway subscription. " +
                "While the link is on and reachable, chat runs on the desktop and your " +
                "conversations + memories stay in sync. When it's on, the Ollama server " +
                "below is disabled.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        DesktopLinkStatusRow(state)
        Spacer(Modifier.height(8.dp))
        DesktopLinkPairingControls(state = state, onScanned = onScanned, onUnpair = onUnpair)
    }
}

/**
 * Desktop-only: shows whether a phone is currently connected (green) or just
 * paired-but-offline (grey), with a Disconnect button that revokes the pairing
 * token so the phone drops and the user re-scans to reconnect.
 */
@Composable
private fun ConnectedMobileRow(state: SettingsUiState, onDisconnect: () -> Unit) {
    val paired = state.desktopLinkConfig.pairedDeviceId.isNotBlank()
    val (label, color) = when (state.mobileConnectionKind) {
        MobileLinkKind.RELAY -> "Phone connected via gateway" to Color(0xFF43A047)
        MobileLinkKind.NONE ->
            (if (paired) "Phone paired (offline)" else "No phone paired yet") to
                MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        if (paired || state.mobileConnectionKind != MobileLinkKind.NONE) {
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun DesktopLinkStatusRow(state: SettingsUiState) {
    val cfg = state.desktopLinkConfig
    val (label, color) = when {
        !cfg.enabled -> "Off" to MaterialTheme.colorScheme.outline
        !cfg.isPaired -> "No desktop paired" to MaterialTheme.colorScheme.outline
        state.desktopLinkStatus == DesktopLinkStatus.UP ->
            "Connected to gateway" to Color(0xFF43A047)
        else ->
            "Gateway unreachable" to Color(0xFFE53935)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OllamaServerSection(
    state: SettingsUiState,
    enabled: Boolean,
    host: String,
    port: String,
    chatModel: String,
    visionModel: String,
    serverType: RemoteServerType,
    useSsl: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onChatModelChange: (String) -> Unit,
    onVisionModelChange: (String) -> Unit,
    onServerTypeChange: (RemoteServerType) -> Unit,
    onUseSslChange: (Boolean) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
) {
    // PR #73 — explicit on/off inline with the header. Off keeps the saved server
    // details but routes chat back to the on-device model.
    SectionHeaderWithToggle(
        title = "Remote LLM Connection",
        checked = state.ollamaConfig.enabled,
        onCheckedChange = onToggleEnabled,
        toggleEnabled = enabled,
    )
    if (!enabled) {
        Text(
            "Disabled while Desktop Agent Connection is active.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
    }
    Text(
        "Run the chat model on a remote LLM server instead of this device. The " +
            "classifier, search and memory always stay on-device. Leave blank to use " +
            "the built-in model.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OllamaStatusRow(state, serverType)
    Spacer(Modifier.height(8.dp))
    // PR #73 — backend type. OpenAI-compatible forces SSL (locked checkbox below).
    ServerTypeDropdown(
        selected = serverType,
        enabled = enabled,
        onSelect = onServerTypeChange,
    )
    Spacer(Modifier.height(8.dp))
    val sslLocked = serverType == RemoteServerType.OPENAI
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = useSsl || sslLocked,
            onCheckedChange = onUseSslChange,
            enabled = enabled && !sslLocked,
        )
        Text(
            if (sslLocked) "Use SSL (https) — required for OpenAI-compatible" else "Use SSL (https)",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && !sslLocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    val isOpenAi = serverType == RemoteServerType.OPENAI
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            enabled = enabled,
            modifier = Modifier.weight(2f),
            // OpenAI-compatible takes a full base URL (port lives in the URL);
            // Ollama takes a bare host/IP with a separate port.
            label = { Text(if (isOpenAi) "Base URL" else "Host / IP") },
            placeholder = { Text(if (isOpenAi) "https://openrouter.ai/api/v1" else "192.168.1.50") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        if (!isOpenAi) {
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
        }
    }
    if (isOpenAi) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Enter the full base URL ending in the API path, e.g. " +
                "https://openrouter.ai/api/v1, https://api.openai.com/v1, or " +
                "http://localhost:1234/v1 for a local server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    // Port is only required for the Ollama backend; OpenAI carries it in the URL.
    // OpenAI also requires an API key before the server can be saved.
    val portReady = isOpenAi || port.isNotBlank()
    val apiKeyReady = !isOpenAi || state.hasOllamaApiKey
    Spacer(Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onTest,
            enabled = enabled && host.isNotBlank() && portReady && state.ollamaTestStatus != OllamaTestStatus.Testing,
        ) { Text("Test connection") }
        if (state.ollamaTestStatus == OllamaTestStatus.Testing) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp).padding(start = 4.dp))
        }
    }

    // Model pickers appear once a probe has returned models. The vision model is
    // optional — image turns fall back to the chat model when it's blank.
    if (state.ollamaModels.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        ModelDropdown(
            label = "Chat model",
            selected = chatModel,
            models = state.ollamaModels.map { it.name },
            onSelect = onChatModelChange,
        )
        Spacer(Modifier.height(8.dp))
        ModelDropdown(
            label = "Vision model (optional)",
            selected = visionModel,
            // Vision-capable models first; allow clearing the selection.
            models = listOf("") + state.ollamaModels.sortedByDescending { it.isVisionCapable }.map { it.name },
            onSelect = onVisionModelChange,
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onSave,
            enabled = enabled && host.isNotBlank() && portReady && chatModel.isNotBlank() && apiKeyReady,
        ) { Text("Save") }
        if (state.ollamaConfig.isConfigured) {
            OutlinedButton(onClick = onClear, enabled = enabled) { Text("Clear") }
        }
    }

    // PR #58 — API key, sent as `Authorization: Bearer` on every outbound request
    // (chat + the Test/health probe). Saved independently of the host/port above.
    // Optional for Ollama (unauthenticated LAN server is the default); REQUIRED for
    // an OpenAI-compatible server (PR #73).
    Spacer(Modifier.height(16.dp))
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    Text(
        when {
            state.hasOllamaApiKey -> "API key set — sent as a Bearer token on outbound requests."
            isOpenAi -> "API key required — an OpenAI-compatible server authenticates every request."
            else -> "No API key — requests use the server's default (no auth). Add one only " +
                "if your server requires it."
        },
        style = MaterialTheme.typography.bodySmall,
        color = when {
            state.hasOllamaApiKey -> MaterialTheme.colorScheme.primary
            isOpenAi -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = apiKeyInput,
        onValueChange = { apiKeyInput = it },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (isOpenAi) "API key" else "API key (optional)") },
        placeholder = { Text(if (state.hasOllamaApiKey) "Replace existing key" else "Paste key") },
        singleLine = true,
        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        trailingIcon = {
            Text(
                text = if (showApiKey) "Hide" else "Show",
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
            onClick = { onSaveApiKey(apiKeyInput); apiKeyInput = "" },
            enabled = enabled && apiKeyInput.isNotBlank(),
        ) { Text("Save key") }
        if (state.hasOllamaApiKey) {
            OutlinedButton(onClick = onClearApiKey, enabled = enabled) { Text("Clear key") }
        }
    }
}

@Composable
private fun OllamaStatusRow(state: SettingsUiState, serverType: RemoteServerType) {
    val cfg = state.ollamaConfig
    val (label, color) = when (state.ollamaTestStatus) {
        OllamaTestStatus.Testing -> "Connecting…" to MaterialTheme.colorScheme.outline
        OllamaTestStatus.Connected ->
            "Connected — ${state.ollamaModels.size} models found." to MaterialTheme.colorScheme.primary
        OllamaTestStatus.NoModels ->
            if (serverType == RemoteServerType.OPENAI) {
                "Reached the server, but it returned no models. Check the Base URL includes the " +
                    "full API path (e.g. it should end in /v1 or /api/v1)."
            } else {
                "Reached the server, but it has no models installed (try `ollama pull <model>`)."
            } to MaterialTheme.colorScheme.error
        OllamaTestStatus.Failed ->
            "Could not reach the server. Check the host, port, and that the server is running." to
                MaterialTheme.colorScheme.error
        OllamaTestStatus.Idle -> when {
            cfg.isConfigured && !cfg.enabled ->
                "Switched off — chat uses the on-device model (server details kept)." to
                    MaterialTheme.colorScheme.outline
            cfg.isActive ->
                "Using remote LLM at ${cfg.host}${cfg.port?.let { ":$it" } ?: ""} (${cfg.chatModel}) — " +
                    "on-device model disabled." to MaterialTheme.colorScheme.primary
            else -> "Not configured — chat uses the on-device model." to MaterialTheme.colorScheme.outline
        }
    }
    Text(label, style = MaterialTheme.typography.bodySmall, color = color)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    label: String,
    selected: String,
    models: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        AssistChip(
            onClick = { open = true },
            label = { Text(selected.ifBlank { "none" }) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name.ifBlank { "none" }) },
                    onClick = { onSelect(name); open = false },
                )
            }
        }
    }
}

/** PR #73 — backend-type picker: Ollama (default) or an OpenAI-compatible server. */
@Composable
private fun ServerTypeDropdown(
    selected: RemoteServerType,
    enabled: Boolean,
    onSelect: (RemoteServerType) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Server type: ", style = MaterialTheme.typography.bodyMedium)
        AssistChip(
            onClick = { open = true },
            enabled = enabled,
            label = { Text(selected.displayLabel()) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RemoteServerType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayLabel()) },
                    onClick = { onSelect(type); open = false },
                )
            }
        }
    }
}

private fun RemoteServerType.displayLabel(): String = when (this) {
    RemoteServerType.OLLAMA -> "Ollama"
    RemoteServerType.OPENAI -> "OpenAI-compatible"
}

private fun AppFontFamily.displayLabel(): String = when (this) {
    AppFontFamily.System -> "System default"
    AppFontFamily.SansSerif -> "Sans-serif"
    AppFontFamily.Serif -> "Serif"
    AppFontFamily.Monospace -> "Monospace"
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
private fun SectionHeaderWithToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = toggleEnabled)
    }
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
