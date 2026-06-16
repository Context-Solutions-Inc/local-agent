package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.inference.PendingToolCall
import com.contextsolutions.localagent.inference.ToolDefinition
import com.contextsolutions.localagent.todo.Todo
import com.contextsolutions.localagent.todo.TodoPriority
import com.contextsolutions.localagent.todo.TodoRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool handler that exposes the on-device TODO list to the agent layer. Six
 * tools:
 *
 *  - [ADD_TODO_NAME] — create a new TODO
 *  - [LIST_TODOS_NAME] — read the ordered list
 *  - [COMPLETE_TODO_NAME] — flip the completed flag
 *  - [DELETE_TODO_NAME] — hard-delete a TODO
 *  - [EDIT_TODO_NAME] — patch one or more fields
 *  - [CLEAR_COMPLETED_TODOS_NAME] — bulk-delete completed
 *
 * These tools are dispatched ONLY from the deterministic
 * [TodoCommandParser] path in [AgentLoop]; the model never invokes them
 * (the short-circuit fires upstream, and on partial parse we emit static
 * guidance rather than letting Gemma reach for the schemas). The
 * [ToolDefinition]s are still advertised for structural uniformity with
 * the clock surface — keeps the tool-handler registration path identical
 * between the two domains.
 *
 * **Index references**: after every `list_todos` call the handler caches
 * the returned id order in [lastListedIds]. Subsequent chat references
 * like `complete #2` resolve through this cache. The cache invalidates
 * any time a mutation changes the list size, so a 1-based index from a
 * stale list won't silently target the wrong row — the handler returns
 * a structured error instead.
 */
@OptIn(ExperimentalUuidApi::class)
class TodoToolHandler(
    private val repository: TodoRepository,
    private val clock: Clock = Clock.System,
) : ToolHandler {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Snapshot of the id order from the most recent [LIST_TODOS_NAME]
     * call. Updated atomically as part of each list response and cleared
     * whenever a mutation observably changes the list size.
     */
    @Volatile private var lastListedIds: List<String> = emptyList()

    override val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(ADD_TODO_NAME, ADD_TODO_SCHEMA),
        ToolDefinition(LIST_TODOS_NAME, LIST_TODOS_SCHEMA),
        ToolDefinition(COMPLETE_TODO_NAME, COMPLETE_TODO_SCHEMA),
        ToolDefinition(DELETE_TODO_NAME, DELETE_TODO_SCHEMA),
        ToolDefinition(EDIT_TODO_NAME, EDIT_TODO_SCHEMA),
        ToolDefinition(CLEAR_COMPLETED_TODOS_NAME, CLEAR_COMPLETED_TODOS_SCHEMA),
    )

    private val toolNames: Set<String> = definitions.map { it.name }.toSet()

    override fun handles(toolName: String): Boolean = toolName in toolNames

    override suspend fun execute(call: PendingToolCall): String {
        val args = parseArgs(call.argumentsJson) ?: return errorPayload("could not parse arguments")
        return when (call.name) {
            ADD_TODO_NAME -> handleAdd(args)
            LIST_TODOS_NAME -> handleList(args)
            COMPLETE_TODO_NAME -> handleComplete(args)
            DELETE_TODO_NAME -> handleDelete(args)
            EDIT_TODO_NAME -> handleEdit(args)
            CLEAR_COMPLETED_TODOS_NAME -> handleClearCompleted()
            else -> errorPayload("unknown tool '${call.name}'")
        }
    }

    private suspend fun handleAdd(args: JsonObject): String {
        val title = args["title"]?.asStringOrNull()?.trim()
            ?: return errorPayload("'title' is required")
        if (title.isBlank()) return errorPayload("'title' is required")
        val priority = args["priority"]?.asStringOrNull()?.let(::parsePriority) ?: TodoPriority.MEDIUM
        val dueDate = args["due_date_epoch_ms"]?.asLong()
        val notes = args["notes"]?.asStringOrNull()
        val now = clock.now().toEpochMilliseconds()
        val todo = repository.create(
            id = newTodoId(),
            title = title,
            priority = priority,
            dueDateEpochMs = dueDate,
            notes = notes,
            nowEpochMs = now,
        )
        invalidateListCache()
        return resultJson {
            put("status", "ok")
            put("id", todo.id)
            put("title", todo.title)
            put("priority", todo.priority.name)
            todo.dueDateEpochMs?.let { put("due_date_epoch_ms", it) }
        }
    }

    private suspend fun handleList(args: JsonObject): String {
        val includeCompleted = args["include_completed"]?.asBoolean() ?: false
        val todos = if (includeCompleted) repository.snapshot() else repository.snapshotActive()
        lastListedIds = todos.map { it.id }
        return resultJson {
            put("status", "ok")
            put("count", todos.size)
            put("include_completed", includeCompleted)
            put("todos", buildJsonArray {
                todos.forEachIndexed { i, t ->
                    add(buildJsonObject {
                        put("index", (i + 1).toLong())
                        put("id", t.id)
                        put("title", t.title)
                        put("priority", t.priority.name)
                        put("completed", t.completed)
                        t.dueDateEpochMs?.let { put("due_date_epoch_ms", it) }
                    })
                }
            })
        }
    }

    private suspend fun handleComplete(args: JsonObject): String {
        val completed = args["completed"]?.asBoolean() ?: true
        val target = resolveRef(args) ?: return errorPayload("could not find a matching todo")
        val now = clock.now().toEpochMilliseconds()
        val updated = repository.setCompleted(target.id, completed, now)
            ?: return errorPayload("todo no longer exists")
        return resultJson {
            put("status", "ok")
            put("id", updated.id)
            put("title", updated.title)
            put("completed", updated.completed)
        }
    }

    private suspend fun handleDelete(args: JsonObject): String {
        val target = resolveRef(args) ?: return errorPayload("could not find a matching todo")
        val deleted = repository.delete(target.id)
        if (!deleted) return errorPayload("todo no longer exists")
        invalidateListCache()
        return resultJson {
            put("status", "ok")
            put("id", target.id)
            put("title", target.title)
        }
    }

    private suspend fun handleEdit(args: JsonObject): String {
        val target = resolveRef(args) ?: return errorPayload("could not find a matching todo")
        val newTitle = args["title"]?.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: target.title
        val priorityArg = args["priority"]?.asStringOrNull()
        val priority = priorityArg?.let(::parsePriority) ?: target.priority
        // due_date_epoch_ms: omit → unchanged; explicit JSON null → cleared.
        val dueDate: Long? = when {
            args.containsKey("due_date_epoch_ms") -> {
                val el = args["due_date_epoch_ms"]
                if (el is JsonPrimitive && el.content == "null") null
                else el?.asLong() ?: target.dueDateEpochMs
            }
            else -> target.dueDateEpochMs
        }
        val notes: String? = when {
            args.containsKey("notes") -> args["notes"]?.asStringOrNull()
            else -> target.notes
        }
        val now = clock.now().toEpochMilliseconds()
        val updated = repository.update(
            target.copy(
                title = newTitle,
                priority = priority,
                dueDateEpochMs = dueDate,
                notes = notes,
            ),
            nowEpochMs = now,
        ) ?: return errorPayload("todo no longer exists")
        return resultJson {
            put("status", "ok")
            put("id", updated.id)
            put("title", updated.title)
            put("priority", updated.priority.name)
            updated.dueDateEpochMs?.let { put("due_date_epoch_ms", it) }
        }
    }

    private suspend fun handleClearCompleted(): String {
        val deleted = repository.deleteCompleted()
        invalidateListCache()
        return resultJson {
            put("status", "ok")
            put("deleted_count", deleted.toLong())
        }
    }

    /**
     * Resolve a ref-shaped args bundle (`id` / `index` / `title_substring`)
     * to an actual [Todo]. Returns null when nothing matches; the caller
     * formats that into an error response.
     */
    private suspend fun resolveRef(args: JsonObject): Todo? {
        val id = args["id"]?.asStringOrNull()
        val index = args["index"]?.asInt()
        val needle = args["title_substring"]?.asStringOrNull()
        return when {
            id != null -> repository.get(id)
            index != null -> {
                val cache = lastListedIds
                val oneBased = index
                if (oneBased < 1 || oneBased > cache.size) return null
                repository.get(cache[oneBased - 1])
            }
            needle != null -> matchByTitle(needle)
            else -> null
        }
    }

    private suspend fun matchByTitle(needle: String): Todo? {
        val canon = needle.trim().lowercase()
        if (canon.isBlank()) return null
        val all = repository.snapshot()
        // Active rows preferred so "complete the milk" doesn't match an
        // already-completed milk task. If no active match, fall through
        // to completed (so 'reopen the milk' still works).
        val active = all.filter { !it.completed && it.title.lowercase().contains(canon) }
        if (active.size == 1) return active.single()
        if (active.isNotEmpty()) return null // ambiguous: refuse to guess
        val anyMatch = all.filter { it.title.lowercase().contains(canon) }
        return if (anyMatch.size == 1) anyMatch.single() else null
    }

    private fun invalidateListCache() {
        // Mutations always desync the cached indices; clearing here forces
        // a fresh `list_todos` before the next #N reference can resolve.
        lastListedIds = emptyList()
    }

    private fun parseArgs(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (_: Throwable) {
        try {
            json.parseToJsonElement(if (raw.isBlank()) "{}" else raw).jsonObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun resultJson(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject(build))

    private fun errorPayload(message: String): String = resultJson {
        put("status", "error")
        put("message", message)
    }

    private fun newTodoId(): String = "todo-${Uuid.random()}"

    private fun parsePriority(raw: String): TodoPriority? = when (raw.trim().uppercase()) {
        "LOW" -> TodoPriority.LOW
        "MEDIUM", "MED", "NORMAL" -> TodoPriority.MEDIUM
        "HIGH", "URGENT" -> TodoPriority.HIGH
        else -> null
    }

    private fun kotlinx.serialization.json.JsonElement.asInt(): Int? = try {
        val p = (this as? JsonPrimitive) ?: return null
        val content = p.content
        content.toIntOrNull() ?: content.toDoubleOrNull()?.toInt()
    } catch (_: Throwable) {
        null
    }

    private fun kotlinx.serialization.json.JsonElement.asLong(): Long? = try {
        val p = (this as? JsonPrimitive) ?: return null
        val content = p.content
        content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong()
    } catch (_: Throwable) {
        null
    }

    private fun kotlinx.serialization.json.JsonElement.asBoolean(): Boolean? = try {
        (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.content.takeIf { it.isNotBlank() }
    }

    companion object {
        const val ADD_TODO_NAME = "add_todo"
        const val LIST_TODOS_NAME = "list_todos"
        const val COMPLETE_TODO_NAME = "complete_todo"
        const val DELETE_TODO_NAME = "delete_todo"
        const val EDIT_TODO_NAME = "edit_todo"
        const val CLEAR_COMPLETED_TODOS_NAME = "clear_completed_todos"

        // Schemas are intentionally terse. The deterministic parser is the
        // canonical entry point for these tools — the LLM rarely (in
        // practice never) sees a TODO turn, since intent detection upstream
        // short-circuits with either a deterministic dispatch or a
        // guidance reply. We still ship schemas so the ToolHandler contract
        // stays uniform across domains.
        const val ADD_TODO_SCHEMA = """{
  "name": "add_todo",
  "description": "Create a TODO list item.",
  "parameters": {
    "type": "object",
    "properties": {
      "title": {"type": "string", "description": "The short text the user wants to remember."},
      "priority": {"type": "string", "description": "One of LOW, MEDIUM, HIGH. Defaults to MEDIUM."},
      "due_date_epoch_ms": {"type": "integer", "description": "Optional due date as epoch ms (midnight, local time zone)."},
      "notes": {"type": "string", "description": "Optional free-form notes."}
    },
    "required": ["title"]
  }
}"""

        const val LIST_TODOS_SCHEMA = """{
  "name": "list_todos",
  "description": "List TODO items. By default, active (not-completed) only.",
  "parameters": {
    "type": "object",
    "properties": {
      "include_completed": {"type": "boolean", "description": "If true, completed TODOs are included."}
    }
  }
}"""

        const val COMPLETE_TODO_SCHEMA = """{
  "name": "complete_todo",
  "description": "Mark a TODO as completed (or reopen one). Reference the item by id, index (1-based from the most recent list_todos), or title substring.",
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string", "description": "Exact id from list_todos."},
      "index": {"type": "integer", "description": "1-based index from the most recent list_todos."},
      "title_substring": {"type": "string", "description": "Case-insensitive substring match on title."},
      "completed": {"type": "boolean", "description": "If false, reopen instead of completing. Defaults to true."}
    }
  }
}"""

        const val DELETE_TODO_SCHEMA = """{
  "name": "delete_todo",
  "description": "Hard-delete a TODO. Reference by id, index, or title substring.",
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "index": {"type": "integer"},
      "title_substring": {"type": "string"}
    }
  }
}"""

        const val EDIT_TODO_SCHEMA = """{
  "name": "edit_todo",
  "description": "Patch one or more fields on a TODO. Fields omitted from the call are left unchanged.",
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "index": {"type": "integer"},
      "title_substring": {"type": "string"},
      "title": {"type": "string"},
      "priority": {"type": "string"},
      "due_date_epoch_ms": {"type": "integer"},
      "notes": {"type": "string"}
    }
  }
}"""

        const val CLEAR_COMPLETED_TODOS_SCHEMA = """{
  "name": "clear_completed_todos",
  "description": "Hard-delete every completed TODO.",
  "parameters": {"type": "object", "properties": {}}
}"""
    }
}
