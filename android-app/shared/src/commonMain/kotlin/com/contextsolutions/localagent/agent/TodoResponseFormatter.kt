package com.contextsolutions.localagent.agent

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Render a [TodoToolHandler] JSON result into a short human-readable
 * sentence. Mirrors [ClockResponseFormatter] — the deterministic agent
 * path uses this to surface a friendly reply without involving the LLM.
 */
class TodoResponseFormatter(
    private val clock: Clock = Clock.System,
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun format(toolName: String, resultJson: String): String {
        val obj = try {
            json.parseToJsonElement(resultJson).jsonObject
        } catch (_: Throwable) {
            return "Done."
        }
        if (obj["status"]?.jsonPrimitive?.content == "error") {
            val msg = obj["message"]?.jsonPrimitive?.content ?: "unknown error"
            return "Sorry, that didn't work: $msg"
        }
        return when (toolName) {
            TodoToolHandler.ADD_TODO_NAME -> formatAdd(obj)
            TodoToolHandler.LIST_TODOS_NAME -> formatList(obj)
            TodoToolHandler.COMPLETE_TODO_NAME -> formatComplete(obj)
            TodoToolHandler.DELETE_TODO_NAME -> formatDelete(obj)
            TodoToolHandler.EDIT_TODO_NAME -> formatEdit(obj)
            TodoToolHandler.CLEAR_COMPLETED_TODOS_NAME -> formatClearCompleted(obj)
            else -> "Done."
        }
    }

    private fun formatAdd(obj: JsonObject): String {
        val title = obj["title"]?.jsonPrimitive?.content ?: "todo"
        val priority = obj["priority"]?.jsonPrimitive?.content?.let(::priorityLabel)
        val due = obj["due_date_epoch_ms"]?.jsonPrimitive?.content?.toLongOrNull()
        return buildString {
            append("Added \"")
            append(title)
            append("\"")
            val tags = buildList {
                if (priority != null && priority != "Medium") add(priority)
                if (due != null) add("due ${dueLabel(due)}")
            }
            if (tags.isNotEmpty()) {
                append(" (")
                append(tags.joinToString(", "))
                append(")")
            }
            append(".")
        }
    }

    private fun formatList(obj: JsonObject): String {
        val rows = obj["todos"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (rows.isEmpty()) {
            val includeCompleted = obj["include_completed"]?.jsonPrimitive?.booleanOrNull ?: false
            return if (includeCompleted) "You don't have any todos."
            else "You don't have any open todos."
        }
        val lines = rows.map(::renderRow)
        val header = if (rows.size == 1) "You have one todo:" else "You have ${rows.size} todos:"
        return header + "\n" + lines.joinToString("\n")
    }

    private fun renderRow(row: JsonObject): String {
        val index = row["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val title = row["title"]?.jsonPrimitive?.content ?: ""
        val priority = row["priority"]?.jsonPrimitive?.content?.let(::priorityLabel)
        val completed = row["completed"]?.jsonPrimitive?.booleanOrNull ?: false
        val due = row["due_date_epoch_ms"]?.jsonPrimitive?.content?.toLongOrNull()
        return buildString {
            append(index)
            append(". ")
            if (completed) append("[done] ")
            append(title)
            val tags = buildList {
                if (priority != null && priority != "Medium") add(priority)
                if (due != null) add("due ${dueLabel(due)}")
            }
            if (tags.isNotEmpty()) {
                append(" (")
                append(tags.joinToString(", "))
                append(")")
            }
        }
    }

    private fun formatComplete(obj: JsonObject): String {
        val title = obj["title"]?.jsonPrimitive?.content ?: "todo"
        val completed = obj["completed"]?.jsonPrimitive?.booleanOrNull ?: true
        return if (completed) "Marked \"$title\" as done." else "Reopened \"$title\"."
    }

    private fun formatDelete(obj: JsonObject): String {
        val title = obj["title"]?.jsonPrimitive?.content ?: "todo"
        return "Deleted \"$title\"."
    }

    private fun formatEdit(obj: JsonObject): String {
        val title = obj["title"]?.jsonPrimitive?.content ?: "todo"
        val priority = obj["priority"]?.jsonPrimitive?.content?.let(::priorityLabel)
        val due = obj["due_date_epoch_ms"]?.jsonPrimitive?.content?.toLongOrNull()
        return buildString {
            append("Updated \"")
            append(title)
            append("\"")
            val tags = buildList {
                if (priority != null) add("priority $priority")
                if (due != null) add("due ${dueLabel(due)}")
            }
            if (tags.isNotEmpty()) {
                append(" (")
                append(tags.joinToString(", "))
                append(")")
            }
            append(".")
        }
    }

    private fun formatClearCompleted(obj: JsonObject): String {
        val count = obj["deleted_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return when (count) {
            0 -> "No completed todos to clear."
            1 -> "Cleared 1 completed todo."
            else -> "Cleared $count completed todos."
        }
    }

    private fun priorityLabel(raw: String): String = when (raw.uppercase()) {
        "HIGH" -> "High"
        "MEDIUM" -> "Medium"
        "LOW" -> "Low"
        else -> raw
    }

    private fun dueLabel(epochMs: Long): String {
        val tz = timeZoneProvider()
        val today: LocalDate = clock.now().toLocalDateTime(tz).date
        val target: LocalDate = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz).date
        return when (target) {
            today -> "today"
            today.plus(1, DateTimeUnit.DAY) -> "tomorrow"
            today.plus(-1, DateTimeUnit.DAY) -> "yesterday"
            else -> target.toString()
        }
    }
}
