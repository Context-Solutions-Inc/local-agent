package com.contextsolutions.localagent.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.inference.SessionState
import com.contextsolutions.localagent.ui.i18n.tr
import com.contextsolutions.localagent.ui.clock.ClockViewModel
import com.contextsolutions.localagent.ui.icons.RuleSettingsIcon
import com.contextsolutions.localagent.ui.platform.isDesktopPlatform
import com.contextsolutions.localagent.ui.markdown.MarkdownMath
import com.contextsolutions.localagent.ui.mylist.MyListViewModel
import com.contextsolutions.localagent.ui.util.AccessibilityAnnouncer
import com.contextsolutions.localagent.ui.util.decodeImageBitmap
import com.contextsolutions.localagent.ui.util.rememberAccessibilityAnnouncer
import com.contextsolutions.localagent.ui.util.urlHost
import com.contextsolutions.localagent.inference.Accelerator
import com.contextsolutions.localagent.inference.MemoryStatus
import com.contextsolutions.localagent.inference.DesktopLinkStatus
import com.contextsolutions.localagent.inference.DesktopLinkStatusProvider
import com.contextsolutions.localagent.inference.SystemMemoryStatusProvider
import com.contextsolutions.localagent.inference.ThermalStatus
import com.contextsolutions.localagent.job.JobBadge
import com.contextsolutions.localagent.platform.UrlOpener
import com.contextsolutions.localagent.search.SearchSource
import com.contextsolutions.localagent.voice.Dictation
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

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
    onOpenMyList: () -> Unit,
    onOpenTimers: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenJobs: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
    clockViewModel: ClockViewModel = koinViewModel(),
    myListViewModel: MyListViewModel = koinViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val session by viewModel.sessionState.collectAsState()
    val timers by clockViewModel.timers.collectAsState()
    val alarms by clockViewModel.alarms.collectAsState()
    val activeMyListCount by myListViewModel.activeCount.collectAsState()
    var input by remember { mutableStateOf("") }
    // PR #67 — `input` is what the box SHOWS (and the user can type into);
    // `committedInput` is the stable text minus any in-flight dictation partial.
    // While the user speaks, the live transcript is rendered as
    // `committedInput (+ space) + partial` so words appear immediately and keep
    // changing as the engine refines them; the final transcript (or a manual
    // edit) promotes into `committedInput`. They stay equal whenever no partial
    // is in flight.
    var committedInput by remember { mutableStateOf("") }
    // PR #48 — image picker behind the cross-platform :ui seam (Phase 9 inc 8c).
    // The actual decodes + downscales the chosen photo to the model-ready JPEG
    // off the main thread before handing back the bytes.
    val imagePicker = rememberImagePicker()
    // Read-aloud (TTS) + dictation (STT) toggles, both owned by the ViewModel.
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val micEnabled by viewModel.micEnabled.collectAsState()
    val ttsSpeaking by viewModel.ttsSpeaking.collectAsState()
    // Mic availability + RECORD_AUDIO permission behind the cross-platform seam
    // (Android wraps a RequestPermission launcher + recognition-available check;
    // desktop's Vosk needs neither). The Koin-bound dictation engine (Android
    // SpeechRecognizer / desktop Vosk) is driven start/stop below.
    val micPermission = rememberMicPermission()
    val dictation = koinInject<Dictation>()

    // Echo suppression for dictation: true while the speaker reads aloud AND for
    // a short grace afterwards. The recognizer delivers a transcript a beat
    // after end-of-speech, so a tight `ttsSpeaking` check still lets the tail of
    // the ack / "still working" heartbeat / answer land in the box. The grace
    // covers that late delivery and bridges the silent gaps between heartbeats
    // (a new utterance re-asserts it before it lapses). 2.5s comfortably exceeds
    // recognizer end-of-speech + result latency; the only cost is a brief window
    // after the speaker finishes where dictation is ignored.
    var suppressDictationText by remember { mutableStateOf(false) }
    LaunchedEffect(ttsSpeaking) {
        if (ttsSpeaking) {
            suppressDictationText = true
        } else {
            delay(2_500)
            suppressDictationText = false
        }
    }

    // PR #67 — live transcript: show the in-progress utterance in the box as the
    // user talks (it may change as the engine refines words). Rendered on top of
    // the committed text and superseded by the matching `results` emission below.
    // Voice commands still key off finalized `results`, so we don't act on a
    // partial — only display it (and drop it during TTS playback, echo #42).
    LaunchedEffect(dictation) {
        dictation.partials.collect { partial ->
            if (!suppressDictationText) {
                input = if (committedInput.isBlank()) partial else "$committedInput $partial"
            }
        }
    }

    // Continuous dictation. Each finalized utterance is either a spoken command
    // (send / cancel / speaker off / … — fired instead of typed) or text
    // appended to the input box. Collected for the screen's lifetime; the engine
    // is destroyed when the screen leaves composition.
    LaunchedEffect(dictation) {
        dictation.results.collect { spoken ->
            when (VoiceCommand.match(spoken)) {
                VoiceCommand.SEND -> {
                    val canSend = !ui.isGenerating &&
                        !viewModel.thermalStatus.value.isBlocking &&
                        (committedInput.isNotBlank() || ui.pendingImageBytes != null)
                    if (canSend) {
                        viewModel.send(committedInput)
                        input = ""
                        committedInput = ""
                    } else {
                        // Discard the "send" partial that briefly flashed in the box.
                        input = committedInput
                    }
                }
                VoiceCommand.CANCEL -> {
                    viewModel.cancel()
                    input = committedInput
                }
                VoiceCommand.CLEAR -> {
                    input = ""
                    committedInput = ""
                }
                VoiceCommand.NEW_CHAT -> {
                    viewModel.newConversation()
                    input = ""
                    committedInput = ""
                }
                VoiceCommand.MIC_OFF -> viewModel.setMicEnabled(false)
                VoiceCommand.SPEAKER_OFF -> viewModel.setTtsEnabled(false)
                VoiceCommand.SPEAKER_ON -> viewModel.setTtsEnabled(true)
                VoiceCommand.NONE -> {
                    // While the speaker is reading aloud (plus a grace tail for
                    // late-delivered transcripts), stay in command-only mode:
                    // keep listening so "speaker off" can interrupt, but DROP
                    // regular text since the mic is mostly hearing the
                    // assistant's own playback (echo). Outside playback, promote
                    // the finalized utterance into the committed text (replacing
                    // the live partial that was on screen).
                    if (!suppressDictationText) {
                        committedInput = if (committedInput.isBlank()) spoken else "$committedInput $spoken"
                        input = committedInput
                    } else {
                        input = committedInput
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { dictation.destroy() } }
    // A user-facing notice from the dictation engine (e.g. desktop capture wedged after
    // suspend/resume — see VoskDictation). Surface it as a dismissible banner and turn the
    // mic off so the button reflects that we've stopped listening.
    var dictationNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dictation) {
        dictation.notices.collect { msg ->
            dictationNotice = msg
            viewModel.setMicEnabled(false)
        }
    }
    // Listen for the whole time the mic is on — including while the speaker is
    // talking, so spoken commands ("speaker off") can interrupt playback. The
    // echo is handled in the collector above by dropping non-command text during
    // playback rather than by pausing the recognizer.
    LaunchedEffect(micEnabled) {
        if (micEnabled) dictation.start() else dictation.stop()
    }
    val listState = rememberLazyListState()
    // Desktop scrolls a plain Column via this state (see the list branch below);
    // mobile uses `listState` with its LazyColumn. Unused on mobile.
    val desktopScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // PR #9 — Sticky-to-bottom + jump-to-latest, identical on mobile + desktop.
    //  - `isAtBottom` is derived from layoutInfo. True when the last item is fully
    //    in the viewport (no clipping at the bottom edge).
    //  - `stickyToBottom` tracks whether new content should auto-scroll. While on,
    //    the list pins to the newest content (on each token, each new message, and
    //    as the last bubble settles its height) — so submitting a prompt scrolls it
    //    into view and the response stays pinned to the bottom as it streams + on
    //    completion.
    //  - It disengages ONLY on a genuine USER scroll (see `userScrollConnection`),
    //    never on our own programmatic scroll or a relayout-driven anchor clamp.
    //    The clamp distinction matters on desktop: the async markdown renderer
    //    measures a finalized bubble across several frames, which nudges the scroll
    //    anchor — a raw position-delta heuristic mistook that for a user scroll and
    //    wrongly disengaged after the first exchange (breaking auto-scroll + the FAB).
    //  - Re-engages whenever `isAtBottom` is true again (manual scroll-back or FAB tap).
    //  - A SmallFloatingActionButton shows while sticky is off to jump back.
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
    var stickyToBottom by remember { mutableStateOf(true) }
    // True only while WE are programmatically scrolling, so the user-scroll detector
    // doesn't mistake our own auto-scroll (or the FAB's animated jump) for a manual one.
    var autoScrolling by remember { mutableStateOf(false) }

    /** Total item count in the LazyColumn (chat bubbles + optional streaming bubble). */
    fun totalListItems(): Int =
        ui.messages.size + (if (ui.partialText.isNotEmpty() || ui.isGenerating || ui.searchStatus !is SearchStatus.None) 1 else 0)

    // Pin to the newest item, fenced by `autoScrolling` so the user-scroll detector
    // ignores it. `followBottom` is a no-op once the last item is fully visible.
    val pinToBottom: suspend (Boolean) -> Unit = pin@{ animate ->
        val total = totalListItems()
        if (total <= 0) return@pin
        autoScrolling = true
        try {
            listState.followBottom(lastIndex = total - 1, animate = animate)
        } finally {
            autoScrolling = false
        }
    }

    // Disengage on a genuine user scroll away from the bottom; re-engage at the bottom.
    // Keyed on BOTH isScrollInProgress AND isAtBottom so it re-checks as a scroll crosses
    // off the bottom, not only at the scroll's start edge. isScrollInProgress is false for
    // a relayout clamp, and our own scrolls set `autoScrolling`, so neither misfires.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .collect { (scrolling, atBottom) ->
                when {
                    scrolling && !autoScrolling && !atBottom -> stickyToBottom = false
                    atBottom -> stickyToBottom = true
                }
            }
    }

    // Auto-follow the tail — MOBILE ONLY (PR #64). Mobile's scroll behavior works and is
    // left exactly as-is; desktop showed a scroll-up jump that we're isolating by removing
    // ALL programmatic auto-scroll on desktop (only the explicit FAB jump remains there),
    // then re-adding desktop behaviors one at a time. On mobile this pins to the bottom
    // ONLY while a response is actively generating or on a fresh user submit — never on
    // scroll position and never on other content changes (e.g. an inline memory-prompt
    // card appearing after a turn).
    if (!isDesktopPlatform) {
        var prevUserTurns by remember { mutableIntStateOf(ui.messages.count { it is UiMessage.User }) }
        LaunchedEffect(listState) {
            snapshotFlow {
                Triple(listState.layoutInfo.totalItemsCount, ui.partialText.length, ui.isGenerating)
            }.collect {
                val userTurns = ui.messages.count { it is UiMessage.User }
                val newUserTurn = userTurns > prevUserTurns
                prevUserTurns = userTurns
                if (newUserTurn) stickyToBottom = true
                val streaming = ui.isGenerating || ui.partialText.isNotEmpty() ||
                    ui.searchStatus !is SearchStatus.None
                if (stickyToBottom && (streaming || newUserTurn)) pinToBottom(false)
            }
        }

        // A single alignment pin when generation finishes (the finalized markdown bubble
        // can measure to a slightly different height than the streamed plain text). Gated
        // on sticky, so it only fires if the user was still following.
        LaunchedEffect(ui.isGenerating) {
            if (!ui.isGenerating && stickyToBottom) pinToBottom(false)
        }
    }

    // Desktop follow-while-generating (PR #64) — re-added on top of the smooth
    // verticalScroll, which scrolls by pixels (no LazyColumn tall-item skip). Keep the
    // newest output in view while a response streams, but ONLY while generating, and
    // yield the instant the user scrolls up so reading history is never disturbed.
    if (isDesktopPlatform) {
        var follow by remember { mutableStateOf(true) }
        var autoScrollingDesktop by remember { mutableStateOf(false) }
        var prevUserTurns by remember { mutableIntStateOf(ui.messages.count { it is UiMessage.User }) }

        // Yield to the user on a manual scroll-up; resume when they return to the bottom.
        // Our own scrolls only ever increase `value` (toward the bottom) and are fenced by
        // `autoScrollingDesktop`, so neither they nor content growth count as a user scroll.
        LaunchedEffect(desktopScrollState) {
            var prev = desktopScrollState.value
            snapshotFlow { desktopScrollState.value to desktopScrollState.maxValue }
                .collect { (value, max) ->
                    val atBottom = value >= max
                    when {
                        value < prev && !autoScrollingDesktop && !atBottom -> follow = false
                        atBottom -> follow = true
                    }
                    prev = value
                }
        }

        // Follow the streaming tail: scroll to the bottom on each new token / content
        // growth while generating + engaged. A fresh USER turn (submit) re-engages follow
        // and scrolls the new prompt into view even if the user had scrolled up. Keyed on
        // CONTENT (incl. maxValue so a bubble settling taller mid-stream is caught), never
        // on scroll position, so it can't fight the user. A single re-align fires on
        // completion (below).
        LaunchedEffect(desktopScrollState) {
            snapshotFlow {
                listOf(
                    ui.partialText.length,
                    if (ui.isGenerating) 1 else 0,
                    ui.messages.size,
                    desktopScrollState.maxValue,
                )
            }.collect {
                val userTurns = ui.messages.count { it is UiMessage.User }
                val newUserTurn = userTurns > prevUserTurns
                prevUserTurns = userTurns
                if (newUserTurn) follow = true
                val streaming = ui.isGenerating || ui.partialText.isNotEmpty() ||
                    ui.searchStatus !is SearchStatus.None
                if (follow && (streaming || newUserTurn)) {
                    autoScrollingDesktop = true
                    try {
                        desktopScrollState.scrollTo(desktopScrollState.maxValue)
                    } finally {
                        autoScrollingDesktop = false
                    }
                }
            }
        }
        // On completion, settle at the bottom as the finalized markdown bubble measures
        // over a few frames. Scroll DOWN only (never up): the streaming follow already
        // left us at the bottom, and reading a half-measured maxValue too early was what
        // bounced the view UP to the prompt. So we never move up — we just hold position
        // until the bubble is laid out, then follow the new bottom down. Bails if the user
        // scrolls away.
        LaunchedEffect(ui.isGenerating) {
            if (ui.isGenerating || !follow) return@LaunchedEffect
            repeat(6) {
                delay(24)
                if (!follow) return@LaunchedEffect
                val max = desktopScrollState.maxValue
                if (max > desktopScrollState.value) {
                    autoScrollingDesktop = true
                    try {
                        desktopScrollState.scrollTo(max)
                    } finally {
                        autoScrollingDesktop = false
                    }
                }
            }
        }
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
    val announcer: AccessibilityAnnouncer = rememberAccessibilityAnnouncer()
    var lastSeenSize by remember { mutableIntStateOf(ui.messages.size) }
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.size > lastSeenSize) {
            val newest = ui.messages.lastOrNull()
            if (newest is UiMessage.Assistant && newest.text.isNotBlank()) {
                announcer.announce(newest.text)
            }
        }
        lastSeenSize = ui.messages.size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // PR #44 — system-memory dot rides in the title slot, left-
                // justified, leaving a gap before the right-justified action
                // icons. Green/yellow/red bands mirror the watchdog + send-time
                // gate thresholds in SystemMemoryThresholds, so the dot the user
                // sees can't drift out of sync with what actually gates inference.
                // (PR #63 removed the brand logo from the header; build identity
                // now lives in Settings → About.)
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SystemMemoryStatusIndicator()
                        DesktopLinkStatusIndicator()
                    }
                },
                actions = {
                    // PR #44 — New Chat first on the right, Settings last.
                    // Order L→R: New Chat, TODO, Timer, Alarm, theme, Settings.
                    IconButton(onClick = { viewModel.newConversation() }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = tr(StringKeys.CHAT_CD_NEW_CHAT),
                        )
                    }
                    // PR #15 — My List entry point. Count is folded into the
                    // accessibility label only; the visual badge was
                    // removed in PR #26. PR #22 — shown on BOTH platforms now
                    // that My List syncs mobile↔desktop.
                    ClockIconButton(
                        icon = Icons.Filled.Checklist,
                        contentDescription = tr(StringKeys.CHAT_CD_MYLIST, activeMyListCount),
                        onClick = onOpenMyList,
                    )
                    // PR #57 — timers/alarms stay mobile-only (Android clock
                    // services), so these entry points are hidden on desktop.
                    if (!isDesktopPlatform) {
                        // PR #11 — clock entry points. Always shown so the user
                        // can create the first timer/alarm.
                        ClockIconButton(
                            icon = Icons.Filled.Timer,
                            contentDescription = tr(StringKeys.CHAT_CD_TIMERS, timers.size),
                            onClick = onOpenTimers,
                        )
                        ClockIconButton(
                            icon = Icons.Filled.AccessAlarm,
                            contentDescription = tr(StringKeys.CHAT_CD_ALARMS, alarms.count { it.enabled }),
                            onClick = onOpenAlarms,
                        )
                    }
                    // PR #70 / #80 — Jobs. On the desktop (the job HOST) it's always
                    // shown. On mobile it appears ONLY when a desktop is paired
                    // (status != DISABLED, i.e. a relay QR has been scanned), since
                    // jobs are meaningless without a desktop to run them. It stays
                    // visible while paired-but-offline so the last-synced list is
                    // still viewable; pause/resume inside is gated on the link being UP.
                    val jobsLinkStatus by koinInject<DesktopLinkStatusProvider>().status.collectAsState()
                    if (isDesktopPlatform || jobsLinkStatus != DesktopLinkStatus.DISABLED) {
                        // PR #85 — numbered bubble when synced job runs finished unseen
                        // (mobile-only: JobBadge is bound on Android, absent on desktop).
                        // Same count-bubble style the alarm/timer/my-list icons used pre-#26:
                        // the bubble matches the icon's tint so it reads as part of the icon.
                        val jobBadge = getKoin().getOrNull<JobBadge>()
                        val unseenFlow = remember(jobBadge) { jobBadge?.unseenCount ?: MutableStateFlow(0) }
                        val unseenJobRuns by unseenFlow.collectAsState()
                        IconButton(onClick = onOpenJobs) {
                            if (unseenJobRuns > 0) {
                                val iconTint = LocalContentColor.current
                                val digitColor = MaterialTheme.colorScheme.surface
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = iconTint, contentColor = digitColor) {
                                            Text(unseenJobRuns.toString())
                                        }
                                    },
                                ) {
                                    Icon(imageVector = RuleSettingsIcon, contentDescription = tr(StringKeys.CHAT_CD_JOBS))
                                }
                            } else {
                                Icon(imageVector = RuleSettingsIcon, contentDescription = tr(StringKeys.CHAT_CD_JOBS))
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = tr(StringKeys.CHAT_CD_SETTINGS),
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

            // Wrap the LazyColumn in a BoxWithConstraints so the "jump to latest"
            // FAB can overlay the bottom-end of the message list (not the input
            // bar) AND so bubbles can size to the available width.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Bubble width cap. Narrow windows (phones, narrow desktop) keep the
                // mobile-tuned 320 dp; wider desktop windows let bubbles grow
                // proportionally with the window (no upper cap) so they keep filling
                // the space as the window widens, with only a 480 dp floor. `maxWidth`
                // here is the list width (window minus the 16 dp side gutters).
                val bubbleMaxWidth: Dp = if (maxWidth < 600.dp) {
                    320.dp
                } else {
                    (maxWidth * 0.72f).coerceAtLeast(480.dp)
                }
            if (isDesktopPlatform) {
                // Desktop: a plain Column + verticalScroll instead of LazyColumn.
                // Compose Desktop's mouse-wheel scrolling skips over LazyColumn items
                // taller than the viewport — our long markdown response bubbles — so
                // scrolling up jumps straight past a response to the prior prompt.
                // verticalScroll moves by pixels through ANY height, giving smooth
                // scrolling across every bubble. A single conversation is bounded, so
                // eager (non-lazy) composition is fine. Mobile keeps the LazyColumn (in
                // the else branch) exactly as-is.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(desktopScrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (ui.messages.isEmpty() && ui.partialText.isEmpty() && !ui.isGenerating && ui.error == null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(text = tr(StringKeys.CHAT_EMPTY_TITLE), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                text = tr(StringKeys.CHAT_EMPTY_BODY),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ui.messages.forEach { message ->
                        when (message) {
                            is UiMessage.User -> UserBubble(message.text, message.imageBytes, bubbleMaxWidth)
                            is UiMessage.Assistant -> AssistantBubble(
                                text = message.text,
                                citations = message.citations,
                                fromCache = message.fromCache,
                                renderMarkdown = message.renderMarkdown,
                                onDelete = { viewModel.deleteTurn(message.id) },
                                maxWidth = bubbleMaxWidth,
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
                        StreamingAssistantBubble(
                            partial = ui.partialText,
                            searchStatus = ui.searchStatus,
                            isGenerating = ui.isGenerating,
                            maxWidth = bubbleMaxWidth,
                        )
                    }
                    if (ui.error != null) {
                        Text(
                            text = tr(StringKeys.CHAT_ERROR_PREFIX, ui.error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
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
                                text = tr(StringKeys.CHAT_EMPTY_TITLE),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = tr(StringKeys.CHAT_EMPTY_BODY),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(ui.messages) { message ->
                    when (message) {
                        is UiMessage.User -> UserBubble(message.text, message.imageBytes, bubbleMaxWidth)
                        is UiMessage.Assistant -> AssistantBubble(
                            text = message.text,
                            citations = message.citations,
                            fromCache = message.fromCache,
                            renderMarkdown = message.renderMarkdown,
                            onDelete = { viewModel.deleteTurn(message.id) },
                            maxWidth = bubbleMaxWidth,
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
                            maxWidth = bubbleMaxWidth,
                        )
                    }
                }
                if (ui.error != null) {
                    item {
                        Text(
                            text = tr(StringKeys.CHAT_ERROR_PREFIX, ui.error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            } // end desktop-Column / mobile-LazyColumn branch

            // "Jump to latest" — appears once the user has scrolled away from the
            // bottom; tapping it scrolls back to the newest content. Desktop drives the
            // verticalScroll state; mobile re-engages sticky-to-bottom + follows the tail.
            val showJumpToLatest = if (isDesktopPlatform) {
                desktopScrollState.maxValue > 0 && desktopScrollState.value < desktopScrollState.maxValue
            } else {
                !stickyToBottom && totalListItems() > 0
            }
            if (showJumpToLatest) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            if (isDesktopPlatform) {
                                desktopScrollState.animateScrollTo(desktopScrollState.maxValue)
                            } else {
                                stickyToBottom = true
                                pinToBottom(true)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = tr(StringKeys.CHAT_CD_JUMP_TO_LATEST),
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

            // Enter submits; Shift+Enter inserts a newline (multiline prompts).
            // Two mechanisms cover both platforms: `onPreviewKeyEvent` catches
            // the physical Enter key (desktop + Android hardware keyboards),
            // and `KeyboardOptions(imeAction = Send)` + `KeyboardActions` covers
            // Android soft keyboards (which commit text via the IME rather than
            // delivering key events). `ChatViewModel.send` trims leading/trailing
            // whitespace; internal newlines (Shift+Enter) are preserved and Gemma
            // handles them natively (chat templating expects multiline turns).
            val canSend = !ui.isGenerating && !thermal.isBlocking &&
                (input.isNotBlank() || ui.pendingImageBytes != null)
            val submit = {
                if (canSend) {
                    viewModel.send(input)
                    input = ""
                    committedInput = ""
                }
            }
            // Dictation notice (e.g. mic capture wedged after suspend/resume) — a
            // dismissible error banner above the input, mirroring the staged-image row.
            dictationNotice?.let { notice ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        )
                        IconButton(onClick = { dictationNotice = null }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = tr(StringKeys.CHAT_CD_CLEAR_INPUT),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            // PR #48 — staged-image chip: thumbnail + remove button, shown
            // above the input while a photo is attached to the next send.
            ui.pendingImageBytes?.let { bytes ->
                val thumb by produceState<ImageBitmap?>(initialValue = null, bytes) {
                    value = withContext(Dispatchers.Default) { decodeImageBitmap(bytes) }
                }
                Row(verticalAlignment = Alignment.Top) {
                    thumb?.let {
                        // Match the sent-bubble rendering (UserBubble): show the whole
                        // image at full bubble width, aspect-ratio preserved (Fit, not Crop).
                        Image(
                            bitmap = it,
                            contentDescription = tr(StringKeys.CHAT_CD_ATTACHED_IMAGE),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                // Bound the height too: a portrait (taller-than-wide) photo
                                // would otherwise render ~320-420dp tall and, as a fixed-height
                                // sibling of the text field in this non-scrolling Column, push
                                // the input off-screen once imePadding() shrinks the column for
                                // the keyboard. The staged chip stays small; the sent bubble
                                // (UserBubble) keeps full height since it lives in the scroll area.
                                .heightIn(max = 160.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    IconButton(onClick = { viewModel.clearPickedImage() }) {
                        Icon(Icons.Default.Close, contentDescription = tr(StringKeys.CHAT_CD_REMOVE_IMAGE))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            OutlinedTextField(
                value = input,
                // A manual edit is authoritative: it becomes the committed text,
                // abandoning any in-flight dictation partial (PR #67).
                onValueChange = { input = it; committedInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                            !event.isShiftPressed
                        ) {
                            submit()
                            true // consume — don't insert a newline
                        } else {
                            false // Shift+Enter (and everything else) falls through
                        }
                    },
                placeholder = { Text(tr(StringKeys.CHAT_INPUT_HINT)) },
                // Quick "x" to wipe the current draft. Resets both the shown text
                // and the dictation commit anchor (PR #67) so a stale transcript
                // can't resurface on the next partial.
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = ""; committedInput = "" }) {
                            Icon(Icons.Default.Close, contentDescription = tr(StringKeys.CHAT_CD_CLEAR_INPUT))
                        }
                    }
                },
                enabled = !ui.isGenerating && !thermal.isBlocking,
                minLines = 1,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Microphone — a toggle: on = continuously transcribe speech
                // into the input field, off = stop. Greyed out if the device
                // has no recognition service.
                IconButton(
                    onClick = {
                        if (micEnabled) {
                            viewModel.setMicEnabled(false)
                        } else {
                            // Request RECORD_AUDIO if needed (no-ops to granted on
                            // desktop); flip the toggle on only once granted.
                            micPermission.request { granted -> viewModel.setMicEnabled(granted) }
                        }
                    },
                    enabled = micPermission.available,
                    // Grey resting tint (onSurfaceVariant) to match the top-bar icons;
                    // the IconButton dims it when disabled. Active stays primary below.
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(
                        imageVector = if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = if (micEnabled) tr(StringKeys.CHAT_CD_MIC_STOP) else tr(StringKeys.CHAT_CD_MIC_START),
                        tint = if (micEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
                // Speaker — read finished answers aloud. Persisted toggle;
                // available regardless of generation state.
                IconButton(
                    onClick = { viewModel.toggleTts() },
                    // Grey resting tint to match the top-bar icons; active stays primary.
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(
                        imageVector = if (ttsEnabled) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = if (ttsEnabled) {
                            tr(StringKeys.CHAT_CD_TTS_DISABLE)
                        } else {
                            tr(StringKeys.CHAT_CD_TTS_ENABLE)
                        },
                        tint = if (ttsEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
                // PR #48 — attach a photo from the gallery.
                IconButton(
                    onClick = {
                        imagePicker.launch { bytes ->
                            if (bytes != null) viewModel.onImagePicked(bytes)
                        }
                    },
                    enabled = !ui.isGenerating && !thermal.isBlocking,
                    // Grey resting tint to match the top-bar icons; the IconButton dims
                    // it while disabled (during generation / thermal blocking).
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Default.Image, contentDescription = tr(StringKeys.CHAT_CD_ATTACH_IMAGE))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = submit,
                    // Allow an image-only send (no text) when a photo is staged.
                    enabled = canSend,
                ) {
                    Text(tr(StringKeys.CHAT_SEND))
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
                        Text(if (ui.isCancelling) tr(StringKeys.CHAT_CANCELLING) else tr(StringKeys.CHAT_CANCEL))
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
                title = { Text(tr(StringKeys.CHAT_OVERFLOW_TITLE)) },
                text = {
                    Text(tr(StringKeys.CHAT_OVERFLOW_BODY, decision.pendingPrompt))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.continueAfterOverflow() }) {
                        Text(tr(StringKeys.CHAT_OVERFLOW_CONTINUE))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissOverflowStartNew() }) {
                        Text(tr(StringKeys.CHAT_OVERFLOW_START_NEW))
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
                title = { Text(tr(StringKeys.CHAT_MEMORY_LIMIT_TITLE)) },
                text = {
                    Text(tr(StringKeys.CHAT_MEMORY_LIMIT_BODY, limit))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissMemoryLimitDialog() }) {
                        Text(tr(StringKeys.CHAT_OK))
                    }
                },
            )
        }

        // The PR #16 low-memory submission gate has been removed — the
        // user is no longer blocked when system free RAM is low. The
        // header SystemMemoryStatusIndicator (red LED) is the sole signal
        // for the condition.
        // (PR #63 moved the About/build-identity surface from a header-logo
        // dialog here to Settings → About.)
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
private fun UserBubble(text: String, imageBytes: ByteArray? = null, maxWidth: Dp = 320.dp) {
    // The attached photo (PR #48 live send / PR #49 persisted on resume) arrives
    // as downscaled JPEG [imageBytes], decoded on demand here (off the main
    // thread) via the cross-platform `decodeImageBitmap` seam. LazyColumn
    // disposes off-screen items, so the decoded bitmap is released when the
    // bubble scrolls away; only currently-visible photos hold a bitmap at once.
    val decoded: ImageBitmap? = imageBytes?.let { bytes ->
        produceState<ImageBitmap?>(initialValue = null, bytes) {
            value = withContext(Dispatchers.Default) { decodeImageBitmap(bytes) }
        }.value
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
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
                        contentDescription = tr(StringKeys.CHAT_CD_ATTACHED_IMAGE),
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
    onDelete: () -> Unit,
    maxWidth: Dp = 320.dp,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // PR #4 — the bubble sits beside a small "x" that removes this whole
        // exchange (the question + this answer) from the thread. Aligned to the
        // bubble top so it never overlaps the answer text; confirmed first since
        // it's destructive of both turns.
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxWidth)
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
                    MarkdownMath(text, renderMarkdown = true)
                } else {
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = tr(StringKeys.CHAT_CD_DELETE_TURN),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (fromCache) {
            Text(
                tr(StringKeys.CHAT_FROM_CACHE),
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
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr(StringKeys.CHAT_DELETE_TURN_TITLE)) },
            text = { Text(tr(StringKeys.CHAT_DELETE_TURN_BODY)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(tr(StringKeys.CHAT_DELETE_TURN_CONFIRM))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(tr(StringKeys.CHAT_DELETE_TURN_CANCEL))
                }
            },
        )
    }
}

@Composable
private fun StreamingAssistantBubble(
    partial: String,
    searchStatus: SearchStatus,
    isGenerating: Boolean,
    maxWidth: Dp = 320.dp,
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
        } else if (searchStatus is SearchStatus.RunningJob) {
            RunningJobChip(searchStatus.jobName)
            Spacer(Modifier.height(4.dp))
        } else if (searchStatus is SearchStatus.Failed) {
            Text(
                tr(StringKeys.CHAT_SEARCH_FAILED, searchStatus.kind.lowercase(), searchStatus.message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (partial.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxWidth)
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
        } else if (isGenerating &&
            searchStatus !is SearchStatus.Searching &&
            searchStatus !is SearchStatus.RunningJob
        ) {
            Text(
                tr(StringKeys.CHAT_THINKING),
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
        label = { Text(tr(StringKeys.CHAT_SEARCHING, query)) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun RunningJobChip(jobName: String) {
    // In-progress indicator while a "run job …" command (PR #88) runs the desktop
    // job; replaced by the streamed answer once it completes (or by an error).
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(tr(StringKeys.CHAT_RUNNING_JOB, jobName)) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun CitationChips(citations: List<SearchSource>) {
    val urlOpener = koinInject<UrlOpener>()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        citations.forEachIndexed { index, source ->
            val host = remember(source.url) { urlHost(source.url) ?: source.url }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { urlOpener.openUrl(source.url) }
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

@Composable
private fun SessionBanner(state: SessionState) {
    // PR #22 — mobile shows a small fixed set of states (downloading%/loading/
    // loaded on GPU/loaded on CPU/unloaded). Desktop keeps the accurate
    // per-accelerator banner (it runs on CPU/Vulkan/remote, where "GPU" would lie).
    val info: Pair<String, Boolean>? = if (isDesktopPlatform) {
        when (state) {
            is SessionState.Unloaded ->
                tr(StringKeys.CHAT_SESSION_UNLOADED) to false
            is SessionState.Downloading -> {
                val pct = state.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                tr(StringKeys.CHAT_SESSION_DOWNLOADING, pct) to false
            }
            is SessionState.Loading ->
                tr(StringKeys.CHAT_SESSION_LOADING) to false
            is SessionState.Loaded -> when (state.activeAccelerator) {
                // PR #56 — generation runs on a remote Ollama server; the on-device
                // accelerator banner ("Loaded on CPU/GPU…") doesn't apply, so omit it.
                Accelerator.REMOTE -> null
                Accelerator.CPU -> tr(StringKeys.CHAT_SESSION_LOADED_CPU) to true
                else -> tr(StringKeys.CHAT_SESSION_LOADED, state.activeAccelerator.name) to false
            }
            is SessionState.Failed ->
                tr(StringKeys.CHAT_SESSION_FAILED, state.message) to true
        }
    } else {
        when (state) {
            // Failed collapses to "Model unloaded" — the next prompt cold-loads/retries;
            // keep the error string out of this banner.
            is SessionState.Unloaded, is SessionState.Failed ->
                tr(StringKeys.CHAT_SESSION_MOBILE_UNLOADED) to false
            is SessionState.Downloading -> {
                val pct = state.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                tr(StringKeys.CHAT_SESSION_MOBILE_DOWNLOADING, pct) to false
            }
            is SessionState.Loading ->
                tr(StringKeys.CHAT_SESSION_MOBILE_LOADING) to false
            is SessionState.Loaded -> when (state.activeAccelerator) {
                Accelerator.REMOTE -> null
                Accelerator.CPU -> tr(StringKeys.CHAT_SESSION_MOBILE_LOADED_CPU) to false
                else -> tr(StringKeys.CHAT_SESSION_MOBILE_LOADED_GPU) to false
            }
        }
    }
    if (info == null) return
    val (text, isWarning) = info
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun SystemMemoryStatusIndicator(
    provider: SystemMemoryStatusProvider = koinInject(),
) {
    val status by provider.status.collectAsState()
    // All three bands use fixed Material colors rather than theme slots —
    // `colorScheme.primary` adapts to wallpaper under Material You (so
    // "green" can render blue/purple) and `colorScheme.error` desaturates
    // to pink on dark surfaces. A status dot needs to stay semantically
    // red/yellow/green regardless of theme.
    val (color, desc) = when (status) {
        MemoryStatus.Green -> Color(0xFF43A047) to tr(StringKeys.CHAT_CD_MEM_HEALTHY)
        MemoryStatus.Yellow -> Color(0xFFFFA000) to tr(StringKeys.CHAT_CD_MEM_CAUTION)
        MemoryStatus.Red -> Color(0xFFE53935) to tr(StringKeys.CHAT_CD_MEM_LOW)
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

/**
 * PR #57 — the mobile↔desktop link indicator, beside the system-memory dot. It's
 * a small NETWORK icon (the memory indicator is a round dot) so the two are easy
 * to tell apart at a glance. Green when the link is on + paired + reachable, red
 * when on + paired but unreachable, and nothing at all when off/unpaired.
 */
@Composable
private fun DesktopLinkStatusIndicator(
    provider: DesktopLinkStatusProvider = koinInject(),
) {
    val status by provider.status.collectAsState()
    val (color, desc) = when (status) {
        DesktopLinkStatus.UP -> Color(0xFF43A047) to tr(StringKeys.CHAT_CD_LINK_CONNECTED)
        DesktopLinkStatus.DOWN -> Color(0xFFE53935) to tr(StringKeys.CHAT_CD_LINK_UNREACHABLE)
        DesktopLinkStatus.DISABLED -> return // render nothing when off/unpaired
    }
    Icon(
        imageVector = Icons.Filled.Lan,
        contentDescription = desc,
        tint = color,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(14.dp),
    )
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
