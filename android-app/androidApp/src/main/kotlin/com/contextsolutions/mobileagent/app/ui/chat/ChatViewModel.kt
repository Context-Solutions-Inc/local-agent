package com.contextsolutions.mobileagent.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import com.contextsolutions.mobileagent.agent.AgentEvent
import com.contextsolutions.mobileagent.agent.AgentTurnInput
import com.contextsolutions.mobileagent.agent.ChatMessage
import com.contextsolutions.mobileagent.agent.ResponseFilter
import com.contextsolutions.mobileagent.agent.TokenBudgetEstimator
import com.contextsolutions.mobileagent.agent.TranslationIntentDetector
import com.contextsolutions.mobileagent.app.di.AgentLoopFactory
import com.contextsolutions.mobileagent.app.service.InferenceSessionAdapter
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.app.service.ModelInventory
import com.contextsolutions.mobileagent.app.service.SessionState
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.inference.ThermalStatus
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.language.LanguagePreferences
import com.contextsolutions.mobileagent.memory.MemoryCategory
import com.contextsolutions.mobileagent.memory.MemoryExtractor
import com.contextsolutions.mobileagent.memory.MemoryPromptCandidate
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.contextsolutions.mobileagent.search.SearchSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the M2 chat surface: maintains the conversation history, runs the
 * [com.contextsolutions.mobileagent.agent.AgentLoop] on each user send, and
 * projects the resulting [AgentEvent] stream into UI state the Compose layer
 * can render directly.
 *
 * PR#13: conversations + messages are persisted through [ConversationRepository]
 * so users can browse and resume prior chats from Settings. The 8K KV-cache
 * budget is enforced via [TokenBudgetEstimator]: when the next send would
 * exceed [TokenBudgetEstimator.SAFE_HISTORY_TOKENS] and the user has not
 * already accepted the "oldest pair will be dropped" warning for this
 * conversation, the send is paused and a modal is requested via
 * [overflowDecision]. After acknowledgement, subsequent overflows in this
 * conversation silently drop the oldest pair before each send.
 */
@OptIn(ExperimentalUuidApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoopFactory: AgentLoopFactory,
    private val sessionManager: InferenceSessionManager,
    private val inventory: ModelInventory,
    private val languagePreferences: LanguagePreferences,
    private val translationIntentDetector: TranslationIntentDetector,
    private val memoryExtractor: MemoryExtractor,
    private val memoryStore: MemoryStore,
    private val conversationRepository: ConversationRepository,
    private val telemetryCounters: TelemetryCounters,
    @ApplicationContext private val appContext: Context,
    thermalStatusProvider: ThermalStatusProvider,
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = sessionManager.state

    /**
     * M6 Phase E — surface thermal state to the chat UI (PRD §4.3).
     * Banner at MODERATE/SEVERE; full block at CRITICAL+. The flow emits
     * the current value on subscribe and again on every transition.
     */
    val thermalStatus: StateFlow<ThermalStatus> = thermalStatusProvider.statusFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, thermalStatusProvider.current())

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    /**
     * Memory count for the current conversation — drives the badge in
     * the chat top bar (M5 Phase E). Refreshed after every Done event so
     * a newly-extracted memory appears within ~1 turn of when it landed.
     */
    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    /**
     * The active conversation ID. Exposed to the chat screen so the
     * "memories from this chat" route can scope itself correctly.
     */
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()

    /**
     * PR#13: non-null when the chat surface should render the overflow
     * modal (context-limit-reached dialog). Cleared by [continueAfterOverflow]
     * or [dismissOverflowStartNew].
     */
    private val _overflowDecision = MutableStateFlow<OverflowDecision?>(null)
    val overflowDecision: StateFlow<OverflowDecision?> = _overflowDecision.asStateFlow()

    /**
     * PR#46: holds the hard memory cap (`MemoryConfig.maxMemories`) when a
     * save was refused because the store is full, so the chat surface can
     * render a "memory limit reached" dialog. Null when no dialog is showing.
     * Set by [saveMemoryPrompt] (consent card) and [surfaceMemoryPrompts]
     * (explicit `Remember:` command); cleared by [dismissMemoryLimitDialog].
     */
    private val _memoryLimitReached = MutableStateFlow<Int?>(null)
    val memoryLimitReached: StateFlow<Int?> = _memoryLimitReached.asStateFlow()

    /** Internal full conversation as the agent sees it (includes tool_call/tool turns). */
    private var agentHistory: List<ChatMessage> = emptyList()

    /**
     * Mirrors the active conversation row's `truncation_acknowledged_at`
     * column. Source of truth is the DB; this in-memory copy keeps the
     * overflow check synchronous on the hot path. Reset on
     * [newConversation], populated on [loadConversation], set true by
     * [continueAfterOverflow].
     */
    private var truncationAcknowledged: Boolean = false

    /**
     * True when the active conversation row has been persisted (i.e.
     * [ConversationRepository.create] has run for [_conversationId]).
     * Distinguishes "first send for a new conversation" from "subsequent
     * send for an existing conversation" so we only call create once.
     */
    private var conversationPersisted: Boolean = false

    /**
     * Middle-band memory candidates currently surfaced in the chat as
     * Save / Dismiss cards. Keyed by candidate id so the UI callbacks
     * can hand the real object back to [MemoryExtractor].
     *
     * Lifetime: cleared whenever a new turn produces a new
     * [MemoryExtractor.ExtractionReport.PromptRequested]; lost on
     * process kill (acceptable for v1).
     */
    private val pendingCandidates = mutableMapOf<String, MemoryPromptCandidate>()

    private var currentJob: Job? = null

    /**
     * PR #48 — the photo attached to the *next* send. Bytes (downscaled JPEG)
     * go to the model; the matching thumbnail is mirrored into [ChatUiState] for
     * the input chip. Ephemeral: cleared after each send, never persisted.
     */
    private var pendingImageBytes: ByteArray? = null

    /**
     * Decode + downscale a gallery image and stage it for the next send. Runs
     * off the main thread via [ImagePreprocessor]. On a decode failure the
     * pending image is left cleared and a chat error is surfaced.
     */
    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            val prepared = ImagePreprocessor.prepare(appContext, uri)
            if (prepared == null) {
                _ui.update { it.copy(error = "Couldn't read that image.") }
                return@launch
            }
            pendingImageBytes = prepared.jpegBytes
            _ui.update { it.copy(pendingImageThumbnail = prepared.thumbnail, error = null) }
        }
    }

    /** Discard a staged image before sending (the chip's remove button). */
    fun clearPickedImage() {
        pendingImageBytes = null
        _ui.update { it.copy(pendingImageThumbnail = null) }
    }

    // Aux-model warm-up is owned by MainViewModel.warmUpAuxEngines, fired
    // from the chat-screen RESUME hook. Single source of truth, fires on
    // every chat-screen entry AND on background→foreground bounce, and
    // re-fires after a 5-min idle / onTrimMemory unload. Gemma is NOT
    // warmed eagerly (PR #25) — it loads on the first generate() call.

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        // PR #48 — snapshot the staged image and clear it so a second tap can't
        // double-send it. An image-only turn (no text) is allowed.
        val imageBytes = pendingImageBytes
        val imageThumb = _ui.value.pendingImageThumbnail
        pendingImageBytes = null
        if (trimmed.isEmpty() && imageBytes == null) return

        // PR#13 overflow guard. The check is synchronous (uses the in-memory
        // ack mirror) so the UI can react in the same frame.
        val projected = agentHistory + ChatMessage.User(trimmed)
        if (TokenBudgetEstimator.wouldOverflow(projected) && !truncationAcknowledged) {
            _overflowDecision.value = OverflowDecision(pendingPrompt = trimmed)
            return
        }

        // PR #16's send-time memory gate has been removed — the user is no
        // longer blocked from sending under memory pressure. The
        // SystemMemoryMonitor / header LED still signals the condition;
        // background unload via MemoryPressureWatchdog still fires under
        // its own threshold; the cold-load + hot-path gates here used to
        // proactively refuse to start inference under crunch and surfaced
        // a modal — that whole UX is gone by user request.

        currentJob?.cancel()

        // Generate a conversation ID lazily on the first send so memories
        // extracted during this chat can be grouped for the Phase E badge.
        if (_conversationId.value == null) {
            _conversationId.value = "conv-${Uuid.random()}"
        }

        // Add user bubble immediately so it appears even before the model loads.
        // isCancelling is cleared here in case a prior turn left it set (e.g.
        // the user cancelled then immediately re-sent before the prior turn's
        // CancellationException catch ran).
        _ui.update {
            it.copy(
                messages = it.messages + UiMessage.User(trimmed, thumbnail = imageThumb),
                // Clear the input chip now that the image is part of the turn.
                pendingImageThumbnail = null,
                partialText = "",
                searchStatus = SearchStatus.None,
                error = null,
                isGenerating = true,
                isCancelling = false,
            )
        }

        // PR #10 — per-turn response language + character filter. The
        // filter is enforced for normal turns and relaxed (NoOp) when the
        // user's message looks like a translation request. Decisions are
        // snapshotted at send-time so a Settings flip mid-stream doesn't
        // affect the in-flight turn.
        val language = languagePreferences.preferredLanguage()
        val isTranslation = translationIntentDetector.isTranslationRequest(trimmed, language)
        val filter = if (isTranslation) ResponseFilter.NoOp else ResponseFilter.allowedScripts(language)
        currentJob = viewModelScope.launch {
            try {
                val convId = _conversationId.value
                    ?: throw IllegalStateException("conversationId vanished mid-send")

                // PR#13 — silent truncate after the user has acknowledged the
                // dialog once. Drop oldest pairs until the projected token
                // count fits (or we hit the 1-pair floor).
                if (truncationAcknowledged) {
                    silentTruncateUntilFits(convId, trimmed)
                }

                // Ensure conversation row exists, then persist the user
                // message. Both run in IO via the repo; ordering matters so
                // we don't insert a message before the conversation row.
                ensureConversationPersisted(convId, trimmed)
                val now = System.currentTimeMillis()
                conversationRepository.appendMessage(
                    conversationId = convId,
                    // PR #49 — persist the attached photo (downscaled JPEG) so it
                    // re-renders in the bubble on resume. Display-only: the model
                    // still only sees the current turn's image (invariant #39).
                    message = ChatMessage.User(trimmed, imageBytes = imageBytes),
                    nowEpochMs = now,
                )

                val historySnapshot = agentHistory
                val session = InferenceSessionAdapter(
                    sessionManager = sessionManager,
                    modelPath = inventory.localFile().absolutePath,
                )
                val loop = agentLoopFactory.create(
                    session = session,
                    responseLanguage = language,
                    responseFilter = filter,
                )
                loop.run(
                    AgentTurnInput(
                        userMessage = trimmed,
                        history = historySnapshot,
                        imageBytes = imageBytes,
                    ),
                ).collect { event ->
                    onAgentEvent(event)
                }
            } catch (e: CancellationException) {
                _ui.update {
                    it.copy(
                        isGenerating = false,
                        isCancelling = false,
                        partialText = "",
                        searchStatus = SearchStatus.None,
                    )
                }
                throw e
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        isGenerating = false,
                        isCancelling = false,
                        partialText = "",
                        searchStatus = SearchStatus.None,
                        error = t.message ?: t::class.simpleName,
                    )
                }
            }
        }
    }

    /**
     * Cancel the in-flight turn. The button calls this; UI feedback must be
     * immediate even though the underlying [Job.cancel] is asynchronous (the
     * coroutine only checks cancellation at suspension points, and the native
     * decode loop inside LiteRT-LM keeps running until
     * [com.google.ai.edge.litertlm.Conversation.cancelProcess] propagates).
     *
     * Two-stage UX: flip `isCancelling = true` synchronously so the Cancel
     * button immediately re-renders as a disabled "Cancelling…" affordance,
     * then call `cancel()` on the job. The catch block clears the flag once
     * the coroutine actually unwinds — typically tens of ms after the native
     * abort lands.
     */
    fun cancel() {
        val job = currentJob ?: return
        if (job.isCancelled || job.isCompleted) return
        if (!_ui.value.isGenerating) return
        _ui.update { it.copy(isCancelling = true) }
        job.cancel()
    }

    /**
     * Re-query the badge count for the current conversation. Called from
     * [com.contextsolutions.mobileagent.app.ui.chat.ChatScreen]'s
     * `LaunchedEffect(Unit)` so a delete on `MemoryScreen` is reflected
     * on return — the count update during extraction only fires after
     * a Done event, so without this hook a deletion stays invisible
     * until the user sends a new message.
     */
    fun refreshMemoryCount() {
        val cid = _conversationId.value ?: run {
            _memoryCount.value = 0
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { _memoryCount.value = memoryStore.countForConversation(cid) }
        }
    }

    fun newConversation() {
        currentJob?.cancel()
        agentHistory = emptyList()
        _conversationId.value = null
        _memoryCount.value = 0
        _ui.value = ChatUiState()
        _overflowDecision.value = null
        truncationAcknowledged = false
        conversationPersisted = false
    }

    /**
     * PR#13 — resume a prior conversation. Cancels any in-flight send, loads
     * the persisted history, rebuilds the UI bubble list, and mirrors the
     * row's `truncation_acknowledged_at` flag into [truncationAcknowledged].
     * If [conversationId] doesn't exist (e.g., concurrently evicted) the
     * call no-ops and leaves the chat in its current state.
     */
    fun loadConversation(conversationId: String) {
        currentJob?.cancel()
        viewModelScope.launch {
            val record = conversationRepository.get(conversationId) ?: return@launch
            val messages = conversationRepository.loadMessages(conversationId)

            agentHistory = messages
            _conversationId.value = record.id
            truncationAcknowledged = record.truncationAcknowledgedAtEpochMs != null
            conversationPersisted = true
            pendingCandidates.clear()
            _overflowDecision.value = null

            val uiMessages = messages.mapNotNull { it.toUiMessage() }
            _ui.value = ChatUiState(messages = uiMessages)

            telemetryCounters.increment(CounterNames.CONVERSATIONS_RESUMED_TOTAL)
            runCatching { _memoryCount.value = memoryStore.countForConversation(record.id) }
        }
    }

    /**
     * User tapped "Continue" on the overflow dialog. Persists the ack so
     * future overflows truncate silently, drops oldest pair(s) inline,
     * then re-invokes the original send.
     */
    fun continueAfterOverflow() {
        val pending = _overflowDecision.value ?: return
        _overflowDecision.value = null
        truncationAcknowledged = true
        val convId = _conversationId.value
        if (convId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    conversationRepository.acknowledgeTruncation(
                        conversationId = convId,
                        nowEpochMs = System.currentTimeMillis(),
                    )
                }
            }
        }
        send(pending.pendingPrompt)
    }

    /** User tapped "Start new conversation" on the overflow dialog. */
    fun dismissOverflowStartNew() {
        _overflowDecision.value = null
        newConversation()
    }

    /**
     * Called by the conversation-history screen after the user deletes a
     * conversation row. If the row they deleted is the one currently shown
     * in the chat surface, reset to a fresh empty chat — otherwise the user
     * sees ghost messages for a conversation that no longer exists in the
     * DB, and the next send would try to append to a deleted parent row.
     * No-op when the deleted id isn't the active one.
     */
    fun onConversationDeleted(deletedConversationId: String) {
        if (_conversationId.value == deletedConversationId) {
            newConversation()
        }
    }

    private suspend fun ensureConversationPersisted(conversationId: String, firstUserMessage: String) {
        if (conversationPersisted) return
        val now = System.currentTimeMillis()
        conversationRepository.create(
            id = conversationId,
            title = ConversationRepository.deriveTitle(firstUserMessage),
            nowEpochMs = now,
        )
        conversationPersisted = true
    }

    private suspend fun silentTruncateUntilFits(conversationId: String, pendingUserMessage: String) {
        var projected = agentHistory + ChatMessage.User(pendingUserMessage)
        while (TokenBudgetEstimator.wouldOverflow(projected)) {
            val dropped = conversationRepository.deleteOldestPair(conversationId)
            if (dropped == 0) break // fewer than 2 user messages; can't pair-drop further
            agentHistory = conversationRepository.loadMessages(conversationId)
            _ui.update { state ->
                state.copy(messages = agentHistory.mapNotNull { it.toUiMessage() })
            }
            projected = agentHistory + ChatMessage.User(pendingUserMessage)
        }
    }

    private fun ChatMessage.toUiMessage(): UiMessage? = when (this) {
        // PR #49 — carry the persisted JPEG so a resumed conversation re-renders
        // the photo. UserBubble decodes it lazily (decode-on-demand).
        is ChatMessage.User -> UiMessage.User(text, imageBytes = imageBytes)
        is ChatMessage.Assistant -> {
            // Skip intermediate tool-call turns. The fresh-conversation path
            // surfaces only the FINAL Assistant on Done (the one with the
            // user-visible reply text and `toolCall == null`); rebuilding
            // history from persistence has to mirror that or we end up
            // rendering an empty leading bubble before each real response.
            if (toolCall != null) null
            else UiMessage.Assistant(
                text = text,
                citations = citations,
                fromCache = false,
                renderMarkdown = renderMarkdown,
            )
        }
        is ChatMessage.Tool -> null // tool turns aren't shown in the UI bubble list
        is ChatMessage.System -> null
    }

    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TokenChunk -> _ui.update {
                it.copy(partialText = it.partialText + event.text)
            }
            is AgentEvent.SearchStarted -> _ui.update {
                it.copy(searchStatus = SearchStatus.Searching(event.query))
            }
            is AgentEvent.SearchCompleted -> _ui.update {
                it.copy(searchStatus = event.outcome.toSearchStatus())
            }
            is AgentEvent.Done -> {
                agentHistory = agentHistory + event.turnMessages
                _ui.update {
                    val cacheHit = it.searchStatus is SearchStatus.CompletedFromCache
                    it.copy(
                        messages = it.messages + UiMessage.Assistant(
                            text = event.message.text,
                            citations = event.message.citations,
                            fromCache = cacheHit,
                            renderMarkdown = event.message.renderMarkdown,
                        ),
                        partialText = "",
                        searchStatus = SearchStatus.None,
                        isGenerating = false,
                        isCancelling = false,
                    )
                }
                persistAgentTurnMessages(event.turnMessages)
                runMemoryExtraction(event)
            }
            is AgentEvent.Error -> _ui.update {
                it.copy(
                    error = event.message,
                    partialText = "",
                    searchStatus = SearchStatus.None,
                    isGenerating = false,
                    isCancelling = false,
                )
            }
        }
    }

    /**
     * Persist everything the loop appended this turn EXCEPT the leading
     * user message (already persisted at send time so it survives a mid-turn
     * crash). Tool and assistant turns land in chronological order.
     */
    private fun persistAgentTurnMessages(turnMessages: List<ChatMessage>) {
        val convId = _conversationId.value ?: return
        val toPersist = turnMessages.dropWhile { it is ChatMessage.User }
        if (toPersist.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            for (msg in toPersist) {
                runCatching {
                    conversationRepository.appendMessage(
                        conversationId = convId,
                        message = msg,
                        nowEpochMs = now,
                    )
                }.onFailure { Log.w(TAG, "persist turn message failed", it) }
            }
        }
    }

    /**
     * M5 Phase D: post-turn memory extraction. Fire-and-forget on
     * [Dispatchers.IO] so a slow extractor can never block the UI or the
     * next user turn. The extractor itself catches every failure path —
     * we just gate on having a real user message to work with.
     */
    private fun runMemoryExtraction(event: AgentEvent.Done) {
        // PR #37 — the deterministic WEATHER path resolves a city + state/
        // province from the user's own words and asks us to remember it (so a
        // later bare "what's the weather?" reuses it). Surfaced as the same
        // Save / Dismiss consent card as classifier-proposed memories; dedups,
        // so repeated weather queries for the same place don't re-prompt. Runs
        // independently of the classifier extractor below — weather turns set
        // skipMemoryExtraction = true, so without this they'd propose nothing.
        event.locationToRemember?.let { locationText ->
            val cid = _conversationId.value
            viewModelScope.launch(Dispatchers.IO) {
                val report = runCatching { memoryExtractor.proposeLocationMemory(locationText, cid) }
                    .onFailure { Log.w(TAG, "proposeLocationMemory crashed; turn already complete", it) }
                    .getOrNull()
                surfaceMemoryPrompts(report)
            }
        }
        // Deterministic clock commands ("what alarms do I have", "set a 5
        // minute timer") aren't memorable — the user is driving a tool,
        // not telling us anything about themselves. AgentLoop tags these
        // turns explicitly so we skip the extractor and avoid surfacing
        // a "save this as a preference?" prompt for trivial drive-by
        // interactions.
        if (event.skipMemoryExtraction) {
            Log.i(TAG, "skip memory extraction: turn flagged by agent loop")
            return
        }
        val userMessage = event.turnMessages
            .firstOrNull { it is ChatMessage.User }
            ?.text
            ?: return
        // PR #10 follow-up — translation turns aren't memory-worthy:
        // "translate hello world to Japanese" is a transient request, not
        // a personal preference, and surfacing a "save this?" prompt for
        // it confuses the user. Reuse the same detector that drives the
        // streamed-token filter so both paths agree on what counts as a
        // translation. Explicit `Remember: ...` commands still hit the
        // RememberForgetDetector path inside MemoryExtractor, so users who
        // want to save a translation preference can still do so via the
        // explicit prefix.
        val language = languagePreferences.preferredLanguage()
        if (translationIntentDetector.isTranslationRequest(userMessage, language)) {
            Log.i(TAG, "skip memory extraction: translation intent on user turn")
            return
        }
        val assistantText = event.message.text
        val cid = _conversationId.value
        viewModelScope.launch(Dispatchers.IO) {
            val report = runCatching {
                memoryExtractor.extract(
                    userMessage = userMessage,
                    assistantResponse = assistantText,
                    conversationId = cid,
                )
            }.onFailure {
                Log.w(TAG, "memory extraction crashed; turn already complete", it)
            }.getOrNull()

            surfaceMemoryPrompts(report)

            // Refresh the badge count after extraction settles. Sequencing
            // matters: the count must reflect the just-completed extract.
            if (cid != null) {
                runCatching { _memoryCount.value = memoryStore.countForConversation(cid) }
            }
        }
    }

    /**
     * Surface any [MemoryExtractor.ExtractionReport.PromptRequested] candidates
     * as Save / Dismiss cards. Shared by the classifier-driven extractor and
     * the PR #37 weather-location proposal. Auto-dismisses prior pending cards
     * first so only the latest decision is visible.
     */
    private fun surfaceMemoryPrompts(report: MemoryExtractor.ExtractionReport?) {
        // Explicit `Remember:` command refused at the hard cap — surface the
        // same limit dialog the consent-card path uses.
        if (report is MemoryExtractor.ExtractionReport.CapReached) {
            _memoryLimitReached.value = report.limit
            return
        }
        val hasNewPrompts = report is MemoryExtractor.ExtractionReport.PromptRequested
        if (hasNewPrompts || pendingCandidates.isNotEmpty()) {
            clearPendingPromptsAsAutoDismissed(hasNewPrompts)
        }
        if (report is MemoryExtractor.ExtractionReport.PromptRequested) {
            for (candidate in report.candidates) {
                pendingCandidates[candidate.id] = candidate
            }
            _ui.update { state ->
                state.copy(
                    messages = state.messages + report.candidates.map { candidate ->
                        UiMessage.MemoryPrompt(
                            candidateId = candidate.id,
                            text = candidate.text,
                            category = candidate.category,
                        )
                    },
                )
            }
        }
    }

    /**
     * Remove every pending prompt card from the chat list and notify the
     * extractor so the dismissed counter bumps. [auto] is true when this
     * fires automatically as a new turn arrives (vs. the user tapping
     * Dismiss on a single card via [dismissMemoryPrompt]).
     */
    private fun clearPendingPromptsAsAutoDismissed(auto: Boolean) {
        if (pendingCandidates.isEmpty()) return
        val dismissed = pendingCandidates.values.toList()
        pendingCandidates.clear()
        _ui.update { state ->
            state.copy(messages = state.messages.filterNot { it is UiMessage.MemoryPrompt })
        }
        for (candidate in dismissed) {
            memoryExtractor.dismissPromptCandidate(candidate)
        }
        Log.i(TAG, "auto-dismissed ${dismissed.size} pending prompt(s) (auto=$auto)")
    }

    /** User tapped Save on a Memory prompt card. */
    fun saveMemoryPrompt(candidateId: String) {
        // Don't remove the card up-front: if the save is refused at the hard
        // cap we leave it in place so the user can retry after deleting
        // memories in Settings → Memory.
        val candidate = pendingCandidates[candidateId] ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val outcome = memoryExtractor.acceptPromptCandidate(candidate)) {
                is MemoryExtractor.AcceptOutcome.CapReached -> {
                    _memoryLimitReached.value = outcome.limit
                }
                is MemoryExtractor.AcceptOutcome.Saved,
                is MemoryExtractor.AcceptOutcome.Deduped,
                is MemoryExtractor.AcceptOutcome.Failed -> {
                    pendingCandidates.remove(candidateId)
                    _ui.update { state ->
                        state.copy(
                            messages = state.messages.filterNot {
                                it is UiMessage.MemoryPrompt && it.candidateId == candidateId
                            },
                        )
                    }
                    if (outcome is MemoryExtractor.AcceptOutcome.Saved) {
                        val cid = candidate.conversationId
                        if (cid != null) {
                            runCatching { _memoryCount.value = memoryStore.countForConversation(cid) }
                        }
                    }
                }
            }
        }
    }

    /** User dismissed the "memory limit reached" dialog. */
    fun dismissMemoryLimitDialog() {
        _memoryLimitReached.value = null
    }

    /** User tapped Dismiss on a Memory prompt card. */
    fun dismissMemoryPrompt(candidateId: String) {
        val candidate = pendingCandidates.remove(candidateId) ?: return
        _ui.update { state ->
            state.copy(
                messages = state.messages.filterNot {
                    it is UiMessage.MemoryPrompt && it.candidateId == candidateId
                },
            )
        }
        memoryExtractor.dismissPromptCandidate(candidate)
    }

    private fun SearchOutcome.toSearchStatus(): SearchStatus = when (this) {
        is SearchOutcome.Success -> if (fromCache) SearchStatus.CompletedFromCache else SearchStatus.None
        is SearchOutcome.Error -> SearchStatus.Failed(kind.name, message)
    }

    private companion object {
        private const val TAG = "ChatViewModel"
    }
}

/**
 * UI-shaped state. The Compose layer reads this directly.
 *
 * [isCancelling] (PR #22) is `true` between the user tapping Cancel and the
 * coroutine actually unwinding. The Cancel button reads it to render
 * "Cancelling…" + disable itself so the user gets immediate feedback even
 * though the underlying native decode loop may take tens of ms to abort.
 */
data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val partialText: String = "",
    val searchStatus: SearchStatus = SearchStatus.None,
    val isGenerating: Boolean = false,
    val isCancelling: Boolean = false,
    val error: String? = null,
    /** PR #48 — thumbnail of the image staged for the next send (input chip). Null when none. */
    val pendingImageThumbnail: ImageBitmap? = null,
)

sealed interface UiMessage {
    /**
     * A user turn with an optional attached photo. [thumbnail] (PR #48) is the
     * already-decoded bitmap set on a live send. [imageBytes] (PR #49) is the
     * persisted JPEG carried back on a reloaded conversation; [UserBubble]
     * decodes it on demand so a long thread doesn't hold every bitmap at once.
     * At most one is set: live sends use [thumbnail], reloads use [imageBytes].
     */
    data class User(
        val text: String,
        val thumbnail: ImageBitmap? = null,
        val imageBytes: ByteArray? = null,
    ) : UiMessage
    data class Assistant(
        val text: String,
        val citations: List<SearchSource>,
        val fromCache: Boolean = false,
        // PR #50 — true: render markdown + LaTeX; false: plain (weather/finance cards).
        val renderMarkdown: Boolean = true,
    ) : UiMessage

    /**
     * Middle-band memory proposal — rendered inline as a Save / Dismiss
     * card immediately after the assistant bubble that produced it.
     */
    data class MemoryPrompt(
        val candidateId: String,
        val text: String,
        val category: MemoryCategory,
    ) : UiMessage
}

sealed interface SearchStatus {
    data object None : SearchStatus
    data class Searching(val query: String) : SearchStatus

    /**
     * Held briefly until the next assistant turn lands so the UI can attach a
     * "from cache" indicator to that turn. The indicator decision is made when
     * [AgentEvent.Done] arrives; this state never reaches the user directly.
     */
    data object CompletedFromCache : SearchStatus
    data class Failed(val kind: String, val message: String) : SearchStatus
}

/**
 * PR#13: payload for the overflow modal. [pendingPrompt] is the user's
 * unsent message, surfaced in the dialog body and resent when they pick
 * Continue.
 */
data class OverflowDecision(
    val pendingPrompt: String,
)
