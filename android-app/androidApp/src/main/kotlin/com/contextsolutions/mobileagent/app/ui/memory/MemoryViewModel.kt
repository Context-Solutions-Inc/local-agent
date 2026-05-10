package com.contextsolutions.mobileagent.app.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.memory.Memory
import com.contextsolutions.mobileagent.memory.MemoryCategory
import com.contextsolutions.mobileagent.memory.MemoryPreferences
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.platform.AgentClock
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs both [MemoryScreen] (full inventory grouped by category) and
 * [ConversationMemoryListScreen] (memories created in a single chat) per
 * PRD §3.2.4. Phase E surface for the user-control story; the underlying
 * store, extractor, and preferences ship in Phases B-D.
 *
 * **State strategy.** The store is small (≤ 1,000 rows) so we just reload
 * the full list on every action. No reactive `Flow<List<Memory>>` from
 * SQLDelight — that surface isn't wired up and the snapshot pattern keeps
 * the ViewModel a thin DAO consumer. After every mutation we call
 * [refresh] which re-runs `store.listAll()` on IO.
 *
 * **Per-conversation view.** [observeConversation] exposes a separate
 * `StateFlow<List<Memory>>` keyed off the same store. The
 * [ConversationMemoryListScreen] sets the conversation ID on entry; the
 * flow re-emits when [refresh] runs.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val store: MemoryStore,
    private val preferences: MemoryPreferences,
    private val clock: AgentClock,
) : ViewModel() {

    /**
     * Dispatcher used for all store reads/writes. Production keeps the
     * default `Dispatchers.IO`; tests reassign to a `TestDispatcher` so
     * `runTest`'s virtual scheduler can advance to completion. Visible-
     * for-testing pattern — Hilt only constructs the ViewModel; this
     * field is mutable on purpose.
     */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(
        MemoryUiState(
            memoriesByCategory = emptyMap(),
            totalCount = 0,
            creationEnabled = preferences.creationEnabled(),
            isLoading = true,
        ),
    )
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    private val _conversationMemories = MutableStateFlow<List<Memory>>(emptyList())
    val conversationMemories: StateFlow<List<Memory>> = _conversationMemories.asStateFlow()

    private var currentConversationId: String? = null

    /**
     * Re-load the full inventory + creation toggle. Compose screens call
     * this from a `LaunchedEffect(Unit)` on entry so the ViewModel doesn't
     * have to auto-load in `init` (which would force a dispatcher
     * commitment before the screen has a chance to attach test fixtures).
     * Safe to call repeatedly.
     */
    fun refresh() {
        viewModelScope.launch {
            val all = withContext(ioDispatcher) { store.listAll() }
            val grouped = MemoryCategory.entries.associateWith { category ->
                all.filter { it.category == category }
            }
            _state.value = MemoryUiState(
                memoriesByCategory = grouped,
                totalCount = all.size,
                creationEnabled = preferences.creationEnabled(),
                isLoading = false,
            )
            // Refresh the conversation slice too so the per-chat screen
            // doesn't fall behind after a delete.
            currentConversationId?.let { reloadConversation(it) }
        }
    }

    /** Switch the [conversationMemories] flow to track [conversationId]. */
    fun observeConversation(conversationId: String) {
        currentConversationId = conversationId
        viewModelScope.launch { reloadConversation(conversationId) }
    }

    /** Stop tracking the per-conversation slice. Call on screen exit. */
    fun stopObservingConversation() {
        currentConversationId = null
        _conversationMemories.value = emptyList()
    }

    fun onDelete(memoryId: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) { store.deleteById(memoryId) }
            refresh()
        }
    }

    fun onClearAll() {
        viewModelScope.launch {
            withContext(ioDispatcher) { store.deleteAll() }
            refresh()
        }
    }

    fun onToggleCreation(enabled: Boolean) {
        preferences.setCreationEnabled(enabled)
        // No store mutation, but bring `state.creationEnabled` in sync.
        _state.value = _state.value.copy(creationEnabled = enabled)
    }

    private suspend fun reloadConversation(conversationId: String) {
        val rows = withContext(ioDispatcher) { store.listForConversation(conversationId) }
        _conversationMemories.value = rows
    }
}

/**
 * UI-shaped state. Compose reads this directly. The grouping is precomputed
 * so the screen doesn't recompute it on every recomposition.
 */
data class MemoryUiState(
    val memoriesByCategory: Map<MemoryCategory, List<Memory>>,
    val totalCount: Int,
    val creationEnabled: Boolean,
    val isLoading: Boolean,
)
