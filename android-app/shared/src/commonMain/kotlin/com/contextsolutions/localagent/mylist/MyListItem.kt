package com.contextsolutions.localagent.mylist

/**
 * A single My List entry. Persistence is SQLDelight-backed (see MyList.sq);
 * domain shape is decoupled from the row shape so the SQL column order
 * (load-bearing for CLAUDE.md invariant #20) doesn't dictate Kotlin field
 * order.
 *
 * `dueDateEpochMs` and `notes` are nullable: an item with no due date or no
 * notes is the common case, not an exceptional one.
 */
data class MyListItem(
    val id: String,
    val title: String,
    val priority: MyListItemPriority,
    val dueDateEpochMs: Long?,
    val completed: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val notes: String?,
)

/**
 * Three-level priority, surfaced verbatim in chat replies and the priority
 * chip on the management screen. Stored as TEXT in SQLite (see MyList.sq) so
 * a future reordering of these constants doesn't break the on-disk format.
 */
enum class MyListItemPriority { LOW, MEDIUM, HIGH }
