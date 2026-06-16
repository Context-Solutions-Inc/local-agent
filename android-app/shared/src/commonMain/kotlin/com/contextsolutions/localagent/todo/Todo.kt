package com.contextsolutions.localagent.todo

/**
 * A single TODO entry. Persistence is SQLDelight-backed (see Todos.sq);
 * domain shape is decoupled from the row shape so the SQL column order
 * (load-bearing for CLAUDE.md invariant #20) doesn't dictate Kotlin field
 * order.
 *
 * `dueDateEpochMs` and `notes` are nullable: a TODO with no due date or no
 * notes is the common case, not an exceptional one.
 */
data class Todo(
    val id: String,
    val title: String,
    val priority: TodoPriority,
    val dueDateEpochMs: Long?,
    val completed: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val notes: String?,
)

/**
 * Three-level priority, surfaced verbatim in chat replies and the priority
 * chip on the management screen. Stored as TEXT in SQLite (see Todos.sq) so
 * a future reordering of these constants doesn't break the on-disk format.
 */
enum class TodoPriority { LOW, MEDIUM, HIGH }
