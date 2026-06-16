package com.contextsolutions.localagent.conversation

import com.contextsolutions.localagent.agent.ChatMessage

/**
 * Persistence + lifecycle for chat conversations and their messages.
 *
 * Backs the multi-conversation feature shipped in PR#13: each conversation
 * has a stable id, a derived title (first user message truncated), and an
 * ordered message log. Capacity is bounded — [evictToCap] trims to the
 * oldest-out policy ([CONVERSATION_CAP] entries by default).
 *
 * The `truncation_acknowledged_at` field (added in `3.sqm`) records the
 * moment a user confirmed the "oldest pair will be dropped" warning for a
 * given conversation. NULL means the dialog must fire on the next overflow;
 * non-null means subsequent overflows truncate silently.
 *
 * **Memory linkage.** Per PR#13 design decision: memories tagged with a
 * conversation_id survive when that conversation is deleted. They are
 * user-level facts, not chat-scoped data — the foreign-key relationship in
 * `memories.conversation_id` is intentionally absent (no `REFERENCES` clause).
 * No explicit memory cleanup happens here.
 */
interface ConversationRepository {

    /**
     * Create a brand-new conversation row. Caller picks the id (typically a
     * UUID) so the UI can reference it before the DB write completes. If
     * inserting this row pushes the store past [CONVERSATION_CAP], the
     * oldest conversations (by `updated_at_epoch_ms`) are evicted in the
     * same call.
     */
    suspend fun create(id: String, title: String, nowEpochMs: Long): ConversationRecord

    /** Latest [limit] conversations, newest-first. Used by ConversationHistoryScreen. */
    suspend fun listRecent(limit: Int = LIST_PAGE_SIZE): List<ConversationSummary>

    /** Read a single conversation row by id, or null if it doesn't exist. */
    suspend fun get(id: String): ConversationRecord?

    /** Load full ordered message list for a conversation (used on resume). */
    suspend fun loadMessages(conversationId: String): List<ChatMessage>

    /**
     * Append [message] at the next monotonically-increasing sequence_index
     * for [conversationId]. Also bumps `updated_at_epoch_ms` so this
     * conversation moves to the top of the history list. Returns the
     * sequence_index assigned to the row.
     */
    suspend fun appendMessage(
        conversationId: String,
        message: ChatMessage,
        nowEpochMs: Long,
    ): Long

    /**
     * Hard-delete the oldest user+assistant turn-pair (plus any tool messages
     * between them, by `sequence_index`) from [conversationId]. Returns the
     * number of messages actually removed (typically 2; can be 3+ if tool
     * turns interleaved). Returns 0 if fewer than 2 user messages exist —
     * caller should not invoke this in that case.
     */
    suspend fun deleteOldestPair(conversationId: String): Int

    /**
     * Mark this conversation's overflow warning as acknowledged. Idempotent:
     * subsequent calls overwrite the timestamp but do not error.
     */
    suspend fun acknowledgeTruncation(conversationId: String, nowEpochMs: Long)

    /** Hard-delete one conversation and (via FK CASCADE) all its messages. */
    suspend fun delete(id: String)

    /**
     * If the store exceeds [maxConversations], delete the oldest conversations
     * (by `updated_at_epoch_ms` ascending) until the count fits. Called
     * automatically from [create]; exposed for testing and for forcing a
     * trim in the rare case a stored conversation count drifts out of sync.
     * Returns the number of conversations evicted.
     */
    suspend fun evictToCap(maxConversations: Int = CONVERSATION_CAP): Int

    companion object {
        /** Maximum number of conversations the store retains. PR#13 design lock. */
        const val CONVERSATION_CAP: Int = 50

        /** Default page size when listing conversations. */
        const val LIST_PAGE_SIZE: Int = 50

        /** Cap on the derived title length, used by [deriveTitle]. */
        const val TITLE_MAX_CHARS: Int = 40

        /**
         * Build a title from the first user message: trim, collapse internal
         * whitespace to single spaces, truncate at [TITLE_MAX_CHARS] (adding
         * an ellipsis if it overflows). Empty input falls back to
         * "New conversation".
         */
        fun deriveTitle(firstUserMessage: String): String {
            val collapsed = firstUserMessage.trim().replace(WHITESPACE_RUN, " ")
            if (collapsed.isEmpty()) return "New conversation"
            return if (collapsed.length <= TITLE_MAX_CHARS) {
                collapsed
            } else {
                collapsed.substring(0, TITLE_MAX_CHARS - 1).trimEnd() + "…"
            }
        }

        private val WHITESPACE_RUN: Regex = Regex("\\s+")
    }
}

/**
 * Full conversation row. The `truncationAcknowledgedAtEpochMs` flag drives
 * whether the overflow dialog fires (null) or the oldest pair is dropped
 * silently (non-null).
 */
data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val truncationAcknowledgedAtEpochMs: Long?,
)

/**
 * Row used by ConversationHistoryScreen — same as [ConversationRecord] plus
 * the last user/assistant message text as a short preview. Tool messages
 * are excluded from the preview (they're noisy and rarely meaningful).
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val truncationAcknowledgedAtEpochMs: Long?,
    val lastMessagePreview: String?,
)
