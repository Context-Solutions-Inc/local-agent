package com.contextsolutions.localagent.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.conversation.ConversationRepository
import com.contextsolutions.localagent.conversation.ConversationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * PR#13 — backs [ConversationHistoryScreen]. Surfaces the most-recent
 * [ConversationRepository.LIST_PAGE_SIZE] conversations and proxies delete
 * actions to the repository.
 *
 * PR #8 — the list is now query-driven: [setQuery] feeds the search field, a
 * blank query yields the recent list, a non-blank one runs a full-content
 * search ([ConversationRepository.search] — title OR any message body). The
 * query flow is debounced so typing doesn't hammer the DB. [refresh] bumps a
 * trigger so screen re-entry re-runs whatever the current query is (a delete or
 * a new conversation finished elsewhere shows up without a pull-to-refresh).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ConversationHistoryViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Bumped to force a re-query on screen re-entry / after a delete. */
    private val refreshTrigger = MutableStateFlow(0)

    val conversations: StateFlow<List<ConversationSummary>> =
        combine(_query.debounce(QUERY_DEBOUNCE_MS), refreshTrigger) { q, _ -> q }
            .onEach { _loading.value = true }
            .mapLatest { q ->
                try {
                    if (q.isBlank()) repository.listRecent() else repository.search(q.trim())
                } finally {
                    _loading.value = false
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun refresh() {
        refreshTrigger.value += 1
    }

    fun delete(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(conversationId)
            refresh()
        }
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 200L
    }
}
