package com.contextsolutions.localagent.ui.settings

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
import com.contextsolutions.localagent.inference.DesktopLinkStatus
import com.contextsolutions.localagent.link.MobileLinkPresence
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
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.language.PreferredLanguage
import com.contextsolutions.localagent.platform.AppBuildConfig
import com.contextsolutions.localagent.preferences.OllamaConfig
import com.contextsolutions.localagent.preferences.RemoteServerType
import com.contextsolutions.localagent.ui.i18n.tr
import com.contextsolutions.localagent.ui.platform.isDesktopPlatform
import com.contextsolutions.localagent.ui.theme.AppFontFamily
import com.contextsolutions.localagent.ui.theme.FontScale
import com.contextsolutions.localagent.ui.theme.ThemeMode
import com.contextsolutions.localagent.ui.theme.ThemeModeViewModel
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(StringKeys.SETTINGS_TITLE)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(StringKeys.COMMON_BACK))
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
            SectionHeader(tr(StringKeys.SETTINGS_CONVERSATIONS_HEADER))
            Text(
                tr(StringKeys.SETTINGS_CONVERSATIONS_DESC),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenConversationHistory) { Text(tr(StringKeys.SETTINGS_CONVERSATIONS_MANAGE)) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            SectionHeaderWithToggle(
                title = tr(StringKeys.SETTINGS_WEB_SEARCH_HEADER),
                checked = state.searchEnabled,
                onCheckedChange = { viewModel.setSearchEnabled(it) },
            )
            Text(
                tr(StringKeys.SETTINGS_WEB_SEARCH_DESC),
                style = MaterialTheme.typography.bodySmall,
            )
            // The toggle alone isn't enough — search needs a Brave API key too.
            // Surface that here (tied to the control the user just flipped) so an
            // enabled-but-keyless config doesn't silently fall back to
            // SearchDisabled. Mirrors the gate: isAvailable() = enabled && hasKey.
            if (state.searchEnabled && !state.hasUserKey && !state.hasDevKey) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(StringKeys.SETTINGS_WEB_SEARCH_NO_KEY),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            SectionHeader(tr(StringKeys.SETTINGS_CACHE_HEADER))
            val countLabel = when {
                state.cacheCount < 0 -> tr(StringKeys.SETTINGS_CACHE_LOADING)
                state.cacheCount == 0L -> tr(StringKeys.SETTINGS_CACHE_EMPTY)
                else -> tr(StringKeys.SETTINGS_CACHE_ENTRIES, state.cacheCount)
            }
            Text(
                tr(StringKeys.SETTINGS_CACHE_DESC, countLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.clearCache() }) { Text(tr(StringKeys.SETTINGS_CACHE_CLEAR)) }
            if (state.cacheJustCleared) {
                LaunchedEffect(Unit) {
                    viewModel.acknowledgeCacheCleared()
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(StringKeys.SETTINGS_CACHE_CLEARED),
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
            SectionHeader(tr(StringKeys.SETTINGS_SOURCES_HEADER))
            Text(
                tr(StringKeys.SETTINGS_SOURCES_DESC),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSearchSources) { Text(tr(StringKeys.SETTINGS_SOURCES_MANAGE)) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            SectionHeader(tr(StringKeys.SETTINGS_MEMORY_HEADER))
            val memoryCountLabel = when (state.memoryCount) {
                -1 -> tr(StringKeys.SETTINGS_MEMORY_LOADING)
                0 -> tr(StringKeys.SETTINGS_MEMORY_NONE)
                1 -> tr(StringKeys.SETTINGS_MEMORY_ONE)
                else -> tr(StringKeys.SETTINGS_MEMORY_MANY, state.memoryCount)
            }
            val creationLabel = if (state.memoryCreationEnabled) {
                tr(StringKeys.SETTINGS_MEMORY_CREATION_ON)
            } else {
                tr(StringKeys.SETTINGS_MEMORY_CREATION_OFF)
            }
            Text(
                tr(StringKeys.SETTINGS_MEMORY_SUMMARY, memoryCountLabel, creationLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenMemoryManagement) { Text(tr(StringKeys.SETTINGS_MEMORY_MANAGE)) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            // Theme selector (PR #59). Moved here from the Chat top-bar icon and
            // made a three-way segmented control. Drives ThemeModeViewModel.setMode,
            // which MainActivity (Android) and Main.kt (desktop) observe to flip the
            // MaterialTheme colorScheme. "Auto" follows the OS dark-mode setting.
            SectionHeader(tr(StringKeys.SETTINGS_APPEARANCE_HEADER))
            Text(
                tr(StringKeys.SETTINGS_APPEARANCE_DESC),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val themeOptions = listOf(
                ThemeMode.Light to tr(StringKeys.SETTINGS_APPEARANCE_LIGHT),
                ThemeMode.System to tr(StringKeys.SETTINGS_APPEARANCE_AUTO),
                ThemeMode.Dark to tr(StringKeys.SETTINGS_APPEARANCE_DARK),
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

            // Response-language picker (re-added; was removed in PR #44). Drives
            // SettingsViewModel.setPreferredLanguage, read by ChatViewModel on the
            // next turn. Sits between the theme control and the font row.
            Spacer(Modifier.height(12.dp))
            LanguageDropdown(
                selected = state.preferredLanguage,
                onSelect = { viewModel.setPreferredLanguage(it) },
            )

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
                Text(tr(StringKeys.SETTINGS_FONT_LABEL), style = MaterialTheme.typography.bodyMedium)
                var fontMenuOpen by remember { mutableStateOf(false) }
                AssistChip(
                    onClick = { fontMenuOpen = true },
                    label = { Text(fontFamily.trLabel()) },
                )
                DropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                    AppFontFamily.entries.forEach { family ->
                        DropdownMenuItem(
                            text = { Text(family.trLabel()) },
                            onClick = { themeModeViewModel.setFontFamily(family); fontMenuOpen = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                tr(StringKeys.SETTINGS_FONT_SIZE, (fontScale * 100).roundToInt()),
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
                tr(StringKeys.SETTINGS_FONT_PREVIEW),
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
                title = tr(StringKeys.SETTINGS_TELEMETRY_HEADER),
                checked = state.telemetryEnabled,
                onCheckedChange = { viewModel.setTelemetryEnabled(it) },
            )
            Text(
                if (state.telemetryEnabled) {
                    tr(StringKeys.SETTINGS_TELEMETRY_ON)
                } else {
                    tr(StringKeys.SETTINGS_TELEMETRY_OFF)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr(StringKeys.SETTINGS_TELEMETRY_DETAIL),
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
            SectionHeader(tr(StringKeys.SETTINGS_BRAVE_HEADER))
            val braveUrl = "https://brave.com/search/api/"
            Text(
                buildAnnotatedString {
                    append(tr(StringKeys.SETTINGS_BRAVE_DESC_PRE))
                    withLink(LinkAnnotation.Url(braveUrl, linkStyles)) { append(braveUrl) }
                    append(tr(StringKeys.SETTINGS_BRAVE_DESC_POST))
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
                label = { Text(tr(StringKeys.SETTINGS_BRAVE_FIELD_LABEL)) },
                placeholder = {
                    Text(
                        if (state.hasUserKey) {
                            tr(StringKeys.SETTINGS_BRAVE_PLACEHOLDER_REPLACE)
                        } else {
                            tr(StringKeys.SETTINGS_BRAVE_PLACEHOLDER_PASTE)
                        },
                    )
                },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    Text(
                        text = if (showKey) tr(StringKeys.COMMON_HIDE) else tr(StringKeys.COMMON_SHOW),
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
                ) { Text(tr(StringKeys.COMMON_SAVE)) }
                if (state.hasUserKey) {
                    OutlinedButton(onClick = { viewModel.clearBraveKey() }) { Text(tr(StringKeys.COMMON_CLEAR)) }
                }
                OutlinedButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) tr(StringKeys.COMMON_MASK) else tr(StringKeys.COMMON_REVEAL))
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
                onPairNow = { viewModel.requestPairing() },
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
    SectionHeader(tr(StringKeys.SETTINGS_ABOUT_HEADER))
    Text(
        text = if (isDesktopPlatform) {
            tr(StringKeys.SETTINGS_ABOUT_DESKTOP_AGENT)
        } else {
            tr(StringKeys.SETTINGS_ABOUT_LOCAL_AGENT)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = tr(
            StringKeys.SETTINGS_ABOUT_BUILD_INFO,
            buildConfig.versionName,
            buildConfig.versionCode,
            buildConfig.gitDescribe,
        ),
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
    onPairNow: () -> Unit = {},
    showUpgrade: Boolean = false,
    onUpgrade: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
) {
    // The desktop HOSTS the link (shows a QR + the connected phone, no toggle); the
    // phone JOINS it (toggle + pairing copy + scanner). PR #57.
    if (isDesktopPlatform) {
        val anywhere = state.subscription.isActive // PR #74 — paid relay active
        SectionHeader(tr(StringKeys.SETTINGS_LINK_DESKTOP_HEADER))
        Text(
            if (anywhere) {
                tr(StringKeys.SETTINGS_LINK_DESKTOP_DESC_ANYWHERE)
            } else {
                // PR #80 — the LAN path is gone; pairing requires an active subscription.
                tr(StringKeys.SETTINGS_LINK_DESKTOP_DESC_SUBSCRIBE)
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
                Text(
                    if (anywhere) {
                        tr(StringKeys.SETTINGS_LINK_SUBSCRIPTION_SETTINGS)
                    } else {
                        tr(StringKeys.SETTINGS_LINK_UPGRADE)
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ConnectedMobileRow(state = state, onDisconnect = onDisconnectMobile)
        Spacer(Modifier.height(8.dp))
        DesktopLinkPairingControls(
            state = state,
            onScanned = onScanned,
            onUnpair = onUnpair,
            onPairNow = onPairNow,
        )
    } else {
        SectionHeaderWithToggle(
            title = tr(StringKeys.SETTINGS_LINK_MOBILE_HEADER),
            checked = state.desktopLinkConfig.enabled,
            onCheckedChange = onToggle,
        )
        Text(
            tr(StringKeys.SETTINGS_LINK_MOBILE_DESC),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        DesktopLinkStatusRow(state)
        Spacer(Modifier.height(8.dp))
        DesktopLinkPairingControls(
            state = state,
            onScanned = onScanned,
            onUnpair = onUnpair,
            onPairNow = onPairNow,
        )
    }
}

/**
 * Desktop-only: shows whether a phone is currently connected (green) or just
 * paired-but-offline (grey), with a Disconnect button that revokes the pairing
 * token so the phone drops and the user re-scans to reconnect.
 */
@Composable
private fun ConnectedMobileRow(state: SettingsUiState, onDisconnect: () -> Unit) {
    // PR #90 — drive label + Disconnect off the relay's 3-state presence, NOT the
    // LAN-era pairedDeviceId (never set on the relay path, so an offline phone used to
    // read as "No phone paired yet" with no Disconnect).
    val (label, color) = when (state.mobilePresence) {
        MobileLinkPresence.CONNECTED ->
            tr(StringKeys.SETTINGS_LINK_MOBILE_CONNECTED) to Color(0xFF43A047)
        MobileLinkPresence.OFFLINE ->
            tr(StringKeys.SETTINGS_LINK_MOBILE_OFFLINE) to MaterialTheme.colorScheme.outline
        MobileLinkPresence.UNPAIRED ->
            tr(StringKeys.SETTINGS_LINK_MOBILE_UNPAIRED) to MaterialTheme.colorScheme.outline
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
        if (state.mobilePresence != MobileLinkPresence.UNPAIRED) {
            OutlinedButton(onClick = onDisconnect) { Text(tr(StringKeys.SETTINGS_LINK_DISCONNECT)) }
        }
    }
}

@Composable
private fun DesktopLinkStatusRow(state: SettingsUiState) {
    val cfg = state.desktopLinkConfig
    val (label, color) = when {
        !cfg.enabled -> tr(StringKeys.SETTINGS_LINK_STATUS_OFF) to MaterialTheme.colorScheme.outline
        !cfg.isPaired -> tr(StringKeys.SETTINGS_LINK_STATUS_NO_DESKTOP) to MaterialTheme.colorScheme.outline
        state.desktopLinkStatus == DesktopLinkStatus.UP ->
            tr(StringKeys.SETTINGS_LINK_STATUS_CONNECTED) to Color(0xFF43A047)
        else ->
            tr(StringKeys.SETTINGS_LINK_STATUS_UNREACHABLE) to Color(0xFFE53935)
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
        title = tr(StringKeys.SETTINGS_OLLAMA_HEADER),
        checked = state.ollamaConfig.enabled,
        onCheckedChange = onToggleEnabled,
        toggleEnabled = enabled,
    )
    if (!enabled) {
        Text(
            tr(StringKeys.SETTINGS_OLLAMA_DISABLED_BY_LINK),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
    }
    Text(
        tr(StringKeys.SETTINGS_OLLAMA_DESC),
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
    // L3 (UI gate) — a saved API key must never ride cleartext HTTP, so once a key
    // is stored SSL can't be turned off. OPENAI already locks SSL on, so this only
    // adds the lock for OLLAMA. The user must clear the saved key first (warning below).
    val sslLockedByKey = state.hasOllamaApiKey && !sslLocked
    // Keep the form's useSsl consistent with the forced-on display so a later
    // server-config save can't persist a cleartext config alongside a saved key.
    LaunchedEffect(sslLockedByKey) {
        if (sslLockedByKey && !useSsl) onUseSslChange(true)
    }
    val sslForcedOn = sslLocked || sslLockedByKey
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = useSsl || sslForcedOn,
            onCheckedChange = onUseSslChange,
            enabled = enabled && !sslForcedOn,
        )
        Text(
            if (sslLocked) tr(StringKeys.SETTINGS_OLLAMA_SSL_LOCKED) else tr(StringKeys.SETTINGS_OLLAMA_SSL),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && !sslForcedOn) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
    if (sslLockedByKey) {
        Text(
            tr(StringKeys.SETTINGS_OLLAMA_SSL_LOCKED_BY_KEY),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 48.dp),
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
            label = { Text(if (isOpenAi) tr(StringKeys.SETTINGS_OLLAMA_BASE_URL) else tr(StringKeys.SETTINGS_OLLAMA_HOST)) },
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
                label = { Text(tr(StringKeys.SETTINGS_OLLAMA_PORT)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
        }
    }
    if (isOpenAi) {
        Spacer(Modifier.height(4.dp))
        Text(
            tr(StringKeys.SETTINGS_OLLAMA_OPENAI_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    // Security L1: warn when the connection will be cleartext HTTP (Ollama, SSL off, and the
    // host isn't an explicit https:// URL) — the LAN Ollama path is plain HTTP and MITM-able.
    // Mirrors OllamaConfig.baseUrl()'s scheme logic.
    val willUseCleartext = !isOpenAi && !(useSsl || sslForcedOn) &&
        host.isNotBlank() && !host.trim().lowercase().startsWith("https://")
    if (willUseCleartext) {
        Spacer(Modifier.height(4.dp))
        Text(
            tr(StringKeys.SETTINGS_OLLAMA_HTTP_WARNING),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
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
        ) { Text(tr(StringKeys.SETTINGS_OLLAMA_TEST)) }
        if (state.ollamaTestStatus == OllamaTestStatus.Testing) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp).padding(start = 4.dp))
        }
    }

    // Model pickers appear once a probe has returned models. The vision model is
    // optional — image turns fall back to the chat model when it's blank.
    if (state.ollamaModels.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        ModelDropdown(
            label = tr(StringKeys.SETTINGS_OLLAMA_CHAT_MODEL),
            selected = chatModel,
            models = state.ollamaModels.map { it.name },
            onSelect = onChatModelChange,
        )
        Spacer(Modifier.height(8.dp))
        ModelDropdown(
            label = tr(StringKeys.SETTINGS_OLLAMA_VISION_MODEL),
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
        ) { Text(tr(StringKeys.COMMON_SAVE)) }
        if (state.ollamaConfig.isConfigured) {
            OutlinedButton(onClick = onClear, enabled = enabled) { Text(tr(StringKeys.COMMON_CLEAR)) }
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
            state.hasOllamaApiKey -> tr(StringKeys.SETTINGS_OLLAMA_APIKEY_SET)
            isOpenAi -> tr(StringKeys.SETTINGS_OLLAMA_APIKEY_REQUIRED)
            else -> tr(StringKeys.SETTINGS_OLLAMA_APIKEY_NONE)
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
        label = { Text(if (isOpenAi) tr(StringKeys.SETTINGS_OLLAMA_APIKEY_LABEL) else tr(StringKeys.SETTINGS_OLLAMA_APIKEY_LABEL_OPTIONAL)) },
        placeholder = {
            Text(
                if (state.hasOllamaApiKey) {
                    tr(StringKeys.SETTINGS_OLLAMA_APIKEY_PLACEHOLDER_REPLACE)
                } else {
                    tr(StringKeys.SETTINGS_OLLAMA_APIKEY_PLACEHOLDER_PASTE)
                },
            )
        },
        singleLine = true,
        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        trailingIcon = {
            Text(
                text = if (showApiKey) tr(StringKeys.COMMON_HIDE) else tr(StringKeys.COMMON_SHOW),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
    // L3 (UI gate) — an API key may only be saved when SSL/HTTPS is on, so the key
    // is never sent over cleartext HTTP. OPENAI implies SSL (locked on), so this only
    // blocks an OLLAMA server with the checkbox off. Mirrors the runtime refusal in
    // OllamaInferenceEngine/OllamaClient.
    val sslEnabledForKey = useSsl || isOpenAi
    val keyBlockedBySsl = apiKeyInput.isNotBlank() && !sslEnabledForKey
    if (keyBlockedBySsl) {
        Spacer(Modifier.height(4.dp))
        Text(
            tr(StringKeys.SETTINGS_OLLAMA_APIKEY_REQUIRES_SSL),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onSaveApiKey(apiKeyInput); apiKeyInput = "" },
            enabled = enabled && apiKeyInput.isNotBlank() && sslEnabledForKey,
        ) { Text(tr(StringKeys.SETTINGS_OLLAMA_SAVE_KEY)) }
        if (state.hasOllamaApiKey) {
            OutlinedButton(onClick = onClearApiKey, enabled = enabled) { Text(tr(StringKeys.SETTINGS_OLLAMA_CLEAR_KEY)) }
        }
    }
}

@Composable
private fun OllamaStatusRow(state: SettingsUiState, serverType: RemoteServerType) {
    val cfg = state.ollamaConfig
    val (label, color) = when (state.ollamaTestStatus) {
        OllamaTestStatus.Testing -> tr(StringKeys.SETTINGS_OLLAMA_STATUS_CONNECTING) to MaterialTheme.colorScheme.outline
        OllamaTestStatus.Connected ->
            tr(StringKeys.SETTINGS_OLLAMA_STATUS_CONNECTED, state.ollamaModels.size) to MaterialTheme.colorScheme.primary
        OllamaTestStatus.NoModels ->
            if (serverType == RemoteServerType.OPENAI) {
                tr(StringKeys.SETTINGS_OLLAMA_STATUS_NO_MODELS_OPENAI)
            } else {
                tr(StringKeys.SETTINGS_OLLAMA_STATUS_NO_MODELS)
            } to MaterialTheme.colorScheme.error
        OllamaTestStatus.Failed ->
            tr(StringKeys.SETTINGS_OLLAMA_STATUS_FAILED) to
                MaterialTheme.colorScheme.error
        OllamaTestStatus.Idle -> when {
            cfg.isConfigured && !cfg.enabled ->
                tr(StringKeys.SETTINGS_OLLAMA_STATUS_OFF) to
                    MaterialTheme.colorScheme.outline
            cfg.isActive ->
                tr(
                    StringKeys.SETTINGS_OLLAMA_STATUS_ACTIVE,
                    "${cfg.host}${cfg.port?.let { ":$it" } ?: ""}",
                    cfg.chatModel,
                ) to MaterialTheme.colorScheme.primary
            else -> tr(StringKeys.SETTINGS_OLLAMA_STATUS_NOT_CONFIGURED) to MaterialTheme.colorScheme.outline
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
    val noneLabel = tr(StringKeys.COMMON_NONE)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        AssistChip(
            onClick = { open = true },
            label = { Text(selected.ifBlank { noneLabel }) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name.ifBlank { noneLabel }) },
                    onClick = { onSelect(name); open = false },
                )
            }
        }
    }
}

/**
 * Response-language picker (re-added after PR #44). Mirrors [ServerTypeDropdown]:
 * a leading label + an [AssistChip] that opens a [DropdownMenu] of every
 * [PreferredLanguage]. The chip/menu text is a structural concat of the entry's
 * native + English names (data fields, not localized copy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selected: PreferredLanguage,
    onSelect: (PreferredLanguage) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr(StringKeys.SETTINGS_LANGUAGE_LABEL), style = MaterialTheme.typography.bodyMedium)
        AssistChip(
            onClick = { open = true },
            label = { Text("${selected.nativeName} · ${selected.englishName}") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PreferredLanguage.selectable.forEach { lang ->
                DropdownMenuItem(
                    text = { Text("${lang.nativeName} · ${lang.englishName}") },
                    onClick = { onSelect(lang); open = false },
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
        Text(tr(StringKeys.SETTINGS_OLLAMA_SERVER_TYPE_LABEL), style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun AppFontFamily.trLabel(): String = when (this) {
    AppFontFamily.System -> tr(StringKeys.SETTINGS_FONT_SYSTEM)
    AppFontFamily.SansSerif -> tr(StringKeys.SETTINGS_FONT_SANS)
    AppFontFamily.Serif -> tr(StringKeys.SETTINGS_FONT_SERIF)
    AppFontFamily.Monospace -> tr(StringKeys.SETTINGS_FONT_MONOSPACE)
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
        state.hasUserKey -> tr(StringKeys.SETTINGS_BRAVE_STATUS_USER) to MaterialTheme.colorScheme.primary
        state.hasDevKey -> tr(StringKeys.SETTINGS_BRAVE_STATUS_DEV) to MaterialTheme.colorScheme.outline
        else -> tr(StringKeys.SETTINGS_BRAVE_STATUS_NONE) to MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.bodySmall, color = color)
}
