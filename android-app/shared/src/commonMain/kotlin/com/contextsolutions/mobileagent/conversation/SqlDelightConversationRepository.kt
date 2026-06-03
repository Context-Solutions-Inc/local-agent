package com.contextsolutions.mobileagent.conversation

import com.contextsolutions.mobileagent.agent.ChatMessage
import com.contextsolutions.mobileagent.agent.ToolCall
import com.contextsolutions.mobileagent.db.ConversationsQueries
import com.contextsolutions.mobileagent.search.SearchSource
import com.contextsolutions.mobileagent.sync.LocalChangeBus
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * SQLDelight-backed [ConversationRepository]. Thin DAO wrapper — every query
 * is hopped onto [ioDispatcher] (default `Dispatchers.IO`) so the UI never
 * blocks on a DB write.
 *
 * Telemetry: increments stable counters from [CounterNames] for the
 * conversation-lifecycle events (create, resume, delete, overflow). Per
 * CLAUDE.md invariant #27 we DO NOT pass any message content into the
 * counter API — names + tags only.
 */
@OptIn(ExperimentalUuidApi::class)
class SqlDelightConversationRepository(
    private val queries: ConversationsQueries,
    private val counters: TelemetryCounters,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** PR #57 — fired after a genuine local write so [com.contextsolutions.mobileagent.sync.SyncController] pushes it. */
    private val localChangeBus: LocalChangeBus? = null,
) : ConversationRepository {

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun create(
        id: String,
        title: String,
        nowEpochMs: Long,
    ): ConversationRecord = withContext(ioDispatcher) {
        queries.insertConversation(
            id = id,
            title = title,
            created_at_epoch_ms = nowEpochMs,
            updated_at_epoch_ms = nowEpochMs,
        )
        counters.increment(CounterNames.CONVERSATIONS_CREATED_TOTAL)
        evictToCapInternal(ConversationRepository.CONVERSATION_CAP)
        localChangeBus?.notifyChanged()
        ConversationRecord(
            id = id,
            title = title,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            truncationAcknowledgedAtEpochMs = null,
        )
    }

    override suspend fun listRecent(limit: Int): List<ConversationSummary> =
        withContext(ioDispatcher) {
            val rows = queries.selectRecentConversations(limit.toLong()).executeAsList()
            rows.map { row ->
                val preview = queries
                    .selectLastMessagePreview(row.id)
                    .executeAsOneOrNull()
                ConversationSummary(
                    id = row.id,
                    title = row.title,
                    createdAtEpochMs = row.created_at_epoch_ms,
                    updatedAtEpochMs = row.updated_at_epoch_ms,
                    truncationAcknowledgedAtEpochMs = row.truncation_acknowledged_at,
                    lastMessagePreview = preview,
                )
            }
        }

    override suspend fun get(id: String): ConversationRecord? = withContext(ioDispatcher) {
        queries.selectConversationById(id).executeAsOneOrNull()?.let { row ->
            ConversationRecord(
                id = row.id,
                title = row.title,
                createdAtEpochMs = row.created_at_epoch_ms,
                updatedAtEpochMs = row.updated_at_epoch_ms,
                truncationAcknowledgedAtEpochMs = row.truncation_acknowledged_at,
            )
        }
    }

    override suspend fun loadMessages(conversationId: String): List<ChatMessage> =
        withContext(ioDispatcher) {
            queries.selectMessagesByConversation(conversationId).executeAsList()
                .map { row -> rowToChatMessage(row.role, row.content, row.tool_call_json, row.tool_result_json, row.image_bytes, row.render_markdown) }
        }

    override suspend fun appendMessage(
        conversationId: String,
        message: ChatMessage,
        nowEpochMs: Long,
    ): Long = withContext(ioDispatcher) {
        // Compute the next sequence index in the same transaction we write the
        // row, so two concurrent appends from the same conversation cannot
        // collide on the index. (In practice ChatViewModel serialises sends
        // via a single coroutine job, but the DB-level guarantee is cheap.)
        queries.transactionWithResult {
            val nextSeq = queries.selectMessagesByConversation(conversationId)
                .executeAsList()
                .maxOfOrNull { it.sequence_index }
                ?.plus(1L)
                ?: 0L
            val cols = chatMessageToColumns(message)
            queries.insertMessage(
                id = newMessageId(),
                conversation_id = conversationId,
                role = cols.role,
                content = cols.content,
                tool_call_json = cols.toolCallJson,
                tool_result_json = cols.toolResultJson,
                created_at_epoch_ms = nowEpochMs,
                sequence_index = nextSeq,
                image_bytes = cols.imageBytes,
                render_markdown = if (cols.renderMarkdown) 1L else 0L,
            )
            queries.touchUpdatedAt(nowEpochMs = nowEpochMs, id = conversationId)
            nextSeq
        }.also { localChangeBus?.notifyChanged() }
    }

    override suspend fun deleteOldestPair(conversationId: String): Int = withContext(ioDispatcher) {
        queries.transactionWithResult {
            val rows = queries.selectMessagesByConversation(conversationId).executeAsList()
            if (rows.isEmpty()) return@transactionWithResult 0
            // Find the SECOND user message. Everything strictly before its
            // sequence_index is the oldest user+assistant pair (plus any
            // interleaved tool turns).
            val userIndices = rows.withIndex()
                .filter { (_, row) -> row.role == ROLE_USER }
                .map { it.index }
            if (userIndices.size < 2) return@transactionWithResult 0
            val cutoffSeq = rows[userIndices[1]].sequence_index
            val deleted = rows.count { it.sequence_index < cutoffSeq }
            queries.deleteMessagesBelowSequence(
                conversationId = conversationId,
                cutoffSequenceIndex = cutoffSeq,
            )
            counters.increment(CounterNames.CONVERSATION_TURNPAIRS_DROPPED_TOTAL)
            deleted
        }
    }

    override suspend fun acknowledgeTruncation(
        conversationId: String,
        nowEpochMs: Long,
    ): Unit = withContext(ioDispatcher) {
        queries.acknowledgeTruncation(nowEpochMs = nowEpochMs, id = conversationId)
        counters.increment(CounterNames.CONVERSATION_OVERFLOW_WARNED_TOTAL)
    }

    override suspend fun delete(id: String): Unit = withContext(ioDispatcher) {
        // PR #57 — soft-delete (tombstone) so the delete propagates to the paired
        // device instead of the row reappearing on the next sync. Capacity
        // eviction below still hard-deletes (a local-only decision, not synced).
        queries.softDeleteConversation(nowEpochMs = Clock.System.now().toEpochMilliseconds(), id = id)
        counters.increment(CounterNames.CONVERSATIONS_DELETED_TOTAL, tag = TAG_DELETE_EXPLICIT)
        localChangeBus?.notifyChanged()
    }

    override suspend fun evictToCap(maxConversations: Int): Int = withContext(ioDispatcher) {
        evictToCapInternal(maxConversations)
    }

    private fun evictToCapInternal(maxConversations: Int): Int {
        val current = queries.countConversations().executeAsOne()
        if (current <= maxConversations) return 0
        val toEvict = (current - maxConversations).toInt()
        val victims = queries.selectOldestConversationIds(toEvict.toLong()).executeAsList()
        for (victimId in victims) {
            queries.deleteConversation(victimId)
            counters.increment(CounterNames.CONVERSATIONS_DELETED_TOTAL, tag = TAG_DELETE_CAPACITY)
        }
        return victims.size
    }

    private fun newMessageId(): String = "msg-${Uuid.random()}"

    private fun chatMessageToColumns(message: ChatMessage): MessageColumns = when (message) {
        is ChatMessage.User -> MessageColumns(ROLE_USER, message.text, null, null, message.imageBytes)
        is ChatMessage.System -> MessageColumns(ROLE_SYSTEM, message.text, null, null)
        is ChatMessage.Assistant -> {
            val callJson = message.toolCall?.let {
                json.encodeToString(PersistedToolCall.serializer(), PersistedToolCall.from(it))
            }
            // Reuse the existing tool_result_json column for the final
            // assistant's citation list. Assistant rows never carry a tool
            // *result* (those land on tool rows), so the column is free here.
            // The role discriminator keeps the two payload shapes from
            // colliding at deserialise time.
            val citationsJson = message.citations.takeIf { it.isNotEmpty() }?.let { list ->
                json.encodeToString(
                    PersistedCitations.serializer(),
                    PersistedCitations(sources = list),
                )
            }
            // renderMarkdown=false for deterministic weather/finance cards
            // (invariant #32/#33) so they render plain on resume, not markdown.
            MessageColumns(ROLE_ASSISTANT, message.text, callJson, citationsJson, renderMarkdown = message.renderMarkdown)
        }
        is ChatMessage.Tool -> {
            val resultJson = json.encodeToString(
                PersistedToolResult.serializer(),
                PersistedToolResult(callId = message.callId, isError = message.isError),
            )
            // The toolName is stashed in tool_call_json on the Tool row so a
            // round-trip can rebuild ChatMessage.Tool(callId, toolName, ...).
            // We're not introducing a column for it — Tool rows always carry
            // both a callId and a name, so co-locating in tool_result_json is
            // the path of least schema churn for v1.
            MessageColumns(ROLE_TOOL, message.text, message.toolName, resultJson)
        }
    }

    private fun rowToChatMessage(
        role: String,
        content: String,
        toolCallJson: String?,
        toolResultJson: String?,
        imageBytes: ByteArray?,
        renderMarkdown: Long,
    ): ChatMessage = when (role) {
        // PR #49 — image_bytes (if present) re-renders the photo in the bubble
        // on resume. It is display-only: PromptAssembler scopes it to the
        // trailing turn and the engine's history path is text-only (invariant #39).
        ROLE_USER -> ChatMessage.User(content, imageBytes = imageBytes)
        ROLE_SYSTEM -> ChatMessage.System(content)
        ROLE_ASSISTANT -> {
            val call = toolCallJson?.let { raw ->
                runCatching { json.decodeFromString(PersistedToolCall.serializer(), raw) }
                    .getOrNull()
                    ?.toToolCall()
            }
            // Rows written before PR#13's citations-persistence fix have
            // tool_result_json == NULL on assistant rows; decode fails fall
            // back to an empty citation list so the UI just hides the chips.
            val citations = toolResultJson?.let { raw ->
                runCatching { json.decodeFromString(PersistedCitations.serializer(), raw) }
                    .getOrNull()
                    ?.sources
            }.orEmpty()
            ChatMessage.Assistant(
                text = content,
                toolCall = call,
                citations = citations,
                renderMarkdown = renderMarkdown != 0L,
            )
        }
        ROLE_TOOL -> {
            // tool_call_json holds the toolName; tool_result_json holds metadata.
            val toolName = toolCallJson.orEmpty()
            val meta = toolResultJson?.let { raw ->
                runCatching { json.decodeFromString(PersistedToolResult.serializer(), raw) }
                    .getOrNull()
            }
            ChatMessage.Tool(
                callId = meta?.callId ?: "",
                toolName = toolName,
                text = content,
                isError = meta?.isError ?: false,
            )
        }
        else -> ChatMessage.User(content) // safest fallback for unknown roles
    }

    @Serializable
    private data class PersistedToolCall(
        @SerialName("call_id") val callId: String,
        val name: String,
        @SerialName("arguments_json") val argumentsJson: String,
    ) {
        fun toToolCall() = ToolCall(callId = callId, name = name, argumentsJson = argumentsJson)
        companion object {
            fun from(call: ToolCall) = PersistedToolCall(
                callId = call.callId,
                name = call.name,
                argumentsJson = call.argumentsJson,
            )
        }
    }

    @Serializable
    private data class PersistedToolResult(
        @SerialName("call_id") val callId: String,
        @SerialName("is_error") val isError: Boolean = false,
    )

    /**
     * Envelope for the final assistant turn's citation list. Shares the
     * `tool_result_json` column with [PersistedToolResult]; the row's `role`
     * column tells the deserialiser which shape to expect.
     */
    @Serializable
    private data class PersistedCitations(
        val sources: List<SearchSource>,
    )

    private data class MessageColumns(
        val role: String,
        val content: String,
        val toolCallJson: String?,
        val toolResultJson: String?,
        // PR #49 — downscaled JPEG attached to a user turn, persisted for
        // display on resume. null on every non-user row.
        val imageBytes: ByteArray? = null,
        // PR #50 — render the bubble as markdown+LaTeX (true) or plain text
        // (false, for deterministic weather/finance cards). Only meaningful on
        // assistant rows; harmless default on the rest.
        val renderMarkdown: Boolean = true,
    )

    companion object {
        const val ROLE_USER: String = "user"
        const val ROLE_ASSISTANT: String = "assistant"
        const val ROLE_TOOL: String = "tool"
        const val ROLE_SYSTEM: String = "system"

        const val TAG_DELETE_EXPLICIT: String = "explicit"
        const val TAG_DELETE_CAPACITY: String = "capacity"
    }
}
