package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.mylist.MyListItemPriority

/**
 * Typed result of [MyListCommandParser.parse]. Each sub-type maps 1:1 to a
 * [MyListToolHandler] tool call. Mirrors the [ClockCommand] sealed interface
 * so the agent-loop wiring stays uniform between the two domains.
 */
sealed interface MyListCommand {

    /** Create a new My List item. */
    data class Add(
        val title: String,
        val priority: MyListItemPriority?,
        val dueDateEpochMs: Long?,
    ) : MyListCommand

    /**
     * Read-only list. [includeCompleted] toggles the active-only filter.
     * [ref] non-null (PR #22) narrows the reply to a single item ("show number
     * 2 on my list" / "show the milk item on my list") — only that item renders.
     */
    data class Show(val includeCompleted: Boolean, val ref: MyListRef? = null) : MyListCommand

    /** Flip the completed flag on an item referenced by [ref]. */
    data class SetCompleted(val ref: MyListRef, val completed: Boolean) : MyListCommand

    data class Delete(val ref: MyListRef) : MyListCommand

    data class SetPriority(val ref: MyListRef, val priority: MyListItemPriority) : MyListCommand

    /** Set or clear a due date. Null [dueDateEpochMs] clears the field. */
    data class SetDueDate(val ref: MyListRef, val dueDateEpochMs: Long?) : MyListCommand

    data class SetTitle(val ref: MyListRef, val title: String) : MyListCommand

    /** Delete every completed item. */
    data object ClearCompleted : MyListCommand
}

/**
 * How a chat command refers to a My List item: either a 1-based [Index] from
 * the most recent `show_mylist` reply, or a case-insensitive [TitleSubstring]
 * match.
 *
 * Indices are convenient in conversation ("complete #2") and unambiguous
 * provided the user hasn't mutated the list elsewhere between the show
 * call and the next reference. [MyListToolHandler] keeps a small
 * `lastListedIds` cache and invalidates it on any size change.
 */
sealed interface MyListRef {
    data class Index(val oneBased: Int) : MyListRef
    data class TitleSubstring(val needle: String) : MyListRef
}
