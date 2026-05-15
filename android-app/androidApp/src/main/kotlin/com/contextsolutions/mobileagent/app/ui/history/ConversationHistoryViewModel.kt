package com.contextsolutions.mobileagent.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.conversation.ConversationRepository
import com.contextsolutions.mobileagent.conversation.ConversationSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * PR#13 — backs [ConversationHistoryScreen]. Surfaces the most-recent
 * [ConversationRepository.LIST_PAGE_SIZE] conversations and proxies delete
 * actions to the repository.
 *
 * Refreshes on every `observe()` call (i.e. each time the screen lands)
 * so a delete elsewhere — or a brand-new conversation finished mid-app —
 * shows up without a manual pull-to-refresh.
 */
@HiltViewModel
class ConversationHistoryViewModel @Inject constructor(
    private val repository: ConversationRepository,
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                _conversations.value = repository.listRecent()
            } finally {
                _loading.value = false
            }
        }
    }

    fun delete(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(conversationId)
            _conversations.value = _conversations.value.filterNot { it.id == conversationId }
        }
    }
}
