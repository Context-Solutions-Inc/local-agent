package com.contextsolutions.mobileagent.app.ui.chat

import android.content.Intent
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.app.service.SessionState
import com.contextsolutions.mobileagent.app.ui.clock.ClockViewModel
import com.contextsolutions.mobileagent.app.ui.observability.SystemMemoryStatusViewModel
import com.contextsolutions.mobileagent.app.ui.theme.ThemeMode
import com.contextsolutions.mobileagent.app.ui.todo.TodoViewModel
import com.contextsolutions.mobileagent.app.ui.theme.ThemeModeViewModel
import com.contextsolutions.mobileagent.inference.Accelerator
import com.contextsolutions.mobileagent.inference.MemoryStatus
import com.contextsolutions.mobileagent.inference.ThermalStatus
import com.contextsolutions.mobileagent.search.SearchSource

/**
 * M2 chat surface — full conversation with streaming, web-search status, and
 * citation chips. Settings (key entry, search toggle, cache clear) is one tap
 * away in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenConversationMemory: (conversationId: String) -> Unit,
    onOpenTodos: () -> Unit,
    onOpenTimers: () -> Unit,
    onOpenAlarms: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    themeModeViewModel: ThemeModeViewModel = hiltViewModel(),
    clockViewModel: ClockViewModel = hiltViewModel(),
    todoViewModel: TodoViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val session by viewModel.sessionState.collectAsState()
    val themeMode by themeModeViewModel.mode.collectAsState()
    val timers by clockViewModel.timers.collectAsState()
    val alarms by clockViewModel.alarms.collectAsState()
    val activeTodoCount by todoViewModel.activeCount.collectAsState()
    var input by remember { mutableStateOf("") }
    // PR #48 — Android Photo Picker. No storage permission needed; returns a
    // content Uri the ViewModel decodes + downscales off the main thread.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) viewModel.onImagePicked(uri) }
    // PR #32 — About dialog: tapping the brand logo surfaces app name + the
    // build currently on the device (version name + commit-count build number).
    var showAbout by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // PR #9 — Sticky-to-bottom + jump-to-latest. Token streaming used to
    // fire animateScrollToItem on every chunk (keyed on partialText.length),
    // which fought any user-initiated scroll. New behaviour:
    //  - `isAtBottom` is derived from layoutInfo. True when the last item
    //    is fully in the viewport (no clipping at the bottom edge).
    //  - `stickyToBottom` tracks whether new tokens should auto-scroll.
    //  - Sticky disengages ONLY when the user actively drags the list
    //    away from the bottom — never just because the content grew past
    //    the viewport. Otherwise every token would briefly flip
    //    `isAtBottom` false before the next auto-scroll catches up, and
    //    sticky would flip off mid-stream.
    //  - Sticky re-engages whenever `isAtBottom` becomes true again
    //    (user manually scrolled back, or FAB tap finished).
    //  - The auto-scroll effect calls [followBottom] (defined below) so
    //    a streaming bubble taller than the viewport still scrolls to
    //    its actual bottom, not its top.
    //  - A SmallFloatingActionButton appears at the bottom-end of the
    //    list while sticky is off, letting the user jump back without
    //    having to manually scroll all the way down.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty() || info.totalItemsCount == 0) return@derivedStateOf true
            val last = visible.last()
            // Fully visible bottom item + no items below it = at-bottom.
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset
        }
    }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var stickyToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(isUserDragging, isAtBottom) {
        // Disengage only on a user-initiated drag that ends away from the
        // bottom. Content growing past the viewport during streaming
        // doesn't qualify — `isUserDragging` is false for programmatic scrolls.
        if (isUserDragging && !isAtBottom) stickyToBottom = false
    }
    LaunchedEffect(isAtBottom) {
        // Re-engage whenever the list is back at the bottom — covers both
        // "user manually scrolled back" and "auto-scroll just landed".
        if (isAtBottom) stickyToBottom = true
    }

    /** Total item count in the LazyColumn (chat bubbles + optional streaming bubble). */
    fun totalListItems(): Int =
        ui.messages.size + (if (ui.partialText.isNotEmpty() || ui.isGenerating || ui.searchStatus !is SearchStatus.None) 1 else 0)

    // Auto-scroll on new tokens / new messages — but only while sticky.
    //
    // The effect re-keys on partialText.length so each token chunk fires.
    // The body is a no-op when the user has scrolled away.
    //
    // `animateScrollToItem(lastIndex)` alone is NOT enough: it scrolls
    // until the item's TOP is at the viewport top. If the streaming
    // bubble is taller than the viewport, the new tokens accumulate
    // off-screen below. We therefore (a) make sure the last item is
    // rendered, and (b) push past any overflow of its bottom beyond
    // `viewportEndOffset`. Instant `scrollBy` instead of an animated
    // call so token streaming doesn't queue up overlapping animations.
    LaunchedEffect(ui.messages.size, ui.partialText.length, ui.isGenerating) {
        if (!stickyToBottom) return@LaunchedEffect
        val total = totalListItems()
        if (total > 0) listState.followBottom(lastIndex = total - 1, animate = false)
    }

    // M6 Phase E accessibility — announce a completed assistant response
    // exactly ONCE to TalkBack (not per token). The previous attempt put
    // liveRegion on the streaming bubble; that fired on every partial
    // update and re-read the entire growing string. The right signal is
    // "messages list grew AND newest entry is an Assistant" — that's the
    // transition from streaming to done. `lastSeenSize` survives in
    // remember within a composition, so route-flips (Chat → Settings →
    // Chat) reset it to the current size, suppressing re-announcement of
    // already-rendered history.
    val view = LocalView.current
    var lastSeenSize by remember { mutableIntStateOf(ui.messages.size) }
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.size > lastSeenSize) {
            val newest = ui.messages.lastOrNull()
            if (newest is UiMessage.Assistant && newest.text.isNotBlank()) {
                announceForAccessibilityCompat(view, newest.text)
            }
        }
        lastSeenSize = ui.messages.size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // PR #18 brand logo, now tappable (PR #32) — opens the
                    // About dialog so the running build is always identifiable.
                    IconButton(onClick = { showAbout = true }) {
                        Icon(
                            imageVector = Icons.Filled.SmartToy,
                            contentDescription = "About Mobile Agent",
                        )
                    }
                },
                // PR #44 — system-memory dot rides in the title slot so it
                // sits left-justified right after the app icon, leaving a gap
                // before the right-justified action icons. Green/yellow/red
                // bands mirror the watchdog + send-time gate thresholds in
                // SystemMemoryThresholds, so the dot the user sees can't
                // drift out of sync with what actually gates inference.
                title = { SystemMemoryStatusIndicator() },
                actions = {
                    // PR #44 — New Chat first on the right, Settings last.
                    // Order L→R: New Chat, TODO, Timer, Alarm, theme, Settings.
                    IconButton(onClick = { viewModel.newConversation() }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "New chat",
                        )
                    }
                    // PR #15 — TODO entry point. Count is folded into the
                    // accessibility label only; the visual badge was
                    // removed in PR #26.
                    ClockIconButton(
                        icon = Icons.Filled.Checklist,
                        contentDescription = "Todos ($activeTodoCount open)",
                        onClick = onOpenTodos,
                    )
                    // PR #11 — clock entry points. Always shown so the user
                    // can create the first timer/alarm.
                    ClockIconButton(
                        icon = Icons.Filled.Timer,
                        contentDescription = "Timers (${timers.size} active)",
                        onClick = onOpenTimers,
                    )
                    ClockIconButton(
                        icon = Icons.Filled.AccessAlarm,
                        contentDescription = "Alarms (${alarms.count { it.enabled }} active)",
                        onClick = onOpenAlarms,
                    )
                    ThemeModeToggle(
                        mode = themeMode,
                        onCycle = { themeModeViewModel.cycle() },
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
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

            // Wrap the LazyColumn in a Box so the "jump to latest" FAB can
            // overlay the bottom-end of the message list (not the input bar).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.messages.isEmpty() && ui.partialText.isEmpty() && !ui.isGenerating && ui.error == null) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Hello.",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = "Ask anything. The assistant runs on your device — your messages stay here. " +
                                    "It'll search the web automatically when it needs current info.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(ui.messages) { message ->
                    when (message) {
                        is UiMessage.User -> UserBubble(message.text, message.thumbnail, message.imageBytes)
                        is UiMessage.Assistant -> AssistantBubble(
                            text = message.text,
                            citations = message.citations,
                            fromCache = message.fromCache,
                            renderMarkdown = message.renderMarkdown,
                        )
                        is UiMessage.MemoryPrompt -> MemoryPromptCard(
                            text = message.text,
                            category = message.category,
                            onSave = { viewModel.saveMemoryPrompt(message.candidateId) },
                            onDismiss = { viewModel.dismissMemoryPrompt(message.candidateId) },
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

            // "Jump to latest" — appears once the user has scrolled away
            // from the bottom. Tapping it re-engages sticky-to-bottom and
            // animates back to the newest item. Plain conditional rather
            // than AnimatedVisibility to avoid scope ambiguity between
            // the outer ColumnScope and this BoxScope.
            if (!stickyToBottom && totalListItems() > 0) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            stickyToBottom = true
                            val total = totalListItems()
                            if (total > 0) listState.followBottom(
                                lastIndex = total - 1,
                                animate = true,
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Jump to latest",
                    )
                }
            }
            } // end overlay Box

            Spacer(Modifier.height(8.dp))
            if (ui.isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }

            // M6 Phase E — thermal warning (PRD §4.3). Banner at
            // MODERATE+; full block + disabled send at CRITICAL+.
            val thermal by viewModel.thermalStatus.collectAsState()
            ThermalBanner(thermal)

            // Enter inserts a newline (multiline prompts). Only the Send
            // button submits — KeyboardOptions intentionally omitted so the
            // IME shows its default newline key. `ChatViewModel.send` trims
            // leading/trailing whitespace; internal newlines are preserved
            // and Gemma handles them natively (chat templating already
            // expects multiline user turns).
            // PR #48 — staged-image chip: thumbnail + remove button, shown
            // above the input while a photo is attached to the next send.
            ui.pendingImageThumbnail?.let { thumb ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = thumb,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    IconButton(onClick = { viewModel.clearPickedImage() }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove image")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ask anything…") },
                enabled = !ui.isGenerating && !thermal.isBlocking,
                minLines = 1,
                maxLines = 6,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // PR #48 — attach a photo from the gallery.
                IconButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !ui.isGenerating && !thermal.isBlocking,
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Attach image")
                }
                Button(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                    // Allow an image-only send (no text) when a photo is staged.
                    enabled = !ui.isGenerating && !thermal.isBlocking &&
                        (input.isNotBlank() || ui.pendingImageThumbnail != null),
                ) {
                    Text("Send")
                }
                if (ui.isGenerating) {
                    // PR #22 — two-stage feedback. The moment the user taps,
                    // `isCancelling` flips synchronously and we re-render as
                    // a disabled "Cancelling…" button. Without this, the
                    // button stays active and clickable while the native
                    // decode loop aborts (tens of ms to a few hundred ms),
                    // so repeated taps queue up as additional cancel signals
                    // on an already-cancelled job and the UI looks frozen.
                    OutlinedButton(
                        onClick = { viewModel.cancel() },
                        enabled = !ui.isCancelling,
                    ) {
                        Text(if (ui.isCancelling) "Cancelling…" else "Cancel")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // PR#13 — context-full warning. Fires once per conversation: the
        // first time the user's pending send would push history past the
        // 8K KV-cache budget. After they tap Continue, the conversation row
        // records the acknowledgement and subsequent overflows truncate
        // silently. Picking "Start new" discards the pending prompt and
        // clears the chat.
        val overflow by viewModel.overflowDecision.collectAsState()
        overflow?.let { decision ->
            AlertDialog(
                onDismissRequest = { /* require explicit choice */ },
                title = { Text("Conversation limit reached") },
                text = {
                    Text(
                        "This conversation has reached the maximum context length. " +
                            "Continue to send your message — the oldest message pair " +
                            "will be permanently removed — or start a new conversation.\n\n" +
                            "Your message:\n\"${decision.pendingPrompt}\"",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.continueAfterOverflow() }) {
                        Text("Continue")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissOverflowStartNew() }) {
                        Text("Start new conversation")
                    }
                },
            )
        }

        // PR#46 — hard memory cap reached. A save was refused because the
        // store is full; the consent card (if any) stays in place so the user
        // can retry after deleting memories in Settings → Memory.
        val memoryLimit by viewModel.memoryLimitReached.collectAsState()
        memoryLimit?.let { limit ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissMemoryLimitDialog() },
                title = { Text("Memory limit reached") },
                text = {
                    Text(
                        "You've saved the maximum of $limit memories. " +
                            "Delete some in Settings → Memory to save new ones.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissMemoryLimitDialog() }) {
                        Text("OK")
                    }
                },
            )
        }

        // The PR #16 low-memory submission gate has been removed — the
        // user is no longer blocked when system free RAM is low. The
        // header SystemMemoryStatusIndicator (red LED) is the sole signal
        // for the condition.

        // PR #32 — About dialog. Version name is the semantic release tag;
        // build number is VERSION_CODE = HEAD's commit timestamp (see
        // androidApp/build.gradle.kts). GIT_DESCRIBE (SHA + `-dirty`) added in
        // PR #50 disambiguates working-tree dev builds whose versionCode hasn't
        // bumped; the same identity prints at build time for easy comparison.
        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("Mobile Agent") },
                text = {
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}\n" +
                            "Build ${BuildConfig.VERSION_CODE}\n" +
                            "Git ${BuildConfig.GIT_DESCRIBE}",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showAbout = false }) {
                        Text("OK")
                    }
                },
            )
        }
    }
}

@Composable
private fun ClockIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
private fun UserBubble(text: String, thumbnail: ImageBitmap? = null, imageBytes: ByteArray? = null) {
    // PR #48 set a decoded [thumbnail] on a live send. PR #49 persists the
    // photo, so a resumed conversation arrives with JPEG [imageBytes] instead —
    // decoded on demand here (off the main thread). LazyColumn disposes
    // off-screen items, so the decoded bitmap is released when the bubble
    // scrolls away; only currently-visible photos hold a bitmap at once.
    val decoded: ImageBitmap? = thumbnail ?: imageBytes?.let { bytes ->
        produceState<ImageBitmap?>(initialValue = null, bytes) {
            value = withContext(Dispatchers.Default) { ImagePreprocessor.decodeThumbnail(bytes) }
        }.value
    }
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
            Column {
                // Attached photo: live thumbnail (PR #48) or the persisted JPEG
                // decoded on resume (PR #49).
                decoded?.let { thumb ->
                    Image(
                        bitmap = thumb,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    if (text.isNotEmpty()) Spacer(Modifier.height(6.dp))
                }
                // SelectionContainer enables long-press text selection; the
                // platform shows its standard floating toolbar (Copy / Select
                // all / Share) — no custom menu needed.
                if (text.isNotEmpty()) {
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    citations: List<SearchSource>,
    fromCache: Boolean,
    renderMarkdown: Boolean,
) {
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
            // LLM answers render as markdown + LaTeX math (PR #50); the
            // deterministic weather/finance cards (renderMarkdown=false) keep
            // the plain selectable Text so their layout/`$` aren't reparsed.
            if (renderMarkdown) {
                MarkdownMathText(text)
            } else {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
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
    // No liveRegion here — the partial text grows by tokens, and a live
    // region would re-announce the growing string on every update,
    // making TalkBack unusable on streamed responses. The completed
    // assistant message is announced ONCE via `View.announceForAccessibility`
    // from the ChatScreen-level LaunchedEffect that watches messages.size.
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
                SelectionContainer {
                    Text(partial, style = MaterialTheme.typography.bodyMedium)
                }
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

@Composable
private fun SystemMemoryStatusIndicator(
    viewModel: SystemMemoryStatusViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsState()
    // All three bands use fixed Material colors rather than theme slots —
    // `colorScheme.primary` adapts to wallpaper under Material You (so
    // "green" can render blue/purple) and `colorScheme.error` desaturates
    // to pink on dark surfaces. A status dot needs to stay semantically
    // red/yellow/green regardless of theme.
    val (color, desc) = when (status) {
        MemoryStatus.Green -> Color(0xFF43A047) to "System memory: healthy"
        MemoryStatus.Yellow -> Color(0xFFFFA000) to "System memory: caution"
        MemoryStatus.Red -> Color(0xFFE53935) to "System memory: low"
    }
    Icon(
        imageVector = Icons.Filled.Circle,
        contentDescription = desc,
        tint = color,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(12.dp),
    )
}

@Composable
private fun ThemeModeToggle(mode: ThemeMode, onCycle: () -> Unit) {
    val (icon, label) = when (mode) {
        ThemeMode.System -> Icons.Filled.BrightnessAuto to "Theme: follow system (tap for light)"
        ThemeMode.Light -> Icons.Filled.BrightnessHigh to "Theme: light (tap for dark)"
        ThemeMode.Dark -> Icons.Filled.Brightness4 to "Theme: dark (tap to follow system)"
    }
    IconButton(onClick = onCycle) {
        Icon(imageVector = icon, contentDescription = label)
    }
}

/**
 * Scroll the list so the bottom edge of [lastIndex] sits at the bottom
 * edge of the viewport, even if that item is taller than the viewport
 * itself. `animateScrollToItem(index)` alone is not enough — it stops
 * once the item's TOP is at the viewport top, leaving the rest of a
 * tall streaming bubble off-screen below.
 *
 * Two-step:
 *  1. Ensure [lastIndex] is rendered (`scrollToItem` if it isn't yet).
 *  2. Read `layoutInfo` and push past any overflow of the item's bottom
 *     beyond `viewportEndOffset`.
 *
 * Set [animate] = false for the auto-scroll path (token streaming —
 * overlapping animations would queue up and lag). Set true for the
 * user-facing FAB tap so the scroll has visible feedback.
 */
private suspend fun LazyListState.followBottom(lastIndex: Int, animate: Boolean) {
    if (lastIndex < 0) return
    val needsJump = layoutInfo.visibleItemsInfo.none { it.index == lastIndex }
    if (needsJump) {
        if (animate) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    }
    val info = layoutInfo
    val last = info.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
    val overflow = (last.offset + last.size) - info.viewportEndOffset
    if (overflow > 0) {
        if (animate) animateScrollBy(overflow.toFloat()) else scrollBy(overflow.toFloat())
    }
}

// `View.announceForAccessibility` is deprecated in API 36 but is still the
// canonical one-shot TalkBack announcement primitive (invariant #26 — a
// `liveRegion` on the streaming bubble re-reads the whole growing string).
// Isolated here so the deprecation suppression stays narrowly scoped.
@Suppress("DEPRECATION")
private fun announceForAccessibilityCompat(view: View, text: CharSequence) {
    view.announceForAccessibility(text)
}
