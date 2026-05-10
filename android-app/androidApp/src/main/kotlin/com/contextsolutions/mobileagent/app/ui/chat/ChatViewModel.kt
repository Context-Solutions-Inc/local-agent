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
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.memory.EmbedderEngine
import com.contextsolutions.mobileagent.memory.MemoryExtractor
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val classifierEngine: ClassifierEngine,
    private val embedderEngine: EmbedderEngine,
    private val memoryExtractor: MemoryExtractor,
    private val memoryStore: MemoryStore,
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = sessionManager.state

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

    private var currentJob: Job? = null

    init {
        // M4 Phase B: warm the pre-flight classifier on the IO dispatcher as
        // soon as the user enters the chat screen. The 100-500 ms load latency
        // hides behind user typing time. Failure is logged but never surfaced
        // to UI — PreflightRouter (Phase C) sees isLoaded=false and falls
        // through to standard Gemma tool-calling per PRD §3.2.1.
        viewModelScope.launch(Dispatchers.IO) {
            val accelerator = classifierEngine.warmUp()
            if (accelerator != null) {
                Log.i(TAG, "pre-flight classifier ready on $accelerator")
            } else {
                Log.w(TAG, "pre-flight classifier unavailable; agent will fall through to Gemma")
            }
        }

        // M5 Phase B: warm the embedder in parallel. Once loaded the
        // memory subsystem (retrieval + extraction, Phases C/D) is ready.
        // Failure is silent — MemoryRetriever / MemoryExtractor see
        // isLoaded=false and degrade to no-op (PRD §3.2.4 graceful
        // degradation).
        viewModelScope.launch(Dispatchers.IO) {
            val accelerator = embedderEngine.warmUp()
            if (accelerator != null) {
                Log.i(TAG, "memory embedder ready on $accelerator")
            } else {
                Log.w(TAG, "memory embedder unavailable; memory subsystem will be inert")
            }
        }
    }

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

    /** Debug helper retained from M1; useful for manually exercising cold-load + reload. */
    fun forceUnload() {
        sessionManager.forceUnload()
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
            runCatching {
                memoryExtractor.extract(
                    userMessage = userMessage,
                    assistantResponse = assistantText,
                    conversationId = cid,
                )
            }.onFailure {
                Log.w(TAG, "memory extraction crashed; turn already complete", it)
            }
            // Refresh the badge count after extraction settles. Sequencing
            // matters: the count must reflect the just-completed extract.
            if (cid != null) {
                runCatching { _memoryCount.value = memoryStore.countForConversation(cid) }
            }
        }
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
