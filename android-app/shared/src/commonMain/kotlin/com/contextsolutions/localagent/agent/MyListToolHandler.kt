package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.inference.PendingToolCall
import com.contextsolutions.localagent.inference.ToolDefinition
import com.contextsolutions.localagent.mylist.MyListItem
import com.contextsolutions.localagent.mylist.MyListItemPriority
import com.contextsolutions.localagent.mylist.MyListRepository
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
 * Tool handler that exposes the on-device My List to the agent layer. Six
 * tools:
 *
 *  - [ADD_ITEM_NAME] — create a new item
 *  - [SHOW_LIST_NAME] — read the ordered list
 *  - [COMPLETE_ITEM_NAME] — flip the completed flag
 *  - [DELETE_ITEM_NAME] — hard-delete an item
 *  - [EDIT_ITEM_NAME] — patch one or more fields
 *  - [CLEAR_COMPLETED_NAME] — bulk-delete completed
 *
 * These tools are dispatched ONLY from the deterministic
 * [MyListCommandParser] path in [AgentLoop]; the model never invokes them
 * (the short-circuit fires upstream, and on partial parse we emit static
 * guidance rather than letting Gemma reach for the schemas). The
 * [ToolDefinition]s are still advertised for structural uniformity with
 * the clock surface — keeps the tool-handler registration path identical
 * between the two domains.
 *
 * **Index references**: after every `show_mylist` call the handler caches
 * the returned id order in [lastListedIds]. Subsequent chat references
 * like `complete #2` resolve through this cache. The cache invalidates
 * any time a mutation changes the list size, so a 1-based index from a
 * stale list won't silently target the wrong row — the handler returns
 * a structured error instead.
 */
@OptIn(ExperimentalUuidApi::class)
class MyListToolHandler(
    private val repository: MyListRepository,
    private val clock: Clock = Clock.System,
) : ToolHandler {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Snapshot of the id order from the most recent [SHOW_LIST_NAME]
     * call. Updated atomically as part of each list response and cleared
     * whenever a mutation observably changes the list size.
     */
    @Volatile private var lastListedIds: List<String> = emptyList()

    override val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(ADD_ITEM_NAME, ADD_ITEM_SCHEMA),
        ToolDefinition(SHOW_LIST_NAME, SHOW_LIST_SCHEMA),
        ToolDefinition(COMPLETE_ITEM_NAME, COMPLETE_ITEM_SCHEMA),
        ToolDefinition(DELETE_ITEM_NAME, DELETE_ITEM_SCHEMA),
        ToolDefinition(EDIT_ITEM_NAME, EDIT_ITEM_SCHEMA),
        ToolDefinition(CLEAR_COMPLETED_NAME, CLEAR_COMPLETED_SCHEMA),
    )

    private val toolNames: Set<String> = definitions.map { it.name }.toSet()

    override fun handles(toolName: String): Boolean = toolName in toolNames

    override suspend fun execute(call: PendingToolCall): String {
        val args = parseArgs(call.argumentsJson) ?: return errorPayload("could not parse arguments")
        return when (call.name) {
            ADD_ITEM_NAME -> handleAdd(args)
            SHOW_LIST_NAME -> handleList(args)
            COMPLETE_ITEM_NAME -> handleComplete(args)
            DELETE_ITEM_NAME -> handleDelete(args)
            EDIT_ITEM_NAME -> handleEdit(args)
            CLEAR_COMPLETED_NAME -> handleClearCompleted()
            else -> errorPayload("unknown tool '${call.name}'")
        }
    }

    private suspend fun handleAdd(args: JsonObject): String {
        val title = args["title"]?.asStringOrNull()?.trim()
            ?: return errorPayload("'title' is required")
        if (title.isBlank()) return errorPayload("'title' is required")
        val priority = args["priority"]?.asStringOrNull()?.let(::parsePriority) ?: MyListItemPriority.MEDIUM
        val dueDate = args["due_date_epoch_ms"]?.asLong()
        val notes = args["notes"]?.asStringOrNull()
        val now = clock.now().toEpochMilliseconds()
        val item = repository.create(
            id = newItemId(),
            title = title,
            priority = priority,
            dueDateEpochMs = dueDate,
            notes = notes,
            nowEpochMs = now,
        )
        invalidateListCache()
        return resultJson {
            put("status", "ok")
            put("id", item.id)
            put("title", item.title)
            put("priority", item.priority.name)
            item.dueDateEpochMs?.let { put("due_date_epoch_ms", it) }
        }
    }

    private suspend fun handleList(args: JsonObject): String {
        val includeCompleted = args["include_completed"]?.asBoolean() ?: false
        val items = if (includeCompleted) repository.snapshot() else repository.snapshotActive()
        lastListedIds = items.map { it.id }
        return resultJson {
            put("status", "ok")
            put("count", items.size)
            put("include_completed", includeCompleted)
            put("items", buildJsonArray {
                items.forEachIndexed { i, t ->
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
        val target = resolveRef(args) ?: return errorPayload("could not find a matching item")
        val now = clock.now().toEpochMilliseconds()
        val updated = repository.setCompleted(target.id, completed, now)
            ?: return errorPayload("item no longer exists")
        return resultJson {
            put("status", "ok")
            put("id", updated.id)
            put("title", updated.title)
            put("completed", updated.completed)
        }
    }

    private suspend fun handleDelete(args: JsonObject): String {
        val target = resolveRef(args) ?: return errorPayload("could not find a matching item")
        val deleted = repository.delete(target.id)
        if (!deleted) return errorPayload("item no longer exists")
        invalidateListCache()
        return resultJson {
            put("status", "ok")
            put("id", target.id)
            put("title", target.title)
        }
    }

    private suspend fun handleEdit(args: JsonObject): String {
        val target = resolveRef(args) ?: return errorPayload("could not find a matching item")
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
        ) ?: return errorPayload("item no longer exists")
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
     * to an actual [MyListItem]. Returns null when nothing matches; the
     * caller formats that into an error response.
     */
    private suspend fun resolveRef(args: JsonObject): MyListItem? {
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

    private suspend fun matchByTitle(needle: String): MyListItem? {
        val canon = needle.trim().lowercase()
        if (canon.isBlank()) return null
        val all = repository.snapshot()
        // Active rows preferred so "complete the milk" doesn't match an
        // already-completed milk item. If no active match, fall through
        // to completed (so 'reopen the milk' still works).
        val active = all.filter { !it.completed && it.title.lowercase().contains(canon) }
        if (active.size == 1) return active.single()
        if (active.isNotEmpty()) return null // ambiguous: refuse to guess
        val anyMatch = all.filter { it.title.lowercase().contains(canon) }
        return if (anyMatch.size == 1) anyMatch.single() else null
    }

    private fun invalidateListCache() {
        // Mutations always desync the cached indices; clearing here forces
        // a fresh `show_mylist` before the next #N reference can resolve.
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

    private fun newItemId(): String = "mylist-${Uuid.random()}"

    private fun parsePriority(raw: String): MyListItemPriority? = when (raw.trim().uppercase()) {
        "LOW" -> MyListItemPriority.LOW
        "MEDIUM", "MED", "NORMAL" -> MyListItemPriority.MEDIUM
        "HIGH", "URGENT" -> MyListItemPriority.HIGH
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
        const val ADD_ITEM_NAME = "add_mylist_item"
        const val SHOW_LIST_NAME = "show_mylist"
        const val COMPLETE_ITEM_NAME = "complete_mylist_item"
        const val DELETE_ITEM_NAME = "delete_mylist_item"
        const val EDIT_ITEM_NAME = "edit_mylist_item"
        const val CLEAR_COMPLETED_NAME = "clear_completed_mylist_items"

        // Schemas are intentionally terse. The deterministic parser is the
        // canonical entry point for these tools — the LLM rarely (in
        // practice never) sees a My List turn, since intent detection
        // upstream short-circuits with either a deterministic dispatch or a
        // guidance reply. We still ship schemas so the ToolHandler contract
        // stays uniform across domains.
        const val ADD_ITEM_SCHEMA = """{
  "name": "add_mylist_item",
  "description": "Create a My List item.",
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

        const val SHOW_LIST_SCHEMA = """{
  "name": "show_mylist",
  "description": "Show My List items. By default, active (not-completed) only.",
  "parameters": {
    "type": "object",
    "properties": {
      "include_completed": {"type": "boolean", "description": "If true, completed items are included."}
    }
  }
}"""

        const val COMPLETE_ITEM_SCHEMA = """{
  "name": "complete_mylist_item",
  "description": "Mark a My List item as completed (or reopen one). Reference the item by id, index (1-based from the most recent show_mylist), or title substring.",
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string", "description": "Exact id from show_mylist."},
      "index": {"type": "integer", "description": "1-based index from the most recent show_mylist."},
      "title_substring": {"type": "string", "description": "Case-insensitive substring match on title."},
      "completed": {"type": "boolean", "description": "If false, reopen instead of completing. Defaults to true."}
    }
  }
}"""

        const val DELETE_ITEM_SCHEMA = """{
  "name": "delete_mylist_item",
  "description": "Hard-delete a My List item. Reference by id, index, or title substring.",
  "parameters": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "index": {"type": "integer"},
      "title_substring": {"type": "string"}
    }
  }
}"""

        const val EDIT_ITEM_SCHEMA = """{
  "name": "edit_mylist_item",
  "description": "Patch one or more fields on a My List item. Fields omitted from the call are left unchanged.",
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

        const val CLEAR_COMPLETED_SCHEMA = """{
  "name": "clear_completed_mylist_items",
  "description": "Hard-delete every completed My List item.",
  "parameters": {"type": "object", "properties": {}}
}"""
    }
}
