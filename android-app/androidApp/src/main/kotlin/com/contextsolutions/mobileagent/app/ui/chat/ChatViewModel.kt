package com.contextsolutions.mobileagent.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.agent.AgentEvent
import com.contextsolutions.mobileagent.agent.AgentTurnInput
import com.contextsolutions.mobileagent.agent.ChatMessage
import com.contextsolutions.mobileagent.app.di.AgentLoopFactory
import com.contextsolutions.mobileagent.app.service.InferenceSessionAdapter
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.app.service.ModelInventory
import com.contextsolutions.mobileagent.app.service.SessionState
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoopFactory: AgentLoopFactory,
    private val sessionManager: InferenceSessionManager,
    private val inventory: ModelInventory,
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = sessionManager.state

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    /** Internal full conversation as the agent sees it (includes tool_call/tool turns). */
    private var agentHistory: List<ChatMessage> = emptyList()

    private var currentJob: Job? = null

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        currentJob?.cancel()

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

    fun newConversation() {
        currentJob?.cancel()
        agentHistory = emptyList()
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

    private fun SearchOutcome.toSearchStatus(): SearchStatus = when (this) {
        is SearchOutcome.Success -> if (fromCache) SearchStatus.CompletedFromCache else SearchStatus.None
        is SearchOutcome.Error -> SearchStatus.Failed(kind.name, message)
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
