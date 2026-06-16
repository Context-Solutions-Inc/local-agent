package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.todo.TodoPriority

/**
 * Typed result of [TodoCommandParser.parse]. Each sub-type maps 1:1 to a
 * [TodoToolHandler] tool call. Mirrors the [ClockCommand] sealed interface
 * so the agent-loop wiring stays uniform between the two domains.
 */
sealed interface TodoCommand {

    /** Create a new TODO. */
    data class Add(
        val title: String,
        val priority: TodoPriority?,
        val dueDateEpochMs: Long?,
    ) : TodoCommand

    /** Read-only list. [includeCompleted] toggles the active-only filter. */
    data class List(val includeCompleted: Boolean) : TodoCommand

    /** Flip the completed flag on a TODO referenced by [ref]. */
    data class SetCompleted(val ref: TodoRef, val completed: Boolean) : TodoCommand

    data class Delete(val ref: TodoRef) : TodoCommand

    data class SetPriority(val ref: TodoRef, val priority: TodoPriority) : TodoCommand

    /** Set or clear a due date. Null [dueDateEpochMs] clears the field. */
    data class SetDueDate(val ref: TodoRef, val dueDateEpochMs: Long?) : TodoCommand

    data class SetTitle(val ref: TodoRef, val title: String) : TodoCommand

    /** Delete every completed TODO. */
    data object ClearCompleted : TodoCommand
}

/**
 * How a chat command refers to a TODO: either a 1-based [Index] from the
 * most recent `list_todos` reply, or a case-insensitive [TitleSubstring]
 * match.
 *
 * Indices are convenient in conversation ("complete #2") and unambiguous
 * provided the user hasn't mutated the list elsewhere between the list
 * call and the next reference. [TodoToolHandler] keeps a small
 * `lastListedIds` cache and invalidates it on any size change.
 */
sealed interface TodoRef {
    data class Index(val oneBased: Int) : TodoRef
    data class TitleSubstring(val needle: String) : TodoRef
}
