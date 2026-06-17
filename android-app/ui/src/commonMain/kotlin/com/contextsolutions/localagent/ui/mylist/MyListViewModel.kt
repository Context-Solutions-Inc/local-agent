package com.contextsolutions.localagent.ui.mylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.mylist.MyListItem
import com.contextsolutions.localagent.mylist.MyListItemPriority
import com.contextsolutions.localagent.mylist.MyListRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * UI-facing state for the My List management screen. The repository's [flow]
 * is the source of truth; this view-model just re-exposes it on
 * [viewModelScope] and routes user actions back through the repository on
 * [Dispatchers.IO] so writes never touch the main thread.
 *
 * Chat-side mutations (through [MyListToolHandler]) and UI-side mutations
 * (through this VM) hit the same singleton repository, so both views stay
 * live without any cross-coupling between the two surfaces.
 */
@OptIn(ExperimentalUuidApi::class)
class MyListViewModel(
    private val repository: MyListRepository,
) : ViewModel() {

    val items: StateFlow<List<MyListItem>> = repository.flow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Active (not-completed) count — used by the chat header badge. */
    val activeCount: StateFlow<Int> = repository.flow()
        .map { list -> list.count { !it.completed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun createItem(
        title: String,
        priority: MyListItemPriority,
        dueDateEpochMs: Long?,
        notes: String?,
    ) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.create(
                id = "mylist-${Uuid.random()}",
                title = trimmed,
                priority = priority,
                dueDateEpochMs = dueDateEpochMs,
                notes = notes?.takeIf { it.isNotBlank() },
                nowEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    fun updateItem(item: MyListItem) {
        if (item.title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(item, Clock.System.now().toEpochMilliseconds())
        }
    }

    fun setCompleted(id: String, completed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setCompleted(id, completed, Clock.System.now().toEpochMilliseconds())
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(id)
        }
    }

    fun clearCompleted() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCompleted()
        }
    }
}
