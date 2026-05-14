package com.contextsolutions.mobileagent.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.contextsolutions.mobileagent.agent.AgentEvent
import com.contextsolutions.mobileagent.agent.AgentTurnInput
import com.contextsolutions.mobileagent.agent.ChatMessage
import com.contextsolutions.mobileagent.app.di.AgentLoopFactory
import com.contextsolutions.mobileagent.app.service.InferenceSessionAdapter
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.app.service.ModelInventory
import com.contextsolutions.mobileagent.app.service.SessionState
import com.contextsolutions.mobileagent.inference.ThermalStatus
import com.contextsolutions.mobileagent.inference.ThermalStatusProvider
import com.contextsolutions.mobileagent.memory.MemoryCategory
import com.contextsolutions.mobileagent.memory.MemoryExtractor
import com.contextsolutions.mobileagent.memory.MemoryPromptCandidate
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.search.SearchOutcome
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
 * History is held in memory; a future polish pass will persist it through
 * `MobileAgentDatabase.conversations` + `messages`. The agent-side history
 * (including intermediate tool-call / tool-result messages) is tracked
 * separately from the user-visible bubble list so Gemma sees the full context
 * on follow-up turns while the UI shows a clean conversation.
 */
@OptIn(ExperimentalUuidApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoopFactory: AgentLoopFactory,
    private val sessionManager: InferenceSessionManager,
    private val inventory: ModelInventory,
    private val memoryExtractor: MemoryExtractor,
    private val memoryStore: MemoryStore,
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

    /** Internal full conversation as the agent sees it (includes tool_call/tool turns). */
    private var agentHistory: List<ChatMessage> = emptyList()

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

    // PR #8 — aux-model warm-up moved to MainViewModel.warmUpEagerly so
    // it shares the chat-screen RESUME hook with Gemma. Single source of
    // truth, fires on every chat-screen entry AND on background→foreground
    // bounce, and re-fires after a 5-min idle / onTrimMemory unload.

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        currentJob?.cancel()

        // Generate a conversation ID lazily on the first send so memories
        // extracted during this chat can be grouped for the Phase E badge.
        if (_conversationId.value == null) {
            _conversationId.value = "conv-${Uuid.random()}"
        }

        // Add user bubble immediately so it appears even before the model loads.
        _ui.update {
            it.copy(
                messages = it.messages + UiMessage.User(trimmed),
                partialText = "",
                searchStatus = SearchStatus.None,
                error = null,
                isGenerating = true,
            )
        }

        val historySnapshot = agentHistory
        currentJob = viewModelScope.launch {
            try {
                val session = InferenceSessionAdapter(
                    sessionManager = sessionManager,
                    modelPath = inventory.localFile().absolutePath,
                )
                val loop = agentLoopFactory.create(session)
                loop.run(AgentTurnInput(userMessage = trimmed, history = historySnapshot)).collect { event ->
                    onAgentEvent(event)
                }
            } catch (e: CancellationException) {
                _ui.update { it.copy(isGenerating = false, partialText = "", searchStatus = SearchStatus.None) }
                throw e
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        isGenerating = false,
                        partialText = "",
                        searchStatus = SearchStatus.None,
                        error = t.message ?: t::class.simpleName,
                    )
                }
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
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
                        ),
                        partialText = "",
                        searchStatus = SearchStatus.None,
                        isGenerating = false,
                    )
                }
                runMemoryExtraction(event)
            }
            is AgentEvent.Error -> _ui.update {
                it.copy(
                    error = event.message,
                    partialText = "",
                    searchStatus = SearchStatus.None,
                    isGenerating = false,
                )
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
        val userMessage = event.turnMessages
            .firstOrNull { it is ChatMessage.User }
            ?.text
            ?: return
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

            // Auto-dismiss any prior pending prompt cards before surfacing
            // new ones. Mirrors the "only the latest candidate is visible"
            // rule — keeps the chat focused on the most-recent decision
            // without burying the user under stale prompts.
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

            // Refresh the badge count after extraction settles. Sequencing
            // matters: the count must reflect the just-completed extract.
            if (cid != null) {
                runCatching { _memoryCount.value = memoryStore.countForConversation(cid) }
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
        val candidate = pendingCandidates.remove(candidateId) ?: return
        _ui.update { state ->
            state.copy(
                messages = state.messages.filterNot {
                    it is UiMessage.MemoryPrompt && it.candidateId == candidateId
                },
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val inserted = memoryExtractor.acceptPromptCandidate(candidate)
            if (inserted != null) {
                val cid = candidate.conversationId
                if (cid != null) {
                    runCatching { _memoryCount.value = memoryStore.countForConversation(cid) }
                }
            }
        }
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

/** UI-shaped state. The Compose layer reads this directly. */
data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val partialText: String = "",
    val searchStatus: SearchStatus = SearchStatus.None,
    val isGenerating: Boolean = false,
    val error: String? = null,
)

sealed interface UiMessage {
    data class User(val text: String) : UiMessage
    data class Assistant(
        val text: String,
        val citations: List<SearchSource>,
        val fromCache: Boolean = false,
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
