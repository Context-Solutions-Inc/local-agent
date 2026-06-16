package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings
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
 *
 * User-visible text resolves through the per-turn [Strings] (PR #96 i18n);
 * structural punctuation, indices and tool-supplied titles stay literal.
 * Defaults to [Strings.ENGLISH] so direct callers and tests stay English.
 */
class TodoResponseFormatter(
    private val clock: Clock = Clock.System,
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun format(toolName: String, resultJson: String, strings: Strings = Strings.ENGLISH): String {
        val obj = try {
            json.parseToJsonElement(resultJson).jsonObject
        } catch (_: Throwable) {
            return strings.get(StringKeys.COMMON_DONE)
        }
        if (obj["status"]?.jsonPrimitive?.content == "error") {
            val msg = obj["message"]?.jsonPrimitive?.content ?: strings.get(StringKeys.COMMON_UNKNOWN_ERROR)
            return strings.get(StringKeys.COMMON_ERROR_GENERIC, msg)
        }
        return when (toolName) {
            TodoToolHandler.ADD_TODO_NAME -> formatAdd(obj, strings)
            TodoToolHandler.LIST_TODOS_NAME -> formatList(obj, strings)
            TodoToolHandler.COMPLETE_TODO_NAME -> formatComplete(obj, strings)
            TodoToolHandler.DELETE_TODO_NAME -> formatDelete(obj, strings)
            TodoToolHandler.EDIT_TODO_NAME -> formatEdit(obj, strings)
            TodoToolHandler.CLEAR_COMPLETED_TODOS_NAME -> formatClearCompleted(obj, strings)
            else -> strings.get(StringKeys.COMMON_DONE)
        }
    }

    private fun formatAdd(obj: JsonObject, strings: Strings): String {
        val title = obj["title"]?.jsonPrimitive?.content ?: "todo"
        val priorityRaw = obj["priority"]?.jsonPrimitive?.content
        val priority = priorityRaw?.let { priorityLabel(it, strings) }
        val due = obj["due_date_epoch_ms"]?.jsonPrimitive?.content?.toLongOrNull()
        return buildString {
            append(strings.get(StringKeys.TODO_ADDED, title))
            val tags = buildList {
                if (priority != null && priorityRaw.uppercase() != "MEDIUM") add(priority)
                if (due != null) add(strings.get(StringKeys.TODO_DUE, dueLabel(due, strings)))
            }
            if (tags.isNotEmpty()) {
                append(" (")
                append(tags.joinToString(", "))
                append(")")
            }
            append(".")
        }
    }

    private fun formatList(obj: JsonObject, strings: Strings): String {
        val rows = obj["todos"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (rows.isEmpty()) {
            val includeCompleted = obj["include_completed"]?.jsonPrimitive?.booleanOrNull ?: false
            return if (includeCompleted) strings.get(StringKeys.TODO_NONE_ALL)
            else strings.get(StringKeys.TODO_NONE_OPEN)
        }
        val lines = rows.map { renderRow(it, strings) }
        val header = if (rows.size == 1) {
            strings.get(StringKeys.TODO_ONE_HEADER)
        } else {
            strings.get(StringKeys.TODO_HEADER, rows.size)
        }
        return header + "\n" + lines.joinToString("\n")
    }

    private fun renderRow(row: JsonObject, strings: Strings): String {
        val index = row["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val title = row["title"]?.jsonPrimitive?.content ?: ""
        val priorityRaw = row["priority"]?.jsonPrimitive?.content
        val priority = priorityRaw?.let { priorityLabel(it, strings) }
        val completed = row["completed"]?.jsonPrimitive?.booleanOrNull ?: false
        val due = row["due_date_epoch_ms"]?.jsonPrimitive?.content?.toLongOrNull()
        return buildString {
            append(index)
            append(". ")
            if (completed) {
                append(strings.get(StringKeys.TODO_DONE_MARKER))
                append(' ')
            }
            append(title)
            val tags = buildList {
                if (priority != null && priorityRaw.uppercase() != "MEDIUM") add(priority)
                if (due != null) add(strings.get(StringKeys.TODO_DUE, dueLabel(due, strings)))
            }
            if (tags.isNotEmpty()) {
                append(" (")
                append(tags.joinToString(", "))
                append(")")
            }
        }
    }

    private fun formatComplete(obj: JsonObject, strings: Strings): String {
        val title = obj["title"]?.jsonPrimitive?.content ?: "todo"
        val completed = obj["completed"]?.jsonPrimitive?.booleanOrNull ?: true
        return if (completed) {
            strings.get(StringKeys.TODO_MARKED_DONE, title)
        } else {
            strings.get(StringKeys.TODO_REOPENED, title)
        }
    }

    private fun formatDelete(obj: JsonObject, strings: Strings): String {
        val title = obj["title"]?.jsonPrimitive?.content ?: "todo"
        return strings.get(StringKeys.TODO_DELETED, title)
    }

    private fun formatEdit(obj: JsonObject, strings: Strings): String {
        val title = obj["title"]?.jsonPrimitive?.content ?: "todo"
        val priority = obj["priority"]?.jsonPrimitive?.content?.let { priorityLabel(it, strings) }
        val due = obj["due_date_epoch_ms"]?.jsonPrimitive?.content?.toLongOrNull()
        return buildString {
            append(strings.get(StringKeys.TODO_UPDATED, title))
            val tags = buildList {
                if (priority != null) add(strings.get(StringKeys.TODO_PRIORITY_TAG, priority))
                if (due != null) add(strings.get(StringKeys.TODO_DUE, dueLabel(due, strings)))
            }
            if (tags.isNotEmpty()) {
                append(" (")
                append(tags.joinToString(", "))
                append(")")
            }
            append(".")
        }
    }

    private fun formatClearCompleted(obj: JsonObject, strings: Strings): String {
        val count = obj["deleted_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return if (count == 0) {
            strings.get(StringKeys.TODO_CLEAR_NONE)
        } else {
            strings.plural(StringKeys.TODO_CLEARED, count, count)
        }
    }

    private fun priorityLabel(raw: String, strings: Strings): String = when (raw.uppercase()) {
        "HIGH" -> strings.get(StringKeys.TODO_PRIORITY_HIGH)
        "MEDIUM" -> strings.get(StringKeys.TODO_PRIORITY_MEDIUM)
        "LOW" -> strings.get(StringKeys.TODO_PRIORITY_LOW)
        else -> raw
    }

    private fun dueLabel(epochMs: Long, strings: Strings): String {
        val tz = timeZoneProvider()
        val today: LocalDate = clock.now().toLocalDateTime(tz).date
        val target: LocalDate = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz).date
        return when (target) {
            today -> strings.get(StringKeys.TODO_DUE_TODAY)
            today.plus(1, DateTimeUnit.DAY) -> strings.get(StringKeys.TODO_DUE_TOMORROW)
            today.plus(-1, DateTimeUnit.DAY) -> strings.get(StringKeys.TODO_DUE_YESTERDAY)
            else -> target.toString()
        }
    }
}
